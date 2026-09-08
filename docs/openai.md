# tools.agents.openai

A pure-Clojure client for [OpenAI's Responses and Chat Completions
APIs](https://platform.openai.com/docs/api-reference/), ergonomically modeled
on the official [openai-python](https://github.com/openai/openai-python)
client: an `OpenAIClient` record standing in for `OpenAI(...)`,
`responses-create` / `chat-completions-create` standing in for
`client.responses.create(**params)` / `client.chat.completions.create(**params)`,
and a typed error hierarchy matching the SDK's exception classes.

Sibling of [tools.agents.anthropic](anthropic.md) in this same repo — same
architecture (single leaf for network I/O, hand-rolled portable JSON codec,
`ex-info`-with-`:type` error hierarchy, two-runtime test matrix). The
handful of places where the two libraries deliberately behave *differently*
are called out in "Divergences from tools.agents.anthropic" below; they all come
from following the respective vendor SDK rather than each other.

## Usage

```clojure
(require '[tools.agents.openai :as oai])

(def client (oai/client {:api-key (System/getenv "OPENAI_API_KEY")}))

;; Responses API (preferred — this is what openai-python's README leads with)
(-> (oai/responses-create client
      {"model" "gpt-5.5"
       "instructions" "You are a coding assistant that talks like a pirate."
       "input" "How do I check if a Python object is an instance of a class?"})
    (oai/output-text))
;; => "Arr, ye be wantin' `isinstance(obj, SomeClass)`, matey!"

;; Chat Completions API (legacy surface, still fully supported)
(-> (oai/chat-completions-create client
      {"model" "gpt-5.5"
       "messages" (-> []
                      (oai/add-developer-message "Talk like a pirate.")
                      (oai/add-user-message "How do I check if a Python object is an instance of a class?"))})
    (oai/completion-text))
```

`client` resolves credentials eagerly — explicit `:api-key` first, then
`OPENAI_API_KEY`, else it throws a catchable `ex-info` **before any network
request is attempted** (fail fast). Failed requests are retried per
openai-python's own policy (`:max-retries`, default 2 — see Retries). The
`request` map is passed through to
JSON almost verbatim — `model`, `input`, `instructions`, `max_output_tokens`,
`temperature`, `reasoning`, `text`, `tools`, `store`, `previous_response_id`
etc. all pass straight through untouched, with no kebab↔snake key conversion
(see the parity table below).

See `examples/` for three complete, runnable-against-a-mock-server ports of
real openai-python usage shapes (Responses-API basic chat, legacy Chat
Completions with a `developer` turn, and a custom-gateway `base_url` +
org/project client).

### `base_url` carries `/v1` — this is not the sibling's shape

openai-python's default `base_url` is **`https://api.openai.com/v1`**, path
segment included, and the SDK appends only `/responses` /
`/chat/completions` to it. `tools.agents.openai` does the same. (Contrast
anthropic-sdk-python, whose `base_url` is host-only and which appends the
full `/v1/messages`.) So when pointing at a gateway, include the `/v1`
yourself, exactly as the Python README's own example does:

```clojure
(oai/client {:base-url "http://my.test.server.example.com:8083/v1"})
```

## Design notes

### Parity with openai-python

| openai-python | tools.agents.openai | Notes |
|---|---|---|
| `OpenAI(api_key=..., organization=..., project=..., base_url=...)` | `(client {:api-key ... :organization ... :project ... :base-url ...})` | Returns an `OpenAIClient` record with map-style keyword access. |
| `client.responses.create(**params)` | `(responses-create client params)` | `params` is a plain map passed through to JSON almost verbatim. |
| `client.chat.completions.create(**params)` | `(chat-completions-create client params)` | Same contract, `/chat/completions` path. |
| `response.output_text` | `(output-text response)` | Direct port of the SDK property, **including its empty-string-when-absent contract** — see below. |
| `completion.choices[0].message.content` | `(completion-text completion)` | Not an SDK method (Python spells it as attribute access) — just the shortest portable spelling of the same traversal. Returns `nil` when `content` is JSON `null` (tool-call / refusal responses), matching `.content` being `None` there. |
| dict key `"max_output_tokens"` | map key `"max_output_tokens"` or `:max_output_tokens` | Both string and keyword keys are accepted and encoded **verbatim** via `name` — no kebab-case↔snake_case conversion. Write `:max_output_tokens`, not `:max-output-tokens`. |
| `openai.APIError` / `.APIStatusError` / `.RateLimitError` / `.ConflictError` / etc. | `ex-info` with `:type` in `ex-data` | See the error-hierarchy table below — one exception constructor discriminated by `:type`, rather than a Python-style class hierarchy (there is no `class` in Clojure to mirror it with). |
| `OPENAI_API_KEY` / `OPENAI_ORG_ID` / `OPENAI_PROJECT_ID` / `OPENAI_BASE_URL` | same env vars, same precedence | Explicit arg wins over env in every case. |
| `OpenAI-Organization` / `OpenAI-Project` headers | same headers | Emitted **only** when set — the SDK sends `Omit()` for them otherwise, and so does this library (absent, never present-and-empty). |
| `client.with_options(...)` (general form) | `(assoc client ...)` | Per-request override without mutating the client. `OpenAIClient` supports associative updates, so callers use `(assoc client :base-url ...)` / `(assoc client :max-retries 5)` themselves. |
| `OpenAI(max_retries=2)` / automatic backoff | `(client {:max-retries 2})`, default `default-max-retries` = 2 | Implemented — connection failures, 408, 409, 429 and 5xx are retried with the SDK's exact backoff, jitter and `Retry-After` handling. See Retries below. |
| `client.with_options(max_retries=5)` | `(assoc client :max-retries 5)` | The client record is associative, so the per-call override needs no dedicated API. |
| `timeout` (default 10 min) / `APITimeoutError` | *(not implemented)* | Each runtime's HTTP leaf uses its own default timeout; a timeout surfaces as `:tools.agents.openai/api-connection-error` (which is also where Python's `APITimeoutError` sits in the hierarchy, as a subclass of `APIConnectionError`) and is retried like any other transport failure, exactly as the SDK does. |
| `client.responses.create(..., stream=True)` / `client.responses.stream(...)` | **rejected outright** | See Streaming below. |
| `client.embeddings.*` / `.images.*` / `.files.*` / `.batches.*` / `.fine_tuning.*` / Assistants / Realtime / webhooks | *(not implemented)* | Only the two text-generation resource methods are in scope for this port. `post-json!` is the shared transport, so adding another POST resource is a two-line change. |
| `admin_api_key` / `OPENAI_ADMIN_KEY`, Workload Identity Federation, `AzureOpenAI` | *(not implemented)* | The credential chain here is explicit `:api-key` → `OPENAI_API_KEY` → throw. The SDK's fuller chain (admin keys, token-exchange workload identity, Azure's separate deployment/api-version routing) is out of scope. |

