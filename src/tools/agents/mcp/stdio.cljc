(ns tools.agents.mcp.stdio
  "The stdio transport: one newline-delimited JSON-RPC message per line over
   a process's standard streams.

   THE SERVER HALF IS PORTABLE WITH NO LEAF AT ALL. It needs `read-line`,
   `println`, `*err*` and `flush`, every one of which is plain clojure.core
   on JVM Clojure and Babashka alike — so unlike tools.agents.anthropic and
   tools.agents.openai, which each isolate an HTTP leaf behind a reader
   conditional, this namespace's server path has nothing runtime-specific in
   it at all.

   THE CLIENT HALF LAUNCHES A SUBPROCESS via java.lang.ProcessBuilder, which
   both runtimes provide.

   FRAMING RULES THIS ENFORCES (specification/2026-07-28/basic/transports/stdio):

     - One message per line, and messages MUST NOT contain embedded newlines.
       `mcp/write-json` escapes every control character below 0x20, so no
       tool output can break the framing.
     - The server MUST NOT write anything to stdout that is not a valid MCP
       message. `log!` below is the sanctioned way to print — it writes to
       stderr, which the spec explicitly permits and which the client SHOULD
       NOT read as an error signal. This is the Clojure equivalent of the
       Python tutorial's 'never call print() in a STDIO server'.
     - The server MUST NOT write JSON-RPC *requests* to stdout. It cannot:
       nothing in tools.agents.mcp.server constructs one. Server-to-client
       interactions leave as InputRequiredResult replies.
     - The server SHOULD exit promptly when stdin reaches EOF. `serve!`
       returns at that point, and a script that called it then ends.
     - Notifications for a `subscriptions/listen` stream share the one
       channel with everything else, so each carries
       `io.modelcontextprotocol/subscriptionId`; `notify!` tags them.

   LEGACY-CLIENT COMPATIBILITY, opt-in via `serve!`'s `:legacy-handshake?`:
   `server/handle` implements only revision 2026-07-28 (SEP-2575), which
   removed the `initialize` handshake and protocol-level sessions entirely.
   A client still speaking an earlier revision's classic handshake gets a
   flat 32602 from `server/handle` on its very first message, since that
   revision's `initialize` request has no `_meta` at all. See
   `handle-line-legacy!` for what this mode adds, and its docstring for
   which real client this was found against."

  (:require [clojure.string :as str]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.server :as server]))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(defn log!
  "Write a line to stderr. THE ONLY safe way for a stdio server to print.
   Anything on stdout that is not an MCP message corrupts the stream."
  [& parts]
  (binding [*out* *err*]
    (println (apply str parts))
    (flush)))

(defn- write-line!
  "Write one framed message. Serialized against concurrent writers, because a
   `subscriptions/listen` stream is pushed from another thread while request
   handling writes from this one, and two interleaved half-lines are two
   corrupt messages."
  [lock s]
  (locking lock (println s) (flush)))

;; ---------------------------------------------------------------------------
;; Connection
;; ---------------------------------------------------------------------------

(defn connection
  "A stdio connection. Holds no protocol state — the protocol is stateless —
   only the bookkeeping the TRANSPORT genuinely owns: which
   `subscriptions/listen` streams are currently open on this process's
   stdout, and the lock that keeps concurrent writes from interleaving."
  ([srv] (connection srv nil))
  ([srv {:keys [write-fn]}]
   (let [lock (Object.)
         write! (or write-fn (fn [s] (write-line! lock s)))]
     {:server srv
      :subscriptions (atom {})
      :write! write!
      :send! (fn [msg] (write! (mcp/write-json msg)))})))

(defn notify!
  "Push a change notification to every open subscription that opted into its
   type. Returns how many streams it went to.

   This is what a server calls from its own thread when something actually
   changes — a tool list edit, a resource update. It is deliberately the only
   way notifications reach a listen stream: `notifications/progress` and
   `notifications/message` are request-scoped and the spec forbids delivering
   them here, so they never pass through this function."
  [conn notification]
  (let [subs (vals @(:subscriptions conn))
        tagged (keep (fn [sub] (server/subscription-notification sub notification)) subs)]
    (doseq [n tagged] ((:send! conn) n))
    (count tagged)))

(defn close-subscriptions!
  "End every open subscription gracefully — the server SHOULD answer the
   original `subscriptions/listen` request before the stream goes away, so
   the client can tell a clean close from a dropped transport."
  [conn]
  (doseq [[id _] @(:subscriptions conn)]
    ((:send! conn) (server/close-subscription id)))
  (reset! (:subscriptions conn) {}))

