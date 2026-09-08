(ns tools.agents.anthropic.live-test
  "Mock-server / transport-level coverage — reproduces the mock-server matrix
   of test/anthropic_test.zig, the test suite of the hardcoded Zig builtin
   this library replaced (that file is not part of this repository). The mock server itself lives in tools.agents.test-support,
   shared with the openai and gemini suites — it is the only
   runtime-specific piece in any of them. Everything here is identical
   handler logic and identical assertions on both runtimes."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [examples.anthropic.basic-chat :as ex-basic]
            [examples.anthropic.eval-harness :as ex-eval]
            [examples.anthropic.custom-gateway :as ex-gateway]
            [examples.anthropic.tool-use :as ex-tool]
            [examples.anthropic.ptc-demo :as ex-ptc]
            [tools.agents.test-support :refer [start-server!]]))

(defn- canned-response []
  "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hello back\"}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}")

;; ---------------------------------------------------------------------------
;; Request formation: method/path, required headers, body content
;; ---------------------------------------------------------------------------

(deftest posts-to-v1-messages-with-required-headers
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18930 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "claude-3-5-haiku-latest" "max_tokens" 10
                                               "messages" [{"role" "user" "content" "hi"}]})]
        (is (= "hello back" (a/output-text resp)))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/messages" (:path req)))
          (is (= "test-key" (get (:headers req) "x-api-key")))
          (is (= "2023-06-01" (get (:headers req) "anthropic-version")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"max_tokens\":10"))
          (is (str/includes? (:body req) "\"messages\""))
          ;; credentials live on the client, not the request map — they must
          ;; never appear in the outbound JSON body.
          (is (not (str/includes? (:body req) "test-key")))
          (is (not (str/includes? (:body req) "api-key")))))
      (finally (stop!)))))

(deftest messages-create-carries-request-id-header-as-metadata
  (let [{:keys [port stop!]} (start-server! 18947 "/v1/messages"
                                (fn [_] {:status 200 :headers {"request-id" "req_abc123"}
                                         :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})]
        (is (= "req_abc123" (a/request-id resp)))
        ;; the header rides as metadata, not a wire-format map key — the
        ;; decoded body itself must stay exactly what the server sent.
        (is (= "msg_1" (get resp "id"))))
      (finally (stop!)))))

(deftest request-id-returns-nil-for-a-hand-built-map
  (is (nil? (a/request-id {"id" "msg_1"}))))

(deftest auth-token-sends-bearer-and-oauth-beta-header
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18931 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:auth-token "tok-abc" :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (let [req @captured]
          (is (= "Bearer tok-abc" (get (:headers req) "authorization")))
          (is (= "oauth-2025-04-20" (get (:headers req) "anthropic-beta")))
          (is (nil? (get (:headers req) "x-api-key")))))
      (finally (stop!)))))

(deftest api-key-client-with-betas-sends-comma-joined-beta-header
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18972 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "k" :betas ["advanced-tool-use-2025-11-20"]
                               :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "advanced-tool-use-2025-11-20" (get (:headers @captured) "anthropic-beta"))))
      (finally (stop!)))))

(deftest auth-token-client-with-betas-combines-oauth-and-requested-flags
  ;; Regression guard: a naive (merge oauth-headers {"anthropic-beta" ...})
  ;; would drop oauth-2025-04-20 the moment :betas was also set, silently
  ;; breaking OAuth auth — see beta-header-value's docstring.
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18973 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:auth-token "tok-abc" :betas ["advanced-tool-use-2025-11-20"]
                               :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "oauth-2025-04-20,advanced-tool-use-2025-11-20"
               (get (:headers @captured) "anthropic-beta"))))
      (finally (stop!)))))

(deftest base-url-with-trailing-slash-still-builds-correct-path
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18932 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port "/")})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "/v1/messages" (:path @captured))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Successful decode
;; ---------------------------------------------------------------------------

