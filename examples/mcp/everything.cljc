(ns examples.mcp.everything
  "Clojure port of the reference 'everything' MCP server
   (https://github.com/modelcontextprotocol/servers/tree/main/src/everything),
   rebuilt on tools.agents.mcp for protocol revision 2026-07-28.

   The original is a TypeScript server whose purpose is to exercise every
   feature of the protocol so that CLIENT authors have something to test
   against. It targets the pre-2026-07-28 protocol, and several of its
   features exist only because that protocol had things this one removed:
   per-connection sessions, `resources/subscribe`, server-initiated
   `elicitation/create` requests, `logging/setLevel`, core tasks, and error
   code -32042. Porting it is therefore not a translation exercise — it is a
   migration, and the interesting part of this file is where it diverges.

   docs/mcp.md has the full row-by-row table. The short version:

     - Tools that map straight across: echo, get-annotated-message, get-env,
       get-resource-links, get-resource-reference, get-structured-content,
       get-sum, get-tiny-image, trigger-long-running-operation.
     - Server-to-client requests (elicitation, URL elicitation, sampling,
       roots) become InputRequiredResult replies driven by the client's
       retry, not requests the server sends. Same three tools, one round trip
       turned into two.
     - The original registers its elicitation tools ONLY IF the connected
       client declared the capability, reading it off the session. Under
       SEP-2567 list endpoints no longer vary per connection, so here they
       are always listed and refuse at CALL time with -32021 naming exactly
       what was missing. This is strictly better for the client author: the
       tool is discoverable, and the failure says why.
     - toggle-simulated-logging and toggle-subscriber-updates were per-session
       toggles. Log level is now per-request, so 'emit-simulated-logs' just
       logs for the request that asked; resource updates are pushed to
       `subscriptions/listen` streams, so 'touch-resource' bumps a resource
       and the transport fans the notification out.
     - Session-scoped resources become handle-scoped: 'register-resource'
       mints an explicit id, returns it, and the client passes it back as an
       ordinary argument — the pattern SEP-2567 prescribes in place of
       connection state.
     - The three MCP Tasks tools — simulate-research-query,
       trigger-sampling-request-async and trigger-elicitation-request-async —
       are NOT ported. Tasks left the core protocol in this revision and
       became the `io.modelcontextprotocol/tasks` extension; `server/server`'s
       `:methods` option is the seam an extension would be built on.
     - gzip-file-as-resource is not ported: it fetches an arbitrary URL and
       gzips it, neither of which this dependency-free library provides.
     - The static documents keep the reference server's URIs and filenames but
       are held in source rather than read off disk, and a hundred numbered
       resources are ADDED to the list so that `resources/list` pagination is
       reachable — the reference server lists nothing pageable.

   Run it: `clojure -M -m examples.mcp.everything` (or under bb)
   speaks stdio. `--http` serves Streamable HTTP on JVM Clojure."
  (:require [clojure.string :as str]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.http :as http]
            [tools.agents.mcp.server :as server]
            [tools.agents.mcp.stdio :as stdio]))

;; The MCP logo as a tiny PNG, verbatim from the reference server's
;; get-tiny-image.ts.
(def mcp-tiny-image
  "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")

;; ---------------------------------------------------------------------------
;; Dynamic resources — demo://resource/dynamic/{text,blob}/{resourceId}
;; ---------------------------------------------------------------------------

(def uri-base "demo://resource/dynamic")
(def text-uri-template (str uri-base "/text/{resourceId}"))
(def blob-uri-template (str uri-base "/blob/{resourceId}"))

(defn text-resource-uri [resource-id] (str uri-base "/text/" resource-id))
(defn blob-resource-uri [resource-id] (str uri-base "/blob/" resource-id))

(defn parse-resource-id!
  "The reference server accepts any finite positive integer and throws
   otherwise. A bad id is a bad PARAMETER, so this is -32602 — which is also,
   as of this revision, the code for a resource that does not exist at all."
  [uri id-str]
  (let [n (try (mcp/read-json (str id-str)) (catch Exception _ nil))]
    (if (and (integer? n) (pos? n))
      n
      (mcp/invalid-params! (str "Unknown resource: " uri)))))

(defn text-resource
  "Port of `textResource`. The original stamps a wall-clock timestamp; this
   takes it as an argument so the server stays a pure function of its inputs
   and the conformance tests can assert on exact bytes."
  [uri resource-id timestamp]
  (mcp/text-contents uri
                     (str "Resource " resource-id ": This is a plaintext resource created at " timestamp)
                     "text/plain"))

(defn blob-resource
  "Port of `blobResource`."
  [uri resource-id timestamp]
  (mcp/blob-contents uri
                     (http/base64-encode
                      (str "Resource " resource-id ": This is a base64 blob created at " timestamp))
                     "application/octet-stream"))