(defn handle-message!
  "Dispatch one already-parsed message and write everything it produced.
   Returns the `handle` result map, so a caller can inspect it in a test
   without going anywhere near a real process."
  [conn message]
  (let [srv (:server conn)
        send! (:send! conn)
        ;; :emit! flushes each notification as it is produced rather than
        ;; after the handler returns, which is what makes progress reporting
        ;; on a long-running tool actually progressive.
        res (server/handle srv message {:emit! send!})]
    (when-let [sub (:subscription res)]
      (swap! (:subscriptions conn) assoc (:id sub) sub))
    (when-let [cancelled (:cancelled res)]
      ;; On stdio, notifications/cancelled is also how a client closes a
      ;; subscription stream: it references the listen request's id.
      (let [rid (:request-id cancelled)]
        (when (contains? @(:subscriptions conn) rid)
          (swap! (:subscriptions conn) dissoc rid))))
    (when-let [r (:response res)]
      (send! r))
    res))

(defn handle-line!
  "Parse one line of the framing and dispatch it. A line that is not valid
   JSON gets a -32700 response with a null id, which is the only case in
   which a JSON-RPC error response may omit the id."
  [conn line]
  (let [parsed (try
                 [::ok (mcp/read-json line)]
                 (catch Exception e [::bad e]))]
    (if (= ::bad (first parsed))
      (do ((:send! conn) (mcp/error-response nil mcp/parse-error
                                             (str "Parse error: " (ex-message (second parsed)))))
          {:response nil :notifications [] :parse-error (second parsed)})
      (handle-message! conn (second parsed)))))

;; ---------------------------------------------------------------------------
;; Legacy-handshake compatibility (opt-in)
;; ---------------------------------------------------------------------------

(defn legacy-initialize-response
  "A classic (pre-2026-07-28) InitializeResult for `srv`, answering `parsed`
   (an already-`mcp/read-json`'d `initialize` request). Echoes back whatever
   `protocolVersion` the client asked for — a conforming legacy client
   accepts whatever the server names here, and this function never actually
   negotiates one, so echoing avoids a spurious version-mismatch on either
   side. `serverInfo` comes straight from `(:info srv)`, the same map
   `server/handle` itself advertises."
  [srv id parsed]
  {"jsonrpc" mcp/jsonrpc-version
   "id" id
   "result" {"protocolVersion" (or (get-in parsed ["params" "protocolVersion"])
                                    mcp/latest-protocol-version)
             "capabilities" {"tools" {}}
             "serverInfo" (:info srv)}})

(defn legacy-stamp-meta
  "Fill in the two `_meta` fields `server/handle` requires (mcp.cljc's
   `request-meta`, minimal form: `io.modelcontextprotocol/protocolVersion`
   and `io.modelcontextprotocol/clientCapabilities`) on an already-parsed
   request, leaving every other `_meta` key untouched.

   Repairs per-field rather than replacing the whole `_meta` wholesale: a
   legacy client's `tools/call` typically DOES carry its own `_meta` (a
   `progressToken`) while never sending either field this revision
   requires, so an all-or-nothing 'stamp only if `_meta` is entirely
   absent' guard would stamp nothing on exactly the requests that need it —
   and clobbering the whole map instead would silently drop
   `progressToken`, which `server/handle` correlates progress
   notifications by."
  [parsed]
  (update parsed "params"
          (fn [params]
            (let [params (or params {})
                  m (get params "_meta" {})]
              (assoc params "_meta"
                     (cond-> m
                       (not (string? (get m mcp/meta-protocol-version)))
                       (assoc mcp/meta-protocol-version mcp/latest-protocol-version)

                       (not (map? (get m mcp/meta-client-caps)))
                       (assoc mcp/meta-client-caps {})))))))

(defn handle-line-legacy!
  "`handle-line!`, plus the three things a classic (pre-2026-07-28) client
   needs that revision 2026-07-28 removed:

     - `initialize` is answered locally with `legacy-initialize-response`
       rather than reaching `server/handle`, which has no method for it at
       all (SEP-2575 removed the handshake outright).
     - `notifications/initialized` is swallowed: it is a notification (no
       `id`), so no response is correct either way, but a legacy client
       sends it unprompted and it would otherwise fall through to
       `server/handle` and 32601.
     - `ping` gets an empty result. `server/handle` has no `\"ping\"`
       method — the stateless revision has no session to keep alive — but a
       legacy client's keepalive MUST get a prompt response or it may drop
       the connection mid-session; that failure mode is invisible to a
       startup-time smoke test since it only shows up minutes into a real
       session.

   Every other request is stamped with `legacy-stamp-meta` (the required
   `_meta` a legacy client never sends) and handed to `handle-message!`
   exactly as `handle-line!` would."
  [conn line]
  (let [srv (:server conn)
        parsed (try [::ok (mcp/read-json line)] (catch Exception e [::bad e]))]
    (cond
      (= ::bad (first parsed))
      (do ((:send! conn) (mcp/error-response nil mcp/parse-error
                                             (str "Parse error: " (ex-message (second parsed)))))
          {:response nil :notifications [] :parse-error (second parsed)})

      (= "initialize" (get (second parsed) "method"))
      (let [resp (legacy-initialize-response srv (get (second parsed) "id") (second parsed))]
        ((:send! conn) resp)
        {:response resp :notifications []})

      (= "notifications/initialized" (get (second parsed) "method"))
      {:response nil :notifications []}

      (= "ping" (get (second parsed) "method"))
      (let [resp {"jsonrpc" mcp/jsonrpc-version "id" (get (second parsed) "id") "result" {}}]
        ((:send! conn) resp)
        {:response resp :notifications []})

      :else
      (handle-message! conn (legacy-stamp-meta (second parsed))))))

