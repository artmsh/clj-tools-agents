(ns tools.agents.anthropic
  "A pure-Clojure client for Anthropic's Messages API, ergonomically modeled on
   the official anthropic-sdk-python client (Anthropic(...) constructor,
   client.messages.create(**params) resource method, typed error hierarchy).

   Runs unmodified on JVM Clojure and Babashka. The 'client object' here is an
   `AnthropicClient` record built by `client`; it retains Clojure's map-style
   keyword lookup and immutable update semantics on both runtimes.

   PORTABILITY: all network I/O goes through `tools.agents.http/request!`,
   the shared HTTP request function, which needs no reader
   conditional. Everything else — URL/header building, the hand-rolled JSON codec,
   error typing, credential resolution, output-text extraction — is plain,
   portable clojure.core exercised identically by both test runners.

   JSON: there is no JSON library available on both runtimes without adding a
   dependency (Babashka bundles one, JVM Clojure does not), so
   `write-json`/`read-json` below wrap the small hand-written codec shared
   in tools.agents.json. It supports exactly what the Messages API needs:
   nil/bool/number/string/keyword/vector/seq/map, with map keys as either
   strings or keywords passed through VERBATIM (no kebab<->snake conversion
   — the same idiom the Python SDK uses with literal dict keys like
   \"max_tokens\").

   STREAMING: not implemented. :stream true is rejected with a clear error
   rather than silently ignored. See README's platform-limitations section."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]))

(def default-base-url "https://api.anthropic.com")
(def ^:private anthropic-version "2023-06-01")
(def ^:private oauth-beta-header "oauth-2025-04-20")

;; anthropic-sdk-python's and anthropic-sdk-typescript's shared default —
;; DEFAULT_MAX_RETRIES = 2 in both SDKs.
(def default-max-retries 2)

;; Only keys present on every resolved client are record fields. Credentials
;; are mutually exclusive and :betas is optional, so map->AnthropicClient keeps
;; them in the record's extension map and preserves the old `contains?` shape.
(defrecord AnthropicClient [base-url max-retries])

;; ---------------------------------------------------------------------------
;; JSON codec — tools.agents.json, bound to this namespace's error contract.
;; ---------------------------------------------------------------------------

(def ^:private json-codec
  (json/codec {:prefix      "tools.agents.anthropic"
               :encode-type :tools.agents.anthropic.error/json-encode
               :parse-type  :tools.agents.anthropic.error/json-parse}))

(defn json-key->str
  "Coerce a map key (string/keyword/symbol) to its wire string form, verbatim
   — no case conversion. Throws ex-info
   {:type :tools.agents.anthropic.error/json-encode} on anything else.
   Public so callers building JSON-shaped output on this library's wire
   contract (e.g. visualize.cljc's pretty-json) reuse the same coercion and
   error contract."
  [k]
  ((:key->str json-codec) k))

(defn write-json
  "Encode a Clojure value as a JSON string (tools.agents.json/write-json). Map
   keys may be strings or keywords, encoded verbatim; every character below
   0x20 is \\u00XX-escaped. Throws ex-info
   {:type :tools.agents.anthropic.error/json-encode} on an unsupported value."
  [v]
  ((:write json-codec) v))

(defn read-json
  "Decode a JSON string into a Clojure value (tools.agents.json/read-json):
   objects become maps with STRING keys, arrays become vectors, numbers stay
   numbers. Throws ex-info {:type :tools.agents.anthropic.error/json-parse} on
   malformed input — never returns a partial result."
  [s]
  ((:read json-codec) s))

;; ---------------------------------------------------------------------------
;; Process boundary — `getenv` is the env-var seam. Network I/O is
;; tools.agents.http/request!, called from post-json! below.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  (System/getenv name))

;; ---------------------------------------------------------------------------
;; Credentials / client construction
;; ---------------------------------------------------------------------------

(defn resolve-credentials
  "Pure credential-resolution logic, factored out for testability (mirrors
   the original Zig builtin's `resolveApiKeyValue` split — process env vars
   can't be reliably unset mid-test-run, so tests inject a fake getenv-fn
   instead of touching the real environment).

   Precedence: explicit :api-key > explicit :auth-token > ANTHROPIC_API_KEY
   env var > ANTHROPIC_AUTH_TOKEN env var > throw. Returns {:api-key s} or
   {:auth-token s}."
  [{:keys [api-key auth-token]} getenv-fn]
  (cond
    api-key {:api-key api-key}
    auth-token {:auth-token auth-token}
    :else
    (let [env-key   (getenv-fn "ANTHROPIC_API_KEY")
          env-token (getenv-fn "ANTHROPIC_AUTH_TOKEN")]
      (cond
        (seq env-key) {:api-key env-key}
        (seq env-token) {:auth-token env-token}
        :else
        (throw (ex-info (str "tools.agents.anthropic/client: no credentials found — pass :api-key or "
                              ":auth-token, or set the ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN env var")
                         {:type :tools.agents.anthropic.error/missing-credentials}))))))

(defn client
  "Build an AnthropicClient record — the 'client object' analogue of Python's
   Anthropic(...) constructor. Resolves credentials eagerly (fails fast with
   a catchable ex-info BEFORE any network request, matching the original
   builtin's contract) unless :api-key/:auth-token/env vars are present.

   opts:
     :api-key      explicit API key -> sent as `x-api-key`
     :auth-token   explicit bearer/OAuth token -> sent as `Authorization: Bearer`
                   (+ the required `anthropic-beta: oauth-2025-04-20` header)
     :base-url     override API host, default `https://api.anthropic.com`
                   (also resolved from ANTHROPIC_BASE_URL if unset)
     :betas        seq of beta-feature flag strings (e.g.
                   [\"advanced-tool-use-2025-11-20\"] for programmatic tool
                   calling), sent as a comma-joined `anthropic-beta` header —
                   analogous to the Python SDK's per-call `betas=[...]` kwarg
                   on `client.beta.messages.create`, but set once here on the
                   client record since this library has no separate
                   `.beta` resource namespace. Combined with, never replacing,
                   the OAuth path's own required `oauth-2025-04-20` flag when
                   :auth-token is in use — see auth-headers.
     :max-retries  automatic retry count for 408/409/429/5xx and connection
                   failures, default 2 (matches anthropic-sdk-python and
                   anthropic-sdk-typescript's shared DEFAULT_MAX_RETRIES).
                   0 disables retries — one attempt, same as before this
                   was added. Must be a non-negative integer — throws
                   {:type :tools.agents.anthropic.error/invalid-max-retries}
                   otherwise (a negative value used to be accepted silently
                   and disabled retries with no signal at all, since -1 is
                   truthy in Clojure's `or` and `(< attempt -1)` is always
                   false; spec.clj's own ::max-retries spec already
                   documented non-negative as the contract, this just
                   enforces it on the real, non-instrumented call path).
                   See messages-create's retry note."
  ([] (client {}))
  ([opts]
   (let [creds       (resolve-credentials opts getenv)
         max-retries (or (:max-retries opts) default-max-retries)]
     (when-not (and (integer? max-retries) (>= max-retries 0))
       (throw (ex-info (str "tools.agents.anthropic/client: :max-retries must be a non-negative "
                             "integer, got: " (pr-str max-retries))
                        {:type :tools.agents.anthropic.error/invalid-max-retries})))
     (map->AnthropicClient
      (cond-> (merge {:base-url (or (:base-url opts) (getenv "ANTHROPIC_BASE_URL") default-base-url)
                      :max-retries max-retries}
                     creds)
        (seq (:betas opts)) (assoc :betas (vec (:betas opts))))))))

;; ---------------------------------------------------------------------------
;; Error typing
;; ---------------------------------------------------------------------------

(defn- status->type [status]
  (cond
    (= status 400) :tools.agents.anthropic.error/bad-request
    (= status 401) :tools.agents.anthropic.error/authentication
    (= status 403) :tools.agents.anthropic.error/permission-denied
    (= status 404) :tools.agents.anthropic.error/not-found
    (= status 422) :tools.agents.anthropic.error/unprocessable-entity
    (= status 429) :tools.agents.anthropic.error/rate-limit
    (and status (>= status 500)) :tools.agents.anthropic.error/internal-server
    :else :tools.agents.anthropic.error/api-status))

(defn- extract-error-message
  "Best-effort extraction of Anthropic's {\"error\":{\"message\":\"...\"}}
   shape. Returns nil (never throws) on any parse failure."
  [body]
  (when (seq body)
    (try
      (let [parsed (read-json body)]
        (when (map? parsed)
          (let [err (get parsed "error")]
            (when (map? err)
              (let [msg (get err "message")]
                (when (string? msg) msg))))))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Retries — pure clojure.core, no runtime-specific leaf needed.
;; ---------------------------------------------------------------------------

(defn- retryable-status?
  "408 (request timeout), 409 (conflict — e.g. concurrent edit on a batch),
   429 (rate limit) and any 5xx — the same status set anthropic-sdk-python
   retries by default."
  [status]
  (or (contains? #{408 409} status)
      (= status 429)
      (and status (>= status 500))))

;; Sanity clamp for a server-supplied Retry-After value — same idea as
;; anthropic-sdk-python's own clamping of this header. Without it, a
;; negative value (malformed, hostile, or from a misbehaving gateway) sails
;; through to *sleep-fn*/Thread-sleep and crashes the whole retry loop with
;; an unrelated, untyped IllegalArgumentException ("timeout value is
;; negative") instead of either retrying or propagating the real
;; rate-limit/5xx error — verified empirically. An absurdly large-but-well-formed
;; value (a malicious/misconfigured gateway saying "retry-after: 86400")
;; would otherwise also be honored verbatim with no upper bound at all.
(def ^:private max-retry-after-seconds 60.0)

(defn- parse-retry-after
  "Anthropic sends `retry-after` as an integer/decimal seconds count (never
   the HTTP-date form) — parse it if present, nil otherwise (never throws).
   `Double/parseDouble`, NOT `read-string`: a response header is attacker-
   reachable data (compromised/malicious gateway, MITM'd plaintext hop —
   this library talks to arbitrary :base-urls, not just api.anthropic.com),
   and `read-string`'s default `*read-eval*` executes `#=(...)` forms at
   read time — confirmed empirically on both JVM Clojure and Babashka,
   `(read-string \"#=(println :x)\")` really does run it. `Double/parseDouble`
   only ever produces a number or throws, never evaluates anything.

   The same hostile-input threat model applies to the NUMBER itself, not
   just to code-injection via the parser: a negative value is clamped away
   (returns nil, same as unparseable) and a positive value is clamped to
   max-retry-after-seconds, so a malformed/adversarial header can only ever
   shorten the honored delay toward computed backoff, never crash the retry
   loop or force an unbounded sleep.

   A header value may also be a VECTOR of strings, not a bare string:
   tools.agents.http/request! returns a header's value as a vector
   whenever that header name appears more than once in the response, on
   both runtimes — `retry-after` sent twice yields
   [\"30\" \"60\"] — a real occurrence when a proxy/gateway/load-balancer in
   front of a custom :base-url duplicates or folds a singleton header,
   exactly the adversarial-gateway class this function already defends
   against for other reasons. `(str [\"30\" \"60\"])` is not a valid double
   and would previously fail parse + get silently swallowed, discarding the
   real Retry-After value in favor of the much shorter computed backoff —
   so a vector's first element is used instead of the whole collection."
  [headers]
  (when-let [v0 (or (get headers "retry-after") (get headers "Retry-After"))]
    (let [v (if (coll? v0) (first v0) v0)]
      (try
        (let [n (Double/parseDouble (str v))]
          ;; `(= n n)` rather than Double/isNaN: NaN is the only value not
          ;; equal to itself under IEEE 754.
          (when (and (= n n) (>= n 0))
            (min n max-retry-after-seconds)))
        (catch Exception _ nil)))))

(defn- backoff-seconds
  "Exponential backoff with jitter, capped at 8s — same shape as
   anthropic-sdk-python's default retry policy: jitter only ever SHORTENS
   the delay (`base * (1 - 0.25*rand())`, same direction as the real SDK's
   `sleep_seconds * (1 - 0.25 * random())`), so the returned value never
   exceeds the 8s cap — unlike an earlier version of this function, which
   added jitter ON TOP of the capped base and could return up to ~10s,
   contradicting this very docstring. attempt is 0-based (0 = the delay
   before the first retry). A Retry-After response header, when present,
   wins outright (already sanity-clamped to [0, max-retry-after-seconds] by
   parse-retry-after, so it can't itself blow past a sane ceiling either)."
  [attempt retry-after]
  (or retry-after
      (let [base (min (* 0.5 (Math/pow 2 attempt)) 8.0)]
        (* base (- 1.0 (* 0.25 (rand)))))))

;; Rebindable so tests can replace real sleeping with a no-op and assert on
;; retry *count* instead of eating multi-second delays. Left bound to a real
;; sleep in production — same pattern as resolve-credentials' injected
;; getenv-fn (a testability seam, not a runtime-specific leaf).
(def ^:dynamic *sleep-fn*
  (fn [seconds]
    (let [ms (long (* seconds 1000))]
      (when (pos? ms)
        (Thread/sleep ms)
        nil))))

(defn request-with-retries!
  "Calls (attempt-fn) up to (inc max-retries) times total. attempt-fn performs
   one full attempt and either returns the decoded response map or throws a
   typed ex-info (see status->type / post-json!). Retries only on
   :tools.agents.anthropic.error/api-connection and on retryable-status? HTTP
   statuses, honoring a Retry-After header (ex-data :headers) when present.
   Any other error — including the permanent refusals like
   :streaming-unsupported / :missing-credentials —
   propagates on the first attempt, consuming no retry and no sleep.

   Public (unlike this file's other private helpers) for the same reason
   resolve-credentials is: testability without a real mock server — pass a
   fake attempt-fn and bind *sleep-fn* to a no-op to unit-test retry counting
   and backoff/Retry-After selection with zero I/O. messages-create and
   count-tokens are both just attempt-fn callers around this."
  [max-retries attempt-fn]
  (loop [attempt 0]
    (let [outcome (try {:ok (attempt-fn)}
                        (catch Exception e
                          (let [data      (ex-data e)
                                status    (:status data)
                                type      (:type data)
                                retryable (or (= type :tools.agents.anthropic.error/api-connection)
                                              (retryable-status? status))]
                            (if (and retryable (< attempt max-retries))
                              {:retry e}
                              (throw e)))))]
      (if (contains? outcome :retry)
        (do (*sleep-fn* (backoff-seconds attempt (parse-retry-after (:headers (ex-data (:retry outcome))))))
            (recur (inc attempt)))
        (:ok outcome)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- api-url
  "path must start with \"/\", e.g. \"/v1/messages\"."
  [base-url path]
  (if (str/ends-with? base-url "/")
    (str base-url (subs path 1))
    (str base-url path)))

(defn- beta-header-value
  "Join required (a single flag string, or nil) with client's :betas (a seq
   of additional flag strings, or nil/absent) into one comma-separated
   `anthropic-beta` header value, or nil if there's nothing to send. Used so
   an opt-in feature flag like PTC's \"advanced-tool-use-2025-11-20\" COMBINES
   with the OAuth path's mandatory oauth-2025-04-20 flag instead of
   clobbering it — a naive last-write-wins merge here would silently drop
   oauth-2025-04-20 off an :auth-token client's header and break auth the
   moment :betas was also set."
  [required betas]
  (let [all (cond-> [] required (conj required) (seq betas) (into betas))]
    (when (seq all) (str/join "," all))))

(defn- auth-headers [client]
  (cond
    (:api-key client)
    (cond-> {"x-api-key" (:api-key client)
             "anthropic-version" anthropic-version
             "content-type" "application/json"}
      (seq (:betas client)) (assoc "anthropic-beta" (beta-header-value nil (:betas client))))

    (:auth-token client)
    {"authorization" (str "Bearer " (:auth-token client))
     "anthropic-version" anthropic-version
     "anthropic-beta" (beta-header-value oauth-beta-header (:betas client))
     "content-type" "application/json"}

    :else
    (throw (ex-info (str "tools.agents.anthropic: client has neither :api-key nor "
                          ":auth-token — build it via tools.agents.anthropic/client")
                     {:type :tools.agents.anthropic.error/missing-credentials}))))

(defn- post-json!
  "POST body-str to url with headers via tools.agents.http/request!,
   classifying a genuine transport failure (DNS/refused/TLS/timeout — no
   response at all) as :tools.agents.anthropic.error/api-connection.
   caller-name (e.g. \"tools.agents.anthropic/messages-create\") prefixes
   that error's message. An already-typed tools.agents.anthropic.error/*
   ex-info surfaces verbatim instead — those are permanent, not connection
   failures."
  [caller-name url headers body-str]
  (try
    (http/request! {:method :post :url url :headers headers :body body-str})
    (catch Exception e
      (let [data (ex-data e)]
        (if (and data (keyword? (:type data)) (= "tools.agents.anthropic.error" (namespace (:type data))))
          (throw e)
          (throw (ex-info (str caller-name ": connection failed: " (str e))
                           {:type :tools.agents.anthropic.error/api-connection :status nil :body nil})))))))

(defn- decode-or-throw!
  "Shared response handling for messages-create/count-tokens: 2xx decodes the
   body and attaches resp's :headers as metadata (see request-id below),
   anything else throws a typed ex-info with :status/:body/:headers in
   ex-data (:headers feeds request-with-retries!'s Retry-After handling)."
  [caller-name resp]
  (let [status    (:status resp)
        resp-body (:body resp)]
    (if (and status (>= status 200) (< status 300))
      (with-meta (read-json resp-body) {::headers (:headers resp)})
      (let [err-msg (extract-error-message resp-body)
            detail  (cond err-msg err-msg (seq resp-body) resp-body :else nil)]
        (throw (ex-info (str caller-name ": HTTP " status (when detail (str " " detail)))
                         {:type (status->type status) :status status :body resp-body :headers (:headers resp)}))))))

(defn- attempt-request!
  "One HTTP round trip: build headers, POST body-str to url, decode-or-throw!
   the response. Shared by messages-create/count-tokens.

   headers-fn is called at the start of EVERY attempt, so credentials that
   change between attempts (a refreshed token) land on the retry. url and
   body-str are computed ONCE by the caller before entering
   request-with-retries! — re-serializing the whole request via write-json
   on every retry would be wasted work identical across attempts."
  [caller-name url headers-fn body-str]
  (let [headers (headers-fn)]
    (decode-or-throw! caller-name (post-json! caller-name url headers body-str))))

(defn- post-request!
  "Shared body of every resource method: build the URL, encode the request,
   POST it under the client's retry policy with headers rebuilt per attempt.
   caller-name prefixes any error message; path is appended to the client's
   base-url."
  [client caller-name path request]
  (let [url        (api-url (:base-url client) path)
        headers-fn #(auth-headers client)
        body-str   (write-json request)]
    (request-with-retries! (or (:max-retries client) default-max-retries)
                           #(attempt-request! caller-name url headers-fn body-str))))

(defn messages-create
  "POST request (a plain map, passed through to JSON almost verbatim — model,
   max_tokens, messages, system, temperature, stop_sequences, thinking, tools,
   etc. pass straight through untouched, matching the original builtin's
   data-transparency contract) to POST /v1/messages against client's
   base-url. Returns the decoded response map (string keys), with the raw
   response headers attached as metadata — see `request-id` below for the
   Python SDK's `message._request_id` equivalent.

   :stream true throws immediately — see the ns docstring's STREAMING note.

   Retries client's :max-retries times (default 2, see `client`) on
   408/409/429/5xx and connection failures, with exponential backoff honoring
   a Retry-After header — see request-with-retries!. Permanent refusals
   (:streaming-unsupported, 4xx other than 408/409/429)
   are never retried.

   Throws ex-info on any failure, message prefixed
   \"tools.agents.anthropic/messages-create: \", ex-data
   {:type <keyword — see the error-hierarchy table in README> :status
   <http-status-or-nil> :body <raw-response-body-or-nil> :headers
   <response-headers-map-or-nil>}."
  [client request]
  (when (or (true? (get request :stream)) (true? (get request "stream")))
    (throw (ex-info (str "tools.agents.anthropic/messages-create: :stream true is not supported — "
                          "SSE streaming is not implemented by this client. See README.")
                     {:type :tools.agents.anthropic.error/streaming-unsupported})))
  (post-request! client "tools.agents.anthropic/messages-create" "/v1/messages" request))

(defn count-tokens
  "POST request (same shape as messages-create's request map — model,
   messages, system, tools, etc. — but without max_tokens, which
   /v1/messages/count_tokens doesn't take) to POST
   /v1/messages/count_tokens against client's base-url. Returns the decoded
   response map, e.g. {\"input_tokens\" 2470}.

   Same retry policy, error hierarchy and ex-data shape as messages-create."
  [client request]
  (post-request! client "tools.agents.anthropic/count-tokens" "/v1/messages/count_tokens" request))

(defn request-id
  "The `request-id` response header for a successful messages-create/
   count-tokens response — Python SDK equivalent: `message._request_id`.
   Returns nil if response carries no such metadata (e.g. a map built by
   hand rather than returned by this library, or a header genuinely absent
   from the response).

   The Python SDK exposes this via a hidden attribute on the response
   object; this library has no object to hang it off of — messages-create
   returns the decoded body as a plain map, verbatim, per its
   data-transparency contract (see README). So the header is carried as
   Clojure metadata on that same map instead, out of the way of equality,
   printing, and JSON re-encoding, rather than as a wire-format map key.
   Use this accessor rather than reading `(meta response)` directly, since
   the metadata key is a private implementation detail.

   For most logging/correlation purposes, prefer the response body's own
   \"id\" field (e.g. \"msg_...\") — it needs no accessor and, unlike this
   header, is also present on every retried request's final response."
  [response]
  (let [headers (::headers (meta response))]
    (or (get headers "request-id") (get headers "Request-Id"))))

(defn output-text
  "Concatenate the \"text\" fields of response's top-level \"content\" array
   items where \"type\" = \"text\". Throws (never returns nil) if none found."
  [response]
  (let [content (get response "content")]
    (when-not (vector? content)
      (throw (ex-info "tools.agents.anthropic/output-text: response has no \"content\" array"
                       {:type :tools.agents.anthropic.error/invalid-response :status nil :body nil})))
    (let [texts (keep (fn [block]
                         (when (and (map? block) (= (get block "type") "text") (contains? block "text"))
                           (let [text (get block "text")]
                             (when-not (string? text)
                               (throw (ex-info (str "tools.agents.anthropic/output-text: \"text\" content "
                                                     "block has a non-string \"text\" value: " (pr-str text))
                                                {:type :tools.agents.anthropic.error/invalid-content-shape
                                                 :status nil :body nil})))
                             text)))
                       content)]
      (if (seq texts)
        (str/join texts)
        (throw (ex-info "tools.agents.anthropic/output-text: no text content blocks found"
                         {:type :tools.agents.anthropic.error/no-text-content :status nil :body nil}))))))

;; ---------------------------------------------------------------------------
;; Message content-block DSL — brainstormed via a 4-candidate design panel
;; (hiccup-style tags / builder functions / plain-data normalizer / macro
;; templating) judged against simple/powerful/portable/consistent/composable-
;; with-tools; this is the synthesis. Deliberately NOT a parallel syntax:
;; `add-user-message`/`add-assistant-message` are WIDENED in place (same
;; names, same arity) to accept a string (unchanged), a single content-block
;; map, or a mixed seq of strings/maps — everything above them (messages-
;; create, output-text, tool-calls, add-tool-results) is untouched by this.
;; ---------------------------------------------------------------------------

(defn content-blocks
  "Normalize items into a Messages API content-block array:
     - a string -> [{\"type\" \"text\" \"text\" items}]  (one-element array)
     - a map    -> [items]                              (one-element array,
                   already a valid block — hand-written, built via
                   text/image-*/document-*/thinking/tool-use below, or
                   copied straight from a decoded response's own \"content\")
     - a seq    -> each element normalized one level in: bare strings become
                   {\"type\" \"text\" \"text\" s} blocks, maps pass through
                   verbatim
   Doubles as the builder for the request's array-form \"system\" field —
   same grammar, same function.

   A nil seq ITEM (not `items` itself — see below) is dropped, not rejected
   — the common `[(when include-image? (a/image-url u)) (a/text \"hi\")]`
   idiom (a conditional producing nil when its guard is false) is ordinary
   Clojure, not malformed input; requiring callers to `(remove nil? ...)`
   themselves first would be needless friction for a shape this common.

   Throws ex-info {:type :tools.agents.anthropic.error/invalid-content-block}
   on any seq item that is neither nil, a string, nor a map, AND on `items`
   itself being anything other than a string, a map, a seqable collection,
   or nil (nil/empty items is not otherwise rejected — normalizes to [],
   matching add-tool-results' existing behavior on an empty results seq: it
   fails at the API boundary with a 400, not locally). Checking `coll?` up
   front keeps the reject-a-bare-scalar behavior explicit rather than relying
   on `map`/`seq` in the :else branch to throw."
  [items]
  (cond
    (string? items) [{"type" "text" "text" items}]
    (map? items) [items]
    (or (nil? items) (coll? items) (seq? items))
    (vec (keep (fn [item]
                 (cond
                   (nil? item) nil
                   (string? item) {"type" "text" "text" item}
                   (map? item) item
                   :else (throw (ex-info (str "tools.agents.anthropic/content-blocks: content item must be a "
                                               "string or a content-block map, got: " (pr-str item))
                                          {:type :tools.agents.anthropic.error/invalid-content-block
                                           :status nil :body nil}))))
               items))
    :else
    (throw (ex-info (str "tools.agents.anthropic/content-blocks: items must be a string, a content-block "
                          "map, a seq of strings/maps, or nil, got: " (pr-str items))
                     {:type :tools.agents.anthropic.error/invalid-content-block
                      :status nil :body nil}))))

(defn text
  "A {\"type\" \"text\" ...} content block. opts merges onto the block itself
   (e.g. {\"cache_control\" {\"type\" \"ephemeral\"}})."
  ([s] {"type" "text" "text" s})
  ([s opts] (merge {"type" "text" "text" s} opts)))

(defn image-base64
  ([media-type data] {"type" "image" "source" {"type" "base64" "media_type" media-type "data" data}})
  ([media-type data opts] (merge (image-base64 media-type data) opts)))

(defn image-url
  ([url] {"type" "image" "source" {"type" "url" "url" url}})
  ([url opts] (merge (image-url url) opts)))

(defn document-base64
  ([media-type data] {"type" "document" "source" {"type" "base64" "media_type" media-type "data" data}})
  ([media-type data opts] (merge (document-base64 media-type data) opts)))

(defn document-url
  ([url] {"type" "document" "source" {"type" "url" "url" url}})
  ([url opts] (merge (document-url url) opts)))

(defn document-text
  ([s] {"type" "document" "source" {"type" "text" "media_type" "text/plain" "data" s}})
  ([s opts] (merge (document-text s) opts)))

(defn thinking
  "For rehydrating stored conversation history or authoring test fixtures —
   NOT the primary live tool-loop path. The primary path for replaying a
   model turn verbatim (thinking block, opaque signature and all) is
   `(add-assistant-message messages (get response \"content\"))`."
  [thinking-text signature]
  {"type" "thinking" "thinking" thinking-text "signature" signature})

(defn tool-use
  "A {\"type\" \"tool_use\" ...} content block — for rehydrating stored
   history, same caveat as `thinking`. The live path is the verbatim
   `add-assistant-message` echo above; tool-calls/tool-use? read a real
   response's tool_use blocks back out."
  [id name input]
  {"type" "tool_use" "id" id "name" name "input" input})

(defn add-user-message
  "Append a {\"role\" \"user\" \"content\" ...} turn and return the updated
   message vector. content may be:
     - a string (UNCHANGED behavior) -> sent verbatim as a bare string
     - a map    -> one content block, wrapped into a one-element array
     - a seq    -> mixed strings/maps, normalized via content-blocks: bare
                   strings become text blocks; maps — including tool_use,
                   image, document, thinking blocks, or a decoded response's
                   own content array — pass through verbatim
   See content-blocks for the nil/empty-content contract."
  [messages content]
  (conj (vec messages)
        {"role" "user"
         "content" (if (string? content) content (content-blocks content))}))

(defn add-assistant-message
  "Same widened contract as add-user-message, role \"assistant\" (e.g. for
   prefill — see examples/). The tool-loop idiom for replaying a model turn
   verbatim — including any thinking block's opaque signature and every
   tool_use block, byte-for-byte, as the API requires — is:
     (add-assistant-message messages (get response \"content\"))"
  [messages content]
  (conj (vec messages)
        {"role" "assistant"
         "content" (if (string? content) content (content-blocks content))}))

;; ---------------------------------------------------------------------------
;; Tool calling — "tools" in the request map already passes straight through
;; messages-create untouched (this library is data-transparent by design), so
;; the only thing missing was ergonomics around the RESPONSE side (finding
;; tool_use blocks) and the follow-up turn (sending tool_result blocks back).
;; ---------------------------------------------------------------------------

(defn tool-use?
  "True when response's \"stop_reason\" is \"tool_use\" — the model paused to
   call one or more tools rather than finishing its turn."
  [response]
  (= (get response "stop_reason") "tool_use"))

(defn tool-calls
  "Extract response's tool_use content blocks as a vector of
   {:id ... :name ... :input ...} maps, for a (doseq [{:keys [id name
   input]} (tool-calls resp)] ...) dispatch loop. Only this outer wrapper is
   keyword-keyed for convenience — :input is passed through exactly as the
   API returned it (string-keyed, since it's arbitrary caller-defined JSON
   matching the tool's input_schema).

   Returns [] (never throws) when response has no \"content\" array or no
   tool_use blocks at all — unlike output-text, \"the model didn't call a
   tool\" is an ordinary branch here, not an error condition."
  [response]
  (let [content (get response "content")]
    (if (vector? content)
      (into []
            (keep (fn [block]
                    (when (and (map? block) (= (get block "type") "tool_use"))
                      {:id (get block "id") :name (get block "name") :input (get block "input")})))
            content)
      [])))

(defn add-tool-results
  "Append ONE user turn containing a tool_result content block for each
   {:tool-use-id ... :content ... :is-error ...} map in results — the
   Messages API expects every tool_use call from one assistant turn to be
   answered inside a SINGLE following user message as multiple content
   blocks, not one message per result (a common tool-loop bug this signature
   is shaped to avoid). :content gets the SAME widened-content-blocks
   handling as add-user-message/add-assistant-message: a plain string is
   sent verbatim, a single content-block map (e.g. an image block, for tools
   returning non-text output) is wrapped into a one-element array, and a
   mixed seq of strings/maps is normalized via content-blocks (bare strings
   become text blocks). Previously :content was forwarded to the wire
   completely unnormalized — a bare image-block map (the docstring's own
   example) or a mixed string/map seq shipped a malformed tool_result
   payload; fixed to match every sibling content-accepting function. :is-
   error defaults to falsy; set true so the model sees that particular tool
   call failed. Returns the updated message vector, same conj-and-return
   contract as add-user-message/add-assistant-message."
  [messages results]
  (conj (vec messages)
        {"role" "user"
         "content" (mapv (fn [{:keys [tool-use-id content is-error]}]
                            (cond-> {"type" "tool_result" "tool_use_id" tool-use-id
                                     "content" (if (string? content) content (content-blocks content))}
                              is-error (assoc "is_error" true)))
                          results)}))

(defn add-tool-result
  "Convenience single-result wrapper over add-tool-results, for the common
   case of exactly one tool_use call in the previous assistant turn."
  ([messages tool-use-id content]
   (add-tool-result messages tool-use-id content false))
  ([messages tool-use-id content is-error]
   (add-tool-results messages [{:tool-use-id tool-use-id :content content :is-error is-error}])))
