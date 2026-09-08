(ns tools.agents.mcp.stdio-test
  "The stdio transport.

   Two halves. The first drives `connection`/`handle-line!`/`notify!` with a
   capturing writer, so the framing rules are asserted as data. The second
   launches a REAL subprocess — `stdio-child`, which serves the build-server
   tutorial's weather server — and drives it through `connect!` over real
   pipes, which is the only way to prove that the framing, the newline
   discipline and the 'never write to stdout' rule actually hold end to
   end."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.client :as client]
            [tools.agents.mcp.server :as server]
            [tools.agents.mcp.stdio :as stdio]))

;; ---------------------------------------------------------------------------
;; In-process: framing, with a capturing writer
;; ---------------------------------------------------------------------------

(defn a-server []
  (server/server
   {:name "stdio-demo" :version "0.1"
    :tools [{:name "echo" :handler (fn [_ctx args] (get args "m"))}
            {:name "multiline"
             ;; A tool whose output contains the framing character.
             :handler (fn [_ctx _args] "line one\nline two\r\nline three")}
            {:name "noisy" :handler (fn [ctx _] ((:progress! ctx) 1 nil) "done")}]
    :resources [{:name "r" :uri "test://r" :handler (fn [_ _] "body")}]
    :resources-subscribe? true
    :tools-list-changed? true
    :resources-list-changed? true}))

(defn capturing-connection []
  (let [written (atom [])]
    [written (stdio/connection (a-server) {:write-fn (fn [s] (swap! written conj s))})]))

(def a-meta (mcp/request-meta {:protocol-version mcp/latest-protocol-version
                               :client-capabilities {}}))

(defn line [method params]
  (mcp/write-json (mcp/request 1 method (assoc (or params {}) "_meta" a-meta))))

(deftest one-message-per-line
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line! conn (line "tools/list" nil))
    (is (= 1 (count @written)))
    (is (not (str/includes? (first @written) "\n")))))

(deftest tool-output-can-never-break-the-framing
  ;; The spec forbids embedded newlines in a message. `write-json` escapes the
  ;; whole C0 range, so a tool that returns newlines cannot split its own
  ;; response into two frames.
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line! conn (line "tools/call" {"name" "multiline" "arguments" {}}))
    (let [out (first @written)]
      (is (= 1 (count @written)))
      (is (not (str/includes? out "\n")))
      (is (not (str/includes? out "\r")))
      (is (str/includes? out "\\n"))
      (testing "and the newlines survive the round trip intact"
        (is (= "line one\nline two\r\nline three"
               (mcp/output-text (get (mcp/read-json out) "result"))))))))

(deftest a-bad-line-is-a-parse-error-with-a-null-id
  ;; The only case in which a JSON-RPC error response may omit the id.
  (let [[written conn] (capturing-connection)
        res (stdio/handle-line! conn "{not json")]
    (is (some? (:parse-error res)))
    (let [resp (mcp/read-json (first @written))]
      (is (nil? (get resp "id")))
      (is (= -32700 (get-in resp ["error" "code"]))))))

