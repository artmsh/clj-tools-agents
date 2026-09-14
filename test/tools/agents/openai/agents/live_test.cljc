(ns tools.agents.openai.agents.live-test
  "Mock-server / transport-level coverage for tools.agents.openai.agents. The
   mock server itself is tools.agents.test-support, shared with the
   anthropic/gemini/openai suites. Everything here is identical handler logic
   and identical assertions on both runtimes.

   Port range 19000-19039 — chosen not to collide with the sibling suites'
   ranges (anthropic 18930-18975, gemini 18980-18997, openai 18950-18971)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.agents :as agents]
            [examples.openai.agents-sandbox-task :as ex-task]
            [examples.openai.agents-self-hosted :as ex-self-hosted]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- parse-query
  "Raw \"k=v&k2=v2\" query string -> {k v} map, order-independent — both
   babashka.http-client and this repo's own query-string builder iterate a
   Clojure map in unspecified order, so tests compare parsed params rather
   than an exact query string."
  [query]
  (into {} (map #(str/split % #"=" 2) (str/split (or query "") #"&"))))

(defn- canned-session
  ([id status] (canned-session id status nil))
  ([id status environment]
   (str "{\"id\":\"" id "\",\"object\":\"agent.session\",\"status\":\"" status "\""
        (when environment (str ",\"environment\":" environment))
        "}")))

(defn- canned-items [assistant-text]
  (str "{\"object\":\"list\",\"data\":[{\"type\":\"message\",\"role\":\"assistant\","
       "\"content\":[{\"type\":\"output_text\",\"text\":\"" assistant-text "\"}]}],"
       "\"has_more\":false}"))

(def ^:private credit-error
  "{\"code\":\"credit_balance_exhausted\",\"message\":\"You have no credits remaining.\"}")

(defn- canned-turn [id status error-json]
  (str "{\"id\":\"" id "\",\"object\":\"agent.session.turn\",\"session_id\":\"sess_1\","
       "\"subagent_id\":null,\"status\":\"" status "\",\"error\":" (or error-json "null") "}"))

(defn- canned-turns [turn-jsons]
  (str "{\"object\":\"list\",\"data\":[" (str/join "," turn-jsons) "],\"has_more\":false}"))

;; ---------------------------------------------------------------------------
;; sessions-create
;; ---------------------------------------------------------------------------

(deftest sessions-create-posts-with-required-headers-and-body
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19000 "/v1/agents/sessions"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-session "sess_1" "in_progress")}))]
    (try
      (let [client  (oai/client {:api-key "test-key" :base-url (base-url port)})
            session (agents/sessions-create client
                      {"agent" {"model" "gpt-6-astra" "instructions" "Write clean code."}
                       "environment" {"type" "openai_hosted"}
                       "input" "hi"})]
        (is (= "sess_1" (get session "id")))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/agents/sessions" (:path req)))
          (is (= "Bearer test-key" (get (:headers req) "authorization")))
          (is (= "agents=v1" (get (:headers req) "openai-beta")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"model\":\"gpt-6-astra\""))
          (is (str/includes? (:body req) "\"type\":\"openai_hosted\""))
          (is (str/includes? (:body req) "\"input\":\"hi\""))
          (is (not (str/includes? (:body req) "test-key")))))
      (finally (stop!)))))

(deftest sessions-create-org-and-project-headers-forwarded
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19001 "/v1/agents/sessions"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-session "sess_1" "idle")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :organization "org-abc" :project "proj-xyz"})]
        (agents/sessions-create client {"environment" {"type" "none"} "input" "hi"})
        (is (= "org-abc" (get (:headers @captured) "openai-organization")))
        (is (= "proj-xyz" (get (:headers @captured) "openai-project"))))
      (finally (stop!)))))

(deftest sessions-create-rejects-stream-true-before-any-network-activity
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:19999/v1"})
        e (try (agents/sessions-create client {"environment" {"type" "none"} "input" "hi" "stream" true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))))

