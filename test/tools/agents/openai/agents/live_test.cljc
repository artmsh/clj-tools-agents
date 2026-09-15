(ns tools.agents.openai.agents.live-test
  "Mock-server / transport-level coverage for tools.agents.openai.agents. The
   mock server itself is tools.agents.test-support, shared with the
   anthropic/gemini/openai suites. Everything here is identical handler logic
   and identical assertions on both runtimes.

   Port range 19000-19079 — chosen not to collide with the sibling suites'
   ranges (anthropic 18930-18975, gemini 18980-18997, openai 18950-18971)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.agents :as agents]
            [tools.agents.sse :as sse]
            [tools.agents.stream :as stream]
            [examples.openai.agents-sandbox-task :as ex-task]
            [examples.openai.agents-self-hosted :as ex-self-hosted]
            [tools.agents.test-support :refer [start-server! start-abort-server! rotating-token-cache]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- parse-query
  "Raw \"k=v&k2=v2\" query string -> {k v} map, order-independent — both
   this repo's own query-string builder (`tools.agents.http/encode-params`) iterate a
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
;; saved agents: agents-create / -retrieve / -update / -list / -delete
;; Fixtures follow the API reference's example responses.
;; ---------------------------------------------------------------------------

(defn- canned-agent [id name]
  (str "{\"id\":\"" id "\",\"object\":\"agent\",\"created_at\":1789413451,\"updated_at\":1789413451,"
       "\"model\":\"gpt-6-astra\",\"name\":" (if name (str "\"" name "\"") "null") ","
       "\"instructions\":null,\"metadata\":{\"team\":\"docs\"},"
       "\"multi_agent\":{\"enabled\":false,\"max_concurrent_subagents\":null},"
       "\"reasoning\":{\"effort\":\"medium\",\"summary\":null},\"service_tier\":\"auto\","
       "\"text\":{\"format\":{\"type\":\"text\"},\"verbosity\":\"medium\"},\"tools\":[]}"))

(deftest agents-create-posts-to-agents-with-beta-header-and-body
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19025 "/v1/agents"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-agent "agent_1" "helper")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            agent  (agents/agents-create client {"model" "gpt-6-astra" "name" "helper"
                                                 "metadata" {"team" "docs"}
                                                 "reasoning" {"effort" "medium"}})]
        (is (= "agent_1" (get agent "id")))
        (is (= "agent" (get agent "object")))
        (is (= "docs" (get-in agent ["metadata" "team"])))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/agents" (:path req)))
          (is (nil? (:query req)))
          (is (= "agents=v1" (get (:headers req) "openai-beta")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"model\":\"gpt-6-astra\""))
          (is (str/includes? (:body req) "\"name\":\"helper\""))
          (is (str/includes? (:body req) "\"metadata\":{\"team\":\"docs\"}"))
          (is (str/includes? (:body req) "\"reasoning\":{\"effort\":\"medium\"}"))))
      (finally (stop!)))))

(deftest agents-retrieve-gets-by-id
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19026 "/v1/agents"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-agent "agent_42" nil)}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            agent  (agents/agents-retrieve client "agent_42")]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/agent_42" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (= "agent_42" (get agent "id")))
        (is (nil? (get agent "name"))))
      (finally (stop!)))))

(deftest agents-update-posts-to-the-agent-and-sends-null-to-clear
  ;; nil must reach the wire as JSON null: the reference defines null as
  ;; "clear" for name/instructions/metadata, distinct from omitting the key.
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19027 "/v1/agents"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-agent "agent_1" "renamed")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            agent  (agents/agents-update client "agent_1" {"name" "renamed" "instructions" nil})]
        (is (= "POST" (:method @captured)))
        (is (= "/v1/agents/agent_1" (:path @captured)))
        (is (str/includes? (:body @captured) "\"name\":\"renamed\""))
        (is (str/includes? (:body @captured) "\"instructions\":null"))
        (is (not (str/includes? (:body @captured) "\"model\"")))
        (is (= "renamed" (get agent "name"))))
      (finally (stop!)))))

(deftest agents-list-sends-cursor-params-and-decodes-the-page
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19028 "/v1/agents"
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body (str "{\"object\":\"list\",\"data\":[" (canned-agent "agent_2" "b") ","
                                              (canned-agent "agent_3" "c") "],"
                                              "\"first_id\":\"agent_2\",\"last_id\":\"agent_3\",\"has_more\":true}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            page   (agents/agents-list client {"after" "agent_1" "limit" 2 "order" :asc})]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents" (:path @captured)))
        (is (= {"after" "agent_1" "limit" "2" "order" "asc"} (parse-query (:query @captured))))
        (is (= ["agent_2" "agent_3"] (mapv #(get % "id") (get page "data"))))
        (is (true? (get page "has_more")))
        (is (= "agent_3" (get page "last_id"))))
      (finally (stop!)))))

(deftest agents-list-with-no-params-sends-no-query-string
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19029 "/v1/agents"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"object\":\"list\",\"data\":[],\"first_id\":null,\"last_id\":null,\"has_more\":false}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            page   (agents/agents-list client)]
        (is (= "/v1/agents" (:path @captured)))
        (is (nil? (:query @captured)))
        (is (= [] (get page "data"))))
      (finally (stop!)))))

(deftest agents-delete-sends-delete
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19030 "/v1/agents"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"id\":\"agent_1\",\"deleted\":true,\"object\":\"agent.deleted\"}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (agents/agents-delete client "agent_1")]
        (is (= "DELETE" (:method @captured)))
        (is (= "/v1/agents/agent_1" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (true? (get result "deleted")))
        (is (= "agent.deleted" (get result "object"))))
      (finally (stop!)))))