;; ---------------------------------------------------------------------------
;; Server loop
;; ---------------------------------------------------------------------------

(defn serve!
  "Run the server on stdin/stdout until EOF. Blocks.

   `:on-start` is called once with the connection before the loop begins —
   that is where a server spawns whatever thread pushes `notify!` updates.
   `:on-stop` is called after EOF, before the graceful close of any open
   subscription.

   Blank lines are skipped rather than answered with a parse error: they are
   framing noise, not messages, and answering one would put a response on
   the wire that no request asked for.

   `:legacy-handshake?` (default false) routes each line through
   `handle-line-legacy!` instead of `handle-line!` — opt into this when the
   client on the other end still speaks the pre-2026-07-28 `initialize`
   handshake, which this library's `server/handle` does not implement at
   all (see `handle-line-legacy!`'s docstring). Concretely: as of this
   writing, every general-purpose MCP client this library has been tested
   against (Claude Code's own) still needs this — `server/handle` alone
   32602s such a client's very first message. Leave it off for a
   revision-2026-07-28-native client, or a test harness driving the server
   directly through JSON that already carries `_meta`."
  ([srv] (serve! srv nil))
  ([srv {:keys [on-start on-stop conn legacy-handshake?]}]
   (let [conn (or conn (connection srv))
         handle-line! (if legacy-handshake? handle-line-legacy! handle-line!)]
     (when on-start (on-start conn))
     (loop []
       (let [line (read-line)]
         (cond
           (nil? line) nil                       ;; EOF — exit promptly
           (= "" (str/trim line)) (recur)
           :else (do (handle-line! conn line) (recur)))))
     (when on-stop (on-stop conn))
     (close-subscriptions! conn)
     conn)))

;; ---------------------------------------------------------------------------
;; Client side — launches the server as a subprocess (JVM Clojure / Babashka)
;; ---------------------------------------------------------------------------

(defn connect!
  "Launch `command` (a vector of program + args) as an MCP server subprocess
   and return a transport map:

     {:send! f  :process p  :close! f}

   `:send!` goes straight into `tools.agents.mcp.client/client`. It writes
   the request, then reads lines until the one whose `id` matches — handing
   every other line (notifications, and responses to other in-flight
   requests) to `:on-notification`. A notification is not an error and never
   terminates the wait.

   The server's stderr is drained on its own thread into `:on-stderr`
   (default: discard). The spec says a client SHOULD NOT treat stderr output
   as indicating an error."
  [command {:keys [on-notification on-stderr env]
            :or {on-notification (fn [_])}}]
  (let [pb (java.lang.ProcessBuilder. ^java.util.List (vec command))
        _ (when (seq env)
            (let [m (.environment pb)]
              (doseq [[k v] env] (.put m (str (if (keyword? k) (name k) k)) (str v)))))
        proc (.start pb)
        w (java.io.BufferedWriter. (java.io.OutputStreamWriter. (.getOutputStream proc) "UTF-8"))
        r (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc) "UTF-8"))
        e (java.io.BufferedReader. (java.io.InputStreamReader. (.getErrorStream proc) "UTF-8"))
        lock (Object.)]
    ;; A DAEMON thread, not `future`: clojure.core/future runs on the agent
    ;; send-off pool, whose non-daemon threads keep a JVM alive for a full
    ;; minute after main returns. A transport must not hold its host open.
    (doto (Thread. ^Runnable (fn [] (loop []
                                      (when-let [l (.readLine e)]
                                        (when on-stderr (on-stderr l))
                                        (recur)))))
      (.setDaemon true)
      (.start))
    {:process proc
     :reader r
     :writer w
     ;; Shutdown as the spec defines it: close the child's stdin and wait.
     ;; A conforming server exits on EOF, so no signal is needed.
     :close! (fn [] (.close w) (.waitFor proc))
     :send!
     (fn [msg]
       ;; Write and read under one lock: this transport is synchronous
       ;; request/response. A `subscriptions/listen` stream never produces
       ;; the response this would wait for, so drive one off :reader
       ;; directly rather than through :send!.
       (locking lock
         (.write w (mcp/write-json msg))
         (.newLine w)
         (.flush w)
         (when (mcp/request? msg)
           (loop []
             (let [line (.readLine r)]
               (when (nil? line)
                 (throw (ex-info "tools.agents.mcp.stdio: server closed its output stream before responding"
                                 {:type :tools.agents.mcp.error/transport :request msg})))
               (let [m (mcp/read-json line)]
                 (if (and (map? m)
                          (= (get m "id") (get msg "id"))
                          (or (contains? m "result") (contains? m "error")))
                   m
                   (do (on-notification m) (recur)))))))))}))
