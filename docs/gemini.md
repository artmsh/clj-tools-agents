# tools.agents.gemini

A pure-Clojure client for the [Gemini Developer
API](https://ai.google.dev/gemini-api/docs), ergonomically modeled on the
official [python-genai](https://github.com/googleapis/python-genai) client: a
`client` config map standing in for `genai.Client(api_key=...)`,
`generate-content` / `count-tokens` standing in for
`client.models.generate_content(model=..., contents=..., config=...)` /
`client.models.count_tokens(...)`, and a typed error hierarchy matching the
SDK's `APIError`.

Runs unmodified on **JVM Clojure** and **Babashka**.

Sibling of [tools.agents.anthropic](anthropic.md) and
[tools.agents.openai](openai.md) in this same repo — same architecture
(single leaf for network I/O, hand-rolled portable JSON codec,
`ex-info`-with-`:type` error hierarchy, two-runtime test matrix). The
places where this library deliberately behaves *differently* from its two
siblings are called out in "Divergences from tools.agents.anthropic/
tools.agents.openai" below; they all come from following python-genai rather
than the other two vendor SDKs.

## Usage

```clojure
(require '[tools.agents.gemini :as gemini])

(def client (gemini/client {:api-key (System/getenv "GEMINI_API_KEY")}))

(-> (gemini/generate-content client "gemini-2.5-flash"
      {"contents" [{"role" "user" "parts"
                     [{"text" "How do I check if a Python object is an instance of a class?"}]}]
       "systemInstruction" {"parts" [{"text" "You are a coding assistant that talks like a pirate."}]}})
    (gemini/output-text))
;; => "Arr, ye be wantin' `isinstance(obj, SomeClass)`, matey!"

;; countTokens — same request shape, no max-tokens-equivalent field to omit.
(gemini/count-tokens client "gemini-2.5-flash"
  {"contents" [{"role" "user" "parts" [{"text" "hi"}]}]})
;; => {"totalTokens" 2}
```

`client` resolves credentials eagerly — explicit `:api-key` first, then
`GOOGLE_API_KEY`, then `GEMINI_API_KEY`, else it throws a catchable `ex-info`
**before any network request is attempted** (fail fast). Failed requests are
retried per python-genai's own policy (`:max-retries`, default 4 — see
Retries). The `request` map is passed through to JSON almost verbatim —
`contents`, `generationConfig`, `systemInstruction`, `safetySettings`,
`tools`, `cachedContent`, etc. all pass straight through untouched, with no
kebab↔snake/camelCase conversion (see the parity table below).

See `examples/gemini/` for three complete, runnable-against-a-mock-server
ports of real python-genai usage shapes: `basic_chat.clj` (the README
quickstart's `systemInstruction` + `generationConfig`), `count_tokens.clj`
(the `count_tokens` quickstart), and `custom_gateway.clj` (a `:base-url`
override plus a JSON-mode `generationConfig`).

### The model is in the URL, not the request body

Unlike `tools.agents.anthropic`/`tools.agents.openai`, where `"model"` is a
key inside the JSON request map, python-genai's `generate_content(model=...,
contents=...)` puts the model in the **URL path**:
`POST {base-url}/v1beta/models/{model}:generateContent`. `generate-content`/
`count-tokens` below take `model` as an explicit positional argument for
exactly this reason — `request` never carries a `"model"` key. A bare model
id like `"gemini-2.5-flash"` gets `"models/"` prepended automatically; a
`model` already prefixed with `"models/"` or `"tunedModels/"` (e.g. a tuned
model id) is used verbatim.

## Design notes

### Parity with python-genai

| python-genai | tools.agents.gemini | Notes |
|---|---|---|
| `genai.Client(api_key=..., http_options=...)` | `(client {:api-key ... :base-url ... :api-version ... :max-retries ...})` | Returns a plain config map. |
| `client.models.generate_content(model=model, contents=..., config=request)` | `(generate-content client model request)` | `request` is a plain map passed through to JSON almost verbatim; `model` is a separate argument (see above), not a request key. |
| `client.models.count_tokens(model=model, contents=...)` | `(count-tokens client model request)` | Same contract, `:countTokens` path. |
| `response.text` | `(output-text response)` | Port of the SDK property: join every string `"text"` across `candidates[0].content.parts`, skipping `"thought"` parts. Returns `nil` (matching Python's `None`) rather than throwing when there's nothing to join — see below. |
| dict key `"maxOutputTokens"` (nested under `"generationConfig"`) | map key `"maxOutputTokens"` or `:maxOutputTokens` | Both string and keyword keys are accepted and encoded **verbatim** via `name` — no case conversion at all. Gemini's REST field names are camelCase already, unlike anthropic/openai's snake_case — write `:maxOutputTokens`, not `:max_output_tokens` or `:max-output-tokens`. |
| `google.genai.errors.APIError` (`ClientError` / `ServerError` subclasses) | `ex-info` with `:type` in `ex-data` | See the error-hierarchy table below — one exception constructor discriminated by `:type`, rather than a Python-style class hierarchy. |
| `GOOGLE_API_KEY` / `GEMINI_API_KEY` | same env vars, same precedence (`GOOGLE_API_KEY` wins when both are set) | Explicit `:api-key` wins over both. python-genai logs a warning when both env vars are set; this library just picks `GOOGLE_API_KEY` silently (the two runtimes share no common logging facility). |
| `x-goog-api-key` header | same header | Confirmed from `_api_client.py`: a bare API key, **no** `Bearer ` prefix — unlike `tools.agents.openai`'s `Authorization: Bearer`. |
| `HttpOptions(base_url=..., api_version=...)` | `:base-url` (default `https://generativelanguage.googleapis.com`), `:api-version` (default `"v1beta"`) | Kept as two separate client fields, joined at request time, rather than one pre-concatenated base-url string — see `client`'s docstring. |
| `client.models.generate_content_stream(...)` | *(not implemented)* | See Streaming below - same rationale as the two siblings. |
| tenacity-backed automatic retries, `_RETRY_ATTEMPTS = 5` | `:max-retries` client opt, default 4 | Implemented — see Retries below. |
| `client.chats.create(...)` (multi-turn chat session object) | *(not implemented)* | A stateful wrapper over `generate_content` with local history bookkeeping; `add-user-message`/`add-model-message` below give the same history-building ergonomics without the stateful object. |
| `client.files.*` / `.caches.*` / `.tunings.*` / `.batches.*` / `.live.*` (Live API) / embeddings / image & video generation | *(not implemented)* | Only `generateContent`/`countTokens` are in scope for this port. `post-json!` is the shared transport, so adding another POST resource is a small change. |
| Vertex AI mode (`vertexai=True`, ADC/service-account credentials, `us-central1`-style locations) | *(not implemented)* | This library only targets the Gemini Developer API (API-key auth against `generativelanguage.googleapis.com`), not the separate Vertex AI code path python-genai also supports. |

### Credential resolution & headers (confirmed from python-genai source)

Precedence, first match wins: explicit `:api-key` → `GOOGLE_API_KEY` env var
→ `GEMINI_API_KEY` env var → throw.

- Auth is always `x-goog-api-key: <api-key>` — a bare key, no scheme prefix.
  There is no `Authorization` header at all for Gemini Developer API auth.
- Every request also sends `content-type: application/json`.
- `:base-url` defaults to `https://generativelanguage.googleapis.com` (also
  resolvable from `GOOGLE_GEMINI_BASE_URL` — this library's own env-var
  convention, matching how `tools.agents.openai` resolves `OPENAI_BASE_URL`;
  python-genai itself has no base-url env var).
- `:api-version` defaults to `"v1beta"` — python-genai's own default for the
  Gemini Developer API (Vertex AI mode defaults to `"v1"` instead; not
  relevant here, see the parity table's Vertex AI row).

### Error hierarchy

`generate-content`/`count-tokens` throw `ex-info` with a message prefixed by
the calling function (`"tools.agents.gemini/generate-content: "` /
`"tools.agents.gemini/count-tokens: "`) and `ex-data`
`{:type <keyword> :status <http-status-or-nil> :body <raw-body-or-nil>
:retries-taken <int>}`:

As in `tools.agents.openai`, these keywords sit directly under
`:tools.agents.gemini/…` rather than under a `.error` sub-namespace the way
`tools.agents.anthropic` and `tools.agents.mcp` spell theirs. Frozen
spelling, not an oversight.

| HTTP status | `:type` | python-genai class |
|---|---|---|
| 400 | `:tools.agents.gemini/invalid-argument-error` | `ClientError` (400 `INVALID_ARGUMENT`) |
| 401 | `:tools.agents.gemini/authentication-error` | `ClientError` (401 `UNAUTHENTICATED`) |
| 403 | `:tools.agents.gemini/permission-denied-error` | `ClientError` (403 `PERMISSION_DENIED`) |
| 404 | `:tools.agents.gemini/not-found-error` | `ClientError` (404 `NOT_FOUND`) |
| 429 | `:tools.agents.gemini/resource-exhausted-error` | `ClientError` (429 `RESOURCE_EXHAUSTED`) |
| ≥500 | `:tools.agents.gemini/internal-server-error` | `ServerError` |
| other non-2xx | `:tools.agents.gemini/api-status-error` | `APIError` (the generic base) |
| no response at all (DNS/refused/TLS/timeout) | `:tools.agents.gemini/api-connection-error` | *(python-genai has no distinct connection-error class; the underlying `httpx`/`requests` exception propagates instead)* |

Non-status error types:

| Condition | `:type` |
|---|---|
| malformed request/response JSON | `:tools.agents.gemini/json-encode-error` / `:tools.agents.gemini/json-parse-error` |
| missing credentials (client construction or a hand-built client map) | `:tools.agents.gemini/missing-credentials` |

Unlike the two siblings, `output-text` never throws — see the next section.

`extract-error-message` mirrors `APIError`'s own two-shape tolerance
(confirmed from `errors.py`): a message is read from a top-level
`{"message": "..."}` field first, falling back to the nested
`{"error": {"message": "..."}}` shape Google's `google.rpc.Status`-style
errors normally use. Whichever is found is appended to the thrown message
(`"HTTP 429 Resource exhausted"`); with neither, the raw body is appended
instead (`"HTTP 500 <raw body>"`); with an empty body, just `"HTTP <status>"`.

Every error thrown from a resource method also carries `:retries-taken` in
`ex-data` — how many retries were spent before giving up.

### `output-text` returns `nil`, never throws

This is a genuine, deliberate divergence from **both** siblings:
`tools.agents.anthropic/output-text` throws when there's no text content,
and `tools.agents.openai/output-text` returns `""` (matching
`Response.output_text`'s documented empty-string contract) but still throws
on structurally malformed input. `tools.agents.gemini/output-text` instead
mirrors python-genai's `GenerateContentResponse.text` **property** —
attribute access that returns `None`, never raises — for every "there's no
text here" case: no `"candidates"` at all, an empty `"candidates"` array, a
first candidate with no `"content"`/`"parts"`, or a `"parts"` array with no
string-`"text"` (non-`"thought"`) entries in it (a tool-call-only or
safety-blocked response). There is no equivalent of the SDK's warning logs
for multiple candidates or non-text parts — the two runtimes here share no
common logging facility, and a silent best-effort extraction is the more
useful library default.

### Retries

A port of python-genai's `tenacity`-backed retry loop in `_api_client.py`, on
by default with `:max-retries` 4 (the SDK's `_RETRY_ATTEMPTS = 5` counts the
initial call, so 4 *retries*).

- **What is retried.** Transport failures (DNS, connection refused, TLS
  handshake, timeout) are retried unconditionally. Responses are retried when
  the status is one of `408 429 500 502 503 504` — a bare status-code set,
  confirmed from `_api_client.py`, with **no header inspection** found there
  (contrast `tools.agents.openai`'s `x-should-retry`/`Retry-After` handling,
  which has a confirmed source basis in openai-python; no equivalent is
  implemented here because none was found in python-genai). `409` is
  deliberately excluded, unlike `tools.agents.openai`'s retryable set.
- **What is not retried.** Any other 4xx; a malformed JSON body on an
  otherwise-successful 2xx; this library's own typed errors.
- **How long it waits.** `tenacity.wait_exponential_jitter(initial=1.0,
  max=60.0, exp_base=2, jitter=1)`'s documented formula: `delay = min(max,
  initial * exp_base^n + random.uniform(0, jitter))`, where `n` is
  retries-already-taken (in **seconds** in tenacity; ported here as integer
  **milliseconds** throughout — `initial` 1000ms, `max` 60000ms, `jitter`
  0–1000ms — integer ms is what every runtime's sleep primitive wants).
- **No `Retry-After` equivalent.** See "What is retried" above — this is the
  one place this library's retry policy is knowingly less complete than
  `tools.agents.openai`'s, because the upstream behavior it would port isn't
  there to find.

`retry-delay-ms` is a pure function with the RNG injected, so it is
unit-testable without sleeping or flaking:

```clojure
(gemini/retry-delay-ms 0 (fn [] 0.0))     ;; => 1000  (n=0: 1000*2^0 + 0)
(gemini/retry-delay-ms 1 (fn [] 0.0))     ;; => 2000  (n=1: 1000*2^1 + 0)
(gemini/retry-delay-ms 6 (fn [] 0.999))   ;; => 60000 (would be 65000+jitter, capped at max)
(gemini/retryable-status? 503)            ;; => true
(gemini/retryable-status? 409)            ;; => false — excluded, unlike tools.agents.openai
```

A dynamic `gemini/*sleep-fn*` (default the real sleep) lets tests bind a
no-op and exercise the retry loop's *counting* without paying real wall-clock
— the same pattern `tools.agents.anthropic/*sleep-fn*` uses; unlike
`tools.agents.openai`, this library has no `Retry-After` header path to also
prove is honored, so there is nothing finer-grained to test than "the retry
happened."

### Divergences from tools.agents.anthropic/tools.agents.openai

| | tools.agents.anthropic | tools.agents.openai | tools.agents.gemini |
|---|---|---|---|
| model location | request body (`"model"` key) | request body (`"model"` key) | **URL path** — a separate `model` argument, never a request key |
| auth header | `x-api-key` **or** `Authorization: Bearer` + `anthropic-beta` | always `Authorization: Bearer` | always `x-goog-api-key` (bare key, no scheme prefix) |
| `output-text` on no text | **throws** `:no-text-content` | **returns `""`** (SDK's documented contract); still throws on malformed shape | **returns `nil`**, never throws — matches the SDK's `None`-returning property, not an exception |
| default `:max-retries` | 2 | 2 | **4** — ported from python-genai's `_RETRY_ATTEMPTS = 5` (attempts, not retries) |
| retryable statuses | 408/409/429/5xx | 408/409/429/5xx | **408/429/500/502/503/504** — 409 excluded, and not "all 5xx" (501/etc. excluded too) |
| `Retry-After`-equivalent header | clamped `[0, 60]`s, private | `retry-after-ms`/`Retry-After` **and** `x-should-retry`, public, honored | **not implemented** — no such header found in python-genai's retry predicate |
| request-key casing | snake_case verbatim (`max_tokens`) | snake_case verbatim (`max_output_tokens`) | **camelCase** verbatim (`maxOutputTokens`) — matches the REST API's own field names |

Everything else — the JSON codec, the leaf-I/O split, `ex-info` typing
style, and the two-runtime test harness — is
intentionally identical.

### Streaming is not supported

There is no `stream-generate-content`/`:stream true` flag to reject here, the
way the two siblings each reject one — Gemini's REST streaming variant
(`:streamGenerateContent`) is a **separate endpoint**, not a request-body
flag, so there is simply no streaming function offered at all.

### JSON: a small hand-rolled codec, not a dependency

There is no JSON library available on both runtimes without adding a
dependency, so `write-json`/`read-json` in `tools/agents/gemini.cljc` are the
same small hand-written codec as the two siblings (byte-for-byte identical
algorithm, kept as a separate copy rather than a shared ns — see
`gemini.cljc`'s own ns docstring). It supports exactly what this API needs
and decodes JSON objects into maps with **string** keys. Known gap: control
characters other than `\n \r \t` and backspace/form-feed are not
`\u00XX`-escaped on output.

### Isolating runtime-specific I/O

Exactly one function in `tools/agents/gemini.cljc` is runtime-specific:
`http-post!`, behind a `#?(:bb ... :clj ...)` reader conditional, identical in
shape and rationale to `tools.agents.openai`'s own leaf (see docs/openai.md's
"Isolating runtime-specific I/O" and "Why HTTP/1.1 is pinned" sections — both
apply here verbatim, including the HTTP/1.1 pin).

## Testing

`test/tools/agents/gemini_test.cljc` is pure logic (JSON codec, credential
resolution, `output-text`, the retry policy's `retryable-status?`/
`retry-delay-ms`, contents-list helpers) — zero I/O, zero network, zero
sleeping (the RNG is injected), identical on both runtimes.

`test/tools/agents/gemini/live_test.cljc` runs a local mock server on both
runtimes: request line and required headers (`x-goog-api-key`,
`content-type`), bare-model-id vs. `tunedModels/`-prefixed model-path
building, trailing-slash base-urls, `generateContent`/`countTokens` path
building, outbound JSON preserving the request map verbatim, credentials
never leaking into the outbound body, successful nested-map/vector decoding,
a safety-blocked response yielding `nil` from `output-text`, non-2xx with
status+body context (both the nested- and flat-error-shape extraction, and
the raw-body fallback), malformed-JSON-response handling, typed connection
failures, and the retry loop end-to-end (a 503 retried then succeeding,
retries exhausted into a status error, a non-retryable 400 not retried,
`:max-retries 0` disabling retries, `count-tokens` retrying too, and
connection errors retried then typed — all with `g/*sleep-fn*` bound to a
no-op so they cost no measurable wall-clock, since — unlike
`tools.agents.openai`'s live-test — there is no `retry-after-ms` header
trick available to shrink the real ~1s-per-retry floor). All three
`examples/gemini/*.clj` ports run end-to-end against the mock server too.

The mock server is `tools.agents.test-support/start-server!`, shared by all three provider suites: two leaves, Babashka
`org.httpkit.server` and JVM Clojure `com.sun.net.httpserver.HttpServer`.
Mock ports are `18980`–`18997`, kept disjoint from
`tools.agents.anthropic`'s `18930`–`18946` and `tools.agents.openai`'s
`18950`–`18971` as a matter of hygiene. Port `18999` is additionally used by
the tests that deliberately start *no* server (missing credentials and the
connection-failure tests, which actually dial it and so assume nothing else
on the host has `18999` bound).

A couple of client-construction tests read the real environment (they assert
the behavior that applies when `GOOGLE_API_KEY`/`GEMINI_API_KEY` are unset),
so run the suite in a shell where those are not exported.
`resolve-credentials` takes an injected `getenv-fn` precisely so the
precedence rules themselves can be tested without touching the environment.

### Running the tests

```
./script/test-all.sh
```

## License

Apache License 2.0 — see `LICENSE`.