(deftest notifications-are-written-before-the-response
  (let [[written conn] (capturing-connection)]
    (stdio/handle-message!
     conn (mcp/request 1 "tools/call"
                       {"name" "noisy" "arguments" {}
                        "_meta" (mcp/request-meta
                                 {:protocol-version mcp/latest-protocol-version
                                  :client-capabilities {}
                                  :progress-token "p"})}))
    (is (= ["notifications/progress" nil]
           (mapv #(get (mcp/read-json %) "method") @written)))))

(deftest a-listen-request-opens-a-stream-and-answers-only-an-acknowledgment
  (let [[written conn] (capturing-connection)
        res (stdio/handle-message!
             conn (mcp/request 7 "subscriptions/listen"
                               {"notifications" {"toolsListChanged" true
                                                 "resourceSubscriptions" ["test://r"]}
                                "_meta" a-meta}))]
    (is (nil? (:response res)))
    (is (= 1 (count @written)))
    (let [ack (mcp/read-json (first @written))]
      (is (= "notifications/subscriptions/acknowledged" (get ack "method")))
      (is (= 7 (get-in ack ["params" "_meta" mcp/meta-subscription-id]))))
    (is (= #{7} (set (keys @(:subscriptions conn)))))))

(deftest notify!-fans-out-only-to-streams-that-asked
  (let [[written conn] (capturing-connection)]
    (stdio/handle-message! conn (mcp/request 1 "subscriptions/listen"
                                             {"notifications" {"toolsListChanged" true}
                                              "_meta" a-meta}))
    (stdio/handle-message! conn (mcp/request 2 "subscriptions/listen"
                                             {"notifications" {"resourcesListChanged" true}
                                              "_meta" a-meta}))
    (reset! written [])
    (is (= 1 (stdio/notify! conn mcp/tools-list-changed-notification)))
    (is (= 1 (get-in (mcp/read-json (first @written)) ["params" "_meta" mcp/meta-subscription-id])))
    (reset! written [])
    (is (= 0 (stdio/notify! conn mcp/prompts-list-changed-notification))
        "nothing asked for prompt list changes")
    (is (= [] @written))))

(deftest resource-updates-reach-only-subscribers-of-that-uri
  (let [[written conn] (capturing-connection)]
    (stdio/handle-message! conn (mcp/request 1 "subscriptions/listen"
                                             {"notifications" {"resourceSubscriptions" ["test://r"]}
                                              "_meta" a-meta}))
    (reset! written [])
    (is (= 1 (stdio/notify! conn (mcp/resource-updated-notification "test://r"))))
    (is (= 0 (stdio/notify! conn (mcp/resource-updated-notification "test://other"))))))

(deftest a-cancelled-notification-closes-the-stream-it-names
  (let [[_ conn] (capturing-connection)]
    (stdio/handle-message! conn (mcp/request 4 "subscriptions/listen"
                                             {"notifications" {"toolsListChanged" true}
                                              "_meta" a-meta}))
    (is (= #{4} (set (keys @(:subscriptions conn)))))
    (stdio/handle-message! conn (mcp/cancelled-notification 4 "done with it"))
    (is (empty? @(:subscriptions conn)))
    (is (= 0 (stdio/notify! conn mcp/tools-list-changed-notification)))))

(deftest closing-subscriptions-answers-each-listen-request
  (let [[written conn] (capturing-connection)]
    (stdio/handle-message! conn (mcp/request 5 "subscriptions/listen"
                                             {"notifications" {"toolsListChanged" true}
                                              "_meta" a-meta}))
    (reset! written [])
    (stdio/close-subscriptions! conn)
    (let [resp (mcp/read-json (first @written))]
      (is (= 5 (get resp "id")))
      (is (= "complete" (get-in resp ["result" "resultType"])))
      (is (= 5 (get-in resp ["result" "_meta" mcp/meta-subscription-id]))))
    (is (empty? @(:subscriptions conn)))))

(deftest a-notification-from-the-client-produces-no-output
  (let [[written conn] (capturing-connection)]
    (stdio/handle-message! conn (mcp/cancelled-notification 99 "nothing to cancel"))
    (is (= [] @written))))

(deftest log!-writes-to-stderr-and-never-to-stdout
  ;; The Clojure equivalent of the tutorial's "never call print() in a STDIO
  ;; server": anything on stdout that is not a message corrupts the stream.
  (is (= "" (with-out-str (stdio/log! "a diagnostic line")))))

;; ---------------------------------------------------------------------------
;; Legacy-handshake compatibility (handle-line-legacy!, serve!'s
;; :legacy-handshake? true)
;; ---------------------------------------------------------------------------

(defn a-classic-initialize [id]
  (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" id "method" "initialize"
                   "params" {"protocolVersion" "2025-06-18"
                             "capabilities" {}
                             "clientInfo" {"name" "legacy-client" "version" "0"}}}))

(deftest legacy-initialize-is-answered-locally-not-by-server-handle
  ;; server/handle has no "initialize" method at all under 2026-07-28 — a
  ;; classic client's first message would 32602 there. handle-line-legacy!
  ;; answers it without ever reaching server/handle.
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy! conn (a-classic-initialize 1))
    (is (= 1 (count @written)))
    (let [resp (mcp/read-json (first @written))]
      (is (= 1 (get resp "id")))
      (is (= "2025-06-18" (get-in resp ["result" "protocolVersion"]))
          "echoes the client's requested version back")
      (is (= {"tools" {}} (get-in resp ["result" "capabilities"])))
      (is (= {"name" "stdio-demo" "version" "0.1"} (get-in resp ["result" "serverInfo"]))))))

(deftest legacy-initialize-falls-back-to-latest-version-when-the-client-omits-one
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy! conn (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 1
                                                      "method" "initialize" "params" {}}))
    (is (= mcp/latest-protocol-version (get-in (mcp/read-json (first @written)) ["result" "protocolVersion"])))))

(deftest legacy-notifications-initialized-produces-no-output
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy! conn (mcp/write-json {"jsonrpc" mcp/jsonrpc-version
                                                      "method" "notifications/initialized"}))
    (is (= [] @written))))

(deftest legacy-ping-gets-an-empty-result
  ;; server/handle has no "ping" method — a legacy client's keepalive MUST
  ;; get a prompt response or it may drop the connection mid-session.
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy! conn (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 9 "method" "ping"}))
    (is (= {"jsonrpc" mcp/jsonrpc-version "id" 9 "result" {}} (mcp/read-json (first @written))))))

