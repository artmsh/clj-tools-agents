(ns tools.agents.mcp
  "Core of a pure-Clojure implementation of the Model Context Protocol,
   revision 2026-07-28 — the wire types, the JSON codec, the JSON-RPC
   framing, and every constructor for a protocol value. The server lives in
   `tools.agents.mcp.server`, the client in `tools.agents.mcp.client`, and
   the two transports in `tools.agents.mcp.stdio` / `tools.agents.mcp.http`.

   Ergonomically modeled on the official mcp-python-sdk v2 (`MCPServer`
   registration methods matching its decorators, `Client` request methods,
   a typed `ex-info` error hierarchy matching its exception classes) — but
   NOT on its architecture. mcp-python-sdk is built on asyncio, anyio task
   groups and per-connection `ClientSession`/`ServerSession` objects, which
   made sense while MCP had an `initialize` handshake and protocol-level
   sessions. Revision 2026-07-28 removed both (SEP-2575): MCP is now a
   STATELESS protocol in which every request carries its own protocol
   version, client capabilities and identity in `_meta`, and a server 'MUST
   NOT rely on prior requests over the same connection to establish
   context'. So the whole of this implementation above the transport is one
   pure function of two values:

       (tools.agents.mcp.server/handle server request)
         => {:response <JSON-RPC response> :notifications [...]}

   No session objects, no lifespan, no connection state, no async. That is
   not a simplification of the protocol — it is what the protocol now says.
   Its practical consequence for this repo: the entire conformance suite is
   plain data in / plain data out, with no mock server and no subprocess.

   Runs unmodified on JVM Clojure and Babashka — same two-runtime contract
   as tools.agents.anthropic and tools.agents.openai, and (unlike those two)
   with no runtime-specific leaf at all in this namespace: the stdio
   transport needs only `read-line`/`println`/`*err*`, all of which are
   plain clojure.core on both.

   WIRE VALUES ARE STRING-KEYED MAPS, passed through VERBATIM — the same
   contract as this repo's other two libraries, and the same idiom the
   Python SDK uses with literal dict keys. Clojure-facing CONFIGURATION
   (the server map, a tool descriptor, client options) is keyword-keyed.
   The boundary between the two is exactly the boundary between 'this is a
   protocol value' and 'this is your program'.

   JSON: the shared hand-written codec in tools.agents.json — there is no
   JSON library available on both runtimes without adding a dependency.
   `write-json` \\u00XX-escapes EVERY control character below 0x20, which is
   load-bearing here: the stdio transport's framing contract is 'messages
   are delimited by newlines, and MUST NOT contain embedded newlines', and a
   raw \\u0001 in a tool's output would produce a message that is not valid
   JSON at all."
  (:require [clojure.string :as str]
            [tools.agents.json :as json]))

;; ---------------------------------------------------------------------------
;; Protocol constants
;; ---------------------------------------------------------------------------

;; LATEST_PROTOCOL_VERSION from schema/2026-07-28/schema.ts.
(def latest-protocol-version
  "2026-07-28")

;; Protocol versions this implementation speaks. Only the stateless
;; revision: 2025-11-25 and earlier require the `initialize` handshake and
;; protocol-level sessions that SEP-2575 removed, which is a different
;; protocol wearing the same name. A client asking for one of those gets a
;; well-formed UnsupportedProtocolVersionError listing this vector, which is
;; exactly the signal the stdio backward-compatibility probe is defined to
;; act on.
(def supported-protocol-versions
  [latest-protocol-version])

(def jsonrpc-version "2.0")