### Credential resolution & headers (confirmed from openai-python source)

Precedence, first match wins: explicit `:api-key` → `OPENAI_API_KEY` env var
→ throw.

- Auth is always `Authorization: Bearer <api-key>` — there is no `x-api-key`
  path here, and no beta header (both of which the Anthropic sibling needs).
- `:organization` (or `OPENAI_ORG_ID`) → `OpenAI-Organization` header,
  emitted only when set.
- `:project` (or `OPENAI_PROJECT_ID`) → `OpenAI-Project` header, emitted only
  when set.
- Every request also sends `content-type: application/json`.
- `:base-url` defaults to `https://api.openai.com/v1` (also resolvable from
  `OPENAI_BASE_URL`) — override it to point at a local mock server or a
  third-party OpenAI-compatible gateway (see `examples/openai/custom_gateway.clj`).

### Error hierarchy

`responses-create` / `chat-completions-create` throw `ex-info` with a message
prefixed by the calling function (`"tools.agents.openai/responses-create: "` /
`"tools.agents.openai/chat-completions-create: "`, and
`"tools.agents.openai/output-text: "` / `"tools.agents.openai/completion-text: "`
for accessor failures) and `ex-data`
`{:type <keyword> :status <http-status-or-nil> :body <raw-body-or-nil>}`:

| HTTP status | `:type` | openai-python class |
|---|---|---|
| 400 | `:tools.agents.openai/bad-request-error` | `BadRequestError` |
| 401 | `:tools.agents.openai/authentication-error` | `AuthenticationError` |
| 403 | `:tools.agents.openai/permission-denied-error` | `PermissionDeniedError` |
| 404 | `:tools.agents.openai/not-found-error` | `NotFoundError` |
| 409 | `:tools.agents.openai/conflict-error` | `ConflictError` |
| 422 | `:tools.agents.openai/unprocessable-entity-error` | `UnprocessableEntityError` |
| 429 | `:tools.agents.openai/rate-limit-error` | `RateLimitError` |
| ≥500 | `:tools.agents.openai/internal-server-error` | `InternalServerError` |
| other non-2xx (e.g. 408) | `:tools.agents.openai/api-status-error` | `APIStatusError` (Python has no 408 subclass either) |
| no response at all (DNS/refused/TLS/timeout) | `:tools.agents.openai/api-connection-error` | `APIConnectionError` / `APITimeoutError` |

Non-status error types:

| Condition | `:type` |
|---|---|
| malformed request/response JSON | `:tools.agents.openai/json-encode-error` / `:tools.agents.openai/json-parse-error` |
| missing credentials (client construction or a hand-built client map) | `:tools.agents.openai/missing-credentials` |
| `:stream true` requested | `:tools.agents.openai/streaming-unsupported` |
| response has no `"output"` / `"choices"` array, or an empty `"choices"` | `:tools.agents.openai/invalid-response` |
| structurally wrong content (non-array `"content"`, non-string `"text"`, non-string non-null `"content"`) | `:tools.agents.openai/invalid-content-shape` |

These keywords sit directly under `:tools.agents.openai/…`, not under a
`.error` sub-namespace the way `tools.agents.anthropic` and
`tools.agents.mcp` spell theirs. That is a frozen spelling, not an
oversight — renaming it would break every `catch` that matches on `:type`,
and there is nothing to gain by it.

When the response body matches OpenAI's `{"error":{"message":"..."}}` shape,
that human-readable message is extracted and appended to the thrown message
(`"HTTP 429 Rate limit reached"`); otherwise the raw body is appended
(`"HTTP 500 <raw body>"`); otherwise just `"HTTP <status>"`.

Every error thrown from a resource method also carries `:retries-taken` in
`ex-data` — how many retries were spent before giving up. openai-python
exposes the same number as `response.retries_taken`, but also on *successful*
responses; here it is error-path-only, since a successful call returns the
decoded response map verbatim with nothing of ours added to it.

### Retries

A port of openai-python's `_base_client` retry loop and `_constants.py`
tuning, on by default with `:max-retries` 2.

- **What is retried.** Transport failures (DNS, connection refused, TLS
  handshake, timeout) are retried unconditionally, as in the SDK. Responses
  are retried when `should-retry?` says so: an `x-should-retry: true`/`false`
  header wins outright (exact, case-sensitive match, as in the SDK), otherwise
  408 / 409 / 429 / 5xx retry and everything else does not. A `Retry-After`
  longer than two minutes vetoes the retry entirely.
- **What is not retried.** 4xx other than 408/409/429; a malformed JSON body
  on an otherwise-successful 2xx (the SDK decodes after its retry loop has
  already broken out); and this library's own typed refusal, the `:stream true`
  rejection — mirroring the SDK re-raising `OpenAIError` out of the send path
  without retrying.
- **How long it waits.** A `Retry-After` / `retry-after-ms` inside
  `(0, 2 minutes]` is honored verbatim. Otherwise exponential backoff from
  `INITIAL_RETRY_DELAY` 0.5s, doubling per attempt, capped at
  `MAX_RETRY_DELAY` 8s, scaled by the SDK's `1 - 0.25 * random()` jitter.
- **How `:max-retries` is normalized — it is never rejected.** `client`
  clamps and truncates instead of throwing: a negative count becomes `0`, a
  fractional one truncates toward zero (`2.7` → `2`, `1/2` → `0`), and a
  **non-number is silently replaced by the default** (`"3"`, `nil`, `:x` →
  `2`). The same normalization runs again at request time, so the
  `(assoc client :max-retries n)` per-call override is clamped too. This is a
  divergence from `tools.agents.anthropic`, which throws
  `:tools.agents.anthropic.error/invalid-max-retries` for every one of those —
  see Divergences below. Consequence worth knowing if you write specs: a
  `::max-retries` spec copied from the sibling (`(s/and int? #(>= % 0))`)
  wrongly rejects `{:max-retries -3}`, a call this library treats as legal
  and pins in its own test suite.