(deftest sessions-create-rejects-keyword-stream-key-too
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:19999/v1"})
        e (try (agents/sessions-create client {:stream true}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; sessions-retrieve / sessions-list / sessions-delete
;; ---------------------------------------------------------------------------

(deftest sessions-retrieve-gets-by-id
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19002 "/v1/agents/sessions/sess_42"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-session "sess_42" "idle")}))]
    (try
      (let [client  (oai/client {:api-key "k" :base-url (base-url port)})
            session (agents/sessions-retrieve client "sess_42")]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_42" (:path @captured)))
        (is (= "idle" (get session "status"))))
      (finally (stop!)))))

(deftest sessions-list-sends-query-params
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19003 "/v1/agents/sessions"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"object\":\"list\",\"data\":[],\"has_more\":false}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/sessions-list client {"limit" 20 "order" "desc"})
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions" (:path @captured)))
        (is (= {"limit" "20" "order" "desc"} (parse-query (:query @captured)))))
      (finally (stop!)))))

(deftest sessions-list-encodes-keyword-query-values-via-name-not-print-form
  ;; Regression: a keyword VALUE must encode the same way a keyword KEY does
  ;; (via `name`) — encoding it via `str` would send a literal leading colon
  ;; (":desc" -> "%3Adesc"), a garbage value OpenAI would receive silently.
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19020 "/v1/agents/sessions"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"object\":\"list\",\"data\":[],\"has_more\":false}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/sessions-list client {"limit" 20 "order" :desc})
        (is (= {"limit" "20" "order" "desc"} (parse-query (:query @captured)))))
      (finally (stop!)))))

(deftest sessions-list-with-no-params-sends-no-query-string
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19004 "/v1/agents/sessions"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"object\":\"list\",\"data\":[],\"has_more\":false}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/sessions-list client)
        (is (nil? (:query @captured))))
      (finally (stop!)))))

(deftest sessions-delete-sends-delete
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19005 "/v1/agents/sessions/sess_1"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"id\":\"sess_1\",\"object\":\"agent.session.deleted\",\"deleted\":true}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (agents/sessions-delete client "sess_1")]
        (is (= "DELETE" (:method @captured)))
        (is (true? (get result "deleted"))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; events: send-message / cancel-turn / send-tool-result
;; ---------------------------------------------------------------------------

(deftest send-message-posts-the-input-message-event
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19006 "/v1/agents/sessions/sess_1/events"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"accepted\":true}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/send-message client "sess_1" "List the files in the current directory.")
        (is (= "POST" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/events" (:path @captured)))
        (is (str/includes? (:body @captured) "\"type\":\"agent.session.input.message\""))
        (is (str/includes? (:body @captured) "\"type\":\"input_text\""))
        (is (str/includes? (:body @captured) "\"text\":\"List the files in the current directory.\"")))
      (finally (stop!)))))

(deftest cancel-turn-posts-the-input-cancel-event
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19007 "/v1/agents/sessions/sess_1/events"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"accepted\":true}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/cancel-turn client "sess_1")
        (is (= "{\"events\":[{\"type\":\"agent.session.input.cancel\"}]}" (:body @captured))))
      (finally (stop!)))))

(deftest send-tool-result-success-shape
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19008 "/v1/agents/sessions/sess_1/events"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"accepted\":true}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/send-tool-result client "sess_1"
          {:turn-id "turn_1" :call-id "call_1" :success true :output "{\"found\":true}"})
        (let [body (:body @captured)]
          (is (str/includes? body "\"type\":\"agent.session.input.tool_result\""))
          (is (str/includes? body "\"turn_id\":\"turn_1\""))
          (is (str/includes? body "\"call_id\":\"call_1\""))
          (is (str/includes? body "\"success\":true"))
          (is (str/includes? body "\"output\":\"{\\\"found\\\":true}\""))
          (is (not (str/includes? body "\"error\"")))))
      (finally (stop!)))))

