# tools.agents.gemini

A pure-Clojure client for the [Gemini Developer
API](https://ai.google.dev/gemini-api/docs), ergonomically modeled on the
official [python-genai](https://github.com/googleapis/python-genai) client: a
`GeminiClient` record standing in for `genai.Client(api_key=...)`,
`generate-content` / `generate-content-stream` / `count-tokens` standing in for
`client.models.generate_content(model=..., contents=..., config=...)` /
`client.models.generate_content_stream(...)` /
`client.models.count_tokens(...)`, and a typed error hierarchy matching the
SDK's `APIError`.

Runs unmodified on **JVM Clojure** and **Babashka**.

Sibling of [tools.agents.anthropic](anthropic.md) and
[tools.agents.openai](openai.md) in this same repo — same architecture
(single leaf for network I/O, hand-rolled portable JSON codec,
`ex-info`-with-`:type` error hierarchy, two-runtime test matrix). The
places where this library deliberately behaves *differently* from its
siblings are tabulated in [divergences.md](divergences.md); they all come from
following python-genai rather than the other vendor SDKs.

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
| `genai.Client(api_key=..., http_options=...)` | `(client {:api-key ... :base-url ... :api-version ... :max-retries ...})` | Returns a `GeminiClient` record with map-style keyword access. |
| `client.models.generate_content(model=model, contents=..., config=request)` | `(generate-content client model request)` | `request` is a plain map passed through to JSON almost verbatim; `model` is a separate argument (see above), not a request key. |
| `client.models.count_tokens(model=model, contents=...)` | `(count-tokens client model request)` | Same contract, `:countTokens` path. |
| `response.text` | `(output-text response)` | Port of the SDK property: join every string `"text"` across `candidates[0].content.parts`, skipping `"thought"` parts. Returns `nil` (matching Python's `None`) rather than throwing when there's nothing to join — see below. |
| dict key `"maxOutputTokens"` (nested under `"generationConfig"`) | map key `"maxOutputTokens"` or `:maxOutputTokens` | Both string and keyword keys are accepted and encoded **verbatim** via `name` — no case conversion at all. Gemini's REST field names are camelCase already, unlike anthropic/openai's snake_case — write `:maxOutputTokens`, not `:max_output_tokens` or `:max-output-tokens`. |
| `google.genai.errors.APIError` (`ClientError` / `ServerError` subclasses) | `ex-info` with `:type` in `ex-data` | See the error-hierarchy table below — one exception constructor discriminated by `:type`, rather than a Python-style class hierarchy. |
| `GOOGLE_API_KEY` / `GEMINI_API_KEY` | same env vars, same precedence (`GOOGLE_API_KEY` wins when both are set) | Explicit `:api-key` wins over both. python-genai logs a warning when both env vars are set; this library just picks `GOOGLE_API_KEY` silently (the two runtimes share no common logging facility). |
| `x-goog-api-key` header | same header | Confirmed from `_api_client.py`: a bare API key, **no** `Bearer ` prefix — unlike `tools.agents.openai`'s `Authorization: Bearer`. |
| `HttpOptions(base_url=..., api_version=...)` | `:base-url` (default `https://generativelanguage.googleapis.com`), `:api-version` (default `"v1beta"`) | Kept as two separate client fields, joined at request time, rather than one pre-concatenated base-url string — see `client`'s docstring. |
| `client.models.generate_content_stream(model=model, contents=..., config=request)` | `(generate-content-stream client model request)` | Same `{model}:streamGenerateContent?alt=sse` path. Returns a single-use reducible of chunk maps instead of a generator; `accumulate-stream` joins them (python-genai has no joiner). See Streaming below. |
| tenacity-backed automatic retries, `_RETRY_ATTEMPTS = 5` | `:max-retries` client opt, default 4 | Implemented — see Retries below. |
| `HttpOptions(httpx_client=...)` | `:http` client opt | A request fn with `tools.agents.http/request!`'s contract, used for every exchange, streaming included. See README, Bring your own HTTP client. |
| `client.chats.create(...)` (multi-turn chat session object) | *(not implemented)* | A stateful wrapper over `generate_content` with local history bookkeeping; `add-user-message`/`add-model-message` below give the same history-building ergonomics without the stateful object. |
| `client.files.*` / `.caches.*` / `.tunings.*` / `.batches.*` / `.live.*` (Live API) / embeddings / image & video generation | *(not implemented)* | Only `generateContent`/`streamGenerateContent`/`countTokens` are in scope for this port. `post-json!` is the shared transport, so adding another POST resource is a small change. |
| Vertex AI mode (`vertexai=True`, ADC/service-account credentials, `us-central1`-style locations) | *(not implemented)* | This library only targets the Gemini Developer API (API-key auth against `generativelanguage.googleapis.com`), not the separate Vertex AI code path python-genai also supports. |

### Credential resolution & headers (confirmed from python-genai source)

Precedence, first match wins: explicit `:api-key` → `GOOGLE_API_KEY` env var
→ `GEMINI_API_KEY` env var → throw. An explicit `:credential-source` (a
`tools.agents.token/TokenSource`, e.g. an OAuth access-token cache) replaces
this chain: its token is fetched before every attempt and sent as
`Authorization: Bearer <token>` **instead of** `x-goog-api-key`; combining it
with `:api-key` throws `invalid-credentials`. A 401 invalidates it and retries
once outside `:max-retries`. See README, Refreshable credentials.

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
| `:credential-source` not a `TokenSource`, or combined with `:api-key` | `:tools.agents.gemini/invalid-credentials` |
| `:http` is not a fn (client construction; `:option` in `ex-data`) | `:tools.agents.gemini/invalid-options` |

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
- **401.** Retried once, immediately and outside `:max-retries`, only for a
  `:credential-source` client; a static key's 401 is never retried.
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

### Divergences from the sibling clients

See [divergences.md](divergences.md) for the per-contract table across all four clients.

### Streaming

```clojure
(let [s (gemini/generate-content-stream client "gemini-2.5-flash" request)]
  (run! #(print (gemini/output-text %)) s))            ; text as it arrives

(let [r (gemini/accumulate-stream
          (gemini/generate-content-stream client "gemini-2.5-flash" request))]
  (when-not (gemini/stream-complete? r) (throw (ex-info "truncated" {})))
  (gemini/output-text r))                              ; the joined text
```

**Wire.** Streaming is a separate endpoint, not a request-body flag, so
`generate-content` has no `stream` flag to refuse.
`POST {base}/{version}/models/{model}:streamGenerateContent?alt=sse`, same
request body and `x-goog-api-key` header as `generateContent`. Sources: the
REST reference's `models.streamGenerateContent` ("stream of
`GenerateContentResponse`"; every curl example passes `?alt=sse`), and
python-genai (`models.py` path `'{model}:streamGenerateContent?alt=sse'`,
googleapis/python-genai @ b88fded). Without `alt=sse` the body is a JSON
array, not SSE (Google REST convention; the reference page does not say
so). Each `data:` line is one complete `GenerateContentResponse`
chunk. There are no event names and no `[DONE]`.

**Lifecycle.** `generate-content-stream` sends the request at call time and
returns the `tools.agents.stream` reducible. It is single-use: one reduce
consumes it and closes the connection, including on early termination such
as `(into [] (take 1) s)`. A stream you never reduce must be released with
`(tools.agents.stream/close! s)`, which is also the cross-thread cancel.
`with-open`/`.close` work on the JVM only, because Babashka's `reify`
cannot add `java.io.Closeable`. `(tools.agents.stream/response s)` gives the
2xx status and headers.

**Retries and errors.** The retry policy is `generate-content`'s (statuses
408/429/500/502/503/504 and connection failures, `:max-retries`,
`*sleep-fn*`), applied to the opening exchange only. python-genai also
retries only in `_request`, before the chunk iterator starts. Nothing is
retried once a 2xx body is being read.

| failure | when it throws | `:type` |
|---|---|---|
| non-2xx after retries | from `generate-content-stream` | the status table above, same `:status`/`:body`/`:retries-taken` |
| no connection after retries | from `generate-content-stream` | `api-connection-error` |
| error chunk `{"error": {"code": c, ...}}` | from the reduce, after earlier chunks were delivered | `c` treated as a status (python-genai `request_streamed` → `APIError.raise_error(code, ...)`), e.g. 503 → `internal-server-error`; `:body` is the raw `data:` string |
| connection lost mid-stream | from the reduce | `api-connection-error`, cause the `IOException` |
| undecodable chunk | from the reduce | `json-parse-error` |

**Truncation.** Gemini has no terminal event, so EOF is the normal end:
`(tools.agents.stream/outcome s)` is `:eof` for a complete stream **and**
for one cut off cleanly at a chunk boundary. Completeness is in the data.
The last chunk of a finished candidate carries `finishReason`, and a
blocked prompt carries `promptFeedback.blockReason` with no candidates.
`(stream-complete? response)` checks exactly that, on the joined response
or on a last chunk. python-genai's `Chat.send_message_stream` uses the same
criterion: a turn whose `finish_reason` stayed `None` is left out of the
curated history. Neither the stream nor `accumulate-stream` throws on
truncation. A connection that drops mid-chunk is a transport error and
throws, see above. One wire case is not covered: python-genai's
`_iter_response_stream` also brace-balances raw, non-`data:` JSON lines
into an error chunk. Such lines are not SSE fields, so the WHATWG parser in
`tools.agents.sse` ignores them. That stream ends at `:eof` without
`finishReason` and reads as truncated rather than typed.

**Joining chunks.** `accumulate-chunk` is a pure reducing fn (`[]` → `nil`,
`[acc]` → `acc`, `[acc chunk]`), and `(accumulate-stream chunks)` is
`(transduce identity accumulate-chunk chunks)` over the stream or any
collection. The result is an ordinary response map, so `output-text`
works on it. python-genai has no joiner: `generate_content_stream` yields
chunks, and `Chat` stores each chunk's `Content` unmerged. The join ports
the deprecated google-generativeai SDK's `generation_types._join_chunks`
(google-gemini/deprecated-generative-ai-python @ 7a7cc54), the merge
behind its streamed `response.resolve()`:

| field | rule | vs. `_join_chunks` |
|---|---|---|
| `candidates` | grouped by `index` (default 0), a vector sorted by index | same |
| `content.parts` | appended; adjacent text parts concatenated; adjacent `executableCode` join `code`, adjacent `codeExecutionResult` join `output` (outcome from the later one); other parts (`functionCall`, `inlineData`, ...) kept whole | text joins only when both parts have the same `thought` flag, so `output-text` still skips thoughts; the later part's other keys (`thoughtSignature`) are kept rather than dropped |
| `content.role` | first chunk that has one | same |
| `finishReason`, `citationMetadata`, other candidate fields | last non-nil | same, except the SDK read the literal last chunk |
| `safetyRatings` | last non-nil | simplified: the SDK merged per category and OR-ed `blocked` |
| `usageMetadata`, `modelVersion`, other top-level fields | last non-nil (usage is cumulative per chunk) | the SDK took the last chunk's value even when absent |
| `promptFeedback` | first | same |

An error chunk passed to `accumulate-chunk` throws the same typed ex-info as
the stream does, so a fixture reduced without the transport cannot swallow
it.

### JSON: a small hand-rolled codec, not a dependency

There is no JSON library available on both runtimes without adding a
dependency, so `write-json`/`read-json` in `tools/agents/gemini.cljc` are thin
wrappers over the shared hand-written codec the two siblings also use,
`tools.agents.json` (see the README's "JSON: one shared hand-rolled codec"
section). It decodes JSON objects into maps with **string** keys. The
wrappers throw this namespace's own `:tools.agents.gemini/json-encode-error`
/ `json-parse-error` with `tools.agents.gemini/write-json: ` /
`tools.agents.gemini/read-json: ` message prefixes.

### Isolating runtime-specific I/O

`tools/agents/gemini.cljc` has no runtime-specific code. Its requests go
through the shared `tools.agents.http/request!`, the same function
`tools.agents.openai` uses (see docs/openai.md's "Isolating runtime-specific
I/O" and "Why HTTP/1.1 is pinned" sections — both apply here verbatim).

## Testing

The shared JSON codec itself is covered by `test/tools/agents/json_test.cljc`
in the core suite (`-M:test-core` / `bb test-core`). `test/tools/agents/gemini_test.cljc` is pure logic (the JSON
codec's error contract, credential
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

`gemini_test.cljc` also covers the pure chunk join (`accumulate-chunk`,
`accumulate-stream`, `stream-complete?`): thought/answer separation, whole `functionCall` parts, code-execution joins, multiple
candidates by index, top-level field rules, a blocked prompt and error
chunks. `test/tools/agents/gemini/stream_test.cljc` runs
the join over the SSE fixture `test/resources/sse/gemini-text.sse`, which
is **synthetic** (hand-built, because the REST docs show no streamed
response body), then `generate-content-stream` against the streaming mock
servers: request line,
`alt=sse` query, headers and body; incremental multi-chunk accumulation
read by `output-text`; retries of the opening request (503 then a stream,
exhaustion, connection refused); a non-2xx before the stream; an error chunk
mid-stream (typed, not retried); a body cut off mid-chunk
(`api-connection-error`); a stream that ends at `:eof` without
`finishReason`; and early termination and `close!` releasing the
connection. Its ports come from the OS (bind port 0), not a fixed band.

The mock server is `tools.agents.test-support/start-server!`, shared by all three provider suites: two leaves, Babashka
`org.httpkit.server` and JVM Clojure `com.sun.net.httpserver.HttpServer`.
Mock ports are `18980`–`18997` (plus `19360`–`19362` for `:credential-source`), kept disjoint from
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
