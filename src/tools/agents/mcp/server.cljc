(ns tools.agents.mcp.server
  "An MCP server for revision 2026-07-28, ergonomically modeled on
   mcp-python-sdk v2's `MCPServer` — the same primitives (tools, resources,
   resource templates, prompts, completions), registered the same way, with
   the same names.

   THE WHOLE SERVER IS ONE PURE FUNCTION:

       (handle server request) => {:response ... :notifications [...]}

   `handle` reads nothing outside its two arguments and writes nothing. That
   is not a stylistic choice; it is what SEP-2575 made possible by removing
   the `initialize` handshake and protocol-level sessions. Every request now
   carries its own protocol version, client capabilities and identity in
   `_meta`, and the spec says a server 'MUST NOT rely on prior requests over
   the same connection to establish context'. There is nothing left for a
   session object to hold.

   WHERE MUTABLE STATE IS STILL LEGITIMATE, and how to carry it: state that
   spans requests 'MUST be referenced by an explicit identifier the client
   passes on each request'. So a server that needs it closes over its own
   atom in its handlers and takes a server-minted handle as an ordinary tool
   ARGUMENT — never as an implicit property of the connection. See
   examples/mcp/everything.clj, which does exactly this for the session
   resources the pre-2026-07-28 everything server kept per connection.

   SPEC RULES ENFORCED HERE RATHER THAN LEFT TO EACH HANDLER — each has a
   test in test/tools/agents/mcp/conformance_test.cljc naming the clause:

     1. Every result carries `resultType`; the only way out is
        `mcp/result-response`, which stamps it.
     2. A request whose `_meta` omits `io.modelcontextprotocol/protocolVersion`
        or `io.modelcontextprotocol/clientCapabilities` is malformed and gets
        -32602 — before any handler runs.
     3. An unsupported protocol version gets -32022 carrying `supported` and
        `requested`.
     4. `notifications/message` is dropped unless the request carried
        `io.modelcontextprotocol/logLevel`, and then only at or above it.
        `notifications/progress` is dropped unless the request carried a
        `progressToken`. Handlers call `:log!`/`:progress!` unconditionally
        and the gate lives here, so the MUST NOT cannot be violated by
        forgetting a check.
     5. Server-to-client interactions leave only as an InputRequiredResult.
        There is no code path in this namespace that emits a JSON-RPC
        *request*, so a server built on it cannot violate the stdio rule
        that servers MUST NOT write requests to stdout.
     6. An input request whose capability the client did not declare is
        refused with -32021 — checked up front by a handler calling
        `mcp/require-capabilities!`, and again here as a backstop before the
        result goes on the wire.
     7. `server/discover` is answered whatever is registered.
     8. Resource-not-found is -32602. -32002 and -32042 are never emitted.

   REQUEST STATE IS ATTACKER-CONTROLLED. `requestState` round-trips through
   the client, so a server that lets it influence authorization, resource
   access or business logic MUST integrity-protect it (HMAC or AEAD) and
   reject what fails verification, and SHOULD bind a principal, an expiry
   and an identifier for the originating request inside the protected
   payload. This namespace does not do that for you: it hands
   `:request-state` to your handler as the opaque string it arrived as.
   Integrity protection MAY be omitted only when tampering can cause nothing
   worse than the request failing."
  (:require [clojure.string :as str]
            [tools.agents.mcp :as mcp]))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn- index-by-name
  "Preserve registration order (the spec SHOULDs a deterministic `tools/list`
   order so clients can cache and prompt-cache hit), while giving O(1)
   lookup by name."
  [items]
  (reduce (fn [m item]
            (when (contains? m (:name item))
              (throw (ex-info (str "tools.agents.mcp.server/server: duplicate name " (pr-str (:name item)))
                              {:type :tools.agents.mcp.error/duplicate-name :name (:name item)})))
            (assoc m (:name item) item))
          {}
          items))