(deftest send-tool-result-failure-shape
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19009 "/v1/agents/sessions/sess_1/events"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"accepted\":true}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]
        (agents/send-tool-result client "sess_1"
          {:turn-id "turn_1" :call-id "call_1" :success false :error "customer not found"})
        (let [body (:body @captured)]
          (is (str/includes? body "\"success\":false"))
          (is (str/includes? body "\"error\":\"customer not found\""))
          (is (not (str/includes? body "\"output\"")))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; sessions-items-list / items-output-text (end-to-end decode)
;; ---------------------------------------------------------------------------

(deftest sessions-items-list-sends-query-and-decodes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19010 "/v1/agents/sessions/sess_1/items"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-items "tree printed")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            items  (agents/sessions-items-list client "sess_1" {"order" "asc" "limit" 100})]
        (is (= "/v1/agents/sessions/sess_1/items" (:path @captured)))
        (is (= {"order" "asc" "limit" "100"} (parse-query (:query @captured))))
        (is (= "tree printed" (agents/items-output-text items))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; sessions-turns-list / sessions-turns-retrieve
;; ---------------------------------------------------------------------------

(deftest sessions-turns-list-sends-query-and-decodes-the-failed-turn
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19021 "/v1/agents/sessions/sess_1/turns"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body (canned-turns [(canned-turn "turn_2" "failed" credit-error)
                                                                    (canned-turn "turn_1" "completed" nil)])}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            turns  (agents/sessions-turns-list client "sess_1" {"limit" 2})
            turn   (agents/latest-root-turn turns)]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/turns" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (= {"limit" "2"} (parse-query (:query @captured))))
        (is (= "turn_2" (get turn "id")))
        (is (agents/turn-finished? turn))
        (is (= "credit_balance_exhausted" (get-in turn ["error" "code"]))))
      (finally (stop!)))))

