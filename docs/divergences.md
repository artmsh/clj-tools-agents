# Cross-client divergences

`tools.agents.anthropic`, `tools.agents.openai`, `tools.agents.openai.agents`
and `tools.agents.gemini` share one architecture, but each client follows its
own vendor SDK (anthropic-sdk-python, openai-python, python-genai), so a
number of caller-visible contracts differ on purpose. If you use more than
one client, these are the places not to assume symmetry. This table is the
only place these differences are recorded; the per-client docs link here.

`openai.agents` is built on a `tools.agents.openai/client` value and reuses
that namespace's `request!` (codec, retry loop and error typing), so "as
openai" in its column means the same code path, not a copy.

| contract | anthropic | openai | openai.agents | gemini |
|---|---|---|---|---|
| model location | request body `"model"` | request body `"model"` | request body (`sessions-create`'s `"agent"` map passes through) | **URL path**: separate `model` argument, never a request key; a bare id gets `models/` prepended, `models/…`/`tunedModels/…` used verbatim |
| `:base-url` shape | host only, default `https://api.anthropic.com`; library appends `/v1/messages` | **includes `/v1`**, default `https://api.openai.com/v1`; library appends `/responses` | the openai client's `:base-url`; library appends `/agents/…` (sessions, environments, templates) and, for vaults, root `/vaults/…` — not under `/agents` although the SDK nests them at `client.beta.agents.vaults` | host only, default `https://generativelanguage.googleapis.com`, plus a **separate `:api-version`** (default `v1beta`): `{base}/{version}/models/{model}:{method}` |
| base-url env var | `ANTHROPIC_BASE_URL` | `OPENAI_BASE_URL` | inherited from the openai client | `GOOGLE_GEMINI_BASE_URL`, this library's own name, not an SDK one |
| credential env vars | `ANTHROPIC_API_KEY`, then `ANTHROPIC_AUTH_TOKEN`, then an explicitly selected profile (`ANTHROPIC_PROFILE`, `ANTHROPIC_CONFIG_DIR` or `active_config`), then Workload Identity Federation (`ANTHROPIC_FEDERATION_RULE_ID` + `ANTHROPIC_ORGANIZATION_ID` + `ANTHROPIC_IDENTITY_TOKEN[_FILE]`), then `~/.config/anthropic/configs/default.json`; profiles and WIF are refreshable sources | `OPENAI_API_KEY` | inherited from the openai client | `GOOGLE_API_KEY`, then `GEMINI_API_KEY` |
| auth header | `x-api-key` **or** `Authorization: Bearer` + `anthropic-beta: oauth-2025-04-20`, chosen by which credential resolved (`:credential-source` → Bearer + oauth beta) | always `Authorization: Bearer` | as openai, **plus `OpenAI-Beta: agents=v1`** on every request | `x-goog-api-key`, bare key, no scheme prefix; a `:credential-source` token goes in `Authorization: Bearer` instead |
| `:credential-source` (`tools.agents.token`) | conflict with `:api-key`/`:auth-token` throws `:tools.agents.anthropic.error/invalid-credentials`; 401 → invalidate + one retry via `request-with-retries!`'s `:on-unauthorized` | conflict with `:api-key` throws `:tools.agents.openai/invalid-credentials`; 401 → invalidate + one retry in `request!`. **Callable `:api-key`** (openai only, the SDK's callable `api_key`) is wrapped as a source called per attempt, no cache, whose `invalidate!` declines, so its 401 is not retried; a non-string/blank return throws `:tools.agents.openai/invalid-api-key`. **Workload identity** (`tools.agents.openai.credentials/workload-identity-source`, the SDK's `workload_identity`) is a token-cache source: exchange at `auth.openai.com/oauth/token`, 1200 s refresh buffer, 401 → re-exchange once; no env vars | inherited from the openai client | conflict with `:api-key` throws `:tools.agents.gemini/invalid-credentials`; 401 → invalidate + one retry |
| injected `:http` | every exchange, batches, and the profile / WIF token exchanges resolved inside `client`; a pre-built `:credential-source` uses its own `:http-fn`; non-fn → `:tools.agents.anthropic.error/invalid-options` | every `request!` attempt (all resource namespaces, streaming); `workload-identity-source` and the Azure/GCP metadata providers take their own `:http`, never the client's; non-fn → `:tools.agents.openai/invalid-options` | inherited from the openai client | every exchange, streaming included; non-fn → `:tools.agents.gemini/invalid-options` |
| injected `:json` | request bodies, responses, error bodies, stream events, `batches-results`; errors → `:tools.agents.anthropic.error/json-parse` / `json-encode`; built-in stays for `accumulate-event` tool input, `visualize`, credential files and exchanges | `request!` (all resource namespaces, streams), `batches-results`, `webhooks/unwrap` with `:client`; errors → `:tools.agents.openai/json-parse-error` / `json-encode-error`; built-in stays for `batch-input-jsonl` and WIF exchanges | inherited from the openai client | request bodies, responses, error bodies, stream chunks; errors → `:tools.agents.gemini/json-parse-error` / `json-encode-error` |
| request-key casing | snake_case verbatim (`max_tokens`) | snake_case verbatim (`max_output_tokens`) | snake_case verbatim | **camelCase** verbatim (`maxOutputTokens`), the REST API's own field names |
| HTTP methods | GET, POST, DELETE (`messages-create`/`count-tokens` POST; `anthropic.batches` GET/POST/DELETE); `request!` takes any method | POST only in its resource methods; `request!` takes any method | **GET, POST, DELETE** | POST only |
| no text in the response | `output-text` **throws** `:tools.agents.anthropic.error/no-text-content` | `output-text` **returns `""`** (`Response.output_text`'s contract); a malformed shape still throws | `items-output-text` **returns `""`**; lenient where openai is strict: a message item's non-array `"content"` counts as empty; a missing `"data"` array or non-string `"text"` still throws | `output-text` **returns `nil`** and never throws, including on malformed shapes (`GenerateContentResponse.text`'s `None`); see [gemini.md](gemini.md#output-text-returns-nil-never-throws) |
| message-list helpers | `add-user-message`, `add-assistant-message`, `add-tool-result(s)` | `add-user-message`, `add-assistant-message`, `add-developer-message`, `add-system-message` | none; `send-message` posts a session event | `add-user-message`, `add-model-message` (`user`/`model` roles; system prompt is the top-level `systemInstruction`) |
| error `:type` keywords | `:tools.agents.anthropic.error/<name>`, **no `-error` suffix** (`rate-limit`, `api-status`) | `:tools.agents.openai/<name>-error` | **openai's keywords verbatim** (`:tools.agents.openai/*`); only the message prefix is `tools.agents.openai.agents/<fn>: ` | `:tools.agents.gemini/<name>-error`; 400 is `invalid-argument-error`, 429 is `resource-exhausted-error` |
| 409 | generic `:tools.agents.anthropic.error/api-status` (the SDK has no `ConflictError`) | `:conflict-error` | `:tools.agents.openai/conflict-error` | generic `:api-status-error` |
| 422 | `:unprocessable-entity` | `:unprocessable-entity-error` | as openai | generic `:api-status-error` |
| error `ex-data` | `{:type :status :body :headers}`; no `:retries-taken`; success responses carry response headers as metadata | `{:type :status :body :retries-taken}` | as openai | as openai |
| default `:max-retries` | 2 | 2 | 2 (openai's default) | **4** (python-genai's `_RETRY_ATTEMPTS = 5` counts the initial call) |
| invalid `:max-retries` | **`client` throws** `:tools.agents.anthropic.error/invalid-max-retries` unless a non-negative integer; a per-call `assoc` is not re-validated | **never throws**: negatives clamp to `0`, fractions truncate, a non-number falls back to the default; re-applied per request, so `(assoc client :max-retries n)` is normalized too | as openai | as openai |
| default timeouts | connect 5 s, request 600 s (the SDK's `DEFAULT_TIMEOUT`) | connect 5 s, request 600 s (the SDK's `DEFAULT_TIMEOUT`) | as openai | connect 5 s, request 600 s: **python-genai has no default timeout** (`HttpOptions.timeout` None); this library applies the other SDKs' values, and sends no `X-Server-Timeout` header |
| timeout semantics | `:timeout-ms` is a deadline on the whole response for non-streaming calls and on the response headers only for streams (a stream body is never timed); the SDKs' httpx 600 s is a per-read idle timeout, which the JDK does not offer. nil disables; `(assoc client :timeout-ms n)` per call; `request!` opts per request | as anthropic | as openai | as anthropic, per call only (no public `request!`) |
| timeout error | `:api-connection` + `:timeout? true`, cause the `HttpTimeoutException`; retried | `:api-connection-error` + `:timeout? true`; retried | as openai | `:api-connection-error` + `:timeout? true`; retried |
| retryable statuses | 408, 409, 429, any 5xx; plus connection failures | 408, 409, 429, any 5xx, unless `x-should-retry` overrides; plus connection failures | as openai | **408, 429, 500, 502, 503, 504** (no 409, no 501/505+); plus connection failures |
| backoff curve | `0.5s × 2^n`, cap 8s, `× (1 − 0.25·rand)`, float seconds | same curve in integer ms | as openai | `min(60s, 1s × 2^n + U[0, 1s))`, integer ms |
| server retry headers | `retry-after` as seconds only (no `-ms`, no HTTP-date), clamped to `[0, 60]`s, wins outright; `retry-after: 0` retries immediately | `retry-after-ms` > `retry-after` seconds > `retry-after` HTTP-date; honored within `(0, 120]`s, a value `≤ 0` falls back to computed backoff, **> 120s vetoes the retry**; `x-should-retry: true`/`false` overrides the status | as openai | **none**: python-genai's retry predicate inspects no headers |
| retry-policy visibility | decision functions private; `request-with-retries!` and `*sleep-fn*` public | `should-retry?`, `retryable-status?`, `retry-delay-ms`, `parse-retry-after-ms` public and pure; the loop itself is public as `request!`; no `*sleep-fn*` | none of its own; every request goes through openai's `request!`; no `*sleep-fn*` | `retryable-status?`, `retry-delay-ms` and `*sleep-fn*` public; no `should-retry?` |
| streaming | **`messages-stream`** POSTs `/v1/messages` with `"stream": true` and returns a single-use reducible of event maps; `accumulate-stream` rebuilds the Message as the SDK's `accumulate_event`. **Terminal event** `message_stop`, but `stream/outcome` is still `:eof` for complete and truncated streams: `stream-complete?` is true iff `message_stop` was seen; truncation never throws. An in-stream `error` event throws, typed from `error.type` (streaming-only `:overloaded`; see [In-stream error events](#in-stream-error-events)). `:stream true` on `messages-create` still throws `:tools.agents.anthropic.error/streaming-unsupported`; see [anthropic.md](anthropic.md#streaming) | **`responses-stream`** returns a single-use reducible of decoded event maps, opened and retried at call time through `request!`; `accumulate-response-stream` returns the terminal event's Response (`failed`/`incomplete` returned, not thrown) or one assembled from deltas; `stream/outcome` is `:eof` either way, so check `stream-complete?`; **`chat-completions-stream`** ends at `data: [DONE]` (`stream/outcome` `:done`) and `accumulate-chat-completion-stream` returns a ChatCompletion-shaped map (`completion-text` works; `stream-complete?` = `[DONE]` seen, when given the stream); `error` events / error chunks throw `:tools.agents.openai/stream-error` ([In-stream error events](#in-stream-error-events)); `:stream true` on `responses-create` / `chat-completions-create` still throws `:tools.agents.openai/streaming-unsupported`; see [openai.md](openai.md#streaming) | `sessions-events-stream` (GET `.../events?stream=true`) and `sessions-create-stream` return a single-use reducible of decoded event maps, opened and retried at call time; an `error` event throws `:tools.agents.openai/stream-error` from the reduce, as on openai; `await-root-turn` also raises on root turn failure and on truncation (`:tools.agents.openai/stream-truncated`); a `stream` true body on any other method still throws `:tools.agents.openai/streaming-unsupported` | no flag to refuse: **`generate-content-stream`** hits the separate `:streamGenerateContent?alt=sse` endpoint and returns a single-use reducible of chunk maps; `accumulate-stream` joins them; an error chunk throws, typed from `error.code` ([In-stream error events](#in-stream-error-events)). **No terminal event**: `stream/outcome` is `:eof` for complete and truncated streams alike, so check `stream-complete?` (`finishReason`/`blockReason`); see [gemini.md](gemini.md#streaming) |

## In-stream error events

One rule covers every `*-stream` function (`anthropic/messages-stream`,
`openai/responses-stream`, `openai/chat-completions-stream`,
`openai.agents/sessions-events-stream` and `sessions-create-stream`,
`gemini/generate-content-stream`): an error event is never handed to the
reducer. It throws a typed `ex-info` from the reduce at the point the event
is decoded, so every event before it has already reached the reducer and
nothing after it does; the reduce's `finally` closes the connection,
`(tools.agents.stream/outcome s)` is `:failed`, and nothing is retried. The
ex-data always carries `:status` (nil, except gemini, below), `:body` (the raw
`data:` string), `:error` (the wire error object) and `:event` (the decoded
event, nil only for anthropic's non-JSON error data). Each pure accumulator
that can be fed a plain collection (`anthropic/accumulate-event`,
`openai/accumulate-response-event`, `openai/accumulate-chat-completion-chunk`,
`gemini/accumulate-chunk`, `openai.agents/await-root-turn`) throws the same
`:type` for the same event, with `:body` nil, so a fixture reduced without
the transport fails identically. Over a stream, `await-root-turn`'s
`:on-event` never sees the error event, because the stream throws first.

The rule is what all three SDKs do, read at their `main` heads:

- anthropic-sdk-python `src/anthropic/_streaming.py` (@ 7e5ca5c)
  `Stream.__stream__` raises `_make_status_error(...)` on `sse.event ==
  "error"`, falling back to the raw `sse.data` as body when it is not JSON.
- openai-python `src/openai/_streaming.py` (@ d421d7a) `Stream.__stream__`
  raises `APIError` on any decoded data whose top-level `"error"` is truthy
  (outside Assistants `thread.*` frames). The agents resources stream through
  that same generic class (`resources/beta/agents/sessions/events.py` and
  `sessions.py`, `stream_cls=Stream[AgentSessionEvent]`), and the agents
  `error` event (`types/beta/agent_session_error_event.py`) carries a required
  top-level `error: SessionError`, so the SDK raises there too; its
  `lib/streaming/agents` `AgentSessionStream` iterates that `Stream` and
  never sees the event.
- python-genai `google/genai/_api_client.py` (@ b88fded)
  `BaseApiClient.request_streamed` raises `APIError.raise_error(code, ...)` on
  a chunk whose JSON starts with `{"error":`.

MCP Streamable HTTP (`tools.agents.mcp.http`) is outside this rule: its SSE
stream carries JSON-RPC messages, and an error there is the `"error"` member
of the response to one request, surfaced by that request's call, not a
stream-level event.

What still differs per client is typing, each following its SDK or the
provider's documented error table:

| | anthropic | openai / openai.agents | gemini |
|---|---|---|---|
| detected on | SSE event name `error`, or data `"type": "error"` (the SDK checks the name only) | a truthy top-level `"error"`; plus the Responses `error` event `{"type" "error" "code" "message" "param"}`, which has no `"error"` key: openai-python **yields** it as `ResponseErrorEvent`, this client throws it so the rule holds (`:error` is then its `code`/`message`/`param`) | a top-level `"error"` map anywhere in the chunk (the SDK needs it first in the serialized JSON) |
| `:type` | from `error.type` (`overloaded_error` → `:overloaded`, ...); the SDK's status-only dispatch sees 200 and raises a plain `APIStatusError` | always `:tools.agents.openai/stream-error` (`APIError`) | `error.code` taken as the status (`APIError.raise_error(code)`), which also fills `:status` |
| extra ex-data | `:headers` (the 2xx response's), `:error-type` | none | `:retries-taken` |
| message | `<fn>: stream error <error.type> <message>` | `<fn>: stream error: <message>` | `<fn>: stream error <code> <message>` |

Shared by all four: the default JSON codec, `tools.agents.json` (verbatim
string/keyword map keys, no case conversion; only the error `:type` keywords
and message prefixes are per client), the single runtime-specific leaf for network I/O
behind `#?(:bb … :clj …)`, typed `ex-info` errors with a status→`:type`
mapping (the keyword spellings differ, see above), and the two-runtime test
harness.

Per-client background for these rows: [anthropic.md](anthropic.md#retries),
[openai.md](openai.md#retries), [openai-agents.md](openai-agents.md#error-hierarchy),
[gemini.md](gemini.md#retries).