;; ---------------------------------------------------------------------------
;; Server-minted handles, in place of session state
;;
;; The reference server keeps `demo://resource/session/<name>` resources for
;; the lifetime of a connection. SEP-2567 removed protocol-level sessions
;; precisely so that a server can be replicated behind a load balancer, and
;; says state spanning requests "MUST be referenced by an explicit identifier
;; the client passes on each request". So the store is keyed by a handle the
;; server mints and hands back, and the client passes that handle as an
;; ordinary tool argument. Nothing about it is tied to a connection.
;; ---------------------------------------------------------------------------

(def registered-uri-template "demo://resource/registered/{handle}")

(defn registered-uri [handle] (str "demo://resource/registered/" handle))

(defn new-store
  "The server's own state. Deliberately passed in rather than defined as a
   top-level atom: two `everything-server` instances in one process (as the
   test suite builds) must not share it."
  []
  (atom {:next-handle 0 :resources {} :versions {}}))

;; ---------------------------------------------------------------------------
;; Static documents — demo://resource/static/document/<name>
;; ---------------------------------------------------------------------------

(def instructions-text
  (str "This server exists to exercise a client. Every core capability of "
       "protocol revision 2026-07-28 is reachable from it: tools with output "
       "schemas and annotations, static and templated resources, prompts with "
       "completions, per-request progress and logging, subscriptions/listen "
       "fan-out, and the multi round-trip request pattern (elicitation, URL "
       "elicitation, sampling, roots). Tools that need a client capability are "
       "listed unconditionally and fail with -32021 naming what was missing."))

(def static-documents
  ;; The reference server serves the seven markdown files in
  ;; src/everything/docs/ from disk. The names are kept; the prose is this
  ;; port's own, because it describes this port. Held in source rather than
  ;; read from disk: this library wraps no filesystem, and a server that
  ;; needs its own working directory to answer resources/read is a worse
  ;; demonstration than one that does not.
  {"architecture" (str "One pure function, tools.agents.mcp.server/handle, sits under both "
                       "transports. The stdio transport adds framing; the Streamable HTTP "
                       "transport adds header mirroring and SSE. Neither holds protocol state, "
                       "because after SEP-2567 there is none to hold.")
   "extension" (str "Methods outside the core protocol — io.modelcontextprotocol/tasks and "
                    "anything like it — are registered through server/server's :methods "
                    "option, which takes a map of method name to (fn [ctx request] result).")
   "features" (str "This server exercises tools, resources, resource templates, prompts, "
                   "completions, progress, logging, subscriptions/listen fan-out and the "
                   "multi round-trip request pattern.")
   "how-it-works" (str "Every request carries its own protocol version, client capabilities "
                       "and client info in _meta. The server answers from that alone: there "
                       "is no handshake to have missed and no session to look up.")
   "instructions" instructions-text
   "startup" (str "`-main` builds the server once and hands it to a transport. stdio by "
                  "default; --http serves Streamable HTTP on JVM Clojure.")
   "structure" (str "Tool, prompt and resource constructors are plain functions returning "
                    "plain maps, assembled by everything-server. Every impure input — the "
                    "clock, the sleep, the notification sink, the handle store — is passed "
                    "in, so the whole server is a pure function under test.")})

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(def read-only-annotations
  {"readOnlyHint" true "destructiveHint" false
   "idempotentHint" true "openWorldHint" false})

(defn- getenv-map
  "Every environment variable, as the reference server's get-env returns."
  []
  (into {} (System/getenv)))

(defn echo-tool []
  {:name "echo"
   :title "Echo Tool"
   :description "Echoes back the input string"
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"message" {"type" "string" "description" "Message to echo"}}
                  "required" ["message"]}
   :handler (fn [_ctx args] (str "Echo: " (get args "message")))})

(defn get-sum-tool []
  {:name "get-sum"
   :title "Get Sum Tool"
   :description "Returns the sum of two numbers"
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"a" {"type" "number" "description" "First number"}
                                "b" {"type" "number" "description" "Second number"}}
                  "required" ["a" "b"]}
   :handler (fn [_ctx args]
              (let [a (get args "a") b (get args "b")]
                (when-not (and (number? a) (number? b))
                  (mcp/invalid-params! "get-sum requires numeric \"a\" and \"b\""))
                (str "The sum of " a " and " b " is " (+ a b) ".")))})

(defn get-tiny-image-tool []
  {:name "get-tiny-image"
   :title "Get Tiny Image Tool"
   :description "Returns the MCP_TINY_IMAGE"
   :annotations read-only-annotations
   :input-schema {"type" "object" "properties" {}}
   :handler (fn [_ctx _args]
              (mcp/tool-result
               [(mcp/text "This is a tiny image:")
                (mcp/image mcp-tiny-image "image/png")
                (mcp/text "The image above is the MCP tiny image.")]))})

