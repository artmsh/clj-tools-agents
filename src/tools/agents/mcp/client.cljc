(ns tools.agents.mcp.client
  "An MCP client for revision 2026-07-28, ergonomically modeled on
   mcp-python-sdk v2's `Client` — the same request methods under the same
   names, and a typed `ex-info` hierarchy in place of its exception classes.

   The client is a plain map holding identity, declared capabilities and one
   `:send!` function — the whole of its transport dependency. `:send!` takes
   a JSON-RPC request map and returns the JSON-RPC response map;
   tools.agents.mcp.stdio and tools.agents.mcp.http each supply one. Every
   other function here is pure, which is why the tests drive a real server
   through a two-line `:send!` that calls `server/handle` directly, with no
   process and no socket anywhere.

   WHAT THIS CLIENT DOES THAT A PRE-2026-07-28 ONE DID NOT:

     - It stamps `io.modelcontextprotocol/protocolVersion` and
       `io.modelcontextprotocol/clientCapabilities` into the `_meta` of
       EVERY request. They are required on every request now; there is no
       `initialize` to negotiate them once.
     - It never receives a server-initiated JSON-RPC request. Sampling,
       elicitation and roots arrive as `inputRequests` inside an
       InputRequiredResult, and `call-tool!` drives the retry loop
       (`with-mrtr` below).
     - It treats a result with NO `resultType` as `\"complete\"`, which the
       spec requires for backward compatibility with earlier servers.
     - It has no session id, and holds no state that a reconnect would lose."
  (:require [tools.agents.mcp :as mcp]))

;; How many times `call-tool!` will fulfil an InputRequiredResult and retry
;; before giving up. A server MAY legitimately ask more than once — the spec
;; explicitly allows repeatedly prompting until it has what it needs — so the
;; bound exists to stop a loop, not because a second round is suspicious.
(def default-max-rounds
  5)

