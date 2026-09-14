# tools.agents.openai.agents

A pure-Clojure client for [OpenAI's Agents API](https://developers.openai.com/api/docs/guides/agents-api/overview)
(beta, September 2026) — the managed Codex harness that runs sessions and
turns, optionally inside an OpenAI-hosted or self-hosted sandbox — modeled on
openai-python's `client.beta.agents.*` resource tree.

**Sibling of [tools.agents.openai](openai.md), not a peer library.** It takes
the exact same `OpenAIClient` record `tools.agents.openai/client` builds and
reuses that namespace's JSON codec and retry-policy functions verbatim,
because the Agents API lives under the same `https://api.openai.com/v1` root
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

Two things the docs show only as SDK method calls, with no literal REST path
or JSON schema anywhere — saved/reusable `agent` CRUD
(`client.beta.agents.create/list/retrieve/delete`, distinct from a
*session's* inline `agent` config, which `sessions-create` passes through
fine) and the session/environment Artifacts and Files APIs — are deliberately
**not implemented**, rather than guessed at. Both are additive; nothing here
would need to change to add them once their wire shape is confirmed the same
way.

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

;; No streaming (see below) — poll the turn, not the session: a failed turn
;; also leaves the session "idle", and the session can settle before the turn
;; list shows a finished turn. (A turn "waiting" on a function result never
;; finishes on its own; see the example's poll-until-done for that case.)
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
```

See `examples/openai/agents_sandbox_task.clj` for the complete polling loop
(as a reusable `poll-until-done`, with an injectable sleep function so the
test suite runs it instantly against a mock server) and
`examples/openai/agents_self_hosted.clj` for the self-hosted sandbox flow.

## Design notes

### Parity with openai-python's `client.beta.agents.*`

| openai-python | tools.agents.openai.agents | Notes |
|---|---|---|
| `client.beta.agents.sessions.create(**params)` | `(sessions-create client request)` | `request` passed through to JSON almost verbatim. Throws immediately on `stream: true` — see Streaming below. |
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
| `item.content[…].text` traversal (no single SDK helper — see below) | `(items-output-text items-response)` | Concatenates every assistant `output_text` block, oldest-first when called with `{"order" "asc"}`. See its own docstring for one deliberate divergence from `tools.agents.openai/output-text`'s stricter contract. |
| `GET /v1/agents/environments/{id}` (literal endpoint text, not an SDK example) | `(environments-retrieve client environment-id)` | Poll an `openai_hosted` sandbox's provisioning state: `"provisioning"` → `"connected"`/`"failed"`. |
| `codex exec-server --remote ... --environment-id ...` (shell, not an SDK call) | `(self-hosted-executor-command session)` | Pure function from a created self-hosted session to the executor's argv — see Self-hosted sandboxes below for what this library does and does not do here. |
| `client.beta.agents.create/list/retrieve/delete` (saved, reusable agent config) | *(not implemented)* | No literal REST path or schema shown anywhere in OpenAI's docs — see the primary-source note above. `sessions-create`'s inline `"agent"` / `"agent_id"` fields are unaffected and pass through fine. |
| Artifacts / environment Files APIs | *(not implemented)* | Same reason. |
| `client.beta.agents.sessions.create(..., stream=True)` / `.events.stream(...)` | **rejected outright** / *(not implemented)* | See Streaming below. |

### Credentials & headers

Identical to `tools.agents.openai` in every respect but one: every request
here also carries `OpenAI-Beta: agents=v1`. Build a client with
`tools.agents.openai/client` and pass it to both namespaces; there is no
separate `agents/client` constructor.

### Error hierarchy

Every resource method here throws the exact same `:type` keywords
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

The message prefix, however, IS this namespace's own —
`"tools.agents.openai.agents/sessions-create: HTTP 429 ..."` — so you can
still tell which library's resource method actually failed.

### Streaming is not supported — poll instead

`sessions-create`'s `:stream true` / `"stream" true` throws
`{:type :tools.agents.openai/streaming-unsupported}` immediately, before any
network request — the same refusal `tools.agents.openai/responses-create`
gives, for the same reason: SSE is out of scope for a client built around one
synchronous request/response leaf per call. There is also no
`GET .../sessions/{id}/events` long-poll implemented.

The trade-off this makes: a turn's progress events (intermediate tool calls,
partial text) are invisible to this client. The turn's *outcome* is still
readable, but not from the session. Poll `sessions-retrieve`'s `"status"`
until it leaves `"created"`/`"in_progress"`, then read the turn:

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
`agent.session.requires_action` would have streamed, just pulled instead of
pushed.

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
I/O" sections. This namespace's own `http-request!` leaf is the same
`#?(:bb ... :clj ...)` shape as `tools.agents.openai/http-post!`, generalized
to a `method` argument (`:get`/`:post`/`:delete`) since sessions/items need
GET and session deletion needs DELETE, alongside the POST both APIs share —
and it dereferences `tools.agents.openai/bb-http-client`/`jvm-http-client`
directly rather than building a second `HttpClient`, so a process using both
namespaces (this doc's own recommended usage) still opens exactly one
connection pool. `endpoint-url`, `status->type` and `extract-error-message`
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
Everything else here calls straight through to `tools.agents.openai`'s
already-tested pure retry functions, so there is nothing new to re-test in
isolation.

`test/tools/agents/openai/agents/live_test.cljc` runs the same local mock
server the sibling suites share (`tools.agents.test-support`): request
method/path/headers/body for every resource method, query-string building
for `sessions-list`/`sessions-items-list`/`sessions-turns-list` (compared as parsed params, not an
exact string, since neither this library's `query-string` builder nor a
Clojure map's own iteration order guarantees key order), the exact wire
shape of `send-message`/`cancel-turn`/`send-tool-result`, non-2xx errors
mapping through the same status table `tools.agents.openai` uses, the retry
loop actually firing through this namespace's own transport leaf, connection
failures, `:stream true` rejected before any network activity (both string-
and keyword-keyed), a missing-credentials guard on a hand-built client map,
and both `examples/openai/agents_*.clj` files run end-to-end against the mock
server — including the live no-credits shape (session `"idle"`, items holding
only the input, the turn `"failed"` with `credit_balance_exhausted`) surfacing
through `run-example`'s `:turn`, and the polling example's timeout path, with `sleep-fn`
injected so none of it costs real wall-clock.

Port range `19000`–`19039` — chosen not to collide with the sibling suites'
ranges (anthropic `18930`–`18975`, gemini `18980`–`18997`, openai
`18950`–`18971`).

### Running the tests

```
./script/test-all.sh
```

## License

Apache License 2.0 — see `LICENSE`.