(deftest legacy-tools-call-with-no-_meta-at-all-still-reaches-the-tool
  ;; Exactly the shape a classic client's tools/call takes: no _meta
  ;; whatsoever. handle-line! (not -legacy!) would 32602 this.
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy!
     conn (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 2 "method" "tools/call"
                           "params" {"name" "echo" "arguments" {"m" "hi"}}}))
    (is (= "hi" (mcp/output-text (get (mcp/read-json (first @written)) "result"))))))

(deftest legacy-stamp-meta-repairs-per-field-rather-than-replacing-a-partial-_meta
  ;; A real classic client's tools/call DOES carry its own _meta — a
  ;; progressToken — while never sending protocolVersion/clientCapabilities.
  ;; Repair, not "stamp only if _meta is entirely absent", or progressToken
  ;; (and the progress notifications it drives) is silently lost.
  (let [[written conn] (capturing-connection)]
    (stdio/handle-line-legacy!
     conn (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 3 "method" "tools/call"
                           "params" {"name" "noisy" "arguments" {}
                                     "_meta" {"progressToken" "p"}}}))
    (is (= ["notifications/progress" nil]
           (mapv #(get (mcp/read-json %) "method") @written))
        "the progressToken survived the repair, so the progress notification was delivered")))

(deftest legacy-stamp-meta-leaves-a-well-formed-_meta-untouched
  (is (= {"_meta" {mcp/meta-protocol-version mcp/latest-protocol-version mcp/meta-client-caps {}}}
         (get (stdio/legacy-stamp-meta
               {"params" {"_meta" {mcp/meta-protocol-version mcp/latest-protocol-version
                                   mcp/meta-client-caps {}}}})
              "params"))))

(deftest a-legacy-bad-line-is-still-a-parse-error-with-a-null-id
  (let [[written conn] (capturing-connection)
        res (stdio/handle-line-legacy! conn "{not json")]
    (is (some? (:parse-error res)))
    (is (= -32700 (get-in (mcp/read-json (first @written)) ["error" "code"])))))

(deftest serve!-with-legacy-handshake?-completes-a-full-classic-session
  ;; The exact sequence a classic client sends: initialize,
  ;; notifications/initialized (no id, no response), a ping, then a
  ;; tools/call carrying only a progressToken _meta — none of which
  ;; plain serve! (legacy-handshake? false, the default) can answer.
  (let [written (atom [])
        conn (stdio/connection (a-server) {:write-fn (fn [s] (swap! written conj s))})]
    (with-in-str (str (a-classic-initialize 1) "\n"
                      (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "method" "notifications/initialized"}) "\n"
                      (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 2 "method" "ping"}) "\n"
                      (mcp/write-json {"jsonrpc" mcp/jsonrpc-version "id" 3 "method" "tools/call"
                                      "params" {"name" "echo" "arguments" {"m" "hi"}
                                                "_meta" {"progressToken" "p"}}}) "\n")
      (stdio/serve! (a-server) {:conn conn :legacy-handshake? true}))
    (is (= 3 (count @written))
        "initialize + ping + tools/call answered; notifications/initialized produced nothing")
    (is (= ["initialize-result" "ping-result" "tools/call-result"]
           (mapv (fn [s] (let [r (mcp/read-json s)]
                          (cond
                            (get-in r ["result" "protocolVersion"]) "initialize-result"
                            (= {} (get r "result")) "ping-result"
                            :else "tools/call-result")))
                 @written)))
    (is (= "hi" (mcp/output-text (get (mcp/read-json (last @written)) "result"))))))

;; ---------------------------------------------------------------------------
;; serve! — the read loop
;; ---------------------------------------------------------------------------