(deftest decodes-nested-response-into-maps-and-vectors
  (let [{:keys [port stop!]} (start-server! 18933 "/v1/messages"
                                (fn [_] {:status 200
                                         :body "{\"id\":\"msg_2\",\"model\":\"claude\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"nested\":{\"a\":[1,2,3]}}"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})]
        (is (= "msg_2" (get resp "id")))
        (is (vector? (get resp "content")))
        (is (= [1 2 3] (get-in resp ["nested" "a"])))
        (is (= 1 (get-in resp ["usage" "input_tokens"])))
        (is (integer? (get-in resp ["usage" "input_tokens"]))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Error paths
;; ---------------------------------------------------------------------------

(deftest non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 18934 "/v1/messages"
                                (fn [_] {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"}))]
    (try
      ;; :max-retries 0: 429 is retryable by default and this test is about
      ;; the error SHAPE on a non-2xx response, not retries (which get their
      ;; own dedicated coverage below / in the pure-logic suite), so pin one
      ;; attempt only.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 slow down"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.anthropic/messages-create: ")))
      (finally (stop!)))))

(deftest non-2xx-without-json-error-shape-falls-back-to-raw-body
  (let [{:keys [port stop!]} (start-server! 18935 "/v1/messages"
                                (fn [_] {:status 500 :body "internal explosion, not json"}))]
    (try
      ;; :max-retries 0 — see the comment on non-2xx-throws-with-status-and-
      ;; extracted-message above: 500 is retryable by default.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/internal-server (:type (ex-data e))))
        (is (= 500 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "internal explosion, not json")))
      (finally (stop!)))))

(deftest malformed-json-response-throws-catchable-parse-error
  (let [{:keys [port stop!]} (start-server! 18936 "/v1/messages" (fn [_] {:status 200 :body "{not valid json"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/json-parse (:type (ex-data e)))))
      (finally (stop!)))))

