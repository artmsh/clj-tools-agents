# tools.agents.anthropic

A pure-Clojure client for [Anthropic's Messages API](https://docs.anthropic.com/),
ergonomically modeled on the official [anthropic-sdk-python](https://github.com/anthropics/anthropic-sdk-python)
client: an `AnthropicClient` record standing in for `Anthropic(...)`, `messages-create` standing in for
`client.messages.create(**params)`, and a typed error hierarchy matching the
SDK's exception classes. Also includes automatic retries with backoff,
`count-tokens`, tool-calling ergonomics, a small message content-block DSL, a
terminal response visualizer, and optional `clojure.spec.alpha` definitions
— see Design notes below.

Several notes below justify a decision by "the original builtin". That was a
hardcoded Anthropic client written in Zig, which this library replaced; it is
not part of this repository. Those references are provenance — why a contract
is shaped the way it is — not something a reader here can check.

## Usage

```clojure
(require '[tools.agents.anthropic :as anthropic])

(def client (anthropic/client {:api-key (System/getenv "ANTHROPIC_API_KEY")}))

(def messages (anthropic/add-user-message [] "Say hello in one short sentence."))

(def response
  (anthropic/messages-create client
    {"model" "claude-sonnet-4-6"
     "max_tokens" 1000
     "messages" messages}))

(anthropic/output-text response)
;; => "Hello there!"
```

`client` resolves credentials eagerly — explicit `:api-key`/`:auth-token` first,
then `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN`, then Workload Identity
Federation env vars (see Workload Identity Federation below), else it throws a catchable
`ex-info` **before any network request is attempted** (fail fast, matching the
original builtin's contract). `messages-create`'s `request` map is passed
through to JSON almost verbatim — `model`, `max_tokens`, `messages`, `system`,
`temperature`, `stop_sequences`, `thinking`, etc. all pass straight through
untouched, with no kebab↔snake key conversion (see the parity table below).

See `examples/` for four complete, runnable-against-a-mock-server ports of
real anthropic-sdk-python usage shapes (basic chat helper, an eval harness
with prefill + stop sequences, a custom-gateway `auth_token` client, and a
tool-calling loop), a fifth, fixture-based one demonstrating the response
visualizer below, and a sixth pair (`ptc_demo.clj` + `ptc_expense_api.clj`)
porting the anthropic-cookbooks Programmatic Tool Calling notebook — see
Programmatic Tool Calling below.

## Design notes

### Parity with anthropic-sdk-python

| Python SDK | tools.agents.anthropic | Notes |
|---|---|---|
| `Anthropic(api_key=..., auth_token=..., base_url=...)` | `(client {:api-key ... :auth-token ... :base-url ... :max-retries ...})` | Returns an `AnthropicClient` record with map-style keyword access. |
| `client.messages.create(**params)` | `(messages-create client params)` | `params` is a plain map passed through to JSON almost verbatim. |
| `message.content[0].text` | `(output-text response)` | Concatenates ALL `"text"` blocks (not just the first) — matches the original Zig builtin's contract; throws instead of ever returning `nil`. |
| dict key `"max_tokens"` | map key `"max_tokens"` or `:max_tokens` | Both string and keyword keys are accepted and encoded **verbatim** via `name` — no kebab-case↔snake_case conversion. Write `:max_tokens`, not `:max-tokens`. **Resolved, kept as-is:** this is a deliberate data-transparency contract (matches the original Zig builtin and the Python SDK's own literal dict keys), not a gap — converting to kebab-only would be a breaking design change in the wrong direction. If you want kebab-case authoring, use the message content-block DSL below, which is sugar *on top of* the verbatim wire format rather than a replacement for it. |
| `anthropic.APIError` / `.APIStatusError` / `.RateLimitError` / etc. | `ex-info` with `:type` in `ex-data` | See the error-hierarchy table below — one exception constructor, discriminated by `:type`, rather than a Python-style class hierarchy (there is no `class` in Clojure to mirror it with). |
| `client.with_options(...)` | *(not implemented)* | Per-request override without mutating the client. Out of scope for this port; `AnthropicClient` supports associative updates, so callers can use `(assoc client :base-url ...)` themselves. |
| `max_retries` / automatic backoff | `:max-retries` client opt, default 2 | **Resolved, implemented.** See Retries below. |
| `client.messages.create(..., stream=True)` / `client.messages.stream(...)` | `(messages-stream client params)` + `accumulate-event`/`accumulate-stream`/`stream-complete?` | **Resolved, implemented.** A single-use reducible of decoded events instead of a `Stream` iterator; the accumulator ports `accumulate_event`. `messages-create` still rejects `:stream true`. No `MessageStream` helper events (`text`, `input_json` snapshots) or `text_stream`. See Streaming below. |
| `client.messages.count_tokens(...)` | `(count-tokens client request)` | **Resolved, implemented** — same request shape as `messages-create` minus `max_tokens`, same retry policy and error hierarchy, POSTs to `/v1/messages/count_tokens`. |
| `client.messages.batches.create/retrieve/list/cancel/delete/results` | `tools.agents.anthropic.batches`: `batches-create`, `batches-retrieve`, `batches-list` (+ `next-page-params`, `batches-list-all`), `batches-cancel`, `batches-delete`, `batches-results` | **Resolved, implemented.** Same client, retries and error typing as `messages-create`. SDK deviations (no pre-flight `retrieve` in `results`, no `workspace_id`/`user_profile_id` kwargs, results buffered then decoded lazily) are listed in Message Batches below. |
| Workload Identity Federation: `WorkloadIdentityCredentials`, `ANTHROPIC_FEDERATION_RULE_ID` + `ANTHROPIC_ORGANIZATION_ID` + `ANTHROPIC_IDENTITY_TOKEN[_FILE]` | `tools.agents.anthropic.credentials`: `workload-identity-source`, `workload-identity-from-env`, `exchange-token!`; env discovery is step 4 of `client`'s chain | **Resolved, implemented** (#35). See Workload Identity Federation below. Verified against a fake token endpoint only. |
| `ANTHROPIC_PROFILE` / `ANTHROPIC_CONFIG_DIR` / `profile=` / `config=` profiles (files written by `ant auth login`) | *(not implemented, #36)* | Chain steps 3 and 5. Hook points are marked in `resolve-client-credentials`. |
| `tools=[...]` / manual `tool_use`/`tool_result` handling | `"tools"` passes straight through `messages-create`; `tool-use?`/`tool-calls`/`add-tool-results`/`add-tool-result` add the response-side/reply-side ergonomics | **New.** See Tool calling below. |
| `client.beta.messages.create(..., betas=[...])` (Programmatic Tool Calling and other beta features) | `:betas` client opt → comma-joined `anthropic-beta` header | **New.** See Credential resolution below and Programmatic Tool Calling below. |
| `message._request_id` | `(request-id response)` | **Resolved.** The Python SDK hangs this off a hidden attribute on the response object; `messages-create`/`count-tokens` return a plain map, verbatim, per the data-transparency contract above — so the `request-id` response header is carried as Clojure metadata on that same map instead (out of the way of equality, printing, and JSON re-encoding) and `request-id` reads it back. Returns `nil` for a hand-built map or a response genuinely missing the header. For most logging/correlation purposes the response body's own `"id"` field (`msg_...`) needs no accessor and works just as well. |

### Credential resolution & auth headers (confirmed from anthropic-sdk-python source)

Precedence, first match wins (anthropic-sdk-python v1.5.0 @ `eb21a435`,
`_client.py:186-208`, `lib/credentials/_chain.py:76-155`):

| step | source | result | status |
|---|---|---|---|
| 1 | explicit `:credential-source`, else `:api-key` → `:auth-token` | as given; any explicit credential disables env lookup | implemented |
| 2 | `ANTHROPIC_API_KEY` → `ANTHROPIC_AUTH_TOKEN` (empty = unset) | `x-api-key` / static Bearer | implemented |
| 3 | explicit profile: `ANTHROPIC_PROFILE`, `ANTHROPIC_CONFIG_DIR` or an `active_config` pointer; errors propagate | profile credential | **not implemented (#36)** |
| 4 | WIF env: `ANTHROPIC_FEDERATION_RULE_ID` + `ANTHROPIC_ORGANIZATION_ID` + `ANTHROPIC_IDENTITY_TOKEN_FILE` or `ANTHROPIC_IDENTITY_TOKEN` | refreshable `:credential-source` (jwt-bearer exchange) | implemented (#35) |
| 5 | fallback on-disk profile (`configs/<active or default>.json`); errors swallowed | profile credential | **not implemented (#36)** |
| — | nothing matched | throws `missing-credentials` | implemented |

Combining `:credential-source` with `:api-key`/`:auth-token` throws
`invalid-credentials`. Steps 1–2 are exactly the public
`resolve-credentials`, unchanged; the chain lives in the private
`resolve-client-credentials`, which `client` calls with its resolved base
URL. Step 4 sits between 3 and 5 so a leftover default profile never beats
WIF env vars. Unlike the SDK (`_auth.py`
`warn_env_static_shadows_auto_discovery`), no warning is logged when a
static env credential shadows configured WIF: this library has no logger.

- `:api-key` / `ANTHROPIC_API_KEY` → sent as the `x-api-key` header (the
  standard API-key auth path).
- `:auth-token` / `ANTHROPIC_AUTH_TOKEN` → sent as `Authorization: Bearer
  <token>` — **not** `x-api-key`. This is the OAuth/bearer-token path, and it
  additionally requires the `anthropic-beta: oauth-2025-04-20` header, which
  `messages-create` sets automatically whenever `:auth-token` is used.
- `:credential-source` (a `tools.agents.token/TokenSource`) → the same
  Bearer + `oauth-2025-04-20` headers, with the token fetched before every
  attempt. A 401 invalidates it and retries once outside `:max-retries`.
  See README, Refreshable credentials.
- Every request also sends `anthropic-version: 2023-06-01` and
  `content-type: application/json`.
- `:base-url` defaults to `https://api.anthropic.com` (also resolvable from
  `ANTHROPIC_BASE_URL`) — override it to point at a local mock server or a
  third-party Anthropic-compatible gateway (see `examples/anthropic/custom_gateway.clj`).
- `:betas` — a seq of beta-feature flag strings (e.g.
  `["advanced-tool-use-2025-11-20"]` for Programmatic Tool Calling), sent as
  a comma-joined `anthropic-beta` header. Analogous to the Python SDK's
  per-call `betas=[...]` kwarg on `client.beta.messages.create`, set once
  here on the client record since this library has no separate `.beta`
  resource namespace. **Combined, never clobbered:** an `:auth-token`
  client's own required `oauth-2025-04-20` flag is joined with any `:betas`
  you add (`"oauth-2025-04-20,advanced-tool-use-2025-11-20"`), not replaced
  by it — see `examples/anthropic/ptc_demo.clj`.
- `:max-retries` defaults to 2 (matching anthropic-sdk-python's and
  anthropic-sdk-typescript's shared `DEFAULT_MAX_RETRIES`), and must be a
  non-negative integer — `client` throws `{:type
  :tools.agents.anthropic.error/invalid-max-retries}` otherwise. (A negative
  value used to be accepted silently and disabled all retries with no
  signal at all, since `-1` is truthy in Clojure's `or` and `(< attempt -1)`
  is always false — this is now caught at `client` construction, matching
  what `spec.clj`'s own `::max-retries` spec already documented as the
  contract.) See Retries below.

### Workload Identity Federation

`tools.agents.anthropic.credentials` exchanges an external OIDC JWT (a
Kubernetes projected service-account token, a CI OIDC token) for a
short-lived Anthropic access token. Port of anthropic-sdk-python v1.5.0 @
`eb21a435`, `src/anthropic/lib/credentials/`. A WIF token is sent like any
`:credential-source` token (Bearer + `oauth-2025-04-20`); the federation
routing flag never goes on API requests, and no `anthropic-workspace-id`
header is added because the token is already workspace-scoped.

```clojure
(require '[tools.agents.anthropic :as anthropic]
         '[tools.agents.anthropic.credentials :as credentials])

;; Env: no code. With ANTHROPIC_API_KEY and ANTHROPIC_AUTH_TOKEN unset,
;; ANTHROPIC_FEDERATION_RULE_ID, ANTHROPIC_ORGANIZATION_ID and
;; ANTHROPIC_IDENTITY_TOKEN_FILE are enough:
(anthropic/client)

;; Explicit: give both the SAME base-url; a token is only valid on the
;; deployment that minted it.
(anthropic/client
 {:credential-source (credentials/workload-identity-source
                      {:federation-rule-id  "fdrl_..."
                       :organization-id     "00000000-0000-0000-0000-000000000000"
                       :identity-token-file "/var/run/secrets/anthropic/token"
                       :service-account-id  "svac_..."})})
```

Exchange (`_workload.py:249-364`):

- `POST {base-url}/v1/oauth/token` (`_constants.py:12`), JSON body:
  `grant_type` = `urn:ietf:params:oauth:grant-type:jwt-bearer`
  (`_constants.py:10`), `assertion`, `federation_rule_id`, `organization_id`,
  plus `service_account_id` / `workspace_id` when set (`_workload.py:264-273`).
  `scope` is accepted and **not sent** (`_workload.py:178-182`).
- Headers: `anthropic-beta: oauth-2025-04-20,oidc-federation-2026-04-01`
  and `content-type: application/json` only (`_workload.py:36,282-286`). The
  `oidc-federation-2026-04-01` flag routes the POST to the jwt-bearer
  handler; it must never go on a `refresh_token` grant, which takes
  `oauth-2025-04-20` alone (`_constants.py:18-28`). No `user-agent`: the SDK
  sends `anthropic-python/<ver>`, this library sends none anywhere.
- One POST with a 30 s timeout (`_constants.py:16`), through
  `tools.agents.http/request!`, not the messages retry loop. A 401 from the
  endpoint is retried once, re-reading the identity token, as the SDK's
  cache does (`_cache.py:88-103`).
- Response: `access_token` and `expires_in` (seconds; a numeric string is
  accepted, like Python's `int()`) are required; `token_type`, if present,
  must be `bearer` in any case (`_workload.py:343-364`).
- Limits: assertion ≤ 16 KiB, rejected before any request; response ≤ 1 MiB
  (`_workload.py:49-50`).
- `base-url` must be `https://`, or `http://localhost`, `http://127.0.0.1`
  or `http://[::1]` (`_constants.py:116-132`); anything else throws
  `invalid-credentials` at construction.

Identity token: `:identity-token-file` is re-read and trimmed on **every**
exchange (Kubernetes rotates it in place); a missing, unreadable, directory
or empty file throws `identity-token` (`_providers.py:736-781`).
`:identity-token-fn` is called on every exchange.

Env discovery (`workload-identity-from-env`, `_chain.py:29-73`) returns nil
unless `ANTHROPIC_FEDERATION_RULE_ID` and `ANTHROPIC_ORGANIZATION_ID` are
non-empty and `ANTHROPIC_IDENTITY_TOKEN_FILE` is non-empty or
`ANTHROPIC_IDENTITY_TOKEN` is set (even empty). The file wins over the
literal; the literal is re-read from the env on every exchange.
`ANTHROPIC_SERVICE_ACCOUNT_ID` is sent when set (even empty, as in the SDK);
an empty `ANTHROPIC_WORKSPACE_ID` counts as unset; `ANTHROPIC_SCOPE` is
ignored. `{:getenv f}` injects the env. Base URL: `:base-url` opt >
`ANTHROPIC_BASE_URL` > default; `client` passes its own resolved base URL.

Caching is `tools.agents.token/token-cache` with `:refresh-skew-ms` 120000,
the SDK's advisory window (`_constants.py:31`). The SDK cache is two-tier: a
refresh that fails between 120 s and 30 s before expiry serves the
still-valid token and backs off 5 s (`_cache.py:118-129,158-166`).
`token-cache` is single-tier, so that failure throws here. A 401 on an API
request invalidates the token and re-exchanges once (README, Refreshable
credentials).

Errors never carry the assertion or an access token. `:body` keeps only
`error`, `error_description` and `error_uri` from a JSON object, or the first
256 chars of any other body (`_workload.py:58-73`); the assertion is also
replaced wherever it appears (the SDK only truncates). A 401 message carries
the SDK's federation-rule hint. The `request-id` response header is in
`:request-id` and the message.

Verified only against a fake token endpoint (`credentials_test.cljc`); no
live WIF credentials were available.

### Retries

`messages-create` and `count-tokens` both retry automatically on connection
failures and on 408/409/429/5xx responses, up to client's `:max-retries`
(default 2 — `0` disables retries, one attempt only, the pre-retries
behavior). Backoff is exponential, capped at 8s (`0.5 * 2^n` per attempt),
with jitter that only ever *shortens* the delay (`base * (1 - 0.25*rand())`,
same direction as anthropic-sdk-python's own `sleep_seconds * (1 - 0.25 *
random())`) — so the returned delay never exceeds the 8s cap. (An earlier
version of this jitter added the 25% on top of the capped base instead of
subtracting it, which could return up to ~10s despite the "capped at 8s"
claim; fixed to match both the doc and the reference SDK's direction.)

A `Retry-After` response header, when present, wins outright — but is
treated as attacker-reachable data (this library talks to arbitrary
`:base-url`s, not just `api.anthropic.com`, so a compromised/malicious
gateway or a MITM'd plaintext hop controls this value): it's parsed with
`Double/parseDouble`, never `read-string` (whose default `*read-eval*`
would execute a hostile `#=(...)` reader-eval form at parse time), and
clamped to `[0, 60]` seconds — a negative value no longer crashes the whole
retry loop with an unrelated `IllegalArgumentException`/`Thread.sleep`
error (masking the real rate-limit/5xx error), and an absurdly large value
can no longer be honored verbatim. A header value may also arrive as a
VECTOR of strings rather than a bare string — `tools.agents.http/request!`
returns it that way on both runtimes whenever the header name appears more than once in the
response (a real occurrence behind a proxy/gateway that duplicates or folds
a singleton header) — the first element is used in that case, rather than
silently failing to parse and falling back to the shorter computed backoff.

Permanent failures — `:streaming-unsupported` and
any other non-retryable 4xx (400/401/403/404/422) — are never retried and
never sleep, regardless of `:max-retries`. One exception: a
`:credential-source` client's first 401 invalidates the token and retries
once, immediately and outside `:max-retries`
(`request-with-retries!`'s 3-arity `{:on-unauthorized f}`).

```clojure
(a/client {:api-key "..." :max-retries 5})   ;; more persistent
(a/client {:api-key "..." :max-retries 0})   ;; exactly one attempt, no retries
```

The retry loop itself (`request-with-retries!`) is exposed publicly — same
rationale as `resolve-credentials` being public — so it's unit-testable with
a fake attempt-fn and no mock server; bind `*sleep-fn*` to a no-op in tests to
skip real waiting.

`messages-create`/`count-tokens` build the URL, headers, and JSON body ONCE
before entering the retry loop, not on every attempt — an earlier version
recomputed all three (including re-serializing the whole request) on every
retry, pure wasted CPU/latency for work that's provably identical across
attempts.

### count-tokens

```clojure
(a/count-tokens client {"model" "claude-sonnet-4-6"
                         "messages" [{"role" "user" "content" "hi"}]})
;; => {"input_tokens" 8}
```

Same request shape as `messages-create` (minus `max_tokens`, which
`/v1/messages/count_tokens` doesn't take), same retry policy, same error
hierarchy and ex-data shape.

### Error hierarchy

`messages-create` and `count-tokens` throw `ex-info` with message prefixed
`"tools.agents.anthropic/messages-create: "` / `"tools.agents.anthropic/count-tokens: "`
(or `"tools.agents.anthropic/output-text: "` / `"tools.agents.anthropic/content-blocks: "`
for those functions' own failures) and `ex-data` `{:type <keyword> :status
<http-status-or-nil> :body <raw-body-or-nil> :headers <response-headers-map-or-nil>}`
(`:headers` is what `request-with-retries!` reads to honor `Retry-After`):

| HTTP status | `:type` |
|---|---|
| 400 | `:tools.agents.anthropic.error/bad-request` |
| 401 | `:tools.agents.anthropic.error/authentication` |
| 403 | `:tools.agents.anthropic.error/permission-denied` |
| 404 | `:tools.agents.anthropic.error/not-found` |
| 422 | `:tools.agents.anthropic.error/unprocessable-entity` |
| 429 | `:tools.agents.anthropic.error/rate-limit` |
| ≥500 | `:tools.agents.anthropic.error/internal-server` |
| in-stream `error` event with `error.type` `overloaded_error` (`messages-stream` only; other `error.type`s map as in Streaming below) | `:tools.agents.anthropic.error/overloaded` |
| other non-2xx (e.g. 413, 529) | `:tools.agents.anthropic.error/api-status` |
| no response at all (DNS/refused/TLS/timeout) | `:tools.agents.anthropic.error/api-connection` |
| malformed request/response JSON | `:tools.agents.anthropic.error/json-encode` / `:tools.agents.anthropic.error/json-parse` |
| empty or non-string message batch id (`tools.agents.anthropic.batches`, before any request) | `:tools.agents.anthropic.error/invalid-argument` |
| missing credentials (client construction) | `:tools.agents.anthropic.error/missing-credentials` |
| `:credential-source` not a `TokenSource`, or combined with `:api-key`/`:auth-token`; bad `workload-identity-source` options, including a cleartext non-loopback base URL | `:tools.agents.anthropic.error/invalid-credentials` |
| WIF token endpoint unreachable, non-2xx, or oversized/malformed response; assertion over 16 KiB. `ex-data` `{:type :status :body <redacted> :request-id}` | `:tools.agents.anthropic.error/token-exchange` |
| WIF identity token file missing, unreadable, a directory or empty; `ANTHROPIC_IDENTITY_TOKEN` removed after discovery. `ex-data` `{:type :path}` | `:tools.agents.anthropic.error/identity-token` |
| `:max-retries` is not a non-negative integer (client construction) | `:tools.agents.anthropic.error/invalid-max-retries` |
| `:stream true` requested on `messages-create` (use `messages-stream`) | `:tools.agents.anthropic.error/streaming-unsupported` |
| stream event before `message_start`, or a delta/stop for an index with no block (`accumulate-event`) | `:tools.agents.anthropic.error/invalid-response` |
| response has no `"content"` array | `:tools.agents.anthropic.error/invalid-response` |
| no `"text"` blocks in `"content"` | `:tools.agents.anthropic.error/no-text-content` |
| `"text"` content block has a non-string `"text"` value | `:tools.agents.anthropic.error/invalid-content-shape` |
| `content-blocks` seq item is neither a string nor a map | `:tools.agents.anthropic.error/invalid-content-block` |

When the response body matches Anthropic's `{"error":{"message":"..."}}`
shape, that human-readable message is extracted and appended to the thrown
message (`"HTTP 429 slow down"`); otherwise the raw body is appended
(`"HTTP 500 <raw body>"`); otherwise just `"HTTP <status>"`.

**Breaking rename:** every `:type` keyword moved from the `tools.agents.anthropic`
namespace to `tools.agents.anthropic.error`, dropping the redundant `-error`
suffix where one existed (e.g. `:tools.agents.anthropic/bad-request-error` →
`:tools.agents.anthropic.error/bad-request`; `:tools.agents.anthropic/rate-limit-error`
→ `:tools.agents.anthropic.error/rate-limit`). Keywords with no `-error` suffix
to begin with only moved namespace (e.g. `:tools.agents.anthropic/missing-credentials`
→ `:tools.agents.anthropic.error/missing-credentials`). Update any `(= (:type
(ex-data e)) :tools.agents.anthropic/...)` check in calling code to the new
namespace.

### Tool calling

`"tools"` in the request map already passes straight through `messages-create`
untouched — this library is data-transparent by design, so tool definitions
need no special handling. What's added is ergonomics for the two things every
tool-calling loop otherwise hand-rolls: noticing the model asked for a tool,
and sending every result back in the shape the API requires.

- `(tool-use? response)` — true when `response`'s `"stop_reason"` is
  `"tool_use"`.
- `(tool-calls response)` — extracts `response`'s `tool_use` content blocks
  as a vector of `{:id ... :name ... :input ...}` maps (only this outer
  wrapper is keyword-keyed; `:input` stays exactly as the API returned it,
  since it's arbitrary caller-defined JSON). Returns `[]` — never throws —
  when there are no tool_use blocks: "the model didn't call a tool" is an
  ordinary branch here, unlike `output-text`'s stricter contract.
- `(add-tool-results messages results)` — appends **one** user turn holding a
  `tool_result` content block for each `{:tool-use-id ... :content ...
  :is-error ...}` map in `results`. Every tool_use call from one assistant
  turn must be answered inside a single following user message as multiple
  content blocks, never one message per result — the shape this signature is
  built to make the natural one. `add-tool-result` (singular) is a
  convenience wrapper for the common one-tool-call case. `:content` gets the
  same widened handling as `add-user-message`/`add-assistant-message` below
  (string verbatim, single map wrapped into a one-element array, mixed
  string/map seq normalized via `content-blocks`) — it used to be forwarded
  to the wire completely unnormalized, so a bare image-block map (this
  docstring's own non-text-output example) or a mixed seq shipped a
  malformed `tool_result` payload; fixed to match every sibling
  content-accepting function.

```clojure
(defn tool-loop [client messages]
  (loop [messages messages]
    (let [response (a/messages-create client
                     {"model" "claude-sonnet-4-6" "max_tokens" 1024
                      "tools" tools "messages" messages})]
      (if (a/tool-use? response)
        (let [messages (a/add-assistant-message messages (get response "content"))
              results  (mapv (fn [{:keys [id name input]}]
                                {:tool-use-id id :content (run-tool name input)})
                              (a/tool-calls response))]
          (recur (a/add-tool-results messages results)))
        response))))
```

See `examples/anthropic/tool_use.clj` for the complete runnable port (including
per-call error handling via `:is-error`).

### Programmatic Tool Calling (PTC)

Port of [anthropic-cookbooks' `tool_use/programmatic_tool_calling_ptc.ipynb`](https://github.com/anthropics/claude-cookbooks/blob/main/tool_use/programmatic_tool_calling_ptc.ipynb) —
Claude writes code that calls tools *inside* the Code Execution environment
instead of round-tripping through the model for every invocation, cutting
both latency and token usage on workflows built around large, metadata-rich
tool results. `"tools"` still passes straight through `messages-create`
untouched — PTC only adds a few more wire-format fields this library is
already data-transparent about, plus one small client capability
(`:betas`, above) the beta header itself needed:

- `allowed_callers` on a tool definition — opts it into being invoked from
  code (`["code_execution_20250825"]`), from the model directly
  (`["direct"]`, the default when absent), or both.
- The `code_execution` tool itself: `{"type" "code_execution_20250825"
  "name" "code_execution"}`, a **server tool** Anthropic's own container
  executes — it never needs a client-supplied `tool_result` and never
  appears as a `tool_use` content block (it surfaces as `server_tool_use` /
  `code_execution_tool_result` instead — both already rendered by
  `tools.agents.anthropic.visualize`).
- `"container"` — a plain top-level request-map key (Python's
  `extra_body={"container": container_id}`) carrying code-execution state
  across turns; read back off the response the same way, via `(get-in
  response ["container" "id"])`.
- Each `tool_use` block's `"caller"` field — `{"type" "direct"}` vs
  `{"type" "code_execution_20250825"}` — deliberately **not** added to
  `tool-calls` itself (that would widen a general-purpose helper for one
  beta feature); `examples/anthropic/ptc_demo.clj` reads it locally off the raw
  response instead.

```clojure
(def client (a/client {:api-key (System/getenv "ANTHROPIC_API_KEY")
                        :betas ["advanced-tool-use-2025-11-20"]}))

(def ptc-tools
  (conj (mapv #(assoc % "allowed_callers" ["code_execution_20250825"]) tools)
        {"type" "code_execution_20250825" "name" "code_execution"}))
```

See `examples/anthropic/ptc_demo.clj` (the agent loop, both with and without PTC —
the cookbook's own before/after comparison) and
`examples/anthropic/ptc_expense_api.clj` (a port of the cookbook's mock third-party
`utils/team_expense_api.py`) for the complete runnable demo, and
`test/tools/agents/anthropic/live_test.cljc`'s `example-e-ptc-...` /
`example-f-baseline-...` tests for the full mechanics — container
threading, `"caller"`-based routing, and the one rule that matters most:
never send a `tool_result` for the `code_execution` server tool itself —
proven end-to-end against a mock server, no beta access required. Running
`examples.anthropic.ptc-demo/-main` for real needs an Anthropic account with PTC beta
access, since `advanced-tool-use-2025-11-20` is opt-in.

### Message content-block DSL

`add-user-message`/`add-assistant-message` are widened (same names, same
arity — strictly backward compatible) to accept more than a bare string:

- a string — unchanged: sent verbatim as a bare string.
- a map — one content block, wrapped into a one-element array.
- a seq — mixed strings/maps, normalized via `content-blocks`: bare strings
  become text blocks, maps (hand-written, built via the constructors below,
  or copied straight from a decoded response's own `"content"` array) pass
  through verbatim.

`content-blocks` (and everything above it that calls it) throws `ex-info`
`{:type :tools.agents.anthropic.error/invalid-content-block}` for anything
that's not a string, a map, a seqable
collection, or `nil`. A `nil` seq ITEM (as
opposed to `items` itself) is silently dropped, not rejected — the ordinary
`[(when include-image? (a/image-url u)) (a/text "hi")]` idiom is common
Clojure, not malformed input, and used to throw once the top-level guard
above was added.

Small content-block constructors are provided for the common shapes —
`text`, `image-base64`, `image-url`, `document-base64`, `document-url`,
`document-text`, `thinking`, `tool-use` — all plain functions returning plain
wire-format maps, no macros, no tagged-vector grammar:

```clojure
(a/add-user-message []
  [(a/image-base64 "image/png" chart-png-base64)
   (a/text "What's the trend in this chart, and what's today's forecast?")])
;; => [{"role" "user"
;;      "content" [{"type" "image" "source" {"type" "base64" "media_type" "image/png" "data" chart-png-base64}}
;;                 {"type" "text" "text" "What's the trend in this chart, and what's today's forecast?"}]}]

;; content-blocks doubles as the "system" field builder — same grammar:
(def system (a/content-blocks [(a/text preamble {"cache_control" {"type" "ephemeral"}})
                                (a/text "Be terse.")]))

;; Replaying a model turn verbatim (thinking's opaque signature and every
;; tool_use block, byte-for-byte, as the API requires) needs no constructor
;; at all — a decoded response's own "content" array is already a valid seq
;; of content-block maps:
(a/add-assistant-message messages (get response "content"))
```

Anything not covered by name (Files API `file_id` sources, `redacted_thinking`,
citations, future block types) needs no special syntax either — hand-write
the raw wire map and hand it to `content-blocks`/`add-user-message`/
`add-assistant-message`; maps always pass through verbatim.

This DSL came out of a 4-candidate design panel (hiccup-style tagged
vectors, plain builder functions, a macro-templating approach, and this
plain-data-normalizer approach) judged against simplicity, power,
portability, consistency with the existing helpers, and how
pleasant it makes the tool-calling loop above — the synthesis above won on
consistency (it widens `add-user-message`/`add-assistant-message` in place
rather than adding a competing parallel API) and portability (plain functions
over string-keyed maps, no macros, no new grammar).

### Message Batches

`tools.agents.anthropic.batches` ports `client.messages.batches.*`
([anthropic-sdk-python `src/anthropic/resources/messages/batches.py`](https://github.com/anthropics/anthropic-sdk-python/blob/eb21a4352015686c30f5759e8c2f02d70f5371e2/src/anthropic/resources/messages/batches.py),
[API reference](https://platform.claude.com/docs/en/api/messages/batches)):

| function | request | returns |
|---|---|---|
| `(batches-create client {"requests" [...]})` | `POST /v1/messages/batches`, body verbatim | MessageBatch map |
| `(batches-retrieve client id)` | `GET /v1/messages/batches/{id}` | MessageBatch map |
| `(batches-list client)` / `(batches-list client params)` | `GET /v1/messages/batches?limit&after_id&before_id` | page map `{"data" "has_more" "first_id" "last_id"}` |
| `(next-page-params params page)` | — (pure) | params for the next page, or `nil` |
| `(batches-list-all client params)` | pages on demand | lazy seq of MessageBatch maps |
| `(batches-cancel client id)` | `POST /v1/messages/batches/{id}/cancel`, no body | MessageBatch map |
| `(batches-delete client id)` | `DELETE /v1/messages/batches/{id}` | `{"id" id "type" "message_batch_deleted"}` |
| `(batches-results client id)` | `GET /v1/messages/batches/{id}/results`, `accept: application/binary` | lazy seq of decoded JSONL lines |

```clojure
(require '[tools.agents.anthropic :as a]
         '[tools.agents.anthropic.batches :as b])

(def client (a/client))

(def batch
  (b/batches-create client
    {"requests" [{"custom_id" "q1"
                  "params" {"model" "claude-haiku-4-5" "max_tokens" 100
                            "messages" [{"role" "user" "content" "Hello"}]}}]}))

;; poll until processing ends (batches can take up to 24h)
(loop []
  (when-not (= "ended" (get (b/batches-retrieve client (get batch "id")) "processing_status"))
    (Thread/sleep 60000)
    (recur)))

(doseq [{:strs [custom_id result]} (b/batches-results client (get batch "id"))]
  (case (get result "type")
    "succeeded" (println custom_id (a/output-text (get result "message")))
    "errored"   (println custom_id "failed:" (get-in result ["error" "error" "message"]))
    (println custom_id (get result "type"))))          ;; "canceled" / "expired"

(take 50 (b/batches-list-all client {"limit" 20}))    ;; newest first, pages fetched on demand
```

All six calls go through `tools.agents.anthropic/request!`, the general
request function (method, path, query, body, extra headers, `:as
:json|:response`), so they share `messages-create`'s credentials,
`anthropic-version`, `:betas`, retry policy (a 409 on a concurrent batch edit
is retried like any 408/429/5xx) and error typing. Batches are GA: the SDK
sends no `anthropic-beta` header for them, and neither does this namespace.

Paging mirrors the SDK's `SyncPage`: `has_more: false` ends paging; when the
request carried `before_id`, the next page is `{"before_id" first_id}`,
otherwise `{"after_id" last_id}`; other params such as `limit` carry over.

An `"errored"` result line is data, not an exception. A malformed line throws
`:tools.agents.anthropic.error/json-parse` (ex-data `:line`) when the seq is
realized that far. `request-id` works on the returned seq.

Deviations from anthropic-sdk-python:

- **`results` makes one request.** The SDK first calls `retrieve`, raises
  `AnthropicError` when `results_url` is null, then GETs the absolute
  `results_url`. Following that URL would bypass `:base-url` (gateways, mock
  servers) and could send credentials to a different host, so
  `batches-results` GETs `{base-url}/v1/messages/batches/{id}/results`
  directly, the path the API reference documents. A batch with no results
  yet surfaces as the API's typed HTTP error instead.
- **Results are buffered.** The SDK's `JSONLDecoder` streams the body. Here
  the body is read into a String inside the retry loop (so no connection
  leaks on a retried or abandoned attempt) and decoded lazily from it.
- **No `workspace_id` / `user_profile_id` kwargs** (`anthropic-workspace-id`
  / `anthropic-user-profile-id` headers), and no per-call
  `extra_headers`/`extra_query`/`timeout`. Call `tools.agents.anthropic/request!`
  with `:headers` if you need them.
- **Empty id** throws `:tools.agents.anthropic.error/invalid-argument` before
  any request, as the SDK's `ValueError`; the id is percent-encoded into the
  path, as the SDK's `path_template`.

### Response visualization

`src/tools/agents/anthropic/visualize.cljc` is a port of anthropic-cookbooks'
[`tool_use/utils/visualize.py`](https://github.com/anthropics/claude-cookbooks/blob/main/tool_use/utils/visualize.py)
— a terminal tree+panel renderer for a Claude API response (role, model,
stop_reason, token usage, and every content block: text, tool_use,
tool_result, server_tool_use, code_execution_tool_result, and any
unrecognized type as a raw-JSON fallback).

```clojure
(require '[tools.agents.anthropic.visualize :as viz])

(viz/show-response response)   ;; parse + print, one call — port of show_response

;; or capture a whole tool-calling loop as it runs:
(def v (viz/visualizer))
(viz/capture! v response)      ;; prints immediately (:auto-show? true, the default)
(viz/show-all! v)               ;; replay every captured response

;; {:color? false} for a session piping to a log file / non-TTY CI console —
;; forwarded to every capture!/show-all! render (previously there was no way
;; to do this through the session API at all):
(def plain-v (viz/visualizer {:color? false}))

;; :responses accumulates unboundedly for as long as v is held (unlike the
;; Python original's with-scoped context manager) — clear! releases it:
(viz/clear! v)
```

| Python | tools.agents.anthropic.visualize | Notes |
|---|---|---|
| `parse_response(response)` | `(parse-response response)` | Same shape: `{:role :content :model :stop-reason :usage}`. |
| `parse_content_block(block)` | `(parse-content-block block)` | Collapsed to ONE code path — see below. |
| `format_json(data, max_length=500)` | `(format-json data)` / `(format-json data max-length)` | Pretty-printed + truncated, same defaults. |
| `visualize_message(message, console=None)` | `(render-message message opts)` (pure, returns a string) + `(visualize-message message opts)` (prints it) | Split in two, unlike the Python original, specifically so the renderer itself is unit-testable with zero I/O — see `test/tools/agents/anthropic/visualize_test.cljc`. |
| `class visualize` (context manager) | `(visualizer opts)` — a plain map holding an atom, plus `capture!`/`show-all!` | A plain map is sufficient here because the visualizer's identity is carried by its atom; unlike the API client, it has no dedicated record type. |
| `show_response(response)` | `(show-response response)` / `(show-response response opts)` | |
| `rich.tree.Tree` / `rich.panel.Panel` / `rich.syntax.Syntax` (Rich, an added dependency) | hand-rolled ANSI tree/panel renderer, zero dependencies | No `rich`-equivalent is available on both runtimes without adding one — same zero-dependency, data-transparency spirit as this library's own hand-rolled JSON codec. |
| `Syntax(..., theme="monokai")` per-token highlighting | single ANSI color per block | No syntax highlighter is being ported, just Rich's structural coloring (role/type/status labels). |
| `Syntax(code, "python", line_numbers=True)` | `number-lines` — plain `<n> | <line>` prefix | Ported (the decision point was explicit, not a silent drop), minus per-token highlighting. |
| tty / `NO_COLOR` auto-detection | explicit `{:color? true}` opt, no auto-detection | Simplicity: no new runtime-specific leaf added just for this — see below. |
| `isinstance(dict)` / `hasattr(SDK object)` / `str` 3-way branch in `parse_content_block` | one `cond` branch | tools.agents.anthropic's `messages-create` always returns a plain decoded (string-keyed) map — there's no second, SDK-object-typed representation to bridge here, unlike the Python SDK's own `Message`/`ContentBlock` classes. |

**No runtime-specific code at all:** this file needs no `#?(:bb ... :clj
...)` reader conditional anywhere; rendering text to a string and
`println`-ing it needs no HTTP client.

**Plain ASCII** (`+`, `-`, `|`, `` ` ``) for all structural drawing —
classic `tree`-CLI style rather than Rich's rounded Unicode boxes. Every
structural character is then one column wide, which is what keeps the
count-based width math correct without a display-width table. Truncation
lengths (1000/2000/500 chars) match the Python original.

See `test/tools/agents/anthropic/visualize_test.cljc` for the full behavioral
coverage (every content-type renderer, truncation, color on/off, panel-border
alignment, thousands-separator grouping) and `examples/anthropic/visualize_demo.clj`
for a runnable, fixture-based (no network) 3-turn tool-calling demo.

### Optional `clojure.spec.alpha` definitions
```clojure
(require '[tools.agents.anthropic.spec :as spec]
         '[clojure.spec.alpha :as s])

(s/valid? ::spec/request-map {"model" "m" "messages" []})    ;; => true
(s/valid? ::spec/error-type :tools.agents.anthropic.error/rate-limit) ;; => true
```

The Messages API wire format is **string-keyed** — `s/keys` only ever matches
keyword keys, so a naive `(s/keys :req-un [::model ::max_tokens])` spec over
a real request map would validate nothing at all. `tools.agents.anthropic.spec`
splits accordingly: genuinely keyword-keyed surfaces (client opts, ex-data,
the tool-calling/DSL descriptor maps — `::client-opts`, `::resolved-client`,
`::ex-data`, `::error-type`, `::tool-call`, `::tool-result-descriptor`) use
ordinary `s/keys`; wire-shaped values (`::request-map`, `::message-map`,
`::content-block-map`) use `s/and` with explicit string-key predicates
instead. A few `s/fdef`s (`client`, `content-blocks`, `add-tool-results`) are
included as a starting point for instrumentation — run `(require
'[clojure.spec.test.alpha :as stest]) (stest/instrument)` yourself to turn
them on; requiring the spec ns alone does not enable instrumentation.

Three spec-correctness bugs, found by adversarial review (and one follow-up
of its own) and fixed:

- `::tool-result-descriptor` required an unqualified key
  `:tool-result-content`, but `add-tool-results`/`add-tool-result` actually
  destructure `:content` (the key every README example and
  `examples/anthropic/tool_use.clj` use) — instrumenting `add-tool-results` and calling
  it with the documented shape threw a spec-conformance error. Fixed by
  requiring `::content` (an alias for the same value spec) instead.
- Once the key was fixed, its VALUE spec turned out to be too narrow for
  what `add-tool-results` actually accepts: only `string?` or `(s/coll-of
  ::content-block-map)`, rejecting a bare content-block map (the docstring's
  own non-text-output example) and a mixed string/map seq — the same defect
  class as the key bug, just one layer deeper. Widened to accept a string, a
  single content-block map, or a seq of either.
- `::resolved-client`'s doc claims "exactly one of `:api-key`/`:auth-token` is
  guaranteed present", but `s/or` alone only enforces *at least* one — a map
  with both keys wrongly validated. Fixed with a trailing xor predicate —
  and note it has to sit **before** the `s/or` inside the `s/and`, not after:
  `s/and` threads each spec's *conformed* value into the next, and `s/or`
  conforms to a tagged pair like `[:api-key {...}]`, not the original map, so
  a predicate placed after it would silently see that tuple instead
  (`s/keys` conforms as identity, so a predicate right after it still sees
  the real map).

### Streaming

```clojure
(let [s (a/messages-stream client request)]
  (run! #(some-> (get-in % ["delta" "text"]) print) s))   ; text as it arrives

(let [m (a/accumulate-stream (a/messages-stream client request))]
  (when-not (a/stream-complete? m) (throw (ex-info "truncated" {})))
  (a/output-text m))                                       ; the final Message
```

**Wire.** `POST /v1/messages` with `"stream": true` added to the request (a
caller's `:stream`/`"stream"` value is replaced), same headers as
`messages-create` (`x-api-key`, or `Authorization: Bearer` +
`oauth-2025-04-20`; `anthropic-version`; `:betas` in `anthropic-beta`).
Sources: Anthropic's streaming docs
(platform.claude.com/docs/en/build-with-claude/streaming): `message_start`,
then per block `content_block_start` / `content_block_delta`+ /
`content_block_stop`, then `message_delta`+ and `message_stop`, with `ping`
anywhere; each SSE event name is repeated as `"type"` in its data; "new event
types may be added"; usage in `message_delta` is cumulative. The accumulator
ports anthropic-sdk-python `src/anthropic/lib/streaming/_messages.py`
`accumulate_event` @ `eb21a4352015686c30f5759e8c2f02d70f5371e2`; error-event
handling ports `src/anthropic/_streaming.py` `Stream.__stream__` at the same
commit.

**Events.** The reducible yields each event's decoded data map (string keys)
verbatim, `ping` and unknown types included; a data map with no `"type"` gets
the SSE event name, as `Stream.__stream__` does. The SDK's raw `Stream` drops
`ping` and unknown event names; here they pass through, since the docs ask
clients to handle them gracefully.

**Lifecycle.** `messages-stream` sends the request at call time and returns
the `tools.agents.stream` reducible. It is single-use: one reduce consumes it
and closes the connection, including on early termination such as
`(into [] (take 1) s)`. A stream you never reduce must be released with
`(tools.agents.stream/close! s)`, which is also the cross-thread cancel.
`with-open`/`.close` work on the JVM only, because Babashka's `reify` cannot
add `java.io.Closeable`. `(tools.agents.stream/response s)` gives the 2xx
status and headers; `accumulate-stream` over the stream attaches them, so
`request-id` works on the accumulated message. No resumption: a lost stream
is re-requested by the caller.

**Retries and errors.** The opening exchange runs under `messages-create`'s
retry policy (`request-with-retries!`: 408/409/429/5xx incl. 529, connection
failures, `Retry-After`, `:max-retries`, `*sleep-fn*`), with headers rebuilt
per attempt; a `:credential-source` client's first 401 invalidates the token
and retries once outside `:max-retries`. The SDK also retries only the
request, before its iterator starts. Nothing is retried once a 2xx body is
being read.

| failure | when it throws | `:type` |
|---|---|---|
| non-2xx after retries | from `messages-stream` | the status table above, same `:status`/`:body`/`:headers`; message `tools.agents.anthropic/messages-stream: HTTP <status> <message>` |
| no connection after retries | from `messages-stream` | `api-connection` |
| `event: error` (or data `"type": "error"`) | from the reduce; nothing after it is delivered | from `error.type`: `invalid_request_error` → `bad-request`, `authentication_error` → `authentication`, `permission_error` → `permission-denied`, `not_found_error` → `not-found`, `rate_limit_error` → `rate-limit`, `api_error` → `internal-server`, `overloaded_error` → `overloaded`, anything else → `api-status`. `:status nil`, `:body` the raw `data:` string, `:headers` the 2xx response's, `:error-type` the wire string |
| connection lost mid-stream | from the reduce | `api-connection`, cause the `IOException` |
| undecodable event | from the reduce | `json-parse` |

**Divergence: error typing.** The SDK raises an in-stream error through
`_make_status_error`, which dispatches on the HTTP status only. A stream's
status is 200, so every in-stream error is a plain `APIStatusError` there.
Here the type comes from `error.type`, per the error-type table in
Anthropic's errors docs (`overloaded_error` is HTTP 529). `overloaded` is
streaming-only: an HTTP 529 before the stream still types as
`internal-server`.

**Truncation.** A body that ends without `message_stop` reduces normally, and
`(tools.agents.stream/outcome s)` is `:eof` for complete and truncated streams
alike: `messages-stream` sets no `done?`, so `message_stop` reaches the
reducer. `(stream-complete? message)` is true iff `accumulate-event` saw
`message_stop`. The flag is metadata, so a hand-built, non-streamed or rebuilt
(`into {}`) map reads `false`. Neither the stream nor the accumulator throws
on truncation. A connection that drops mid-chunk is a transport error and
throws, see above.

**Accumulating.** `accumulate-event` is a pure reducing fn (`[]` → `nil`,
`[acc]` → `acc`, `[acc event]`); `(accumulate-stream events)` is
`(transduce identity accumulate-event events)` over the stream or any
collection. Tool-input buffers and the completion flag live in metadata, so
the result is `=` to the non-streamed Message and
`output-text`/`tool-calls`/`tool-use?` work on it.

| event | rule | vs. `accumulate_event` |
|---|---|---|
| `message_start` | the message, `content` a vector | same |
| content/message event before `message_start` | throws `invalid-response` | same (`RuntimeError`); `ping`/unknown before it are ignored, since the SDK never passes them in |
| `content_block_start` | block assoc'ed at `index` | the SDK appends and ignores `index` |
| `text_delta` | appends `text` on a `text` block | same |
| `citations_delta` | conjs `citation` onto `citations` (created when absent or null) on a `text` block | same |
| `thinking_delta` | appends `thinking` on a `thinking` block | same |
| `signature_delta` | sets `signature` on a `thinking` block | same |
| `input_json_delta` | appends `partial_json` to a per-index buffer on `tool_use`/`server_tool_use` (`TRACKS_TOOL_INPUT`) | the SDK parses the buffer on every delta in jiter partial mode, so its `input` is a live snapshot |
| `content_block_stop` | a tool block with a non-empty buffer gets `input` = the parsed buffer; an empty buffer keeps the start `input` (`{}`); invalid JSON throws `json-parse` with the buffer as `:body` | the SDK raises `ValueError` on invalid JSON at the delta; for a truncated `tool_use` it keeps the partial snapshot, here `input` stays `{}` |
| `message_delta` | `stop_reason`/`stop_sequence`/`stop_details` overwritten; `container` when non-nil; `usage.output_tokens` overwritten; `input_tokens`, `cache_creation_input_tokens`, `cache_read_input_tokens`, `server_tool_use`, `output_tokens_details` overwritten only when non-nil | same, except a `stop_details` absent on both sides stays absent instead of becoming `null` |
| `message_stop` | marks complete | no flag in the SDK |
| `error` | throws, typed as above | unreachable in the SDK (its stream raises first) |
| `ping`, unknown event or delta types | ignored | same |
| `text` block stop with `output_format` | not implemented | the SDK sets `parsed_output` |

### Requiring `examples/` from the tests

`examples/*.clj` is requireable from
`test/tools/agents/anthropic/live_test.cljc` because the repo root (`.`) is
on every test alias's extra paths in `deps.edn`, and on `bb.edn`'s global
`:paths`. That resolves `examples.anthropic.basic-chat` →
`./examples/anthropic/basic_chat.clj`.

### JSON: a small hand-rolled codec, not a dependency

`write-json`/`read-json`/`json-key->str` in `tools/agents/anthropic.cljc` are
thin wrappers over the repo's shared hand-written codec, `tools.agents.json`
(see the README's "JSON: one shared hand-rolled codec" section). It is in the
same data-transparency spirit as the original Zig builtin's own recursive
`std.json` ↔ `CljVal` conversion, and decodes JSON objects into maps with
**string** keys (matching the original builtin's contract). The wrappers
throw this namespace's own `:tools.agents.anthropic.error/json-encode` /
`json-parse` with `tools.agents.anthropic/write-json: ` /
`tools.agents.anthropic/read-json: ` message prefixes.

## Testing

The shared JSON codec itself is covered by `test/tools/agents/json_test.cljc`
in the core suite (`-M:test-core` / `bb test-core`). `test/tools/agents/anthropic_test.cljc` is pure logic (the JSON
codec's error contract, credential
resolution, error typing, `output-text`, message helpers, the content-block
DSL, tool-calling helpers, `request-with-retries!`'s count/backoff/Retry-After
selection via a fake attempt-fn, `:stream true` rejection, client fail-fast)
— zero I/O, zero network, identical on both runtimes.

`test/tools/agents/anthropic/live_test.cljc`: request line and required headers,
outbound JSON preserving `messages`/`max_tokens` verbatim, credentials never
leaking into the outbound body, successful nested-map/vector decoding,
`output-text` extraction, non-2xx with status+body context (both the
Anthropic-JSON-error-shape and raw-body fallback), malformed-JSON-response
handling, content-less responses, the `auth-token` → `Authorization: Bearer`
+ `anthropic-beta` header path, `:betas` combined correctly with both the
`:api-key` and `:auth-token` paths (the latter proving `oauth-2025-04-20`
survives alongside a requested beta flag rather than being clobbered by it),
and all six `examples/*.clj` ports (basic chat, eval-harness prefill,
custom-gateway auth-token, tool-calling loop, and the PTC demo's baseline
and PTC agents — container threading and `"caller"`-based routing included)
run end-to-end against the mock server. `count-tokens` has its own matching
suite against its own path — happy path, non-2xx error shape, and
`:max-retries` pass-through — not just a single happy-path test riding on
`messages-create`'s coverage (a copy-paste slip dropping its retry
pass-through, or breaking its error wiring, would otherwise pass the suite
clean). A real retry-then-succeed round trip (for both `messages-create` and
`count-tokens`) and the tool-calling example's 2-request loop each drive the
mock server through multiple requests.

`test/tools/agents/anthropic/batches_test.cljc` — the Message Batches
namespace against the mock server: method, path, query string, verbatim body
and headers for all six endpoints (including `anthropic-version`, no beta
header, and `:betas`/`:auth-token` still applied), percent-encoded ids and the
empty-id guard, `next-page-params` forward/backward paging, `batches-list-all`
laziness across pages, JSONL results with succeeded/errored/canceled/expired
lines, blank lines and CRLF, a malformed line's typed parse error, 404 typing
and a 409 both surfaced (`:max-retries 0`) and retried. Its last test is a
real-API create → retrieve → list → cancel → poll → results → delete round trip,
skipped unless `ANTHROPIC_LIVE=1` and `ANTHROPIC_API_KEY` are both set (cost:
at most one `max_tokens: 1` request at batch rates; delete is skipped, not
failed, if the batch has not ended within ~60s).

`test/tools/agents/anthropic/credentials_test.cljc` — Workload Identity
Federation against a fake token endpoint on OS-assigned ports: exchange
body, headers and exact beta value, `scope` never sent, env discovery through
`client` end to end, refresh inside the 120 s window, identity token file
and env literal re-read per exchange, a messages 401 re-exchanging once, the
token endpoint's own 401 retried once, errors that never carry the
assertion or access token, size limits, https enforcement, and chain
precedence via an injected env map (the real env is never read).

`test/tools/agents/anthropic/stream_test.cljc` — `messages-stream` against
the streaming mock servers on OS-assigned ports: request line, body
(`"stream": true`), `anthropic-version`, `x-api-key`/Bearer and combined
`anthropic-beta`; the docs-verbatim `test/resources/sse/anthropic-text.sse`
fixture accumulated to the exact Message, with `output-text`, `request-id`
and `stream-complete?`; paced multi-event delivery (`ping` passed through,
joined deltas equal `output-text`); an in-stream `overloaded_error` typed, not
retried, nothing after it delivered, plus an `event: error` whose data has no
`type`; a 400 before the stream; retries of the opening request (529 then 503
then a stream, `Retry-After`, exhaustion, connection refused, 400 not
retried); a body cut mid-chunk (`api-connection`); a stream without
`message_stop` (`:eof`, not complete); early termination and `close!` seen by
the server as a disconnect; a `:credential-source` 401 invalidate-and-retry
(a second 401 and a static key's 401 not retried); `messages-create` still
refusing `:stream true`. The pure accumulator tests are in
`anthropic_test.cljc`: text, `tool_use` partial JSON (the docs' empty first
chunk, truncation, invalid JSON, `server_tool_use`), thinking + signature,
citations, cumulative usage merge, event ordering, unknown events and the
error-type mapping.

`test/tools/agents/anthropic/spec_test.clj` — asserts each spec both
accepts a valid value and REJECTS a malformed one (including the exact
string-vs-keyword-key trap the wire-shaped specs exist to catch, and
regression guards for the two spec-correctness bugs above:
`::tool-result-descriptor`'s old wrong key name, and `::resolved-client`
wrongly accepting both credential keys at once), plus three
`stest/instrument`-around-a-real-call tests proving the `s/fdef`s actually
constrain their functions — including one that instruments
`add-tool-results` and calls it with the exact documented `:content` shape,
closing the gap where instrumenting it used to reject every correct call.

`test/tools/agents/anthropic/visualize_test.cljc` — pure logic, zero I/O, run
directly on both runtimes (no `#?()` branching needed anywhere — see Response
visualization above): every content-type
renderer (text, tool_use, tool_result incl. list-content and error status,
server_tool_use with/without code, code_execution_tool_result incl. the
no-output case, and the unrecognized-type JSON-dump fallback), truncation,
color on/off, panel-border alignment, thousands-separator grouping in the
usage line, and `visualizer`/`capture!`/`show-all!` state (asserted via the
returned/atom state, not by capturing stdout — `visualize-message` just
`println`s `render-message`'s return value, so there's nothing that needs
`with-out-str`).

The mock server is `tools.agents.test-support/start-server!`, shared by the provider and core suites — two tiny runtime branches that
also serve byte[] and streaming (chunked) response bodies. Babashka uses `org.httpkit.server` — verified empirically that
`com.sun.net.httpserver.HttpServer` is not resolvable under bb's native
image, so this is a deliberate fallback, not a default choice. JVM Clojure
uses `com.sun.net.httpserver.HttpServer` (built into the JDK, zero deps).

### Running the tests

```
./script/test-all.sh
```

## License

Apache License 2.0 — see `LICENSE`.
