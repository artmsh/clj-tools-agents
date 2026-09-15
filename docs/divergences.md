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
| `:base-url` shape | host only, default `https://api.anthropic.com`; library appends `/v1/messages` | **includes `/v1`**, default `https://api.openai.com/v1`; library appends `/responses` | the openai client's `:base-url`; library appends `/agents/sessions/…` | host only, default `https://generativelanguage.googleapis.com`, plus a **separate `:api-version`** (default `v1beta`): `{base}/{version}/models/{model}:{method}` |
| base-url env var | `ANTHROPIC_BASE_URL` | `OPENAI_BASE_URL` | inherited from the openai client | `GOOGLE_GEMINI_BASE_URL`, this library's own name, not an SDK one |
| credential env vars | `ANTHROPIC_API_KEY`, then `ANTHROPIC_AUTH_TOKEN` | `OPENAI_API_KEY` | inherited from the openai client | `GOOGLE_API_KEY`, then `GEMINI_API_KEY` |
| auth header | `x-api-key` **or** `Authorization: Bearer` + `anthropic-beta: oauth-2025-04-20`, chosen by which credential resolved | always `Authorization: Bearer` | as openai, **plus `OpenAI-Beta: agents=v1`** on every request | always `x-goog-api-key`, bare key, no scheme prefix |
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
| retryable statuses | 408, 409, 429, any 5xx; plus connection failures | 408, 409, 429, any 5xx, unless `x-should-retry` overrides; plus connection failures | as openai | **408, 429, 500, 502, 503, 504** (no 409, no 501/505+); plus connection failures |
| backoff curve | `0.5s × 2^n`, cap 8s, `× (1 − 0.25·rand)`, float seconds | same curve in integer ms | as openai | `min(60s, 1s × 2^n + U[0, 1s))`, integer ms |
| server retry headers | `retry-after` as seconds only (no `-ms`, no HTTP-date), clamped to `[0, 60]`s, wins outright; `retry-after: 0` retries immediately | `retry-after-ms` > `retry-after` seconds > `retry-after` HTTP-date; honored within `(0, 120]`s, a value `≤ 0` falls back to computed backoff, **> 120s vetoes the retry**; `x-should-retry: true`/`false` overrides the status | as openai | **none**: python-genai's retry predicate inspects no headers |
| retry-policy visibility | decision functions private; `request-with-retries!` and `*sleep-fn*` public | `should-retry?`, `retryable-status?`, `retry-delay-ms`, `parse-retry-after-ms` public and pure; the loop itself is public as `request!`; no `*sleep-fn*` | none of its own; every request goes through openai's `request!`; no `*sleep-fn*` | `retryable-status?`, `retry-delay-ms` and `*sleep-fn*` public; no `should-retry?` |
| streaming | `:stream true` on `messages-create` throws `:tools.agents.anthropic.error/streaming-unsupported` | `:stream true` throws `:tools.agents.openai/streaming-unsupported` | a `stream` true in any request body throws `:tools.agents.openai/streaming-unsupported`; poll turns instead | no flag to refuse: **`generate-content-stream`** hits the separate `:streamGenerateContent?alt=sse` endpoint and returns a single-use reducible of chunk maps; `accumulate-stream` joins them. **No terminal event**: `stream/outcome` is `:eof` for complete and truncated streams alike, so check `stream-complete?` (`finishReason`/`blockReason`); see [gemini.md](gemini.md#streaming) |

Shared by all four: the JSON codec, `tools.agents.json` (verbatim
string/keyword map keys, no case conversion; only the error `:type` keywords
and message prefixes are per client), the single runtime-specific leaf for network I/O
behind `#?(:bb … :clj …)`, typed `ex-info` errors with a status→`:type`
mapping (the keyword spellings differ, see above), and the two-runtime test
harness.

Per-client background for these rows: [anthropic.md](anthropic.md#retries),
[openai.md](openai.md#retries), [openai-agents.md](openai-agents.md#error-hierarchy),
[gemini.md](gemini.md#retries).
