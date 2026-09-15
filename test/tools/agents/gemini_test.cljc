(ns tools.agents.gemini-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka. Mock-server / transport-level coverage lives in
   tools.agents.gemini.live-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.gemini :as g]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [recording-http input-stream recording-codec throwing-codec]]))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

;; Codec behaviour is tested once, in tools.agents.json-test. This pins the
;; wrapper wiring and this namespace's documented error contract.

(deftest json-codec-error-contract
  (is (= {"a" [1 "x"]} (g/read-json (g/write-json {:a [1 "x"]}))))
  (testing "every character below 0x20 is \\u00XX-escaped"
    (is (= "\"a\\u0001b\"" (g/write-json (str "a" (char 1) "b")))))
  (testing "encode"
    (let [e (try (g/write-json {"n" 2/3}) nil (catch Exception e e))]
      (is (= :tools.agents.gemini/json-encode-error (:type (ex-data e))))
      (is (clojure.string/starts-with? (ex-message e) "tools.agents.gemini/write-json: "))))
  (testing "parse"
    (doseq [bad ["{bad" "010"]]
      (let [e (try (g/read-json bad) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/json-parse-error (:type (ex-data e))) bad)
        (is (clojure.string/starts-with? (ex-message e) "tools.agents.gemini/read-json: ") bad)))))

;; ---------------------------------------------------------------------------
;; Credential resolution
;; ---------------------------------------------------------------------------

(deftest resolve-credentials-explicit-api-key-wins
  (is (= {:api-key "explicit"}
         (g/resolve-credentials {:api-key "explicit"} (fn [_] "from-env")))))

(deftest resolve-credentials-falls-back-to-google-api-key
  (is (= {:api-key "g-key"}
         (g/resolve-credentials {} (fn [n] (when (= n "GOOGLE_API_KEY") "g-key"))))))

(deftest resolve-credentials-falls-back-to-gemini-api-key
  (is (= {:api-key "gem-key"}
         (g/resolve-credentials {} (fn [n] (when (= n "GEMINI_API_KEY") "gem-key"))))))

(deftest resolve-credentials-google-api-key-env-beats-gemini-api-key-env
  (is (= {:api-key "g-key"}
         (g/resolve-credentials {} (fn [n] (case n "GOOGLE_API_KEY" "g-key" "GEMINI_API_KEY" "gem-key" nil))))))

(deftest resolve-credentials-throws-before-any-network-call
  (let [e (try (g/resolve-credentials {} (fn [_] nil)) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.gemini/missing-credentials (:type (ex-data e))))))

(deftest client-returns-gemini-client-record
  (let [client (g/client {:api-key "k"})]
    (is (record? client))
    (is (= "k" (:api-key client)))
    (is (record? (assoc client :base-url "http://example.test")))))

;; ---------------------------------------------------------------------------
;; output-text
;; ---------------------------------------------------------------------------

(deftest output-text-joins-text-parts
  (is (= "hello world"
         (g/output-text {"candidates" [{"content" {"parts" [{"text" "hello "} {"text" "world"}]}}]}))))

(deftest output-text-skips-thought-parts
  (is (= "answer"
         (g/output-text {"candidates" [{"content" {"parts" [{"text" "reasoning..." "thought" true}
                                                              {"text" "answer"}]}}]}))))

(deftest output-text-nil-when-no-candidates
  (is (nil? (g/output-text {})))
  (is (nil? (g/output-text {"candidates" []}))))

(deftest output-text-nil-when-no-text-parts
  (is (nil? (g/output-text {"candidates" [{"content" {"parts" [{"functionCall" {"name" "f"}}]}}]}))))

(deftest output-text-nil-when-content-missing
  (is (nil? (g/output-text {"candidates" [{}]}))))

;; ---------------------------------------------------------------------------
;; Retry policy
;; ---------------------------------------------------------------------------

(deftest retryable-status-matches-python-genai-set
  (is (every? true? (map g/retryable-status? [408 429 500 502 503 504])))
  (is (not (g/retryable-status? 409)))
  (is (not (g/retryable-status? 400)))
  (is (not (g/retryable-status? 404)))
  (is (not (g/retryable-status? nil))))

