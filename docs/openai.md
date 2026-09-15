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
places where the clients deliberately behave *differently* are tabulated in
[divergences.md](divergences.md); they all come from following the respective
vendor SDK rather than each other.

For OpenAI's separate (beta) Agents API — managed sessions/turns and
OpenAI-hosted or self-hosted sandboxes, distinct from the Responses/Chat
Completions APIs this doc covers — see
[tools.agents.openai.agents](openai-agents.md), which reuses the `client`
built here as-is.

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

Files (`tools.agents.openai.files`, openai-python's `client.files.*`):

```clojure
(require '[tools.agents.openai.files :as files])

(def f (files/files-create client {"file" (java.io.File. "batch.jsonl")   ;; streamed from disk
                                   "purpose" "batch"
                                   "expires_after" {"anchor" "created_at" "seconds" 86400}}))
(files/files-create client {"file" {:content png-bytes :filename "cat.png"} "purpose" "vision"})
(files/files-list client {"purpose" "batch" "limit" 20 "order" "desc"})  ;; => {"data" [...] "has_more" ...}
(files/files-wait-for-processing client (get f "id"))
(let [^bytes out (files/files-content client (get f "id"))]              ;; raw byte[]
  (java.nio.file.Files/write (.toPath (java.io.File. "out.jsonl")) out
                             (make-array java.nio.file.OpenOption 0)))
(files/files-delete client (get f "id"))                                 ;; => {"id" ... "deleted" true}
```

Images (`tools.agents.openai.images`, openai-python's `client.images.*`):

```clojure
(require '[tools.agents.openai.images :as images])

(def r (images/images-generate client {"model" "gpt-image-1" "prompt" "a red fox" "size" "1024x1024"}))
(images/image-bytes (first (get r "data")))                              ;; b64_json -> byte[]
(images/images-edit client {"image"  [(java.io.File. "a.png") (java.io.File. "b.png")] ;; parts image[]
                            "mask"   (java.io.File. "mask.png")
                            "prompt" "put them together"})
(images/images-create-variation client {"image" {:content png-bytes :filename "in.png"}
                                        "model" "dall-e-2" "response_format" "url"})
;; => {"data" [{"url" "https://..."}]}  — image-bytes returns nil; fetch the URL (valid 60 min)
```

Batches (`tools.agents.openai.batches`, openai-python's `client.batches.*`):

```clojure
(require '[tools.agents.openai.batches :as batches])

(def in (batches/batch-input-jsonl "/v1/responses"               ;; fills "method" "POST" and "url"
          [{"custom_id" "q1" "body" {"model" "gpt-5" "input" "2+2?"}}
           {"custom_id" "q2" "body" {"model" "gpt-5" "input" "3+3?"}}]))
(def in-file (files/files-create client {"file" {:content (.getBytes in "UTF-8") :filename "batch.jsonl"}
                                         "purpose" "batch"}))
(def b (batches/batches-create client {"input_file_id" (get in-file "id")
                                       "endpoint" "/v1/responses"
                                       "completion_window" "24h"}))
(batches/batches-list client {"limit" 20})                       ;; cursor page; "after" = previous "last_id"
;; poll (batches/batches-retrieve client (get b "id")) until "status" is "completed", then:
(def res (batches/batches-results client (get b "id") {:errors? true}))
(get-in res ["q1" "response" "body"])                            ;; lines are unordered — look up by custom_id
```

Fine-tuning (`tools.agents.openai.fine-tuning`, openai-python's `client.fine_tuning.*`):

```clojure
(require '[tools.agents.openai.fine-tuning :as ft])

(def job (ft/jobs-create client {"model" "gpt-4o-mini-2024-07-18" "training_file" "file-abc"
                                 "method" {"type" "supervised"
                                           "supervised" {"hyperparameters" {"n_epochs" 3}}}
                                 "metadata" {"team" "ml"}}))
(ft/jobs-list client {"limit" 10 "metadata" {"team" "ml"}})        ;; metadata[team]=ml
(ft/jobs-list-events client (get job "id") {"limit" 50})
(ft/jobs-pause client (get job "id"))
(ft/jobs-resume client (get job "id"))
(ft/jobs-checkpoints-list client (get job "id"))
;; checkpoint permissions need an organization admin key as :api-key
(let [admin (oai/client {:api-key (System/getenv "OPENAI_ADMIN_KEY")})
      ckpt  "ft:gpt-4o-mini-2024-07-18:org:custom:ckpt-step-100"]
  (ft/checkpoints-permissions-create admin ckpt {"project_ids" ["proj_abc"]})
  (ft/checkpoints-permissions-list admin ckpt {"project_id" "proj_abc"})
  (ft/checkpoints-permissions-delete admin ckpt "cp_123"))
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

See `examples/` for four complete, runnable-against-a-mock-server ports of
real openai-python usage shapes (Responses-API basic chat, legacy Chat
Completions with a `developer` turn, a custom-gateway `base_url` +
org/project client, and the Azure OpenAI v1 API).

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

### Azure OpenAI (v1 API)

Azure's v1 API is served at `{endpoint}/openai/v1/`. It needs no `api-version`
query parameter, and Microsoft documents it for the plain `OpenAI()` client
instead of `AzureOpenAI()`
([Azure OpenAI v1 API](https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle)).
So `:base-url` is all it takes:

```clojure
(oai/client {:base-url "https://<resource>.openai.azure.com/openai/v1"
             :api-key  (System/getenv "AZURE_OPENAI_API_KEY")})
```

- `https://<resource>.services.ai.azure.com/openai/v1` is accepted too. A
  trailing slash is fine either way.
- `"model"` is the **deployment name**.
- `OPENAI_BASE_URL` + `OPENAI_API_KEY` work instead of the options, matching
  Microsoft's env-var example.
- Runnable port: `examples/openai/azure.clj` (reads `AZURE_OPENAI_ENDPOINT`
  and `AZURE_OPENAI_API_KEY` or `AZURE_OPENAI_AUTH_TOKEN`).

This client always sends `Authorization: Bearer <:api-key>`. What Azure v1
accepts, per Microsoft:

| credential | Azure v1 | this client |
|---|---|---|
| API key as `Authorization: Bearer <key>` | Accepted. Microsoft's API-key examples for the OpenAI Python, JavaScript, Go and Java SDKs pass the Azure key as the SDK's `api_key`, which those SDKs send as `Authorization: Bearer`. The [v1 OpenAPI spec](https://github.com/Azure/azure-rest-api-specs/blob/a6943a926f76b3a2f90371b3466157eddf760e25/specification/ai/data-plane/OpenAI.v1/azure-v1-v1-generated.json) declares an API-key scheme in the `authorization` header next to `api-key`. The prose never states it separately. | **works** |
| API key as `api-key: <key>` header | Accepted (Microsoft's REST example). | not sent. The client has no custom-header option, and the Bearer form above makes it unnecessary. |
| Microsoft Entra ID access token as `Authorization: Bearer <token>` | Accepted. Scope `https://ai.azure.com/.default`, role `Cognitive Services OpenAI User`. | **works**: pass a token-provider fn as `:api-key` (the SDK's `api_key=get_bearer_token_provider(...)`), called before every attempt, see below. A static token string also works but is never refreshed. |

Microsoft Entra ID with a refreshing token, the SDK's
`OpenAI(base_url=..., api_key=get_bearer_token_provider(DefaultAzureCredential(), "https://ai.azure.com/.default"))`:

```clojure
;; get-entra-token: your zero-arg fn returning a current access token String
;; for scope https://ai.azure.com/.default (e.g. azure-identity's
;; DefaultAzureCredential via Java interop). Put any caching in it: the client
;; calls it before every attempt, exactly like the SDK's callable api_key.
(oai/client {:base-url "https://<resource>.openai.azure.com/openai/v1"
             :api-key  get-entra-token})
```

In Python the caching lives in the azure-identity credential behind
`get_bearer_token_provider`, not in the SDK; this library likewise adds no
cache around the fn. To have the library cache instead,
pass `:credential-source (tools.agents.token/token-cache {:fetch! ...})` with
`:expires-at`, which also gets the one 401 retry.

Not supported: the legacy `AzureOpenAI` client shape, i.e.
`{endpoint}/openai/deployments/{deployment}/...` routing, the required
`api-version` query parameter, and the `AZURE_OPENAI_*` / `OPENAI_API_VERSION`
env vars. Use the v1 API.

### Workload Identity Federation

`tools.agents.openai.credentials/workload-identity-source` ports
openai-python's `workload_identity=` (3.14.0 @d421d7ab, `auth/_workload.py`).
It returns a `tools.agents.token` source; pass it as `:credential-source`:

```clojure
(require '[tools.agents.openai.credentials :as creds])

(oai/client
  {:credential-source
   (creds/workload-identity-source
     {:identity-provider-id "idp_..."            ; identity_provider_id
      :service-account-id   "sa_..."             ; service_account_id
      :provider (creds/k8s-service-account-token-provider)})})
;; other providers: (creds/gcp-id-token-provider {:audience ...}),
;; (creds/azure-managed-identity-token-provider {:resource ... :client-id ...}),
;; or your own {:token-type :jwt|:id :get-token (fn [] subject-token)}
```

| behaviour | openai-python | this library |
|---|---|---|
| config | `workload_identity={identity_provider_id, service_account_id, provider: {token_type, get_token}, refresh_buffer_seconds?}` (`_workload.py:26-43`), mutually exclusive with `api_key` (`_client.py:235-236`) | same keys, kebab-case; with `:api-key` → `invalid-credentials` |
| env vars | none | none |
| exchange | `POST https://auth.openai.com/oauth/token` (fixed, not `base_url`), JSON `{grant_type: urn:ietf:params:oauth:grant-type:token-exchange, subject_token, subject_token_type, identity_provider_id, service_account_id}`, 10 s timeout, no redirects (`_workload.py:355-380`) | same; `:token-exchange-url` overrides the URL (the SDK's `WorkloadIdentityAuth(token_exchange_url=)`) |
| `subject_token_type` | `jwt` → `urn:ietf:params:oauth:token-type:jwt`, `id` → `…:id_token` (`_workload.py:20-23`) | same (`:jwt`/`:id` or the strings) |
| response | 400/401/403 → `OAuthError` (message = `error_description`); other non-2xx → `OpenAIError`; 2xx needs a non-empty string `access_token` and numeric `expires_in` (`_workload.py:283-310`) | `:tools.agents.openai/oauth-error` `{:status :error}`; `:tools.agents.openai/token-exchange-error` `{:status}`. Neither the subject token, the access token nor the body is ever in a message or `ex-data` |
| cache / refresh | single-flight; refresh at `expires_in - min(buffer, expires_in/2)`, buffer 1200 s (`_workload.py:227-254,325-328`) | `token-cache` with `:refresh-skew-ms` = buffer, same formula, single-flight |
| API 401 | invalidate the token sent on every 401, retry once (`_client.py:579-589`; invalidation at `:582` precedes the `retried` check) | same: `request!` invalidates on every 401, including a second consecutive one, so the next call exchanges again; retries once outside `:max-retries` |
| exchange retries | runs inside the API request's retry loop: a non-`OpenAIError` (transport failure, timeout, raw provider exception) is retried with the client's `max_retries`, then `APIConnectionError`; `OpenAIError`s are not retried (`_base_client.py:1076-1111`) | the source retries the same failures with its own `:max-retries` (default 2), then `:tools.agents.openai/api-connection-error`; typed errors are not retried |
| providers | `k8s_service_account_token_provider` (file, default `/var/run/secrets/kubernetes.io/serviceaccount/token`), `azure_managed_identity_token_provider` (IMDS), `gcp_id_token_provider` (metadata server) (`_workload.py:78-204`) | `k8s-service-account-token-provider`, `azure-managed-identity-token-provider`, `gcp-id-token-provider`; failures → `:tools.agents.openai/subject-token-provider-error`. `:url` override instead of `http_client` |

Divergences:
- Options are validated at construction (`invalid-credentials`); the SDK
  finds an unsupported `token_type` only at exchange time (`_workload.py:359-362`).
- Wall clock (`System/currentTimeMillis`, or `:now-ms`) instead of
  `time.monotonic()` (`_workload.py:271-278`).
- The exchange's retry budget is the source's `:max-retries`, not the API
  call's: the client asks for its token before its own retry loop. A client
  with `:max-retries 0` still retries a failed exchange unless the source
  also gets `:max-retries 0`; in the SDK that is one knob.
- X.509 (mTLS) workload identity (`auth/_x509.py`) is not ported.

Not verified against the live `auth.openai.com`; tests use a local mock.

**Not verified against a live Azure resource.** Coverage is the mock-server
test for `examples/openai/azure.clj` (path `/openai/v1/responses`, Bearer
header, no query string) plus Microsoft's documentation.

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
| *(no SDK equivalent)* | `:json` client opt | A `{:read :write}` codec used by `request!` (every resource namespace and stream), `batches-results` and `webhooks/unwrap` given `:client`; its exceptions become `:json-parse-error`/`:json-encode-error` with the cause kept. `batch-input-jsonl` (no client) and workload-identity exchanges stay built-in. See README, Bring your own HTTP client / JSON codec. |
| `OpenAI(http_client=httpx.Client(...))` | `:http` client opt | A request fn with `tools.agents.http/request!`'s contract; `request!` sends every attempt through it, so all resource namespaces, openai.agents and streaming use it. `workload-identity-source` and the Azure/GCP metadata providers take their own `:http`. See README, Bring your own HTTP client. |
| `timeout` (default 10 min) / `APITimeoutError` | *(not implemented)* | Each runtime's HTTP leaf uses its own default timeout; a timeout surfaces as `:tools.agents.openai/api-connection-error` (which is also where Python's `APITimeoutError` sits in the hierarchy, as a subclass of `APIConnectionError`) and is retried like any other transport failure, exactly as the SDK does. |
| `client.responses.create(..., stream=True)` | `(responses-stream client params)` | Single-use reducible of decoded event maps, opened (and retried) at call time through `request!`. `:stream true` on `responses-create` is still rejected. See Streaming below. |
| `ResponseStreamState.accumulate_event` / `stream.get_final_response()` | `(accumulate-response-event acc event)` / `(accumulate-response-stream s)` + `(stream-complete? r)` | Final Response from `response.completed`/`failed`/`incomplete`; assembled from deltas when the stream is cut off. `output-text` works on both. |
| `client.chat.completions.create(..., stream=True)` | `(chat-completions-stream client params)` | Single-use reducible of decoded `chat.completion.chunk` maps until `data: [DONE]`; same opening, retries and errors as `responses-stream`. `:stream true` on `chat-completions-create` is still rejected. |
| `ChatCompletionStreamState.handle_chunk` / `get_final_completion()` | `(accumulate-chat-completion-chunk acc chunk)` / `(accumulate-chat-completion-stream s)` + `(stream-complete? r)` | ChatCompletion-shaped map (`"object" "chat.completion"`): choices merged by index, `delta` merged by `accumulate_delta` (content/refusal concatenated, `tool_calls` by index with `function.arguments` concatenated), `finish_reason`, `logprobs` arrays, `usage` from the `include_usage` chunk. `completion-text` works on it. Complete means the stream hit `[DONE]`, visible to `stream-complete?` only when the stream itself is passed; for a wrapped stream check `(stream/outcome s)`. |
| `client.responses.stream(...)` / `client.chat.completions.stream(...)` helper events (`snapshot`, `parsed`, `response_format`/`text_format`) | *(not implemented)* | Raw events plus the accumulators only; no structured-output parsing. |
| `client.embeddings.create(**params)` | `(tools.agents.openai.embeddings/embeddings-create client params)` | `POST /embeddings` (`resources/embeddings.py`). Omitted `encoding_format` → sent as `"base64"` and each string `data[].embedding` decoded as little-endian float32 into doubles (`lib/_parsing/_embeddings.py`); an explicit `"float"`/`"base64"` is returned untouched. Empty `data` with the implicit format → `:tools.agents.openai/invalid-response`. Helper: `decode-embedding-base64`. |
| `client.webhooks.verify_signature(payload, headers, secret=, tolerance=300)` / `client.webhooks.unwrap(payload, headers, secret=)` | `(tools.agents.openai.webhooks/verify-signature payload headers {:secret :tolerance :now-s})` / `(tools.agents.openai.webhooks/unwrap payload headers opts)` | Pure, no HTTP (`lib/_webhooks.py`). Standard Webhooks HMAC-SHA256 over `{webhook-id}.{webhook-timestamp}.{body}`; `payload` must be the raw body (String or `byte[]`). Two-sided 300 s window, `whsec_` secrets base64-decoded (others used as raw bytes), space-separated `v1,<b64>` or bare signatures, constant-time compare. Secret: `:secret` → `(:webhook-secret client)` (pass `{:client c}` in opts; `client` resolves `:webhook-secret` → `OPENAI_WEBHOOK_SECRET`, as `_client.py` does for `webhook_secret`) → `OPENAI_WEBHOOK_SECRET`. `unwrap` returns the event parsed by `read-json`. |
| `client.realtime.client_secrets.create(**params)` | `(tools.agents.openai.realtime/realtime-client-secrets-create client params)` | `POST /realtime/client_secrets` (`resources/realtime/client_secrets.py`); `expires_after` / `session` pass through verbatim. |
| `client.files.create(file=, purpose=, expires_after=)` | `(tools.agents.openai.files/files-create client {"file" f "purpose" p "expires_after" {...}})` | `POST /files` multipart (`resources/files.py`, `types/file_create_params.py`). Fields go first, bracket-flattened as `_serialize_multipartform` does (`expires_after[anchor]`, `expires_after[seconds]`), then the `file` part. `file` is a `java.io.File` / `java.nio.file.Path` (streamed from disk, Content-Length kept, filename = the file's name) or `{:content byte[]\|File\|Path :filename :content-type}`. No InputStream: a retry would resend an already-drained stream. `purpose` (`assistants`, `batch`, `fine-tune`, `vision`, `user_data`, `evals`; `types/file_purpose.py`) is sent verbatim, not validated. **Deviations:** the part Content-Type defaults to `application/octet-stream` (httpx guesses it from the filename; the API infers the type from the filename anyway); a bare `byte[]` (httpx would name it `upload`) or a String is rejected with `:tools.agents.openai/invalid-request`. |
| `client.files.list(after=, limit=, order=, purpose=)` / `.retrieve(file_id)` / `.delete(file_id)` | `(files-list client params)` / `(files-retrieve client id)` / `(files-delete client id)` | `GET /files` with `:query` (`types/file_list_params.py`) returns one cursor page (`"data"`, `"has_more"`, `"last_id"`; pass `"after"` for the next page). `GET`/`DELETE /files/{id}` with the id path-encoded. An empty id throws `:tools.agents.openai/invalid-request` before I/O (the SDK's `ValueError`). |
| `client.files.content(file_id)` | `(files-content client id)` → `byte[]` | `GET /files/{id}/content` with `Accept: application/binary` and `:as :bytes`; byte-exact. The deprecated `retrieve_content` (the same GET decoded as `str`, `resources/files.py:331`) is not ported. |
| `client.files.wait_for_processing(id, poll_interval=5.0, max_wait_seconds=1800)` | `(files-wait-for-processing client id {:poll-interval-ms :max-wait-ms :sleep-fn :now-fn})` | `lib/_files.py`: polls `retrieve` until `status` is `processed`, `error` or `deleted`. The SDK's `RuntimeError` on timeout is `:tools.agents.openai/wait-timeout`. |
| `client.images.generate(prompt=, model=, n=, size=, quality=, ...)` | `(tools.agents.openai.images/images-generate client request)` | `POST /images/generations` JSON, request sent verbatim (`resources/images.py` `generate`, `types/image_generate_params.py`). A missing `prompt` throws `:tools.agents.openai/invalid-request` before I/O (the SDK's `required_args`). |
| `client.images.edit(image=, prompt=, mask=, ...)` | `(images-edit client {"image" f-or-[f ...] "prompt" p "mask" m ...})` | `POST /images/edits` multipart. Scalar fields first, bracket-flattened (`_serialize_multipartform`), then image parts, then `mask`, per `extract_files(paths=[["image"], ["image", "<array>"], ["mask"]])`: a single file is part `image`, a sequence is one `image[]` part per entry (brackets `_array_suffix`). File forms as in `files-create`. The part Content-Type is `:content-type`, else guessed from the filename (png, jpg/jpeg, webp, gif), as httpx does (`mimetypes.guess_type`). **Deviation from `files-create`**, which defaults to `application/octet-stream`: GPT image edits are reported to fail with `unsupported mimetype ('application/octet-stream')` (not verified against the live API here). `edit-parts` returns the parts without I/O. |
| `client.images.create_variation(image=, model=, n=, size=, response_format=)` | `(images-create-variation client {"image" f ...})` | `POST /images/variations` multipart with one `image` part. **`dall-e-2` only** (`resources/images.py`); `model` is sent verbatim, not validated. A sequence of images throws `:tools.agents.openai/invalid-request`. |
| `stream=True` on `images.generate` / `images.edit` (`ImageGenStreamEvent` / `ImageEditStreamEvent`) | *(not implemented)* | `"stream" true` throws `:tools.agents.openai/streaming-unsupported` before I/O, on both the JSON and the multipart path. |
| `ImagesResponse.data[i].b64_json` (base64 decode by caller) | `(image-bytes item)` → `byte[]` | Not an SDK method. Standard Base64 decode of one `data` item; `nil` for a `url` item (dall-e `response_format` `url`, the dall-e default), which the caller fetches. All images: `(keep image-bytes (get resp "data"))`. |
| `client.uploads.*` (`/uploads`, multi-part, up to 8 GB) | *(not implemented)* | Out of scope: `/files` takes up to 512 MB in one request. The SDK's `upload_file_chunked` uses 64 MB parts (`resources/uploads/uploads.py`). |
| `client.batches.create(completion_window=, endpoint=, input_file_id=, metadata=, output_expires_after=)` | `(tools.agents.openai.batches/batches-create client {"input_file_id" id "endpoint" e "completion_window" "24h" ...})` | `POST /batches` JSON body, verbatim (`resources/batches.py`, `types/batch_create_params.py`); `output_expires_after` is a nested JSON object. `endpoint` is one of 8 values (`batches/endpoints`: `/v1/responses`, `/v1/chat/completions`, `/v1/embeddings`, `/v1/completions`, `/v1/moderations`, `/v1/images/generations`, `/v1/images/edits`, `/v1/videos`), sent verbatim, not validated. |
| `client.batches.retrieve(batch_id)` / `.list(after=, limit=)` / `.cancel(batch_id)` | `(batches-retrieve client id)` / `(batches-list client params)` / `(batches-cancel client id)` | `GET /batches/{id}`, `GET /batches` (cursor page; no `order`), `POST /batches/{id}/cancel`. Ids are encoded as the SDK's `path_template` (`tools.agents.http/encode-path-segment`); an empty id throws `:tools.agents.openai/invalid-request` before I/O. The SDK has no batch wait helper, so none is ported. |
| *(no SDK equivalent)* | `(batch-input-jsonl url requests)` / `(batches-results client batch {:errors? b})` | Helpers. `batch-input-jsonl` builds the input JSONL (unique non-empty `custom_id` enforced). `batches-results` takes a `Batch` map or id, downloads `output_file_id` (plus `error_file_id` with `:errors?`) via `files-content`, decodes with `tools.agents.openai/read-jsonl` and returns `{custom_id line}` — the output file is **not** in input order. No file id to read → `:invalid-request`; a malformed line → `:json-parse-error` (`:line`); a line without `custom_id` or a repeated one → `:invalid-response`. |
| `client.fine_tuning.jobs.create(model=, training_file=, method=, ...)` | `(tools.agents.openai.fine-tuning/jobs-create client {"model" m "training_file" id ...})` | `POST /fine_tuning/jobs` JSON body, verbatim (`resources/fine_tuning/jobs/jobs.py`); `method`, `hyperparameters` (deprecated), `integrations`, `metadata` are nested JSON, not bracket-flattened. |
| `client.fine_tuning.jobs.retrieve(id)` / `.list(after=, limit=, metadata=)` / `.cancel(id)` / `.pause(id)` / `.resume(id)` / `.list_events(id, after=, limit=)` | `(jobs-retrieve client id)` / `(jobs-list client params)` / `(jobs-cancel client id)` / `(jobs-pause client id)` / `(jobs-resume client id)` / `(jobs-list-events client id params)` | `GET /fine_tuning/jobs/{id}`, `GET /fine_tuning/jobs` (`metadata` filter bracket-encoded as `metadata[k]=v`; the SDK's `metadata=None` → pass `{"metadata" "null"}`, since nil params are dropped), `POST .../cancel`, `.../pause`, `.../resume`, `GET .../events`. Empty id → `:invalid-request` before I/O. |
| `client.fine_tuning.jobs.checkpoints.list(id, after=, limit=)` | `(jobs-checkpoints-list client id params)` | `GET /fine_tuning/jobs/{id}/checkpoints` (`jobs/checkpoints.py`). |
| `client.fine_tuning.checkpoints.permissions.create(ckpt, project_ids=)` / `.list(ckpt, after=, limit=, order=, project_id=)` / `.delete(permission_id, fine_tuned_model_checkpoint=)` | `(checkpoints-permissions-create client ckpt {"project_ids" [...]})` / `(checkpoints-permissions-list client ckpt params)` / `(checkpoints-permissions-delete client ckpt permission-id)` | `POST`/`GET /fine_tuning/checkpoints/{ckpt}/permissions`, `DELETE .../permissions/{id}` (`checkpoints/permissions.py`). Require an **admin API key**: build the client with it as `:api-key`. The checkpoint keeps its `:`s in the path, as the SDK's `path_template`. `delete` takes arguments in URL order (checkpoint first). The deprecated `permissions.retrieve` sends the same GET as `list` and is not ported separately. |
| `client.fine_tuning.alpha.graders.run` / `.validate` | *(not implemented)* | Alpha (`POST /fine_tuning/alpha/graders/run\|validate`, `resources/fine_tuning/alpha/graders.py`); out of scope. |
| Assistants / Realtime `connect` + `calls.*` | *(not implemented)* | Not yet ported. `request!` is the shared transport (any method, `:query`, JSON or multipart body, `:as :json`/`:string`/`:bytes`), so a new resource method is a single call. See Shared transport below. |
| `OpenAI(workload_identity={...})`, `k8s_service_account_token_provider` / `azure_managed_identity_token_provider` / `gcp_id_token_provider` | `(client {:credential-source (tools.agents.openai.credentials/workload-identity-source {...})})`, `k8s-service-account-token-provider` / `azure-managed-identity-token-provider` / `gcp-id-token-provider` | Token exchange at `auth.openai.com/oauth/token`, cached with the 1200 s refresh buffer, 401 → re-exchange once. No env vars (the SDK has none). X.509 workload identity not ported. See Workload Identity Federation above. |
| `admin_api_key` / `OPENAI_ADMIN_KEY` | *(not implemented)* | Dropped: the SDK sends it only on `resources/admin/organization/*` endpoints, none of which are ported. The checkpoint-permission endpoints use the normal bearer (`security={"bearer_auth": True}`), so pass an admin key as `:api-key`. |
| Azure OpenAI v1: `OpenAI(base_url="https://<resource>.openai.azure.com/openai/v1/", api_key=...)` | `(client {:base-url "https://<resource>.openai.azure.com/openai/v1" :api-key ...})` | Works through `:base-url`; no Azure-specific code. API key, static Entra ID token or an Entra token-provider fn (callable `:api-key`), all as `Authorization: Bearer`. Not verified against a live Azure resource. See Azure OpenAI (v1 API) above. |
| `AzureOpenAI(azure_endpoint=, azure_deployment=, api_version=)` (legacy) | *(not supported)* | Deployment path rewriting, the required `api-version` query and `AZURE_OPENAI_*` / `OPENAI_API_VERSION` env vars are not ported. Use the v1 API. |

### Credential resolution & headers (confirmed from openai-python source)

Precedence, first match wins: explicit `:api-key` (a String or a zero-arg
fn) → `OPENAI_API_KEY` env var → throw. An explicit `:credential-source` (a `tools.agents.token/TokenSource`)
replaces this chain: its token is fetched before every attempt and sent as
`Authorization: Bearer`; combining it with `:api-key` throws
`invalid-credentials`. Workload identity is such a source
(`tools.agents.openai.credentials/workload-identity-source`); nothing is
discovered from the environment. See README, Refreshable credentials.

Callable `:api-key` (openai-python 3.14.0 `api_key: str | Callable[[], str]`,
`_client.py:164,253-255`):

| behaviour | openai-python | this client |
|---|---|---|
| when called | before every attempt, retries included: `_prepare_options` → `_refresh_api_key` (`_client.py:671-672,688-690`) at the top of each retry-loop iteration (`_base_client.py:1052-1054`) | same: wrapped as a `tools.agents.token/TokenSource` stored as `:credential-source`, asked for a token per attempt by `request!`, streaming opens (`:as :stream`) included |
| at construction | not called; its presence satisfies the missing-credentials check (`_client.py:266-274`) | not called; beats `OPENAI_API_KEY` |
| caching | none; `self.api_key` overwritten on each call | none; cache inside the fn if needed |
| 401 | not retried: `_send_with_auth_retry` returns unless workload identity is set (`_client.py:578-579`); `_should_retry` has no 401 branch (`_base_client.py:815`) | not retried: the source's `invalidate!` returns false → `authentication-error` |
| fn throws | propagates, not retried (raised before the send's `try`) | propagates as-is, not retried |
| empty / `None` return | no `Authorization` header → `TypeError` "Could not resolve authentication method" (`_client.py:620-623,655-665`) | **divergence:** `:tools.agents.openai/invalid-api-key`, before any I/O |
| non-string return | f-string formatted into the header | **divergence:** `invalid-api-key`; blank strings too. `ex-data` is exactly `{:type ...}`, never the value |
| with `:credential-source` | n/a | `invalid-credentials` |

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
| `:credential-source` not a `TokenSource`, or combined with `:api-key`; invalid `workload-identity-source` options | `:tools.agents.openai/invalid-credentials` |
| workload identity token exchange: HTTP 400/401/403 (`OAuthError`; `ex-data` `{:type :status :error}`) | `:tools.agents.openai/oauth-error` |
| workload identity token exchange: other non-2xx, malformed 2xx body, empty subject token, `expires_in` ≤ 0 (`ex-data` `{:type :status}`, never a token) | `:tools.agents.openai/token-exchange-error` |
| built-in subject token provider failed (k8s file, Azure IMDS, GCP metadata; `SubjectTokenProviderError`) | `:tools.agents.openai/subject-token-provider-error` |
| a callable `:api-key` returned a non-string or blank value (value never included) | `:tools.agents.openai/invalid-api-key` |
| `:http` is not a fn, or `:json` not a `{:read :write}` codec map (client construction; `:option` in `ex-data`) | `:tools.agents.openai/invalid-options` |
| `:stream true` requested from a non-streaming function | `:tools.agents.openai/streaming-unsupported` |
| while reducing a stream: an `error` event, or an event with a top-level `"error"` object (`:error` in ex-data, `:body` the raw data, `:status` nil) | `:tools.agents.openai/stream-error` |
| while reducing a stream: the connection fails mid-body | `:tools.agents.openai/api-connection-error` |
| request rejected before any I/O (bad `:as`; files/images: missing/unsupported file or required field; an empty file, batch, fine-tuning job, checkpoint or permission id — the SDK's `ValueError`; batches: bad `custom_id` in `batch-input-jsonl`, no result file id in `batches-results`) | `:tools.agents.openai/invalid-request` |
| `files-wait-for-processing` gave up after `:max-wait-ms` (the SDK's `RuntimeError`; not an HTTP timeout; never retried) | `:tools.agents.openai/wait-timeout` |
| response has no `"output"` / `"choices"` array, or an empty `"choices"`; a batch result line without a string `custom_id`, or a repeated one | `:tools.agents.openai/invalid-response` |
| structurally wrong content (non-array `"content"`, non-string `"text"`, non-string non-null `"content"`) | `:tools.agents.openai/invalid-content-shape` |
| webhook: bad timestamp format, timestamp outside tolerance, or no matching signature (`InvalidWebhookSignatureError`) | `:tools.agents.openai/invalid-webhook-signature-error` |
| webhook: `webhook-id` / `webhook-timestamp` / `webhook-signature` header absent (`:header` in ex-data) | `:tools.agents.openai/missing-webhook-header` |
| webhook: no `:secret`, no client `:webhook-secret` and no `OPENAI_WEBHOOK_SECRET` | `:tools.agents.openai/missing-webhook-secret` |
| webhook: `whsec_` secret is not valid base64 | `:tools.agents.openai/invalid-webhook-secret` |

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
- **401.** Retried once, immediately and outside `:max-retries`, only for a
  `:credential-source` client after invalidating the token it sent. A static
  or callable `:api-key` 401 is never retried (as in the SDK).
- **What is not retried.** 4xx other than 408/409/429; a malformed, non-empty
  JSON body on an otherwise-successful 2xx (an empty one decodes to `nil`) (the SDK decodes after its retry loop has
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
  see [divergences.md](divergences.md). Consequence worth knowing if you write specs: a
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

### Divergences from the sibling clients

See [divergences.md](divergences.md) for the per-contract table across all four clients.

### Streaming

```clojure
(let [s (oai/responses-stream client {"model" "gpt-6-astra" "input" "Tell me a story"})]
  (oai/accumulate-response-stream
    (eduction (map (fn [e]
                     (when (= "response.output_text.delta" (get e "type"))
                       (print (get e "delta")) (flush))
                     e))
              s)))
;; => the final Response; (oai/output-text r), (oai/stream-complete? r)
```

```clojure
(let [s (oai/chat-completions-stream client {"model" "gpt-6-astra" "messages" msgs
                                             "stream_options" {"include_usage" true}})
      r (oai/accumulate-chat-completion-stream s)]   ; pass the stream itself
  [(oai/completion-text r) (get r "usage") (oai/stream-complete? r)])
```

Chat completeness is `data: [DONE]`, a transport fact the chunks never carry:
`stream-complete?` sees it only when `accumulate-chat-completion-stream` gets
the stream itself. If you wrap the stream (an `eduction` printing deltas, a
`take`), check `(= :done (tools.agents.stream/outcome s))` instead.

`responses-stream` / `chat-completions-stream` POST the `responses-create` /
`chat-completions-create` map with `"stream" true` (a caller's `:stream` key
is replaced). Sources: the streaming guide
(`developers.openai.com/api/docs/guides/streaming-responses`), the Responses
and Chat Completions streaming-events references, and openai-python @
`d421d7a` (`src/openai/_streaming.py`, `lib/streaming/responses/_responses.py`,
`lib/streaming/chat/_completions.py`, `lib/streaming/_deltas.py`).

- **Opening.** The request goes out when the function is called, through
  `request!` with `:as :stream` as `tools.agents.stream/open-event-stream`'s
  `:send!`: per-attempt headers, the retry policy, the `:credential-source`
  401 retry and `status->type` typing are `request!`'s. A final non-2xx or
  connection failure throws from the call. Nothing is retried after the
  first byte, so a retry never replays events.
- **Events.** A single-use reducible of decoded event maps (string keys,
  dispatch on `"type"`; all ~60 types pass through untouched), each carrying
  its SSE frame's name and id as metadata `:tools.agents.sse/event` /
  `:tools.agents.sse/id`. Blank `data` frames are skipped. A `data` starting
  with `[DONE]` ends the stream without decoding (the SDK's rule for every
  stream).
- **Errors while reducing.** An `error` event (`{"type" "error" "code"
  "message" "param"}`) and any event with a truthy top-level `"error"`
  (the SDK's `APIError` rule) throw `:tools.agents.openai/stream-error`. The
  SDK raises only for the second shape and yields the `error` event as a
  `ResponseErrorEvent`; this client throws both. A mid-stream transport
  failure is `api-connection-error`.
- **Lifecycle.** Reducing closes the body (EOF, `(take n)`, exception); a
  second reduce throws `:tools.agents.stream/consumed`. An unreduced stream
  is released with `(tools.agents.stream/close! s)`, also the cross-thread
  cancel. No resume: a dropped stream is not reopened.
- **Responses accumulator.** `accumulate-response-event` is a pure reducing fn
  (`[]`, `[acc]`, `[acc event]`) ported from `ResponseStreamState`. The
  result is the `response` of the terminal event (`response.completed`,
  `response.failed`, `response.incomplete`); a failed or incomplete
  response is returned, not thrown, as `responses-create` returns it. If
  that `response` has a null `output`, the `response.output_item.done`
  items are used, as the SDK does. Without a terminal event the result is
  assembled from `response.created`/`in_progress`/`queued`,
  `output_item.added/done` (by `output_index`), `content_part.added/done`
  (by `content_index`), `output_text.delta`, `refusal.delta` and
  `function_call_arguments.delta`, with each `*.done` replacing the
  accumulated value. `output` and message `content` are vectors, so
  `output-text` reads partial results too.
- **Chat Completions accumulator.** `accumulate-chat-completion-chunk` ports
  `ChatCompletionStreamState._accumulate_chunk`: the first chunk seeds the
  completion (minus `obfuscation`, `"object" "chat.completion"`); each
  choice's `delta` merges into `message` by `accumulate_delta` (strings
  concatenate, `index`/`type` replace, indexed lists such as `tool_calls`
  merge entry by `index`); a truthy `finish_reason` replaces; `logprobs`
  `content`/`refusal` arrays append; `usage` and `system_fingerprint` follow
  the latest chunk, so the `include_usage` chunk (empty `choices`, sent last)
  sets `usage`. Chunks whose `object` is not `chat.completion.chunk` (Azure's
  async content filter) are skipped, as in the SDK.
- **Truncation.** A stream cut off at an event boundary reduces normally.
  Responses has no end marker on the wire besides its terminal event, so
  `(tools.agents.stream/outcome s)` is `:eof` for complete and cut-off
  Responses streams alike, and `stream-complete?` reads the metadata the
  terminal event sets. Chat ends with `data: [DONE]`, which `outcome` reports
  as `:done`; `accumulate-chat-completion-stream` copies that into the
  result's metadata only when handed the stream itself (a collection or an
  `eduction` over the stream is never complete; check `outcome` directly).
  Neither accumulator throws on truncation.
- **Deviations from openai-python.** A delta for an output item that was
  never added is ignored (the SDK raises `RuntimeError`); a text delta with
  no `content_part.added` creates an `output_text` part; no
  `text_format`/`parsed` structured-output parsing and no `snapshot` fields
  on delta events. Chat choices are matched by their `index` field and kept
  sorted (the SDK indexes the list positionally), and a choice first seen
  after the first chunk does not get its `logprobs` appended twice.

### Requiring `examples/` from the tests

`examples/*.clj` is requireable from
`test/tools/agents/openai/live_test.cljc` because the repo root (`.`) is on
every test alias's extra paths in `deps.edn`, and on `bb.edn`'s global
`:paths`. That resolves `examples.openai.basic-chat` →
`./examples/openai/basic_chat.clj`.

### JSON: a small hand-rolled codec, not a dependency

`write-json`/`read-json` in `tools/agents/openai.cljc` are thin wrappers over
the repo's shared hand-written codec, `tools.agents.json` (see the README's
"JSON: one shared hand-rolled codec" section). It decodes JSON objects into
maps with **string** keys. The wrappers throw this namespace's own
`:tools.agents.openai/json-encode-error` / `json-parse-error` with
`tools.agents.openai/write-json: ` / `tools.agents.openai/read-json: `
message prefixes.

### Isolating runtime-specific I/O

Every network request in this repo goes through one function,
`tools.agents.http/request!`. It performs a single HTTP exchange and returns
`{:status :headers :body}` for any response, 2xx or not; it throws only when
no response arrives (the transport exception propagates unwrapped). Retries,
status classification and body decoding stay in each client.

It is plain `java.net.http.HttpClient` interop (built into the JDK since 11,
zero added dependency) with no reader conditional: Babashka exposes the same
classes, so both runtimes send identical bytes. The request map:

| key | meaning |
|---|---|
| `:method`, `:url` | required; `:get`/`:post`/`:put`/`:patch`/`:delete` |
| `:query` | params map, bracket-encoded as openai-python does (`metadata[k]=v`, `image[]=a`) by `encode-params` |
| `:headers` | name → value map; a vector value sends the header once per element |
| `:body` | `nil`, String, `byte[]`, `File`, `Path` or `InputStream` |
| `:multipart` | `[{:name :content :filename :content-type}]` instead of `:body`; File/Path parts stream from disk and the body keeps a Content-Length; names are sent verbatim, so `image[]` twice is two parts |
| `:as` | `:string` (default), `:bytes` (raw `byte[]`), `:stream` (`InputStream`) |
| `:timeout-ms` | request timeout; none by default |
| `:client` | an `HttpClient`; defaults to one shared, lazily built client (`tools.agents.http/client` builds another, e.g. with `:connect-timeout-ms`) |

Response headers have lower-case names on both runtimes; a header sent once
is a String and a header sent more than once is a vector. With `:as :stream`
the caller owns the body and must close it on every path, non-2xx included,
e.g. with `with-open`.

Clients build request headers inside each retry attempt, so credentials that
change between attempts reach the retry; the request body is encoded once.
The shared function pins **HTTP/1.1** — see below.

### Shared transport: `request!`

Every OpenAI resource method, in this namespace and in
`tools.agents.openai.agents`, `.embeddings` and `.realtime`, goes through one
public function, so there is exactly one copy of the retry loop:

```clojure
(oai/request! client "files-list"
              {:method  :get            ;; default :post
               :path    "/files"        ;; appended to :base-url
               :query   {"limit" 20}    ;; bracket-encoded
               :body    {...}           ;; JSON value, encoded once; or
               ;; :multipart [{:name :content :filename :content-type}]
               :headers {"openai-beta" "agents=v1"}  ;; merged over defaults
               :as      :json})         ;; :json (default) | :string | :bytes | :stream
```

- Headers (`Authorization`, `OpenAI-Organization`/`OpenAI-Project`,
  `content-type: application/json` unless multipart, then `:headers`) are
  rebuilt before every attempt.
- `:as :json` decodes a 2xx body; an empty 2xx body returns `nil` (since #41
  for every method; before it, openai's own POST methods threw
  `json-parse-error` on an empty body, agents returned `nil`). `:string`
  returns the raw text and `:bytes` the raw `byte[]` (file and artifact
  content). Error bodies in `ex-data` are always Strings.
- Errors, retries and `:retries-taken` are exactly as described in Retries
  and the error table. The `fn-name` argument labels messages: a bare name gets
  the `tools.agents.openai/` prefix, a qualified name
  (`"tools.agents.openai.agents/sessions-create"`) is used verbatim.
- `:as :stream` returns the whole 2xx response `{:status :headers :body
  InputStream}` (caller closes the body) after the same retry loop; a non-2xx
  body is read to a String and closed before the retry/error decision. It is
  the `:send!` a streaming function hands to
  `tools.agents.stream/open-event-stream` (see
  `tools.agents.openai.agents/sessions-events-stream`).
- A `stream` true body field or multipart part throws `streaming-unsupported`
  before any I/O, except with `:as :stream`.
- `post-json!` stays public as `(request! client fn-name {:path path :body request})`.
- **401:** for a `:credential-source` client the loop invalidates the token
  the failed attempt sent and retries once outside `:max-retries`; a second
  401 invalidates again (`_client.py:582`) and throws. A static or callable `:api-key` 401 is never retried (a
  callable one is a source whose `invalidate!` declines).

Everything else — URL/header building, the JSON codec, credential resolution,
error typing, the whole retry policy, `output-text`/`completion-text`
extraction, the message-list helpers — is plain, portable `clojure.core`,
exercised identically by both test runners.

#### Streaming transport

`tools.agents.stream/open-event-stream` is the provider-agnostic layer every
streaming client function builds on. It sends one `:as :stream` request at
call time and returns a **single-use reducible** of SSE events
(`{:event :data :id :retry}`, parsed by the pure `tools.agents.sse`).

| option | meaning |
|---|---|
| `:request` | the `request!` map; `:as` is forced to `:stream` |
| `:open!` | `(fn [attempt]) -> response`: the client's own retry loop. `attempt` does one exchange and returns a 2xx response (unread `InputStream` body), or a non-2xx response whose body is already a String (read and closed), or throws the transport exception. Retries therefore only ever happen before the first byte is handed to a reducer |
| `:on-error` | called with the final non-2xx `{:status :headers :body String}`; should throw the client's typed error (otherwise `:tools.agents.stream/http-error` is thrown) |
| `:decode` | applied to each event's `:data` (e.g. `read-json`) |
| `:done?` | tested on the raw event before `:decode`; the matching event (e.g. chat's `data: [DONE]`) ends the stream and is not emitted |
| `:xform` | transducer over the decoded events |
| `:on-read-error` | maps an `IOException` from reading the body mid-stream to the client's connection error; exceptions from the reducing fn or `:decode` propagate unwrapped |

Lifecycle: the one `reduce` (or `transduce`/`into`/`run!`) closes the body
in `finally` — on EOF, `done?`, early termination such as `(take 1)`, and
exceptions; a second reduce throws `:tools.agents.stream/consumed`.
`(stream/close! s)` works from any thread: it unblocks a reduce parked in a
read, which then returns what it accumulated. On JVM the value is also
`java.io.Closeable` (`with-open`); Babashka's `reify` allows one Java
interface only, so `close!` is the portable call. There is no read-idle
timeout; a watchdog calling `close!` is the way to bound a stalled stream.

**Truncation is the caller's concern.** EOF without a terminal event
reduces exactly like a complete stream, and an unterminated final event is
dropped per WHATWG. `(stream/outcome s)` reports `:done` (`done?` matched),
`:eof`, `:reduced`, `:cancelled` or `:failed`; a client that has no `done?`
marker (Anthropic's `message_stop`, Responses' `response.completed`) must
check for its terminal event itself. `(stream/response s)` gives the 2xx
`{:status :headers}`.

### Why HTTP/1.1 is pinned

`java.net.http.HttpClient` defaults to `HTTP_2`, and for a **cleartext**
`http://` URL it sends the HTTP/2 connection preface without any h2c upgrade
negotiation. A plain HTTP/1.1 reverse proxy in front of an OpenAI-compatible
gateway answers that with a bare `502`, so a `:base-url` that works
everywhere else fails on JVM Clojure and Babashka, which both sit on
`java.net.http`. Observed against a live Caddy-fronted gateway, not
theorized.

`tools.agents.http` therefore pins HTTP/1.1 explicitly
(`HttpClient$Version/HTTP_1_1`) on its client and on every request.
Over `https://` ALPN would have negotiated safely either way, and this client
issues one request at a time, so HTTP/2 bought it nothing — pinning keeps
both runtimes on the same wire protocol.

## Testing

The shared JSON codec itself is covered by `test/tools/agents/json_test.cljc`
in the core suite (`-M:test-core` / `bb test-core`). `test/tools/agents/openai_test.cljc` is pure logic (the JSON
codec's error contract, credential
resolution, client construction, `output-text`, `completion-text`, the full
retry policy — `parse-retry-after-ms` including HTTP-dates and leap days,
`should-retry?`, the `retry-delay-ms` backoff curve and jitter bounds —
message helpers, `:stream true` rejection, both stream accumulators)
— zero I/O, zero network, zero sleeping (the clock and the RNG are
injected), identical on both runtimes.

`test/tools/agents/openai/stream_test.cljc` drives `responses-stream` and
`chat-completions-stream` against the streaming mock server on OS-assigned ports (bind 0, so it never
collides with the fixed bands below): request body and headers, incremental
delivery, the docs-derived `openai-responses-function-call.sse` fixture,
failed/incomplete terminal events, `error` event typing, HTTP errors and
retries before the stream, the credential-source 401 retry, truncation,
`[DONE]`, a mid-stream transport failure, early termination and `close!`;
for chat also `[DONE]` completeness, the docs-derived
`openai-chat-tool-calls-usage.sse` fixture (interleaved tool-call argument
deltas, `include_usage` final chunk) and an error chunk mid-stream.

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
wall-clock), and all four `examples/openai/*.clj` Responses/Chat ports run
end-to-end against the mock server.

The mock server is `tools.agents.test-support/start-server!`, shared by the provider and core suites — two tiny runtime branches that
also serve byte[] and streaming (chunked) response bodies. Babashka uses `org.httpkit.server`
(`com.sun.net.httpserver.HttpServer` is not resolvable under bb's native
image). JVM Clojure uses `com.sun.net.httpserver.HttpServer` (built into the
JDK, zero deps). Mock
ports are `18950`–`18964`, chosen not to collide with
tools.agents.anthropic's `18930`–`18946`, `18965`–`18971` for the retry
tests, `19391` for the Azure v1 example, and `19400`–`19405` for the
`request!` transport tests (GET + `:query` + extra headers, `:as :bytes` /
`:string`, empty 2xx body, multipart, streaming rejection, static-key 401 not
retried), `19350`–`19354` for `:credential-source` (401
invalidate-and-retry, token per attempt), OS-assigned ports for callable
`:api-key` (per-attempt calls, 401, bad return, streaming open) and for
`tools.agents.openai.credentials` (exchange shape, refresh buffer,
single-flight, 401 re-exchange, typed non-leaking failures, providers,
agents, responses/chat/agents streaming opens), `19280`–`19285` for the
`tools.agents.openai.files` tests (multipart wire format, File streamed from
disk, list query, retrieve/delete, 404 typing, binary content round-trip,
wait-for-processing), and `19300`–`19303` for the
`tools.agents.openai.images` tests (generate JSON body, edit/variation multipart
wire format, 4xx typing), and `19320`–`19325` for the
`tools.agents.openai.batches` tests (create body, retrieve/cancel, 404
typing, list paging, results shuffled and matched by `custom_id`, error file,
malformed and duplicate lines), and `19340`–`19344` for the
`tools.agents.openai.fine-tuning` tests (method/path/query/body of every
endpoint, checkpoint colons in paths, admin key as `:api-key`, 404 typing,
events paging). Port `18999` is additionally used by the three tests that deliberately
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