(defn get-annotated-message-tool []
  {:name "get-annotated-message"
   :title "Get Annotated Message Tool"
   :description "Demonstrates how annotations can be used to provide metadata about content."
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"messageType" {"type" "string" "enum" ["error" "success" "debug"]
                                               "description" "Type of message to demonstrate different annotation patterns"}
                                "includeImage" {"type" "boolean" "default" false
                                                "description" "Whether to include an example image"}}
                  "required" ["messageType"]}
   :handler
   (fn [_ctx args]
     (let [t (get args "messageType")
           msg (condp = t
                 "error" (mcp/text "Error: Operation failed"
                                   (mcp/annotations {:priority 1.0 :audience ["user" "assistant"]}))
                 "success" (mcp/text "Operation completed successfully"
                                     (mcp/annotations {:priority 0.7 :audience ["user"]}))
                 "debug" (mcp/text "Debug: Cache hit ratio 0.95, latency 150ms"
                                   (mcp/annotations {:priority 0.3 :audience ["assistant"]}))
                 (mcp/invalid-params! (str "Unknown messageType: " (pr-str t))))]
       (mcp/tool-result
        (cond-> [msg]
          (get args "includeImage")
          (conj (mcp/image mcp-tiny-image "image/png"
                           (mcp/annotations {:priority 0.5 :audience ["user"]})))))))})

(defn get-env-tool []
  {:name "get-env"
   :title "Get Environment Variables Tool"
   :description "Returns all environment variables from the running process as pretty-printed JSON."
   :annotations read-only-annotations
   :input-schema {"type" "object" "properties" {}}
   :handler (fn [_ctx _args] (mcp/write-json (getenv-map)))})

(defn get-structured-content-tool []
  {:name "get-structured-content"
   :title "Get Structured Content Tool"
   :description "Returns structured content along with an output schema for client data validation"
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"location" {"type" "string"
                                            "enum" ["New York" "Chicago" "Los Angeles"]
                                            "description" "Choose city"}}
                  "required" ["location"]}
   :output-schema {"type" "object"
                   "properties" {"temperature" {"type" "number" "description" "Temperature in celsius"}
                                 "conditions" {"type" "string" "description" "Weather conditions description"}
                                 "humidity" {"type" "number" "description" "Humidity percentage"}}
                   "required" ["temperature" "conditions" "humidity"]}
   :handler
   (fn [_ctx args]
     (let [weather (condp = (get args "location")
                     "New York" {"temperature" 33 "conditions" "Cloudy" "humidity" 60}
                     "Chicago" {"temperature" 26 "conditions" "Sunny" "humidity" 45}
                     "Los Angeles" {"temperature" 22 "conditions" "Partly cloudy" "humidity" 65}
                     (mcp/invalid-params! (str "Unknown location: " (pr-str (get args "location")))))]
       ;; Both halves on purpose: `structuredContent` for a client that
       ;; validates against outputSchema, and a JSON text block for one that
       ;; predates structured content.
       (mcp/tool-result [(mcp/text (mcp/write-json weather))]
                        {:structured-content weather})))})

(defn get-resource-links-tool []
  {:name "get-resource-links"
   :title "Get Resource Links Tool"
   :description "Returns up to ten resource links that reference different types of resources"
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"count" {"type" "number" "minimum" 1 "maximum" 10 "default" 3
                                         "description" "Number of resource links to return (1-10)"}}}
   :handler
   (fn [_ctx args]
     (let [count* (or (get args "count") 3)]
       (when-not (and (number? count*) (<= 1 count* 10))
         (mcp/invalid-params! "count must be between 1 and 10"))
       (mcp/tool-result
        (into [(mcp/text (str "Here are " count* " resource links to resources available in this server:"))]
              (map (fn [rid]
                     (let [text? (even? rid)
                           uri (if text? (text-resource-uri rid) (blob-resource-uri rid))]
                       (mcp/resource-link
                        {:uri uri
                         :name (str (if text? "Text" "Blob") " Resource " rid)
                         :description (str "Resource " rid ": "
                                           (if text? "plaintext resource" "binary blob resource"))
                         :mime-type "text/plain"})))
                   (range 1 (inc (int count*))))))))})

(defn get-resource-reference-tool [timestamp-fn]
  {:name "get-resource-reference"
   :title "Get Resource Reference Tool"
   :description "Returns a resource reference that can be used by MCP clients"
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"resourceType" {"type" "string" "enum" ["text" "blob"]
                                                "description" "Type of resource to reference"}
                                "resourceId" {"type" "integer" "minimum" 1
                                              "description" "ID of the resource to reference (positive integer)"}}
                  "required" ["resourceType" "resourceId"]}
   :handler
   (fn [_ctx args]
     (let [t (get args "resourceType")
           rid (get args "resourceId")]
       (when-not (and (integer? rid) (pos? rid))
         (mcp/invalid-params! "resourceId must be a positive integer"))
       (let [ts (timestamp-fn)
             [uri contents] (condp = t
                              "text" (let [u (text-resource-uri rid)] [u (text-resource u rid ts)])
                              "blob" (let [u (blob-resource-uri rid)] [u (blob-resource u rid ts)])
                              (mcp/invalid-params! (str "Unknown resourceType: " (pr-str t))))]
         (mcp/tool-result
          [(mcp/text (str "Returning resource reference for " uri ":"))
           (mcp/embedded-resource contents)
           (mcp/text "You can access this resource using the URI above.")]))))})

