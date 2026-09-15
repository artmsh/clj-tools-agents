(ns tools.agents.openai.agents
  "A pure-Clojure client for OpenAI's (beta) Agents API — the managed Codex
   harness that runs sessions and turns, optionally inside an OpenAI-hosted or
   self-hosted sandbox, ergonomically modeled on openai-python's
   `client.beta.agents.*` resource tree. See
   https://developers.openai.com/api/docs/guides/agents-api/overview.

   SIBLING OF tools.agents.openai, NOT A PEER LIBRARY: this namespace takes
   the exact `OpenAIClient` record `tools.agents.openai/client` builds
   (:api-key, :base-url, :organization, :project, :max-retries all apply
   unchanged) and sends every request through that namespace's public
   transport, `tools.agents.openai/request!` (JSON codec, retry loop,
   per-attempt headers, error typing), rather than re-implementing or
   forking it. The Agents API lives
   under the SAME `https://api.openai.com/v1` root as Responses and Chat
   Completions — distinguished by a required `OpenAI-Beta: agents=v1` header
   this namespace adds to every request, and by the `/agents` path prefix
   (except vaults, which live at `/vaults` at the API root) — so a client
   built once with `tools.agents.openai/client` works
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
   plus the API reference's raw Markdown alternates (each reference HTML page
   advertises one via `<link rel=\"alternate\" type=\"text/markdown\">`):
     https://developers.openai.com/api/reference/resources/beta/subresources/
     agents/{methods/{create,retrieve,update,list,delete},subresources/...}/index.md
   Those pages carry literal endpoint text (\"**post** /agents\") and a
   `curl` example per method; their turns page matches the paging behaviour
   confirmed against the live API. openai-python's
   `src/openai/resources/beta/agents/**` (added in 1c4284a08294, 2026-09-10)
   ships the same paths.
   Every path and JSON shape below is one shown verbatim in a `curl` example
   or literal endpoint text on those pages. Saved/reusable agent CRUD
   (`agents-create` etc.), session artifacts and environment files are
   sourced from the reference pages; artifact content was verified live
   (2026-09-15) to arrive inline, not as a redirect.

   SCOPE — what this namespace covers:
     - Saved (reusable) agents: create, retrieve, update, list (paginated),
       delete.
     - Session lifecycle: create, retrieve, list (paginated), delete.
     - Turn input: send a message, steer or cancel the active turn, return a
       pending function-tool result.
     - Reading saved work: list a session's items (saved messages and tool
       calls) and pull assistant text out of them; list or retrieve its
       turns, which carry each turn's status and error.
     - OpenAI-hosted sandbox status: poll an environment's provisioning state.
     - Files: list and download (`byte[]`) a session's published artifacts,
       delete one; list a connected environment's files (token paging) and
       copy a file into it (inline base64 or a Files API id).
     - Environment templates: reusable OpenAI-hosted environment config —
       create, retrieve, update, list, delete.
     - Vaults and their MCP credentials: vault create, retrieve, list,
       delete; credential create, retrieve, rotate (update), list, delete.
       Secrets are write-only and never logged.
     - Self-hosted sandboxes: `sessions-create` passes `environment.type`
       \"self_hosted\" straight through like any other field, and
       `self-hosted-executor-command` turns a created session into the
       `codex exec-server` command line the docs show for connecting your own
       infrastructure — see that function's docstring for exactly what is and
       is not this library's job there.

   STREAMING: `sessions-events-stream` (GET .../sessions/{id}/events) and
   `sessions-create-stream` (POST /agents/sessions with \"stream\" true)
   return single-use reducibles of decoded events (tools.agents.stream);
   `await-root-turn` reduces one until the root turn finishes. Plain
   `sessions-create` still refuses `stream: true`
   (`:tools.agents.openai/streaming-unsupported`): its JSON transport cannot
   read an SSE body. Polling remains available: `sessions-retrieve`'s
   \"status\", then the turn (`sessions-turns-list` + `latest-root-turn`) and
   the output (`sessions-items-list`). Either way the session alone does not
   carry the outcome: \"idle\" means ready for input, not success. See
   docs/openai-agents.md's \"Streaming, or poll\" section.

   PORTABILITY: all network I/O goes through `tools.agents.openai/request!`
   and from there `tools.agents.http/request!`, the shared HTTP request
   function (GET/POST/DELETE here: sessions
   and items need GET, session deletion needs DELETE), which needs no reader
   conditional. Everything else is plain, portable clojure.core, exercised
   identically by both test runners.

   Runs unmodified on JVM Clojure and Babashka."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.openai :as oai]
            [tools.agents.stream :as stream]))

;; The one header this API adds on top of everything tools.agents.openai/client
;; already resolves (api-key, organization, project, base-url, max-retries).
;; Required on every request; the OpenAI SDKs add it automatically, quote from
;; the quickstart: "include it explicitly when using cURL."
(def ^:private beta-header "agents=v1")

;; ---------------------------------------------------------------------------
;; Request plumbing — path building and a thin adapter over
;; tools.agents.openai/request!, which owns the retry loop, per-attempt
;; headers and error typing for this namespace too.
;; ---------------------------------------------------------------------------

(defn- query-string
  "Turn a map of query params into \"?k=v&k2=v2\", or nil when nothing
   remains to encode. Encoding is tools.agents.http/encode-params: keys and
   values may be strings or keywords (`{\"order\" :desc}` -> `order=desc`),
   nested maps and vectors use bracket syntax, nil values are dropped."
  [params]
  (when-let [qs (http/encode-params params)]
    (str "?" qs)))

(defn- path-segment
  "URL-encode a caller-supplied path segment (a session or environment id)
   before splicing it into a URL — an id containing `/`, `?`, `#` or `%`
   (corrupted, or echoed from an untrusted source) must not silently reroute
   the request to a different path or endpoint."
  [id]
  (http/url-encode id))

