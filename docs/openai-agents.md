# tools.agents.openai.agents

A pure-Clojure client for [OpenAI's Agents API](https://developers.openai.com/api/docs/guides/agents-api/overview)
(beta, September 2026) — the managed Codex harness that runs sessions and
turns, optionally inside an OpenAI-hosted or self-hosted sandbox — modeled on
openai-python's `client.beta.agents.*` resource tree.

**Sibling of [tools.agents.openai](openai.md), not a peer library.** It takes
the exact same `OpenAIClient` record `tools.agents.openai/client` builds and
sends every request through that namespace's public transport,
`tools.agents.openai/request!` (JSON codec, retry loop, per-attempt headers,
error typing; see [openai.md](openai.md#shared-transport-request)), adding
only its `OpenAI-Beta` header and message prefix, because the Agents API lives under the same `https://api.openai.com/v1` root
— distinguished only by the `/agents` path prefix and a required
`OpenAI-Beta: agents=v1` header this namespace adds to every request. Build
one client and use it with both namespaces.

## Where this came from — a primary-source note

This is a beta API that shipped after this repo's own knowledge cutoff, so
every path, header and JSON shape below was confirmed by fetching OpenAI's
own raw `.md` documentation pages directly (`curl
https://developers.openai.com/api/docs/guides/agents-api/<page>.md`) rather
than trusting any model's prior knowledge or an LLM-generated summary of
those pages — a summarizer pass over the same pages, tried first, produced
several internally-contradictory claims (`/v1/agents/sessions` vs
`/beta/agents/sessions`, an invented `.../events` shape at odds with the
literal one the docs' own `curl` examples show) that only the raw source
resolved. Every endpoint implemented here is one shown verbatim in a `curl`
example or literal endpoint text on an OpenAI doc page; nothing is guessed
from an SDK method's name alone. The one exception to "a guide's `curl`
example" is turns (`GET .../sessions/{id}/turns[/{turn_id}]`): the events
guide links them only through the API reference, so their shape and default
newest-first order were confirmed against the live API.

The API reference is a second accepted source. Each reference HTML page
advertises a raw Markdown alternate (`<link rel="alternate"
type="text/markdown">`) at
`https://developers.openai.com/api/reference/resources/beta/subresources/agents/<...>/index.md`
(only the `.../index.md` form resolves; `.../methods/create.md` 404s). Those
pages carry literal endpoint text (`**post** /agents`) plus a `curl` example
per method, which meets the rule above. Its turns page
(`.../sessions/subresources/turns/methods/list/index.md`: `after`/`limit`/`order`,
default `desc`) matches what the live API returned, which is the
cross-check for trusting the rest. openai-python ships the same paths in
`src/openai/resources/beta/agents/**` (added in `1c4284a08294`,
2026-09-10; read at `d421d7ab8c0a`).

Saved (reusable) agent CRUD is implemented from those reference pages:
[create](https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/create/index.md),
[retrieve](https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/retrieve/index.md),
[update](https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/update/index.md),
[list](https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/list/index.md),
[delete](https://developers.openai.com/api/reference/resources/beta/subresources/agents/methods/delete/index.md).
A saved agent is distinct from a *session's* inline `agent` config, which
`sessions-create` passes through either way.

Session artifacts and environment files are implemented from the reference
pages
([artifacts list](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/artifacts/methods/list/index.md),
[retrieve](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/artifacts/methods/retrieve/index.md),
[content](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/artifacts/methods/content/index.md),
[delete](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/artifacts/methods/delete/index.md);
[environment files list](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/environments/subresources/files/methods/list/index.md),
[create](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/environments/subresources/files/methods/create/index.md)),
the [files guide](https://developers.openai.com/api/docs/guides/agents-api/environments/files.md)
and openai-python's `sessions/artifacts.py` / `environments/files.py`.
The reference has no environment-file retrieve, delete or content endpoint.
Verified live on 2026-09-15 (probe P1,
`artifacts-and-environment-files-probe-against-the-real-api`, 16 assertions):
artifact content arrives inline as `200` bytes (no redirect), byte-exact
including `\0` and `\377`; environment-file create and token-paged list work
on a connected `openai_hosted` environment. The content reference page has a
`curl` example but no Returns block; the SDK sends
`Accept: application/octet-stream` and reads a binary response.

Event streaming is implemented from two literal `curl` examples. The
[events guide](https://developers.openai.com/api/docs/guides/agents-api/sessions/events.md)
shows `curl -N ".../v1/agents/sessions/$session_id/events?stream=true" -H
"OpenAI-Beta: agents=v1" -H "Accept: text/event-stream"` (a GET), and the
[sessions guide](https://developers.openai.com/api/docs/guides/agents-api/sessions.md)
shows `curl --no-buffer` POSTing `/v1/agents/sessions` with `"stream": true`
("to receive events from the first turn in the same request"). The event
union (30 types) comes from the
[stream reference](https://developers.openai.com/api/reference/resources/beta/subresources/agents/subresources/sessions/subresources/events/methods/stream/index.md),
which gives schemas but no example payloads. openai-python's
`sessions/events.py` sends only `Accept: text/event-stream`; this client
sends the header and `?stream=true`, as the guide does. The "invented
`.../events` shape" mentioned above was an LLM summary's; the literal curl
supersedes it. **Not yet verified live** (probe
`session-event-streaming-against-the-real-api` has not run): whether frames
carry an `event:` line or a `[DONE]` sentinel (both tolerated), and whether
the server closes the events stream after a turn or keeps it open. The
input-event POST in its streaming-guide form (`sessions.events.create`) is
shown only as SDK code and is out of scope; `send-message` covers input.

## Usage

```clojure
(require '[tools.agents.openai :as oai]
         '[tools.agents.openai.agents :as agents])

(def client (oai/client {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Start a task in a fresh OpenAI-hosted sandbox.
(def session
  (agents/sessions-create client
    {"agent" {"model" "gpt-6-astra"
              "instructions" "Write clean code, run it, and report the actual output."}
     "environment" {"type" "openai_hosted"}
     "input" "Create tree.py, a script that prints a directory tree. Run it."}))

;; Streaming: subscribe, then send work, then wait for the ROOT turn.
(let [sid (get session "id")
      s   (agents/sessions-events-stream client sid)]
  (agents/send-message client sid "Now add a --depth flag.")
  (-> (agents/await-root-turn s {:on-event #(some-> (get % "delta") print)})
      (get "turn")))                    ; throws on failed/cancelled/error/truncated

;; Or create a session and stream its first turn in one request.
(agents/await-root-turn
  (agents/sessions-create-stream client {"environment" {"type" "none"} "input" "Say hi."}))

;; Or poll the turn, not the session: a failed turn also leaves the session
;; "idle", and the session can settle before the turn list shows a finished
;; turn. (A turn "waiting" on a function result never finishes on its own;
;; see the example's poll-until-done for that case.)
(defn wait-for-turn [client session-id]
  (loop []
    (let [turn (agents/latest-root-turn (agents/sessions-turns-list client session-id))]
      (if (agents/turn-finished? turn)
        turn
        (do (Thread/sleep 500) (recur))))))

(let [turn (wait-for-turn client (get session "id"))]
  (when-not (= "completed" (get turn "status"))
    (throw (ex-info "turn did not complete" {:turn turn}))))

;; Pull the assistant's final text out of the session's saved items.
(-> (agents/sessions-items-list client (get session "id") {"order" "asc"})
    (agents/items-output-text))

;; Saved agents: store the config once, start sessions from its id.
(def saved
  (agents/agents-create client
    {"model" "gpt-6-astra"
     "name" "tree-builder"
     "instructions" "Write clean code, run it, and report the actual output."}))

(agents/agents-update client (get saved "id") {"name" "tree-builder-v2"})  ; omitted fields unchanged
(agents/sessions-create client
  {"agent_id" (get saved "id")
   "environment" {"type" "openai_hosted"}
   "input" "Print a directory tree."})
(agents/agents-list client {"limit" 20})   ; page with "after" = "last_id" while "has_more"
(agents/agents-delete client (get saved "id"))

;; Files: copy input into a connected environment, download published output.
(def env-id (get-in (agents/sessions-retrieve client (get session "id")) ["environment" "id"]))
(agents/environments-files-create client env-id
  {"type" "inline" "path" "/workspace/in.csv"
   "data" (clojure.java.io/file "in.csv")})   ; or byte[], Path, or a base64 String
(agents/environments-files-list client env-id {"path" "/workspace" "limit" 100})
;; => {"object" "page" "data" [...] "has_more" true "next" "..."}; pass "page" = "next"

;; Artifacts: files the agent wrote under /workspace/outputs, published when a turn completes.
(let [sid      (get session "id")
      turn-id  (get (agents/latest-root-turn (agents/sessions-turns-list client sid)) "id")
      artifact (->> (get (agents/sessions-artifacts-list client sid) "data")
                    (filter #(and (= turn-id (get % "turn_id"))
                                  (= "/workspace/outputs/report.pdf" (get % "path"))))
                    first)]
  (clojure.java.io/copy (agents/sessions-artifacts-content client sid (get artifact "id"))  ; byte[]
                        (clojure.java.io/file "report.pdf"))
  (agents/sessions-artifacts-delete client sid (get artifact "id")))
```

See `examples/openai/agents_sandbox_task.clj` for the complete polling loop
(as a reusable `poll-until-done`, with an injectable sleep function so the
test suite runs it instantly against a mock server) and
`examples/openai/agents_self_hosted.clj` for the self-hosted sandbox flow.

## Design notes

### Parity with openai-python's `client.beta.agents.*`

| openai-python | tools.agents.openai.agents | Notes |
|---|---|---|
| `client.beta.agents.create(**params)` — `POST /v1/agents` (API reference) | `(agents-create client request)` | `"model"` required; `name`, `instructions`, `metadata`, `multi_agent`, `reasoning`, `service_tier`, `text`, `tools` passed through. Returns the `Agent` with defaults resolved. |
| `client.beta.agents.retrieve(agent_id)` — `GET /v1/agents/{agent_id}` | `(agents-retrieve client agent-id)` | |
| `client.beta.agents.update(agent_id, **params)` — `POST /v1/agents/{agent_id}` | `(agents-update client agent-id request)` | Omitted fields unchanged; `nil` clears `name`/`instructions`; `"metadata"` replaces wholesale (`nil`/`{}` clears). |
| `client.beta.agents.list(**params)` — `GET /v1/agents` | `(agents-list client params)` / `(agents-list client)` | Same cursor paging as `sessions-list`: `after`/`limit`/`order` (default `desc`); response has `first_id`/`last_id`/`has_more`. |
| `client.beta.agents.delete(agent_id)` — `DELETE /v1/agents/{agent_id}` | `(agents-delete client agent-id)` | Returns `{"deleted" true "object" "agent.deleted"}`. Deleting an agent live sessions reference is undocumented. |
| `client.beta.agents.sessions.create(**params)` | `(sessions-create client request)` | `request` passed through to JSON almost verbatim. Throws immediately on `stream: true`: use `sessions-create-stream`. |
| `client.beta.agents.sessions.retrieve(id)` | `(sessions-retrieve client session-id)` | |
| `client.beta.agents.sessions.list(**params)` | `(sessions-list client params)` / `(sessions-list client)` | `params` is a plain query-param map (`{"limit" 20 "order" "desc" "after" "sess_..."}`); page with `"after"` = the previous page's `"last_id"` while `"has_more"` is true. |
| `client.beta.agents.sessions.delete(id)` | `(sessions-delete client session-id)` | Removes the session from the API; does not stop self-hosted provider compute (per OpenAI's own docs) and has no webhook. |
| `client.beta.agents.sessions.events.create(id, events=[...])` | `(sessions-events-create client session-id request)` | Raw escape hatch; prefer the three helpers below for the shapes OpenAI's docs actually show. |
| … `events=[{"type": "agent.session.input.message", ...}]` | `(send-message client session-id text)` | Steers the active turn, or starts a new one on an idle session. |
| … `events=[{"type": "agent.session.input.cancel"}]` | `(cancel-turn client session-id)` | Session and prior work remain available. |
| … `events=[{"type": "agent.session.input.tool_result", ...}]` | `(send-tool-result client session-id {:turn-id .. :call-id .. :success .. :output/:error ..})` | Copy `:turn-id`/`:call-id` from the matching `required_actions` entry. |
| `client.beta.agents.sessions.items.list(id, **params)` | `(sessions-items-list client session-id params)` | Saved messages and tool calls, including completed turns' output. |
| `GET /v1/agents/sessions/{id}/turns` (API reference; verified live) | `(sessions-turns-list client session-id params)` / `(sessions-turns-list client session-id)` | Newest first by default. Each turn carries `"status"` and `"error"` — the only pollable record of a failed turn. |
| `GET /v1/agents/sessions/{id}/turns/{turn_id}` (API reference; verified live) | `(sessions-turns-retrieve client session-id turn-id)` | |
| *(no SDK helper)* | `(latest-root-turn turns-response)` / `(turn-finished? turn)` | Pure: newest turn with `"subagent_id"` nil; terminal status check. See Streaming below. |
| `item.content[…].text` traversal (no single SDK helper — see below) | `(items-output-text items-response)` | Concatenates every assistant `output_text` block, oldest-first when called with `{"order" "asc"}`. Deliberately more lenient than `tools.agents.openai/output-text` on a non-array `"content"`; see [divergences.md](divergences.md). |
| `GET /v1/agents/environments/{id}` (literal endpoint text, API reference) | `(environments-retrieve client environment-id)` | Poll a sandbox's `"status"`: `"pending"`, `"connected"`, `"disconnected"`, `"expired"` or `"failed"` (API reference). Also carries `"type"`, `"files"`, `"plugins"`, `"skills"`. |
| `codex exec-server --remote ... --environment-id ...` (shell, not an SDK call) | `(self-hosted-executor-command session)` | Pure function from a created self-hosted session to the executor's argv — see Self-hosted sandboxes below for what this library does and does not do here. |
| `client.beta.agents.sessions.artifacts.list(session_id, **params)` — `GET /v1/agents/sessions/{session_id}/artifacts` | `(sessions-artifacts-list client session-id params)` / `(sessions-artifacts-list client session-id)` | Cursor paging like `sessions-list`: `after`/`limit` (1-100)/`order` (default `desc`), plus `environment_id`. Only `openai_hosted`, only `/workspace/outputs`, only on turn completion. Match on `turn_id` + `path`. |
| `client.beta.agents.sessions.artifacts.retrieve(artifact_id, session_id=)` — `GET /v1/agents/sessions/{session_id}/artifacts/{artifact_id}` | `(sessions-artifacts-retrieve client session-id artifact-id)` | `SessionArtifact` metadata: `path`, `size_bytes`, `turn_id`, `environment_id`, `created_at`. |
| `client.beta.agents.sessions.artifacts.content(artifact_id, session_id=)` — `GET /v1/agents/sessions/{session_id}/artifacts/{artifact_id}/content` | `(sessions-artifacts-content client session-id artifact-id)` | Returns `byte[]` (`:as :bytes`), `Accept: application/octet-stream`, whole body in memory (≤200 MiB per artifact). **Redirects are not followed** (JDK `Redirect.NEVER` on both runtimes; httpx in the SDK follows): a 3xx throws `api-status-error`. Live (P1, 2026-09-15): content is served inline, no redirect. |
| `client.beta.agents.sessions.artifacts.delete(artifact_id, session_id=)` — `DELETE /v1/agents/sessions/{session_id}/artifacts/{artifact_id}` | `(sessions-artifacts-delete client session-id artifact-id)` | Deletes the published copy only. Returns `{"deleted" true "object" "agent.session.artifact.deleted"}`. |
| `client.beta.agents.environments.files.list(environment_id, **params)` — `GET /v1/agents/environments/{environment_id}/files` | `(environments-files-list client environment-id params)` / `(environments-files-list client environment-id)` | **Token paging** (`SyncTokenPage`), not `after`: response `{"object" "page" "has_more" .. "next" ..}`; pass `"page"` = `"next"`, keeping `path`/`order`/`limit`. Connected environments only. Verified live (P1, 2026-09-15). |
| `client.beta.agents.environments.files.create(environment_id, type=, path=, data=/file_id=)` — `POST /v1/agents/environments/{environment_id}/files` | `(environments-files-create client environment-id request)` | JSON body, not multipart. `{"type" "inline" "path" .. "data" ..}` or `{"type" "file_id" "path" .. "file_id" ..}`. `"data"`: a base64 String is sent verbatim; `byte[]`/`File`/`Path` is read and std-base64-encoded. Inline ≤5 MiB before encoding (API-enforced). No delete/retrieve endpoint exists. |
| `client.beta.agents.sessions.create(..., stream=True)` — `POST /v1/agents/sessions` with `"stream": true` | `(sessions-create-stream client request)` | Sets `"stream" true`; returns a single-use reducible of decoded events for the first turn, starting with `agent.session.created`. See Streaming below. |
| `client.beta.agents.sessions.events.stream(session_id)` — `GET /v1/agents/sessions/{session_id}/events?stream=true` | `(sessions-events-stream client session-id)` / `(sessions-events-stream client session-id params)` | `Accept: text/event-stream` + `?stream=true`. Request sent at call time; retries and HTTP errors before the first byte, typed as every other method. Events are the decoded JSON maps, verbatim (including `error` events). |
| the events guide's `stream_session` helper (docs code, not an SDK method) | `(await-root-turn events)` / `(await-root-turn events {:on-event f})` | Reduces until the root turn's `agent.session.turn.completed` and returns that event; throws `turn-failed`, `turn-cancelled`, `session-failed`, `stream-error`, `stream-truncated`. |
| *(no SDK helper)* | `(root-turn-finished? event)` | Pure: root (`subagent_id` nil) `turn.completed`/`.failed`/`.cancelled`. |

### Credentials & headers

Identical to `tools.agents.openai` in every respect but one: every request
here also carries `OpenAI-Beta: agents=v1`. Build a client with
`tools.agents.openai/client` and pass it to both namespaces; there is no
separate `agents/client` constructor. Every contract where this namespace, or
`tools.agents.openai` itself, differs from the other clients (headers, HTTP
methods, error keywords, streaming) is tabulated in
[divergences.md](divergences.md).

### Error hierarchy

Every request here throws the exact same `:type` keywords
`tools.agents.openai`'s own resource methods do (see
[docs/openai.md](openai.md)'s error-hierarchy table) — **not** a
`.agents`-suffixed set. Two reasons: the wire error shape
(`{"error":{"message":"..."}}`) and HTTP-status semantics are identical
between the two APIs, and this namespace's `write-json`/`read-json` calls are
literally `tools.agents.openai/write-json`/`read-json`, so a JSON-codec
failure surfaces with whatever `:type` that shared function already bakes in
(`:tools.agents.openai/json-parse-error`, not a namespace-local variant). A
`catch` written against one namespace's errors composes with the other's for
free.

Streaming adds five keywords in the same namespace that
`tools.agents.openai` itself never throws: `stream-error`, `session-failed`,
`turn-failed`, `turn-cancelled` and `stream-truncated`. They are thrown while
the events are reduced, never by a request, and are tabulated in "Streaming,
or poll" below.

The message prefix, however, IS this namespace's own —
`"tools.agents.openai.agents/sessions-create: HTTP 429 ..."` — so you can
still tell which library's resource method actually failed.

### Streaming, or poll

Two endpoints stream Server-Sent Events, each through
`tools.agents.stream/open-event-stream` with `tools.agents.openai/request!`
(`:as :stream`) as the opening request:

- `sessions-events-stream`: `GET .../sessions/{id}/events?stream=true`,
  `Accept: text/event-stream`. Live events for an existing session.
- `sessions-create-stream`: `POST .../sessions` with `"stream" true`. The
  created session's first-turn events in the same response.
  `sessions-create` keeps refusing `stream: true`
  (`:tools.agents.openai/streaming-unsupported`), because its JSON transport
  cannot read an SSE body.

Contract, shared by both:

- **Opened at call time.** Retries (openai's policy, the client's
  `:max-retries`) and non-2xx errors (`not-found-error`, `rate-limit-error`,
  ...) happen inside the call, before any event is handed out. Nothing is
  retried once reading starts: the API does not replay missed events.
- **Single-use reducible.** `reduce`/`into`/`run!`/`transduce` consume it once;
  a second reduce throws `:tools.agents.stream/consumed`. The reduce closes
  the connection on completion, early termination (`reduced`,
  `(into [] (take n) s)`) and exceptions. A stream never reduced must be
  released with `tools.agents.stream/close!`, which is also the cross-thread
  cancel (Babashka's `reify` cannot also implement `java.io.Closeable`, so
  `with-open` works only on the JVM).
- **Events** are the decoded JSON maps exactly as sent, string keys, dispatched
  on `"type"`. The 30 documented types and any new ones pass through. The SSE
  frame name and id sit in metadata (`:tools.agents.sse/event`,
  `:tools.agents.sse/id`). Blank keep-alive frames are dropped; a
  `data: [DONE]` sentinel (not documented for this API) ends the stream.
- **Errors while reading.** A connection failure mid-stream throws
  `:tools.agents.openai/api-connection-error` (the IOException as cause). An
  `error` event, or any event with a truthy top-level `"error"`, is not
  delivered: the reduce throws `:tools.agents.openai/stream-error` (`:status`
  nil, `:body` the raw data, `:error` the `SessionError`, `:event` the decoded
  event) after every earlier event has reached the reducer, and closes the
  connection. This is openai-python's behaviour: both streaming resources use
  the generic `Stream`, whose `__stream__` raises `APIError` on such data, so
  the SDK's `AgentSessionStream` never sees the event either. Events guide
  code that dispatches on `"type" "error"` in its handler maps to a `catch`
  here. The rule is shared by every client's stream, see
  [divergences.md](divergences.md#in-stream-error-events).
- **Truncation is not an error on the raw stream.** A body that simply ends
  (the server closed, a proxy cut it) reduces like a complete one;
  `(tools.agents.stream/outcome s)` is then `:eof`, versus `:reduced` when
  the reducer stopped, `:cancelled` after `close!`, `:done` on `[DONE]`.
  Whether the turn ended is known only from its events: `root-turn-finished?`.
  `await-root-turn` turns an end before the root turn's terminal event into
  `:tools.agents.openai/stream-truncated` (`:outcome` in `ex-data`).
- **No read-idle timeout.** The client's `:timeout-ms` (default 600 s)
  bounds only the wait for the response headers; once events flow, a
  stalled server blocks the reduce until someone calls `close!`.

`await-root-turn` ports the events guide's `stream_session` helper. It
continues on `agent.session.idle` and every other event, ignores subagent
turn events, returns the root `agent.session.turn.completed` event (its
`"turn"` and `"usage"`), and throws ex-info carrying `:event`:

| event | `:type` | extra `ex-data` |
|---|---|---|
| root `agent.session.turn.failed` | `:tools.agents.openai/turn-failed` | `:turn`, `:error` (turn.error) |
| root `agent.session.turn.cancelled` | `:tools.agents.openai/turn-cancelled` | `:turn` |
| `agent.session.failed`, `agent.session.environment.failed` | `:tools.agents.openai/session-failed` | `:error` |
| `error` (any truthy top-level `"error"`) | `:tools.agents.openai/stream-error` | `:error`; over a stream the stream itself throws it, before `:on-event`, with its own message prefix |
| end of events first | `:tools.agents.openai/stream-truncated` | `:outcome` |

**Subscribe before sending work.** Open `sessions-events-stream`, then
`send-message`; a turn that finishes before the stream opens is never seen.
A completed turn does not guarantee every tool succeeded: read the output.

**Recovering a disconnected stream** (guide): open a new stream and buffer
its events, read `sessions-retrieve` and `sessions-items-list` while it stays
connected, restore state by `item_id`, apply buffered updates for items not
yet final, then resume. `output_text.done` replaces a partial delta buffer.
This library does not automate that.

Polling remains available and needs no open connection. Poll
`sessions-retrieve`'s `"status"` until it leaves `"created"`/`"in_progress"`,
then read the turn:

```clojure
(-> (agents/sessions-turns-list client session-id)
    (agents/latest-root-turn))
;; => {"id" "turn_..." "status" "failed"
;;     "error" {"code" "credit_balance_exhausted"
;;              "message" "You have no credits remaining. ..."} ...}
```

A session is **not** evidence of success. Observed against the live API: a
turn that failed on `credit_balance_exhausted` left the session `"idle"`
with `"error"` nil, and its items held only the user's input message. OpenAI's
own events guide says the same: `agent.session.idle` means the session is
ready for more input, not that its last turn succeeded. The failure was
recorded only on the turn (`"status" "failed"`, `"error"`), and on the SSE
stream as `error` and `agent.session.turn.failed` events. The stream does not
replay missed events.

Two polling pitfalls follow:

- **Check the turn, not just the session.** `turn-finished?` is true for
  `"completed"`, `"failed"` and `"cancelled"`; only `"completed"` is success.
  `examples/openai/agents_sandbox_task.clj`'s `run-example` returns the turn
  with the output.
- **Follow-up input races the status.** Right after `send-message`, an idle
  session can still read `"idle"` before the new turn starts. Record
  `latest-root-turn`'s `"id"` before sending, then poll `sessions-turns-list`
  until a root turn with a different id is `turn-finished?`.

Items (`sessions-items-list`) carry the output of a completed turn. For a session that needs a function result or a
self-hosted environment connection mid-turn, `"requires_action"` plus the
retrieved session's `"required_actions"` array carries everything
`agent.session.requires_action` streams, pulled instead of pushed.

Webhooks (`agent.session.idle` etc.) are the other half of OpenAI's
recommended non-streaming story, but they need an HTTP endpoint of your
own to receive them and a generic (not Agents-API-specific) signature
verification step shared with every other OpenAI webhook type — both outside
a client library's job, so they are not implemented here either. Poll if you
don't already have a webhook receiver; wire one up yourself, following
OpenAI's [webhook guide](https://developers.openai.com/api/docs/guides/webhooks),
if you do.

### Self-hosted sandboxes — what this library does and does not do

"Own infrastructure for sandbox" means `environment.type: "self_hosted"`.
OpenAI's architecture there is: OpenAI runs the harness; **you** run
`codex exec-server` — a released `@openai/codex` CLI process — inside
compute you provision, which registers itself and then holds an *outbound*
WebSocket connection back to OpenAI to receive commands and return results.

This library's role stops at the REST boundary: `sessions-create` passes
`environment: {"type" "self_hosted" ...}` through like any other field (no
special-casing needed — the pass-through design handles it for free), and
`self-hosted-executor-command` is a **pure** function turning a created
session's `environment.id`/`environment.remote_url` into the exact
`["codex" "exec-server" "--remote" url "--environment-id" id]` argv OpenAI's
docs show for starting the executor. It does not spawn that process, hold
the WebSocket, or manage the executor's lifecycle — that is a released
external CLI holding a long-lived stateful connection, categorically
different work from this repo's one-request-per-call HTTP clients, and
squarely your own infrastructure's job (a process supervisor, a container
entrypoint, whatever already runs your workloads).

Setup, once, outside this library: create a *restricted* environment key on
the [Agents tab](https://platform.openai.com/agents?tab=environments&environment_view=keys)
of the platform dashboard (every permission except environment-connect set to
**None**), supply it to the environment as `CODEX_API_KEY` — **never** your
application's own `OPENAI_API_KEY`, which the executor's compute must not be
able to read — and allow outbound access to `https://api.openai.com`
(registration) and `wss://codex-cloud-environments.chatgpt.com` (the
command/result WebSocket). See
`examples/openai/agents_self_hosted.clj` for the full session-creation +
executor-command flow, and
[OpenAI's own self-hosted sandboxes guide](https://developers.openai.com/api/docs/guides/agents-api/environments/self-hosted)
for the rest (network policy, provider-specific file access, reconnection
after `agent.session.environment.failed`).

### JSON, retries, and the leaf-I/O split

All inherited, unmodified, from `tools.agents.openai` — see that doc's
"JSON: a small hand-rolled codec", "Retries", and "Isolating runtime-specific
I/O" sections. Requests go through the same shared
`tools.agents.http/request!`, with `:method` `:get`/`:post`/`:delete` since
sessions/items need GET and session deletion needs DELETE; it uses one
process-wide `HttpClient`, so a process using both namespaces still opens
one connection pool. Query strings are built by
`tools.agents.http/encode-params` (nil values are dropped; nested maps and
vectors use bracket syntax). `endpoint-url`, `status->type` and `extract-error-message`
are likewise called directly rather than duplicated — those four are public
(not `^:private`) in `tools.agents.openai` specifically so this namespace
never carries a second, driftable copy of any of them.

Every session/environment id this namespace's resource methods take is
URL-encoded before being spliced into a path — a corrupted or
untrusted-sourced id containing `/`, `?`, `#` or `%` cannot silently reroute
a request to a different endpoint.

## Testing

`test/tools/agents/openai/agents_test.cljc` is pure logic — zero I/O — for
this namespace's pure public functions: `latest-root-turn`/`turn-finished?`
(subagent turns skipped, no root turn yields nil, missing `"data"` throws,
terminal statuses only) and `items-output-text`
(message vs. non-message vs. non-assistant items, multi-turn concatenation, a
tool-only turn yielding `""`, and every malformed-input throw, including an
empty-string `environment.id`/`remote_url` on `self-hosted-executor-command`,
which must fail the same catchable way a missing key does) and
`self-hosted-executor-command` (the argv it builds, and every way a session
can be missing the fields it needs). It also tests the private
`path-segment`/`query-string` encoders directly via `#'agents/path-segment`
rather than through the mock server — `.getPath()` on JVM Clojure's
`com.sun.net.httpserver` decodes percent-escapes before a test ever sees
them, while Babashka's httpkit-backed mock does not, so the same encoded
request produces two different `:path` strings depending on the runtime;
testing the pure encoder directly sidesteps that asymmetry entirely.
Everything else here calls straight through to `tools.agents.openai/request!`,
tested in that namespace's suites, so there is nothing new to re-test in
isolation.

`test/tools/agents/openai/agents/live_test.cljc` runs the same local mock
server the sibling suites share (`tools.agents.test-support`): request
method/path/headers/body for every resource method (including the five
`agents-*` saved-agent methods, `nil` reaching `agents-update`'s body as JSON
null, and a 404 on `agents-retrieve` typed `not-found-error`), query-string building
for `sessions-list`/`agents-list`/`sessions-items-list`/`sessions-turns-list` (compared as parsed params, not an
exact string, since neither this library's `query-string` builder nor a
Clojure map's own iteration order guarantees key order), the exact wire
shape of `send-message`/`cancel-turn`/`send-tool-result`, non-2xx errors
mapping through the same status table `tools.agents.openai` uses, the retry
loop actually firing through `send-request!` (the thin adapter over
`tools.agents.openai/request!`), connection
failures, `:stream true` rejected by `sessions-create` before any network activity (both string-
and keyword-keyed), a missing-credentials guard on a hand-built client map,
and both `examples/openai/agents_*.clj` files run end-to-end against the mock
server — including the live no-credits shape (session `"idle"`, items holding
only the input, the turn `"failed"` with `credit_balance_exhausted`) surfacing
through `run-example`'s `:turn`, and the polling example's timeout path, with `sleep-fn`
injected so none of it costs real wall-clock.

One test in that file hits the real API:
`agents-crud-round-trip-against-the-real-api` runs create → retrieve →
update → list contains → delete → retrieve 404. Listing is eventually
consistent (a new agent appeared after ~5 s live), so the list step polls for
up to 60 s. Verified live on 2026-09-15. It runs no inference (no
model tokens) and passes as skipped unless both `OPENAI_AGENTS_LIVE=1` and
`OPENAI_API_KEY` are set. It
targets `https://api.openai.com/v1` (override: `OPENAI_AGENTS_BASE_URL`;
model: `OPENAI_AGENTS_MODEL`, default `gpt-5.5`).

Artifacts and environment files: the mock suite covers method/path/beta
header/query for all six methods, a binary round trip of every byte value
plus invalid UTF-8 sequences through `sessions-artifacts-content`, a 302 on
content surfacing as `api-status-error` without a second request, `page`/`next`
token paging across two pages, the create body for a base64 String, `byte[]`,
`File`, `Path` and `file_id`, rejection of unsupported `"data"` before any
network I/O, and 404s typed `not-found-error`.
`artifacts-and-environment-files-probe-against-the-real-api` is probe P1, same
gate and overrides: an `openai_hosted` session whose turn writes
`probe-\0\377` to `/workspace/outputs/p.bin`; while the environment is
connected it creates `/workspace/in.txt` and pages env files with `limit` 1;
after the turn it lists, retrieves, downloads (byte-exact), and deletes the
artifact, then expects a 404. Cleanup cancels and deletes the session,
retrying a 409. It costs a short turn; it passed live on 2026-09-15.

Event streaming (ports `19041`–`19048`): `sessions-events-stream` sends GET,
`Accept: text/event-stream`, the beta header and `stream=true` alongside
caller params; events reach the reducer while the server is still blocked
(incremental delivery); the synthetic `test/resources/sse/agents-turn.sse`
fixture decodes verbatim across its eleven event types; an `event:`/`id:` frame,
a blank keep-alive and `[DONE]` are handled; `(into [] (take 2) s)` makes the
server see a disconnect and a second reduce throws `consumed`; a 404 before
the stream is `not-found-error` without retry; a 503 is retried before the
first byte; a mid-stream abort is `api-connection-error`;
`sessions-create-stream` POSTs `"stream": true`, and `await-root-turn`
returns the completed turn and closes the still-open connection; a body
ending before the terminal event is `stream-truncated` with `:outcome :eof`.
`root-turn-finished?` and `await-root-turn`'s rules (root vs subagent,
failed, cancelled, session/environment failed, `error`, truncation) are
tested over plain collections.
`session-event-streaming-against-the-real-api` (same gate; costs two short
turns on an `environment: none` session) streams `sessions-create-stream`
to completion, then opens `sessions-events-stream` before `send-message` and
awaits the second turn. It **has not been run yet**.

Port range `19000`–`19079` — chosen not to collide with the sibling suites'
ranges (anthropic `18930`–`18975`, gemini `18980`–`18997`, openai
`18950`–`18971`).

### Running the tests

```
./script/test-all.sh
```

## License

Apache License 2.0 — see `LICENSE`.