;; ---------------------------------------------------------------------------
;; Progress
;; ---------------------------------------------------------------------------

(defn trigger-long-running-operation-tool [sleep-fn]
  {:name "trigger-long-running-operation"
   :title "Trigger Long Running Operation Tool"
   :description "Demonstrates a long running operation with progress updates."
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"duration" {"type" "number" "default" 10
                                            "description" "Duration of the operation in seconds"}
                                "steps" {"type" "number" "default" 5
                                         "description" "Number of steps in the operation"}}}
   :handler
   (fn [ctx args]
     (let [duration (or (get args "duration") 10)
           steps (int (or (get args "steps") 5))
           step-duration (/ duration (max steps 1))]
       (doseq [i (range 1 (inc steps))]
         (sleep-fn (* step-duration 1000))
         ;; Unconditional: `:progress!` is a no-op when the client did not
         ;; send a progressToken, which is exactly the MUST NOT the reference
         ;; server has to check for itself.
         ((:progress! ctx) i {:total steps
                              :message (str "Step " i " of " steps)}))
       (str "Long running operation completed. Duration: " duration
            " seconds, Steps: " steps ".")))})

;; ---------------------------------------------------------------------------
;; Logging
;;
;; The reference server has `toggle-simulated-logging`, which starts a
;; per-session interval emitting log notifications until toggled off, and
;; honours the level set once by `logging/setLevel`. Both halves of that are
;; gone: `logging/setLevel` was removed, the level is a per-request `_meta`
;; field, and a server MUST NOT emit `notifications/message` at all for a
;; request that did not carry one. A cross-request "logging is on" toggle
;; cannot exist. What replaces it is a tool that logs during the request that
;; asked to see logs.
;; ---------------------------------------------------------------------------

(defn emit-simulated-logs-tool []
  {:name "emit-simulated-logs"
   :title "Emit Simulated Logs Tool"
   :description (str "Emits one log message at each RFC 5424 severity for THIS request. "
                     "Send io.modelcontextprotocol/logLevel in the request _meta to receive "
                     "them; without it the server emits none, as the specification requires.")
   :annotations read-only-annotations
   :input-schema {"type" "object"
                  "properties" {"logger" {"type" "string" "default" "everything"
                                          "description" "Logger name to attach to each message"}}}
   :handler
   (fn [ctx args]
     (let [logger (or (get args "logger") "everything")]
       (doseq [level mcp/logging-levels]
         ((:log! ctx) level (str "Simulated " level " message") {:logger logger}))
       (if (:log-level ctx)
         (str "Emitted log messages at or above " (:log-level ctx) ".")
         (str "No log messages were emitted: this request did not carry "
              mcp/meta-log-level " in its _meta."))))})

;; ---------------------------------------------------------------------------
;; Multi round-trip tools — elicitation, URL elicitation, sampling, roots
;;
;; Every one of these was a server-initiated JSON-RPC request in the
;; reference server. Under MRTR the server answers `input_required` and the
;; CLIENT retries. Each therefore has the same two-branch shape: if the
;; matching input response is already present, finish; otherwise ask.
;; ---------------------------------------------------------------------------

;; The reference server's demo form, which exercises string, boolean,
;; defaults, email format, number bounds and enums.
(def elicitation-schema
  {"type" "object"
   "properties"
   {"name" {"title" "String" "type" "string" "description" "Your full, legal name"}
    "check" {"title" "Boolean" "type" "boolean" "description" "Agree to the terms and conditions"}
    "firstLine" {"title" "String with default" "type" "string"
                 "description" "Favorite first line of a story"
                 "default" "It was a dark and stormy night."}
    "email" {"title" "String with email format" "type" "string" "format" "email"
             "description" "Your email address (will be verified, and never shared with anyone else)"}
    "age" {"title" "Number with bounds" "type" "integer" "minimum" 0 "maximum" 130
           "description" "Your age in years"}
    "pet" {"title" "Enum" "type" "string" "enum" ["cat" "dog" "bird" "fish" "none"]
           "description" "Your preferred pet"}}
   "required" ["name" "check"]})

