(ns tools.agents.openai.agents
  "A pure-Clojure client for OpenAI's (beta) Agents API — the managed Codex
   harness that runs sessions and turns, optionally inside an OpenAI-hosted or
   self-hosted sandbox, ergonomically modeled on openai-python's
   `client.beta.agents.*` resource tree. See
   https://developers.openai.com/api/docs/guides/agents-api/overview.

   SIBLING OF tools.agents.openai, NOT A PEER LIBRARY: this namespace takes
   the exact `OpenAIClient` record `tools.agents.openai/client` builds
   (:api-key, :base-url, :organization, :project, :max-retries all apply
   unchanged) and reuses that namespace's JSON codec (`write-json`/
   `read-json`) and retry-policy functions (`should-retry?`, `retry-delay-ms`)
   verbatim, rather than re-implementing or forking them. The Agents API lives
   under the SAME `https://api.openai.com/v1` root as Responses and Chat
   Completions — distinguished only by the `/agents` path prefix and a
   required `OpenAI-Beta: agents=v1` header this namespace adds to every
   request — so a client built once with `tools.agents.openai/client` works
   for both this namespace and its sibling. A consequence worth knowing: a
   malformed-JSON error from this namespace's own transport still surfaces as
   `:type :tools.agents.openai/json-parse-error` (not a `.agents`-suffixed
   variant) because it is literally `tools.agents.openai/read-json` throwing
   it — see docs/openai-agents.md.

   WIRE FORMAT — confirmed from OpenAI's own raw `.md` documentation pages
   (fetched directly, NOT summarized) rather than from any LLM's prior
   knowledge, since this is a September-2026 beta API:
     https://developers.openai.com/api/docs/guides/agents-api/{overview,
     architecture,quickstart,sessions,sessions/manage,sessions/webhooks,
     configuration,tools/functions,environments/{openai-hosted,self-hosted,
     files}}.md
   Every path and JSON shape below is one shown verbatim in a `curl` example
   or literal endpoint text on those pages. Two things those pages document
   only as SDK method calls, with no literal REST path or schema shown, are
   deliberately NOT implemented here rather than guessed at: saved/reusable
   `agent` CRUD (`client.beta.agents.create/list/retrieve/delete`, distinct
   from a SESSION's inline `agent` config, which `sessions-create` passes
   through fine) and the session/environment Artifacts and Files APIs. Both
   are additive — nothing here would need to change to add them once their
   wire shape is confirmed the same way.

   SCOPE — what this namespace covers:
     - Session lifecycle: create, retrieve, list (paginated), delete.
     - Turn input: send a message, steer or cancel the active turn, return a
       pending function-tool result.
     - Reading saved work: list a session's items (saved messages and tool
       calls) and pull assistant text out of them.
     - OpenAI-hosted sandbox status: poll an environment's provisioning state.
     - Self-hosted sandboxes: `sessions-create` passes `environment.type`
       \"self_hosted\" straight through like any other field, and
       `self-hosted-executor-command` turns a created session into the
       `codex exec-server` command line the docs show for connecting your own
       infrastructure — see that function's docstring for exactly what is and
       is not this library's job there.

   STREAMING IS NOT IMPLEMENTED, matching tools.agents.openai's own
   `:stream true` refusal: `sessions-create` throws
   `:tools.agents.openai/streaming-unsupported` immediately rather than
   opening an SSE connection. There is also no GET .../events long-poll here.
   Follow a turn by polling `sessions-retrieve` (its `\"status\"`) and
   `sessions-items-list` instead — see docs/openai-agents.md's
   \"No streaming — poll instead\" section for the trade-off and a worked
   polling loop (`examples/openai/agents_sandbox_task.clj`).

   PORTABILITY: exactly one function does real network I/O — the private leaf
   `http-request!` below, isolated with a #?(:bb ... :clj ...) reader
   conditional, generalizing tools.agents.openai's `http-post!` to GET/POST/
   DELETE (sessions and items need GET, session deletion needs DELETE).
   Everything above it is plain, portable clojure.core, exercised identically
   by both test runners.

   Runs unmodified on JVM Clojure and Babashka."
  (:require [clojure.string :as str]
            [tools.agents.openai :as oai]
            #?@(:bb [[babashka.http-client :as http]] :clj [])))

;; The one header this API adds on top of everything tools.agents.openai/client
;; already resolves (api-key, organization, project, base-url, max-retries).
;; Required on every request; the OpenAI SDKs add it automatically, quote from
;; the quickstart: "include it explicitly when using cURL."
(def ^:private beta-header "agents=v1")

;; ---------------------------------------------------------------------------
;; I/O leaf — `http-request!` is the only runtime-specific function in this
;; file. Same shape as tools.agents.openai's `http-post!`, generalized to a
;; `method` argument since this API's resource tree needs GET and DELETE too.
;;
;; Deliberately reuses tools.agents.openai's OWN bb-http-client/jvm-http-client
;; delays rather than opening a second HTTP client — those are public
;; (not ^:private) specifically so the two namespaces share one connection
;; pool when a caller follows this doc's advice to build one client and use
;; it with both.
;; ---------------------------------------------------------------------------

(defn- http-request!
  "Perform an HTTP request. `method` is one of :get :post :delete; `body` is
   a JSON string or nil (GET/DELETE send no body). Returns {:status :headers
   :body} on ANY HTTP response (2xx or not) — callers classify status
   themselves. Throws only on a genuine transport failure (DNS, connection
   refused, TLS handshake failure, timeout — no response at all). Pins
   HTTP/1.1 for the same reason tools.agents.openai's leaf does — a plain
   HTTP/1.1 reverse proxy in front of an OpenAI-compatible gateway 502s on
   java.net.http's unnegotiated HTTP/2 cleartext preface."
  [method url headers body]
  #?(:bb
     (let [resp (http/request (cond-> {:method method :uri url :client @oai/bb-http-client
                                        :headers headers :throw false}
                                 body (assoc :body body)))]
       {:status (:status resp) :headers (:headers resp) :body (:body resp)})

     :clj
     (let [builder0 (reduce (fn [b [k v]] (.header ^java.net.http.HttpRequest$Builder b (str k) (str v)))
                             (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                             headers)
           builder  (case method
                      :get    (.GET ^java.net.http.HttpRequest$Builder builder0)
                      :delete (.DELETE ^java.net.http.HttpRequest$Builder builder0)
                      :post   (.POST ^java.net.http.HttpRequest$Builder builder0
                                     (java.net.http.HttpRequest$BodyPublishers/ofString (or body ""))))
           req      (.build ^java.net.http.HttpRequest$Builder builder)
           resp     (.send @oai/jvm-http-client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status  (.statusCode resp)
        :headers (into {} (map (fn [[k vs]] [k (first vs)]) (.map (.headers resp))))
        :body    (.body resp)})))

(defn- sleep! [millis] (let [ms (long millis)] (when (pos? ms) (Thread/sleep ms) nil)))
(defn- now-ms [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Request plumbing — URL/header building, error typing, the shared transport.
;; ---------------------------------------------------------------------------

(defn- url-encode-component
  "URL-encode a single path segment or query value. Strings/numbers encode
   via `str`; keywords/symbols via `name` (verbatim, no case conversion) —
   matching this whole codebase's key/value passthrough idiom, so
   `{\"order\" :desc}` encodes as `order=desc`, not the keyword's own
   `:desc` print-form (which would send a literal leading colon)."
  [v]
  (java.net.URLEncoder/encode (if (or (keyword? v) (symbol? v)) (name v) (str v)) "UTF-8"))

(defn- query-string
  "Turn a plain map of query params into \"?k=v&k2=v2\", URL-encoded, or nil
   when `params` is empty/nil. Map keys AND values may be strings or
   keywords."
  [params]
  (when (seq params)
    (str "?" (str/join "&"
               (map (fn [[k v]] (str (url-encode-component k) "=" (url-encode-component v)))
                    params)))))

(defn- path-segment
  "URL-encode a caller-supplied path segment (a session or environment id)
   before splicing it into a URL — an id containing `/`, `?`, `#` or `%`
   (corrupted, or echoed from an untrusted source) must not silently reroute
   the request to a different path or endpoint."
  [id]
  (url-encode-component id))

(defn- request-headers [fn-name client]
  (when-not (:api-key client)
    (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": client has no :api-key — build one via "
                          "tools.agents.openai/client")
                     {:type :tools.agents.openai/missing-credentials})))
  (cond-> {"authorization" (str "Bearer " (:api-key client))
           "content-type"  "application/json"
           "openai-beta"   beta-header}
    (seq (:organization client)) (assoc "openai-organization" (:organization client))
    (seq (:project client))      (assoc "openai-project" (:project client))))

(defn- reject-streaming! [fn-name request]
  (when (or (true? (get request :stream)) (true? (get request "stream")))
    (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": :stream true is not supported — "
                          "SSE streaming is not implemented by this client. Poll `sessions-retrieve` / "
                          "`sessions-items-list` instead — see docs/openai-agents.md.")
                     {:type :tools.agents.openai/streaming-unsupported}))))