;; Reserved `_meta` keys (specification/2026-07-28/basic/index#meta).
(def meta-protocol-version "io.modelcontextprotocol/protocolVersion")
(def meta-client-info      "io.modelcontextprotocol/clientInfo")
(def meta-client-caps      "io.modelcontextprotocol/clientCapabilities")
(def meta-log-level        "io.modelcontextprotocol/logLevel")
(def meta-server-info      "io.modelcontextprotocol/serverInfo")
(def meta-subscription-id  "io.modelcontextprotocol/subscriptionId")
(def meta-progress-token   "progressToken")

;; Result types. Extensions MAY add more; the core defines two.
(def result-type-complete "complete")
(def result-type-input-required "input_required")

;; JSON-RPC standard error codes.
(def parse-error -32700)
(def invalid-request -32600)
(def method-not-found -32601)
(def invalid-params -32602)
(def internal-error -32603)

;; MCP-reserved codes. The spec partitions -32000..-32099: -32000..-32019 is
;; grandfathered/implementation-defined and MUST NOT be used by new code,
;; -32020..-32099 belongs to the specification. Note what is NOT here:
;; -32002 (resource not found) and -32042 (URL elicitation required), both of
;; which implementations of this revision MUST NOT emit. Resource-not-found
;; is now plain -32602.
(def header-mismatch -32020)
(def missing-required-client-capability -32021)
(def unsupported-protocol-version -32022)

;; RFC 5424 severities, least to most severe.
(def logging-levels
  ["debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"])

(def ^:private logging-level-rank
  (into {} (map-indexed (fn [i l] [l i]) logging-levels)))

(defn log-level-at-least?
  "True when `level` is at or above `minimum` in RFC 5424 severity order.
   Returns false for an unrecognized level on either side rather than
   throwing — the caller that cares (request validation) rejects an
   unrecognized minimum with -32602 before ever reaching here."
  [level minimum]
  (let [a (logging-level-rank level)
        b (logging-level-rank minimum)]
    (boolean (and a b (>= a b)))))

;; ---------------------------------------------------------------------------
;; JSON codec — tools.agents.json, bound to this namespace's error contract.
;; ---------------------------------------------------------------------------

(def ^:private json-codec
  (json/codec {:prefix      "tools.agents.mcp"
               :encode-type :tools.agents.mcp.error/json-encode
               :parse-type  :tools.agents.mcp.error/json-parse}))

(defn json-key->str
  "Coerce a map key (string/keyword/symbol) to its wire string form, verbatim
   — no case conversion. Throws ex-info
   {:type :tools.agents.mcp.error/json-encode} on anything else."
  [k]
  ((:key->str json-codec) k))

(defn write-json
  "Encode a Clojure value as a JSON string (tools.agents.json/write-json). Map
   keys may be strings or keywords, encoded verbatim; every character below
   0x20 is \\u00XX-escaped. Throws ex-info
   {:type :tools.agents.mcp.error/json-encode} on an unsupported value."
  [v]
  ((:write json-codec) v))

(defn read-json
  "Decode a JSON string into a Clojure value (tools.agents.json/read-json):
   objects become maps with STRING keys, arrays become vectors, numbers stay
   numbers. Throws ex-info {:type :tools.agents.mcp.error/json-parse} on
   malformed input — never returns a partial result."
  [s]
  ((:read json-codec) s))

;; ---------------------------------------------------------------------------
;; JSON-RPC framing
;; ---------------------------------------------------------------------------

(defn prune
  "Drop keys whose value is nil. Optional protocol fields are omitted, never
   sent as JSON null — `\"title\": null` is not the same as an absent title
   to a validator, and several MCP fields are typed `string | undefined`."
  [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

(defn request
  "A JSON-RPC request object. `params` is used verbatim; callers that need
   the required per-request `_meta` build it with `request-meta` (or let
   tools.agents.mcp.client stamp it)."
  ([id method] (request id method nil))
  ([id method params]
   (prune {"jsonrpc" jsonrpc-version "id" id "method" method "params" params})))

(defn notification
  "A JSON-RPC notification object. Notifications MUST NOT include an id."
  ([method] (notification method nil))
  ([method params]
   (prune {"jsonrpc" jsonrpc-version "method" method "params" params})))

(defn result
  "The ONE constructor for an MCP result object.

   Revision 2026-07-28 makes `resultType` a REQUIRED field on every result
   (SEP-2322) — not just on the multi-round-trip ones. Routing every result
   in this implementation through this function, and every response through
   `result-response` below, is what makes that structural rather than a rule
   each handler has to remember."
  ([m] (result result-type-complete m))
  ([result-type m] (assoc (or m {}) "resultType" result-type)))

(defn result-response
  "A JSON-RPC result response. Stamps `resultType` if the caller has not
   already (e.g. via `input-required`), so no result can leave this
   implementation without one."
  [id r]
  {"jsonrpc" jsonrpc-version
   "id" id
   "result" (if (contains? r "resultType") r (result r))})

(defn error-response
  "A JSON-RPC error response. `id` may be nil only when the request's id
   could not be read (a parse error / malformed request)."
  ([id code message] (error-response id code message nil))
  ([id code message data]
   (prune {"jsonrpc" jsonrpc-version
           "id" id
           "error" (prune {"code" code "message" message "data" data})})))

(defn request? [m] (and (map? m) (contains? m "method") (contains? m "id")))
(defn notification? [m] (and (map? m) (contains? m "method") (not (contains? m "id"))))
(defn error-response? [m] (and (map? m) (contains? m "error")))

;; ---------------------------------------------------------------------------
;; Typed errors
;;
;; Two representations on purpose, and they are not interchangeable:
;;   - a JSON-RPC error RESPONSE map, which is what goes on the wire; and
;;   - an `ex-info` with a :type, which is how a Clojure handler signals a
;;     failure to the dispatcher, matching this repo's error idiom and
;;     mcp-python-sdk's own McpError.
;; `error-ex->response` is the single bridge between them.
;; ---------------------------------------------------------------------------

(defn error!
  "Throw a protocol error from inside a handler. The dispatcher turns it into
   the corresponding JSON-RPC error response."
  ([code message] (error! code message nil))
  ([code message data]
   (throw (ex-info message {:type :tools.agents.mcp.error/protocol
                            :code code
                            :data data}))))

(defn invalid-params!
  "-32602. Also the code for a resource that does not exist: revision
   2026-07-28 retired -32002 for that case."
  ([message] (invalid-params! message nil))
  ([message data] (error! invalid-params message data)))

(defn not-found!
  "A resource/prompt/tool that does not exist. -32602 by name, so callers do
   not have to remember that -32002 is gone."
  [message]
  (invalid-params! message))

(defn internal-error! [message]
  (error! internal-error message))

(defn unsupported-protocol-version-response
  "UnsupportedProtocolVersionError (-32022). `data` MUST carry both the
   versions this server supports and the one that was requested."
  [id requested]
  (error-response id unsupported-protocol-version
                  (str "Unsupported protocol version: " (pr-str requested))
                  {"supported" (vec supported-protocol-versions)
                   "requested" requested}))

(defn missing-client-capability!
  "MissingRequiredClientCapabilityError (-32021). `required` is a
   ClientCapabilities-shaped map naming what the request would have needed,
   e.g. {\"elicitation\" {\"form\" {}}}.

   A server MUST NOT rely on a capability the client did not declare, so
   this fires BEFORE any attempt to use it — never after a failed round
   trip."
  [required]
  (error! missing-required-client-capability
          (str "Client is missing required capabilities: "
               (str/join ", " (sort (map json-key->str (keys required)))))
          {"requiredCapabilities" required}))

(defn header-mismatch!
  "HeaderMismatch (-32020). Streamable HTTP only: the mirrored HTTP headers
   disagree with the JSON-RPC body."
  [message]
  (error! header-mismatch message))

(defn error-ex->response
  "Turn an exception thrown by a handler into a JSON-RPC error response. A
   `error!`-thrown protocol error keeps its code and data; anything else is
   -32603, with the message but never the stack trace."
  [id e]
  (let [d (ex-data e)]
    (if (= :tools.agents.mcp.error/protocol (:type d))
      (error-response id (:code d) (or (ex-message e) "error") (:data d))
      (error-response id internal-error
                      (str "Internal error: " (or (ex-message e) (str e)))))))

;; ---------------------------------------------------------------------------
;; `_meta`
;; ---------------------------------------------------------------------------

(defn request-meta
  "Build the `_meta` object every client request MUST carry.
   `protocol-version` and `client-capabilities` are required by the spec;
   `client-info`, `log-level` and `progress-token` are optional."
  [{:keys [protocol-version client-capabilities client-info log-level progress-token extra]}]
  (merge (prune {meta-protocol-version (or protocol-version latest-protocol-version)
                 meta-client-caps      (or client-capabilities {})
                 meta-client-info      client-info
                 meta-log-level        log-level
                 meta-progress-token   progress-token})
         (or extra {})))

(defn params-meta
  "The `_meta` map of a request's params, or {}."
  [request]
  (or (get-in request ["params" "_meta"]) {}))

(defn client-capabilities
  "ClientCapabilities carried by this request. Never inferred from a previous
   request — under a stateless protocol there is no such thing."
  [request]
  (get (params-meta request) meta-client-caps))

(defn client-info [request] (get (params-meta request) meta-client-info))
(defn protocol-version [request] (get (params-meta request) meta-protocol-version))
(defn log-level [request] (get (params-meta request) meta-log-level))
(defn progress-token [request] (get (params-meta request) meta-progress-token))

(defn client-supports?
  "True when `caps` declares the capability at `path`, e.g.
   [\"elicitation\"] or [\"elicitation\" \"url\"]. Presence is what counts —
   an empty map means 'supported, no settings'."
  [caps path]
  (let [v (get-in caps path ::absent)]
    (not= v ::absent)))

;; ---------------------------------------------------------------------------
;; Notifications the server emits
;; ---------------------------------------------------------------------------

(defn progress-notification
  [progress-token progress {:keys [total message]}]
  (notification "notifications/progress"
                (prune {"progressToken" progress-token
                        "progress" progress
                        "total" total
                        "message" message})))

(defn log-notification
  [level {:keys [logger data]}]
  (notification "notifications/message"
                (prune {"level" level "logger" logger "data" data})))

(defn resource-updated-notification [uri]
  (notification "notifications/resources/updated" {"uri" uri}))

(def tools-list-changed-notification
  (notification "notifications/tools/list_changed"))

(def prompts-list-changed-notification
  (notification "notifications/prompts/list_changed"))

(def resources-list-changed-notification
  (notification "notifications/resources/list_changed"))

(defn cancelled-notification
  ([request-id] (cancelled-notification request-id nil))
  ([request-id reason]
   (notification "notifications/cancelled"
                 (prune {"requestId" request-id "reason" reason}))))

(defn with-subscription-id
  "Tag a message with the subscription it belongs to. On stdio every
   subscription shares one channel, so the spec makes this the client's only
   way to demultiplex — it MUST be present on every message delivered for a
   `subscriptions/listen` stream, the acknowledgment and the graceful-closure
   response included. A response carries it in `result._meta`, a
   notification in `params._meta`."
  [msg subscription-id]
  (if (contains? msg "result")
    (assoc-in msg ["result" "_meta" meta-subscription-id] subscription-id)
    (assoc-in msg ["params" "_meta" meta-subscription-id] subscription-id)))

;; ---------------------------------------------------------------------------
;; Content blocks
;; ---------------------------------------------------------------------------

(defn text
  ([s] (text s nil))
  ([s annotations] (prune {"type" "text" "text" s "annotations" annotations})))

(defn image
  ([base64-data mime-type] (image base64-data mime-type nil))
  ([base64-data mime-type annotations]
   (prune {"type" "image" "data" base64-data "mimeType" mime-type "annotations" annotations})))

(defn audio
  ([base64-data mime-type] (audio base64-data mime-type nil))
  ([base64-data mime-type annotations]
   (prune {"type" "audio" "data" base64-data "mimeType" mime-type "annotations" annotations})))

(defn resource-link
  "A `resource_link` content block — a pointer to a resource the client can
   read later, as opposed to `embedded-resource`, which inlines it now."
  [{:keys [uri name title description mime-type annotations size]}]
  (prune {"type" "resource_link" "uri" uri "name" name "title" title
          "description" description "mimeType" mime-type
          "annotations" annotations "size" size}))

(defn embedded-resource
  ([contents] (embedded-resource contents nil))
  ([contents annotations]
   (prune {"type" "resource" "resource" contents "annotations" annotations})))

(defn text-contents
  ([uri s] (text-contents uri s nil))
  ([uri s mime-type] (prune {"uri" uri "text" s "mimeType" mime-type})))

(defn blob-contents
  ([uri base64-data] (blob-contents uri base64-data nil))
  ([uri base64-data mime-type] (prune {"uri" uri "blob" base64-data "mimeType" mime-type})))

(defn annotations
  [{:keys [audience priority last-modified]}]
  (prune {"audience" audience "priority" priority "lastModified" last-modified}))

;; ---------------------------------------------------------------------------
;; Prompt messages / sampling messages
;; ---------------------------------------------------------------------------

(defn prompt-message [role content]
  {"role" role "content" content})

(defn user-message [content] (prompt-message "user" content))
(defn assistant-message [content] (prompt-message "assistant" content))

;; ---------------------------------------------------------------------------
;; Tool results
;; ---------------------------------------------------------------------------

(defn tool-result
  "A CallToolResult. `content` is a vector of content blocks; a bare string
   or a single block is accepted and wrapped, matching the Python SDK's
   handling of a plain `str` return."
  ([content] (tool-result content nil))
  ([content {:keys [structured-content is-error]}]
   (result
    (prune {"content" (cond
                        (string? content) [(text content)]
                        (map? content) [content]
                        :else (vec content))
            "structuredContent" structured-content
            "isError" is-error}))))

(defn tool-error
  "A CallToolResult with isError true. This is how a TOOL reports failure —
   an error the model should see and may recover from. A PROTOCOL failure
   (unknown tool, bad arguments) is a JSON-RPC error instead; `error!` and
   friends above raise those."
  [message]
  (tool-result [(text message)] {:is-error true}))

(defn output-text
  "Concatenate the text of every `text` block in a CallToolResult (or in any
   map with a \"content\" vector), joined by newlines. Mirrors
   tools.agents.anthropic/output-text — the same convenience at the other
   end of the same conversation."
  [call-tool-result]
  (->> (get call-tool-result "content")
       (filter #(= "text" (get % "type")))
       (map #(get % "text"))
       (str/join "\n")))

;; ---------------------------------------------------------------------------
;; Multi Round-Trip Requests (MRTR)
;;
;; Revision 2026-07-28's replacement for server-initiated requests, and a
;; breaking change: a server MUST NOT send `sampling/createMessage`,
;; `elicitation/create` or `roots/list` as its own JSON-RPC request any more.
;; It returns them inside an InputRequiredResult, the client fulfils them,
;; and the client RETRIES the original request carrying `inputResponses` and
;; the opaque `requestState` echoed back verbatim.
;; ---------------------------------------------------------------------------

(defn elicit-form
  "An `elicitation/create` input request in form mode. `requested-schema` is
   a restricted JSON Schema object of primitive properties."
  [message requested-schema]
  {"method" "elicitation/create"
   "params" {"mode" "form" "message" message "requestedSchema" requested-schema}})

(defn elicit-url
  "An `elicitation/create` input request in URL mode — hand the user off to
   an out-of-band flow. Note there is no `elicitationId` and no
   `notifications/elicitation/complete` in this revision: both were removed,
   because the client learns the outcome by retrying the original request. A
   server that needs to correlate the interaction across retries encodes its
   own identifier in `requestState`."
  [message url]
  {"method" "elicitation/create"
   "params" {"mode" "url" "message" message "url" url}})

(defn create-message
  "A `sampling/createMessage` input request. Deprecated as a FEATURE in this
   revision (SEP-2577) but fully functional for at least twelve months."
  [{:keys [messages max-tokens system-prompt model-preferences temperature
           stop-sequences include-context metadata tools tool-choice]}]
  {"method" "sampling/createMessage"
   "params" (prune {"messages" messages
                    "maxTokens" max-tokens
                    "systemPrompt" system-prompt
                    "modelPreferences" model-preferences
                    "temperature" temperature
                    "stopSequences" stop-sequences
                    "includeContext" include-context
                    "metadata" metadata
                    "tools" tools
                    "toolChoice" tool-choice})})

(defn list-roots
  "A `roots/list` input request. Deprecated as a feature in this revision
   (SEP-2577); the suggested migration is to pass directories as ordinary
   tool arguments."
  []
  {"method" "roots/list"})

(defn input-required
  "An InputRequiredResult — `resultType: \"input_required\"`. At least one of
   `:input-requests` (a map of server-assigned key -> input request) or
   `:request-state` (an opaque string, echoed back by the client on retry)
   MUST be present; this throws rather than emit a result the spec forbids.

   `:request-state` is ATTACKER-CONTROLLED on the way back. If it influences
   authorization or business logic the server MUST integrity-protect it
   (HMAC/AEAD) and reject what fails verification; see
   `tools.agents.mcp.server/handle`'s docstring."
  [{:keys [input-requests request-state]}]
  (when (and (empty? input-requests) (nil? request-state))
    (throw (ex-info "tools.agents.mcp/input-required: an InputRequiredResult MUST include at least one of :input-requests or :request-state"
                    {:type :tools.agents.mcp.error/invalid-input-required})))
  (result result-type-input-required
          (prune {"inputRequests" (when (seq input-requests) input-requests)
                  "requestState" request-state})))

(defn input-required?
  [r] (= result-type-input-required (get r "resultType")))

(defn input-responses
  "The `inputResponses` the client echoed back on a retry, keyed exactly as
   the server keyed its `inputRequests`."
  [request]
  (get-in request ["params" "inputResponses"] {}))

(defn request-state
  [request]
  (get-in request ["params" "requestState"]))

;; Which client capability each input-request method requires. A server MUST
;; NOT send an input request the client has not declared support for.
(def input-request-capability
  {"elicitation/create"     {"elicitation" {}}
   "sampling/createMessage" {"sampling" {}}
   "roots/list"             {"roots" {}}})

(defn input-request-required-capability
  "The ClientCapabilities-shaped map an input request needs the client to
   have declared. URL-mode elicitation is the one case that needs more than
   the method name: it requires `elicitation.url` specifically, not merely
   `elicitation`."
  [req]
  (let [m (get req "method")]
    (if (and (= m "elicitation/create")
             (= "url" (get-in req ["params" "mode"])))
      {"elicitation" {"url" {}}}
      (get input-request-capability m))))

(defn- capability-paths
  "Every leaf path through a ClientCapabilities-shaped map, e.g.
   {\"elicitation\" {\"url\" {}}} -> ([\"elicitation\" \"url\"])."
  [m]
  (mapcat (fn [[k v]]
            (if (and (map? v) (seq v))
              (map (fn [p] (cons k p)) (capability-paths v))
              [[k]]))
          m))

(defn missing-capabilities
  "The subset of `required` that `caps` does not declare, or nil when
   everything required is present."
  [caps required]
  (let [missing (remove (fn [path] (client-supports? caps (vec path)))
                        (capability-paths required))]
    (when (seq missing)
      (reduce (fn [m path] (assoc-in m (vec path) {})) {} missing))))

(defn require-capabilities!
  "Assert that `caps` declares everything in `required`, or raise
   MissingRequiredClientCapability (-32021). Call this at the TOP of a
   handler that will need a capability — the spec requires the server to
   refuse up front rather than discover the gap mid-flight."
  [caps required]
  (when-let [missing (missing-capabilities caps required)]
    (missing-client-capability! missing))
  true)