(defn trigger-elicitation-request-tool []
  {:name "trigger-elicitation-request"
   :title "Trigger Elicitation Request Tool"
   :description (str "Asks the client to collect form input from the user. Returns an "
                     "InputRequiredResult on the first call; call again with the "
                     "inputResponses and requestState to receive the outcome.")
   :annotations {"readOnlyHint" false "destructiveHint" false
                 "idempotentHint" false "openWorldHint" false}
   :input-schema {"type" "object" "properties" {}}
   :handler
   (fn [ctx _args]
     ;; Up front, before any work: a server MUST NOT rely on a capability the
     ;; client did not declare. The reference server instead hid the tool
     ;; from clients without it, which SEP-2567 no longer permits.
     (mcp/require-capabilities! (:client-capabilities ctx) {"elicitation" {}})
     (if-let [answer (get (:input-responses ctx) "form")]
       (mcp/tool-result
        [(mcp/text (str "Elicitation " (get answer "action") "."))
         (mcp/text (mcp/write-json answer))])
       (mcp/input-required
        {:input-requests {"form" (mcp/elicit-form "Please provide inputs for the following fields:"
                                                  elicitation-schema)}
         :request-state "trigger-elicitation-request"})))})

(defn trigger-url-elicitation-tool []
  {:name "trigger-url-elicitation"
   :title "Trigger URL Elicitation Tool"
   :description (str "Asks the client to send the user through an out-of-band flow at a URL. "
                     "Requires the elicitation.url client capability.")
   :annotations {"readOnlyHint" false "destructiveHint" false
                 "idempotentHint" false "openWorldHint" false}
   :input-schema {"type" "object"
                  "properties" {"url" {"type" "string" "default" "https://modelcontextprotocol.io"
                                       "description" "Where to send the user"}}}
   :handler
   (fn [ctx args]
     (mcp/require-capabilities! (:client-capabilities ctx) {"elicitation" {"url" {}}})
     (let [url (or (get args "url") "https://modelcontextprotocol.io")]
       (if-let [answer (get (:input-responses ctx) "url")]
         (mcp/tool-result (str "URL elicitation " (get answer "action") " for " url "."))
         ;; The reference server's error path threw -32042
         ;; (UrlElicitationRequiredError) and carried an `elicitationId` so a
         ;; later `notifications/elicitation/complete` could be correlated.
         ;; Both were removed in this revision: the client learns the outcome
         ;; by retrying, and a server that needs to correlate across retries
         ;; puts its own identifier in requestState — as here.
         (mcp/input-required
          {:input-requests {"url" (mcp/elicit-url "Please complete authorization in your browser." url)}
           :request-state (str "trigger-url-elicitation:" url)}))))})

(defn trigger-sampling-request-tool []
  {:name "trigger-sampling-request"
   :title "Trigger Sampling Request Tool"
   :description (str "Asks the client's LLM to answer a prompt and returns the response. "
                     "Sampling is deprecated as of 2026-07-28 but remains functional.")
   :annotations {"readOnlyHint" false "destructiveHint" false
                 "idempotentHint" false "openWorldHint" true}
   :input-schema {"type" "object"
                  "properties" {"prompt" {"type" "string" "description" "The prompt to send to the LLM"}
                                "maxTokens" {"type" "integer" "default" 100
                                             "description" "Maximum tokens to generate"}}
                  "required" ["prompt"]}
   :handler
   (fn [ctx args]
     (mcp/require-capabilities! (:client-capabilities ctx) {"sampling" {}})
     (if-let [answer (get (:input-responses ctx) "sample")]
       (mcp/tool-result
        [(mcp/text (str "LLM sampling result (model " (get answer "model") "):"))
         (mcp/text (or (get-in answer ["content" "text"]) (mcp/write-json answer)))])
       (mcp/input-required
        {:input-requests
         {"sample" (mcp/create-message
                    {:messages [(mcp/user-message (mcp/text (get args "prompt")))]
                     :max-tokens (or (get args "maxTokens") 100)
                     :system-prompt "You are a helpful test server."})}
         :request-state "trigger-sampling-request"})))})

(defn get-roots-list-tool []
  {:name "get-roots-list"
   :title "Get Roots List Tool"
   :description (str "Returns the client's roots. Roots are deprecated as of 2026-07-28; "
                     "prefer passing directories as ordinary tool arguments.")
   :annotations read-only-annotations
   :input-schema {"type" "object" "properties" {}}
   :handler
   (fn [ctx _args]
     (mcp/require-capabilities! (:client-capabilities ctx) {"roots" {}})
     (if-let [answer (get (:input-responses ctx) "roots")]
       ;; The reference server returned "the last list of roots sent by the
       ;; client", cached from a roots/list_changed notification. That
       ;; notification was removed, and caching per connection would be
       ;; session state — so the roots are asked for and used within the one
       ;; logical request, and nothing is remembered.
       (mcp/tool-result (mcp/write-json (get answer "roots")))
       (mcp/input-required {:input-requests {"roots" (mcp/list-roots)}
                            :request-state "get-roots-list"})))})