(deftest agents-retrieve-404-maps-to-not-found
  (let [{:keys [port stop!]} (start-server! 19031 "/v1/agents"
                                (fn [_] {:status 404 :body "{\"error\":{\"message\":\"No such agent\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (agents/agents-retrieve client "agent_missing") nil (catch Exception e e))]
        (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
        (is (= 404 (:status (ex-data e))))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.openai.agents/agents-retrieve: HTTP 404 No such agent")))
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

;; ---------------------------------------------------------------------------
;; session artifacts
;; ---------------------------------------------------------------------------

(defn- canned-artifact [id path]
  (str "{\"id\":\"" id "\",\"object\":\"agent.session.artifact\",\"created_at\":1757900000,"
       "\"environment_id\":\"ccarenv_1\",\"path\":\"" path "\",\"session_id\":\"sess_1\","
       "\"size_bytes\":8,\"turn_id\":\"turn_1\"}"))

;; Every byte value, then a lone continuation byte, a truncated 2-byte lead
;; and an overlong NUL — none of which survives a UTF-8 String round trip.
(def ^:private non-utf8-bytes
  (byte-array (map unchecked-byte (concat (range 256) [0x80 0xc3 0x28 0xc0 0x80 0xff 0x00]))))

(deftest sessions-artifacts-list-sends-cursor-query-and-decodes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19032 "/v1/agents/sessions/sess_1/artifacts"
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body (str "{\"object\":\"list\",\"data\":["
                                              (canned-artifact "art_1" "/workspace/outputs/p.bin")
                                              "],\"first_id\":\"art_1\",\"last_id\":\"art_1\",\"has_more\":false}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            page   (agents/sessions-artifacts-list client "sess_1" {"after" "art_0" "limit" 10
                                                                     "order" "asc" "environment_id" "ccarenv_1"})]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/artifacts" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (= {"after" "art_0" "limit" "10" "order" "asc" "environment_id" "ccarenv_1"}
               (parse-query (:query @captured))))
        (is (= "/workspace/outputs/p.bin" (get-in page ["data" 0 "path"])))
        (is (= "art_1" (get page "last_id")))
        (agents/sessions-artifacts-list client "sess_1")
        (is (nil? (:query @captured)) "no params, no query string"))
      (finally (stop!)))))

(deftest sessions-artifacts-retrieve-gets-by-id
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19033 "/v1/agents/sessions/sess_1/artifacts/art_1"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body (canned-artifact "art_1" "/workspace/outputs/p.bin")}))]
    (try
      (let [client   (oai/client {:api-key "k" :base-url (base-url port)})
            artifact (agents/sessions-artifacts-retrieve client "sess_1" "art_1")]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/artifacts/art_1" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (= "turn_1" (get artifact "turn_id")))
        (is (= 8 (get artifact "size_bytes"))))
      (finally (stop!)))))

(deftest sessions-artifacts-content-round-trips-non-utf8-bytes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19034 "/v1/agents/sessions/sess_1/artifacts/art_1/content"
                                (fn [req] (reset! captured req)
                                  {:status 200 :headers {"content-type" "application/octet-stream"}
                                   :body non-utf8-bytes}))]
    (try
      (let [client  (oai/client {:api-key "k" :base-url (base-url port)})
            content (agents/sessions-artifacts-content client "sess_1" "art_1")]
        (is (= "GET" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/artifacts/art_1/content" (:path @captured)))
        (is (= "application/octet-stream" (get-in @captured [:headers "accept"])))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (bytes? content))
        (is (java.util.Arrays/equals ^bytes non-utf8-bytes ^bytes content))
        (is (not (java.util.Arrays/equals ^bytes non-utf8-bytes
                                          (.getBytes (String. ^bytes non-utf8-bytes "UTF-8") "UTF-8")))
            "fixture really is not UTF-8 safe"))
      (finally (stop!)))))