(deftest content-less-response-makes-output-text-throw
  (let [{:keys [port stop!]} (start-server! 18937 "/v1/messages"
                                (fn [_] {:status 200 :body "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\"}]}"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
            e (try (a/output-text resp) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/no-text-content (:type (ex-data e)))))
      (finally (stop!)))))

(deftest missing-api-key-throws-before-any-network-activity
  ;; No server started at this port at all — if a network call were ever
  ;; attempted, this would hang/error with a connection failure instead of
  ;; the expected missing-credentials error.
  (let [e (try (a/client {:base-url "http://127.0.0.1:18999"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/missing-credentials (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; count-tokens — same request/response/error plumbing as messages-create,
;; against a different path.
;; ---------------------------------------------------------------------------

(deftest count-tokens-posts-to-count-tokens-path-and-decodes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18941 "/v1/messages"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"input_tokens\":42}"}))]
    (try
      (let [client   (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp     (a/count-tokens client {"model" "m" "messages" [{"role" "user" "content" "hi"}]})]
        (is (= 42 (get resp "input_tokens")))
        (is (= "/v1/messages/count_tokens" (:path @captured)))
        (is (= "k" (get (:headers @captured) "x-api-key"))))
      (finally (stop!)))))

;; Regression guard: unlike messages-create, count-tokens had exactly one
;; test (the happy path above) -- a copy-paste slip dropping its
;; :max-retries pass-through, or breaking its decode-or-throw!/post-json!
;; error wiring, would have passed the suite clean. Mirrors messages-
;; create's non-2xx-throws-with-status-and-extracted-message.
(deftest count-tokens-non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 18944 "/v1/messages"
                                (fn [_] {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"}))]
    (try
      ;; :max-retries 0 -- same single-shot-mock-server reasoning as
      ;; messages-create's identically-named test above.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/count-tokens client {"model" "m" "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 slow down"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.anthropic/count-tokens: ")))
      (finally (stop!)))))

(deftest count-tokens-respects-client-max-retries-then-succeeds
  (let [attempts (atom 0)
        {:keys [port stop!]} (start-server! 18945 "/v1/messages"
                                (fn [_]
                                  (swap! attempts inc)
                                  (if (< @attempts 3)
                                    {:status 429 :body "{\"error\":{\"message\":\"slow down\"}}"}
                                    {:status 200 :body "{\"input_tokens\":42}"})))]
    (try
      (binding [a/*sleep-fn* (fn [_] nil)]
        (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 5})
              resp   (a/count-tokens client {"model" "m" "messages" []})]
          (is (= 42 (get resp "input_tokens")))
          (is (= 3 @attempts))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Retries end-to-end. The retry LOOP itself (count/backoff/Retry-After
;; selection) already has full zero-I/O coverage in tools.agents.anthropic-
;; test; this only proves the real wiring — client -> messages-create ->
;; request-with-retries! -> a second real HTTP round trip — actually fires.
(deftest retries-then-succeeds-against-real-mock-server
  (let [attempts (atom 0)
        {:keys [port stop!]} (start-server! 18942 "/v1/messages"
                                (fn [_]
                                  (swap! attempts inc)
                                  (if (< @attempts 3)
                                    {:status 429 :body "{\"error\":{\"message\":\"slow down\"}}"}
                                    {:status 200 :body (canned-response)})))]
    (try
      (binding [a/*sleep-fn* (fn [_] nil)]
        (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 5})
              resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})]
          (is (= "hello back" (a/output-text resp)))
          (is (= 3 @attempts))))
      (finally (stop!)))))

;; Regression guard for the "no test covers a real HTTP error after retries
;; are actually exhausted" coverage gap: every other non-2xx test above pins
;; :max-retries 0, and the pure-logic retry-exhaustion tests in
;; anthropic_test.cljc use a fake attempt-fn whose ex-data never includes
;; :body — so nothing anywhere exercised "a real HTTP response, retried,
;; then exhausted" together.
(deftest non-2xx-after-retries-exhausted-still-carries-status-and-body
  (let [attempts (atom 0)
        {:keys [port stop!]} (start-server! 18946 "/v1/messages"
                                (fn [_]
                                  (swap! attempts inc)
                                  {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"still slow\"}}"}))]
    (try
      (binding [a/*sleep-fn* (fn [_] nil)]
        (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 2})
              e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                     nil (catch Exception e e))]
          (is (some? e))
          (is (= 3 @attempts)) ;; 1 initial + 2 retries, all exhausted
          (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
          (is (= 429 (:status (ex-data e))))
          (is (str/includes? (str (:body (ex-data e))) "still slow"))
          (is (str/includes? (str (ex-message e)) "HTTP 429 still slow"))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; examples/*.clj wired end-to-end against the mock server
;; ---------------------------------------------------------------------------

(deftest example-a-basic-chat-runs-against-mock-server
  (let [{:keys [port stop!]} (start-server! 18938 "/v1/messages"
                                (fn [_] {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})]
        (is (= "hello back" (ex-basic/run-example client))))
      (finally (stop!)))))

(deftest example-a-streaming-path-is-rejected-cleanly
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})]
    (is (= {:caught true :type :tools.agents.anthropic.error/streaming-unsupported}
           (ex-basic/run-streaming-example client)))))

(deftest example-b-eval-harness-prefill-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18939 "/v1/messages"
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}]}"}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            result (ex-eval/run-example client)]
        (is (= "{\"a\":1,\"b\":2}" result))
        ;; prefill: the assistant turn we seeded must be in the outbound request
        (is (str/includes? (:body @captured) "```json"))
        (is (str/includes? (:body @captured) "\"stop_sequences\":[\"```\"]"))
        (is (str/includes? (:body @captured) "\"thinking\":{\"type\":\"disabled\"}")))
      (finally (stop!)))))

(deftest example-c-custom-gateway-auth-token-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18940 "/v1/messages"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}"}))]
    (try
      (let [client (a/client {:auth-token "gw-token" :base-url (str "http://127.0.0.1:" port)})
            result (ex-gateway/run-example client)]
        (is (= "{}" result))
        (is (= "Bearer gw-token" (get (:headers @captured) "authorization")))
        (is (str/includes? (:body @captured) "claude-sonnet-4-5-20250929")))
      (finally (stop!)))))

;; Two sequential requests: a tool_use round, then the final text round.
(deftest example-d-tool-use-runs-against-mock-server
  (let [requests (atom [])
        {:keys [port stop!]} (start-server! 18943 "/v1/messages"
                                (fn [req]
                                  (swap! requests conj req)
                                  (if (= 1 (count @requests))
                                    {:status 200
                                     :body (str "{\"stop_reason\":\"tool_use\",\"content\":["
                                                 "{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                                                 "\"name\":\"get_weather\",\"input\":{\"location\":\"San Francisco\"}}]}")}
                                    {:status 200
                                     :body "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"It's 72F and sunny.\"}]}"})))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            result (ex-tool/run-example client)]
        (is (= "It's 72F and sunny." result))
        (is (= 2 (count @requests)))
        ;; 2nd request must carry the tool_result keyed to the 1st response's tool_use id
        (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_1\""))
        (is (str/includes? (:body (second @requests)) "72F and sunny in San Francisco")))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; examples/ptc_demo.clj — Programmatic Tool Calling, 3-turn mock sequence:
;; turn 1 (direct-caller code_execution + a code-execution-caller tool_use),
;; turn 2 (two more code-execution-caller tool_use calls, no "container" key
;; this time — must carry over from turn 1's response), turn 3 (end_turn).
;; ---------------------------------------------------------------------------

(deftest example-e-ptc-runs-against-mock-server-with-container-and-caller-routing
  (let [requests (atom [])
        turn-1 (str "{\"stop_reason\":\"tool_use\","
                    "\"container\":{\"id\":\"cntr_abc123\",\"expires_at\":\"2024-01-01T00:05:00Z\"},"
                    "\"usage\":{\"input_tokens\":500,\"output_tokens\":50},"
                    "\"content\":[{\"type\":\"text\",\"text\":\"Writing code to analyze this.\"},"
                    "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"code_execution\","
                    "\"caller\":{\"type\":\"direct\"},\"input\":{\"code\":\"...\"}},"
                    "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_team_members\","
                    "\"caller\":{\"type\":\"direct\"},\"input\":{\"department\":\"engineering\"}}]}")
        turn-2 (str "{\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":300,\"output_tokens\":40},"
                    "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"get_expenses\","
                    "\"caller\":{\"type\":\"code_execution_20250825\"},"
                    "\"input\":{\"employee_id\":\"ENG002\",\"quarter\":\"Q3\"}},"
                    "{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"get_custom_budget\","
                    "\"caller\":{\"type\":\"code_execution_20250825\"},\"input\":{\"user_id\":\"ENG002\"}}]}")
        turn-3 (str "{\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":100,\"output_tokens\":60},"
                    "\"content\":[{\"type\":\"code_execution_tool_result\","
                    "\"content\":{\"stdout\":\"done\",\"return_code\":0}},"
                    "{\"type\":\"text\",\"text\":\"ENG002 exceeded the standard budget but is within their custom limit.\"}]}")
        {:keys [port stop!]} (start-server! 18974 "/v1/messages"
                                (fn [req]
                                  (swap! requests conj req)
                                  {:status 200
                                   :body (case (count @requests) 1 turn-1 2 turn-2 turn-3)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            result (ex-ptc/run-agent-with-ptc client "irrelevant query")]
        (is (= 3 (count @requests)))
        (is (= "ENG002 exceeded the standard budget but is within their custom limit." (:text result)))
        (is (= 3 (:api-calls result)))
        (is (= 1050 (:total-tokens result))) ;; (500+50) + (300+40) + (100+60)
        (is (= "cntr_abc123" (:container-id result)))
        (is (= [{:name "get_team_members" :caller-type "direct"}
                {:name "get_expenses" :caller-type "code_execution_20250825"}
                {:name "get_custom_budget" :caller-type "code_execution_20250825"}]
               (:caller-log result)))
        ;; turn 1's request must carry the PTC beta header and ptc-tools
        ;; (allowed_callers on our tools, the code_execution server tool
        ;; appended)
        (is (= "advanced-tool-use-2025-11-20" (get (:headers (first @requests)) "anthropic-beta")))
        (is (str/includes? (:body (first @requests)) "\"allowed_callers\":[\"code_execution_20250825\"]"))
        (is (str/includes? (:body (first @requests))
                            "\"type\":\"code_execution_20250825\",\"name\":\"code_execution\""))
        ;; turn 2's request must carry the container id from turn 1's
        ;; response, and toolu_1's real tool_result — dispatched to the
        ;; ACTUAL mock expense API (examples.anthropic.ptc-expense-api), not a stub,
        ;; proving run-tool/local-tool-names routing actually reaches it
        (is (str/includes? (:body (second @requests)) "\"container\":\"cntr_abc123\""))
        (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_1\""))
        (is (str/includes? (:body (second @requests)) "ENG001"))
        ;; turn 3's request must carry BOTH turn-2 tool_results in a
        ;; SINGLE user turn (add-tool-results' contract)
        (is (str/includes? (:body (nth @requests 2)) "\"tool_use_id\":\"toolu_2\""))
        (is (str/includes? (:body (nth @requests 2)) "ENG002_Q3_00"))
        (is (str/includes? (:body (nth @requests 2)) "\"tool_use_id\":\"toolu_3\""))
        (is (str/includes? (:body (nth @requests 2)) "ENG002")))
      (finally (stop!)))))

(deftest example-f-baseline-without-ptc-runs-against-mock-server
  (let [requests (atom [])
        {:keys [port stop!]} (start-server! 18975 "/v1/messages"
                                (fn [req]
                                  (swap! requests conj req)
                                  (if (= 1 (count @requests))
                                    {:status 200
                                     :body (str "{\"stop_reason\":\"tool_use\","
                                                 "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},"
                                                 "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_9\","
                                                 "\"name\":\"get_team_members\","
                                                 "\"input\":{\"department\":\"engineering\"}}]}")}
                                    {:status 200
                                     :body (str "{\"stop_reason\":\"end_turn\","
                                                 "\"usage\":{\"input_tokens\":8,\"output_tokens\":4},"
                                                 "\"content\":[{\"type\":\"text\",\"text\":\"Nobody exceeded budget.\"}]}")})))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            result (ex-ptc/run-agent-without-ptc client "irrelevant query")]
        (is (= 2 (count @requests)))
        (is (= "Nobody exceeded budget." (:text result)))
        (is (= 2 (:api-calls result)))
        (is (= 27 (:total-tokens result)))
        (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_9\""))
        (is (str/includes? (:body (second @requests)) "ENG001")))
      (finally (stop!)))))