;; status->type and extract-error-message are NOT duplicated here — this
;; namespace calls tools.agents.openai's own (public, not ^:private) copies
;; directly, so the two can never drift apart. See the ns docstring for why
;; that means this namespace's thrown :type is always :tools.agents.openai/…,
;; never a `.agents`-suffixed keyword.

(defn- own-error?
  "True for an already-typed ex-info from tools.agents.openai — whose
   write-json/read-json/status->type/extract-error-message this namespace
   calls directly, and which is therefore the only namespace any ex-info
   passing through this file's own throw sites can ever be typed under (see
   the ns docstring)."
  [e]
  (let [data (ex-data e)]
    (boolean (and data (keyword? (:type data)) (= "tools.agents.openai" (namespace (:type data)))))))

(defn- resolve-max-retries [client]
  (let [n (:max-retries client)]
    (if (number? n) (max 0 (long n)) oai/default-max-retries)))

(defn- send-request!
  "Shared transport for every resource method below: build the URL and
   headers, JSON-encode `body-value` when given, perform `method`, retry
   transport failures and retryable statuses per tools.agents.openai's own
   `should-retry?`/`retry-delay-ms` policy (`:max-retries` on the client,
   default 2 — identical policy to the sibling namespace, just parameterized
   over HTTP method here since this API needs GET and DELETE as well as
   POST), and decode a 2xx JSON body.

   `path` is already fully built (joined with a query string, if any, by the
   caller). `body-value` is a Clojure map to encode as the request body, or
   nil for a GET/DELETE with no body.

   Non-retryable failures, and retryable ones once the budget is spent, throw
   ex-info with message prefixed \"tools.agents.openai.agents/<fn-name>: \"
   and `:type` under `:tools.agents.openai/…` (see the ns docstring) plus
   `:retries-taken`."
  [client fn-name method path body-value]
  (when (map? body-value) (reject-streaming! fn-name body-value))
  (let [url         (oai/endpoint-url (:base-url client) path)
        headers     (request-headers fn-name client)
        body-str    (when body-value (oai/write-json body-value))
        max-retries (resolve-max-retries client)]
    (loop [retries-taken 0]
      (let [outcome (try
                      {:resp (http-request! method url headers body-str)}
                      (catch Exception e
                        (if (own-error? e) (throw e) {:error e})))]
        (if (:error outcome)
          (if (< retries-taken max-retries)
            (do (sleep! (oai/retry-delay-ms retries-taken nil (now-ms)))
                (recur (inc retries-taken)))
            (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": connection failed: " (:error outcome))
                             {:type :tools.agents.openai/api-connection-error :status nil :body nil
                              :retries-taken retries-taken})))
          (let [resp      (:resp outcome)
                status    (:status resp)
                resp-hdrs (:headers resp)
                resp-body (:body resp)]
            (if (and status (>= status 200) (< status 300))
              (when (seq resp-body) (oai/read-json resp-body))
              (if (and (< retries-taken max-retries) (oai/should-retry? status resp-hdrs (now-ms)))
                (do (sleep! (oai/retry-delay-ms retries-taken resp-hdrs (now-ms)))
                    (recur (inc retries-taken)))
                (let [err-msg (oai/extract-error-message resp-body)
                      detail  (cond err-msg err-msg (seq resp-body) resp-body :else nil)]
                  (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": HTTP " status (when detail (str " " detail)))
                                   {:type (oai/status->type status) :status status :body resp-body
                                    :retries-taken retries-taken})))))))))))

