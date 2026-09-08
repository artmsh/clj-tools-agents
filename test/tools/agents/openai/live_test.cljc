(ns tools.agents.openai.live-test
  "Mock-server / transport-level coverage. The mock server itself lives in
   tools.agents.test-support, shared with the anthropic and gemini suites —
   it is the only runtime-specific piece in any of them. Everything here is
   identical handler logic and identical assertions on both runtimes.

   NOTE every :base-url below deliberately carries the `/v1` segment, and every
   mock route is registered at the full `/v1/...` path — that is the shape of
   openai-python's real default base_url, and testing it any other way would
   let a `.../v1/v1/responses` URL-building regression pass unnoticed."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [examples.openai.basic-chat :as ex-basic]
            [examples.openai.chat-completions :as ex-chat]
            [examples.openai.custom-gateway :as ex-gateway]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- canned-response []
  (str "{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-5.5\",\"status\":\"completed\","
       "\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
       "\"content\":[{\"type\":\"output_text\",\"text\":\"hello back\"}]}],"
       "\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}"))

(defn- canned-completion []
  (str "{\"id\":\"chatcmpl_1\",\"object\":\"chat.completion\",\"model\":\"gpt-5.5\","
       "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ahoy back\"},"
       "\"finish_reason\":\"stop\"}],"
       "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}"))

;; ---------------------------------------------------------------------------
;; Request formation: method/path, required headers, body content
;; ---------------------------------------------------------------------------

(deftest posts-to-v1-responses-with-required-headers
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18950 "/v1/responses"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (oai/client {:api-key "test-key" :base-url (base-url port)})
            resp   (oai/responses-create client {"model" "gpt-5.5" "input" "hi" "max_output_tokens" 10})]
        (is (= "hello back" (oai/output-text resp)))
        (let [req @captured]
          (is (= "POST" (:method req)))
          ;; the /v1 comes from the base-url, NOT from the library — a
          ;; ".../v1/v1/responses" regression would 404 here.
          (is (= "/v1/responses" (:path req)))
          (is (= "Bearer test-key" (get (:headers req) "authorization")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"max_output_tokens\":10"))
          (is (str/includes? (:body req) "\"input\":\"hi\""))
          ;; credentials live on the client, not the request map — they must
          ;; never appear in the outbound JSON body.
          (is (not (str/includes? (:body req) "test-key")))
          (is (not (str/includes? (:body req) "api_key")))))
      (finally (stop!)))))

(deftest org-and-project-headers-omitted-entirely-when-unset
  ;; openai-python emits Omit() for these — the headers must be ABSENT, not
  ;; present-and-empty (which is what an assoc-of-nil would produce).
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18951 "/v1/responses"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (oai/responses-create client {"model" "m" "input" "hi"})
        (is (nil? (get (:headers @captured) "openai-organization")))
        (is (nil? (get (:headers @captured) "openai-project"))))
      (finally (stop!)))))

(deftest org-and-project-headers-sent-when-set
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18952 "/v1/responses"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)
                                :organization "org-abc" :project "proj-xyz"})]
        (oai/responses-create client {"model" "m" "input" "hi"})
        (is (= "org-abc" (get (:headers @captured) "openai-organization")))
        (is (= "proj-xyz" (get (:headers @captured) "openai-project"))))
      (finally (stop!)))))

(deftest base-url-with-trailing-slash-still-builds-correct-path
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18953 "/v1/responses"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (str (base-url port) "/")})]
        (oai/responses-create client {"model" "m" "input" "hi"})
        (is (= "/v1/responses" (:path @captured))))
      (finally (stop!)))))

(deftest chat-completions-posts-to-v1-chat-completions
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18954 "/v1/chat/completions"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-completion)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            resp   (oai/chat-completions-create
                     client {"model" "gpt-5.5"
                             "messages" [{"role" "developer" "content" "Talk like a pirate."}
                                         {"role" "user" "content" "hi"}]})]
        (is (= "ahoy back" (oai/completion-text resp)))
        (is (= "/v1/chat/completions" (:path @captured)))
        (is (str/includes? (:body @captured) "\"role\":\"developer\"")))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Successful decode