(deftest serve!-answers-every-line-and-stops-at-eof
  (let [written (atom [])
        conn (stdio/connection (a-server) {:write-fn (fn [s] (swap! written conj s))})
        started (atom nil)
        stopped (atom nil)]
    (with-in-str (str (line "tools/list" nil) "\n"
                      "\n"                       ; blank lines are framing noise
                      "   \n"
                      (line "tools/call" {"name" "echo" "arguments" {"m" "hi"}}) "\n")
      (stdio/serve! (a-server) {:conn conn
                                :on-start (fn [c] (reset! started c))
                                :on-stop (fn [c] (reset! stopped c))}))
    (is (= conn @started))
    (is (= conn @stopped))
    (testing "a blank line is skipped, not answered with a parse error"
      (is (= 2 (count @written))))
    (is (= "hi" (mcp/output-text (get (mcp/read-json (second @written)) "result"))))))

;; ---------------------------------------------------------------------------
;; End to end, over real pipes
;; ---------------------------------------------------------------------------

;; How to launch tools.agents.mcp.stdio-child on this runtime. The JVM branch
;; re-uses THIS process's classpath and calls clojure.main directly rather
;; than shelling out to the `clojure` CLI: the CLI re-resolves deps on every
;; invocation, which turned a two-second test into a one-minute one.
(def child-command
  #?(:bb ["bb" "-cp" "src:test:." "-m" "tools.agents.mcp.stdio-child"]
     :clj ["java" "-cp" (System/getProperty "java.class.path")
           "clojure.main" "-m" "tools.agents.mcp.stdio-child"]))

(defn- with-child
  "Launch the child, run `f` with an MCP client wired to it, shut it down."
  [f]
  (let [stderr (atom [])
        transport (stdio/connect! child-command {:on-stderr (fn [l] (swap! stderr conj l))})
        c (client/client {:name "stdio-test" :version "1"
                          :capabilities {}
                          :send! (:send! transport)})]
    (try (f c stderr)
         (finally ((:close! transport))))))

(deftest end-to-end-against-the-tutorial-weather-server
  (with-child
    (fn [c stderr]
      (testing "server/discover — no initialize handshake exists to perform"
        (let [d (client/discover! c)]
          (is (= ["2026-07-28"] (get d "supportedVersions")))
          (is (= {"name" "weather" "version" "1.0.0"}
                 (get-in d ["_meta" mcp/meta-server-info])))
          (is (= {"tools" {}} (get d "capabilities")))))

      (testing "tools/list returns the tutorial's two tools"
        (let [tools (client/list-all-tools! c)]
          (is (= ["get_alerts" "get_forecast"] (mapv #(get % "name") tools)))
          (is (= ["state"] (get-in (first tools) ["inputSchema" "required"])))
          (is (= ["latitude" "longitude"]
                 (get-in (second tools) ["inputSchema" "required"])))))

      (testing "tools/call get_alerts formats each alert as the tutorial does"
        (let [text (mcp/output-text (client/call-tool! c "get_alerts" {"state" "CA"}))]
          (is (str/includes? text "Event: Flood Warning"))
          (is (str/includes? text "Area: Sacramento County"))
          (is (str/includes? text "Instructions: Avoid low crossings."))))

      (testing "tools/call get_forecast walks points -> forecast and joins periods"
        (let [text (mcp/output-text (client/call-tool! c "get_forecast"
                                                       {"latitude" 38.58 "longitude" -121.49}))]
          (is (str/includes? text "Tonight:"))
          (is (str/includes? text "55°F"))
          (is (str/includes? text "Tuesday:"))
          (is (str/includes? text "Sunny."))))

      (testing "an unknown tool is a protocol error, not a result"
        (let [e (try (client/call-tool! c "nope" {}) nil (catch Exception ex ex))]
          (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))))))

      (testing "the server's own diagnostics went to stderr, where they belong"
        (is (some #(str/includes? % "weather server up") @stderr))))))

(deftest a-server-exits-when-its-stdin-closes
  ;; "The server SHOULD exit when stdin is closed" — the client's shutdown
  ;; sequence is exactly that, with no signal sent.
  (let [transport (stdio/connect! child-command {})
        c (client/client {:name "t" :capabilities {} :send! (:send! transport)})]
    (client/discover! c)
    (is (= 0 ((:close! transport)))
        "closing stdin is the whole shutdown; a conforming server exits 0")))