(deftest sessions-turns-retrieve-gets-by-id
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19022 "/v1/agents/sessions/sess_1/turns/turn_1"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body (canned-turn "turn_1" "in_progress" nil)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            turn   (agents/sessions-turns-retrieve client "sess_1" "turn_1")]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/turns/turn_1" (:path @captured)))
        (is (= "in_progress" (get turn "status")))
        (is (not (agents/turn-finished? turn))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; environments-retrieve
;; ---------------------------------------------------------------------------

(deftest environments-retrieve-gets-by-id
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19011 "/v1/agents/environments/ccarenv_1"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"id\":\"ccarenv_1\",\"status\":\"connected\"}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            env    (agents/environments-retrieve client "ccarenv_1")]
        (is (= "/v1/agents/environments/ccarenv_1" (:path @captured)))
        (is (= "connected" (get env "status"))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Error paths — same status->type table as tools.agents.openai
;; ---------------------------------------------------------------------------

(deftest non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 19012 "/v1/agents/sessions"
                                (fn [_] {:status 429
                                         :body "{\"error\":{\"message\":\"Rate limit reached\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (agents/sessions-create client {"environment" {"type" "none"} "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/rate-limit-error (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 Rate limit reached"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.openai.agents/sessions-create: ")))
      (finally (stop!)))))

(deftest sessions-retrieve-404-maps-to-not-found
  (let [{:keys [port stop!]} (start-server! 19013 "/v1/agents/sessions/sess_missing"
                                (fn [_] {:status 404 :body "{\"error\":{\"message\":\"No such session\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (agents/sessions-retrieve client "sess_missing") nil (catch Exception e e))]
        (is (= :tools.agents.openai/not-found-error (:type (ex-data e)))))
      (finally (stop!)))))

(deftest malformed-json-response-throws-catchable-parse-error
  (let [{:keys [port stop!]} (start-server! 19014 "/v1/agents/sessions"
                                (fn [_] {:status 200 :body "{not valid json"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (agents/sessions-create client {"environment" {"type" "none"} "input" "hi"})
                   nil (catch Exception e e))]
        (is (some? e))
        ;; Inherited verbatim from tools.agents.openai/read-json — see the ns
        ;; docstring's note on why this is NOT a `.agents`-suffixed type.
        (is (= :tools.agents.openai/json-parse-error (:type (ex-data e)))))
      (finally (stop!)))))

(deftest connection-failure-is-typed-not-leaked
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:19998/v1" :max-retries 0})
        e (try (agents/sessions-retrieve client "sess_1") nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))
    (is (= 0 (:retries-taken (ex-data e))))))

(deftest missing-api-key-throws-before-any-network-activity
  ;; A hand-built client map bypassing tools.agents.openai/client (which would
  ;; itself throw first) — this namespace's own guard must still catch it.
  ;; Port 19998 has nothing listening: if a network call were ever attempted
  ;; this would fail with a connection error instead.
  (let [client {:base-url "http://127.0.0.1:19998/v1"}
        e (try (agents/sessions-retrieve client "sess_1") nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/missing-credentials (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Retry loop — reuses tools.agents.openai's should-retry?/retry-delay-ms
;; verbatim; this just confirms the wiring through the new transport leaf.
;; ---------------------------------------------------------------------------

(deftest retries-a-429-then-succeeds
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 19015 "/v1/agents/sessions"
                                (fn [_]
                                  (if (< (swap! hits inc) 3)
                                    {:status 429 :headers {"retry-after-ms" "1"} :body "{}"}
                                    {:status 200 :body (canned-session "sess_1" "in_progress")})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})]  ;; default 2 retries
        (is (= "sess_1" (get (agents/sessions-create client {"environment" {"type" "none"} "input" "hi"}) "id")))
        (is (= 3 @hits)))
      (finally (stop!)))))

(deftest retries-are-exhausted-then-the-status-error-surfaces
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 19016 "/v1/agents/sessions"
                                (fn [_] (swap! hits inc)
                                  {:status 503 :headers {"retry-after-ms" "1"} :body "{}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (agents/sessions-create client {"environment" {"type" "none"} "input" "hi"})
                   nil (catch Exception e e))]
        (is (= :tools.agents.openai/internal-server-error (:type (ex-data e))))
        (is (= 2 (:retries-taken (ex-data e))))
        (is (= 3 @hits)))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; examples/*.clj wired end-to-end against the mock server
;; ---------------------------------------------------------------------------

(deftest example-sandbox-task-runs-against-mock-server
  (let [session-hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19017 "/v1/agents/sessions"
          (fn [{:keys [method path] :as req}]
            (cond
              (and (= method "POST") (= path "/v1/agents/sessions"))
              {:status 200 :body (canned-session "sess_1" "in_progress")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1"))
              (let [n (swap! session-hits inc)]
                {:status 200 :body (canned-session "sess_1" (if (< n 3) "in_progress" "idle"))})

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/items"))
              {:status 200 :body (canned-items "here is your directory tree")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/turns"))
              {:status 200 :body (canned-turns [(canned-turn "turn_1" "completed" nil)])}

              :else {:status 404 :body "{}"})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (ex-task/run-example client {:interval-ms 1 :max-attempts 10 :sleep-fn (fn [_] nil)})]
        (is (= "idle" (:status result)))
        (is (= "completed" (get-in result [:turn "status"])))
        (is (= "here is your directory tree" (:output result)))
        (is (= 3 @session-hits)))
      (finally (stop!)))))

(deftest example-sandbox-task-surfaces-a-failed-turn-behind-an-idle-session
  ;; The live no-credits shape: session back to "idle" with no error, items
  ;; holding only the user's input — the failure is only on the turn.
  (let [{:keys [port stop!]}
        (start-server! 19023 "/v1/agents/sessions"
          (fn [{:keys [method path]}]
            (cond
              (and (= method "POST") (= path "/v1/agents/sessions"))
              {:status 200 :body (canned-session "sess_1" "in_progress")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1"))
              {:status 200 :body (canned-session "sess_1" "idle")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/items"))
              {:status 200 :body (str "{\"object\":\"list\",\"data\":[{\"type\":\"message\",\"role\":\"user\","
                                      "\"content\":[{\"type\":\"input_text\",\"text\":\"task\"}]}],\"has_more\":false}")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/turns"))
              {:status 200 :body (canned-turns [(canned-turn "turn_1" "failed" credit-error)])}

              :else {:status 404 :body "{}"})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (ex-task/run-example client {:interval-ms 1 :max-attempts 10 :sleep-fn (fn [_] nil)})]
        (is (= "idle" (:status result)))
        (is (= "" (:output result)))
        (is (= "failed" (get-in result [:turn "status"])))
        (is (= "credit_balance_exhausted" (get-in result [:turn "error" "code"]))))
      (finally (stop!)))))

(deftest example-sandbox-task-waits-for-the-turn-when-the-session-settles-first
  ;; Session already "idle", but the turn list is empty, then in progress,
  ;; then failed — polling must not stop on the session status alone.
  (let [turn-hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19024 "/v1/agents/sessions"
          (fn [{:keys [method path]}]
            (cond
              (and (= method "POST") (= path "/v1/agents/sessions"))
              {:status 200 :body (canned-session "sess_1" "in_progress")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1"))
              {:status 200 :body (canned-session "sess_1" "idle")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/turns"))
              {:status 200 :body (canned-turns (case (swap! turn-hits inc)
                                                 1 []
                                                 2 [(canned-turn "turn_1" "in_progress" nil)]
                                                 [(canned-turn "turn_1" "failed" credit-error)]))}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1/items"))
              {:status 200 :body "{\"object\":\"list\",\"data\":[],\"has_more\":false}"}

              :else {:status 404 :body "{}"})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (ex-task/run-example client {:interval-ms 1 :max-attempts 10 :sleep-fn (fn [_] nil)})]
        (is (= 3 @turn-hits))
        (is (= "turn_1" (get-in result [:turn "id"])))
        (is (= "failed" (get-in result [:turn "status"]))))
      (finally (stop!)))))

(deftest example-sandbox-task-poll-timeout-throws
  (let [{:keys [port stop!]}
        (start-server! 19018 "/v1/agents/sessions"
          (fn [{:keys [method path]}]
            (cond
              (and (= method "POST") (= path "/v1/agents/sessions"))
              {:status 200 :body (canned-session "sess_1" "in_progress")}

              (and (= method "GET") (= path "/v1/agents/sessions/sess_1"))
              {:status 200 :body (canned-session "sess_1" "in_progress")}  ;; never finishes

              :else {:status 404 :body "{}"})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e (try (ex-task/run-example client {:interval-ms 1 :max-attempts 3 :sleep-fn (fn [_] nil)})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= ::ex-task/poll-timeout (:type (ex-data e)))))
      (finally (stop!)))))

(deftest example-self-hosted-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]}
        (start-server! 19019 "/v1/agents/sessions"
          (fn [req] (reset! captured req)
            {:status 200
             :body (canned-session "sess_1" "requires_action"
                     "{\"type\":\"self_hosted\",\"id\":\"ccarenv_1\",\"remote_url\":\"https://api.openai.com/v1/agents/api\"}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            {:keys [session-id executor-command]} (ex-self-hosted/run-example client)]
        (is (= "sess_1" session-id))
        (is (str/includes? (:body @captured) "\"type\":\"self_hosted\""))
        (is (= ["codex" "exec-server" "--remote" "https://api.openai.com/v1/agents/api"
                "--environment-id" "ccarenv_1"]
               executor-command)))
      (finally (stop!)))))
