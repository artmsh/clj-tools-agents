# tools.agents.anthropic.agents

A pure-Clojure client for [Claude Managed Agents](https://platform.claude.com/docs/en/managed-agents/overview)
(beta, `managed-agents-2026-04-01`): Anthropic's hosted agent harness, where
a versioned agent and an environment are created once and each session runs
the agent in its own sandbox, driven by events. It is modeled on
anthropic-sdk-python's `client.beta.{agents,environments,sessions}` resource
tree.

**Sibling of [tools.agents.anthropic](anthropic.md), in the way
[tools.agents.openai.agents](openai-agents.md) is of tools.agents.openai.** It
takes the same `AnthropicClient` record `tools.agents.anthropic/client` builds
and sends every request through that namespace's public transport:
`request!` for JSON calls and `open-stream!` for the event stream. Credential
resolution, the `anthropic-version` header, retries and error typing are
therefore the same code path, not a copy. Build one client and use it with
both namespaces.

## Not verified live

No Anthropic key with Managed Agents access was available when this was
written, and the router this project tests against serves the Messages API
only. Nothing in this namespace has been exercised against the real API.
Every path, header, query parameter and JSON shape is one the sources below
show, and the test suite checks the client against a mock server that plays
those documented shapes back. Treat the shapes as documentation until a live
pass exists; the places most likely to differ from the wire are the SSE frame
details (see [Stream framing](#stream-framing)) and the exact error bodies.

## Where this came from

Read on 2026-10-09, from the raw Markdown pages rather than from any model's
prior knowledge or a summary of them:

- Guides, `https://platform.claude.com/docs/en/managed-agents/<page>.md`:
  `overview`, `quickstart`, `agent-setup`, `environments`, `sessions`,
  `session-operations`, `events-and-streaming`, `event-deltas`, `tools`,
  `budgets`, `reference`.
- API reference, `https://platform.claude.com/docs/en/api/beta/<resource>/<method>.md`:
  `agents/{create,retrieve,update,list,archive}`, `agents/versions/list`,
  `environments/{create,retrieve,update,list,delete,archive}`,
  `sessions/{create,retrieve,update,list,delete,archive}`,
  `sessions/events/{send,list,stream}`. Each page carries the literal
  endpoint (`**POST** /v1/sessions/{session_id}/events`), its parameters and a
  `curl` example.
- anthropic-sdk-python at `b4b7916` (2026-10-09):
  `src/anthropic/resources/beta/{agents,environments,sessions}/**` for paths,
  query names and the beta header; `src/anthropic/_streaming.py` for SSE
  framing; `src/anthropic/pagination.py` (`SyncPageCursor`) for paging;
  `src/anthropic/_client.py` (`Querystring(array_format="brackets")`) for
  array query parameters.

Three things the sources disagree on or leave open, and what this client does:

| Question | Sources | This client |
|---|---|---|
| `?beta=true` | Every SDK path carries it (`"/v1/sessions?beta=true"`). The guides' `curl` examples show it on the event endpoints only; the reference's `curl` examples never. | Sent on every request, as the SDK does. |
| Array query parameters | The guide's `curl` writes `types[]=agent.tool_use&types[]=agent.tool_result`; the SDK's `brackets` format produces the same. | `{"types" [...]}` is sent as repeated `types[]=`. |
| SSE `event:` line | The guides parse only `data:` lines and read `.type` from the JSON. The SDK dispatches on the SSE event name and fills a missing `"type"` from it. | Both work: `"type"` is taken from the data, or from the event name when the data lacks one. |

## Endpoints

| Method | Path | Function |
|---|---|---|
| POST | `/v1/agents` | `agents-create` |
| GET | `/v1/agents/{id}` (`?version=n`) | `agents-retrieve` |
| POST | `/v1/agents/{id}` | `agents-update` |
| GET | `/v1/agents` | `agents-list` |
| POST | `/v1/agents/{id}/archive` | `agents-archive` |
| GET | `/v1/agents/{id}/versions` | `agents-versions-list` |
| POST | `/v1/environments` | `environments-create` |
| GET | `/v1/environments/{id}` | `environments-retrieve` |
| POST | `/v1/environments/{id}` | `environments-update` |
| GET | `/v1/environments` | `environments-list` |
| DELETE | `/v1/environments/{id}` | `environments-delete` |
| POST | `/v1/environments/{id}/archive` | `environments-archive` |
| POST | `/v1/sessions` | `sessions-create` |
| GET | `/v1/sessions/{id}` | `sessions-retrieve` |
| POST | `/v1/sessions/{id}` | `sessions-update` |
| GET | `/v1/sessions` | `sessions-list` |
| DELETE | `/v1/sessions/{id}` | `sessions-delete` |
| POST | `/v1/sessions/{id}/archive` | `sessions-archive` |
| POST | `/v1/sessions/{id}/events` | `sessions-events-send`, and the helpers `send-message`, `interrupt`, `send-custom-tool-result`, `confirm-tool` |
| GET | `/v1/sessions/{id}/events` | `sessions-events-list` |
| GET | `/v1/sessions/{id}/events/stream` (SSE) | `sessions-events-stream`, `resume-stream` |

Function names follow the SDK tree: `client.beta.sessions.events.send` is
`sessions-events-send`. Requests and responses are plain maps with string
keys, passed through verbatim, so a field added to the API needs no change
here.

Not covered, although the reference documents them: session threads
(`/v1/sessions/{id}/threads/**`), session resources
(`/v1/sessions/{id}/resources/**`), vaults and credentials (`/v1/vaults/**`),
the self-hosted work queue (`/v1/environments/{id}/work/**`), and the
separate resources for memory stores, skills, deployments and webhooks. Until
they are, `tools.agents.anthropic/request!` reaches any of them with a client
whose `:betas` includes `"managed-agents-2026-04-01"`.

## Usage

```clojure
(require '[tools.agents.anthropic :as a]
         '[tools.agents.anthropic.agents :as agents]
         '[tools.agents.stream :as stream])

(def client (a/client {}))   ; ANTHROPIC_API_KEY, or any credential a/client resolves

;; Created once, referenced by id from every session.
(def agent
  (agents/agents-create client
    {"name" "repo-helper"
     "model" "claude-opus-5-5"
     "system" "You are a careful engineer. Run what you write."
     "tools" [{"type" "agent_toolset_20260401"}]}))

(def env
  (agents/environments-create client
    {"name" "python-dev"
     "config" {"type" "cloud"
               "networking" {"type" "limited" "allow_package_managers" true}}}))

;; A session starts idle and does nothing until it is sent an event.
(def session
  (agents/sessions-create client
    {"agent" (get agent "id") "environment_id" (get env "id")}))
(def sid (get session "id"))

;; Subscribe FIRST: a stream delivers only events emitted after it opens.
(let [s    (agents/sessions-events-stream client sid)
      _    (agents/send-message client sid "Summarize the repo README.")
      idle (agents/await-idle s {:on-event #(print (agents/events-output-text [%]))})]
  (agents/stop-reason idle))   ; => "end_turn" | "requires_action" | "retries_exhausted" | ...
```

`await-idle` returns the `session.status_idle` event and stops reading, which
closes the connection; the server itself keeps an idle session's stream open.

### Idle is not success

A session that stops goes `idle` whatever the reason, and `sessions-retrieve`
shows only `"status" "idle"`. Why it stopped is on the idle event:

| `(stop-reason idle)` | Meaning | Next step |
|---|---|---|
| `"end_turn"` | The agent finished its turn, or was interrupted. | Send a `user.message` for more work. |
| `"requires_action"` | Tool calls are waiting on the caller. | Answer every id in `(blocking-event-ids idle)`. |
| `"retries_exhausted"` | Repeated errors killed the turn. | Send a new prompt, or give up. |
| `"budget_reached"` | The session's list cost reached its budget. | Raise or remove it with `sessions-update`; no event resumes it. |
| `"refusal"` | The model's response was refused. | `"stop_details"` on the event names the category. |

`await-idle` returns all five and throws for none of them: which of them
counts as failure is the caller's decision. It throws only when no idle can
be reported at all:

| Event | `:type` |
|---|---|
| `session.error` with `retry_status` `terminal` | `:tools.agents.anthropic.error/session-failed` (`:error` is the event's error) |
| `session.status_terminated` | `:tools.agents.anthropic.error/session-terminated` |
| `session.deleted` | `:tools.agents.anthropic.error/session-deleted` |
| SSE `error` event | typed from `error.type`, as `messages-stream` (thrown by the stream itself) |
| events end before an idle | `:tools.agents.anthropic.error/stream-truncated` (`:outcome` is the stream's) |

A `session.error` whose `retry_status` is `retrying` means the server is
retrying on its own, and `exhausted` is followed by an idle with
`retries_exhausted`; neither throws. Both reach `:on-event`. Per-thread
`session.thread_status_idle` events never end the wait.

### Tool calls that pause the session

A custom tool call, or a call a permission policy holds for confirmation,
pauses the session with a `requires_action` idle whose
`stop_reason.event_ids` name the blocking events. Answering fewer than all of
them makes the session emit another idle with the remainder.

```clojure
(let [s (agents/sessions-events-stream client sid)]
  (agents/send-message client sid "What's the weather in Kyiv?")
  (agents/await-idle s
    {:on-event (fn [ev]
                 (case (get ev "type")
                   ;; A custom tool result may be sent as soon as the call arrives.
                   "agent.custom_tool_use"
                   (agents/send-custom-tool-result client sid
                     {:custom-tool-use-id (get ev "id")
                      :content (run-tool (get ev "name") (get ev "input"))})
                   nil))
     ;; The session still emits a requires_action idle for a call answered
     ;; early; wait through those.
     :done? #(not= "requires_action" (agents/stop-reason %))}))

;; Confirmations are answered from the idle event's ids.
(doseq [id (agents/blocking-event-ids idle)]
  (agents/confirm-tool client sid {:tool-use-id id :result :allow}))
```

One stream is one `reduce`. `await-idle` without `:done?` returns at the
first idle and closes the connection, so a caller that answers after the wait
returns must open a new stream (before sending the answers) to see what
follows. Answering from `:on-event` with a `:done?` keeps a single stream
open across the pause.

### Interrupt

`(agents/interrupt client sid)` sends `user.interrupt`. The call returns when
the interrupt is queued, not when it has applied: a model response in
progress stops at once, but running tool calls can delay it. The turn then
ends with an ordinary idle whose stop reason is `end_turn`, the same value a
finished turn reports. A running session can be neither archived nor deleted,
so interrupt it and `await-idle` first. To redirect rather than stop, send
both events in one request:

```clojure
(agents/sessions-events-send client sid
  {"events" [{"type" "user.interrupt"}
             {"type" "user.message"
              "content" [{"type" "text" "text" "Instead, fix the bug on line 42."}]}]})
```

### Reconnect without missing events

The stream has no `Last-Event-ID` replay, and a mid-stream connection failure
is never retried. The documented recovery is three steps: open a new stream,
list the full history to seed a set of seen event ids, then tail the stream
skipping those. `resume-stream` does exactly that and keeps the two halves
apart:

```clojure
(let [{:keys [history events]} (agents/resume-stream client sid)
      status (agents/latest-status history)]
  (if (= "session.status_idle" (get status "type"))
    (do (stream/close! events) status)   ; already waiting: answer or send work
    (agents/await-idle events)))         ; still running: wait on the live half
```

`:history` is every recorded event in ascending order (all pages). `:events`
is the live stream minus anything whose `"id"` is in the history or was
already delivered. They are separate because replaying history into
`await-idle` would end the wait at an earlier turn's idle. For the same
reason the history has to be looked at before waiting: if the session is
already idle, no further idle arrives on the live stream until new input is
sent, and the wait would block. `latest-status` reads that off the history;
when it is a `requires_action` idle, its `blocking-event-ids` are the calls
still to answer.

Stream-only previews (`event_start`, `event_delta`, opted into with
`{"event_deltas" ["agent.message"]}`) have no `"id"` of their own, are never
in the history, and always pass the filter.

### History, text and paging

```clojure
;; One page; "types" narrows it.
(agents/sessions-events-list client sid {"types" ["agent.tool_use" "agent.tool_result"]})

;; The whole history, lazily, then the agent's text.
(->> (agents/list-all #(agents/sessions-events-list client sid %) {"order" "asc"})
     (agents/events-output-text))

(agents/list-all #(agents/agents-list client %))   ; every agent, page by page
```

Every list endpoint here returns `{"data" [...] "next_page" cursor}`; the
cursor goes back as `"page"`. `next-page-params` computes the next request's
params and `list-all` walks the pages lazily. A cursor encodes the `order` of
the request that produced it, so keep `"order"` fixed across pages.

### Agent versions

An agent's `"version"` starts at 1 and increments when an update changes its
configuration. `agents-update` accepts an optional `"version"` for optimistic
concurrency; a mismatch is a 409. That 409 is in the retryable set of both
the SDK and this library, so a stale version costs `:max-retries` attempts
before it surfaces as `:tools.agents.anthropic.error/api-status`. A session
runs the latest version when `"agent"` is the id string, or a pinned one with
`{"type" "agent" "id" id "version" n}`.

## Design notes

### Credentials and headers

Identical to `tools.agents.anthropic`, plus the beta flag. The flag is added
by appending `managed-agents-2026-04-01` to the client's `:betas` for the
call, not by passing a literal `anthropic-beta` header: `request!` merges
extra headers over the auth headers, so a literal would replace the OAuth
flag an `:auth-token` or `:credential-source` client needs, and any flags the
caller set. With `:auth-token` and `:betas ["files-api-2025-04-14"]` the
header is `oauth-2025-04-20,files-api-2025-04-14,managed-agents-2026-04-01`.

### Error hierarchy

Requests throw the `:tools.agents.anthropic.error/*` keywords of
[anthropic.md](anthropic.md#error-hierarchy) unchanged; only the message
prefix is this namespace's (`tools.agents.anthropic.agents/sessions-create:
HTTP 429 ...`). An empty or non-string id throws
`:tools.agents.anthropic.error/invalid-argument` before any request, as the
SDK's `ValueError` does, since an empty id would turn a retrieve into a list
and a delete into a `DELETE` on the collection. `await-idle` adds four
keywords in the same namespace, thrown while events are reduced and never by
a request: `session-failed`, `session-terminated`, `session-deleted` and
`stream-truncated`.

### Stream framing

`sessions-events-stream` is `tools.agents.anthropic/open-stream!`, the opener
`messages-stream` also uses, so the request is sent at call time, retried
before the first byte under the client's policy, and never retried after.
Framing follows the SDK's `Stream.__stream__`:

- An event's `"type"` is the data's own, or the SSE event name when the data
  has none.
- `ping` frames are dropped.
- An SSE `error` event throws from the reduce, typed from `error.type`, and is
  not delivered. This is the transport-level error. A `session.error` is an
  ordinary session event and is delivered.
- One deliberate difference: the SDK yields only the event names it lists and
  silently drops any other. This client delivers every event, so a type added
  after this was written still reaches the reducer.

### What `open-stream!` changed in tools.agents.anthropic

`messages-stream`'s opening logic (per-attempt auth headers, the retry loop,
the 401 refresh, SSE decoding and error typing) was private to that function.
It is now the public `tools.agents.anthropic/open-stream!`, with
`messages-stream` a three-line caller, so this namespace streams through the
same code instead of a second copy. `tools.agents.anthropic/stream-event-error`
is public for the same reason `tools.agents.openai/stream-event-error` is:
`await-idle` over a plain collection must throw what the stream throws.

## Testing

`test/tools/agents/anthropic/agents_test.cljc` runs in the anthropic suite on
both runtimes, against the shared mock server:

- every resource function: method, path, `beta=true` plus its own query
  parameters, body, and the three headers;
- the beta flag combined with the OAuth flag and a caller's `:betas`;
- id validation before any request, and path-segment encoding;
- error typing, a retried 429, and the retried 409 of a stale agent version;
- paging across three pages, fetched lazily;
- each event helper's body;
- the stream: incremental delivery, framing (type from the event name, pings
  dropped, unknown names delivered), early termination, an HTTP error and a
  retried 503 before the first byte, a mid-stream abort, an SSE `error` event;
- `await-idle`: all five stop reasons, `requires_action` with two blocking
  ids, retrying and exhausted `session.error`s, terminal error, terminated,
  deleted, thread events, truncation, `:done?`;
- `requires_action` end to end: the mock emits an idle naming two calls, the
  client answers one, and the mock emits the second idle with the remainder
  only after that answer arrives;
- `resume-stream`: the stream is opened before the history is listed, a
  two-page history, duplicates dropped, previews passed, and the stream
  closed when the listing fails.

The fixtures are the documented shapes, not captures. To make the last two
cases meaningful the JVM mock server now handles requests concurrently, as
the Babashka one always did; before that, a request sent while a streaming
response was still being written waited for the stream to finish.

```
clojure -M:test-anthropic
bb test-anthropic
```

The suite ends with one real-API round trip (agent, environment, session,
stream, message, idle, history, resume, cleanup), skipped unless both
`ANTHROPIC_AGENTS_LIVE=1` and `ANTHROPIC_API_KEY` are set. It is the live
pass this namespace still needs and has itself never been run:

```
ANTHROPIC_AGENTS_LIVE=1 ANTHROPIC_API_KEY=... clojure -M:test-anthropic
```

`ANTHROPIC_AGENTS_MODEL` (default `claude-haiku-5-5`) and
`ANTHROPIC_AGENTS_BASE_URL` override the model and the host.