;; ---------------------------------------------------------------------------
;; Public API — sessions
;; ---------------------------------------------------------------------------

(defn sessions-create
  "POST {base-url}/agents/sessions — create a session, the analogue of
   Python's `client.beta.agents.sessions.create(**params)`. `request` is a
   plain map passed through to JSON almost verbatim:

     \"agent\"       {\"model\" ... \"instructions\" ...} — inline agent
                    config (or use \"agent_id\" to reuse a saved agent)
     \"agent_id\"    reuse a saved agent's configuration for this session
     \"environment\" {\"type\" \"none\"|\"openai_hosted\"|\"self_hosted\" ...}
     \"input\"       a bare string, or an array of
                    {\"role\" \"user\" \"content\" [{\"type\" \"input_text\"
                    \"text\" ...}]} messages

   `:stream true` / `\"stream\" true` throws immediately, before any network
   request — see the ns docstring's streaming note. The returned session's
   first turn (when `input` was given) keeps running asynchronously on
   OpenAI's side; poll `sessions-retrieve` or `sessions-items-list` for its
   outcome.

   Throws ex-info on any failure — `:type` is one of
   tools.agents.openai's error-hierarchy keywords (see docs/openai.md), plus
   `:retries-taken`."
  [client request]
  (send-request! client "sessions-create" :post "/agents/sessions" request))

(defn sessions-retrieve
  "GET {base-url}/agents/sessions/{session-id} — the analogue of
   `client.beta.agents.sessions.retrieve(session_id)`. Returns the session's
   current state: \"status\", \"agent\", \"environment\", and any pending
   \"required_actions\" (a function call awaiting its result, or a
   self-hosted environment awaiting its executor)."
  [client session-id]
  (send-request! client "sessions-retrieve" :get (str "/agents/sessions/" (path-segment session-id)) nil))

(defn sessions-list
  "GET {base-url}/agents/sessions — the analogue of
   `client.beta.agents.sessions.list(**params)`. `params`, if given, is a
   plain map of query parameters, e.g. {\"limit\" 20 \"order\" \"desc\"}.
   Page with `\"after\"` set to the previous page's `\"last_id\"` while
   `\"has_more\"` is true in the response, exactly as OpenAI's own
   cursor pagination works."
  ([client] (sessions-list client nil))
  ([client params]
   (send-request! client "sessions-list" :get (str "/agents/sessions" (query-string params)) nil)))

(defn sessions-delete
  "DELETE {base-url}/agents/sessions/{session-id} — removes the session from
   the API; physical sandbox cleanup may continue asynchronously. This does
   NOT stop provider compute for a self-hosted environment (per OpenAI's own
   docs) and has no corresponding webhook. To stop the current turn while
   KEEPING the session and its history, use `cancel-turn` instead."
  [client session-id]
  (send-request! client "sessions-delete" :delete (str "/agents/sessions/" (path-segment session-id)) nil))

;; ---------------------------------------------------------------------------
;; Public API — turn input (events)
;; ---------------------------------------------------------------------------

(defn sessions-events-create
  "POST {base-url}/agents/sessions/{session-id}/events — send one or more
   input events to a session, the analogue of
   `client.beta.agents.sessions.events.create(session_id, **params)`.
   `request` is `{\"events\" [...]}`; see `send-message`, `cancel-turn` and
   `send-tool-result` for the three event shapes OpenAI's docs show
   (`agent.session.input.message` / `.cancel` / `.tool_result`) — reach for
   this directly only for a shape those helpers don't cover."
  [client session-id request]
  (send-request! client "sessions-events-create" :post
                 (str "/agents/sessions/" (path-segment session-id) "/events") request))

(defn send-message
  "Send a follow-up `text` message to `session-id` — an
   `agent.session.input.message` event. Steers the active turn if one is
   running; starts a new turn on an idle session. Subscribe to the session's
   state (poll `sessions-retrieve`) before calling this if you need to
   observe the turn's very first state change."
  [client session-id text]
  (sessions-events-create client session-id
                           {"events" [{"type" "agent.session.input.message"
                                       "input" [{"role" "user"
                                                 "content" [{"type" "input_text" "text" text}]}]}]}))

(defn cancel-turn
  "Cancel `session-id`'s currently active turn — an
   `agent.session.input.cancel` event. The session and its prior work remain
   available; sending more input afterward starts a new turn."
  [client session-id]
  (sessions-events-create client session-id
                           {"events" [{"type" "agent.session.input.cancel"}]}))

(defn send-tool-result
  "Return a pending function call's result — an
   `agent.session.input.tool_result` event. `result` is a map:

     :turn-id  the pending action's \"turn_id\"
     :call-id  the pending action's \"call_id\"
     :success  true|false
     :output   the function's result (string, or a JSON-serialized string for
               a structured result) — used when :success is true
     :error    a message the agent can use — used when :success is false

   Copy `:turn-id`/`:call-id` from the matching entry in the session's
   `required_actions` (retrieved via `sessions-retrieve`, or read off an
   `agent.session.requires_action` event on a live stream this library does
   not implement)."
  [client session-id {:keys [turn-id call-id success output error]}]
  (sessions-events-create
   client session-id
   {"events" [(cond-> {"type" "agent.session.input.tool_result"
                       "turn_id" turn-id "call_id" call-id "success" (boolean success)}
                (some? output) (assoc "output" output)
                (some? error)  (assoc "error" error))]}))

;; ---------------------------------------------------------------------------
;; Public API — saved work
;; ---------------------------------------------------------------------------

(defn sessions-items-list
  "GET {base-url}/agents/sessions/{session-id}/items — the saved messages and
   tool calls for a session, including completed turns' output; the analogue
   of `client.beta.agents.sessions.items.list(session_id, **params)`.
   `params`, if given, is a plain map of query parameters, e.g.
   {\"order\" \"asc\" \"limit\" 100}. Use `items-output-text` to pull the
   assistant's text out of the result."
  ([client session-id] (sessions-items-list client session-id nil))
  ([client session-id params]
   (send-request! client "sessions-items-list" :get
                  (str "/agents/sessions/" (path-segment session-id) "/items" (query-string params)) nil)))

(defn items-output-text
  "Concatenate every assistant `output_text` content block across every
   `\"message\"` item in a `sessions-items-list` response's `\"data\"` array
   — the closest analogue this event-and-items model has to
   `tools.agents.openai/output-text`'s single-response traversal. Call
   `sessions-items-list` with `{\"order\" \"asc\"}` (its own documented
   example) so multi-turn text concatenates in conversation order.

   Returns \"\" when there is no assistant message text yet — a turn still in
   progress, one that only ran tools, or an empty session. DIVERGES from
   `tools.agents.openai/output-text`'s stricter contract in one place: a
   \"message\" item's non-array \"content\" is treated as empty rather than
   thrown on, because the Agents API's item schema is not fully documented in
   OpenAI's own guides at the time this was written (only \"items are the
   saved messages and tool calls\" prose, no full JSON sample) — being lenient
   here is the safer default against an item shape this library did not
   anticipate. A malformed \"data\" array, or an \"output_text\" block whose
   \"text\" is present but not a string, still throw."
  [items-response]
  (let [data (get items-response "data")]
    (when-not (vector? data)
      (throw (ex-info "tools.agents.openai.agents/items-output-text: response has no \"data\" array"
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    (str/join ""
           (reduce
             (fn [acc item]
               (if (and (map? item) (= (get item "type") "message") (= (get item "role") "assistant"))
                 (let [content (get item "content")]
                   (reduce
                     (fn [acc block]
                       (if (and (map? block) (= (get block "type") "output_text") (contains? block "text"))
                         (let [text (get block "text")]
                           (when-not (string? text)
                             (throw (ex-info (str "tools.agents.openai.agents/items-output-text: \"output_text\" "
                                                   "content block has a non-string \"text\" value: " (pr-str text))
                                              {:type :tools.agents.openai/invalid-content-shape :status nil :body nil})))
                           (conj acc text))
                         acc))
                     acc (if (vector? content) content [])))
                 acc))
             []
             data))))

;; ---------------------------------------------------------------------------
;; Public API — environments (OpenAI-hosted sandbox status)
;; ---------------------------------------------------------------------------

(defn environments-retrieve
  "GET {base-url}/agents/environments/{environment-id} — poll an
   `openai_hosted` sandbox's provisioning state, using the session's own
   `environment.id` (from `sessions-create`'s response, or `sessions-retrieve`
   thereafter). \"provisioning\" means setup is still running, \"connected\"
   means the agent can run commands, \"failed\" means setup errored (read
   `environment.error` off the `agent.session.environment.failed` event —
   this endpoint's own response does not carry it, per OpenAI's docs)."
  [client environment-id]
  (send-request! client "environments-retrieve" :get (str "/agents/environments/" (path-segment environment-id)) nil))

;; ---------------------------------------------------------------------------
;; Public API — self-hosted sandboxes
;; ---------------------------------------------------------------------------

(defn self-hosted-executor-command
  "Given a session created with `environment: {\"type\" \"self_hosted\" ...}`
   — this is YOUR side of \"connect your own infrastructure\": OpenAI runs
   the harness, but a self-hosted environment needs the `codex exec-server`
   executor running inside the compute you provision, holding a restricted
   *environment key* as `CODEX_API_KEY` (never your application's own
   `OPENAI_API_KEY` — see docs/openai-agents.md's Self-hosted sandboxes
   section for creating that key and the network egress the executor needs).

   This library does NOT run the executor itself: it is a released
   `@openai/codex` CLI process holding an outbound WebSocket connection,
   which is your infrastructure's job, not a portable Clojure HTTP client's.
   This function only turns a created session into the exact argv OpenAI's
   docs show for starting it, so callers can hand it to whatever process
   supervisor their own infrastructure already uses, e.g.

     (apply clojure.java.shell/sh (self-hosted-executor-command session))
     ;; (with CODEX_API_KEY set in :env, not this repo's job either)

   Throws ex-info {:type :tools.agents.openai/invalid-response} when the
   session has no `environment.id` / `environment.remote_url` — e.g. it was
   created with `environment.type` \"none\" or \"openai_hosted\", or the
   self-hosted environment has not registered yet."
  [session]
  (let [env (get session "environment")
        id  (get env "id")
        url (get env "remote_url")]
    ;; `(seq id)`/`(seq url)`, not just `string?` — an empty string is not a
    ;; usable id/url either, and must fail the same catchable way a missing
    ;; key does rather than silently building a broken executor command.
    (when-not (and (string? id) (seq id) (string? url) (seq url))
      (throw (ex-info (str "tools.agents.openai.agents/self-hosted-executor-command: session has no "
                            "environment.id/environment.remote_url — not a connected self-hosted session")
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    ["codex" "exec-server" "--remote" url "--environment-id" id]))