(deftest retry-delay-ms-exponential-with-jitter
  ;; n=0: initial(1000) * 2^0 + jitter[0,1000) => [1000,2000)
  (is (<= 1000 (g/retry-delay-ms 0 (constantly 0.0)) 1999))
  (is (= 1999 (g/retry-delay-ms 0 (constantly 0.999))))
  ;; n=1: 1000*2 = 2000 + jitter
  (is (<= 2000 (g/retry-delay-ms 1 (constantly 0.0))))
  ;; n=6: 1000*64=64000, capped at max 60000 regardless of jitter
  (is (= 60000 (g/retry-delay-ms 6 (constantly 0.999)))))

;; ---------------------------------------------------------------------------
;; Contents-list helpers
;; ---------------------------------------------------------------------------

(deftest add-user-message-appends
  (is (= [{"role" "user" "parts" [{"text" "hi"}]}]
         (g/add-user-message [] "hi"))))

(deftest add-model-message-appends
  (is (= [{"role" "model" "parts" [{"text" "hi there"}]}]
         (g/add-model-message [] "hi there"))))

(deftest contents-helpers-compose
  (is (= [{"role" "user" "parts" [{"text" "hi"}]}
          {"role" "model" "parts" [{"text" "hello"}]}]
         (-> [] (g/add-user-message "hi") (g/add-model-message "hello")))))

;; ---------------------------------------------------------------------------
;; Stream accumulation (pure)
;; ---------------------------------------------------------------------------

(defn- cand [parts & {:as extra}]
  (merge {"content" {"role" "model" "parts" parts} "index" 0} extra))

(deftest accumulate-chunk-arities
  (is (nil? (g/accumulate-chunk)))
  (is (= {"a" 1} (g/accumulate-chunk {"a" 1})))
  (is (nil? (g/accumulate-stream [])))
  (is (= "ab" (g/output-text (transduce identity g/accumulate-chunk
                                        [{"candidates" [(cand [{"text" "a"}])]}
                                         {"candidates" [(cand [{"text" "b"}])]}])))))

(deftest accumulate-keeps-thought-and-answer-text-apart
  (let [r (g/accumulate-stream
           [{"candidates" [(cand [{"text" "think " "thought" true}])]}
            {"candidates" [(cand [{"text" "more" "thought" true}])]}
            {"candidates" [(cand [{"text" "Ans" "thoughtSignature" "sig1"}])]}
            {"candidates" [(cand [{"text" "wer"}])]}
            {"candidates" [(cand [{"text" "." "thoughtSignature" "sig2"}] "finishReason" "STOP")]}])]
    (is (= [{"text" "think more" "thought" true}
            {"text" "Answer." "thoughtSignature" "sig2"}]
           (get-in r ["candidates" 0 "content" "parts"])))
    (is (= "Answer." (g/output-text r)))))

(deftest accumulate-non-text-parts-arrive-whole
  (let [fc {"functionCall" {"name" "get_weather" "args" {"city" "Kyiv"}}}
        r  (g/accumulate-stream
            [{"candidates" [(cand [{"text" "Let me check. "}])]}
             {"candidates" [(cand [fc])]}
             {"candidates" [(cand [{"text" "Done"}] "finishReason" "STOP")]}])]
    (is (= [{"text" "Let me check. "} fc {"text" "Done"}]
           (get-in r ["candidates" 0 "content" "parts"])))))

(deftest accumulate-joins-adjacent-code-execution-parts
  (let [r (g/accumulate-stream
           [{"candidates" [(cand [{"executableCode" {"language" "PYTHON" "code" "print("}}])]}
            {"candidates" [(cand [{"executableCode" {"language" "PYTHON" "code" "1)"}}])]}
            {"candidates" [(cand [{"codeExecutionResult" {"outcome" "OUTCOME_UNSPECIFIED" "output" "1"}}])]}
            {"candidates" [(cand [{"codeExecutionResult" {"outcome" "OUTCOME_OK" "output" "\n"}}])]}])]
    (is (= [{"executableCode" {"language" "PYTHON" "code" "print(1)"}}
            {"codeExecutionResult" {"outcome" "OUTCOME_OK" "output" "1\n"}}]
           (get-in r ["candidates" 0 "content" "parts"])))))