(defn client
  "Build a client. `:send!` is the only piece of I/O and comes from a
   transport namespace.

     :name / :version / :title / :icons / :website-url  this client's identity,
         sent as io.modelcontextprotocol/clientInfo on every request. The spec
         SHOULDs it; pass :advertise-client-info? false to suppress it.
     :capabilities  ClientCapabilities, sent on every request. Declaring
         nothing is valid and means the server MUST NOT ask for sampling,
         elicitation or roots.
     :protocol-version  defaults to 2026-07-28.
     :send!  (fn [request-map] response-map)
     :input-handlers  {\"elicitation/create\" (fn [req] result) ...} — how this
         client fulfils MRTR input requests. A handler is only ever called
         for a method whose capability :capabilities declares.
     :max-rounds  MRTR retry bound, default 5."
  [{:keys [name version title icons website-url capabilities protocol-version
           send! input-handlers max-rounds advertise-client-info?]
    :or {version "0.0.0" advertise-client-info? true max-rounds default-max-rounds}}]
  {:info (when advertise-client-info?
           (into {} (remove (fn [[_ v]] (nil? v))
                            {"name" name "version" version "title" title
                             "icons" icons "websiteUrl" website-url})))
   :capabilities (or capabilities {})
   :protocol-version (or protocol-version mcp/latest-protocol-version)
   :send! send!
   :input-handlers (or input-handlers {})
   :max-rounds max-rounds
   :next-id (atom 0)})

(defn next-request-id
  "A fresh JSON-RPC id. MRTR requires the retry of a request to use a
   DIFFERENT id from the original — they are independent requests, not a
   continuation — so ids are minted per send, never per logical operation."
  [c]
  (swap! (:next-id c) inc))

(defn request
  "Build a JSON-RPC request with this client's per-request `_meta` stamped
   in. `params` is merged verbatim on top, so a caller can add
   `inputResponses`/`requestState`/`cursor` without going through a
   dedicated arg."
  ([c method] (request c method nil))
  ([c method params] (request c method params nil))
  ([c method params {:keys [log-level progress-token id]}]
   (mcp/request (or id (next-request-id c))
                method
                (assoc (or params {})
                       "_meta" (mcp/request-meta
                                {:protocol-version (:protocol-version c)
                                 :client-capabilities (:capabilities c)
                                 :client-info (:info c)
                                 :log-level log-level
                                 :progress-token progress-token})))))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(def ^:private code->type
  {mcp/parse-error :tools.agents.mcp.error/parse
   mcp/invalid-request :tools.agents.mcp.error/invalid-request
   mcp/method-not-found :tools.agents.mcp.error/method-not-found
   mcp/invalid-params :tools.agents.mcp.error/invalid-params
   mcp/internal-error :tools.agents.mcp.error/internal
   mcp/header-mismatch :tools.agents.mcp.error/header-mismatch
   mcp/missing-required-client-capability :tools.agents.mcp.error/missing-client-capability
   mcp/unsupported-protocol-version :tools.agents.mcp.error/unsupported-protocol-version})

(defn error-response->ex
  "Turn a JSON-RPC error response into a typed ex-info, mirroring
   mcp-python-sdk's McpError and its subclasses."
  [response]
  (let [err (get response "error")
        code (get err "code")]
    (ex-info (str "tools.agents.mcp.client: " (get err "message"))
             {:type (get code->type code :tools.agents.mcp.error/api)
              :code code
              :data (get err "data")
              :response response})))

(defn result-of
  "The result of a response, or throw. Also the one place the backward
   compatibility rule lives: a result from an earlier-protocol server that
   omits `resultType` MUST be treated as \"complete\"."
  [response]
  (when-not (map? response)
    (throw (ex-info "tools.agents.mcp.client: transport returned a non-map response"
                    {:type :tools.agents.mcp.error/protocol-violation :response response})))
  (when (mcp/error-response? response)
    (throw (error-response->ex response)))
  (let [r (get response "result")]
    (when-not (map? r)
      (throw (ex-info "tools.agents.mcp.client: response has neither \"result\" nor \"error\""
                      {:type :tools.agents.mcp.error/protocol-violation :response response})))
    (if (contains? r "resultType") r (assoc r "resultType" mcp/result-type-complete))))

(defn send!
  "Send one request through the transport and return its result map."
  [c req]
  (result-of ((:send! c) req)))

(defn call!
  "Build, send, and unwrap one request."
  ([c method] (call! c method nil nil))
  ([c method params] (call! c method params nil))
  ([c method params opts] (send! c (request c method params opts))))

;; ---------------------------------------------------------------------------
;; Multi Round-Trip Requests
;; ---------------------------------------------------------------------------

(defn fulfil-input-requests
  "Run this client's `:input-handlers` over an InputRequiredResult's
   `inputRequests`, returning the `inputResponses` map keyed identically.

   Refuses to answer an input request whose capability this client did not
   declare: a conforming server MUST NOT have asked, so answering anyway
   would paper over the server's bug and hand it data it was not entitled
   to ask for."
  [c result]
  (reduce-kv
   (fn [acc k req]
     (let [method (get req "method")
           required (mcp/input-request-required-capability req)]
       (when (and required (mcp/missing-capabilities (:capabilities c) required))
         (throw (ex-info (str "tools.agents.mcp.client: server sent an input request this client did not declare support for: " method)
                         {:type :tools.agents.mcp.error/undeclared-input-request
                          :method method :required required})))
       (if-let [h (get (:input-handlers c) method)]
         (assoc acc k (h req))
         (throw (ex-info (str "tools.agents.mcp.client: no :input-handlers entry for " method)
                         {:type :tools.agents.mcp.error/no-input-handler :method method})))))
   {}
   (get result "inputRequests" {})))

(defn with-mrtr
  "Drive the multi-round-trip loop for one logical request.

   Sends `params` for `method`; while the server answers `input_required`,
   fulfils the `inputRequests`, echoes `requestState` back VERBATIM (the
   client MUST NOT inspect, parse or modify it), and retries with a NEW
   JSON-RPC id. Returns the first result that is not `input_required`.

   Only `tools/call`, `resources/read` and `prompts/get` may receive an
   InputRequiredResult; a server sending one on any other method is in
   violation and this raises rather than looping."
  ([c method params] (with-mrtr c method params nil))
  ([c method params opts]
   (when-not (contains? #{"tools/call" "resources/read" "prompts/get"} method)
     (throw (ex-info (str "tools.agents.mcp.client/with-mrtr: " method " does not support multi round-trip requests")
                     {:type :tools.agents.mcp.error/mrtr-unsupported-method :method method})))
   (loop [params params round 0]
     (let [res (call! c method params opts)]
       (if-not (mcp/input-required? res)
         res
         (if (>= round (:max-rounds c))
           (throw (ex-info (str "tools.agents.mcp.client: server still requesting input after "
                                (:max-rounds c) " rounds of " method)
                           {:type :tools.agents.mcp.error/mrtr-exhausted
                            :method method :result res}))
           (recur (into (dissoc params "inputResponses" "requestState")
                        (into {} (remove (fn [[_ v]] (nil? v))
                                         {"inputResponses" (let [r (fulfil-input-requests c res)]
                                                             (when (seq r) r))
                                          ;; Echoed back exactly, and only when
                                          ;; the server sent one.
                                          "requestState" (get res "requestState")})))
                  (inc round))))))))

;; ---------------------------------------------------------------------------
;; Requests
;; ---------------------------------------------------------------------------

(defn discover!
  "server/discover. Servers MUST implement it, so this doubles as the stdio
   backward-compatibility probe — see `probe!`."
  [c] (call! c "server/discover" {}))

(defn list-tools!
  ([c] (list-tools! c nil))
  ([c cursor] (call! c "tools/list" (when cursor {"cursor" cursor}))))

(defn list-prompts!
  ([c] (list-prompts! c nil))
  ([c cursor] (call! c "prompts/list" (when cursor {"cursor" cursor}))))

(defn list-resources!
  ([c] (list-resources! c nil))
  ([c cursor] (call! c "resources/list" (when cursor {"cursor" cursor}))))

(defn list-resource-templates!
  ([c] (list-resource-templates! c nil))
  ([c cursor] (call! c "resources/templates/list" (when cursor {"cursor" cursor}))))

(defn list-all!
  "Follow `nextCursor` to the end and return every item under `k`. The list
   results also carry `ttlMs`/`cacheScope`; a client that wants to honour
   them should use the single-page calls and read those fields."
  [c method k]
  (loop [cursor nil acc []]
    (let [res (call! c method (when cursor {"cursor" cursor}))
          acc (into acc (get res k []))
          next-cursor (get res "nextCursor")]
      (if next-cursor (recur next-cursor acc) acc))))

(defn list-all-tools! [c] (list-all! c "tools/list" "tools"))
(defn list-all-prompts! [c] (list-all! c "prompts/list" "prompts"))
(defn list-all-resources! [c] (list-all! c "resources/list" "resources"))
(defn list-all-resource-templates! [c] (list-all! c "resources/templates/list" "resourceTemplates"))

(defn call-tool!
  "tools/call, driving the MRTR loop. Returns a CallToolResult; a tool that
   failed reports it as `isError` inside that result, while a protocol
   failure (unknown tool, bad arguments) throws."
  ([c tool-name] (call-tool! c tool-name nil nil))
  ([c tool-name arguments] (call-tool! c tool-name arguments nil))
  ([c tool-name arguments opts]
   (with-mrtr c "tools/call"
     (into {"name" tool-name} (when (seq arguments) {"arguments" arguments}))
     opts)))

(defn read-resource!
  ([c uri] (read-resource! c uri nil))
  ([c uri opts] (with-mrtr c "resources/read" {"uri" uri} opts)))

(defn get-prompt!
  ([c prompt-name] (get-prompt! c prompt-name nil nil))
  ([c prompt-name arguments] (get-prompt! c prompt-name arguments nil))
  ([c prompt-name arguments opts]
   (with-mrtr c "prompts/get"
     (into {"name" prompt-name} (when (seq arguments) {"arguments" arguments}))
     opts)))

(defn complete!
  "completion/complete — argument autocompletion for a prompt argument or a
   resource template variable."
  ([c ref argument] (complete! c ref argument nil))
  ([c ref argument context]
   (call! c "completion/complete"
          (into {"ref" ref "argument" argument}
                (when context {"context" context})))))

(defn prompt-ref [prompt-name] {"type" "ref/prompt" "name" prompt-name})
(defn resource-ref [uri-template] {"type" "ref/resource" "uri" uri-template})

(defn listen!
  "subscriptions/listen — builds the request for a long-lived notification
   stream. It only BUILDS it: the stream itself is the transport's business.

   Over `tools.agents.mcp.stdio/connect!`, sending it with `(:send! c)`
   blocks for the life of the subscription — every notification on the
   stream goes to that transport's `:on-notification`, and the call returns
   only when the server closes the stream gracefully. So a caller who also
   wants to issue ordinary requests dedicates a thread (or a second
   connection) to the subscription.

   `notifications` is the SubscriptionFilter: any of \"toolsListChanged\",
   \"promptsListChanged\", \"resourcesListChanged\",
   \"resourceSubscriptions\"."
  [c notifications]
  (request c "subscriptions/listen" {"notifications" notifications}))

(defn cancel!
  "notifications/cancelled — a client-to-server notification, so there is no
   response and nothing to unwrap. On stdio this is also how a
   `subscriptions/listen` stream is closed."
  [c request-id reason]
  ((:send! c) (mcp/cancelled-notification request-id reason))
  nil)

;; ---------------------------------------------------------------------------
;; Backward-compatibility probe
;; ---------------------------------------------------------------------------

(defn probe!
  "The stdio backward-compatibility probe. Sends `server/discover` and
   classifies the outcome exactly as the spec defines it:

     {:era :modern  :result r}          a DiscoverResult came back
     {:era :modern  :unsupported-version {:supported [...] :requested v}}
                                        -32022 — modern server, wrong version;
                                        pick from :supported and do NOT fall
                                        back to `initialize`
     {:era :legacy  :error e}           any other error, or no answer — an
                                        `initialize`-era server

   The fallback is deliberately NOT keyed to one error code: legacy servers
   answer an unknown pre-`initialize` request with whatever they like
   (commonly -32601 or -32602) or with nothing at all."
  [c]
  (try
    {:era :modern :result (discover! c)}
    (catch Exception e
      (let [d (ex-data e)]
        (if (= mcp/unsupported-protocol-version (:code d))
          {:era :modern
           :unsupported-version {:supported (get (:data d) "supported")
                                 :requested (get (:data d) "requested")}}
          {:era :legacy :error e})))))