Every piece of that is a pure function with "now" and the RNG injected, so it
is unit-testable without sleeping or flaking:

```clojure
(oai/should-retry? 429 {"retry-after" "5"} (System/currentTimeMillis))     ;; => true
(oai/should-retry? 500 {"x-should-retry" "false"} now-ms)                  ;; => false
(oai/retry-delay-ms 2 nil now-ms (fn [] 0.0))                              ;; => 2000
(oai/parse-retry-after-ms {"Retry-After" "2.5"} now-ms)                    ;; => 2500
(oai/retryable-status? 409)                                                ;; => true
```

`parse-retry-after-ms` implements the SDK's precedence — `retry-after-ms`
first, then `retry-after` as (possibly fractional) seconds, then `retry-after`
as an HTTP-date. Only the RFC-1123 date spelling is handled; the obsolete
RFC-850 and asctime forms that Python's `email.utils.parsedate_tz` also
accepts parse to `nil` here and fall back to exponential backoff. Date
arithmetic is a hand-rolled `days-from-civil` rather than `java.time`, so it
adds no dependency and behaves identically on both runtimes.

Delay arithmetic is integer-milliseconds throughout (the jitter numerator is
drawn from `[751, 1000]` over 1000) — integer ms is what every runtime's
sleep primitive wants anyway.

### Divergences from tools.agents.anthropic

Both libraries follow their own vendor SDK, so a few contracts differ on
purpose. If you use both, these are the places not to assume symmetry:

