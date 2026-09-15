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
   sourced from the reference pages; artifact content transport (inline
   bytes vs. redirect) is not yet verified live.

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
   Follow a turn by polling `sessions-retrieve` (its `\"status\"`), then read
   the outcome off the turn (`sessions-turns-list` + `latest-root-turn`) and
   the output off `sessions-items-list`. The session alone does not carry
   the outcome: \"idle\" means ready for input, not that the turn succeeded.
   See docs/openai-agents.md's \"Streaming is not supported — poll instead\"
   section and `examples/openai/agents_sandbox_task.clj`.

   PORTABILITY: all network I/O goes through `tools.agents.openai/request!`
   and from there `tools.agents.http/request!`, the shared HTTP request
   function (GET/POST/DELETE here: sessions
   and items need GET, session deletion needs DELETE), which needs no reader
   conditional. Everything else is plain, portable clojure.core, exercised
   identically by both test runners.

   Runs unmodified on JVM Clojure and Babashka."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.openai :as oai]))

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
   namespace's refusal points at polling instead."
  [fn-name request]
  (when (or (true? (get request :stream)) (true? (get request "stream")))
    (throw (ex-info (str "tools.agents.openai.agents/" fn-name ": :stream true is not supported — "
                          "SSE streaming is not implemented by this client. Poll `sessions-retrieve` / "
                          "`sessions-items-list` instead — see docs/openai-agents.md.")
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
   request — see the ns docstring's streaming note. The returned session's
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
