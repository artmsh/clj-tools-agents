(ns tools.agents.anthropic.agents-test
  "tools.agents.anthropic.agents against the shared mock server
   (tools.agents.test-support): method, path, query, body and headers for
   every resource function, paging, the event helpers, the SSE stream,
   `await-idle` and `resume-stream` — identical assertions on both runtimes.

   Nothing here talks to the real API: no key with Managed Agents access was
   available, so the fixtures are the shapes Anthropic's documentation shows
   (see docs/anthropic-agents.md), written with `event:` lines because
   anthropic-sdk-python's Stream dispatches on the SSE event name."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.agents :as ag]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [closed-port start-server! start-abort-server!]]))

(def ^:private beta "managed-agents-2026-04-01")

(def ^:private sse-headers {"content-type" "text/event-stream"})

(defn- base-url [port] (str "http://127.0.0.1:" port))

(defn- url-decode [s] (java.net.URLDecoder/decode (str s) "UTF-8"))

(defn- parse-query
  "Raw query string -> {name [value ...]}, names and values URL-decoded.
   Decoding first makes the two runtimes agree (the JDK server's `.getQuery`
   has already decoded `%5B%5D`, httpkit's `:query-string` has not), and the
   vector keeps repeated names such as `types[]`."
  [query]
  (reduce (fn [m pair]
            (let [[k v] (str/split pair #"=" 2)]
              (update m (url-decode k) (fnil conj []) (url-decode (or v "")))))
          {}
          (remove str/blank? (str/split (or query "") #"&"))))

(defn- with-mock
  "Start the mock on an OS-assigned port, call (f client captured), stop.
   `captured` holds every request in arrival order."
  ([handler f] (with-mock {} handler f))
  ([client-opts handler f]
   (let [captured (atom [])
         {:keys [port stop!]} (start-server! 0 "/" (fn [req] (swap! captured conj req) (handler req)))]
     (try
       (f (a/client (merge {:api-key "test-key" :base-url (base-url port)} client-opts)) captured)
       (finally (stop!))))))

(defn- ok [json] (fn [_] {:status 200 :headers {"request-id" "req_1"} :body json}))

(defn- caught [thunk] (try (thunk) nil (catch Exception e e)))

(defn- dead-client
  "A client whose base-url nothing listens on: a call that reaches the
   network fails with :api-connection, so any other error came first."
  []
  (a/client {:api-key "k" :base-url (base-url (closed-port)) :max-retries 0}))

(defn- sse [event json] (str "event: " event "\ndata: " json "\n\n"))

(defn- ev-json [m] (a/write-json m))

(defn- frame
  "One SSE frame for event map m, named by its \"type\" as the API does."
  [m]
  (sse (get m "type") (ev-json m)))

(defn- until-gone!
  "Streaming-body helper: SSE comments every 10 ms until the client
   disconnects, then deliver `gone` true (false after ~5 s)."
  [send! gone]
  (loop [i 0]
    (cond
      (not (send! ": keep-alive\n\n")) (deliver gone true)
      (> i 500)                        (deliver gone false)
      :else                            (do (Thread/sleep 10) (recur (inc i))))))

;; ---------------------------------------------------------------------------
;; Resource functions: method, path, query, body
;; ---------------------------------------------------------------------------

(def ^:private body {"name" "n" "metadata" {"k" "v"}})

(def ^:private endpoint-cases
  "[label call method path extra-query body]. Every call must also send
   `beta=true`; `extra-query` is what the call's params add to it."
  [["agents-create"         #(ag/agents-create % body)                        "POST"   "/v1/agents"                    {} body]
   ["agents-retrieve"       #(ag/agents-retrieve % "agent_1")                 "GET"    "/v1/agents/agent_1"            {} nil]
   ["agents-retrieve v"     #(ag/agents-retrieve % "agent_1" {"version" 2})   "GET"    "/v1/agents/agent_1"            {"version" ["2"]} nil]
   ["agents-update"         #(ag/agents-update % "agent_1" body)              "POST"   "/v1/agents/agent_1"            {} body]
   ["agents-list"           #(ag/agents-list %)                               "GET"    "/v1/agents"                    {} nil]
   ["agents-list params"    #(ag/agents-list % {"limit" 5 "include_archived" true
                                                "created_at[gte]" "2026-01-01T00:00:00Z"})
    "GET" "/v1/agents" {"limit" ["5"] "include_archived" ["true"] "created_at[gte]" ["2026-01-01T00:00:00Z"]} nil]
   ["agents-archive"        #(ag/agents-archive % "agent_1")                  "POST"   "/v1/agents/agent_1/archive"    {} nil]
   ["agents-versions-list"  #(ag/agents-versions-list % "agent_1" {"limit" 2}) "GET"   "/v1/agents/agent_1/versions"   {"limit" ["2"]} nil]
   ["environments-create"   #(ag/environments-create % body)                  "POST"   "/v1/environments"              {} body]
   ["environments-retrieve" #(ag/environments-retrieve % "env_1")             "GET"    "/v1/environments/env_1"        {} nil]
   ["environments-update"   #(ag/environments-update % "env_1" body)          "POST"   "/v1/environments/env_1"        {} body]
   ["environments-list"     #(ag/environments-list % {"page" "page_abc"})     "GET"    "/v1/environments"              {"page" ["page_abc"]} nil]
   ["environments-delete"   #(ag/environments-delete % "env_1")               "DELETE" "/v1/environments/env_1"        {} nil]
   ["environments-archive"  #(ag/environments-archive % "env_1")              "POST"   "/v1/environments/env_1/archive" {} nil]
   ["sessions-create"       #(ag/sessions-create % body)                      "POST"   "/v1/sessions"                  {} body]
   ["sessions-retrieve"     #(ag/sessions-retrieve % "sesn_1")                "GET"    "/v1/sessions/sesn_1"           {} nil]
   ["sessions-update"       #(ag/sessions-update % "sesn_1" body)             "POST"   "/v1/sessions/sesn_1"           {} body]
   ["sessions-list"         #(ag/sessions-list % {"agent_id" "agent_1" "order" :asc
                                                  "statuses" ["idle" "running"]})
    "GET" "/v1/sessions" {"agent_id" ["agent_1"] "order" ["asc"] "statuses[]" ["idle" "running"]} nil]
   ["sessions-delete"       #(ag/sessions-delete % "sesn_1")                  "DELETE" "/v1/sessions/sesn_1"           {} nil]
   ["sessions-archive"      #(ag/sessions-archive % "sesn_1")                 "POST"   "/v1/sessions/sesn_1/archive"   {} nil]
   ["sessions-events-send"  #(ag/sessions-events-send % "sesn_1" {"events" [{"type" "user.interrupt"}]})
    "POST" "/v1/sessions/sesn_1/events" {} {"events" [{"type" "user.interrupt"}]}]
   ["sessions-events-list"  #(ag/sessions-events-list % "sesn_1" {"types" ["agent.tool_use" "agent.tool_result"]
                                                                  "limit" 10})
    "GET" "/v1/sessions/sesn_1/events" {"types[]" ["agent.tool_use" "agent.tool_result"] "limit" ["10"]} nil]])

(deftest every-resource-function-sends-the-documented-request
  (doseq [[label call method path extra-query req-body] endpoint-cases]
    (testing label
      (with-mock
        (ok "{\"id\":\"x_1\",\"type\":\"thing\"}")
        (fn [client captured]
          (let [resp (call client)
                req  (first @captured)]
            (is (= 1 (count @captured)))
            (is (= {"id" "x_1" "type" "thing"} resp) "the decoded body, verbatim")
            (is (= "req_1" (a/request-id resp)) "response headers ride as metadata")
            (is (= method (:method req)))
            (is (= path (:path req)))
            (is (= (assoc extra-query "beta" ["true"]) (parse-query (:query req))))
            (if req-body
              (is (= req-body (a/read-json (:body req))) "body is the request map, verbatim")
              (is (= "" (:body req)) "no body"))
            (is (= "test-key" (get-in req [:headers "x-api-key"])))
            (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
            (is (= beta (get-in req [:headers "anthropic-beta"])))))))))

(deftest beta-flag-joins-the-oauth-flag-and-the-clients-own-betas
  ;; The flag must go through the client's :betas. A literal `anthropic-beta`
  ;; request header would be merged OVER the auth headers and drop both the
  ;; OAuth flag (breaking auth) and the caller's flags.
  (with-mock {:api-key nil :auth-token "tok-abc" :betas ["files-api-2025-04-14"]}
    (fn [req]
      (if (str/ends-with? (:path req) "/stream")
        {:status 200 :headers sse-headers :body ""}
        {:status 200 :body "{}"}))
    (fn [client captured]
      (ag/sessions-retrieve client "sesn_1")
      (into [] (ag/sessions-events-stream client "sesn_1"))
      (doseq [req @captured]
        (is (= "Bearer tok-abc" (get-in req [:headers "authorization"])))
        (is (= (str "oauth-2025-04-20,files-api-2025-04-14," beta)
               (get-in req [:headers "anthropic-beta"]))))
      (is (= 2 (count @captured))))))

(deftest a-client-that-already-carries-the-flag-sends-it-once
  (with-mock {:betas [beta]}
    (ok "{}")
    (fn [client captured]
      (ag/agents-list client)
      (is (= beta (get-in (first @captured) [:headers "anthropic-beta"]))))))

(deftest an-empty-or-non-string-id-throws-before-any-request
  (let [client (dead-client)]
    (doseq [[label thunk] [["agents-retrieve"       #(ag/agents-retrieve client "")]
                           ["agents-update"         #(ag/agents-update client nil {})]
                           ["agents-archive"        #(ag/agents-archive client "")]
                           ["agents-versions-list"  #(ag/agents-versions-list client "")]
                           ["environments-retrieve" #(ag/environments-retrieve client "")]
                           ["environments-update"   #(ag/environments-update client "" {})]
                           ["environments-delete"   #(ag/environments-delete client "")]
                           ["environments-archive"  #(ag/environments-archive client :env)]
                           ["sessions-retrieve"     #(ag/sessions-retrieve client "")]
                           ["sessions-update"       #(ag/sessions-update client "" {})]
                           ["sessions-delete"       #(ag/sessions-delete client "")]
                           ["sessions-archive"      #(ag/sessions-archive client "")]
                           ["sessions-events-send"  #(ag/sessions-events-send client "" {})]
                           ["sessions-events-list"  #(ag/sessions-events-list client "")]
                           ["sessions-events-stream" #(ag/sessions-events-stream client "")]
                           ["send-message"          #(ag/send-message client "" "hi")]
                           ["resume-stream"         #(ag/resume-stream client "")]]]
      (testing label
        (let [e (caught thunk)]
          (is (= :tools.agents.anthropic.error/invalid-argument (:type (ex-data e))))
          (is (str/includes? (str (ex-message e)) "tools.agents.anthropic.agents/")))))))

(deftest an-id-is-percent-encoded-as-one-path-segment
  ;; Tested on the private fn: the JDK mock server decodes the path before the
  ;; handler sees it and httpkit does not, so the wire form is not observable
  ;; the same way on both runtimes.
  (is (= "a%2Fb%3Fc%20d%23e" (#'ag/segment "f" "id" "a/b?c d#e")))
  (is (= "agent_1%2F..%2Fsessions" (#'ag/segment "f" "id" "agent_1/../sessions"))))

;; ---------------------------------------------------------------------------
;; Errors and retries — tools.agents.anthropic/request!'s, unchanged
;; ---------------------------------------------------------------------------

(deftest a-404-is-typed-not-found-with-this-namespaces-message-prefix
  (with-mock
    (fn [_] {:status 404 :body "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"Session not found\"}}"})
    (fn [client captured]
      (let [e (caught #(ag/sessions-retrieve client "sesn_missing"))]
        (is (= :tools.agents.anthropic.error/not-found (:type (ex-data e))))
        (is (= 404 (:status (ex-data e))))
        (is (= "tools.agents.anthropic.agents/sessions-retrieve: HTTP 404 Session not found" (ex-message e)))
        (is (= 1 (count @captured)) "a 404 is not retried")))))

(deftest a-429-is-retried-then-succeeds
  (let [hits (atom 0)]
    (with-mock {:max-retries 2}
      (fn [_] (if (= 1 (swap! hits inc))
                {:status 429 :headers {"retry-after" "0"} :body "{\"error\":{\"message\":\"slow down\"}}"}
                {:status 200 :body "{\"id\":\"sesn_1\"}"}))
      (fn [client captured]
        (binding [a/*sleep-fn* (fn [_] nil)]
          (is (= "sesn_1" (get (ag/sessions-create client {"agent" "agent_1" "environment_id" "env_1"}) "id"))))
        (is (= 2 (count @captured)))
        (is (= (:body (first @captured)) (:body (second @captured))) "the retry resends the same body")))))

(deftest a-stale-agent-version-409-is-retried-and-surfaces-as-api-status
  ;; 409 is in the retryable set (as in anthropic-sdk-python), so the
  ;; documented optimistic-concurrency failure costs the retries first.
  (with-mock {:max-retries 1}
    (fn [_] {:status 409 :body "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"version mismatch\"}}"})
    (fn [client captured]
      (let [e (binding [a/*sleep-fn* (fn [_] nil)]
                (caught #(ag/agents-update client "agent_1" {"version" 1 "system" "x"})))]
        (is (= :tools.agents.anthropic.error/api-status (:type (ex-data e))))
        (is (= 409 (:status (ex-data e))))
        (is (= 2 (count @captured)))))))

(deftest a-connection-failure-is-typed
  (let [e (caught #(ag/agents-list (dead-client)))]
    (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e))))
    (is (str/starts-with? (ex-message e) "tools.agents.anthropic.agents/agents-list: "))))

;; ---------------------------------------------------------------------------
;; Paging
;; ---------------------------------------------------------------------------

(deftest next-page-params-follows-the-next-page-cursor
  (is (= {"limit" 2 "page" "page_b"} (ag/next-page-params {"limit" 2} {"data" [] "next_page" "page_b"})))
  (is (= {"limit" 2 "page" "page_c"} (ag/next-page-params {"limit" 2 "page" "page_b"} {"next_page" "page_c"})))
  (is (= {"page" "page_b"} (ag/next-page-params nil {"next_page" "page_b"})))
  (is (= {"page" "page_b"} (ag/next-page-params {:page "page_a"} {"next_page" "page_b"})) "a keyword :page is replaced")
  (is (nil? (ag/next-page-params {"limit" 2} {"data" [] "next_page" nil})))
  (is (nil? (ag/next-page-params {"limit" 2} {"data" []})))
  (is (nil? (ag/next-page-params {"limit" 2} {"next_page" ""}))))

(deftest list-all-walks-every-page-lazily
  (with-mock
    (fn [req]
      (let [page (first (get (parse-query (:query req)) "page"))]
        {:status 200
         :body (case page
                 nil      "{\"data\":[{\"id\":\"agent_1\"},{\"id\":\"agent_2\"}],\"next_page\":\"page_2\"}"
                 "page_2" "{\"data\":[{\"id\":\"agent_3\"}],\"next_page\":\"page_3\"}"
                 "page_3" "{\"data\":[],\"next_page\":null}")}))
    (fn [client captured]
      (let [agents (ag/list-all #(ag/agents-list client %) {"limit" 2})]
        (is (= "agent_1" (get (first agents) "id")))
        (is (= 1 (count @captured)) "only the first page is fetched for the first item")
        (is (= ["agent_1" "agent_2" "agent_3"] (mapv #(get % "id") agents)))
        (is (= [nil ["page_2"] ["page_3"]] (mapv #(get (parse-query (:query %)) "page") @captured)))
        (is (every? #(= ["2"] (get (parse-query (:query %)) "limit")) @captured) "other params carry over")))))

;; ---------------------------------------------------------------------------
;; Event helpers
;; ---------------------------------------------------------------------------

(defn- sent-events
  "The \"events\" array a helper call POSTed, plus a check of where it went."
  [call]
  (with-mock
    (ok "{\"data\":[{\"id\":\"sevt_1\",\"type\":\"user.message\",\"processed_at\":null}]}")
    (fn [client captured]
      (let [resp (call client)
            req  (first @captured)]
        (is (= "sevt_1" (get-in resp ["data" 0 "id"])) "returns the send response")
        (is (= "POST" (:method req)))
        (is (= "/v1/sessions/sesn_1/events" (:path req)))
        (is (= {"beta" ["true"]} (parse-query (:query req))))
        (get (a/read-json (:body req)) "events")))))

(deftest send-message-wraps-a-string-in-one-text-block
  (is (= [{"type" "user.message" "content" [{"type" "text" "text" "Summarize the README"}]}]
         (sent-events #(ag/send-message % "sesn_1" "Summarize the README")))))

(deftest send-message-passes-content-blocks-through
  (let [blocks [{"type" "text" "text" "What is this?"}
                {"type" "image" "source" {"type" "url" "url" "https://example.com/a.png"}}]]
    (is (= [{"type" "user.message" "content" blocks}]
           (sent-events #(ag/send-message % "sesn_1" blocks))))))

(deftest interrupt-sends-a-bare-user-interrupt
  (is (= [{"type" "user.interrupt"}] (sent-events #(ag/interrupt % "sesn_1"))))
  (is (= [{"type" "user.interrupt" "session_thread_id" "sthr_1"}]
         (sent-events #(ag/interrupt % "sesn_1" {:session-thread-id "sthr_1"})))))

(deftest send-custom-tool-result-shapes
  (is (= [{"type" "user.custom_tool_result" "custom_tool_use_id" "sevt_tool"
           "content" [{"type" "text" "text" "72F and sunny"}]}]
         (sent-events #(ag/send-custom-tool-result % "sesn_1" {:custom-tool-use-id "sevt_tool"
                                                               :content "72F and sunny"}))))
  (is (= [{"type" "user.custom_tool_result" "custom_tool_use_id" "sevt_tool"
           "content" [{"type" "text" "text" "upstream timed out"}] "is_error" true}]
         (sent-events #(ag/send-custom-tool-result % "sesn_1" {:custom-tool-use-id "sevt_tool"
                                                               :content [{"type" "text" "text" "upstream timed out"}]
                                                               :is-error true}))))
  (is (= [{"type" "user.custom_tool_result" "custom_tool_use_id" "sevt_tool"}]
         (sent-events #(ag/send-custom-tool-result % "sesn_1" {:custom-tool-use-id "sevt_tool"})))
      "content and is_error are omitted, not sent as null"))

(deftest confirm-tool-shapes
  (is (= [{"type" "user.tool_confirmation" "tool_use_id" "sevt_tool" "result" "allow"}]
         (sent-events #(ag/confirm-tool % "sesn_1" {:tool-use-id "sevt_tool" :result :allow}))))
  (is (= [{"type" "user.tool_confirmation" "tool_use_id" "sevt_tool" "result" "deny"
           "deny_message" "not in production"}]
         (sent-events #(ag/confirm-tool % "sesn_1" {:tool-use-id "sevt_tool" :result "deny"
                                                    :deny-message "not in production"})))))

;; ---------------------------------------------------------------------------
;; Pure readers
;; ---------------------------------------------------------------------------

(defn- idle
  ([id reason] (idle id reason nil))
  ([id reason event-ids]
   {"type" "session.status_idle" "id" id "processed_at" "2026-10-09T10:00:00Z" "stop_details" nil
    "stop_reason" (cond-> {"type" reason} event-ids (assoc "event_ids" event-ids))}))

(defn- agent-message [id text]
  {"type" "agent.message" "id" id "processed_at" "2026-10-09T10:00:00Z"
   "content" [{"type" "text" "text" text}]})

(defn- session-error [id retry-status]
  {"type" "session.error" "id" id
   "error" {"type" "model_overloaded_error" "message" "The model is overloaded."
            "retry_status" {"type" retry-status}}})

(def ^:private running {"type" "session.status_running" "id" "sevt_run" "processed_at" "2026-10-09T10:00:00Z"})

(deftest events-output-text-joins-agent-message-text-blocks
  (is (= "Hello, world."
         (ag/events-output-text [{"type" "user.message" "id" "sevt_u" "content" [{"type" "text" "text" "hi"}]}
                                 (agent-message "sevt_1" "Hello, ")
                                 {"type" "agent.tool_use" "id" "sevt_t" "name" "bash" "input" {}}
                                 {"type" "agent.message" "id" "sevt_2"
                                  "content" [{"type" "redacted"} {"type" "text" "text" "world."}]}
                                 (idle "sevt_i" "end_turn")])))
  (is (= "" (ag/events-output-text [])))
  (is (= "" (ag/events-output-text [{"type" "agent.message" "id" "sevt_1" "content" nil}]))))

(deftest stop-reason-and-blocking-event-ids-read-the-idle-event
  (is (= "end_turn" (ag/stop-reason (idle "sevt_i" "end_turn"))))
  (is (= "requires_action" (ag/stop-reason (idle "sevt_i" "requires_action" ["sevt_a" "sevt_b"]))))
  (is (= ["sevt_a" "sevt_b"] (ag/blocking-event-ids (idle "sevt_i" "requires_action" ["sevt_a" "sevt_b"]))))
  (is (= [] (ag/blocking-event-ids (idle "sevt_i" "end_turn"))))
  (is (nil? (ag/stop-reason running)))
  (is (nil? (ag/stop-reason {"type" "session.thread_status_idle" "stop_reason" {"type" "end_turn"}}))
      "a thread's idle is not the session's")
  (is (nil? (ag/stop-reason nil))))

(deftest latest-status-is-the-last-session-level-status-event
  (is (= "sevt_i2" (get (ag/latest-status [running (idle "sevt_i1" "requires_action" ["sevt_a"])
                                           running (agent-message "sevt_m" "done")
                                           (idle "sevt_i2" "end_turn")
                                           {"type" "session.thread_status_running" "id" "sevt_tr"}])
                        "id")))
  (is (= "session.status_running" (get (ag/latest-status [(idle "sevt_i" "end_turn") running]) "type")))
  (is (nil? (ag/latest-status [(agent-message "sevt_m" "hi")])))
  (is (nil? (ag/latest-status []))))

;; ---------------------------------------------------------------------------
;; await-idle — pure rules over plain collections
;; ---------------------------------------------------------------------------

(deftest await-idle-returns-the-idle-event-for-every-stop-reason
  (doseq [reason ["end_turn" "requires_action" "retries_exhausted" "budget_reached" "refusal"]]
    (testing reason
      (let [ev (ag/await-idle [running (agent-message "sevt_m" "x") (idle "sevt_i" reason) (agent-message "sevt_n" "late")])]
        (is (= "sevt_i" (get ev "id")))
        (is (= reason (ag/stop-reason ev)) "idle is returned, not judged: the caller reads the stop reason")))))

(deftest await-idle-stops-reading-at-the-idle-event
  (let [seen (atom [])]
    (ag/await-idle [running (idle "sevt_i" "end_turn") (agent-message "sevt_late" "never read")]
                   {:on-event #(swap! seen conj (get % "id"))})
    (is (= ["sevt_run" "sevt_i"] @seen) ":on-event sees every event up to and including the idle")))

(deftest await-idle-requires-action-carries-both-blocking-ids
  (let [ev (ag/await-idle [running
                           {"type" "agent.custom_tool_use" "id" "sevt_a" "name" "get_weather" "input" {"city" "Kyiv"}}
                           {"type" "agent.custom_tool_use" "id" "sevt_b" "name" "get_time" "input" {}}
                           (idle "sevt_i" "requires_action" ["sevt_a" "sevt_b"])])]
    (is (= "requires_action" (ag/stop-reason ev)))
    (is (= ["sevt_a" "sevt_b"] (ag/blocking-event-ids ev)))))

(deftest await-idle-waits-through-retrying-and-exhausted-session-errors
  (let [seen (atom [])
        ev   (ag/await-idle [running (session-error "sevt_e1" "retrying") (session-error "sevt_e2" "retrying")
                             (session-error "sevt_e3" "exhausted") (idle "sevt_i" "retries_exhausted")]
                            {:on-event #(swap! seen conj (get % "id"))})]
    (is (= "retries_exhausted" (ag/stop-reason ev)))
    (is (= ["sevt_run" "sevt_e1" "sevt_e2" "sevt_e3" "sevt_i"] @seen) "the errors still reach :on-event")))

(deftest await-idle-throws-on-a-terminal-session-error
  (let [seen (atom [])
        e    (caught #(ag/await-idle [running (session-error "sevt_e" "terminal") (idle "sevt_i" "end_turn")]
                                     {:on-event (fn [ev] (swap! seen conj (get ev "id")))}))]
    (is (= :tools.agents.anthropic.error/session-failed (:type (ex-data e))))
    (is (= "model_overloaded_error" (get-in (ex-data e) [:error "type"])))
    (is (= "sevt_e" (get-in (ex-data e) [:event "id"])))
    (is (= {:status nil :body nil} (select-keys (ex-data e) [:status :body])))
    (is (= "tools.agents.anthropic.agents/await-idle: session.error model_overloaded_error: The model is overloaded."
           (ex-message e)))
    (is (= ["sevt_run" "sevt_e"] @seen) ":on-event saw the failing event first")))

(deftest await-idle-throws-on-terminated-and-deleted
  (let [e (caught #(ag/await-idle [running {"type" "session.status_terminated" "id" "sevt_t"}]))]
    (is (= :tools.agents.anthropic.error/session-terminated (:type (ex-data e))))
    (is (= "sevt_t" (get-in (ex-data e) [:event "id"]))))
  (let [e (caught #(ag/await-idle [{"type" "session.deleted" "id" "sevt_d"}]))]
    (is (= :tools.agents.anthropic.error/session-deleted (:type (ex-data e))))))

(deftest await-idle-ignores-thread-status-events
  (let [ev (ag/await-idle [{"type" "session.thread_status_idle" "id" "sevt_ti" "stop_reason" {"type" "end_turn"}}
                           {"type" "session.thread_status_terminated" "id" "sevt_tt"}
                           (idle "sevt_i" "end_turn")])]
    (is (= "sevt_i" (get ev "id")))))

(deftest await-idle-throws-stream-truncated-when-events-end-first
  (let [e (caught #(ag/await-idle [running (agent-message "sevt_m" "half an ans")]))]
    (is (= :tools.agents.anthropic.error/stream-truncated (:type (ex-data e))))
    (is (nil? (:outcome (ex-data e))) "a plain collection has no stream outcome"))
  (is (= :tools.agents.anthropic.error/stream-truncated (:type (ex-data (caught #(ag/await-idle [])))))))

(deftest await-idle-throws-an-error-event-in-a-collection-as-the-stream-would
  (let [err {"type" "error" "error" {"type" "overloaded_error" "message" "Overloaded"}}
        e   (caught #(ag/await-idle [running err (idle "sevt_i" "end_turn")]))]
    (is (= :tools.agents.anthropic.error/overloaded (:type (ex-data e))))
    (is (= {"type" "overloaded_error" "message" "Overloaded"} (:error (ex-data e))))
    (is (= err (:event (ex-data e))))
    (is (nil? (:body (ex-data e))))))

(deftest await-idle-done?-waits-through-idles-it-rejects
  (let [ev (ag/await-idle [(idle "sevt_i1" "requires_action" ["sevt_a"]) running (idle "sevt_i2" "end_turn")]
                          {:done? #(not= "requires_action" (ag/stop-reason %))})]
    (is (= "sevt_i2" (get ev "id")))))

;; ---------------------------------------------------------------------------
;; sessions-events-stream
;; ---------------------------------------------------------------------------

(deftest sessions-events-stream-gets-the-stream-path-and-delivers-incrementally
  (let [seen-first (promise)
        server-saw (promise)
        events     [running (agent-message "sevt_m" "Hello") (idle "sevt_i" "end_turn")]]
    (with-mock
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send! (frame (first events)))
                       ;; The rest goes out only once the reducer has seen event 1.
                       (deliver server-saw (deref seen-first 5000 :timeout))
                       (doseq [e (rest events)] (send! (frame e))))})
      (fn [client captured]
        (let [s   (ag/sessions-events-stream client "sesn_1" {"event_deltas" ["agent.message" "agent.thinking"]})
              got (reduce (fn [acc ev] (deliver seen-first :seen) (conj acc ev)) [] s)
              req (first @captured)]
          (is (= :seen @server-saw) "event 1 reached the reducer while the server was still blocked")
          (is (= events got) "decoded JSON maps, verbatim")
          (is (= "GET" (:method req)))
          (is (= "/v1/sessions/sesn_1/events/stream" (:path req)))
          (is (= {"beta" ["true"] "event_deltas[]" ["agent.message" "agent.thinking"]} (parse-query (:query req))))
          (is (= "text/event-stream" (get-in req [:headers "accept"])))
          (is (= "test-key" (get-in req [:headers "x-api-key"])))
          (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
          (is (= beta (get-in req [:headers "anthropic-beta"])))
          (is (= "" (:body req)))
          (is (= :eof (stream/outcome s)))
          (is (= 200 (:status (stream/response s)))))
        (deliver seen-first :cleanup)))))

(deftest sessions-events-stream-framing-follows-the-sdk
  (with-mock
    (fn [_] {:status 200 :headers sse-headers
             :body (str ": comment\n\n"
                        ;; data without "type": filled from the SSE event name
                        (sse "session.status_running" "{\"id\":\"sevt_1\"}")
                        ;; pings, with and without a data payload, are dropped
                        (sse "ping" "{\"type\":\"ping\"}")
                        "event: ping\ndata:\n\n"
                        ;; an event name the SDK does not list is still delivered
                        (sse "session.some_future_event" "{\"type\":\"session.some_future_event\",\"id\":\"sevt_2\"}")
                        ;; stream-only previews have no id of their own
                        (sse "event_start" "{\"type\":\"event_start\",\"event\":{\"type\":\"agent.message\",\"id\":\"sevt_3\"}}")
                        (frame (idle "sevt_4" "end_turn")))})
    (fn [client _]
      (is (= [{"id" "sevt_1" "type" "session.status_running"}
              {"type" "session.some_future_event" "id" "sevt_2"}
              {"type" "event_start" "event" {"type" "agent.message" "id" "sevt_3"}}
              (idle "sevt_4" "end_turn")]
             (into [] (ag/sessions-events-stream client "sesn_1")))))))

(deftest sessions-events-stream-early-termination-closes-the-connection
  (let [gone (promise)]
    (with-mock
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send! (frame running))
                       (send! (frame (idle "sevt_i" "end_turn")))
                       (until-gone! send! gone))})
      (fn [client _]
        (let [s (ag/sessions-events-stream client "sesn_1")]
          (is (= "end_turn" (ag/stop-reason (ag/await-idle s))))
          (is (true? (deref gone 5000 :timeout))
              "the server keeps an idle session's stream open; await-idle hangs up")
          (is (= :reduced (stream/outcome s)))
          (is (= :tools.agents.stream/consumed (:type (ex-data (caught #(into [] s)))))))))))

(deftest sessions-events-stream-http-error-before-the-stream-is-typed
  (with-mock
    (fn [_] {:status 404 :headers {"content-type" "application/json"}
             :body "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"Session not found\"}}"})
    (fn [client captured]
      (let [e (caught #(ag/sessions-events-stream client "sesn_missing"))]
        (is (= :tools.agents.anthropic.error/not-found (:type (ex-data e))))
        (is (= 404 (:status (ex-data e))))
        (is (= "tools.agents.anthropic.agents/sessions-events-stream: HTTP 404 Session not found" (ex-message e)))
        (is (= 1 (count @captured)) "a 404 is not retried")))))

(deftest sessions-events-stream-retries-a-503-before-the-first-byte
  (let [hits (atom 0)]
    (with-mock {:max-retries 1}
      (fn [_] (if (= 1 (swap! hits inc))
                {:status 503 :body "{\"error\":{\"message\":\"busy\"}}"}
                {:status 200 :headers sse-headers :body (frame (idle "sevt_i" "end_turn"))}))
      (fn [client captured]
        (binding [a/*sleep-fn* (fn [_] nil)]
          (is (= [(idle "sevt_i" "end_turn")] (into [] (ag/sessions-events-stream client "sesn_1")))))
        (is (= 2 (count @captured)))))))

(deftest sessions-events-stream-mid-stream-abort-is-a-connection-error
  (let [{:keys [port stop!]} (start-abort-server! 0 {:headers sse-headers
                                                     :chunks [(frame running) (frame (agent-message "sevt_m" "Hel"))]})]
    (try
      (let [client (a/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            seen   (atom [])
            e      (caught #(ag/await-idle (ag/sessions-events-stream client "sesn_1")
                                           {:on-event (fn [ev] (swap! seen conj (get ev "type")))}))]
        (is (= ["session.status_running" "agent.message"] @seen))
        (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

(deftest sessions-events-stream-sse-error-event-throws-after-earlier-events
  (let [gone (promise)]
    (with-mock
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send! (frame running))
                       (send! (sse "error" "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))
                       (send! (frame (idle "sevt_i" "end_turn")))
                       (until-gone! send! gone))})
      (fn [client _]
        (let [seen (atom [])
              s    (ag/sessions-events-stream client "sesn_1")
              e    (caught #(ag/await-idle s {:on-event (fn [ev] (swap! seen conj (get ev "type")))}))]
          (is (= ["session.status_running"] @seen) "the error event is thrown, not delivered")
          (is (= :tools.agents.anthropic.error/overloaded (:type (ex-data e))))
          (is (= "overloaded_error" (:error-type (ex-data e))))
          (is (= {"type" "overloaded_error" "message" "Overloaded"} (:error (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.anthropic.agents/sessions-events-stream: stream error"))
          (is (= :failed (stream/outcome s)))
          (is (true? (deref gone 5000 :timeout)) "the connection is closed"))))))

(deftest a-session-error-event-is-delivered-not-thrown-by-the-stream
  (with-mock
    (fn [_] {:status 200 :headers sse-headers
             :body (str (frame (session-error "sevt_e" "terminal")) (frame {"type" "session.status_terminated" "id" "sevt_t"}))})
    (fn [client _]
      (is (= ["session.error" "session.status_terminated"]
             (mapv #(get % "type") (into [] (ag/sessions-events-stream client "sesn_1"))))
          "reducing the stream yourself sees both")
      (let [e (caught #(ag/await-idle (ag/sessions-events-stream client "sesn_1")))]
        (is (= :tools.agents.anthropic.error/session-failed (:type (ex-data e))) "await-idle is what gives it meaning")))))

(deftest await-idle-over-a-stream-that-ends-without-an-idle-is-truncated
  (with-mock
    (fn [_] {:status 200 :headers sse-headers :body (str (frame running) (frame (agent-message "sevt_m" "half")))})
    (fn [client _]
      (let [s (ag/sessions-events-stream client "sesn_1")
            e (caught #(ag/await-idle s))]
        (is (= :tools.agents.anthropic.error/stream-truncated (:type (ex-data e))))
        (is (= :eof (:outcome (ex-data e))))
        (is (str/includes? (ex-message e) "resume-stream"))))))

;; ---------------------------------------------------------------------------
;; requires_action end to end: two blocking calls, answered one at a time
;; ---------------------------------------------------------------------------

(deftest answering-one-of-two-blocking-calls-re-emits-idle-with-the-remainder
  ;; The mock plays the documented sequence: two custom tool calls, an idle
  ;; naming both, then — once ONE result arrives — an idle naming the other,
  ;; and end_turn after the second.
  (let [answers  (atom [])
        waits    (atom [])
        answered (atom {"sevt_a" (promise) "sevt_b" (promise)})
        tool-use (fn [id name] {"type" "agent.custom_tool_use" "id" id "name" name "input" {}})]
    (with-mock
      (fn [req]
        (if (= "GET" (:method req))
          {:status 200 :headers sse-headers
           :body (fn [send!]
                   (send! (frame running))
                   (send! (frame (tool-use "sevt_a" "get_weather")))
                   (send! (frame (tool-use "sevt_b" "get_time")))
                   (send! (frame (idle "sevt_i1" "requires_action" ["sevt_a" "sevt_b"])))
                   (swap! waits conj (deref (get @answered "sevt_a") 5000 :timeout))
                   (send! (frame (idle "sevt_i2" "requires_action" ["sevt_b"])))
                   (swap! waits conj (deref (get @answered "sevt_b") 5000 :timeout))
                   (send! (frame running))
                   (send! (frame (agent-message "sevt_m" "Sunny, 10:00.")))
                   (send! (frame (idle "sevt_i3" "end_turn"))))}
          (let [ev (get-in (a/read-json (:body req)) ["events" 0])
                id (get ev "custom_tool_use_id")]
            (swap! answers conj ev)
            (deliver (get @answered id) true)
            {:status 200 :body (str "{\"data\":[" (ev-json (assoc ev "id" (str "sevt_r_" id))) "]}")})))
      (fn [client _]
        (let [idles (atom [])
              text  (atom [])
              final (ag/await-idle
                     (ag/sessions-events-stream client "sesn_1")
                     {:on-event (fn [ev]
                                  (when (= "agent.message" (get ev "type")) (swap! text conj ev))
                                  (when-let [id (first (ag/blocking-event-ids ev))]
                                    (swap! idles conj (ag/blocking-event-ids ev))
                                    ;; answer only the FIRST blocking call each time
                                    (ag/send-custom-tool-result client "sesn_1"
                                                                {:custom-tool-use-id id :content (str "result for " id)})))
                      :done?    #(not= "requires_action" (ag/stop-reason %))})]
          (is (= [["sevt_a" "sevt_b"] ["sevt_b"]] @idles)
              "the first idle names both calls; after one answer the session idles again with the remainder")
          (is (= [true true] @waits) "each later idle went out only after the result before it arrived")
          (is (= ["sevt_a" "sevt_b"] (mapv #(get % "custom_tool_use_id") @answers)))
          (is (= [{"type" "text" "text" "result for sevt_a"}] (get (first @answers) "content")))
          (is (= "sevt_i3" (get final "id")))
          (is (= "end_turn" (ag/stop-reason final)))
          (is (= "Sunny, 10:00." (ag/events-output-text @text))))))))

;; ---------------------------------------------------------------------------
;; resume-stream
;; ---------------------------------------------------------------------------

(defn- history-page [events next-page]
  {:status 200 :body (ev-json {"data" events "next_page" next-page})})

(deftest resume-stream-opens-the-stream-first-then-drops-what-history-already-holds
  (let [e1     {"type" "user.message" "id" "sevt_1" "content" [{"type" "text" "text" "go"}]}
        e2     running
        e3     (agent-message "sevt_3" "Working")
        e4     (agent-message "sevt_4" " on it.")
        start  {"type" "event_start" "event" {"type" "agent.message" "id" "sevt_4"}}
        delta  {"type" "event_delta" "event_id" "sevt_4"
                "delta" {"type" "content_delta" "index" 0 "content" {"type" "text" "text" " on"}}}
        e5     (idle "sevt_5" "end_turn")
        listed (promise)
        waited (promise)]
    (with-mock
      (fn [req]
        (cond
          (str/ends-with? (:path req) "/stream")
          {:status 200 :headers sse-headers
           :body (fn [send!]
                   ;; A first write, so the JDK mock flushes the response
                   ;; headers and the client's open call returns.
                   (send! ": open\n\n")
                   ;; Held until the history has been listed, so e2/e3 are in
                   ;; both: the overlap the recipe exists for.
                   (deliver waited (deref listed 5000 :timeout))
                   (doseq [e [e2 e3 start delta delta e4 e4 e5]] (send! (frame e))))}

          (= ["page_2"] (get (parse-query (:query req)) "page"))
          (do (deliver listed true) (history-page [e3] nil))

          :else
          (history-page [e1 e2] "page_2")))
      (fn [client captured]
        (let [{:keys [history events]} (ag/resume-stream client "sesn_1"
                                                         {:stream-params {"event_deltas" ["agent.message"]}
                                                          :list-params   {"limit" 2 "order" "desc"}})]
          (is (= [e1 e2 e3] history) "every page of the history, in order")
          (is (= ["/v1/sessions/sesn_1/events/stream" "/v1/sessions/sesn_1/events" "/v1/sessions/sesn_1/events"]
                 (mapv :path @captured))
              "step 1 is the stream; the history is listed only once it is open")
          (is (= {"beta" ["true"] "event_deltas[]" ["agent.message"]} (parse-query (:query (first @captured)))))
          (is (= {"beta" ["true"] "limit" ["2"] "order" ["asc"]} (parse-query (:query (second @captured))))
              "order is forced ascending")
          (is (= "session.status_running" (get (ag/latest-status history) "type"))
              "the session was still running at reconnect, so waiting on the live stream is right")
          (is (= [start delta delta e4 e5] (into [] events))
              "e2 and e3 came from history; the repeated e4 is delivered once; id-less previews always pass")
          (is (true? (deref waited 1000 :timeout)) "the stream was open and silent while the history was listed")
          (is (= :eof (stream/outcome events)) "the live half is the stream itself"))))))

(deftest resume-stream-then-await-idle-does-not-stop-at-an-earlier-turns-idle
  (let [old-idle (idle "sevt_old" "end_turn")
        new-idle (idle "sevt_new" "end_turn")]
    (with-mock
      (fn [req]
        (if (str/ends-with? (:path req) "/stream")
          {:status 200 :headers sse-headers
           :body (str (frame old-idle) (frame running) (frame (agent-message "sevt_m" "second turn")) (frame new-idle))}
          (history-page [old-idle running] nil)))
      (fn [client _]
        (let [{:keys [history events]} (ag/resume-stream client "sesn_1")]
          (is (= "sevt_old" (get (first history) "id")))
          (is (= "sevt_new" (get (ag/await-idle events) "id"))))))))

(deftest resume-stream-closes-the-stream-when-the-history-listing-fails
  (let [gone (promise)]
    (with-mock {:max-retries 0}
      (fn [req]
        (if (str/ends-with? (:path req) "/stream")
          {:status 200 :headers sse-headers :body (fn [send!] (until-gone! send! gone))}
          {:status 500 :body "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"boom\"}}"}))
      (fn [client _]
        (let [e (caught #(ag/resume-stream client "sesn_1"))]
          (is (= :tools.agents.anthropic.error/internal-server (:type (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.anthropic.agents/sessions-events-list: HTTP 500"))
          (is (true? (deref gone 5000 :timeout)) "the already-open stream was released"))))))
