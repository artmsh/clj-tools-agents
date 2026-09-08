(ns tools.agents.mcp.conformance-test
  "One test per normative clause that `tools.agents.mcp.server/handle`
   enforces. Named after the rule rather than after the function, because the
   rule is the thing that must not regress.

   `handle` is a pure function of (server, message), so every clause below is
   asserted on real wire values with no transport, no subprocess and no mock:
   the same data-in/data-out shape the rest of this repo uses.
   That is not a testing trick — it is what revision 2026-07-28 made possible
   by deleting the handshake and the session."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.server :as server]))

;; ---------------------------------------------------------------------------
;; A server that exercises every branch
;; ---------------------------------------------------------------------------

(def caps-all {"elicitation" {"url" {}} "sampling" {} "roots" {}})

(defn test-server []
  (server/server
   {:name "conformance" :version "9.9.9" :title "Conformance Server"
    :instructions "instructions here"
    :tools
    [{:name "echo"
      :description "Echoes"
      :input-schema {"type" "object" "properties" {"m" {"type" "string"}} "required" ["m"]}
      :handler (fn [_ctx args] (get args "m"))}

     {:name "noisy"
      :description "Emits one progress update and one log line at every level"
      :input-schema {"type" "object"}
      :handler (fn [ctx _args]
                 ((:progress! ctx) 1 {:total 2 :message "half"})
                 (doseq [l mcp/logging-levels] ((:log! ctx) l (str l " line") nil))
                 "done")}

     {:name "structured"
      :description "Returns structured content"
      :input-schema {"type" "object"}
      :output-schema {"type" "object" "properties" {"n" {"type" "integer"}}}
      :handler (fn [_ctx _args] (mcp/tool-result [(mcp/text "1")] {:structured-content {"n" 1}}))}

     {:name "boom"
      :description "Throws a plain exception"
      :input-schema {"type" "object"}
      :handler (fn [_ctx _args] (throw (ex-info "kaboom" {:secret "hunter2"})))}

     {:name "refuse"
      :description "Raises a protocol error"
      :input-schema {"type" "object"}
      :handler (fn [_ctx _args] (mcp/invalid-params! "you gave me nonsense"))}

     {:name "needs-roots"
      :description "Asks the client for its roots via MRTR"
      :input-schema {"type" "object"}
      :handler (fn [ctx _args]
                 (mcp/require-capabilities! (:client-capabilities ctx) {"roots" {}})
                 (if-let [rs (get (:input-responses ctx) "roots")]
                   (str "roots: " (str/join "," (map #(get % "uri") (get rs "roots"))))
                   (mcp/input-required {:input-requests {"roots" (mcp/list-roots)}
                                        :request-state "s1"})))}

     {:name "sneaky"
      :description "Asks for sampling WITHOUT checking the capability first"
      :input-schema {"type" "object"}
      :handler (fn [_ctx _args]
                 (mcp/input-required
                  {:input-requests {"s" (mcp/create-message {:messages [] :max-tokens 1})}}))}]

    :resources
    [{:name "fixed" :uri "test://fixed" :mime-type "text/plain"
      :handler (fn [_ctx uri] (mcp/text-contents uri "fixed body" "text/plain"))}
     {:name "second" :uri "test://second" :handler (fn [_ctx uri] "second body")}
     {:name "third" :uri "test://third" :handler (fn [_ctx uri] "third body")}]

    :resource-templates
    [{:name "numbered" :uri-template "test://n/{id}"
      :handler (fn [_ctx uri variables]
                 (when (= "0" (get variables "id"))
                   (mcp/not-found! (str "Unknown resource: " uri)))
                 (mcp/text-contents uri (str "n=" (get variables "id"))))}]

    :prompts
    [{:name "greet"
      :arguments [{:name "who" :required true}]
      :handler (fn [_ctx args]
                 [(mcp/user-message (mcp/text (str "Hello, " (get args "who"))))])}]

    :completions (fn [_ctx ref argument _context]
                   (when (= "greet" (get ref "name"))
                     (vec (filter #(str/starts-with? % (get argument "value" ""))
                                  ["ada" "alan" "grace"]))))

    :methods {"x.example/ping" (fn [_ctx _request] {"pong" true})}

    :tools-list-changed? true
    :resources-list-changed? true
    :resources-subscribe? true
    :logging? true
    :cache {:ttl-ms 60000 :cache-scope "public"}}))

(def srv (test-server))

(defn meta*
  ([] (meta* nil))
  ([extra]
   (mcp/request-meta (merge {:protocol-version mcp/latest-protocol-version
                             :client-capabilities caps-all
                             :client-info {"name" "conformance-test" "version" "1"}}
                            extra))))

(defn req
  ([method] (req method nil nil))
  ([method params] (req method params nil))
  ([method params meta-extra]
   (server/handle srv (mcp/request 1 method (assoc (or params {}) "_meta" (meta* meta-extra))))))

(defn result-of [r] (get-in r [:response "result"]))
(defn error-of [r] (get-in r [:response "error"]))

;; ---------------------------------------------------------------------------
;; server/discover
;; ---------------------------------------------------------------------------

(deftest server-discover-must-be-implemented
  ;; "Servers MUST implement server/discover" — it is also the stdio
  ;; backward-compatibility probe, so a server that omits it is undetectable.
  (let [r (result-of (req "server/discover"))]
    (is (= ["2026-07-28"] (get r "supportedVersions")))
    (is (= "instructions here" (get r "instructions")))
    (is (map? (get r "capabilities")))))

(deftest discover-advertises-only-what-is-registered
  (let [c (get (result-of (req "server/discover")) "capabilities")]
    (is (= {"listChanged" true} (get c "tools")))
    (is (= {} (get c "prompts")) "no prompts-list-changed? was declared")
    (is (= {"subscribe" true "listChanged" true} (get c "resources")))
    (is (= {} (get c "completions")))
    (is (= {} (get c "logging"))))
  (testing "a server with no prompts does not advertise prompts"
    (let [bare (server/server {:name "bare"
                               :tools [{:name "t" :handler (fn [_ _] "x")}]})]
      (is (nil? (get (server/capabilities bare) "prompts")))
      (is (= {} (get (server/capabilities bare) "tools"))))))

(deftest every-response-carries-server-info-in-meta
  (doseq [m ["server/discover" "tools/list" "prompts/list" "resources/list"]]
    (is (= {"name" "conformance" "version" "9.9.9" "title" "Conformance Server"}
           (get-in (result-of (req m)) ["_meta" mcp/meta-server-info]))
        m)))

;; ---------------------------------------------------------------------------
;; resultType and CacheableResult
;; ---------------------------------------------------------------------------

(deftest every-result-carries-a-result-type
  (doseq [m ["server/discover" "tools/list" "prompts/list" "resources/list"
             "resources/templates/list"]]
    (is (= "complete" (get (result-of (req m)) "resultType")) m))
  (is (= "complete" (get (result-of (req "tools/call" {"name" "echo" "arguments" {"m" "x"}}))
                         "resultType")))
  (is (= "complete" (get (result-of (req "resources/read" {"uri" "test://fixed"})) "resultType"))))

(deftest cacheable-results-carry-ttl-and-scope
  ;; SEP-2549 makes ttlMs and cacheScope REQUIRED on these six.
  (doseq [[m params] [["server/discover" nil]
                      ["tools/list" nil]
                      ["prompts/list" nil]
                      ["resources/list" nil]
                      ["resources/templates/list" nil]
                      ["resources/read" {"uri" "test://fixed"}]]]
    (let [r (result-of (req m params))]
      (is (= 60000 (get r "ttlMs")) m)
      (is (= "public" (get r "cacheScope")) m)))
  (testing "the default is the conservative one: stale immediately, private"
    (let [d (server/server {:name "d" :tools [{:name "t" :handler (fn [_ _] "x")}]})
          r (get-in (server/handle d (mcp/request 1 "tools/list" {"_meta" (meta*)}))
                    [:response "result"])]
      (is (= 0 (get r "ttlMs")))
      (is (= "private" (get r "cacheScope"))))))

;; ---------------------------------------------------------------------------
;; Per-request _meta
;; ---------------------------------------------------------------------------

(deftest missing-protocol-version-is-invalid-params
  (let [r (server/handle srv (mcp/request 1 "tools/list"
                                          {"_meta" {mcp/meta-client-caps {}}}))]
    (is (= -32602 (get-in r [:response "error" "code"])))
    (is (str/includes? (get-in r [:response "error" "message"]) "protocolVersion"))))

(deftest missing-client-capabilities-is-invalid-params
  (let [r (server/handle srv (mcp/request 1 "tools/list"
                                          {"_meta" {mcp/meta-protocol-version
                                                    mcp/latest-protocol-version}}))]
    (is (= -32602 (get-in r [:response "error" "code"])))
    (is (str/includes? (get-in r [:response "error" "message"]) "clientCapabilities"))))

(deftest absent-meta-entirely-is-invalid-params
  (is (= -32602 (get-in (server/handle srv (mcp/request 1 "tools/list"))
                        [:response "error" "code"]))))

(deftest unknown-log-level-is-invalid-params
  (is (= -32602 (get-in (req "tools/list" nil {:log-level "chatty"})
                        [:response "error" "code"]))))

(deftest unsupported-protocol-version-is-32022-with-both-lists
  (let [r (server/handle srv (mcp/request 1 "tools/list"
                                          {"_meta" {mcp/meta-protocol-version "2025-06-18"
                                                    mcp/meta-client-caps {}}}))
        e (get-in r [:response "error"])]
    (is (= -32022 (get e "code")))
    (is (= ["2026-07-28"] (get-in e ["data" "supported"])))
    (is (= "2025-06-18" (get-in e ["data" "requested"])))))

;; ---------------------------------------------------------------------------
;; Notification gating
;; ---------------------------------------------------------------------------

(deftest no-progress-notification-without-a-progress-token
  (let [r (req "tools/call" {"name" "noisy" "arguments" {}})]
    (is (empty? (filter #(= "notifications/progress" (get % "method")) (:notifications r))))))

(deftest progress-notifications-flow-when-a-token-was-sent
  (let [r (req "tools/call" {"name" "noisy" "arguments" {}} {:progress-token "p9"})
        p (first (filter #(= "notifications/progress" (get % "method")) (:notifications r)))]
    (is (= {"progressToken" "p9" "progress" 1 "total" 2 "message" "half"} (get p "params")))))

(deftest no-log-notification-without-a-log-level
  (let [r (req "tools/call" {"name" "noisy" "arguments" {}})]
    (is (empty? (filter #(= "notifications/message" (get % "method")) (:notifications r))))))

(deftest log-notifications-are-filtered-to-the-requested-severity
  (let [levels (fn [lvl]
                 (->> (:notifications (req "tools/call" {"name" "noisy" "arguments" {}}
                                           {:log-level lvl}))
                      (filter #(= "notifications/message" (get % "method")))
                      (mapv #(get-in % ["params" "level"]))))]
    (is (= mcp/logging-levels (levels "debug")))
    (is (= ["error" "critical" "alert" "emergency"] (levels "error")))
    (is (= ["emergency"] (levels "emergency")))))

(deftest emit!-sees-notifications-before-the-response-returns
  (let [seen (atom [])
        r (server/handle srv (mcp/request 1 "tools/call"
                                          {"name" "noisy" "arguments" {}
                                           "_meta" (meta* {:progress-token "p"})})
                         {:emit! (fn [n] (swap! seen conj (get n "method")))})]
    (is (= ["notifications/progress"] @seen))
    (is (= @seen (mapv #(get % "method") (:notifications r))))))

;; ---------------------------------------------------------------------------
;; No server-initiated requests
;; ---------------------------------------------------------------------------

(deftest handle-never-produces-a-json-rpc-request
  ;; SEP-2567: in this revision the server may only respond and notify. Every
  ;; message this function can put on the wire is checked, not just the ones
  ;; a particular test happens to exercise.
  (doseq [[m params meta-extra]
          [["server/discover" nil nil]
           ["tools/list" nil nil]
           ["tools/call" {"name" "noisy" "arguments" {}} {:progress-token "p" :log-level "debug"}]
           ["tools/call" {"name" "needs-roots" "arguments" {}} nil]
           ["resources/read" {"uri" "test://fixed"} nil]
           ["prompts/get" {"name" "greet" "arguments" {"who" "ada"}} nil]
           ["subscriptions/listen" {"notifications" {"toolsListChanged" true}} nil]]]
    (let [r (req m params meta-extra)]
      (doseq [msg (cons (:response r) (:notifications r))]
        (when msg
          (is (not (mcp/request? msg)) (str m " produced a request: " (pr-str msg))))))))

;; ---------------------------------------------------------------------------
;; tools
;; ---------------------------------------------------------------------------

(deftest tools-list-wire-shape
  (let [t (first (get (result-of (req "tools/list")) "tools"))]
    (is (= "echo" (get t "name")))
    (is (= "Echoes" (get t "description")))
    (is (= {"type" "object" "properties" {"m" {"type" "string"}} "required" ["m"]}
           (get t "inputSchema"))))
  (testing "an output schema is advertised when declared"
    (let [t (first (filter #(= "structured" (get % "name"))
                           (get (result-of (req "tools/list")) "tools")))]
      (is (= {"type" "object" "properties" {"n" {"type" "integer"}}} (get t "outputSchema"))))))

(deftest unknown-tool-is-invalid-params
  (is (= -32602 (get (error-of (req "tools/call" {"name" "nope" "arguments" {}})) "code"))))

(deftest a-tool-that-throws-reports-is-error-not-a-json-rpc-error
  ;; The model has to be able to see and recover from a tool failure, so an
  ;; execution failure is a normal result with isError true.
  (let [r (result-of (req "tools/call" {"name" "boom" "arguments" {}}))]
    (is (true? (get r "isError")))
    (is (str/includes? (mcp/output-text r) "kaboom"))
    (is (not (str/includes? (mcp/output-text r) "hunter2")))))

(deftest a-tool-that-raises-a-protocol-error-means-it
  (let [e (error-of (req "tools/call" {"name" "refuse" "arguments" {}}))]
    (is (= -32602 (get e "code")))
    (is (str/includes? (get e "message") "nonsense"))))

(deftest structured-content-passes-through
  (is (= {"n" 1} (get (result-of (req "tools/call" {"name" "structured" "arguments" {}}))
                      "structuredContent"))))

;; ---------------------------------------------------------------------------
;; resources
;; ---------------------------------------------------------------------------

(deftest resources-read-static-and-templated
  (is (= "fixed body"
         (get-in (result-of (req "resources/read" {"uri" "test://fixed"})) ["contents" 0 "text"])))
  (is (= "n=42"
         (get-in (result-of (req "resources/read" {"uri" "test://n/42"})) ["contents" 0 "text"]))))

(deftest resource-not-found-is-32602-not-32002
  ;; -32002 was deleted in this revision; a URI that matches nothing is a bad
  ;; parameter, and so is one a template explicitly rejects.
  (is (= -32602 (get (error-of (req "resources/read" {"uri" "test://nothing"})) "code")))
  (is (= -32602 (get (error-of (req "resources/read" {"uri" "test://n/0"})) "code"))))

(deftest resources-read-requires-a-string-uri
  (is (= -32602 (get (error-of (req "resources/read" {})) "code")))
  (is (= -32602 (get (error-of (req "resources/read" {"uri" 7})) "code"))))

(deftest resource-templates-list-wire-shape
  (let [t (first (get (result-of (req "resources/templates/list")) "resourceTemplates"))]
    (is (= "numbered" (get t "name")))
    (is (= "test://n/{id}" (get t "uriTemplate")))))

;; ---------------------------------------------------------------------------
;; prompts and completions
;; ---------------------------------------------------------------------------

(deftest prompts-get-normalises-a-bare-message-vector
  (let [r (result-of (req "prompts/get" {"name" "greet" "arguments" {"who" "ada"}}))]
    (is (= [{"role" "user" "content" {"type" "text" "text" "Hello, ada"}}] (get r "messages")))
    (is (= "complete" (get r "resultType")))))

(deftest unknown-prompt-is-invalid-params
  (is (= -32602 (get (error-of (req "prompts/get" {"name" "nope"})) "code"))))

(deftest completion-complete-wire-shape
  (let [r (result-of (req "completion/complete"
                          {"ref" {"type" "ref/prompt" "name" "greet"} "argument" {"name" "who" "value" "a"}}))]
    (is (= ["ada" "alan"] (get-in r ["completion" "values"])))
    (is (= 2 (get-in r ["completion" "total"])))
    (is (false? (get-in r ["completion" "hasMore"])))))

;; ---------------------------------------------------------------------------
;; Multi round-trip request pattern
;; ---------------------------------------------------------------------------

(deftest input-required-round-trip
  (let [ask (result-of (req "tools/call" {"name" "needs-roots" "arguments" {}}))]
    (is (= "input_required" (get ask "resultType")))
    (is (= {"method" "roots/list"} (get-in ask ["inputRequests" "roots"])))
    (is (= "s1" (get ask "requestState")))
    (testing "the client retries with a NEW id, echoing requestState verbatim"
      (let [retry (server/handle
                   srv (mcp/request 2 "tools/call"
                                    {"name" "needs-roots" "arguments" {}
                                     "inputResponses" {"roots" {"roots" [{"uri" "file:///a"}
                                                                         {"uri" "file:///b"}]}}
                                     "requestState" (get ask "requestState")
                                     "_meta" (meta*)}))]
        (is (= 2 (get-in retry [:response "id"])))
        (is (= "complete" (get-in retry [:response "result" "resultType"])))
        (is (= "roots: file:///a,file:///b" (mcp/output-text (result-of retry))))))))

(deftest undeclared-capability-is-32021-naming-what-is-missing
  (let [r (server/handle srv (mcp/request 1 "tools/call"
                                          {"name" "needs-roots" "arguments" {}
                                           "_meta" (mcp/request-meta
                                                    {:protocol-version mcp/latest-protocol-version
                                                     :client-capabilities {}})}))
        e (get-in r [:response "error"])]
    (is (= -32021 (get e "code")))
    (is (= {"roots" {}} (get-in e ["data" "requiredCapabilities"])))))

(deftest the-dispatcher-backstops-a-handler-that-forgot-to-check
  ;; "sneaky" never calls require-capabilities!. The MUST NOT is still honoured.
  (let [r (server/handle srv (mcp/request 1 "tools/call"
                                          {"name" "sneaky" "arguments" {}
                                           "_meta" (mcp/request-meta
                                                    {:protocol-version mcp/latest-protocol-version
                                                     :client-capabilities {}})}))]
    (is (= -32021 (get-in r [:response "error" "code"])))
    (is (= {"sampling" {}} (get-in r [:response "error" "data" "requiredCapabilities"]))))
  (testing "and lets it through when the client did declare it"
    (is (= "input_required"
           (get (result-of (req "tools/call" {"name" "sneaky" "arguments" {}})) "resultType")))))

;; ---------------------------------------------------------------------------
;; Pagination
;; ---------------------------------------------------------------------------

(deftest pagination-walks-with-an-opaque-cursor
  (let [paged (server/server (assoc (select-keys (test-server) [])
                                    :name "paged"
                                    :page-size 2
                                    :tools (mapv (fn [i] {:name (str "t" i)
                                                          :handler (fn [_ _] (str i))})
                                                 (range 5))))
        page (fn [cursor]
               (get-in (server/handle paged
                                      (mcp/request 1 "tools/list"
                                                   (cond-> {"_meta" (meta*)}
                                                     cursor (assoc "cursor" cursor))))
                       [:response "result"]))
        p1 (page nil)
        p2 (page (get p1 "nextCursor"))
        p3 (page (get p2 "nextCursor"))]
    (is (= ["t0" "t1"] (mapv #(get % "name") (get p1 "tools"))))
    (is (= ["t2" "t3"] (mapv #(get % "name") (get p2 "tools"))))
    (is (= ["t4"] (mapv #(get % "name") (get p3 "tools"))))
    (is (nil? (get p3 "nextCursor")))))

(deftest a-malformed-cursor-is-invalid-params
  (let [paged (server/server {:name "paged" :page-size 2
                              :tools [{:name "t" :handler (fn [_ _] "x")}]})]
    (is (= -32602 (get-in (server/handle paged (mcp/request 1 "tools/list"
                                                            {"cursor" "../../etc/passwd"
                                                             "_meta" (meta*)}))
                          [:response "error" "code"])))))

(deftest page-size-can-be-scoped-per-list-kind
  (let [s (server/server {:name "s"
                          :page-size {:resources 1}
                          :tools (mapv (fn [i] {:name (str "t" i) :handler (fn [_ _] "x")}) (range 3))
                          :resources (mapv (fn [i] {:name (str "r" i) :uri (str "u://" i)
                                                    :handler (fn [_ _] "x")})
                                           (range 3))})
        call (fn [m] (get-in (server/handle s (mcp/request 1 m {"_meta" (meta*)}))
                             [:response "result"]))]
    (is (= 3 (count (get (call "tools/list") "tools"))))
    (is (nil? (get (call "tools/list") "nextCursor")))
    (is (= 1 (count (get (call "resources/list") "resources"))))
    (is (some? (get (call "resources/list") "nextCursor")))))

;; ---------------------------------------------------------------------------
;; subscriptions/listen
;; ---------------------------------------------------------------------------

(deftest listen-acknowledges-first-and-answers-nothing-yet
  (let [r (req "subscriptions/listen" {"notifications" {"toolsListChanged" true
                                                        "resourceSubscriptions" ["test://fixed"]}})
        ack (first (:notifications r))]
    (is (nil? (:response r)) "the response comes only at graceful closure")
    (is (= "notifications/subscriptions/acknowledged" (get ack "method")))
    (is (= 1 (get-in ack ["params" "_meta" mcp/meta-subscription-id])))
    (is (= {:id 1 :filter {"toolsListChanged" true
                           "resourceSubscriptions" ["test://fixed"]}}
           (:subscription r)))))

(deftest listen-agrees-only-to-what-the-server-supports
  (let [bare (server/server {:name "bare" :tools [{:name "t" :handler (fn [_ _] "x")}]})
        r (server/handle bare (mcp/request 1 "subscriptions/listen"
                                           {"notifications" {"toolsListChanged" true
                                                             "resourceSubscriptions" ["u://1"]}
                                            "_meta" (meta*)}))]
    (is (= {} (:filter (:subscription r)))
        "a server without listChanged or subscribe agrees to nothing")))

(deftest subscription-notification-honours-the-filter
  (let [sub (:subscription (req "subscriptions/listen"
                                {"notifications" {"toolsListChanged" true
                                                  "resourceSubscriptions" ["test://fixed"]}}))]
    (testing "an opted-in type is tagged with the subscription id"
      (is (= 1 (get-in (server/subscription-notification sub mcp/tools-list-changed-notification)
                       ["params" "_meta" mcp/meta-subscription-id]))))
    (testing "a type the client did not ask for is dropped"
      (is (nil? (server/subscription-notification sub mcp/prompts-list-changed-notification))))
    (testing "resources/updated only for a subscribed URI"
      (is (some? (server/subscription-notification
                  sub (mcp/resource-updated-notification "test://fixed"))))
      (is (nil? (server/subscription-notification
                 sub (mcp/resource-updated-notification "test://second")))))
    (testing "request-scoped notifications are never deliverable on a stream"
      (is (nil? (server/subscription-notification sub (mcp/progress-notification "p" 1 nil))))
      (is (nil? (server/subscription-notification sub (mcp/log-notification "info" {:data "x"})))))))

(deftest close-subscription-is-a-result-on-the-listen-id
  (let [c (server/close-subscription 7)]
    (is (= 7 (get c "id")))
    (is (= "complete" (get-in c ["result" "resultType"])))
    (is (= 7 (get-in c ["result" "_meta" mcp/meta-subscription-id])))))

;; ---------------------------------------------------------------------------
;; Notifications inbound, and malformed messages
;; ---------------------------------------------------------------------------

(deftest a-notification-never-gets-a-response
  (let [r (server/handle srv (mcp/cancelled-notification 5 "user cancelled"))]
    (is (nil? (:response r)))
    (is (= {:request-id 5 :reason "user cancelled"} (:cancelled r)))))

(deftest unknown-method-is-method-not-found
  (is (= -32601 (get (error-of (req "does/not/exist")) "code"))))

(deftest registered-extension-methods-dispatch
  ;; Tasks left the core protocol in this revision; :methods is the seam.
  (is (= true (get (result-of (req "x.example/ping")) "pong"))))

(deftest malformed-messages-are-invalid-request
  (is (= -32600 (get-in (server/handle srv "not a map") [:response "error" "code"])))
  (is (= -32600 (get-in (server/handle srv {"jsonrpc" "1.0" "id" 1 "method" "tools/list"})
                        [:response "error" "code"])))
  (is (= -32600 (get-in (server/handle srv {"jsonrpc" "2.0" "id" 1}) [:response "error" "code"]))))

;; ---------------------------------------------------------------------------
;; Registration validation
;; ---------------------------------------------------------------------------

(deftest registration-rejects-what-cannot-work
  (is (thrown? Exception (server/server {})))
  (is (thrown? Exception (server/server {:name "x" :tools [{:handler (fn [_ _] nil)}]})))
  (is (thrown? Exception (server/server {:name "x" :tools [{:name "t"}]}))))

;; ---------------------------------------------------------------------------
;; URI templates
;; ---------------------------------------------------------------------------

(deftest uri-template-parsing-and-matching
  (is (= [[:lit "a://"] [:var "id"]] (server/parse-uri-template "a://{id}")))
  (is (= [[:lit "a://"] [:var "x"] [:lit "/"] [:var "y"]]
         (server/parse-uri-template "a://{x}/{y}")))
  (is (thrown? Exception (server/parse-uri-template "a://{id")))
  (is (= {"id" "7"} (server/match-uri-template "a://{id}" "a://7")))
  (is (= {"x" "1" "y" "2"} (server/match-uri-template "a://{x}/{y}" "a://1/2")))
  (testing "a variable never matches the empty string"
    (is (nil? (server/match-uri-template "a://{id}" "a://"))))
  (testing "a trailing literal must be present"
    (is (nil? (server/match-uri-template "a://{x}/{y}" "a://1")))
    (is (nil? (server/match-uri-template "a://{id}" "b://7")))))