(defn- reject-streaming!
  "Runs before tools.agents.openai/request!'s own streaming check so this
   namespace's refusal points at the streaming functions instead."
  [fn-name request]
  (when (or (true? (get request :stream)) (true? (get request "stream")))
    (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": :stream true is not supported — "
                          "this JSON transport cannot read an SSE body. Use `sessions-create-stream` / "
                          "`sessions-events-stream`, or poll `sessions-retrieve` — see docs/openai-agents.md.")
                     {:type :tools.agents.openai/streaming-unsupported}))))

;; status->type, extract-error-message and the retry loop are NOT duplicated
;; here: every request goes through tools.agents.openai/request!, so the two
;; namespaces can never drift apart. That is also why this namespace's thrown
;; :type is always :tools.agents.openai/…, never a `.agents`-suffixed keyword.

(defn- send-request!
  "Every resource method below calls this: `tools.agents.openai/request!`
   with the `OpenAI-Beta: agents=v1` header and messages labelled
   \"tools.agents.openai.agents/<fn-name>: \". `path` may already carry its
   query string (see `query-string`); newer methods pass `:query` in `opts`
   instead. `body-value` is the JSON request map, or nil for GET/DELETE. A 2xx
   with an empty body returns nil.

   opts (optional): :query (params map), :as (`request!`'s :json/:string/
   :bytes), :headers (merged over the beta header)."
  ([client fn-name method path body-value]
   (send-request! client fn-name method path body-value nil))
  ([client fn-name method path body-value {:keys [query as headers]}]
   (when (map? body-value) (reject-streaming! fn-name body-value))
   (oai/request! client (str "tools.agents.openai.agents/" fn-name)
                 (cond-> {:method  method
                          :path    path
                          :body    body-value
                          :headers (merge {"openai-beta" beta-header} headers)}
                   query (assoc :query query)
                   as    (assoc :as as)))))

;; ---------------------------------------------------------------------------
;; Public API — saved (reusable) agents
;;
;; Source: https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/{create,retrieve,update,list,delete}/index.md
;; and openai-python src/openai/resources/beta/agents/agents.py.
;; ---------------------------------------------------------------------------