(deftest sessions-artifacts-content-404-is-typed-with-decoded-message
  (let [{:keys [port stop!]} (start-server! 19035 "/v1/agents/sessions/sess_1/artifacts"
                                (fn [_] {:status 404 :body "{\"error\":{\"message\":\"No such artifact\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e      (try (agents/sessions-artifacts-content client "sess_1" "art_missing") nil
                        (catch Exception e e))]
        (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
        (is (= 404 (:status (ex-data e))))
        (is (string? (:body (ex-data e))))
        (is (str/starts-with? (str (ex-message e))
                              "tools.agents.openai.agents/sessions-artifacts-content: HTTP 404 No such artifact")))
      (finally (stop!)))))

(deftest sessions-artifacts-content-does-not-follow-a-redirect
  ;; Pins the documented behaviour: the shared java.net.http client keeps
  ;; Redirect.NEVER, so a 3xx surfaces as a typed error rather than a silent
  ;; follow (which would forward the bearer token to the Location host).
  (let [hits (atom [])
        {:keys [port stop!]} (start-server! 19036 "/v1/agents/sessions/sess_1/artifacts"
                                (fn [{:keys [path]}]
                                  (swap! hits conj path)
                                  {:status 302 :headers {"location" "/v1/signed/blob"} :body ""}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e      (try (agents/sessions-artifacts-content client "sess_1" "art_1") nil
                        (catch Exception e e))]
        (is (= :tools.agents.openai/api-status-error (:type (ex-data e))))
        (is (= 302 (:status (ex-data e))))
        (is (= ["/v1/agents/sessions/sess_1/artifacts/art_1/content"] @hits)))
      (finally (stop!)))))

(deftest sessions-artifacts-delete-sends-delete
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19037 "/v1/agents/sessions/sess_1/artifacts/art_1"
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body "{\"id\":\"art_1\",\"deleted\":true,\"object\":\"agent.session.artifact.deleted\"}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            result (agents/sessions-artifacts-delete client "sess_1" "art_1")]
        (is (= "DELETE" (:method @captured)))
        (is (= "/v1/agents/sessions/sess_1/artifacts/art_1" (:path @captured)))
        (is (= "agents=v1" (get-in @captured [:headers "openai-beta"])))
        (is (true? (get result "deleted")))
        (is (= "agent.session.artifact.deleted" (get result "object"))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; environment files
;; ---------------------------------------------------------------------------

(defn- canned-env-file [path size]
  (str "{\"environment_id\":\"ccarenv_1\",\"object\":\"agent.environment.file\",\"path\":\"" path
       "\",\"size_bytes\":" size "}"))

(deftest environments-files-list-follows-page-next-tokens
  (let [queries (atom [])
        {:keys [port stop!]}
        (start-server! 19038 "/v1/agents/environments/ccarenv_1/files"
          (fn [{:keys [method path query]}]
            ;; JVM's .getQuery decodes %2F, httpkit's does not: decode here.
            (swap! queries conj [method path (update-vals (parse-query query)
                                                          #(java.net.URLDecoder/decode ^String % "UTF-8"))])
            (if (= "tok_2" (get (parse-query query) "page"))
              {:status 200 :body (str "{\"object\":\"page\",\"data\":[" (canned-env-file "/workspace/a.txt" 1)
                                      "],\"has_more\":false,\"next\":null}")}
              {:status 200 :body (str "{\"object\":\"page\",\"data\":[" (canned-env-file "/workspace/b.txt" 2)
                                      "],\"has_more\":true,\"next\":\"tok_2\"}")})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            params {"limit" 1 "path" "/workspace" "order" "desc"}
            pages  (loop [resp (agents/environments-files-list client "ccarenv_1" params)
                          acc  [resp]]
                     (if (get resp "has_more")
                       (let [nxt (agents/environments-files-list client "ccarenv_1"
                                                                 (assoc params "page" (get resp "next")))]
                         (recur nxt (conj acc nxt)))
                       acc))]
        (is (= 2 (count pages)))
        (is (= ["/workspace/b.txt" "/workspace/a.txt"]
               (mapv #(get-in % ["data" 0 "path"]) pages)))
        (is (every? #(= "page" (get % "object")) pages))
        (is (= [["GET" "/v1/agents/environments/ccarenv_1/files"
                 {"limit" "1" "path" "/workspace" "order" "desc"}]
                ["GET" "/v1/agents/environments/ccarenv_1/files"
                 {"limit" "1" "path" "/workspace" "order" "desc" "page" "tok_2"}]]
               @queries)))
      (finally (stop!)))))

(deftest environments-files-list-404-maps-to-not-found
  (let [{:keys [port stop!]} (start-server! 19039 "/v1/agents/environments"
                                (fn [_] {:status 404 :body "{\"error\":{\"message\":\"No such environment\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e      (try (agents/environments-files-list client "ccarenv_missing") nil (catch Exception e e))]
        (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
        (is (str/includes? (str (ex-message e)) "environments-files-list: HTTP 404 No such environment")))
      (finally (stop!)))))

(defn- decode-b64 ^bytes [^String s] (.decode (java.util.Base64/getDecoder) s))

(deftest environments-files-create-sends-json-body-with-base64-data
  (let [captured (atom [])
        {:keys [port stop!]} (start-server! 19040 "/v1/agents/environments/ccarenv_1/files"
                                (fn [req] (swap! captured conj req)
                                  {:status 200 :body (canned-env-file "/workspace/in.bin" 5)}))
        tmp (java.io.File/createTempFile "clj-tools-agents-env-file" ".bin")]
    (try
      (java.nio.file.Files/write (.toPath tmp) ^bytes non-utf8-bytes
                                 ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
      (let [client  (oai/client {:api-key "k" :base-url (base-url port)})
            created (agents/environments-files-create client "ccarenv_1"
                      {"type" "inline" "path" "/workspace/in.txt" "data" "aGVsbG8="})]
        (is (= "agent.environment.file" (get created "object")))
        (agents/environments-files-create client "ccarenv_1"
          {"type" "inline" "path" "/workspace/in.bin" "data" non-utf8-bytes})
        (agents/environments-files-create client "ccarenv_1"
          {"type" "inline" "path" "/workspace/in.bin" "data" tmp})
        (agents/environments-files-create client "ccarenv_1"
          {"type" "inline" "path" "/workspace/in.bin" "data" (.toPath tmp)})
        (agents/environments-files-create client "ccarenv_1"
          {"type" "file_id" "path" "/workspace/in.pdf" "file_id" "file-abc"})
        (let [[as-string as-bytes as-file as-path as-file-id] @captured
              body (fn [req] (oai/read-json (:body req)))]
          (is (= "POST" (:method as-string)))
          (is (= "/v1/agents/environments/ccarenv_1/files" (:path as-string)))
          (is (= "agents=v1" (get-in as-string [:headers "openai-beta"])))
          (is (str/starts-with? (str (get-in as-string [:headers "content-type"])) "application/json"))
          (is (= {"type" "inline" "path" "/workspace/in.txt" "data" "aGVsbG8="} (body as-string))
              "a String is sent verbatim")
          (doseq [req [as-bytes as-file as-path]]
            (is (java.util.Arrays/equals ^bytes non-utf8-bytes (decode-b64 (get (body req) "data")))))
          (is (= {"type" "file_id" "path" "/workspace/in.pdf" "file_id" "file-abc"} (body as-file-id))
              "no \"data\" key is added")))
      (finally (stop!) (.delete tmp)))))

(deftest environments-files-create-rejects-unsupported-data-before-network
  ;; Nothing listens on 19998: an attempted request would be a connection error.
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:19998/v1" :max-retries 0})
        e      (try (agents/environments-files-create client "ccarenv_1"
                      {"type" "inline" "path" "/workspace/x" "data" 42})
                    nil (catch Exception e e))]
    (is (= :tools.agents.openai/invalid-request (:type (ex-data e))))
    (is (str/includes? (str (ex-message e)) "java.lang.Long"))))

;; ---------------------------------------------------------------------------
;; Event streaming (#25): sessions-events-stream, sessions-create-stream,
;; root-turn-finished?, await-root-turn. Ports 19041-19049.
;; ---------------------------------------------------------------------------

(def ^:private sse-headers {"content-type" "text/event-stream"})

(def ^:private turn-fixture (slurp "test/resources/sse/agents-turn.sse"))

(defn- fixture-frames
  "The fixture's `data: ...` frames, each with its trailing blank line."
  []
  (mapv #(str % "\n\n") (re-seq #"(?m)^data: .*$" turn-fixture)))

(defn- fixture-events [] (mapv (comp oai/read-json :data) (sse/parse-string turn-fixture)))

(defn- until-gone!
  "Streaming-body helper: SSE comments every 10 ms until the client
   disconnects, then deliver `gone` true (false after ~5 s)."
  [send! gone]
  (loop [i 0]
    (cond
      (not (send! ": keep-alive\n\n")) (deliver gone true)
      (> i 500)                        (deliver gone false)
      :else                            (do (Thread/sleep 10) (recur (inc i))))))

(defn- turn-event [type status subagent-id & {:as extra}]
  (merge {"type" type "event_id" "evt_x" "session_id" "sess_1" "turn_id" "turn_9"
          "turn" {"id" "turn_9" "object" "agent.session.turn" "subagent_id" subagent-id
                  "status" status "error" (get extra "error")}}
         (dissoc extra "error")))

(deftest sessions-events-stream-gets-with-accept-and-stream-query-and-delivers-incrementally
  (let [captured   (atom nil)
        seen-first (promise)
        server-saw (promise)
        frames     (fixture-frames)
        {:keys [port stop!]}
        (start-server! 19041 "/v1/agents/sessions/sess_1/events"
          (fn [req]
            (reset! captured req)
            {:status 200 :headers sse-headers
             :body (fn [send!]
                     (send! (first frames))
                     ;; The rest goes out only once the reducer has seen event 1.
                     (deliver server-saw (deref seen-first 5000 :timeout))
                     (doseq [f (rest frames)] (send! f)))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-events-stream client "sess_1" {"limit" 5})
            events (reduce (fn [acc ev]
                             (deliver seen-first :seen)
                             (conj acc ev))
                           [] s)]
        (is (= :seen @server-saw) "event 1 reached the reducer while the server was still blocked")
        (let [req @captured]
          (is (= "GET" (:method req)))
          (is (= "/v1/agents/sessions/sess_1/events" (:path req)))
          (is (= {"stream" "true" "limit" "5"} (parse-query (:query req))))
          (is (= "text/event-stream" (get (:headers req) "accept")))
          (is (= "agents=v1" (get (:headers req) "openai-beta")))
          (is (= "Bearer k" (get (:headers req) "authorization"))))
        (is (= (fixture-events) events) "decoded JSON maps, verbatim")
        (is (= 12 (count events)))
        (let [by-type (group-by #(get % "type") events)]
          (is (= "in_progress" (get-in (first (by-type "agent.session.in_progress")) ["session" "status"])))
          (is (= "queued" (get-in (first (by-type "agent.session.turn.created")) ["turn" "status"])))
          (is (= "message" (get-in (first (by-type "agent.session.turn.item.added")) ["item" "type"])))
          (is (= ["Acme competes" " on price and distribution."]
                 (map #(get % "delta") (by-type "agent.session.turn.output_text.delta"))))
          (is (= "Acme competes on price and distribution."
                 (get (first (by-type "agent.session.turn.output_text.done")) "text")))
          (is (= 821 (get-in (first (by-type "agent.session.turn.completed")) ["usage" "total_tokens"]))))
        (is (= "message" (:tools.agents.sse/event (meta (first events)))) "SSE frame name kept as metadata")
        (is (= :eof (stream/outcome s)))
        (is (= 200 (:status (stream/response s)))))
      (finally (deliver seen-first :cleanup) (stop!)))))

(deftest sessions-events-stream-tolerates-event-lines-keep-alives-and-done
  (let [{:keys [port stop!]}
        (start-server! 19042 "/v1/agents/sessions/sess_1/events"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! ": comment\n\n")
                           (send! "event: agent.session.idle\nid: 7\ndata: {\"type\":\"agent.session.idle\",\"event_id\":\"evt_1\"}\n\n")
                           (send! "data:\n\n")
                           (send! "data: [DONE]\n\n")
                           (send! "data: {\"type\":\"never\"}\n\n"))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-events-stream client "sess_1")
            events (into [] s)]
        (is (= [{"type" "agent.session.idle" "event_id" "evt_1"}] events))
        (is (= {:tools.agents.sse/event "agent.session.idle" :tools.agents.sse/id "7"} (meta (first events))))
        (is (= :done (stream/outcome s)) "[DONE] ends the stream without reaching the decoder"))
      (finally (stop!)))))

(deftest sessions-events-stream-early-termination-closes-the-connection
  (let [gone   (promise)
        frames (fixture-frames)
        {:keys [port stop!]}
        (start-server! 19043 "/v1/agents/sessions/sess_1/events"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (doseq [f (take 3 frames)] (send! f))
                           (until-gone! send! gone))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-events-stream client "sess_1")]
        (is (= ["agent.session.in_progress" "agent.session.turn.created"]
               (into [] (comp (take 2) (map #(get % "type"))) s)))
        (is (true? (deref gone 5000 :timeout)) "server saw the client disconnect")
        (is (= :reduced (stream/outcome s)))
        (is (= :tools.agents.stream/consumed
               (:type (ex-data (try (into [] s) nil (catch Exception e e)))))))
      (finally (stop!)))))

(deftest sessions-events-stream-http-error-before-the-stream-is-typed
  (let [hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19044 "/v1/agents/sessions/sess_missing/events"
          (fn [_] (swap! hits inc)
            {:status 404 :headers {"content-type" "application/json"}
             :body "{\"error\":{\"message\":\"No session found\",\"type\":\"invalid_request_error\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            e      (try (agents/sessions-events-stream client "sess_missing") nil (catch Exception e e))]
        (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
        (is (= 404 (:status (ex-data e))))
        (is (str/includes? (:body (ex-data e)) "No session found"))
        (is (str/includes? (ex-message e) "tools.agents.openai.agents/sessions-events-stream: HTTP 404 No session found"))
        (is (= 1 @hits) "a 404 is not retried"))
      (finally (stop!)))))

(deftest sessions-events-stream-retries-a-503-before-the-first-byte
  (let [hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19045 "/v1/agents/sessions/sess_1/events"
          (fn [_]
            (if (= 1 (swap! hits inc))
              {:status 503 :headers {"retry-after-ms" "1"} :body "{\"error\":{\"message\":\"busy\"}}"}
              {:status 200 :headers sse-headers :body (apply str (fixture-frames))})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 1})
            events (into [] (agents/sessions-events-stream client "sess_1"))]
        (is (= 2 @hits))
        (is (= (fixture-events) events)))
      (finally (stop!)))))

(deftest sessions-events-stream-mid-stream-abort-is-a-connection-error
  (let [{:keys [port stop!]}
        (start-abort-server! 19046 {:headers sse-headers :chunks (take 2 (fixture-frames))})]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            seen   (atom [])
            e      (try (reduce (fn [_ ev] (swap! seen conj (get ev "type"))) nil
                                (agents/sessions-events-stream client "sess_1"))
                        nil (catch Exception e e))]
        (is (= ["agent.session.in_progress" "agent.session.turn.created"] @seen))
        (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

(def ^:private error-event
  {"type" "error" "event_id" "evt_e" "session_id" "sess_1"
   "error" {"code" "credit_balance_exhausted" "message" "You have no credits remaining."
            "param" nil "type" "insufficient_quota"}})

(deftest sessions-events-stream-error-event-throws-after-earlier-events-and-closes
  ;; openai-python streams this endpoint through the generic Stream, whose
  ;; __stream__ raises APIError on data with a truthy top-level "error".
  (let [hits   (atom 0)
        gone   (promise)
        frames (fixture-frames)
        {:keys [port stop!]}
        (start-server! 0 "/v1/agents/sessions/sess_1/events"
          (fn [_] (swap! hits inc)
            {:status 200 :headers sse-headers
             :body (fn [send!]
                     (doseq [f (take 2 frames)] (send! f))
                     (send! (str "event: error\ndata: " (oai/write-json error-event) "\n\n"))
                     (doseq [f (drop 2 frames)] (send! f))
                     (until-gone! send! gone))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-events-stream client "sess_1")
            seen   (atom [])
            e      (try (run! #(swap! seen conj (get % "type")) s) nil (catch Exception e e))
            data   (ex-data e)]
        (is (= ["agent.session.in_progress" "agent.session.turn.created"] @seen)
            "events before the error are delivered, none after")
        (is (= :tools.agents.openai/stream-error (:type data)))
        (is (nil? (:status data)))
        (is (= (get error-event "error") (:error data)))
        (is (= error-event (:event data)))
        (is (= (oai/write-json error-event) (:body data)))
        (is (= "tools.agents.openai.agents/sessions-events-stream: stream error: You have no credits remaining."
               (ex-message e)))
        (is (= :failed (stream/outcome s)))
        (is (true? (deref gone 5000 :timeout)) "the server observed the disconnect")
        (is (= 1 @hits) "nothing is retried once the stream has started"))
      (finally (stop!)))))

(deftest await-root-turn-over-sessions-create-stream-error-event-throws-stream-error
  (let [gone   (promise)
        frames (fixture-frames)
        {:keys [port stop!]}
        (start-server! 0 "/v1/agents/sessions"
          (fn [_]
            {:status 200 :headers sse-headers
             :body (fn [send!]
                     (doseq [f (take 6 frames)] (send! f))
                     (send! (str "data: " (oai/write-json error-event) "\n\n"))
                     (doseq [f (drop 6 frames)] (send! f))
                     (until-gone! send! gone))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-create-stream client {"input" "hi"})
            deltas (atom [])
            seen   (atom 0)
            e      (try (agents/await-root-turn s {:on-event (fn [ev]
                                                               (swap! seen inc)
                                                               (some->> (get ev "delta") (swap! deltas conj)))})
                        nil (catch Exception e e))]
        (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
        (is (= error-event (:event (ex-data e))))
        (is (= 6 @seen) ":on-event saw the events before the error, not the error")
        (is (= ["Acme competes"] @deltas))
        (is (= :failed (stream/outcome s)))
        (is (true? (deref gone 5000 :timeout)) "the server observed the disconnect"))
      (finally (stop!)))))

(deftest sessions-create-stream-posts-stream-true-and-awaits-the-root-turn
  (let [captured (atom nil)
        gone     (promise)
        frames   (fixture-frames)
        {:keys [port stop!]}
        (start-server! 19047 "/v1/agents/sessions"
          (fn [req]
            (reset! captured req)
            {:status 200 :headers sse-headers
             :body (fn [send!]
                     ;; Everything through turn.completed, then hold the
                     ;; connection open: await-root-turn must close it.
                     (doseq [f (take 11 frames)] (send! f))
                     (until-gone! send! gone))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-create-stream client {"environment" {"type" "none"} "input" "hi"
                                                          :stream false})
            deltas (atom [])
            done   (agents/await-root-turn s {:on-event #(some->> (get % "delta") (swap! deltas conj))})]
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/agents/sessions" (:path req)))
          (is (= "text/event-stream" (get (:headers req) "accept")))
          (is (= "agents=v1" (get (:headers req) "openai-beta")))
          (is (= {"environment" {"type" "none"} "input" "hi" "stream" true} (oai/read-json (:body req)))))
        (is (= "agent.session.turn.completed" (get done "type")))
        (is (= "completed" (get-in done ["turn" "status"])))
        (is (agents/turn-finished? (get done "turn")))
        (is (= ["Acme competes" " on price and distribution."] @deltas))
        (is (true? (deref gone 5000 :timeout)) "the stream closed after the root turn completed")
        (is (= :reduced (stream/outcome s))))
      (finally (stop!)))))

(deftest await-root-turn-over-a-truncated-stream-throws-stream-truncated
  (let [{:keys [port stop!]}
        (start-server! 19048 "/v1/agents/sessions/sess_1/events"
          (fn [_] {:status 200 :headers sse-headers :body (apply str (take 8 (fixture-frames)))}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (agents/sessions-events-stream client "sess_1")
            e      (try (agents/await-root-turn s) nil (catch Exception e e))]
        (is (= :tools.agents.openai/stream-truncated (:type (ex-data e))))
        (is (= :eof (:outcome (ex-data e))))
        (is (= :eof (stream/outcome s))))
      (finally (stop!)))))

(deftest sessions-create-stream-still-leaves-sessions-create-refusing-stream
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:19999/v1"})
        e      (try (agents/sessions-create client {"input" "hi" "stream" true}) nil (catch Exception e e))]
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))
    (is (str/includes? (ex-message e) "sessions-create-stream"))))

(deftest root-turn-finished?-only-for-root-terminal-turn-events
  (is (agents/root-turn-finished? (turn-event "agent.session.turn.completed" "completed" nil)))
  (is (agents/root-turn-finished? (turn-event "agent.session.turn.failed" "failed" nil)))
  (is (agents/root-turn-finished? (turn-event "agent.session.turn.cancelled" "cancelled" nil)))
  (is (not (agents/root-turn-finished? (turn-event "agent.session.turn.completed" "completed" "sub_1"))))
  (is (not (agents/root-turn-finished? (turn-event "agent.session.turn.in_progress" "in_progress" nil))))
  (is (not (agents/root-turn-finished? {"type" "agent.session.idle"})))
  (is (not (agents/root-turn-finished? nil)))
  (is (= [false false false false false false false false false false true false]
         (mapv agents/root-turn-finished? (fixture-events)))))

(deftest await-root-turn-pure-rules
  (let [events   (fixture-events)
        sub-done (turn-event "agent.session.turn.completed" "completed" "sub_1")
        thrown   (fn [evs] (try (agents/await-root-turn evs) nil (catch Exception e (ex-data e))))]
    (testing "returns the root turn.completed event; later events are not read"
      (let [seen (atom 0)]
        (is (= (nth events 10) (agents/await-root-turn events {:on-event (fn [_] (swap! seen inc))})))
        (is (= 11 @seen))))
    (testing "idle and subagent turn events do not end the wait"
      (is (= :tools.agents.openai/stream-truncated
             (:type (thrown [{"type" "agent.session.idle"} sub-done
                             (turn-event "agent.session.turn.failed" "failed" "sub_1")]))))
      (is (nil? (:outcome (thrown [sub-done]))) "a plain collection has no stream outcome")
      (is (= "completed" (get-in (agents/await-root-turn (into [sub-done] events)) ["turn" "status"]))))
    (testing "root turn failed"
      (let [err  {"code" "credit_balance_exhausted" "message" "You have no credits remaining."}
            data (thrown [(turn-event "agent.session.turn.failed" "failed" nil "error" err)])]
        (is (= :tools.agents.openai/turn-failed (:type data)))
        (is (= err (:error data)))
        (is (= "failed" (get-in data [:turn "status"])))))
    (testing "root turn cancelled"
      (is (= :tools.agents.openai/turn-cancelled
             (:type (thrown [(turn-event "agent.session.turn.cancelled" "cancelled" nil)])))))
    (testing "session and environment failures"
      (is (= :tools.agents.openai/session-failed
             (:type (thrown [{"type" "agent.session.failed" "session" {"status" "failed" "error" "boom"}}]))))
      (let [data (thrown [{"type" "agent.session.environment.failed" "session_id" "sess_1"
                           "environment" {"id" "env_1" "status" "failed"
                                          "error" {"code" "sandbox_error" "message" "no capacity" "type" "x"}}}])]
        (is (= :tools.agents.openai/session-failed (:type data)))
        (is (= "sandbox_error" (get-in data [:error "code"])))))
    (testing "error event"
      (let [data (thrown [{"type" "error" "event_id" "evt_e" "session_id" "sess_1"
                           "error" {"code" nil "message" "internal" "param" nil "type" "server_error"}}])]
        (is (= :tools.agents.openai/stream-error (:type data)))
        (is (= "internal" (get-in data [:error "message"])))
        (is (= "evt_e" (get-in data [:event "event_id"])))))
    (testing "any event with a truthy top-level error, as the stream decides"
      (is (= :tools.agents.openai/stream-error
             (:type (thrown [{"type" "agent.session.new_type" "error" {"message" "x"}}]))))
      (is (= "completed" (get-in (agents/await-root-turn (into [{"type" "x" "error" nil}] events))
                                 ["turn" "status"]))
          "a null top-level error is not an error"))))

;; ---------------------------------------------------------------------------
;; REAL-API saved-agent CRUD round trip — the one test in this file that
;; talks to OpenAI. Skipped (a single passing assertion) unless both
;; OPENAI_AGENTS_LIVE=1 and OPENAI_API_KEY are set, so a key merely present in
;; the environment never makes the regular suite hit the network. Agent CRUD runs no inference, so it costs no model
;; tokens. Base URL defaults to https://api.openai.com/v1 regardless of
;; OPENAI_BASE_URL (which script/live_check.clj points at a gateway without
;; /agents); override with OPENAI_AGENTS_BASE_URL. Model: OPENAI_AGENTS_MODEL.
;; ---------------------------------------------------------------------------

(defn- env [k default] (let [v (System/getenv k)] (if (seq v) v default)))

(defn- live-poll
  "Call `f` every `interval-ms` until it returns truthy, up to `timeout-ms`."
  [f interval-ms timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (or (f)
          (if (< (System/currentTimeMillis) deadline)
            (do (Thread/sleep (long interval-ms)) (recur))
            (throw (ex-info "live probe: poll timed out" {:timeout-ms timeout-ms})))))))

(deftest agents-crud-round-trip-against-the-real-api
  (if-let [api-key (and (= "1" (System/getenv "OPENAI_AGENTS_LIVE"))
                       (not-empty (System/getenv "OPENAI_API_KEY")))]
    (let [client  (oai/client {:api-key api-key
                               :base-url (env "OPENAI_AGENTS_BASE_URL" "https://api.openai.com/v1")})
          model   (env "OPENAI_AGENTS_MODEL" "gpt-5.5")
          created (agents/agents-create client {"model" model "name" "clj-tools-agents-live-test"
                                                "metadata" {"probe" "1"}})
          id      (get created "id")]
      (try
        (is (string? id))
        (is (= "agent" (get created "object")))
        (is (= "clj-tools-agents-live-test" (get created "name")))
        (is (= id (get (agents/agents-retrieve client id) "id")))
        (let [updated (agents/agents-update client id {"name" "clj-tools-agents-live-test-2"})]
          (is (= "clj-tools-agents-live-test-2" (get updated "name")))
          (is (= model (get updated "model")) "omitted fields are left unchanged"))
        ;; listing is eventually consistent: a fresh agent shows up after a few seconds
        (let [page (live-poll #(let [p (agents/agents-list client {"limit" 100})]
                                 (when (some (fn [a] (= id (get a "id"))) (get p "data")) p))
                              1000 60000)]
          (is (= "list" (get page "object")))
          (is (some #(= id (get % "id")) (get page "data"))))
        (let [deleted (agents/agents-delete client id)]
          (is (true? (get deleted "deleted")))
          (is (= "agent.deleted" (get deleted "object"))))
        (let [e (try (agents/agents-retrieve client id) nil (catch Exception e e))]
          (is (= :tools.agents.openai/not-found-error (:type (ex-data e)))))
        (catch Exception e
          ;; best-effort cleanup when a step before delete threw
          (try (agents/agents-delete client id) (catch Exception _ nil))
          (throw e))))
    (is true "skipped: set OPENAI_AGENTS_LIVE=1 and OPENAI_API_KEY")))

;; ---------------------------------------------------------------------------
;; REAL-API probe P1 (#19): artifacts and environment files. Same gate and
;; env overrides as the CRUD round trip above. COSTS MONEY: one openai_hosted
;; session and one short turn. Cleanup (cancel + delete, retrying a 409) runs
;; in `finally`. Findings to record in docs/openai-agents.md: whether content
;; arrives inline or as a 3xx (this client does not follow redirects), bytes
;; intact, env-file create on the live environment, token paging.
;; ---------------------------------------------------------------------------

(defn- live-cleanup-session! [client session-id]
  (try (agents/cancel-turn client session-id) (catch Exception _ nil))
  (loop [attempt 1]
    (let [outcome (try (agents/sessions-delete client session-id) :deleted
                       (catch Exception e (:type (ex-data e))))]
      (when (and (= outcome :tools.agents.openai/conflict-error) (< attempt 5))
        (Thread/sleep 2000)
        (recur (inc attempt))))))

(deftest artifacts-and-environment-files-probe-against-the-real-api
  (if-let [api-key (and (= "1" (System/getenv "OPENAI_AGENTS_LIVE"))
                       (not-empty (System/getenv "OPENAI_API_KEY")))]
    (let [client     (oai/client {:api-key api-key
                                  :base-url (env "OPENAI_AGENTS_BASE_URL" "https://api.openai.com/v1")})
          model      (env "OPENAI_AGENTS_MODEL" "gpt-5.5")
          expected   (byte-array (map unchecked-byte [0x70 0x72 0x6f 0x62 0x65 0x2d 0x00 0xff]))
          session    (agents/sessions-create client
                       {"agent" {"model" model "instructions" "Do exactly what is asked; no extra work."}
                        "environment" {"type" "openai_hosted"}
                        "input" (str "Run: mkdir -p /workspace/outputs && "
                                     "printf 'probe-\\000\\377' > /workspace/outputs/p.bin. Reply OK.")})
          session-id (get session "id")]
      (try
        (is (string? session-id))
        (let [env-id (live-poll #(get-in (agents/sessions-retrieve client session-id) ["environment" "id"])
                                1000 120000)]
          ;; Env files need a connected environment: do them while the turn runs.
          (live-poll #(contains? #{"connected" "disconnected" "failed" "expired"}
                                 (get (agents/environments-retrieve client env-id) "status"))
                     1000 180000)
          (let [created (agents/environments-files-create client env-id
                          {"type" "inline" "path" "/workspace/in.txt" "data" (.getBytes "hello" "UTF-8")})]
            (is (= "agent.environment.file" (get created "object")))
            (is (= 5 (get created "size_bytes"))))
          (let [params {"limit" 1 "path" "/workspace"}
                page1  (agents/environments-files-list client env-id params)]
            (is (= "page" (get page1 "object")))
            (is (<= (count (get page1 "data")) 1))
            (when (get page1 "has_more")
              (is (string? (get page1 "next")))
              (let [page2 (agents/environments-files-list client env-id (assoc params "page" (get page1 "next")))]
                (is (= "page" (get page2 "object")))
                (is (not= (get page1 "data") (get page2 "data")))))))
        (let [turn (live-poll #(let [t (agents/latest-root-turn (agents/sessions-turns-list client session-id))]
                                 (when (agents/turn-finished? t) t))
                              2000 300000)]
          (is (= "completed" (get turn "status")) (pr-str (get turn "error")))
          (let [listed   (agents/sessions-artifacts-list client session-id)
                artifact (some #(when (and (= "/workspace/outputs/p.bin" (get % "path"))
                                           (= (get turn "id") (get % "turn_id")))
                                  %)
                               (get listed "data"))
                aid      (get artifact "id")]
            (is (= "list" (get listed "object")))
            (is (some? artifact) (pr-str listed))
            (when aid
              (is (= 8 (get artifact "size_bytes")))
              (is (= artifact (agents/sessions-artifacts-retrieve client session-id aid)))
              ;; A 3xx here (api-status-error) means the API redirects: record it.
              (let [content (agents/sessions-artifacts-content client session-id aid)]
                (is (java.util.Arrays/equals ^bytes expected ^bytes content) (pr-str (vec content))))
              (is (true? (get (agents/sessions-artifacts-delete client session-id aid) "deleted")))
              (let [e (try (agents/sessions-artifacts-retrieve client session-id aid) nil
                           (catch Exception e e))]
                (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))))))
        (finally
          (when session-id (live-cleanup-session! client session-id)))))
    (is true "skipped: set OPENAI_AGENTS_LIVE=1 and OPENAI_API_KEY")))

;; ---------------------------------------------------------------------------
;; :credential-source (#34) — inherited from the openai client
;; ---------------------------------------------------------------------------

(deftest sessions-create-with-credential-source-refreshes-on-401
  (let [auths (atom [])
        {:keys [source fetches]} (rotating-token-cache)
        {:keys [port stop!]} (start-server! 19079 "/v1/agents/sessions"
                                (fn [req]
                                  (swap! auths conj [(get (:headers req) "authorization")
                                                     (get (:headers req) "openai-beta")])
                                  (if (= 1 (count @auths))
                                    {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                                    {:status 200 :body (canned-session "sess_1" "in_progress")})))]
    (try
      (let [client  (oai/client {:credential-source source :base-url (base-url port) :max-retries 0})
            session (agents/sessions-create client {"agent" {"model" "m"} "input" "hi"})]
        (is (= "sess_1" (get session "id")))
        (is (= [["Bearer tok-1" "agents=v1"] ["Bearer tok-2" "agents=v1"]] @auths))
        (is (= 2 @fetches)))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; REAL-API streaming probe (#25, P3). Same gate and env overrides as above.
;; COSTS MONEY: one `environment: none` session and two short turns. Covers
;; sessions-create-stream (first turn in the POST response) and
;; sessions-events-stream opened before send-message (the guide's order).
;; ---------------------------------------------------------------------------

(deftest session-event-streaming-against-the-real-api
  (if-let [api-key (and (= "1" (System/getenv "OPENAI_AGENTS_LIVE"))
                       (not-empty (System/getenv "OPENAI_API_KEY")))]
    (let [client     (oai/client {:api-key api-key
                                  :base-url (env "OPENAI_AGENTS_BASE_URL" "https://api.openai.com/v1")})
          model      (env "OPENAI_AGENTS_MODEL" "gpt-5.5")
          session-id (atom nil)]
      (try
        (let [s    (agents/sessions-create-stream client
                     {"agent" {"model" model "instructions" "Reply with exactly: OK"}
                      "environment" {"type" "none"}
                      "input" "Say OK."})
              ;; capture the id from whichever event carries it first, so cleanup never leaks a paid session
              done (agents/await-root-turn s {:on-event #(when-let [id (or (get-in % ["session" "id"])
                                                                            (get % "session_id"))]
                                                           (compare-and-set! session-id nil id))})]
          (is (string? @session-id))
          (is (= "completed" (get-in done ["turn" "status"])) (pr-str done)))
        (when @session-id
          (let [s    (agents/sessions-events-stream client @session-id)
                _    (agents/send-message client @session-id "Say OK again.")
                text (StringBuilder.)
                done (agents/await-root-turn s {:on-event #(some->> (get % "delta") (.append text))})]
            (is (= "completed" (get-in done ["turn" "status"])) (pr-str done))
            (is (str/includes? (str text) "OK") (str text))))
        (finally
          (when @session-id (live-cleanup-session! client @session-id)))))
    (is true "skipped: set OPENAI_AGENTS_LIVE=1 and OPENAI_API_KEY")))

;; ---------------------------------------------------------------------------
;; callable :api-key (#37) — inherited from the openai client, streaming open
;; included. OS-assigned ports.
;; ---------------------------------------------------------------------------

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort s)))

(deftest sessions-create-with-api-key-fn-calls-it-per-attempt
  (let [auths (atom [])
        calls (atom 0)
        {:keys [port stop!]} (start-server! 0 "/v1/agents/sessions"
                                (fn [req]
                                  (swap! auths conj [(get (:headers req) "authorization")
                                                     (get (:headers req) "openai-beta")])
                                  (if (= 1 (count @auths))
                                    {:status 503 :headers {"retry-after-ms" "1"} :body "{}"}
                                    {:status 200 :body (canned-session "sess_1" "in_progress")})))]
    (try
      (let [client  (oai/client {:api-key #(str "entra-" (swap! calls inc)) :base-url (base-url port)})
            session (agents/sessions-create client {"agent" {"model" "m"} "input" "hi"})]
        (is (= "sess_1" (get session "id")))
        (is (= [["Bearer entra-1" "agents=v1"] ["Bearer entra-2" "agents=v1"]] @auths)))
      (finally (stop!)))))

(deftest sessions-events-stream-open-calls-api-key-fn-per-attempt
  (let [auths (atom [])
        calls (atom 0)
        {:keys [port stop!]}
        (start-server! 0 "/v1/agents/sessions/sess_1/events"
          (fn [req]
            (swap! auths conj (get (:headers req) "authorization"))
            (if (= 1 (count @auths))
              {:status 429 :headers {"retry-after-ms" "1"} :body "{}"}
              {:status 200 :headers sse-headers
               :body (fn [send!] (doseq [f (fixture-frames)] (send! f)))})))]
    (try
      (let [client (oai/client {:api-key #(str "entra-" (swap! calls inc)) :base-url (base-url port)})
            events (into [] (agents/sessions-events-stream client "sess_1"))]
        (is (seq events))
        (is (= ["Bearer entra-1" "Bearer entra-2"] @auths))
        (is (= 2 @calls)))
      (finally (stop!)))))

(deftest sessions-create-with-api-key-fn-401-is-not-retried
  (let [hits  (atom 0)
        calls (atom 0)
        {:keys [port stop!]} (start-server! 0 "/v1/agents/sessions"
                                (fn [_] (swap! hits inc) {:status 401 :body "{}"}))]
    (try
      (let [client (oai/client {:api-key #(str "entra-" (swap! calls inc)) :base-url (base-url port)})
            e      (try (agents/sessions-create client {"agent" {"model" "m"} "input" "hi"}) nil
                        (catch Exception e e))]
        (is (= :tools.agents.openai/authentication-error (:type (ex-data e))))
        (is (= [1 1] [@hits @calls])))
      (finally (stop!)))))