| | tools.agents.anthropic | tools.agents.openai |
|---|---|---|
| `:base-url` shape | host only (`https://api.anthropic.com`); library appends `/v1/messages` | **includes `/v1`** (`https://api.openai.com/v1`); library appends `/responses` |
| no text in the response | `output-text` **throws** `:no-text-content` (never returns nil/empty) | `output-text` **returns `""`** — the documented contract of Python's `Response.output_text`. Structurally malformed responses still throw. |
| auth | `x-api-key` **or** `Authorization: Bearer` + `anthropic-beta`, chosen by which credential resolved | always `Authorization: Bearer` |
| 409 | folded into the generic `:api-status-error` (Anthropic's SDK has no `ConflictError`) | its own `:conflict-error` |
| retry policy | implemented, but **not exposed** — the decision functions are private; `Retry-After` is clamped to `[0, 60]`s | implemented **and exposed** — `should-retry?`, `retryable-status?`, `retry-delay-ms`, `parse-retry-after-ms` are public and pure; `x-should-retry` and `retry-after-ms` are honored, and a `Retry-After` over 2 minutes vetoes the retry rather than being clamped |
| invalid `:max-retries` | **throws** `:invalid-max-retries` — must be a non-negative integer | **never throws** — negatives clamp to `0`, fractions truncate, non-numbers fall back to the default `2`; see Retries above |

Everything else — the JSON codec, the leaf-I/O split, `ex-info` typing style,
message-list helpers, the `:stream true` refusal, and the two-runtime test
harness — is intentionally identical.

### Streaming is not supported

`:stream true` throws `{:type :tools.agents.openai/streaming-unsupported}`
immediately, before any network request, rather than being silently ignored
or hanging. SSE streaming is simply not implemented by this client.

### Requiring `examples/` from the tests

`examples/*.clj` is requireable from
`test/tools/agents/openai/live_test.cljc` because the repo root (`.`) is on
every test alias's extra paths in `deps.edn`, and on `bb.edn`'s global
`:paths`. That resolves `examples.openai.basic-chat` →
`./examples/openai/basic_chat.clj`.

### JSON: a small hand-rolled codec, not a dependency

`write-json`/`read-json` in `tools/agents/openai.cljc` are a small hand-written codec. It
supports exactly what these APIs need (nil/bool/number/string/keyword/vector/
map) and decodes JSON objects into maps with **string** keys. Known gap:
control characters other than `\n \r \t` and backspace/form-feed are not
`\u00XX`-escaped on output — vanishingly rare in real message text.

### Isolating runtime-specific I/O

Exactly one function in `tools/agents/openai.cljc` is runtime-specific:
`http-post!`, the actual network POST, behind a `#?(:bb ... :clj ...)` reader
conditional. Babashka uses `babashka.http-client` (bundled by default); JVM
Clojure uses `java.net.http.HttpClient` (built into the JDK since 11 — zero
added dependency). Both leaves pin **HTTP/1.1** — see below.

Everything else — URL/header building, the JSON codec, credential resolution,
error typing, the whole retry policy, `output-text`/`completion-text`
extraction, the message-list helpers — is plain, portable `clojure.core`,
exercised identically by both test runners.

### Why HTTP/1.1 is pinned

`java.net.http.HttpClient` defaults to `HTTP_2`, and for a **cleartext**
`http://` URL it sends the HTTP/2 connection preface without any h2c upgrade
negotiation. A plain HTTP/1.1 reverse proxy in front of an OpenAI-compatible
gateway answers that with a bare `502`, so a `:base-url` that works
everywhere else fails on JVM Clojure and Babashka, which both sit on
`java.net.http`. Observed against a live Caddy-fronted gateway, not
theorized.

Both JVM-backed leaves therefore pin HTTP/1.1 explicitly
(`HttpClient$Version/HTTP_1_1`; `:version :http1.1` for babashka.http-client).
Over `https://` ALPN would have negotiated safely either way, and this client
issues one request at a time, so HTTP/2 bought it nothing — pinning keeps
both runtimes on the same wire protocol.

## Testing

`test/tools/agents/openai_test.cljc` is pure logic (JSON codec, credential
resolution, client construction, `output-text`, `completion-text`, the full
retry policy — `parse-retry-after-ms` including HTTP-dates and leap days,
`should-retry?`, the `retry-delay-ms` backoff curve and jitter bounds —
message helpers, `:stream true` rejection) — zero I/O, zero network, zero
sleeping (the clock and the RNG are injected), identical on both runtimes.

`test/tools/agents/openai/live_test.cljc` runs a local mock server on both
runtimes: request line and required headers, `OpenAI-Organization` /
`OpenAI-Project` being absent when unset and present when set, `/v1`-suffixed
base-urls (with and without a trailing slash) producing exactly
`/v1/responses` and `/v1/chat/completions`, outbound JSON preserving the
request map verbatim, credentials never leaking into the outbound body,
successful nested-map/vector decoding, `output-text` on a reasoning-only
response returning `""`, non-2xx with status+body context (both the
JSON-error-shape and raw-body fallback), 409 → `:conflict-error`,
malformed-JSON-response handling, typed connection failures, the retry loop
end-to-end (a 429 retried then succeeding, retries exhausted into a status
error, a non-retryable 400 not retried, `x-should-retry` overriding the status
in both directions, `:max-retries 0` disabling retries, and connection errors
retried then typed — all with `retry-after-ms: 1` so they cost no measurable
wall-clock), and all three `examples/*.clj` ports run end-to-end against the
mock server.

The mock server is `tools.agents.test-support/start-server!`, shared by all three provider suites — two tiny leaves, same shape as
`http-post!`. Babashka uses `org.httpkit.server`
(`com.sun.net.httpserver.HttpServer` is not resolvable under bb's native
image). JVM Clojure uses `com.sun.net.httpserver.HttpServer` (built into the
JDK, zero deps). Mock
ports are `18950`–`18964`, chosen not to collide with
tools.agents.anthropic's `18930`–`18946`, and `18965`–`18971` for the retry
tests. Port `18999` is additionally used by the three tests that deliberately
start *no* server (missing credentials and the two connection-failure tests,
which actually dial it and so assume nothing else on the host has `18999`
bound).

A few client-construction tests read the real environment (they assert the
defaults that apply when `OPENAI_API_KEY` / `OPENAI_ORG_ID` /
`OPENAI_PROJECT_ID` / `OPENAI_BASE_URL` are unset), so run the suite in a
shell where those are not exported. `resolve-credentials` takes an injected
`getenv-fn` precisely so the precedence rules themselves can be tested without
touching the environment.

### Running the tests

```
./script/test-all.sh
```

## License

Apache License 2.0 — see `LICENSE`.
