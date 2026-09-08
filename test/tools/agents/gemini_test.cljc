(ns tools.agents.gemini-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka. Mock-server / transport-level coverage lives in
   tools.agents.gemini.live-test."
  (:require [clojure.test :refer [deftest is]]
            [tools.agents.gemini :as g]))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

(deftest write-json-scalars
  (is (= "null" (g/write-json nil)))
  (is (= "true" (g/write-json true)))
  (is (= "false" (g/write-json false)))
  (is (= "42" (g/write-json 42)))
  (is (= "1.5" (g/write-json 1.5)))
  (is (= "\"hi\"" (g/write-json "hi")))
  (is (= "\"hi\"" (g/write-json :hi))))

(deftest write-json-string-escaping
  (is (= "\"a\\nb\"" (g/write-json "a\nb")))
  (is (= "\"a\\tb\"" (g/write-json "a\tb")))
  (is (= "\"a\\\"b\"" (g/write-json "a\"b")))
  (is (= "\"a\\\\b\"" (g/write-json "a\\b"))))

(deftest write-json-collections
  (is (= "[1,2,3]" (g/write-json [1 2 3])))
  (is (= "[1,2,3]" (g/write-json '(1 2 3))))
  (is (contains? #{"{\"a\":1,\"b\":2}" "{\"b\":2,\"a\":1}"} (g/write-json {:a 1 "b" 2})))
  (is (= "{\"maxOutputTokens\":10}" (g/write-json {:maxOutputTokens 10}))))

(deftest write-json-rejects-unsupported
  (is (thrown? Exception (g/write-json (fn [])))))

(deftest write-json-rejects-ratios
  (let [e (try (g/write-json {"temperature" 2/3}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.gemini/json-encode-error (:type (ex-data e))))))

(deftest read-json-scalars
  (is (= nil (g/read-json "null")))
  (is (= true (g/read-json "true")))
  (is (= false (g/read-json "false")))
  (is (= 42 (g/read-json "42")))
  (is (integer? (g/read-json "42")))
  (is (= 1.5 (g/read-json "1.5")))
  (is (= "hi" (g/read-json "\"hi\""))))

(deftest read-json-string-escapes
  (is (= "a\nb" (g/read-json "\"a\\nb\"")))
  (is (= "a\tb" (g/read-json "\"a\\tb\"")))
  (is (= "a\"b" (g/read-json "\"a\\\"b\"")))
  (is (= "a\\b" (g/read-json "\"a\\\\b\"")))
  (is (= "é" (g/read-json "\"\\u00e9\""))))

(deftest read-json-collections
  (is (= [1 2 3] (g/read-json "[1,2,3]")))
  (is (vector? (g/read-json "[1,2,3]")))
  (is (= {} (g/read-json "{}")))
  (is (= [] (g/read-json "[]")))
  (is (= {"a" 1 "b" [1 2 {"c" true}]} (g/read-json "{\"a\":1,\"b\":[1,2,{\"c\":true}]}"))))

(deftest read-json-object-keys-are-strings
  (let [decoded (g/read-json "{\"model\":\"x\"}")]
    (is (= "x" (get decoded "model")))
    (is (not (contains? decoded :model)))))

(deftest read-json-rejects-leading-zero-numbers
  (is (thrown? Exception (g/read-json "010"))))

(deftest read-json-malformed-throws
  (let [e (try (g/read-json "{bad") nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.gemini/json-parse-error (:type (ex-data e))))))

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