;; ---------------------------------------------------------------------------

(deftest decodes-nested-response-into-maps-and-vectors
  (let [{:keys [port stop!]} (start-server! 18955 "/v1/responses"
                                (fn [_] {:status 200
                                         :body (str "{\"id\":\"resp_2\",\"model\":\"gpt-5.5\","
                                                    "\"output\":[{\"type\":\"message\",\"content\":"
                                                    "[{\"type\":\"output_text\",\"text\":\"hi\"}]}],"
                                                    "\"usage\":{\"input_tokens\":1,\"output_tokens\":1},"
                                                    "\"nested\":{\"a\":[1,2,3]}}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            resp   (oai/responses-create client {"model" "m" "input" "hi"})]
        (is (= "resp_2" (get resp "id")))
        (is (vector? (get resp "output")))
        (is (= [1 2 3] (get-in resp ["nested" "a"])))
        (is (= 1 (get-in resp ["usage" "input_tokens"])))
        (is (integer? (get-in resp ["usage" "input_tokens"]))))
      (finally (stop!)))))

(deftest reasoning-only-response-yields-empty-output-text
  ;; End-to-end confirmation of the SDK's documented "" contract — not just
  ;; the pure-unit version in tools.agents.openai-test.
  (let [{:keys [port stop!]} (start-server! 18956 "/v1/responses"
                                (fn [_] {:status 200
                                         :body (str "{\"id\":\"resp_3\",\"status\":\"incomplete\","
                                                    "\"output\":[{\"type\":\"reasoning\",\"summary\":[]}],"
                                                    "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            resp   (oai/responses-create client {"model" "m" "input" "hi" "max_output_tokens" 16})]
        (is (= "" (oai/output-text resp)))
        (is (= "max_output_tokens" (get-in resp ["incomplete_details" "reason"]))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Error paths
;; ---------------------------------------------------------------------------

(deftest non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 18957 "/v1/responses"
                                (fn [_] {:status 429
                                         :body (str "{\"error\":{\"message\":\"Rate limit reached\","
                                                    "\"type\":\"rate_limit_error\",\"param\":null,"
                                                    "\"code\":\"rate_limit_exceeded\"}}")}))]
    (try
      ;; :max-retries 0 — 429 is retryable, and this test is about the terminal
      ;; error's shape, not the retry loop (which has its own tests below).
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/rate-limit-error (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (= 0 (:retries-taken (ex-data e))))
        (is (true? (oai/retryable-status? (:status (ex-data e)))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 Rate limit reached"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.openai/responses-create: ")))
      (finally (stop!)))))

(deftest conflict-409-maps-to-its-own-error-type
  ;; openai-python has a ConflictError class for 409 that anthropic-sdk-python
  ;; has no analogue for — easy to lose in a port of the sibling's status map.
  (let [{:keys [port stop!]} (start-server! 18958 "/v1/responses"
                                (fn [_] {:status 409 :body "{\"error\":{\"message\":\"conflict\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/conflict-error (:type (ex-data e))))
        (is (= 409 (:status (ex-data e)))))
      (finally (stop!)))))

(deftest non-2xx-without-json-error-shape-falls-back-to-raw-body
  (let [{:keys [port stop!]} (start-server! 18959 "/v1/responses"
                                (fn [_] {:status 500 :body "internal explosion, not json"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/internal-server-error (:type (ex-data e))))
        (is (= 500 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "internal explosion, not json")))
      (finally (stop!)))))

(deftest chat-completions-errors-are-labelled-with-their-own-fn-name
  (let [{:keys [port stop!]} (start-server! 18960 "/v1/chat/completions"
                                (fn [_] {:status 400 :body "{\"error\":{\"message\":\"bad model\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (oai/chat-completions-create client {"model" "nope" "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.openai/chat-completions-create: ")))
      (finally (stop!)))))

(deftest malformed-json-response-throws-catchable-parse-error
  (let [{:keys [port stop!]} (start-server! 18961 "/v1/responses"
                                (fn [_] {:status 200 :body "{not valid json"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/json-parse-error (:type (ex-data e)))))
      (finally (stop!)))))

(deftest connection-failure-is-typed-not-leaked
  ;; Nothing is listening on this port at all. :max-retries 0 keeps it instant.
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:18999/v1" :max-retries 0})
        e (try (oai/responses-create client {"model" "m" "input" "hi"})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))
    (is (= 0 (:retries-taken (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Retry loop (openai-python's _base_client policy) against the mock server.
;; Every retryable response carries `retry-after-ms: 1`, so the honored delay
;; is 1ms and these tests cost no measurable wall-clock.
;; ---------------------------------------------------------------------------

(deftest retries-a-429-then-succeeds
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18965 "/v1/responses"
                                (fn [_]
                                  (if (< (swap! hits inc) 3)
                                    {:status 429 :headers {"retry-after-ms" "1"}
                                     :body "{\"error\":{\"message\":\"slow down\"}}"}
                                    {:status 200 :body (canned-response)})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})  ;; default 2 retries
            t0     (System/currentTimeMillis)]
        (is (= "hello back" (oai/output-text (oai/responses-create client {"model" "m" "input" "hi"}))))
        (is (= 3 @hits))
        ;; Pins that the RESPONSE headers actually reach retry-delay-ms, not
        ;; just should-retry?: honoring retry-after-ms costs ~2ms of sleep for
        ;; two retries, while ignoring it would fall back to exponential
        ;; backoff whose jittered FLOOR is 375 + 751 = 1126ms. Without this,
        ;; dropping the headers argument would leave every other assertion
        ;; here passing (verified by mutation: it fails at ~1149ms).
        ;; If this ever flakes on a loaded runner, raise the threshold toward
        ;; 1100 — do not delete it.
        (is (< (- (System/currentTimeMillis) t0) 1000)))
      (finally (stop!)))))

(deftest retries-are-exhausted-then-the-status-error-surfaces
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18966 "/v1/responses"
                                (fn [_] (swap! hits inc)
                                  {:status 503 :headers {"retry-after-ms" "1"}
                                   :body "{\"error\":{\"message\":\"overloaded\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/internal-server-error (:type (ex-data e))))
        (is (= 2 (:retries-taken (ex-data e))))
        ;; 1 initial attempt + 2 retries
        (is (= 3 @hits)))
      (finally (stop!)))))

(deftest non-retryable-status-is-not-retried
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18967 "/v1/responses"
                                (fn [_] (swap! hits inc)
                                  {:status 400 :body "{\"error\":{\"message\":\"bad request\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))
        (is (= 0 (:retries-taken (ex-data e))))
        (is (= 1 @hits)))
      (finally (stop!)))))

(deftest x-should-retry-header-overrides-the-status
  ;; A 400 the server explicitly asks us to retry — and a 500 it asks us not to.
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18968 "/v1/responses"
                                (fn [_]
                                  (if (< (swap! hits inc) 2)
                                    {:status 400 :headers {"x-should-retry" "true" "retry-after-ms" "1"}
                                     :body "{\"error\":{\"message\":\"try again\"}}"}
                                    {:status 200 :body (canned-response)})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (is (= "hello back" (oai/output-text (oai/responses-create client {"model" "m" "input" "hi"}))))
        (is (= 2 @hits)))
      (finally (stop!))))
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18969 "/v1/responses"
                                (fn [_] (swap! hits inc)
                                  {:status 500 :headers {"x-should-retry" "false"}
                                   :body "{\"error\":{\"message\":\"do not retry\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (= :tools.agents.openai/internal-server-error (:type (ex-data e))))
        (is (= 1 @hits)))
      (finally (stop!)))))

(deftest max-retries-zero-disables-retrying
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18970 "/v1/responses"
                                (fn [_] (swap! hits inc)
                                  {:status 429 :headers {"retry-after-ms" "1"} :body "{}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (oai/responses-create client {"model" "m" "input" "hi"})
                   nil (catch Exception e e))]
        (is (= :tools.agents.openai/rate-limit-error (:type (ex-data e))))
        (is (= 1 @hits)))
      (finally (stop!)))))

(deftest chat-completions-retries-too
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18971 "/v1/chat/completions"
                                (fn [_]
                                  (if (< (swap! hits inc) 2)
                                    {:status 429 :headers {"retry-after-ms" "1"} :body "{}"}
                                    {:status 200 :body (canned-completion)})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (is (= "ahoy back" (oai/completion-text
                             (oai/chat-completions-create client {"model" "m" "messages" []}))))
        (is (= 2 @hits)))
      (finally (stop!)))))

(deftest connection-errors-are-retried-then-typed
  ;; Nothing listening at all — the SDK retries transport failures without
  ;; consulting should-retry?. One retry keeps the backoff sleep at ~0.5s.
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:18999/v1" :max-retries 1})
        e (try (oai/responses-create client {"model" "m" "input" "hi"})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (= 1 (:retries-taken (ex-data e))))))

(deftest missing-api-key-throws-before-any-network-activity
  ;; No server started at this port at all — if a network call were ever
  ;; attempted, this would hang/error with a connection failure instead of
  ;; the expected missing-credentials error. (Assumes OPENAI_API_KEY is unset
  ;; in the test environment — see README's testing section.)
  (let [e (try (oai/client {:base-url "http://127.0.0.1:18999/v1"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/missing-credentials (:type (ex-data e))))))


;; ---------------------------------------------------------------------------
;; examples/*.clj wired end-to-end against the mock server
;; ---------------------------------------------------------------------------

(deftest example-a-basic-chat-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18962 "/v1/responses"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (oai/client {:api-key "test-key" :base-url (base-url port)})]
        (is (= "hello back" (ex-basic/run-example client)))
        (is (str/includes? (:body @captured) "\"instructions\":\"You are a coding assistant that talks like a pirate.\"")))
      (finally (stop!)))))

(deftest example-a-streaming-path-is-rejected-cleanly
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1"})]
    (is (= {:caught true :type :tools.agents.openai/streaming-unsupported}
           (ex-basic/run-streaming-example client)))))

(deftest example-b-chat-completions-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18963 "/v1/chat/completions"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-completion)}))]
    (try
      (let [client (oai/client {:api-key "test-key" :base-url (base-url port)})]
        (is (= "ahoy back" (ex-chat/run-example client)))
        (is (str/includes? (:body @captured) "\"content\":\"Talk like a pirate.\""))
        (is (str/includes? (:body @captured) "\"model\":\"gpt-5.5\"")))
      (finally (stop!)))))

(deftest example-c-custom-gateway-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18964 "/v1/responses"
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body (str "{\"output\":[{\"type\":\"message\",\"content\":"
                                              "[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}")}))]
    (try
      (let [client (oai/client {:api-key "gw-key" :base-url (base-url port)
                                :organization "org-abc" :project "proj-xyz"})
            result (ex-gateway/run-example client)]
        (is (= "{}" result))
        (is (= "Bearer gw-key" (get (:headers @captured) "authorization")))
        (is (= "org-abc" (get (:headers @captured) "openai-organization")))
        (is (str/includes? (:body @captured) "\"model\":\"gpt-4o\""))
        (is (str/includes? (:body @captured) "\"format\":{\"type\":\"json_object\"}")))
      (finally (stop!)))))