(defn- require-name! [kind item]
  (when-not (string? (:name item))
    (throw (ex-info (str "tools.agents.mcp.server/server: every " kind " needs a string :name")
                    {:type :tools.agents.mcp.error/invalid-registration :kind kind :item item})))
  item)

(defn- require-handler! [kind item]
  (when-not (fn? (:handler item))
    (throw (ex-info (str "tools.agents.mcp.server/server: " kind " " (pr-str (:name item)) " needs an :handler fn")
                    {:type :tools.agents.mcp.error/invalid-registration :kind kind :item item})))
  item)

(defn server
  "Build a server. Returns a plain map — the same 'client object is a plain
   map' choice tools.agents.anthropic makes: a server is data you can print,
   diff and assemble, not an opaque record.

   Identity — :name (required), :version (default \"0.0.0\"), :title,
   :description, :website-url, :icons, :instructions.

   Primitives, each a vector of descriptor maps:

     :tools               {:name :title :description :input-schema
                           :output-schema :annotations :icons :meta
                           :handler (fn [ctx arguments] ...)}
     :resources           {:name :title :uri :description :mime-type
                           :annotations :size :icons :meta
                           :handler (fn [ctx uri] ...)}
     :resource-templates  {:name :title :uri-template :description
                           :mime-type :annotations :icons :meta
                           :handler (fn [ctx uri variables] ...)}
     :prompts             {:name :title :description :arguments :icons :meta
                           :handler (fn [ctx arguments] ...)}

   :completions  (fn [ctx ref argument context] ...) — one function, matching
                 the Python SDK's single `@mcp.completion()` hook rather than
                 the TypeScript SDK's per-argument `completable()` wrapper.

   :methods      {\"some/method\" (fn [ctx request] result)} — an escape hatch
                 for extension methods (`io.modelcontextprotocol/tasks` and
                 the like), which this revision moved out of the core.

   Notification and cache options:

     :tools-list-changed?     :prompts-list-changed?
     :resources-list-changed? :resources-subscribe?
     :logging?      declare the (deprecated but functional) logging capability
     :cache         {:ttl-ms 0 :cache-scope \"private\"} — `ttlMs`/`cacheScope`
                    are REQUIRED fields on every list result, discover, and
                    resources/read in this revision (SEP-2549). The defaults
                    are the conservative ones: stale immediately, never shared
                    across authorization contexts.
     :page-size     when set, list results paginate with an opaque cursor.
                    Either a number (every list) or a map keyed by list kind,
                    {:tools n :resources n :resource-templates n :prompts n},
                    so paging a hundred resources need not also page a
                    dozen tools.
     :advertise-server-info?  default true
     :experimental / :extensions  merged into the advertised capabilities"
  [{:keys [name version title description website-url icons instructions
           tools resources resource-templates prompts completions methods
           tools-list-changed? prompts-list-changed? resources-list-changed?
           resources-subscribe? logging? cache page-size
           advertise-server-info? experimental extensions]
    :or {version "0.0.0" advertise-server-info? true}}]
  (when-not (string? name)
    (throw (ex-info "tools.agents.mcp.server/server: :name is required and must be a string"
                    {:type :tools.agents.mcp.error/invalid-registration})))
  (let [tools (mapv (comp (partial require-handler! "tool") (partial require-name! "tool")) tools)
        resources (mapv (comp (partial require-handler! "resource") (partial require-name! "resource")) resources)
        templates (mapv (comp (partial require-handler! "resource template") (partial require-name! "resource template")) resource-templates)
        prompts (mapv (comp (partial require-handler! "prompt") (partial require-name! "prompt")) prompts)]
    {:info (into {} (remove (fn [[_ v]] (nil? v))
                            {"name" name "version" version "title" title
                             "description" description "websiteUrl" website-url
                             "icons" icons}))
     :instructions instructions
     :tools tools
     :tools-by-name (index-by-name tools)
     :resources resources
     :resources-by-uri (reduce (fn [m r] (assoc m (:uri r) r)) {} resources)
     :resource-templates templates
     :prompts prompts
     :prompts-by-name (index-by-name prompts)
     :completions completions
     :methods (or methods {})
     :tools-list-changed? (boolean tools-list-changed?)
     :prompts-list-changed? (boolean prompts-list-changed?)
     :resources-list-changed? (boolean resources-list-changed?)
     :resources-subscribe? (boolean resources-subscribe?)
     :logging? (boolean logging?)
     :cache (merge {:ttl-ms 0 :cache-scope "private"} cache)
     :page-size page-size
     :advertise-server-info? advertise-server-info?
     :experimental experimental
     :extensions extensions}))

(defn capabilities
  "ServerCapabilities derived from what is actually registered — a server
   cannot advertise `tools` while having none, or forget to advertise them
   while having some."
  [srv]
  (into {} (remove (fn [[_ v]] (nil? v))
                   {"tools" (when (seq (:tools srv))
                              (if (:tools-list-changed? srv) {"listChanged" true} {}))
                    "prompts" (when (seq (:prompts srv))
                                (if (:prompts-list-changed? srv) {"listChanged" true} {}))
                    "resources" (when (or (seq (:resources srv)) (seq (:resource-templates srv)))
                                  (into {} (remove (fn [[_ v]] (nil? v))
                                                   {"subscribe" (when (:resources-subscribe? srv) true)
                                                    "listChanged" (when (:resources-list-changed? srv) true)})))
                    "completions" (when (:completions srv) {})
                    "logging" (when (:logging? srv) {})
                    "experimental" (:experimental srv)
                    "extensions" (:extensions srv)})))

;; ---------------------------------------------------------------------------
;; Caching / pagination
;; ---------------------------------------------------------------------------

(defn- cacheable
  "Stamp the `ttlMs`/`cacheScope` that CacheableResult makes REQUIRED on
   `server/discover`, all four list results and `resources/read`."
  [srv m]
  (assoc m "ttlMs" (:ttl-ms (:cache srv))
           "cacheScope" (:cache-scope (:cache srv))))

(def ^:private cursor-prefix "o")

(defn- parse-cursor [cursor]
  (when (some? cursor)
    (when-not (and (string? cursor)
                   (str/starts-with? cursor cursor-prefix)
                   (seq (subs cursor 1))
                   (every? #(contains? (set "0123456789") %) (subs cursor 1)))
      (mcp/invalid-params! (str "Invalid cursor: " (pr-str cursor))))
    (mcp/read-json (subs cursor 1))))

(defn- page-size-for
  "`:page-size` is either one number for every list, or a map keyed by list
   kind — :tools :resources :resource-templates :prompts — so a server can
   page its hundred resources without also hiding half its tools behind a
   cursor."
  [srv kind]
  (let [ps (:page-size srv)]
    (if (map? ps) (get ps kind) ps)))

(defn- paginate
  "Split `items` at the page size for `kind`, returning [page next-cursor].
   The cursor is opaque to the client by contract; that it happens to be an
   offset is this server's business alone."
  [srv kind items cursor]
  (let [size (page-size-for srv kind)
        start (or (parse-cursor cursor) 0)]
    (if-not size
      [(vec (drop start items)) nil]
      (let [page (vec (take size (drop start items)))
            next-start (+ start (count page))]
        [page (when (< next-start (count items)) (str cursor-prefix next-start))]))))

;; ---------------------------------------------------------------------------
;; Wire shapes for the registered primitives
;; ---------------------------------------------------------------------------

(defn tool->wire [t]
  (mcp/prune {"name" (:name t)
          "title" (:title t)
          "description" (:description t)
          "inputSchema" (or (:input-schema t) {"type" "object"})
          "outputSchema" (:output-schema t)
          "annotations" (:annotations t)
          "icons" (:icons t)
          "_meta" (:meta t)}))

(defn resource->wire [r]
  (mcp/prune {"uri" (:uri r) "name" (:name r) "title" (:title r)
          "description" (:description r) "mimeType" (:mime-type r)
          "annotations" (:annotations r) "size" (:size r)
          "icons" (:icons r) "_meta" (:meta r)}))

(defn resource-template->wire [t]
  (mcp/prune {"uriTemplate" (:uri-template t) "name" (:name t) "title" (:title t)
          "description" (:description t) "mimeType" (:mime-type t)
          "annotations" (:annotations t) "icons" (:icons t) "_meta" (:meta t)}))

(defn prompt->wire [p]
  (mcp/prune {"name" (:name p) "title" (:title p) "description" (:description p)
          "arguments" (when (seq (:arguments p))
                        (mapv (fn [a] (mcp/prune {"name" (:name a) "title" (:title a)
                                              "description" (:description a)
                                              "required" (:required a)}))
                              (:arguments p)))
          "icons" (:icons p) "_meta" (:meta p)}))

;; ---------------------------------------------------------------------------
;; URI templates — RFC 6570 simple string expansion, matched by scanning
;; rather than by regex, so the split points are exactly the two the RFC
;; defines and nothing else can be read as a metacharacter.
;; ---------------------------------------------------------------------------

(defn parse-uri-template
  "Split a template into alternating literal and variable segments:
   \"demo://x/{id}\" -> [[:lit \"demo://x/\"] [:var \"id\"]]."
  [template]
  (loop [s template acc []]
    (let [open (str/index-of s "{")]
      (if (nil? open)
        (if (seq s) (conj acc [:lit s]) acc)
        (let [close (str/index-of s "}" open)]
          (when (nil? close)
            (throw (ex-info (str "tools.agents.mcp.server: unterminated {var} in URI template " (pr-str template))
                            {:type :tools.agents.mcp.error/invalid-registration})))
          (recur (subs s (inc close))
                 (cond-> acc
                   (pos? open) (conj [:lit (subs s 0 open)])
                   true (conj [:var (subs s (inc open) close)]))))))))

(defn match-uri-template
  "Match `uri` against `template`, returning a map of variable name -> value,
   or nil. Variables never match the empty string, and a variable followed by
   a literal matches lazily up to that literal's next occurrence."
  [template uri]
  (loop [segs (parse-uri-template template) s uri vars {}]
    (if (empty? segs)
      (when (empty? s) vars)
      (let [[kind v] (first segs)
            rest-segs (next segs)]
        (cond
          (= kind :lit)
          (when (str/starts-with? s v) (recur rest-segs (subs s (count v)) vars))

          ;; trailing variable: takes the remainder, which must be non-empty
          (empty? rest-segs)
          (when (seq s) (assoc vars v s))

          :else
          (let [[_ lit] (first rest-segs)
                idx (str/index-of s lit)]
            (when (and idx (pos? idx))
              (recur (next rest-segs) (subs s (+ idx (count lit)))
                     (assoc vars v (subs s 0 idx))))))))))

(defn- find-template [srv uri]
  (some (fn [t] (when-let [vars (match-uri-template (:uri-template t) uri)]
                  [t vars]))
        (:resource-templates srv)))

;; ---------------------------------------------------------------------------
;; Request validation
;; ---------------------------------------------------------------------------

(defn validate-request-meta!
  "Enforce the per-request protocol fields. A request missing a required one
   is malformed: the server MUST reject it with -32602 (and, on HTTP, 400).
   Under a stateless protocol there is no earlier request to inherit them
   from, so this cannot be relaxed."
  [request]
  (let [m (mcp/params-meta request)
        pv (get m mcp/meta-protocol-version)
        caps (get m mcp/meta-client-caps)
        lvl (get m mcp/meta-log-level)]
    (when-not (string? pv)
      (mcp/invalid-params!
       (str "Missing or invalid " mcp/meta-protocol-version " in request _meta")))
    (when-not (map? caps)
      (mcp/invalid-params!
       (str "Missing or invalid " mcp/meta-client-caps " in request _meta")))
    (when (and (some? lvl) (not (contains? (set mcp/logging-levels) lvl)))
      (mcp/invalid-params! (str "Invalid " mcp/meta-log-level ": " (pr-str lvl))))
    pv))

;; ---------------------------------------------------------------------------
;; Handler context
;; ---------------------------------------------------------------------------

(defn- make-context
  "The map every handler receives. `:progress!` and `:log!` are the ONLY
   sanctioned way to emit a request-scoped notification, and both are
   already gated — a handler calls them unconditionally and the spec's MUST
   NOTs are honoured whether or not the handler thought about them."
  [srv request emit!]
  (let [m (mcp/params-meta request)
        token (get m mcp/meta-progress-token)
        min-level (get m mcp/meta-log-level)]
    {:server srv
     :request request
     :method (get request "method")
     :params (get request "params" {})
     :meta m
     :protocol-version (get m mcp/meta-protocol-version)
     :client-capabilities (get m mcp/meta-client-caps)
     :client-info (get m mcp/meta-client-info)
     :log-level min-level
     :progress-token token
     :input-responses (mcp/input-responses request)
     :request-state (mcp/request-state request)
     :emit! emit!
     :progress! (fn progress!
                  ([progress] (progress! progress nil))
                  ([progress opts]
                   ;; A request without a progressToken did not opt in, so
                   ;; there is nothing to correlate a progress notification
                   ;; with and none is emitted.
                   (when (some? token)
                     (emit! (mcp/progress-notification token progress opts)))))
     :log! (fn log!
             ([level data] (log! level data nil))
             ([level data opts]
              ;; The server MUST NOT emit notifications/message for a request
              ;; that did not carry io.modelcontextprotocol/logLevel, and then
              ;; only at or above the level it did carry.
              (when (and (some? min-level) (mcp/log-level-at-least? level min-level))
                (emit! (mcp/log-notification level (assoc opts :data data))))))}))

;; ---------------------------------------------------------------------------
;; Method handlers
;;
;; Each takes [srv ctx params] and returns a RESULT BODY (a plain map without
;; `resultType`); `dispatch` below stamps `resultType` and `_meta.serverInfo`
;; once, for all of them. A handler that needs a different result type — the
;; MRTR `input_required` — returns one built by `mcp/input-required`, which
;; has already stamped its own.
;; ---------------------------------------------------------------------------

(defn- handle-discover [srv _ctx _params]
  (cacheable srv
             (into {} (remove (fn [[_ v]] (nil? v))
                              {"supportedVersions" (vec mcp/supported-protocol-versions)
                               "capabilities" (capabilities srv)
                               "instructions" (:instructions srv)}))))

(defn- handle-tools-list [srv _ctx params]
  (let [[page next-cursor] (paginate srv :tools (:tools srv) (get params "cursor"))]
    (cacheable srv (into {"tools" (mapv tool->wire page)}
                         (when next-cursor {"nextCursor" next-cursor})))))

(defn- normalize-tool-result
  "Accept what a tool handler returned. A bare string or a vector of content
   blocks is sugar for `mcp/tool-result`; a map is taken as an
   already-constructed result body."
  [v]
  (cond
    (nil? v) (mcp/tool-result [])
    (string? v) (mcp/tool-result v)
    (map? v) (if (contains? v "resultType") v (mcp/result v))
    (sequential? v) (mcp/tool-result (vec v))
    :else (mcp/tool-result (str v))))

(defn- check-input-request-capabilities!
  "Backstop for MRTR server requirement 7: a server MUST NOT send an
   `inputRequests` entry the client has not declared support for. A handler
   should call `mcp/require-capabilities!` up front — the spec wants the
   refusal BEFORE the work, not after — but nothing forbidden gets past
   here even if it forgets."
  [ctx res]
  (when (mcp/input-required? res)
    (let [caps (:client-capabilities ctx)]
      (doseq [[_k req] (get res "inputRequests")]
        (when-let [required (mcp/input-request-required-capability req)]
          (mcp/require-capabilities! caps required)))))
  res)

(defn- handle-tools-call [srv ctx params]
  (let [tool-name (get params "name")
        tool (get (:tools-by-name srv) tool-name)]
    (when-not tool
      (mcp/not-found! (str "Unknown tool: " (pr-str tool-name))))
    (->> (try
           ((:handler tool) ctx (get params "arguments" {}))
           (catch Exception e
             ;; A tool that throws an MCP protocol error means it — that
             ;; propagates as a JSON-RPC error. Anything else is an execution
             ;; failure of the tool itself, which the spec says to report as
             ;; `isError` inside a normal result so the model can see and
             ;; recover from it.
             (if (= :tools.agents.mcp.error/protocol (:type (ex-data e)))
               (throw e)
               (mcp/tool-error (str "Tool " (pr-str tool-name) " failed: "
                                    (or (ex-message e) (str e)))))))
         (normalize-tool-result)
         (check-input-request-capabilities! ctx))))

(defn- handle-resources-list [srv _ctx params]
  (let [[page next-cursor] (paginate srv :resources (:resources srv) (get params "cursor"))]
    (cacheable srv (into {"resources" (mapv resource->wire page)}
                         (when next-cursor {"nextCursor" next-cursor})))))

(defn- handle-resource-templates-list [srv _ctx params]
  (let [[page next-cursor] (paginate srv :resource-templates (:resource-templates srv) (get params "cursor"))]
    (cacheable srv (into {"resourceTemplates" (mapv resource-template->wire page)}
                         (when next-cursor {"nextCursor" next-cursor})))))

(defn- normalize-contents
  "A resource handler may return a string (text), one contents map, or a
   vector of them."
  [uri v]
  (cond
    (string? v) [(mcp/text-contents uri v)]
    (map? v) (if (contains? v "resultType") v [v])
    (sequential? v) (vec v)
    (nil? v) []
    :else [(mcp/text-contents uri (str v))]))

(defn- handle-resources-read [srv ctx params]
  (let [uri (get params "uri")]
    (when-not (string? uri)
      (mcp/invalid-params! "resources/read requires a string \"uri\" param"))
    (let [static (get (:resources-by-uri srv) uri)
          [tmpl vars] (when-not static (find-template srv uri))]
      (when-not (or static tmpl)
        ;; -32602, not -32002: this revision retired the old resource-not-found
        ;; code, and implementations of it MUST NOT emit -32002 at all.
        (mcp/not-found! (str "Resource not found: " uri)))
      (let [raw (if static
                  ((:handler static) ctx uri)
                  ((:handler tmpl) ctx uri vars))
            norm (normalize-contents uri raw)]
        (check-input-request-capabilities! ctx
          (if (and (map? norm) (mcp/input-required? norm))
            norm
            (cacheable srv {"contents" norm})))))))

(defn- handle-prompts-list [srv _ctx params]
  (let [[page next-cursor] (paginate srv :prompts (:prompts srv) (get params "cursor"))]
    (cacheable srv (into {"prompts" (mapv prompt->wire page)}
                         (when next-cursor {"nextCursor" next-cursor})))))

(defn- normalize-prompt-result [p v]
  (cond
    (map? v) v
    (sequential? v) (into {} (remove (fn [[_ x]] (nil? x))
                                     {"description" (:description p) "messages" (vec v)}))
    (string? v) {"description" (:description p)
                 "messages" [(mcp/user-message (mcp/text v))]}
    :else (mcp/internal-error! (str "Prompt " (pr-str (:name p)) " returned an unusable value"))))

(defn- handle-prompts-get [srv ctx params]
  (let [prompt-name (get params "name")
        prompt (get (:prompts-by-name srv) prompt-name)]
    (when-not prompt
      (mcp/not-found! (str "Unknown prompt: " (pr-str prompt-name))))
    (let [args (get params "arguments" {})]
      (doseq [a (:arguments prompt)]
        (when (and (:required a) (not (contains? args (:name a))))
          (mcp/invalid-params! (str "Missing required prompt argument: " (:name a)))))
      (check-input-request-capabilities! ctx
        (normalize-prompt-result prompt ((:handler prompt) ctx args))))))

(defn- handle-completion [srv ctx params]
  (let [f (:completions srv)]
    (when-not f (mcp/error! mcp/method-not-found "This server does not support completions"))
    (let [ref (get params "ref")
          argument (get params "argument")
          context (get params "context")
          v (f ctx ref argument context)
          completion (cond
                       (map? v) v
                       ;; `n` bound once: a handler may return a lazy seq,
                       ;; and counting it twice walks it twice.
                       (sequential? v) (let [n (count v)]
                                         {"values" (vec (take 100 v))
                                          "total" n
                                          "hasMore" (> n 100)})
                       (nil? v) {"values" []}
                       :else (mcp/internal-error! "Completion handler returned an unusable value"))]
      {"completion" completion})))

(defn- agreed-subscription-filter
  "The subset of the requested filter this server will honour. The
   acknowledgment reflects what was agreed, not what was asked; types the
   server does not support are omitted, and the server MUST NOT then send
   them."
  [srv requested]
  (into {} (remove (fn [[_ v]] (nil? v))
                   {"toolsListChanged" (when (and (get requested "toolsListChanged")
                                                  (:tools-list-changed? srv)) true)
                    "promptsListChanged" (when (and (get requested "promptsListChanged")
                                                    (:prompts-list-changed? srv)) true)
                    "resourcesListChanged" (when (and (get requested "resourcesListChanged")
                                                      (:resources-list-changed? srv)) true)
                    "resourceSubscriptions" (when (:resources-subscribe? srv)
                                              (some-> (get requested "resourceSubscriptions") vec))})))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(def ^:private method-table
  {"server/discover" handle-discover
   "tools/list" handle-tools-list
   "tools/call" handle-tools-call
   "resources/list" handle-resources-list
   "resources/templates/list" handle-resource-templates-list
   "resources/read" handle-resources-read
   "prompts/list" handle-prompts-list
   "prompts/get" handle-prompts-get
   "completion/complete" handle-completion})

(defn- with-server-info [srv result]
  (if (:advertise-server-info? srv)
    (assoc-in result ["_meta" mcp/meta-server-info] (:info srv))
    result))

(defn handle-notification
  "Client-to-server notifications. This revision defines exactly one —
   `notifications/cancelled` — and a notification never gets a response."
  [_srv message]
  (let [method (get message "method")]
    {:response nil
     :notifications []
     :cancelled (when (= "notifications/cancelled" method)
                  {:request-id (get-in message ["params" "requestId"])
                   :reason (get-in message ["params" "reason"])})}))

(defn handle
  "Handle one incoming JSON-RPC message. Pure: reads nothing outside its
   arguments, performs no I/O, and returns everything it produced.

     (handle server request)
     (handle server request {:emit! f})

   Returns a map:

     :response       the JSON-RPC response, or nil for a notification and for
                     `subscriptions/listen` (whose response comes only at
                     graceful closure)
     :notifications  request-scoped notifications the handler emitted, in
                     order, already filtered by the client's `progressToken`
                     and `logLevel` opt-ins
     :subscription   present only for `subscriptions/listen`: {:id :filter},
                     the stream the transport should now hold open
     :cancelled      present only for `notifications/cancelled`

   `:emit!`, when given, is also called with each notification as it is
   produced, so a streaming transport can flush it before the final response
   instead of waiting for the return value. The returned vector is populated
   either way."
  ([srv message] (handle srv message nil))
  ([srv message {:keys [emit!]}]
   (let [collected (atom [])
         emit (fn [n] (swap! collected conj n) (when emit! (emit! n)) n)
         done (fn [response] {:response response :notifications @collected})]
     (cond
       (not (map? message))
       (done (mcp/error-response nil mcp/invalid-request "Request must be a JSON object"))

       (and (contains? message "jsonrpc") (not= mcp/jsonrpc-version (get message "jsonrpc")))
       (done (mcp/error-response (get message "id") mcp/invalid-request
                                 "Unsupported \"jsonrpc\" version"))

       (not (string? (get message "method")))
       (done (mcp/error-response (get message "id") mcp/invalid-request
                                 "Request must carry a string \"method\""))

       (mcp/notification? message)
       (merge (done nil) (dissoc (handle-notification srv message) :notifications))

       :else
       (let [id (get message "id")
             method (get message "method")
             params (get message "params" {})]
         (try
           (let [pv (validate-request-meta! message)]
             (when-not (contains? (set mcp/supported-protocol-versions) pv)
               ;; Not `error!`: -32022's data shape is fixed by the schema and
               ;; is what a probing client reads to pick a version.
               (throw (ex-info "unsupported-protocol-version"
                               {:type :tools.agents.mcp.error/protocol
                                :code mcp/unsupported-protocol-version
                                :message (str "Unsupported protocol version: " (pr-str pv))
                                :data {"supported" (vec mcp/supported-protocol-versions)
                                       "requested" pv}})))
             (let [ctx (make-context srv message emit)]
               (if (= "subscriptions/listen" method)
                 (let [requested (get params "notifications" {})
                       agreed (agreed-subscription-filter srv requested)]
                   ;; The acknowledgment MUST be the first message on the
                   ;; subscription, and every message on it — this one
                   ;; included — carries the subscription id in _meta.
                   (emit (mcp/with-subscription-id
                          (mcp/notification "notifications/subscriptions/acknowledged"
                                            {"notifications" agreed})
                          id))
                   (assoc (done nil) :subscription {:id id :filter agreed}))
                 (if-let [f (or (method-table method)
                                (when-let [g (get (:methods srv) method)]
                                  (fn [_srv ctx _params] (g ctx message))))]
                   (done (mcp/result-response id (with-server-info srv (f srv ctx params))))
                   (done (mcp/error-response id mcp/method-not-found
                                             (str "Method not found: " method)))))))
           (catch Exception e
             (let [d (ex-data e)]
               (done (if (= :tools.agents.mcp.error/protocol (:type d))
                       (mcp/error-response id (:code d)
                                           (or (:message d) (ex-message e) "error")
                                           (:data d))
                       (mcp/error-ex->response id e)))))))))))

(defn close-subscription
  "The graceful-closure response for a `subscriptions/listen` stream the
   SERVER is ending (shutdown, say). A client that receives it knows the
   subscription closed cleanly; a transport that just drops carries no such
   response."
  [subscription-id]
  (mcp/with-subscription-id
   (mcp/result-response subscription-id (mcp/result {}))
   subscription-id))

(defn subscription-notification
  "Tag a change notification for delivery on a `subscriptions/listen` stream,
   returning nil when the subscription did not opt into that type — the
   server MUST NOT send notification types the client did not request.
   Request-scoped notifications (`notifications/progress`,
   `notifications/message`) are deliberately NOT deliverable here: the spec
   confines them to the response stream of the request they relate to."
  [subscription notif]
  (let [{:keys [id]} subscription
        flt (:filter subscription)
        method (get notif "method")
        allowed (case method
                  "notifications/tools/list_changed" (get flt "toolsListChanged")
                  "notifications/prompts/list_changed" (get flt "promptsListChanged")
                  "notifications/resources/list_changed" (get flt "resourcesListChanged")
                  "notifications/resources/updated"
                  (contains? (set (get flt "resourceSubscriptions"))
                             (get-in notif ["params" "uri"]))
                  false)]
    (when allowed (mcp/with-subscription-id notif id))))