;; ---------------------------------------------------------------------------
;; Handle-scoped resources and subscription updates
;; ---------------------------------------------------------------------------

(defn register-resource-tool [store]
  {:name "register-resource"
   :title "Register Resource Tool"
   :description (str "Stores text under a server-minted handle and returns a resource link to it. "
                     "The handle is the explicit identifier that replaces per-session state: "
                     "pass it back as an ordinary argument, or read the resource by its URI.")
   :annotations {"readOnlyHint" false "destructiveHint" false
                 "idempotentHint" false "openWorldHint" false}
   :input-schema {"type" "object"
                  "properties" {"name" {"type" "string" "description" "Human-readable name"}
                                "text" {"type" "string" "description" "Content to store"}}
                  "required" ["name" "text"]}
   :handler
   (fn [_ctx args]
     (let [handle (str "r" (:next-handle (swap! store update :next-handle inc)))
           uri (registered-uri handle)]
       (swap! store assoc-in [:resources handle]
              {:name (get args "name") :text (get args "text")})
       (swap! store update-in [:versions uri] (fnil inc 0))
       (mcp/tool-result
        [(mcp/text (str "Registered resource with handle " handle "."))
         (mcp/resource-link {:uri uri :name (get args "name") :mime-type "text/plain"})])))})

(defn touch-resource-tool [store notify!]
  {:name "touch-resource"
   :title "Touch Resource Tool"
   :description (str "Bumps a registered resource's version and emits "
                     "notifications/resources/updated to every subscriptions/listen stream "
                     "that subscribed to its URI.")
   :annotations {"readOnlyHint" false "destructiveHint" false
                 "idempotentHint" false "openWorldHint" false}
   :input-schema {"type" "object"
                  "properties" {"handle" {"type" "string" "description" "Handle from register-resource"}}
                  "required" ["handle"]}
   :handler
   (fn [_ctx args]
     (let [handle (get args "handle")
           uri (registered-uri handle)]
       (when-not (get-in @store [:resources handle])
         (mcp/not-found! (str "Unknown handle: " (pr-str handle))))
       (swap! store update-in [:versions uri] (fnil inc 0))
       ;; The notification goes out on the subscription stream, never on this
       ;; request's own response stream — the two are different channels and
       ;; the spec keeps them apart.
       (let [n (notify! (mcp/resource-updated-notification uri))]
         (str "Bumped " uri " to version " (get-in @store [:versions uri])
              "; notified " n " subscription stream(s)."))))})

;; ---------------------------------------------------------------------------
;; Prompts
;; ---------------------------------------------------------------------------

(def department-members
  {"Engineering" ["Alice" "Bob" "Charlie"]
   "Sales" ["David" "Eve" "Frank"]
   "Marketing" ["Grace" "Henry" "Iris"]
   "Support" ["John" "Kim" "Lee"]})

(defn prompts [timestamp-fn]
  [{:name "simple-prompt"
    :title "Simple Prompt"
    :description "A prompt with no arguments"
    :handler (fn [_ctx _args]
               [(mcp/user-message (mcp/text "This is a simple prompt without arguments."))])}

   {:name "args-prompt"
    :title "Arguments Prompt"
    :description "A prompt with two arguments, one required and one optional"
    :arguments [{:name "city" :description "Name of the city" :required true}
                {:name "state" :description "Name of the state"}]
    :handler (fn [_ctx args]
               (let [location (str (get args "city")
                                   (when-let [s (get args "state")] (str ", " s)))]
                 [(mcp/user-message (mcp/text (str "What's weather in " location "?")))]))}

   {:name "completable-prompt"
    :title "Team Management"
    :description "First argument choice narrows values for second argument."
    :arguments [{:name "department" :description "Choose the department." :required true}
                {:name "name" :description "Choose a team member to lead the selected department."
                 :required true}]
    :handler (fn [_ctx args]
               [(mcp/user-message
                 (mcp/text (str "Please promote " (get args "name")
                                " to the head of the " (get args "department") " team.")))])}

   {:name "resource-prompt"
    :title "Resource Prompt"
    :description "A prompt that includes an embedded resource of the chosen type"
    :arguments [{:name "resourceType" :description "\"Text\" or \"Blob\"" :required true}
                {:name "resourceId" :description "ID of the resource to embed" :required true}]
    :handler
    (fn [_ctx args]
      (let [t (get args "resourceType")
            rid-str (get args "resourceId")
            rid (parse-resource-id! (str "resource-prompt:" rid-str) rid-str)
            ts (timestamp-fn)
            [uri contents] (condp = t
                             "Text" (let [u (text-resource-uri rid)] [u (text-resource u rid ts)])
                             "Blob" (let [u (blob-resource-uri rid)] [u (blob-resource u rid ts)])
                             (mcp/invalid-params! (str "Unknown resourceType: " (pr-str t))))]
        [(mcp/user-message (mcp/text (str "This prompt includes " uri ":")))
         (mcp/user-message (mcp/embedded-resource contents))]))}])

