(ns tools.agents.anthropic.agents
  "A pure-Clojure client for Claude Managed Agents (beta) — Anthropic's hosted
   agent harness: versioned agents, environments (the sandbox configuration),
   and sessions driven by events — modeled on anthropic-sdk-python's
   `client.beta.{agents,environments,sessions}` resource tree. See
   https://platform.claude.com/docs/en/managed-agents/overview.

   SIBLING OF tools.agents.anthropic, in the way tools.agents.openai.agents
   is of tools.agents.openai: this namespace takes the exact `AnthropicClient`
   record `tools.agents.anthropic/client` builds and sends every request
   through that namespace's public transport — `request!` for JSON calls,
   `open-stream!` for the event stream — so credentials, the
   `anthropic-version` header, the retry policy and the error typing
   (`:tools.agents.anthropic.error/*`) are the same code path. It adds two
   things to each request: the `managed-agents-2026-04-01` beta flag, appended
   to the client's own `:betas` (so an OAuth client's `oauth-2025-04-20` and a
   caller's flags all reach the one `anthropic-beta` header), and the
   `beta=true` query parameter every SDK path carries.

   WIRE FORMAT — read on 2026-10-09 from Anthropic's raw `.md` documentation
   pages and the SDK source, not from any model's prior knowledge:
     https://platform.claude.com/docs/en/managed-agents/{overview,quickstart,
     agent-setup,environments,sessions,session-operations,
     events-and-streaming,event-deltas,tools,budgets,reference}.md
     https://platform.claude.com/docs/en/api/beta/{agents,environments,
     sessions}/<method>.md and .../sessions/events/{send,list,stream}.md
     anthropic-sdk-python src/anthropic/resources/beta/{agents,environments,
     sessions}/** and src/anthropic/_streaming.py at b4b7916.
   NOT VERIFIED LIVE: no key with Managed Agents access was available. Every
   path and JSON shape is one those pages show; none has been exercised
   against the real API. See docs/anthropic-agents.md.

   SCOPE:
     - Agents: create, retrieve (optionally a version), update, list,
       archive, list versions.
     - Environments: create, retrieve, update, list, delete, archive.
     - Sessions: create, retrieve, update, list, delete, archive.
     - Session events: send, list (history), stream (SSE).
     - Event helpers: `send-message`, `interrupt`,
       `send-custom-tool-result`, `confirm-tool`.
     - Reading a stream: `await-idle` reduces events to the
       `session.status_idle` that ends the turn; `resume-stream` is the
       documented reconnect (new stream + history + skip seen ids).
     - Paging: `next-page-params` / `list-all` over the `page`/`next_page`
       cursor every list endpoint here uses.
   Not covered: session threads, session resources, vaults and credentials,
   the self-hosted work queue (`/v1/environments/{id}/work`), memory stores,
   skills, deployments, webhooks.

   Requests and responses are plain maps with string keys, passed through
   verbatim. Portable clojure.core only: runs unmodified on JVM Clojure and
   Babashka."
  (:require [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.http :as http]
            [tools.agents.stream :as stream]))

;; Every Managed Agents request carries this flag; anthropic-sdk-python adds
;; it after any caller-supplied betas (resources/beta/sessions/events.py).
(def ^:private beta-flag "managed-agents-2026-04-01")

;; ---------------------------------------------------------------------------
;; Request plumbing
;; ---------------------------------------------------------------------------

(defn- caller [fn-name] (str "tools.agents.anthropic.agents/" fn-name))

(defn- with-beta
  "client with the Managed Agents flag appended to its :betas. Going through
   :betas, not a literal `anthropic-beta` request header, is what keeps the
   OAuth flag and the caller's own flags on the header: `request!` merges
   extra headers OVER the auth headers, so a literal would replace them."
  [client]
  (update client :betas #(vec (distinct (concat % [beta-flag])))))

(defn- segment
  "id as one URL path segment. An empty or non-string id throws
   :tools.agents.anthropic.error/invalid-argument BEFORE any request, as the
   SDK's `Expected a non-empty value for ...` ValueError, and because \"\"
   would turn a retrieve into a list and a delete into a DELETE on the
   collection. Percent-encoded so an id carrying `/`, `?` or `#` cannot
   reroute the request."
  [fn-name what id]
  (when-not (and (string? id) (seq id))
    (throw (ex-info (str (caller fn-name) ": expected a non-empty " what ", got: " (pr-str id))
                    {:type :tools.agents.anthropic.error/invalid-argument :status nil :body nil})))
  (http/encode-path-segment id))

(defn- with-beta-query
  "params plus `beta=true`, which every SDK path for these resources carries
   (\"/v1/sessions?beta=true\") and the guides' curl examples show on the
   event endpoints."
  [params]
  (assoc (or params {}) "beta" "true"))

(defn- send!
  "One JSON call through tools.agents.anthropic/request!. body nil sends no
   body (GET, DELETE and the bodyless archive POSTs)."
  ([client fn-name method path] (send! client fn-name method path nil nil))
  ([client fn-name method path body] (send! client fn-name method path body nil))
  ([client fn-name method path body params]
   (a/request! (with-beta client) (caller fn-name)
               (cond-> {:method method :path path :query (with-beta-query params)}
                 (some? body) (assoc :body body)))))

;; ---------------------------------------------------------------------------
;; Paging — every list endpoint here returns {"data" [...] "next_page" cursor}
;; (the SDK's SyncPageCursor); the cursor goes back as the "page" parameter.
;; ---------------------------------------------------------------------------

(defn next-page-params
  "params for the page after `page`, or nil when there is none: the SDK's
   SyncPageCursor.next_page_info. \"next_page\" nil or \"\" ends paging;
   otherwise it becomes \"page\" and every other param carries over. Keep
   \"order\" fixed across pages: a cursor encodes the order of the request
   that produced it, and reusing it with another returns a 400."
  [params page]
  (let [cursor (get page "next_page")]
    (when (and (string? cursor) (seq cursor))
      (assoc (dissoc (or params {}) :page) "page" cursor))))

(defn list-all
  "Lazy seq of every item across pages. `page-fn` takes a params map and
   returns one page, e.g.

     (list-all #(agents-list client %))
     (list-all #(sessions-events-list client session-id %) {\"limit\" 100})

   The next page is fetched only when the seq is realized that far — the
   SDK's auto-paging iteration."
  ([page-fn] (list-all page-fn nil))
  ([page-fn params]
   (lazy-seq
    (let [page  (page-fn params)
          nextp (next-page-params params page)]
      (concat (get page "data") (when nextp (list-all page-fn nextp)))))))

;; ---------------------------------------------------------------------------
;; Public API — agents
;;
;; Source: guide .../managed-agents/agent-setup.md; reference
;; .../api/beta/agents/{create,retrieve,update,list,archive}.md and
;; .../api/beta/agents/versions/list.md; SDK resources/beta/agents/**.
;; ---------------------------------------------------------------------------

(defn agents-create
  "POST /v1/agents — create a versioned agent, the analogue of
   `client.beta.agents.create(**params)`. `request` is a plain map passed
   through verbatim:

     \"name\"         required
     \"model\"        required: a model id string, or {\"id\" ... \"speed\" ...}
     \"system\"       system prompt
     \"description\"
     \"tools\"        {\"type\" \"agent_toolset_20260401\"}, {\"type\"
                     \"mcp_toolset\" \"mcp_server_name\" ...}, or a custom tool
                     {\"type\" \"custom\" \"name\" ... \"input_schema\" {...}}
     \"mcp_servers\"  [{\"type\" \"url\" \"name\" ... \"url\" ...}]
     \"skills\"
     \"multiagent\"
     \"metadata\"     string pairs

   Returns the agent: \"id\", \"type\" \"agent\", \"version\" (1 on create)
   and the fields above. Pass its \"id\" as `sessions-create`'s \"agent\".
   Throws ex-info typed as tools.agents.anthropic/request! documents."
  [client request]
  (send! client "agents-create" :post "/v1/agents" request))

(defn agents-retrieve
  "GET /v1/agents/{agent-id} — the analogue of
   `client.beta.agents.retrieve(agent_id)`. `params` (optional) is the query
   map; {\"version\" n} returns that version instead of the latest."
  ([client agent-id] (agents-retrieve client agent-id nil))
  ([client agent-id params]
   (send! client "agents-retrieve" :get (str "/v1/agents/" (segment "agents-retrieve" "agent id" agent-id))
          nil params)))

(defn agents-update
  "POST /v1/agents/{agent-id} — the analogue of
   `client.beta.agents.update(agent_id, **params)`. `request` takes
   `agents-create`'s fields, all optional, plus an optional \"version\" for
   optimistic concurrency: the agent's current version, and a mismatch is a
   409. Omit it and the update applies unconditionally (last write wins). An
   update that changes the configuration creates a new version; the agent is
   returned with it. An archived agent rejects updates.

   The 409 is retried before it surfaces: 409 is in the SDK's retryable set
   (and this library's), so a stale \"version\" costs :max-retries attempts
   and then throws :tools.agents.anthropic.error/api-status."
  [client agent-id request]
  (send! client "agents-update" :post (str "/v1/agents/" (segment "agents-update" "agent id" agent-id)) request))

(defn agents-list
  "GET /v1/agents — one page, the analogue of
   `client.beta.agents.list(**params)`. `params` (optional): \"limit\",
   \"page\", \"include_archived\", \"created_at[gte]\", \"created_at[lte]\".
   Returns {\"data\" [agent ...] \"next_page\" cursor-or-nil}; see
   `next-page-params` / `list-all`."
  ([client] (agents-list client nil))
  ([client params] (send! client "agents-list" :get "/v1/agents" nil params)))

(defn agents-archive
  "POST /v1/agents/{agent-id}/archive (no body) — the analogue of
   `client.beta.agents.archive(agent_id)`. Returns the agent with
   \"archived_at\" set. Archiving cannot be undone: the agent becomes
   read-only and new sessions cannot reference it; existing ones keep
   running."
  [client agent-id]
  (send! client "agents-archive" :post
         (str "/v1/agents/" (segment "agents-archive" "agent id" agent-id) "/archive")))

(defn agents-versions-list
  "GET /v1/agents/{agent-id}/versions — one page of the agent's versions,
   the analogue of `client.beta.agents.versions.list(agent_id, **params)`.
   `params` (optional): \"limit\", \"page\". Same page shape as `agents-list`."
  ([client agent-id] (agents-versions-list client agent-id nil))
  ([client agent-id params]
   (send! client "agents-versions-list" :get
          (str "/v1/agents/" (segment "agents-versions-list" "agent id" agent-id) "/versions")
          nil params)))

;; ---------------------------------------------------------------------------
;; Public API — environments
;;
;; Source: guide .../managed-agents/environments.md; reference
;; .../api/beta/environments/{create,retrieve,update,list,delete,archive}.md;
;; SDK resources/beta/environments/environments.py.
;; ---------------------------------------------------------------------------

(defn- environment-path [fn-name environment-id]
  (str "/v1/environments/" (segment fn-name "environment id" environment-id)))

(defn environments-create
  "POST /v1/environments — create a sandbox configuration, the analogue of
   `client.beta.environments.create(**params)`. `request`, verbatim:

     \"name\"        required
     \"config\"      {\"type\" \"cloud\" \"networking\" {\"type\" \"limited\"
                    \"allow_package_managers\" true ...} ...}, or a
                    self-hosted config
     \"description\"
     \"metadata\"    string pairs
     \"scope\"       \"organization\" | \"account\"

   Returns the environment; pass its \"id\" as `sessions-create`'s
   \"environment_id\". Each session gets its own sandbox from it."
  [client request]
  (send! client "environments-create" :post "/v1/environments" request))

(defn environments-retrieve
  "GET /v1/environments/{environment-id} — the analogue of
   `client.beta.environments.retrieve(environment_id)`."
  [client environment-id]
  (send! client "environments-retrieve" :get (environment-path "environments-retrieve" environment-id)))

(defn environments-update
  "POST /v1/environments/{environment-id} — the analogue of
   `client.beta.environments.update(environment_id, **params)`. `request`
   takes `environments-create`'s fields, all optional. Returns the updated
   environment."
  [client environment-id request]
  (send! client "environments-update" :post (environment-path "environments-update" environment-id) request))

(defn environments-list
  "GET /v1/environments — one page, the analogue of
   `client.beta.environments.list(**params)`. `params` (optional):
   \"limit\", \"page\", \"include_archived\". Returns
   {\"data\" [environment ...] \"next_page\" cursor-or-nil}."
  ([client] (environments-list client nil))
  ([client params] (send! client "environments-list" :get "/v1/environments" nil params)))

(defn environments-delete
  "DELETE /v1/environments/{environment-id} — the analogue of
   `client.beta.environments.delete(environment_id)`. Returns
   {\"id\" id \"type\" \"environment_deleted\"}."
  [client environment-id]
  (send! client "environments-delete" :delete (environment-path "environments-delete" environment-id)))

(defn environments-archive
  "POST /v1/environments/{environment-id}/archive (no body) — the analogue
   of `client.beta.environments.archive(environment_id)`. Returns the
   environment with \"archived_at\" set."
  [client environment-id]
  (send! client "environments-archive" :post
         (str (environment-path "environments-archive" environment-id) "/archive")))

;; ---------------------------------------------------------------------------
;; Public API — sessions
;;
;; Source: guides .../managed-agents/{sessions,session-operations,budgets}.md;
;; reference .../api/beta/sessions/{create,retrieve,update,list,delete,
;; archive}.md; SDK resources/beta/sessions/sessions.py.
;; ---------------------------------------------------------------------------

(defn- session-path [fn-name session-id]
  (str "/v1/sessions/" (segment fn-name "session id" session-id)))

(defn sessions-create
  "POST /v1/sessions — create a session, the analogue of
   `client.beta.sessions.create(**params)`. `request`, verbatim:

     \"agent\"           required: an agent id string (latest version),
                        {\"type\" \"agent\" \"id\" ... \"version\" n} to pin a
                        version, or {\"type\" \"agent_with_overrides\" \"id\"
                        ... \"model\"/\"system\"/\"tools\"/\"mcp_servers\"/
                        \"skills\" ...} for per-session overrides
     \"environment_id\"  required
     \"initial_events\"  up to 50 `user.message` / `user.define_outcome`
                        events, processed in order
     \"budget\"          {\"type\" \"limit\" \"max_list_cost\" {\"amount\"
                        \"125\" \"currency\" \"USD\"}}
     \"resources\"       files, repositories, memory stores to mount
     \"vault_ids\"
     \"title\"
     \"metadata\"        string pairs

   Returns the session (\"id\", \"status\", \"agent\", ...). Without
   \"initial_events\" it starts \"idle\" and does nothing until an event is
   sent (`send-message`); a non-empty list creates it \"running\". The
   response does not echo the initial events — list them with
   `sessions-events-list`. To follow the work, open `sessions-events-stream`
   BEFORE sending: a stream delivers only events emitted after it opens."
  [client request]
  (send! client "sessions-create" :post "/v1/sessions" request))

(defn sessions-retrieve
  "GET /v1/sessions/{session-id} — the analogue of
   `client.beta.sessions.retrieve(session_id)`. \"status\" is \"idle\",
   \"running\", \"rescheduling\" or \"terminated\". \"idle\" means waiting
   for input, not success: why it stopped is the `stop_reason` of the latest
   `session.status_idle` event (`sessions-events-list`, `latest-status`)."
  [client session-id]
  (send! client "sessions-retrieve" :get (session-path "sessions-retrieve" session-id)))

(defn sessions-update
  "POST /v1/sessions/{session-id} — the analogue of
   `client.beta.sessions.update(session_id, **params)`. `request`, all
   optional: \"title\", \"metadata\", \"vault_ids\", \"budget\" (a new
   limit, or nil to remove it — either resumes work paused
   at the budget), and \"agent\" {\"tools\" [...] \"mcp_servers\" [...]},
   each array replacing the session's in full. The session must be \"idle\"
   to update its agent. Returns the updated session."
  [client session-id request]
  (send! client "sessions-update" :post (session-path "sessions-update" session-id) request))

(defn sessions-list
  "GET /v1/sessions — one page, the analogue of
   `client.beta.sessions.list(**params)`. `params` (optional): \"limit\",
   \"page\", \"order\" (\"asc\"|\"desc\", default \"desc\"), \"agent_id\",
   \"agent_version\", \"statuses\" (a vector, sent as `statuses[]=`),
   \"include_archived\", \"created_at[gt|gte|lt|lte]\", \"deployment_id\",
   \"memory_store_id\". Returns {\"data\" [session ...] \"next_page\"
   cursor-or-nil \"prev_page\" cursor-or-nil}."
  ([client] (sessions-list client nil))
  ([client params] (send! client "sessions-list" :get "/v1/sessions" nil params)))

(defn sessions-delete
  "DELETE /v1/sessions/{session-id} — permanently remove the session, its
   events and its sandbox; the analogue of
   `client.beta.sessions.delete(session_id)`. Returns
   {\"id\" id \"type\" \"session_deleted\"}. A \"running\" session cannot be
   deleted: `interrupt` it and wait for idle first."
  [client session-id]
  (send! client "sessions-delete" :delete (session-path "sessions-delete" session-id)))

(defn sessions-archive
  "POST /v1/sessions/{session-id}/archive (no body) — stop accepting events
   while keeping the history; the analogue of
   `client.beta.sessions.archive(session_id)`. Returns the session. A
   \"running\" session cannot be archived: `interrupt` it and wait for idle
   first."
  [client session-id]
  (send! client "sessions-archive" :post (str (session-path "sessions-archive" session-id) "/archive")))

;; ---------------------------------------------------------------------------
;; Public API — session events: send, history
;;
;; Source: guide .../managed-agents/events-and-streaming.md; reference
;; .../api/beta/sessions/events/{send,list}.md; SDK
;; resources/beta/sessions/events.py.
;; ---------------------------------------------------------------------------

(defn- events-path [fn-name session-id]
  (str (session-path fn-name session-id) "/events"))

(defn sessions-events-send
  "POST /v1/sessions/{session-id}/events — send one or more events, the
   analogue of `client.beta.sessions.events.send(session_id, events=[...])`.
   `request` is {\"events\" [...]}, processed in order. The event types the
   API accepts: `user.message`, `user.interrupt`, `user.custom_tool_result`,
   `user.tool_confirmation`, `user.define_outcome`, `user.tool_result`
   (self-hosted environments only) and `system.message`. `send-message`,
   `interrupt`, `send-custom-tool-result` and `confirm-tool` build the common
   ones; call this directly for the rest, or to send several in one request
   (an interrupt followed by a redirecting message, say).

   Returns {\"data\" [event ...]}: the events as persisted, each with its
   server-assigned \"id\". The call returns once they are queued, not once
   they are processed (\"processed_at\" is nil until then)."
  [client session-id request]
  (send! client "sessions-events-send" :post (events-path "sessions-events-send" session-id) request))

(defn sessions-events-list
  "GET /v1/sessions/{session-id}/events — one page of the session's event
   history, the analogue of
   `client.beta.sessions.events.list(session_id, **params)`. `params`
   (optional): \"limit\", \"page\", \"order\" (\"asc\"|\"desc\"), \"types\"
   (a vector, sent as `types[]=agent.tool_use&types[]=...`),
   \"created_at[gt|gte|lt|lte]\". Returns
   {\"data\" [event ...] \"next_page\" cursor-or-nil}; see `list-all` for
   the whole history. Stream-only event deltas (`event_start`,
   `event_delta`) are never in it."
  ([client session-id] (sessions-events-list client session-id nil))
  ([client session-id params]
   (send! client "sessions-events-list" :get (events-path "sessions-events-list" session-id) nil params)))

(defn- content-blocks
  "A bare string becomes one text block; a sequential value is taken as
   content blocks already and passed through."
  [content]
  (if (string? content) [{"type" "text" "text" content}] (vec content)))

(defn send-message
  "Send a `user.message` to `session-id`. `content` is a string (one text
   block) or a vector of content blocks (text, image, document) sent
   verbatim. It starts a turn on an idle session and is queued behind the
   current one on a running session. Returns `sessions-events-send`'s
   response."
  [client session-id content]
  (sessions-events-send client session-id
                        {"events" [{"type" "user.message" "content" (content-blocks content)}]}))

(defn interrupt
  "Send a `user.interrupt` to `session-id`: stop the agent mid-execution,
   keeping the session and its history. The counterpart of
   tools.agents.openai.agents/cancel-turn, with one difference worth knowing:
   this call returns when the interrupt is QUEUED. It applies later (a model
   response in progress stops at once; running tool calls can delay it), and
   the turn then ends with a `session.status_idle` whose stop reason is
   \"end_turn\", the same as a turn that finished on its own. Wait for that
   idle (`await-idle`) before archiving or deleting.

   opts: :session-thread-id — interrupt only that thread of a multiagent
   session (default: every non-archived thread)."
  ([client session-id] (interrupt client session-id nil))
  ([client session-id {:keys [session-thread-id]}]
   (sessions-events-send client session-id
                         {"events" [(cond-> {"type" "user.interrupt"}
                                      session-thread-id (assoc "session_thread_id" session-thread-id))]})))

(defn send-custom-tool-result
  "Answer a custom tool call — a `user.custom_tool_result` event. `result`:

     :custom-tool-use-id  the \"id\" of the `agent.custom_tool_use` event
                          (also listed in the idle event's
                          stop_reason.event_ids)
     :content             the tool's output: a string, or content blocks
     :is-error            true when the tool failed

   It may be sent as soon as the `agent.custom_tool_use` event arrives; the
   session still emits its `requires_action` idle for the call afterwards. A
   second result for the same call is accepted and ignored."
  [client session-id {:keys [custom-tool-use-id content is-error]}]
  (sessions-events-send
   client session-id
   {"events" [(cond-> {"type" "user.custom_tool_result" "custom_tool_use_id" custom-tool-use-id}
                (some? content)  (assoc "content" (content-blocks content))
                (some? is-error) (assoc "is_error" (boolean is-error)))]}))

(defn confirm-tool
  "Allow or deny a tool call waiting on a permission policy — a
   `user.tool_confirmation` event. `decision`:

     :tool-use-id   the \"id\" of the `agent.tool_use` / `agent.mcp_tool_use`
                    event (listed in the idle event's stop_reason.event_ids)
     :result        \"allow\" | \"deny\" (a keyword works)
     :deny-message  optional context for a deny; the API rejects it on allow"
  [client session-id {:keys [tool-use-id result deny-message]}]
  (sessions-events-send
   client session-id
   {"events" [(cond-> {"type" "user.tool_confirmation" "tool_use_id" tool-use-id
                       "result" (if (keyword? result) (name result) result)}
                (some? deny-message) (assoc "deny_message" deny-message))]}))

;; ---------------------------------------------------------------------------
;; Public API — event stream
;;
;; Source: guide .../managed-agents/events-and-streaming.md (literal
;; `curl -N ".../v1/sessions/$SESSION_ID/events/stream?beta=true" ... -H
;; "accept: text/event-stream"`); guide .../event-deltas.md; reference
;; .../api/beta/sessions/events/stream.md; SDK
;; resources/beta/sessions/events.py (`stream=True`, query `event_deltas`)
;; and _streaming.py Stream.__stream__, which dispatches on the SSE event
;; NAME, fills a missing data "type" from it, skips `ping` and raises on
;; `error`. tools.agents.anthropic/open-stream! does the same; the one
;; difference is that an event whose name the SDK does not list is delivered
;; here rather than dropped, so a type added after this was written still
;; reaches the reducer.
;; ---------------------------------------------------------------------------

(def ^:private drop-pings (remove #(and (map? %) (= "ping" (get % "type")))))

(defn- open-events-stream
  [client fn-name session-id params xform]
  (a/open-stream! (with-beta client) (caller fn-name)
                  {:method  :get
                   :path    (str (events-path fn-name session-id) "/stream")
                   :query   (with-beta-query params)
                   :headers {"accept" "text/event-stream"}
                   :xform   (if xform (comp drop-pings xform) drop-pings)}))

(defn sessions-events-stream
  "GET /v1/sessions/{session-id}/events/stream — live events for a session,
   the analogue of `client.beta.sessions.events.stream(session_id)`. Sends
   `accept: text/event-stream`. `params` (optional) is the query map:
   {\"event_deltas\" [\"agent.message\" \"agent.thinking\"]} opts this
   connection in to the stream-only `event_start` / `event_delta` previews.

   The request is sent NOW (tools.agents.anthropic/open-stream!): retries and
   HTTP errors happen inside this call and throw the same
   `:tools.agents.anthropic.error/*` keywords as `sessions-retrieve`. Returns
   a SINGLE-USE reducible (tools.agents.stream) of events, each the decoded
   JSON map as sent (string keys; dispatch on \"type\"; unknown types pass
   through). `ping` frames are dropped.

   A STREAM DELIVERS ONLY EVENTS EMITTED AFTER IT OPENS, and the server does
   not close it when the session goes idle. So subscribe first, send work
   second, and stop reading yourself:

     (let [s (sessions-events-stream client sid)]   ; subscribe first,
       (send-message client sid \"Summarize the README\") ; then send work
       (await-idle s {:on-event #(when (= \"agent.message\" (get % \"type\"))
                                   (print (events-output-text [%])))}))

   The reduce closes the connection on completion, early termination
   (`reduced`, `(into [] (take n) s)`) and exceptions; a stream never reduced
   must be released with `tools.agents.stream/close!`, which is also the
   cross-thread cancel. A second reduce throws `:tools.agents.stream/consumed`.

   While reducing, throws:
     - an SSE `error` event — typed from error.type exactly as
       tools.agents.anthropic/messages-stream types it; not delivered;
     - a mid-stream transport failure — :api-connection, never retried. There
       is no `Last-Event-ID` replay: reconnect with `resume-stream`;
     - an undecodable event — :json-parse.
   A `session.error` event is NOT one of these: it is an ordinary event,
   delivered to the reducer (`await-idle` decides what it means).

   Truncation never throws here: a body that simply ends reduces normally and
   `(tools.agents.stream/outcome s)` is :eof. `await-idle` turns an end
   without an idle event into :stream-truncated."
  ([client session-id] (sessions-events-stream client session-id nil))
  ([client session-id params]
   (open-events-stream client "sessions-events-stream" session-id params nil)))

;; ---------------------------------------------------------------------------
;; Public API — reading events
;; ---------------------------------------------------------------------------

(defn events-output-text
  "Concatenate the text blocks of every `agent.message` event in `events`
   (a collection, e.g. a `sessions-events-list` page's \"data\" in ascending
   order, or events gathered from a stream). The counterpart of
   tools.agents.openai.agents/items-output-text. Returns \"\" when there is
   none; non-text blocks (a `redacted` placeholder) and events of other types
   are skipped."
  [events]
  (str/join
   (for [ev    events
         :when (and (map? ev) (= "agent.message" (get ev "type")))
         block (let [c (get ev "content")] (if (sequential? c) c []))
         :when (and (map? block) (= "text" (get block "type")) (string? (get block "text")))]
     (get block "text"))))

(def ^:private session-status-types
  #{"session.status_running" "session.status_idle" "session.status_rescheduled"
    "session.status_terminated" "session.deleted"})

(defn latest-status
  "The last session-level status event in `events` (ascending order, as
   `sessions-events-list` returns by default): `session.status_running`,
   `session.status_idle`, `session.status_rescheduled`,
   `session.status_terminated` or `session.deleted`; nil when there is none.
   Per-thread `session.thread_status_*` events are not session status.

   After a reconnect this is how to tell where the session stands: when it is
   a `session.status_idle`, the session is already waiting and no further
   idle will arrive on the live stream until new input is sent; its
   `blocking-event-ids` are the calls still to answer."
  [events]
  (reduce (fn [latest ev]
            (if (and (map? ev) (contains? session-status-types (get ev "type"))) ev latest))
          nil events))

(defn stop-reason
  "The `stop_reason.type` of a `session.status_idle` event: \"end_turn\"
   (finished, or interrupted), \"requires_action\" (tool calls await an
   answer — see `blocking-event-ids`), \"retries_exhausted\" (the turn died
   after repeated errors), \"budget_reached\" or \"refusal\" (details in the
   event's \"stop_details\"). nil for any other event. Only \"end_turn\" is a
   turn that ran to its end, and even that does not say the work succeeded."
  [idle-event]
  (when (and (map? idle-event) (= "session.status_idle" (get idle-event "type")))
    (get-in idle-event ["stop_reason" "type"])))

(defn blocking-event-ids
  "The `stop_reason.event_ids` of a `requires_action` idle event: the ids of
   the `agent.custom_tool_use` / `agent.tool_use` / `agent.mcp_tool_use`
   events the session is blocked on, each to be answered with
   `send-custom-tool-result` or `confirm-tool`. Answering fewer than all
   makes the session emit another idle with the remainder. [] otherwise."
  [idle-event]
  (if (= "requires_action" (stop-reason idle-event))
    (vec (get-in idle-event ["stop_reason" "event_ids"]))
    []))

(defn- event-failure
  "The ex-info `await-idle` throws for `event`, or nil."
  [event]
  (let [t     (when (map? event) (get event "type"))
        label (caller "await-idle")
        fail  (fn [kw msg extra]
                (ex-info (str label ": " msg)
                         (merge {:type kw :status nil :body nil :event event} extra)))]
    (cond
      (not (map? event)) nil

      ;; Over a stream this event never gets here (open-stream! throws it in
      ;; decode); a plain collection must fail with the same :type.
      (= "error" t) (a/stream-event-error label event)

      (and (= "session.error" t)
           (= "terminal" (get-in event ["error" "retry_status" "type"])))
      (fail :tools.agents.anthropic.error/session-failed
            (str "session.error " (get-in event ["error" "type"])
                 (some->> (get-in event ["error" "message"]) (str ": ")))
            {:error (get event "error")})

      (= "session.status_terminated" t)
      (fail :tools.agents.anthropic.error/session-terminated "the session terminated" nil)

      (= "session.deleted" t)
      (fail :tools.agents.anthropic.error/session-deleted "the session was deleted" nil))))

(defn await-idle
  "Reduce `events` (a `sessions-events-stream` / `resume-stream` reducible,
   or any collection of decoded events) until the session goes idle, and
   return that `session.status_idle` event. Stops reading there, which closes
   a stream. The counterpart of tools.agents.openai.agents/await-root-turn.

   IDLE IS NOT SUCCESS. The returned event says why the session stopped —
   read `(stop-reason ev)`: \"end_turn\", \"requires_action\" (with
   `blocking-event-ids`), \"retries_exhausted\", \"budget_reached\" or
   \"refusal\". All five return normally; the caller decides.

   Throws ex-info (carrying `:event`, plus `:status nil :body nil`):

     session.error, retry_status terminal  :tools.agents.anthropic.error/session-failed
                                           (:error = the event's error)
     session.status_terminated             :tools.agents.anthropic.error/session-terminated
     session.deleted                       :tools.agents.anthropic.error/session-deleted
     SSE `error` event                     typed from error.type, as
                                           messages-stream (:error)
     end of events before an idle          :tools.agents.anthropic.error/stream-truncated
                                           (:outcome = the stream's outcome —
                                           :eof, :cancelled — or nil for a coll)

   A `session.error` whose retry_status is \"retrying\" (the server retries
   on its own) or \"exhausted\" (an idle with \"retries_exhausted\" follows)
   does not throw: it is passed to :on-event and the wait continues.
   Per-thread `session.thread_status_idle` events never end the wait.

   opts:
     :on-event  (fn [event]) called for every delivered event first,
                including the one that ends or fails the wait.
     :done?     (fn [idle-event]) -> truthy to stop at this idle (default:
                always). An :on-event that answers custom tool calls as they
                arrive still receives the `requires_action` idle the session
                emits for each; pass
                `#(not= \"requires_action\" (stop-reason %))` to wait through
                those.

   Subscribe before sending work: an idle emitted before the stream opened is
   never seen, and the server keeps an idle session's stream open, so the
   wait would block. After a reconnect check `latest-status` on the history
   first."
  ([events] (await-idle events nil))
  ([events {:keys [on-event done?]}]
   (let [done?  (or done? (constantly true))
         result (reduce (fn [_ event]
                          (when on-event (on-event event))
                          (when-let [e (event-failure event)] (throw e))
                          ;; By event type, not by stop reason: an idle
                          ;; with no readable stop_reason must still end the
                          ;; wait, since the server keeps the stream open.
                          (when (and (map? event) (= "session.status_idle" (get event "type")) (done? event))
                            (reduced event)))
                        nil events)]
     (or result
         (let [outcome (when (satisfies? stream/EventStream events) (stream/outcome events))]
           (throw (ex-info (str (caller "await-idle") ": events ended before the session went idle (outcome "
                                (pr-str outcome) "); reconnect with resume-stream")
                           {:type :tools.agents.anthropic.error/stream-truncated :status nil :body nil
                            :outcome outcome})))))))

(defn resume-stream
  "Reconnect to `session-id` without missing events — the guide's three
   steps (\"Reconnect without missing events\"), since the stream has no
   `Last-Event-ID` replay:

     1. open a new stream;
     2. list the FULL event history (every page) to seed a set of seen ids;
     3. tail the stream, skipping events already in the history.

   Returns {:history [event ...] :events reducible}. :history is everything
   the session recorded up to the listing, in ascending order. :events is
   the live stream (a `sessions-events-stream` value: single-use, close it
   with tools.agents.stream/close! if never reduced) minus every event whose
   \"id\" is in :history or was already delivered. `event_start` /
   `event_delta` previews carry no \"id\" of their own and always pass.

   History and live events are kept apart on purpose: replaying history into
   `await-idle` would stop it at an earlier turn's idle. Look at the history
   first, then decide whether to wait:

     (let [{:keys [history events]} (resume-stream client sid)
           status (latest-status history)]
       (if (= \"session.status_idle\" (get status \"type\"))
         (do (stream/close! events) status)   ; already waiting on us
         (await-idle events)))

   opts: :stream-params (the `sessions-events-stream` query map, e.g.
   {\"event_deltas\" [...]}), :list-params (extra `sessions-events-list`
   params, e.g. {\"limit\" 1000}; \"order\" is forced to \"asc\").

   If the history listing throws, the stream is closed and the error
   propagates."
  ([client session-id] (resume-stream client session-id nil))
  ([client session-id {:keys [stream-params list-params]}]
   (let [seen    (atom #{})
         ;; Reads `seen` when the reduce runs, which is after the history
         ;; below has filled it.
         unseen  (remove (fn [ev]
                           (let [id (when (map? ev) (get ev "id"))]
                             (and (some? id)
                                  (let [[old _] (swap-vals! seen conj id)]
                                    (contains? old id))))))
         events  (open-events-stream client "resume-stream" session-id stream-params unseen)
         history (try
                   (vec (list-all #(sessions-events-list client session-id %)
                                  (assoc (or list-params {}) "order" "asc")))
                   (catch Throwable t
                     (stream/close! events)
                     (throw t)))]
     (swap! seen into (keep #(when (map? %) (get % "id"))) history)
     {:history history :events events})))