(deftest accumulate-groups-candidates-by-index-into-a-sorted-vector
  (let [r (g/accumulate-stream
           [{"candidates" [{"index" 1 "content" {"role" "model" "parts" [{"text" "B1"}]}}]}
            {"candidates" [{"content" {"role" "model" "parts" [{"text" "A1"}]}}
                           {"index" 1 "content" {"parts" [{"text" "B2"}]}}]}
            {"candidates" [{"index" 0 "content" {"parts" [{"text" "A2"}]} "finishReason" "STOP"}
                           {"index" 1 "finishReason" "MAX_TOKENS"
                            "safetyRatings" [{"category" "HARM_CATEGORY_HARASSMENT" "probability" "LOW"}]}]}])
        [a b] (get r "candidates")]
    (is (vector? (get r "candidates")))
    (is (= "A1A2" (g/output-text r)) "output-text reads candidate index 0")
    (is (= [{"text" "A1A2"}] (get-in a ["content" "parts"])))
    (is (= [{"text" "B1B2"}] (get-in b ["content" "parts"])))
    (is (= "model" (get-in b ["content" "role"])) "role from the first chunk that had one")
    (is (= "MAX_TOKENS" (get b "finishReason")))
    (is (= "LOW" (get-in b ["safetyRatings" 0 "probability"])))
    (is (g/stream-complete? r))
    (is (false? (g/stream-complete? (update r "candidates" #(assoc-in % [1 "finishReason"] nil))))
        "every candidate needs a finishReason")))

(deftest accumulate-top-level-fields
  (let [r (g/accumulate-stream
           [{"candidates" [(cand [{"text" "a"}])] "usageMetadata" {"totalTokenCount" 3}
             "modelVersion" "m1" "responseId" "r" "promptFeedback" {"safetyRatings" []}}
            {"candidates" [(cand [{"text" "b"}])] "usageMetadata" {"totalTokenCount" 5}
             "promptFeedback" {"blockReason" "later"}}
            {"candidates" [(cand [] "finishReason" "STOP")] "modelVersion" "m2"}])]
    (is (= {"totalTokenCount" 5} (get r "usageMetadata")) "last non-nil")
    (is (= "m2" (get r "modelVersion")))
    (is (= "r" (get r "responseId")))
    (is (= {"safetyRatings" []} (get r "promptFeedback")) "first promptFeedback wins")
    (is (= "ab" (g/output-text r)))))

(deftest accumulate-blocked-prompt
  (let [r (g/accumulate-stream [{"promptFeedback" {"blockReason" "SAFETY"}
                                 "usageMetadata" {"promptTokenCount" 4}}])]
    (is (nil? (g/output-text r)))
    (is (g/stream-complete? r))
    (is (false? (g/stream-complete? {})))
    (is (false? (g/stream-complete? nil)))
    (is (false? (g/stream-complete? {"candidates" []})))))

(deftest accumulate-throws-typed-on-an-error-chunk
  (let [e (try (g/accumulate-stream [{"candidates" [(cand [{"text" "a"}])]}
                                     {"error" {"code" 429 "message" "quota" "status" "RESOURCE_EXHAUSTED"}}])
               nil (catch Exception e e))]
    (is (= :tools.agents.gemini/resource-exhausted-error (:type (ex-data e))))
    (is (= 429 (:status (ex-data e))))
    (is (str/starts-with? (ex-message e) "tools.agents.gemini/accumulate-chunk: stream error 429 quota")))
  (let [e (try (g/accumulate-chunk nil {"error" {"message" "no code"}}) nil (catch Exception e e))]
    (is (= :tools.agents.gemini/api-status-error (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Injected :http (#10) — every exchange goes through the caller's fn
;; ---------------------------------------------------------------------------

(defn- fake-client [http & {:as extra}]
  (g/client (merge {:api-key "k" :base-url "https://fake.example" :http http} extra)))

(deftest injected-http-carries-generate-content
  (let [{:keys [http calls]} (recording-http
                              (fn [_] {:status 200 :headers {}
                                       :body "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}"}))
        r (g/generate-content (fake-client http) "gemini-x" {"contents" []})]
    (is (= "hi" (g/output-text r)))
    (let [[req & more] @calls]
      (is (empty? more))
      (is (= :post (:method req)))
      (is (= "https://fake.example/v1beta/models/gemini-x:generateContent" (:url req)))
      (is (= "k" (get (:headers req) "x-goog-api-key")))
      (is (= {"contents" []} (g/read-json (:body req)))))))

(deftest injected-http-carries-a-stream
  (let [sse (slurp "test/resources/sse/gemini-text.sse")]
    (doseq [[label body-fn] [["InputStream body" input-stream] ["String body (lenient)" identity]]]
      (testing label
        (let [{:keys [http calls]} (recording-http (fn [_] {:status 200
                                                            :headers {"content-type" "text/event-stream"}
                                                            :body (body-fn sse)}))
              s (g/generate-content-stream (fake-client http) "gemini-x" {"contents" []})
              r (g/accumulate-stream s)]
          (is (= "Once upon a time, in a town with a magic backpack..." (g/output-text r)))
          (is (g/stream-complete? r))
          (is (= :eof (stream/outcome s)))
          (is (= :stream (:as (first @calls))))
          (is (= "https://fake.example/v1beta/models/gemini-x:streamGenerateContent?alt=sse"
                 (:url (first @calls)))))))))

(deftest injected-http-transport-failure-is-retried-then-typed
  (let [{:keys [http calls]} (recording-http (fn [_] (throw (java.io.IOException. "reset"))))
        e (binding [g/*sleep-fn* (fn [_])]
            (try (g/generate-content (fake-client http :max-retries 1) "m" {}) nil
                 (catch Exception e e)))]
    (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
    (is (= 2 (count @calls)))))

(deftest invalid-http-option-is-rejected-at-construction
  (doseq [bad [nil "request!" {:method :get} :http]]
    (let [e (try (g/client {:api-key "k" :http bad}) nil (catch Exception e e))]
      (is (= :tools.agents.gemini/invalid-options (:type (ex-data e))) (pr-str bad))))
  (is (not (contains? (g/client {:api-key "k"}) :http)) "absent unless injected"))

;; ---------------------------------------------------------------------------
;; Injected :json (#10)
;; ---------------------------------------------------------------------------

(deftest injected-json-encodes-and-decodes-the-wire
  (let [{:keys [json reads writes]} (recording-codec)
        {:keys [http calls]} (recording-http
                              (fn [req]
                                {:status 200 :headers {}
                                 :body (if (= :stream (:as req))
                                         (input-stream (slurp "test/resources/sse/gemini-text.sse"))
                                         "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}")}))
        c (fake-client http :json json)]
    (is (= "hi" (g/output-text (g/generate-content c "m" {"contents" []}))))
    (is (= [1 1] [@writes @reads]))
    (is (str/starts-with? (:body (first @calls)) " "))
    (is (g/stream-complete? (g/accumulate-stream (g/generate-content-stream c "m" {"contents" []}))))
    (is (< 1 @reads) "stream chunks decode through the codec")))

(deftest injected-json-errors-are-this-namespaces-types
  (let [{:keys [http]} (recording-http (fn [_] {:status 200 :headers {} :body "{}"}))
        thrown (fn [f] (try (f) nil (catch Exception e e)))
        enc (thrown #(g/generate-content (fake-client http :json throwing-codec) "m" {}))
        dec (thrown #(g/generate-content (fake-client http :json (assoc throwing-codec :write g/write-json)) "m" {}))]
    (is (= :tools.agents.gemini/json-encode-error (:type (ex-data enc))))
    (is (= "codec write boom" (ex-message (ex-cause enc))))
    (is (= :tools.agents.gemini/json-parse-error (:type (ex-data dec))))
    (is (= "codec read boom" (ex-message (ex-cause dec))))))

(deftest invalid-json-option-is-rejected-at-construction
  (let [e (try (g/client {:api-key "k" :json {:read g/read-json}}) nil (catch Exception e e))]
    (is (= :tools.agents.gemini/invalid-options (:type (ex-data e))))))