;; ---------------------------------------------------------------------------
;; Completions
;;
;; One function rather than the reference server's per-argument `completable()`
;; wrappers, matching the Python SDK's single `@mcp.completion()` hook. It
;; receives the ref (prompt or resource template), the argument being
;; completed, and the previously-supplied arguments as context — which is what
;; lets `name` depend on `department`.
;; ---------------------------------------------------------------------------

(defn completions-handler [_ctx ref argument context]
  (let [value (or (get argument "value") "")
        arg-name (get argument "name")
        prior (get context "arguments")
        starts (fn [xs] (vec (filter (fn [x] (str/starts-with? x value)) xs)))]
    (condp = (get ref "type")
      "ref/prompt"
      (condp = [(get ref "name") arg-name]
        ["completable-prompt" "department"] (starts (sort (keys department-members)))
        ["completable-prompt" "name"] (starts (get department-members (get prior "department") []))
        ["resource-prompt" "resourceType"] (starts ["Text" "Blob"])
        ["resource-prompt" "resourceId"] (let [n (try (mcp/read-json value) (catch Exception _ nil))]
                                           (if (and (integer? n) (pos? n)) [value] []))
        [])
      "ref/resource"
      (if (= "resourceId" arg-name)
        (let [n (try (mcp/read-json value) (catch Exception _ nil))]
          (if (and (integer? n) (pos? n)) [value] []))
        [])
      [])))

;; ---------------------------------------------------------------------------
;; Resources
;; ---------------------------------------------------------------------------

;; The reference server publishes exactly this many numbered resources so a
;; client author has enough to page through. Kept identical.
(def resource-count
  100)

(defn numbered-resources
  "NOT in the reference server, which reaches its dynamic resources only
   through the two templates and therefore lists nothing pageable. These
   hundred entries — odd ids plaintext, even ids base64 blob — exist so that
   `resources/list` pagination is reachable at all, which no other primitive
   here exercises. Their URIs are the very ones the templates match, so
   `resources/list` and `resources/templates/list` describe one set of
   resources rather than two."
  [timestamp-fn]
  (mapv (fn [i]
          (let [text? (odd? i)
                uri (if text? (text-resource-uri i) (blob-resource-uri i))]
            {:name (str "Resource " i)
             :title (str "Resource " i)
             :uri uri
             :description (str "A " (if text? "plaintext" "base64 blob") " resource")
             :mime-type (if text? "text/plain" "application/octet-stream")
             :annotations (mcp/annotations {:audience ["user" "assistant"]
                                            :priority (if text? 0.5 0.3)})
             :handler (fn [_ctx u]
                        (if text?
                          (text-resource u i (timestamp-fn))
                          (blob-resource u i (timestamp-fn))))}))
        (range 1 (inc resource-count))))

(defn static-resources
  "Three fixed documents. Not in the reference server; they exist here because
   `completions` and `resources/read` deserve a target whose bytes never
   change, and because a server with only generated resources reads as a toy."
  []
  ;; Sorted by key, not by map entry.
  (mapv (fn [nm]
          (let [text (get static-documents nm)]
            {:name nm
             :title (str "Document: " nm)
             :uri (str "demo://resource/static/document/" nm)
             :description (str "Static document \"" nm "\"")
             :mime-type "text/markdown"
             :handler (fn [_ctx uri] (mcp/text-contents uri text "text/markdown"))}))
        (sort (keys static-documents))))

(defn resource-templates
  "Three templates. The first two mint a resource for ANY positive integer —
   including ids beyond the hundred that `resources/list` enumerates, which is
   the point of a template. The third reads back whatever `register-resource`
   stored, and is how handle-scoped state is read without a session."
  [store timestamp-fn]
  [{:name "Dynamic text resource"
    :title "Dynamic Text Resource"
    :uri-template text-uri-template
    :description "A plaintext resource generated for any positive integer id"
    :mime-type "text/plain"
    :handler (fn [_ctx uri variables]
               (let [rid (parse-resource-id! uri (get variables "resourceId"))]
                 (text-resource uri rid (timestamp-fn))))}

   {:name "Dynamic blob resource"
    :title "Dynamic Blob Resource"
    :uri-template blob-uri-template
    :description "A base64 blob resource generated for any positive integer id"
    :mime-type "application/octet-stream"
    :handler (fn [_ctx uri variables]
               (let [rid (parse-resource-id! uri (get variables "resourceId"))]
                 (blob-resource uri rid (timestamp-fn))))}

   {:name "Registered resource"
    :title "Registered Resource"
    :uri-template registered-uri-template
    :description "Text stored by the register-resource tool, read back by handle"
    :mime-type "text/plain"
    :handler (fn [_ctx uri variables]
               (let [handle (get variables "handle")
                     entry (get-in @store [:resources handle])]
                 ;; -32602, not -32002: this revision deleted the dedicated
                 ;; resource-not-found code and folded it into invalid params.
                 (when-not entry
                   (mcp/not-found! (str "Unknown resource: " uri)))
                 (mcp/text-contents uri (:text entry) "text/plain")))}])