(defn agents-create
  "POST {base-url}/agents — create a reusable agent (no credentials stored),
   the analogue of `client.beta.agents.create(**params)`. `request` is a
   plain map passed through to JSON verbatim:

     \"model\"         required
     \"instructions\"  appended to the default base instructions
     \"name\"          human-readable name
     \"metadata\"      up to 16 string pairs (key <= 64, value <= 512 chars)
     \"multi_agent\"   {\"enabled\" bool \"max_concurrent_subagents\" n}
     \"reasoning\"     {\"effort\" \"none\"|\"minimal\"|\"low\"|\"medium\"|
                      \"high\"|\"xhigh\"|\"max\"
                      \"summary\" \"concise\"|\"detailed\"|\"auto\"}
     \"service_tier\"  \"auto\"|\"default\"|\"flex\"|\"priority\"|\"fast\"
     \"text\"          {\"format\" {\"type\" \"text\"} or
                      {\"type\" \"json_schema\" \"schema\" {...}}
                      \"verbosity\" \"low\"|\"medium\"|\"high\"}
     \"tools\"         \"function\", \"tool_search\",
                      \"programmatic_tool_calling\", \"mcp\" (credential-free;
                      \"credential_id\" picks a vault credential) or
                      \"web_search\" entries

   Returns the `Agent`: \"id\", \"object\" \"agent\", \"created_at\",
   \"updated_at\" and every field above with server defaults resolved. Pass
   its \"id\" as `sessions-create`'s \"agent_id\". Throws ex-info on any
   failure, typed as `sessions-create` documents."
  [client request]
  (send-request! client "agents-create" :post "/agents" request))

(defn agents-retrieve
  "GET {base-url}/agents/{agent-id} — the analogue of
   `client.beta.agents.retrieve(agent_id)`. Returns the `Agent`. An unknown
   id throws `:tools.agents.openai/not-found-error`."
  [client agent-id]
  (send-request! client "agents-retrieve" :get (str "/agents/" (path-segment agent-id)) nil))

(defn agents-update
  "POST {base-url}/agents/{agent-id} — the analogue of
   `client.beta.agents.update(agent_id, **params)`. `request` takes the same
   fields as `agents-create`, all optional (\"model\" included) and passed
   through verbatim. Per the API reference: an omitted field is left
   unchanged; \"name\" and \"instructions\" set to nil clear them;
   \"metadata\" REPLACES all metadata (nil or {} clears it). Returns the
   updated `Agent`."
  [client agent-id request]
  (send-request! client "agents-update" :post (str "/agents/" (path-segment agent-id)) request))

(defn agents-list
  "GET {base-url}/agents — the project's reusable agents, the analogue of
   `client.beta.agents.list(**params)`. `params`, if given, is a plain map of
   query parameters: \"after\", \"limit\", \"order\" (\"asc\"|\"desc\",
   default \"desc\"). Page with `\"after\"` set to the previous page's
   `\"last_id\"` while `\"has_more\"` is true in the response, exactly as
   OpenAI's own cursor pagination works (keep \"order\" fixed across pages)."
  ([client] (agents-list client nil))
  ([client params]
   (send-request! client "agents-list" :get (str "/agents" (query-string params)) nil)))

(defn agents-delete
  "DELETE {base-url}/agents/{agent-id} — the analogue of
   `client.beta.agents.delete(agent_id)`. Returns
   {\"id\" ... \"deleted\" true \"object\" \"agent.deleted\"}. The reference
   does not document deleting an agent that sessions still reference."
  [client agent-id]
  (send-request! client "agents-delete" :delete (str "/agents/" (path-segment agent-id)) nil))

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
                    (an \"id\" from `agents-create` / `agents-list`)
     \"environment\" {\"type\" \"none\"|\"openai_hosted\"|\"self_hosted\" ...}
     \"input\"       a bare string, or an array of
                    {\"role\" \"user\" \"content\" [{\"type\" \"input_text\"
                    \"text\" ...}]} messages

   `:stream true` / `\"stream\" true` throws immediately, before any network
   request: use `sessions-create-stream` for that. The returned session's
   first turn (when `input` was given) keeps running asynchronously on
   OpenAI's side; poll `sessions-retrieve` until it settles, then read the
   outcome from `sessions-turns-list` (`latest-root-turn`).

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
   docs) and has no corresponding webhook. It may fail with HTTP 409
   (`:tools.agents.openai/conflict-error`) — seen live on a session that is
   not yet \"durably idle\" (e.g. just after a turn ended). To stop the current turn while
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
   running; starts a new turn on an idle session. When polling, an idle
   session can still read \"idle\" just after this returns, before the new
   turn starts. Note `latest-root-turn`'s \"id\" before sending and wait for a
   newer root turn to be `turn-finished?`, rather than trusting the
   session's status."
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
   `agent.session.requires_action` event from `sessions-events-stream`)."
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
;; Public API — turns
;; ---------------------------------------------------------------------------

(defn sessions-turns-list
  "GET {base-url}/agents/sessions/{session-id}/turns — the session's turns,
   newest first by default (confirmed against the live API; OpenAI's events
   guide links it only through the API reference). `params`,
   if given, is a plain map of query parameters, e.g. {\"order\" \"asc\"
   \"limit\" 20}; page with \"after\" = the previous page's \"last_id\" while
   \"has_more\" is true.

   This is where a polled turn's outcome lives. A session whose turn failed
   goes back to \"idle\" with a nil \"error\", and its items hold only the
   input — the failure is recorded only on the turn: \"status\" \"failed\"
   and \"error\" {\"code\" ... \"message\" ...} (e.g.
   \"credit_balance_exhausted\"). See `latest-root-turn`."
  ([client session-id] (sessions-turns-list client session-id nil))
  ([client session-id params]
   (send-request! client "sessions-turns-list" :get
                  (str "/agents/sessions/" (path-segment session-id) "/turns" (query-string params)) nil)))

(defn sessions-turns-retrieve
  "GET {base-url}/agents/sessions/{session-id}/turns/{turn-id} — one turn's
   \"status\" (\"queued\", \"in_progress\", \"waiting\", \"completed\",
   \"failed\", \"cancelled\"), timestamps, \"usage\" and \"error\".
   A turn id is the \"turn_id\" on the session's items or on a
   `required_actions` entry."
  [client session-id turn-id]
  (send-request! client "sessions-turns-retrieve" :get
                 (str "/agents/sessions/" (path-segment session-id) "/turns/" (path-segment turn-id)) nil))

(def ^:private finished-turn-statuses #{"completed" "failed" "cancelled"})

(defn turn-finished?
  "True when `turn` has reached a terminal status: \"completed\", \"failed\"
   or \"cancelled\"."
  [turn]
  (contains? finished-turn-statuses (get turn "status")))

(defn latest-root-turn
  "The newest root-agent turn (\"subagent_id\" nil) in a `sessions-turns-list`
   response's \"data\", or nil when there is none. Newest is the greatest
   numeric \"created_at\"; turns tied on it, or lacking it, keep list order,
   first wins — so with the API's default newest-first order a tie within the
   same second still picks the newer turn. Subagent turns are skipped: they
   do not decide the session's outcome.

   After polling a session out of \"created\"/\"in_progress\", this is the
   turn that just ran: check `turn-finished?`, its \"status\", and its
   \"error\". Throws `:tools.agents.openai/invalid-response` when \"data\" is
   not an array."
  [turns-response]
  (let [data (get turns-response "data")]
    (when-not (vector? data)
      (throw (ex-info "tools.agents.openai.agents/latest-root-turn: response has no \"data\" array"
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    (let [created-at (fn [turn] (let [t (get turn "created_at")] (if (number? t) t ##-Inf)))]
      (reduce (fn [best turn]
                (if (and (map? turn) (nil? (get turn "subagent_id"))
                         (or (nil? best) (> (created-at turn) (created-at best))))
                  turn
                  best))
              nil data))))

;; ---------------------------------------------------------------------------
;; Public API — event streaming
;;
;; Source: guide https://developers.openai.com/api/docs/guides/agents-api/sessions/events.md
;; (literal `curl -N ".../v1/agents/sessions/$session_id/events?stream=true"
;; -H "OpenAI-Beta: agents=v1" -H "Accept: text/event-stream"`, the
;; stream_session helper, "Recover a disconnected stream"); guide
;; .../agents-api/sessions.md ("Set `stream` to `true` to receive events from
;; the first turn in the same request", literal `curl --no-buffer` POST);
;; reference .../agents/subresources/sessions/subresources/events/methods/stream/index.md
;; (the 30-variant `AgentSessionEvent` union); openai-python
;; src/openai/resources/beta/agents/sessions/events.py (`stream=True`,
;; `Accept: text/event-stream`, no `?stream=true` — this sends both).
;; Frame details the docs do not show (an `event:` line, a `[DONE]` sentinel)
;; are tolerated: dispatch is on the data's "type", and `[DONE]` ends the
;; stream without reaching the JSON decoder.
;; ---------------------------------------------------------------------------

(defn- decode-event-data
  "An event's `data`: blank (a keep-alive with an empty data line) becomes
   ::keep-alive and is dropped by `event-xform`; anything else is JSON."
  [codec data]
  (if (str/blank? data) ::keep-alive ((:read codec) data)))

(def ^:private event-xform
  (comp (remove #(= ::keep-alive (:data %)))
        (map (fn [{:keys [event id data]}]
               (if (map? data)
                 (with-meta data {:tools.agents.sse/event event :tools.agents.sse/id id})
                 data)))))

(defn- open-stream
  "Open one SSE request through tools.agents.openai/request! (`:as :stream`):
   the same URL building, per-attempt headers, retry policy
   (`should-retry?`/`retry-delay-ms`, the client's :max-retries) and
   `status->type` error typing as every other method here, applied before
   the first byte only."
  [client fn-name req]
  (let [label (str "tools.agents.openai.agents/" fn-name)
        codec (oai/client-codec client)]
    (stream/open-event-stream
     {:request       (assoc req :headers {"openai-beta" beta-header "accept" "text/event-stream"})
      :send!         (fn [r] (oai/request! client label r))
      ;; request! already throws a typed error for a final non-2xx; this is
      ;; the same typing in case a response ever reaches open-event-stream.
      :on-error      (fn [{:keys [status body]}]
                       (let [msg (oai/extract-error-message codec body)]
                         (throw (ex-info (str label ": HTTP " status (when (or msg (seq body)) (str " " (or msg body))))
                                         {:type (oai/status->type status) :status status :body body}))))
      :on-read-error (fn [e]
                       (ex-info (str label ": stream read failed: " e)
                                {:type :tools.agents.openai/api-connection-error :status nil :body nil}
                                e))
      :done?         #(str/starts-with? (str (:data %)) "[DONE]")
      ;; An error event throws here, before the reducer sees it: openai-python
      ;; streams these endpoints through the generic Stream, whose __stream__
      ;; raises APIError on any data with a truthy top-level "error".
      :decode        (fn [data]
                       (let [ev (decode-event-data codec data)]
                         (when-let [e (oai/stream-event-error label ev data)] (throw e))
                         ev))
      :xform         event-xform})))

(defn sessions-events-stream
  "GET {base-url}/agents/sessions/{session-id}/events?stream=true — live
   events for a session, the analogue of
   `client.beta.agents.sessions.events.stream(session_id)`. Sends
   `Accept: text/event-stream` and `OpenAI-Beta: agents=v1`. `params`, if
   given, are extra query parameters (\"stream\" is always \"true\").

   The request is sent NOW: retries (the client's :max-retries, openai's
   policy) and HTTP errors happen inside this call and throw the same
   `:tools.agents.openai/*` keywords as `sessions-retrieve`. Returns a
   SINGLE-USE reducible (tools.agents.stream) of events: each the decoded
   JSON map exactly as sent (string keys; dispatch on \"type\" — 30 documented
   types, unknown ones pass through), with the SSE frame's name and id as
   metadata `:tools.agents.sse/event` / `:tools.agents.sse/id`.

   An `error` event (any event with a truthy top-level \"error\", as
   openai-python's Stream raises APIError for) is NOT delivered: the reduce
   throws `:tools.agents.openai/stream-error` (ex-data :status nil, :body the
   raw data, :error the error object, :event the decoded event) and closes
   the connection; events before it have reached the reducer. A failed turn
   was observed live sending both `error` and `agent.session.turn.failed`;
   whichever comes first is what `await-root-turn` throws, so read the turn
   (`sessions-turns-list`) for its status.

     (let [s (sessions-events-stream client sid)]   ; subscribe first,
       (send-message client sid \"Summarize the repo\") ; then send work
       (await-root-turn s {:on-event #(some-> (get % \"delta\") print)}))

   The reduce closes the connection on completion, early termination
   (`reduced`, `(into [] (take n) s)`) and exceptions; a stream never reduced
   must be released with `tools.agents.stream/close!`, which is also the
   cross-thread cancel. A second reduce throws
   `:tools.agents.stream/consumed`. A connection failure mid-stream throws
   `:tools.agents.openai/api-connection-error` from the reduce; it is never
   retried (the API does not replay missed events).

   TRUNCATION: a body that simply ends reduces like a complete one. After the
   reduce, `(tools.agents.stream/outcome s)` is :eof when the server closed
   the stream, :reduced when the reducing fn stopped, :cancelled after
   `close!`; whether the turn ended is only known from its events
   (`root-turn-finished?`). `await-root-turn` turns an end without a root
   terminal event into `:tools.agents.openai/stream-truncated`. To recover:
   open a new stream, then reconcile from `sessions-retrieve` and
   `sessions-items-list` by \"item_id\" (guide, \"Recover a disconnected
   stream\")."
  ([client session-id] (sessions-events-stream client session-id nil))
  ([client session-id params]
   (open-stream client "sessions-events-stream"
                {:method :get
                 :path   (str "/agents/sessions/" (path-segment session-id) "/events")
                 :query  (assoc (dissoc params :stream) "stream" "true")})))

(defn sessions-create-stream
  "POST {base-url}/agents/sessions with \"stream\" true — create a session
   and receive its first turn's events in the same response, the analogue of
   `client.beta.agents.sessions.create(..., stream=True)`. `request` is the
   `sessions-create` map; \"stream\" is set here (a keyword `:stream` key is
   dropped). Same contract as `sessions-events-stream`: sent now, retried
   before the first byte, returns a single-use reducible of decoded events,
   starting with `agent.session.created` (which carries the session and its
   \"id\")."
  [client request]
  (open-stream client "sessions-create-stream"
               {:method :post
                :path   "/agents/sessions"
                :body   (assoc (dissoc request :stream) "stream" true)}))

(def ^:private root-turn-terminal-types
  #{"agent.session.turn.completed" "agent.session.turn.failed" "agent.session.turn.cancelled"})

(defn root-turn-finished?
  "True for the event that ends a ROOT turn: `agent.session.turn.completed`,
   `.failed` or `.cancelled` whose \"turn\" has a nil \"subagent_id\". Subagent
   turn events do not end the root turn (events guide). The event's \"turn\"
   then satisfies `turn-finished?`; only `.completed` is success."
  [event]
  (boolean (and (map? event)
                (contains? root-turn-terminal-types (get event "type"))
                (nil? (get-in event ["turn" "subagent_id"])))))

(defn- event-failure
  "The ex-info the events guide's helper raises for `event`, or nil."
  [event]
  (let [t     (get event "type")
        label "tools.agents.openai.agents/await-root-turn"
        ;; The same predicate and ex-data the stream throws with, checked
        ;; first: over a stream such an event never reaches the specific
        ;; branches below (it throws in decode), so a collection must agree.
        err   (oai/stream-event-error label event nil)
        fail  (fn [kw msg extra]
                (ex-info (str label ": " msg)
                         (merge {:type kw :status nil :body nil :event event} extra)))]
    (cond
      (not (map? event)) nil

      err err

      (= "agent.session.failed" t)
      (fail :tools.agents.openai/session-failed
            (str t (some->> (get-in event ["session" "error"]) (str ": ")))
            {:error (get-in event ["session" "error"])})

      (= "agent.session.environment.failed" t)
      (fail :tools.agents.openai/session-failed
            (str t (some->> (get-in event ["environment" "error" "message"]) (str ": ")))
            {:error (get-in event ["environment" "error"])})

      (and (= "agent.session.turn.failed" t) (root-turn-finished? event))
      (fail :tools.agents.openai/turn-failed
            (str t (some->> (get-in event ["turn" "error" "message"]) (str ": ")))
            {:turn (get event "turn") :error (get-in event ["turn" "error"])})

      (and (= "agent.session.turn.cancelled" t) (root-turn-finished? event))
      (fail :tools.agents.openai/turn-cancelled "the agent turn was cancelled"
            {:turn (get event "turn")}))))

(defn await-root-turn
  "Reduce `events` (a `sessions-events-stream` / `sessions-create-stream`
   reducible, or any collection of decoded events) until the root turn ends,
   and return its `agent.session.turn.completed` event: \"turn\" (status,
   timestamps, error) and \"usage\". Stops reading at that event, which
   closes a stream. The alternative to polling `sessions-turns-list`.

   Follows the events guide's helper: `agent.session.idle` and every other
   event continue; subagent turn events never end the wait. Throws ex-info
   (carrying `:event`, plus `:status nil :body nil`):

     root agent.session.turn.failed      :tools.agents.openai/turn-failed
                                         (:turn, :error = turn.error)
     root agent.session.turn.cancelled   :tools.agents.openai/turn-cancelled
     agent.session.failed,
     agent.session.environment.failed    :tools.agents.openai/session-failed
     error                               :tools.agents.openai/stream-error
                                         (:error)
     end of events before a root turn    :tools.agents.openai/stream-truncated
     ended                               (:outcome = the stream's outcome —
                                         :eof, :cancelled — or nil for a coll)

   Over a stream, the `error` row is thrown by the stream itself (message
   prefix the stream fn's, same :type/:error/:event) before the event
   reaches :on-event; the row applies as written to a plain collection.

   opts: :on-event (fn [event]) called for every delivered event first,
   including the one that ends or fails the wait (e.g. to print deltas).

   `agent.session.requires_action` does not end the wait: a caller with
   function tools must answer it (`send-tool-result`) from :on-event, or the
   root turn never finishes.

   Subscribe before sending work: a turn already finished before the stream
   opened is never seen and ends as stream-truncated only when the server
   closes. A completed turn does not guarantee every tool succeeded — read
   the output (`sessions-items-list`)."
  ([events] (await-root-turn events nil))
  ([events {:keys [on-event]}]
   (let [result (reduce (fn [_ event]
                          (when on-event (on-event event))
                          (when-let [e (event-failure event)] (throw e))
                          (when (and (root-turn-finished? event)
                                     (= "agent.session.turn.completed" (get event "type")))
                            (reduced event)))
                        nil events)]
     (or result
         (let [outcome (when (satisfies? stream/EventStream events) (stream/outcome events))]
           (throw (ex-info (str "tools.agents.openai.agents/await-root-turn: events ended before the root turn "
                                "finished (outcome " (pr-str outcome) "); retrieve the saved state")
                           {:type :tools.agents.openai/stream-truncated :status nil :body nil
                            :outcome outcome})))))))

;; ---------------------------------------------------------------------------
;; Public API — environments (OpenAI-hosted sandbox status)
;; ---------------------------------------------------------------------------

(defn environments-retrieve
  "GET {base-url}/agents/environments/{environment-id} — poll a
   sandbox's connection state, using the session's own `environment.id`
   (from `sessions-create`'s response, or `sessions-retrieve` thereafter).
   \"status\" is one of \"pending\", \"connected\", \"disconnected\",
   \"expired\" or \"failed\" (API reference); \"connected\" means the agent
   can run commands. The response also carries \"type\" (\"openai_hosted\"
   or \"self_hosted\") and the installed \"files\", \"plugins\" and
   \"skills\" (metadata only). It does not carry a failure reason: read
   `environment.error` off the `agent.session.environment.failed` event."
  [client environment-id]
  (send-request! client "environments-retrieve" :get (str "/agents/environments/" (path-segment environment-id)) nil))

;; ---------------------------------------------------------------------------
;; Public API — session artifacts
;;
;; Source: https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/artifacts/methods/{list,retrieve,content,delete}/index.md,
;; guide https://developers.openai.com/api/docs/guides/agents-api/environments/files.md
;; ("From an OpenAI-hosted environment"), and openai-python
;; src/openai/resources/beta/agents/sessions/artifacts.py.
;; ---------------------------------------------------------------------------

(defn- artifacts-path [session-id]
  (str "/agents/sessions/" (path-segment session-id) "/artifacts"))

(defn sessions-artifacts-list
  "GET {base-url}/agents/sessions/{session-id}/artifacts — the immutable
   artifacts published by the session's completed turns, the analogue of
   `client.beta.agents.sessions.artifacts.list(session_id, **params)`.
   `params`, if given, is a plain map of query parameters: \"after\",
   \"limit\" (1-100), \"order\" (\"asc\"|\"desc\", default \"desc\"),
   \"environment_id\". Page with `\"after\"` set to the previous page's
   `\"last_id\"` while `\"has_more\"` is true, keeping \"order\" and filters
   fixed — the same cursor paging as `sessions-list`.

   Each `SessionArtifact` carries \"id\", \"created_at\", \"environment_id\",
   \"path\", \"session_id\", \"size_bytes\" and \"turn_id\". Only
   `openai_hosted` environments publish artifacts, only files under
   `/workspace/outputs`, and only when a turn completes. A new version of a
   path is a new artifact: match on both \"turn_id\" and \"path\"."
  ([client session-id] (sessions-artifacts-list client session-id nil))
  ([client session-id params]
   (send-request! client "sessions-artifacts-list" :get (artifacts-path session-id) nil
                  {:query params})))

(defn sessions-artifacts-retrieve
  "GET {base-url}/agents/sessions/{session-id}/artifacts/{artifact-id} — one
   artifact's metadata (the `SessionArtifact` shape `sessions-artifacts-list`
   documents), the analogue of
   `client.beta.agents.sessions.artifacts.retrieve(artifact_id, session_id=...)`."
  [client session-id artifact-id]
  (send-request! client "sessions-artifacts-retrieve" :get
                 (str (artifacts-path session-id) "/" (path-segment artifact-id)) nil))

(defn sessions-artifacts-content
  "GET {base-url}/agents/sessions/{session-id}/artifacts/{artifact-id}/content
   — the artifact's raw bytes as a `byte[]`, undecoded, the analogue of
   `client.beta.agents.sessions.artifacts.content(artifact_id, session_id=...)`.
   Works after the environment expires. Sends `Accept:
   application/octet-stream`, as the SDK does. Write it out with
   `(clojure.java.io/copy bytes (clojure.java.io/file dest))`.

   Held fully in memory: an artifact is capped at 200 MiB (guide, \"File
   limits\"). There is no streaming variant yet.

   A non-2xx throws like every other method here; its body is decoded as
   UTF-8 for the message (an unknown id is
   `:tools.agents.openai/not-found-error`).

   REDIRECTS ARE NOT FOLLOWED. The reference documents no redirect for this
   endpoint (its page shows only a `curl` without `-L`). openai-python's httpx
   client follows redirects by default, but this library's shared
   `java.net.http.HttpClient` (`tools.agents.http/client`, used on both JVM
   Clojure and Babashka) keeps the JDK default `Redirect.NEVER`. If the API
   ever answers with a 3xx to a signed URL, this throws
   `:tools.agents.openai/api-status-error` with that `:status`. Not yet
   verified against the live API."
  [client session-id artifact-id]
  (send-request! client "sessions-artifacts-content" :get
                 (str (artifacts-path session-id) "/" (path-segment artifact-id) "/content") nil
                 {:as :bytes :headers {"accept" "application/octet-stream"}}))

(defn sessions-artifacts-delete
  "DELETE {base-url}/agents/sessions/{session-id}/artifacts/{artifact-id} —
   deletes the published copy only (the live environment file and any Files
   API object stay), the analogue of
   `client.beta.agents.sessions.artifacts.delete(artifact_id, session_id=...)`.
   Returns {\"id\" ... \"deleted\" true \"object\"
   \"agent.session.artifact.deleted\"}."
  [client session-id artifact-id]
  (send-request! client "sessions-artifacts-delete" :delete
                 (str (artifacts-path session-id) "/" (path-segment artifact-id)) nil))

;; ---------------------------------------------------------------------------
;; Public API — environment files
;;
;; Source: https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/environments/subresources/files/methods/{list,create}/index.md
;; and openai-python src/openai/resources/beta/agents/environments/files.py.
;; The API has no retrieve, delete or content endpoint for environment files.
;; ---------------------------------------------------------------------------

(defn- environment-files-path [environment-id]
  (str "/agents/environments/" (path-segment environment-id) "/files"))

(defn environments-files-list
  "GET {base-url}/agents/environments/{environment-id}/files — live files on
   a connected environment, the analogue of
   `client.beta.agents.environments.files.list(environment_id, **params)`.
   `params`, if given, is a plain map of query parameters: \"limit\"
   (1-100), \"order\" (\"asc\"|\"desc\", default \"desc\", by case-sensitive
   path components), \"path\" (an absolute workspace directory filter),
   \"page\".

   TOKEN PAGING, NOT the `\"after\"` cursor the other list methods use. The
   response is {\"object\" \"page\" \"data\" [EnvironmentFile ...] \"has_more\"
   bool \"next\" token-or-nil}. While \"has_more\" is true, pass \"page\" =
   the previous response's \"next\", keeping \"path\", \"order\" and \"limit\"
   unchanged. Each `EnvironmentFile` carries \"environment_id\", \"path\" and
   \"size_bytes\"."
  ([client environment-id] (environments-files-list client environment-id nil))
  ([client environment-id params]
   (send-request! client "environments-files-list" :get (environment-files-path environment-id) nil
                  {:query params})))

(defn- base64-data
  "An inline file's \"data\": a String passes through as already
   standard-base64; a byte[], java.io.File or java.nio.file.Path is read and
   encoded with java.util.Base64's standard encoder."
  [data]
  (let [encode (fn [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))]
    (cond
      (string? data)                       data
      (bytes? data)                        (encode data)
      (instance? java.io.File data)        (encode (java.nio.file.Files/readAllBytes (.toPath ^java.io.File data)))
      (instance? java.nio.file.Path data)  (encode (java.nio.file.Files/readAllBytes data))
      :else
      (throw (ex-info (str "tools.agents.openai.agents/environments-files-create: \"data\" must be a "
                           "base64 String, byte[], java.io.File or java.nio.file.Path, got "
                           (some-> data class .getName))
                      {:type :tools.agents.openai/invalid-request :status nil :body nil})))))

(defn environments-files-create
  "POST {base-url}/agents/environments/{environment-id}/files — copy a file
   into a CONNECTED environment, the analogue of
   `client.beta.agents.environments.files.create(environment_id, **params)`.
   The body is JSON, not multipart. `request` is one of:

     {\"type\" \"inline\"  \"path\" \"/workspace/in.txt\" \"data\" data}
     {\"type\" \"file_id\" \"path\" \"/workspace/in.pdf\" \"file_id\" \"file-...\"}

   \"path\" is the absolute destination inside `/workspace`. \"data\" is
   either a ready standard-base64 String (sent verbatim) or raw content — a
   `byte[]`, `java.io.File` or `java.nio.file.Path` — that this function reads
   and base64-encodes before sending. Any other \"data\" throws
   `:tools.agents.openai/invalid-request` before any network I/O. Every
   other field passes through verbatim.

   Limits (guide, \"File limits\"; enforced by the API, not here): inline
   5 MiB per file before encoding, a Files API copy 50 MiB. Returns the
   `EnvironmentFile`: \"environment_id\", \"object\"
   \"agent.environment.file\", \"path\", \"size_bytes\"."
  [client environment-id request]
  (send-request! client "environments-files-create" :post (environment-files-path environment-id)
                 (cond-> request
                   (contains? request "data") (update "data" base64-data))))

;; ---------------------------------------------------------------------------
;; Public API — environment templates
;;
;; Source: https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/environments/subresources/templates/methods/{create,retrieve,update,list,delete}/index.md
;; and openai-python src/openai/resources/beta/agents/environments/templates.py.
;; ---------------------------------------------------------------------------

(defn- templates-path
  ([] "/agents/environments/templates")
  ([template-id] (str (templates-path) "/" (path-segment template-id))))

(defn environments-templates-create
  "POST {base-url}/agents/environments/templates — create reusable
   OpenAI-hosted environment configuration, the analogue of
   `client.beta.agents.environments.templates.create(**params)`. Every field
   is optional and passed through verbatim:

     \"name\"                    display name
     \"capability_directories\"  directories exposing capabilities
     \"env\"                     {string string}, confidential (never returned)
     \"files\"                   [{\"type\" \"inline\" \"path\" .. \"data\" <base64 String>}
                                 {\"type\" \"file_id\" \"path\" .. \"file_id\" ..}]
     \"network\"                 {\"access\" \"enabled\"|\"disabled\"|\"restricted\"
                                 \"allowed_domains\" [..]}
     \"packages\"                {\"npm\" [..] \"python\" [..] \"system\" [..]}
     \"plugins\"                 [{\"type\" \"inline\" \"name\" .. \"description\" ..
                                  \"source\" {\"type\" \"base64\"
                                            \"media_type\" \"application/zip\" \"data\" ..}}]
     \"setup_commands\"          [{\"command\" .. \"cwd\" ..}], confidential
     \"skills\"                  [{\"type\" \"skill_reference\" \"skill_id\" .. \"version\" ..}
                                 or an inline ZIP like \"plugins\"]

   Unlike `environments-files-create`, \"files\" \"data\" is NOT encoded
   here: pass a standard-base64 String.

   Returns the `EnvironmentTemplate`: \"id\", \"object\"
   \"agent.environment.template\", \"created_at\", \"updated_at\", and the
   fields above as safe metadata — no \"env\", no \"setup_commands\", inline
   files as \"path\"/\"size_bytes\" only. Use its \"id\" as
   `sessions-create`'s `environment` {\"type\" \"openai_hosted\"
   \"environment_template_id\" ..}; inline session fields then override it
   (a network override cannot broaden the template's policy)."
  ([client] (environments-templates-create client nil))
  ([client request]
   (send-request! client "environments-templates-create" :post (templates-path) request)))

(defn environments-templates-retrieve
  "GET {base-url}/agents/environments/templates/{template-id} — the analogue
   of `client.beta.agents.environments.templates.retrieve(environment_template_id)`.
   Returns the `EnvironmentTemplate` without confidential values."
  [client template-id]
  (send-request! client "environments-templates-retrieve" :get (templates-path template-id) nil))

(defn environments-templates-update
  "POST {base-url}/agents/environments/templates/{template-id} — the analogue
   of `client.beta.agents.environments.templates.update(environment_template_id, **params)`.
   `request` takes `environments-templates-create`'s fields, passed through
   verbatim. The reference documents \"env\", \"files\", \"plugins\",
   \"setup_commands\" and \"skills\" as REPLACEMENTS, and \"name\" nil as
   clearing the name; it does not say what an omitted field does. Returns
   the updated `EnvironmentTemplate`."
  [client template-id request]
  (send-request! client "environments-templates-update" :post (templates-path template-id) request))

(defn environments-templates-list
  "GET {base-url}/agents/environments/templates — the analogue of
   `client.beta.agents.environments.templates.list(**params)`. `params`, if
   given: \"after\", \"limit\" (1-100, default 20), \"order\" (\"asc\"|\"desc\",
   default \"desc\"). CURSOR PAGING (`SyncCursorPage`, not the token paging of
   `environments-files-list`): pass \"after\" = the previous page's
   \"last_id\" while \"has_more\" is true."
  ([client] (environments-templates-list client nil))
  ([client params]
   (send-request! client "environments-templates-list" :get (templates-path) nil {:query params})))

(defn environments-templates-delete
  "DELETE {base-url}/agents/environments/templates/{template-id} — the
   analogue of `client.beta.agents.environments.templates.delete(environment_template_id)`.
   Returns {\"id\" ... \"deleted\" true \"object\"
   \"agent.environment.template.deleted\"}."
  [client template-id]
  (send-request! client "environments-templates-delete" :delete (templates-path template-id) nil))

;; ---------------------------------------------------------------------------
;; Public API — vaults and vault credentials
;;
;; Source: https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/vaults/methods/{create,retrieve,list,delete}/index.md,
;; .../vaults/subresources/credentials/methods/{create,retrieve,update,list,delete}/index.md,
;; and openai-python src/openai/resources/beta/agents/vaults/{vaults,credentials}.py.
;; The paths are `/vaults/...` at the API root, NOT under `/agents`, although
;; the SDK nests them at `client.beta.agents.vaults` and they still require
;; `OpenAI-Beta: agents=v1`. There is no vault update endpoint.
;;
;; SECRETS: credential create/update bodies carry write-only secrets
;; (`token`, `access_token`, `refresh_token`, `client_secret`). They are
;; passed through verbatim and never logged or copied into an exception:
;; tools.agents.openai/request! puts only the RESPONSE body in ex-data.
;; ---------------------------------------------------------------------------

(defn- vault-path [vault-id]
  (str "/vaults/" (path-segment vault-id)))

(defn- credentials-path [vault-id]
  (str (vault-path vault-id) "/credentials"))

(defn vaults-create
  "POST {base-url}/vaults — create a vault for the current project, the
   analogue of `client.beta.agents.vaults.create(**params)`. `request`, if
   given, is passed through verbatim; both fields are optional:

     \"name\"      1-256 UTF-8 bytes after trimming
     \"metadata\"  string key-value pairs (e.g. an application or team id)

   Returns the `Vault`: \"id\", \"object\" \"vault\", \"created_at\",
   \"name\" (or nil), \"metadata\". A vault groups credentials that agent MCP
   tools authenticate with: pass vault ids as `sessions-create`'s
   \"vault_ids\", and a credential id as an MCP tool's \"credential_id\"."
  ([client] (vaults-create client nil))
  ([client request]
   (send-request! client "vaults-create" :post "/vaults" request)))

(defn vaults-retrieve
  "GET {base-url}/vaults/{vault-id} — the analogue of
   `client.beta.agents.vaults.retrieve(vault_id)`. Returns the `Vault`."
  [client vault-id]
  (send-request! client "vaults-retrieve" :get (vault-path vault-id) nil))

(defn vaults-list
  "GET {base-url}/vaults — the analogue of
   `client.beta.agents.vaults.list(**params)`. `params`, if given, is a plain
   map of query parameters: \"after\", \"limit\" (default 20, clamped to
   1-100), \"order\" (\"asc\"|\"desc\" by \"created_at\", default \"desc\"),
   \"status\" (\"active\" or \"archived\", or a vector of both, sent as
   `status[]=active&status[]=archived`; both are included by default).

   CURSOR PAGING (`SyncCursorPage`): the response is {\"object\" \"list\"
   \"data\" [...] \"first_id\" .. \"last_id\" .. \"has_more\" bool}; page with
   \"after\" = the previous page's \"last_id\" while \"has_more\" is true,
   keeping \"order\" and \"status\" fixed."
  ([client] (vaults-list client nil))
  ([client params]
   (send-request! client "vaults-list" :get "/vaults" nil {:query params})))

(defn vaults-delete
  "DELETE {base-url}/vaults/{vault-id} — deletes the vault AND all its
   credentials, the analogue of `client.beta.agents.vaults.delete(vault_id)`.
   Returns {\"id\" ... \"deleted\" true \"object\" \"vault.deleted\"}."
  [client vault-id]
  (send-request! client "vaults-delete" :delete (vault-path vault-id) nil))

(defn vaults-credentials-create
  "POST {base-url}/vaults/{vault-id}/credentials — store an MCP server
   credential, the analogue of
   `client.beta.agents.vaults.credentials.create(vault_id, auth=, name=)`.
   `request` is passed through verbatim:

     \"name\"  required, 1-256 UTF-8 bytes after trimming
     \"auth\"  required, one of
       {\"type\" \"static_bearer\" \"mcp_server_url\" \"https://...\"
        \"token\" <secret>}
       {\"type\" \"mcp_oauth\" \"mcp_server_url\" \"https://...\"
        \"access_token\" <secret>
        \"expires_at\" \"<RFC 3339>\"                        ; optional
        \"refresh\" {\"client_id\" .. \"refresh_token\" <secret>   ; optional
                   \"token_endpoint\" \"https://...\"
                   \"token_endpoint_auth\" {\"type\" \"none\"} |
                     {\"type\" \"client_secret_basic\"|\"client_secret_post\"
                      \"client_secret\" <secret>}
                   \"resource\" ..  \"scope\" ..}}              ; optional

   Secrets are write-only: the returned `Credential` (\"id\", \"object\"
   \"vault.credential\", \"vault_id\", \"name\", \"created_at\",
   \"updated_at\", \"auth\") carries only the non-secret \"auth\" fields —
   no \"token\", \"access_token\", \"refresh_token\" or \"client_secret\".
   This function never logs `request` and never puts it in an exception; a
   non-2xx error carries only the server's response body."
  [client vault-id request]
  (send-request! client "vaults-credentials-create" :post (credentials-path vault-id) request))

(defn vaults-credentials-retrieve
  "GET {base-url}/vaults/{vault-id}/credentials/{credential-id} — credential
   metadata without secret values, the analogue of
   `client.beta.agents.vaults.credentials.retrieve(credential_id, vault_id=)`."
  [client vault-id credential-id]
  (send-request! client "vaults-credentials-retrieve" :get
                 (str (credentials-path vault-id) "/" (path-segment credential-id)) nil))

(defn vaults-credentials-update
  "POST {base-url}/vaults/{vault-id}/credentials/{credential-id} — ROTATE a
   credential's secret, the analogue of
   `client.beta.agents.vaults.credentials.update(credential_id, vault_id=, auth=)`.
   `request` is {\"auth\" ...}, passed through verbatim; \"auth\" \"type\"
   must be the credential's existing method (the name and MCP server URL are
   not updatable here):

     {\"type\" \"static_bearer\" \"token\" <secret>}             ; token required
     {\"type\" \"mcp_oauth\"
      \"access_token\" <secret>                 ; optional
      \"expires_at\" \"<RFC 3339>\" or nil        ; nil clears; omitted keeps it,
                                               ; unless a new access_token is sent
      \"refresh\" {\"refresh_token\" <secret>     ; omitted/nil keeps the stored one
                 \"scope\" ..                   ; nil stops sending a scope
                 \"token_endpoint_auth\"
                 {\"type\" \"client_secret_basic\"|\"client_secret_post\"
                  \"client_secret\" <secret>}}}  ; omitted/nil keeps it

   Returns the `Credential` metadata, never the secrets. Same no-logging
   guarantee as `vaults-credentials-create`."
  [client vault-id credential-id request]
  (send-request! client "vaults-credentials-update" :post
                 (str (credentials-path vault-id) "/" (path-segment credential-id)) request))

(defn vaults-credentials-list
  "GET {base-url}/vaults/{vault-id}/credentials — the analogue of
   `client.beta.agents.vaults.credentials.list(vault_id, **params)`, without
   secret values. Same params and CURSOR PAGING as `vaults-list`: \"after\",
   \"limit\", \"order\", \"status\"."
  ([client vault-id] (vaults-credentials-list client vault-id nil))
  ([client vault-id params]
   (send-request! client "vaults-credentials-list" :get (credentials-path vault-id) nil
                  {:query params})))

(defn vaults-credentials-delete
  "DELETE {base-url}/vaults/{vault-id}/credentials/{credential-id} — the
   analogue of `client.beta.agents.vaults.credentials.delete(credential_id, vault_id=)`.
   Returns {\"id\" ... \"deleted\" true \"object\" \"vault.credential.deleted\"}."
  [client vault-id credential-id]
  (send-request! client "vaults-credentials-delete" :delete
                 (str (credentials-path vault-id) "/" (path-segment credential-id)) nil))

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