;; ---------------------------------------------------------------------------
;; The server
;; ---------------------------------------------------------------------------

(defn- default-timestamp
  "Milliseconds since the epoch, as a string. The reference server stamps an
   ISO-8601 date; a monotonic integer is the portable equivalent here, and
   every caller can inject its own anyway — which is what keeps the whole
   server a pure function of its inputs under test."
  []
  (str (System/currentTimeMillis)))

(defn- default-sleep [millis]
  (let [ms (long millis)]
    (when (pos? ms)
      (Thread/sleep ms)
      nil)))

(defn everything-server
  "Build the server.

   Every impure input is injected and defaulted, so a test can build a server
   whose output is byte-for-byte determined by the request:

     :store         handle store from `new-store`, per server instance
     :notify!       (fn [notification] -> stream-count) — how a resource
                    update reaches open `subscriptions/listen` streams. The
                    default drops them, because a server with no transport
                    attached has nowhere to send one.
     :timestamp-fn  (fn [] -> string) stamped into generated resources
     :sleep-fn      (fn [millis]) used by trigger-long-running-operation
     :page-size     resource list page size, 10 as in the reference server.
                    Scoped to resources on purpose: the reference server's
                    PAGE_SIZE covers its hundred resources and nothing else,
                    and a server whose whole job is to be explored should not
                    hide six of its sixteen tools behind a cursor."
  ([] (everything-server nil))
  ([{:keys [store notify! timestamp-fn sleep-fn page-size]
     :or {page-size 10}}]
   (let [store (or store (new-store))
         notify! (or notify! (constantly 0))
         timestamp-fn (or timestamp-fn default-timestamp)
         sleep-fn (or sleep-fn default-sleep)]
     (server/server
      {:name "everything"
       :version "1.0.0"
       :title "Everything Example Server"
       :description "Exercises every capability of MCP revision 2026-07-28."
       :instructions instructions-text
       :tools [(echo-tool)
               (get-sum-tool)
               (get-tiny-image-tool)
               (get-annotated-message-tool)
               (get-env-tool)
               (get-structured-content-tool)
               (get-resource-links-tool)
               (get-resource-reference-tool timestamp-fn)
               (trigger-long-running-operation-tool sleep-fn)
               (emit-simulated-logs-tool)
               (trigger-elicitation-request-tool)
               (trigger-url-elicitation-tool)
               (trigger-sampling-request-tool)
               (get-roots-list-tool)
               (register-resource-tool store)
               (touch-resource-tool store notify!)]
       :resources (into (static-resources) (numbered-resources timestamp-fn))
       :resource-templates (resource-templates store timestamp-fn)
       :prompts (prompts timestamp-fn)
       :completions completions-handler
       ;; register-resource mints a resource; touch-resource updates one. Both
       ;; are things a listen stream can be told about, so both capabilities
       ;; are declared — and declared truthfully, which `capabilities` checks
       ;; against what is actually registered.
       :resources-list-changed? true
       :resources-subscribe? true
       :logging? true
       ;; Resources only — see :page-size above.
       :page-size {:resources page-size}
       ;; Generated resources carry a timestamp, so nothing here is cacheable
       ;; beyond the request that produced it; `ttlMs` 0 says exactly that.
       :cache {:ttl-ms 0 :cache-scope "private"}}))))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn -main
  "`-main` with no arguments speaks stdio, which is what an MCP client
   launches. `--http [port]` serves Streamable HTTP, JVM Clojure only.

   The stdio branch is the interesting one: `notify!` has to reach the
   connection, but the connection does not exist until `serve!` builds it, and
   the server has to exist before that. An atom filled by `:on-start` closes
   the loop without either side knowing about the other."
  [& args]
  (let [conn-atom (atom nil)
        notify! (fn [notification]
                  (if-let [conn @conn-atom] (stdio/notify! conn notification) 0))
        srv (everything-server {:notify! notify!})]
    (if (= "--http" (first args))
      (let [port (if-let [p (second args)] (mcp/read-json p) 0)
            {:keys [port stop!]} (http/serve! srv {:port port})]
        (stdio/log! (str "everything server listening on http://127.0.0.1:" port "/mcp"))
        ;; Park until stdin closes; the transport threads do the work.
        (loop [] (when (read-line) (recur)))
        (stop!))
      (stdio/serve! srv {:on-start (fn [conn] (reset! conn-atom conn))}))))
