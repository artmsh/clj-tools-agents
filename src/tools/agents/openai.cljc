(ns tools.agents.openai
  "A pure-Clojure client for OpenAI's Responses and Chat Completions APIs,
   ergonomically modeled on the official openai-python client (OpenAI(...)
   constructor, client.responses.create(**params) /
   client.chat.completions.create(**params) resource methods, typed error
   hierarchy).

   Sibling of tools.agents.anthropic — same architecture, same two runtimes,
   same leaf-I/O and JSON-codec design. See docs/openai.md 'Parity with
   openai-python' for the SDK parity table, and docs/divergences.md for the
   deliberate divergences from the sibling clients.

   Runs unmodified on JVM Clojure and Babashka. The 'client object' here is an
   `OpenAIClient` record built by `client`; it retains Clojure's map-style
   keyword lookup and immutable update semantics on both runtimes.

   PORTABILITY: all network I/O goes through `tools.agents.http/request!`,
   the shared HTTP request function, which needs no reader
   conditional. Everything else — URL/header building, the hand-rolled JSON codec,
   error typing, credential resolution, the retry policy, output-text
   extraction — is plain, portable clojure.core exercised identically by both
   test runners. `getenv` (env-var access) and `sleep!` (retry backoff) touch
   the process too, but need no reader conditional — they are testability
   seams, not runtime-specific leaves.

   RETRIES: on by default (:max-retries, default 2) and a direct port of
   openai-python's _base_client policy — transport failures always retried,
   408/409/429/5xx retried unless `x-should-retry` says otherwise, `Retry-After`
   honored up to two minutes, exponential backoff with the SDK's jitter. The
   decision and delay functions (`should-retry?`, `retry-delay-ms`,
   `parse-retry-after-ms`) are pure, with the clock and RNG injected.

   JSON: there is no JSON library available on both runtimes without adding a
   dependency (Babashka bundles one, JVM Clojure does not), so
   `write-json`/`read-json` below wrap the small hand-written codec shared
   in tools.agents.json. It supports exactly what these APIs need:
   nil/bool/number/string/keyword/vector/seq/map, with map keys as either
   strings or keywords passed through VERBATIM (no kebab<->snake conversion
   — the same idiom openai-python uses with literal dict keys like
   \"max_output_tokens\"). See docs/openai.md 'JSON: a small hand-rolled
   codec, not a dependency'.

   STREAMING: not implemented. :stream true is rejected with a clear error
   rather than silently ignored. See docs/openai.md 'Streaming is not
   supported'."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.token :as token]))

;; openai-python's default base_url INCLUDES the /v1 path segment (unlike
;; anthropic-sdk-python's host-only base_url) — endpoints are appended to it
;; as "/responses" / "/chat/completions", NOT "/v1/responses".
(def default-base-url "https://api.openai.com/v1")

;; openai-python's DEFAULT_MAX_RETRIES. Override per client with :max-retries
;; (the analogue of `OpenAI(max_retries=n)` / `with_options(max_retries=n)`).
;; The rest of the retry policy lives further down, next to `should-retry?`.
(def default-max-retries 2)

;; :organization and :project are deliberately omitted when unset. Keeping
;; only invariant keys as fields lets the record preserve that `contains?`
;; contract through its extension map.
(defrecord OpenAIClient [api-key base-url max-retries])

;; ---------------------------------------------------------------------------
;; JSON codec — tools.agents.json, bound to this namespace's error contract.
;; ---------------------------------------------------------------------------

(def ^:private json-codec
  (json/codec {:prefix      "tools.agents.openai"
               :encode-type :tools.agents.openai/json-encode-error
               :parse-type  :tools.agents.openai/json-parse-error}))

(defn write-json
  "Encode a Clojure value as a JSON string (tools.agents.json/write-json). Map
   keys may be strings or keywords, encoded verbatim; every character below
   0x20 is \\u00XX-escaped. Throws ex-info
   {:type :tools.agents.openai/json-encode-error} on an unsupported value."
  [v]
  ((:write json-codec) v))

(defn read-json
  "Decode a JSON string into a Clojure value (tools.agents.json/read-json):
   objects become maps with STRING keys, arrays become vectors, numbers stay
   numbers. Throws ex-info {:type :tools.agents.openai/json-parse-error} on
   malformed input — never returns a partial result."
  [s]
  ((:read json-codec) s))

(defn read-jsonl
  "Decode JSON Lines from a String or java.io.Reader into a LAZY seq of
   values (tools.agents.json/read-jsonl). Blank lines are skipped. A
   malformed line throws, when realized, ex-info
   {:type :tools.agents.openai/json-parse-error :line n} with message prefix
   `tools.agents.openai/read-jsonl: line n: `."
  [src]
  ((:read-jsonl json-codec) src))

;; ---------------------------------------------------------------------------
;; Process boundary — `getenv`, `sleep!` and `now-ms` are testability seams.
;; Network I/O is tools.agents.http/request!, called from request! below.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  (System/getenv name))

(defn- sleep! [millis]
  (let [ms (long millis)]
    (when (pos? ms)
      (Thread/sleep ms)
      nil)))

(defn- now-ms [] (System/currentTimeMillis))

;; ---------------------------------------------------------------------------
;; Credentials / client construction
;; ---------------------------------------------------------------------------

(defn resolve-credentials
  "Pure credential-resolution logic, factored out for testability (process env
   vars can't be reliably unset mid-test-run, so tests inject a fake getenv-fn
   instead of touching the real environment).

   openai-python's chain here collapses to a single auth mechanism: unlike
   anthropic-sdk-python's api_key-vs-auth_token split, every OpenAI request is
   authenticated with `Authorization: Bearer <api-key>`. Precedence: explicit
   :api-key > OPENAI_API_KEY env var > throw. Returns {:api-key s}."
  [{:keys [api-key]} getenv-fn]
  (if api-key
    {:api-key api-key}
    (let [env-key (getenv-fn "OPENAI_API_KEY")]
      (if (seq env-key)
        {:api-key env-key}
        (throw (ex-info (str "tools.agents.openai/client: no credentials found — pass :api-key, "
                              "or set the OPENAI_API_KEY environment variable")
                         {:type :tools.agents.openai/missing-credentials}))))))

(defn- resolve-client-credentials
  "The full credential chain `client` uses: an explicit :credential-source,
   else `resolve-credentials` (explicit :api-key > OPENAI_API_KEY > throw).
   Returns {:credential-source src} or {:api-key s}. Later refreshable
   sources (#36 workload identity, #37 callable :api-key) plug in here as
   further steps that return {:credential-source <TokenSource>}."
  [opts getenv-fn]
  (if (contains? opts :credential-source)
    (let [src (:credential-source opts)]
      (when-not (token/token-source? src)
        (throw (ex-info (str "tools.agents.openai/client: :credential-source must satisfy "
                             "tools.agents.token/TokenSource, got: " (type src))
                        {:type :tools.agents.openai/invalid-credentials})))
      (when (some? (:api-key opts))
        (throw (ex-info "tools.agents.openai/client: pass either :api-key or :credential-source, not both"
                        {:type :tools.agents.openai/invalid-credentials})))
      {:credential-source src})
    (resolve-credentials opts getenv-fn)))

(defn client
  "Build an OpenAIClient record — the 'client object' analogue of Python's
   OpenAI(...) constructor. Resolves credentials eagerly (fails fast with a
   catchable ex-info BEFORE any network request).

   opts:
     :api-key      explicit API key -> sent as `Authorization: Bearer <key>`
                   (falls back to OPENAI_API_KEY)
     :credential-source  a tools.agents.token/TokenSource (e.g.
                   `tools.agents.token/token-cache`) asked for a token before
                   every attempt -> `Authorization: Bearer <token>`. Replaces
                   :api-key and OPENAI_API_KEY; passing both throws
                   {:type :tools.agents.openai/invalid-credentials}. A 401
                   invalidates the token and retries once outside
                   :max-retries. The key is absent from the client otherwise.
     :organization optional org id -> sent as the `OpenAI-Organization` header
                   (falls back to OPENAI_ORG_ID; header omitted entirely when
                   unset, matching the SDK's Omit() behavior)
     :project      optional project id -> `OpenAI-Project` header
                   (falls back to OPENAI_PROJECT_ID; omitted when unset)
     :base-url     override API root, default `https://api.openai.com/v1`
                   (also resolved from OPENAI_BASE_URL). NOTE the `/v1` is
                   part of the base-url here, matching openai-python.
     :max-retries  how many times to retry a failed request, default 2
                   (`default-max-retries`, openai-python's own default). 0
                   disables retries. The Python `with_options(max_retries=n)`
                   per-call override is just `(assoc client :max-retries n)`
                   here, since the client record is associative.
     :webhook-secret  secret for tools.agents.openai.webhooks verification
                   (falls back to OPENAI_WEBHOOK_SECRET; key absent when
                   unset). An explicit `:secret` passed to verify-signature /
                   unwrap still wins."
  ([] (client {}))
  ([opts]
   (let [creds (resolve-client-credentials opts getenv)
         org   (or (:organization opts) (getenv "OPENAI_ORG_ID"))
         proj  (or (:project opts) (getenv "OPENAI_PROJECT_ID"))
         whsec (if (some? (:webhook-secret opts)) (:webhook-secret opts) (getenv "OPENAI_WEBHOOK_SECRET"))]
     (map->OpenAIClient
      (cond-> (merge {:base-url    (or (:base-url opts) (getenv "OPENAI_BASE_URL") default-base-url)
                      :max-retries (if (number? (:max-retries opts))
                                     (max 0 (long (:max-retries opts)))
                                     default-max-retries)}
                     creds)
        (seq org)  (assoc :organization org)
        (seq proj)    (assoc :project proj)
        (some? whsec) (assoc :webhook-secret whsec))))))

;; ---------------------------------------------------------------------------
;; Error typing
;; ---------------------------------------------------------------------------

(defn status->type
  "HTTP status -> this library's :type keyword. Public (not ^:private) so
   tools.agents.openai.agents' own resource methods can throw the exact same
   keywords for the exact same wire-error semantics, rather than maintaining
   a second copy of this table that could silently drift from this one."
  [status]
  (cond
    (= status 400) :tools.agents.openai/bad-request-error
    (= status 401) :tools.agents.openai/authentication-error
    (= status 403) :tools.agents.openai/permission-denied-error
    (= status 404) :tools.agents.openai/not-found-error
    (= status 409) :tools.agents.openai/conflict-error
    (= status 422) :tools.agents.openai/unprocessable-entity-error
    (= status 429) :tools.agents.openai/rate-limit-error
    (and status (>= status 500)) :tools.agents.openai/internal-server-error
    :else :tools.agents.openai/api-status-error))

(defn retryable-status?
  "True for the HTTP statuses openai-python's retry policy treats as retryable
   on their own: 408 (request timeout), 409 (lock timeout), 429 (rate limit),
   and any 5xx. `should-retry?` layers the header-driven overrides on top."
  [status]
  (boolean (and status (or (= status 408) (= status 409) (= status 429) (>= status 500)))))

;; ---------------------------------------------------------------------------
;; Retry policy — a port of openai-python's _base_client retry logic
;; (`_parse_retry_after_header`, `_should_retry`, `_calculate_retry_timeout`)
;; and _constants.py's tuning values. Everything here is pure: the caller
;; supplies "now" and the RNG, so every branch is unit-testable without
;; sleeping, without a clock and without randomness.
;; ---------------------------------------------------------------------------

;; `default-max-retries` is defined at the top of the file — `client` needs it.
(def ^:private initial-retry-delay-ms 500)              ;; INITIAL_RETRY_DELAY = 0.5
(def ^:private max-retry-delay-ms 8000)                 ;; MAX_RETRY_DELAY = 8.0
(def ^:private max-retry-after-delay-ms (* 2 60 1000))  ;; MAX_RETRY_AFTER_DELAY = 2 * 60

(defn header-value
  "Case-insensitive header lookup. tools.agents.http/request! lower-cases
   response header names, but hand-built and inbound maps may use any case,
   and keys may be strings or keywords. A multi-value header arrives as a
   vector; take the first. Public (not ^:private) so
   tools.agents.openai.webhooks looks up inbound webhook headers the same way."
  [headers k]
  (when (map? headers)
    (let [target (str/lower-case k)]
      (reduce (fn [acc [hk hv]]
                (let [hk-str (if (keyword? hk) (name hk) (str hk))]
                  (if (= target (str/lower-case hk-str))
                    (reduced (if (vector? hv) (first hv) hv))
                    acc)))
              nil
              headers))))

(def ^:private month-index
  {"Jan" 1 "Feb" 2 "Mar" 3 "Apr" 4 "May" 5 "Jun" 6
   "Jul" 7 "Aug" 8 "Sep" 9 "Oct" 10 "Nov" 11 "Dec" 12})

(defn- days-from-civil
  "Days since the Unix epoch for a proleptic-Gregorian y/m/d (Howard Hinnant's
   days_from_civil). Pure integer arithmetic — no java.time, identical on
   both runtimes."
  [y m d]
  (let [y   (if (<= m 2) (dec y) y)
        era (quot (if (>= y 0) y (- y 399)) 400)
        yoe (- y (* era 400))
        doy (+ (quot (+ (* 153 (+ m (if (> m 2) -3 9))) 2) 5) (dec d))
        doe (+ (* yoe 365) (quot yoe 4) (- (quot yoe 100)) doy)]
    (+ (* era 146097) doe -719468)))

(defn- tz-offset-seconds [tz]
  (cond
    (nil? tz) 0
    (contains? #{"GMT" "UTC" "UT" "Z"} tz) 0
    (and (= 5 (count tz)) (contains? #{"+" "-"} (subs tz 0 1)))
    (let [sign (if (= "-" (subs tz 0 1)) -1 1)
          hh   (parse-long (subs tz 1 3))
          mm   (parse-long (subs tz 3 5))]
      (when (and hh mm) (* sign (+ (* 3600 hh) (* 60 mm)))))
    :else nil))

(defn- parse-http-date-ms
  "Epoch millis for an RFC-1123 HTTP-date (\"Wed, 21 Oct 2015 07:28:00 GMT\"),
   or nil. Only the RFC-1123 spelling is handled — the obsolete RFC-850 and
   asctime forms that Python's email.utils.parsedate_tz also accepts fall
   through to nil, and callers then fall back to exponential backoff."
  [s]
  (let [toks (vec (remove str/blank? (str/split (str/trim (str s)) #"[\s,]+")))
        ;; Find the month token.
        idx  (loop [i 0]
               (cond
                 (>= i (count toks)) nil
                 (contains? month-index (nth toks i)) i
                 :else (recur (inc i))))]
    (when (and idx (>= idx 1) (>= (count toks) (+ idx 3)))
      (let [day        (parse-long (nth toks (dec idx)))
            month      (get month-index (nth toks idx))
            year       (parse-long (nth toks (inc idx)))
            time-parts (str/split (nth toks (+ idx 2)) #":")
            tz         (when (> (count toks) (+ idx 3)) (nth toks (+ idx 3)))]
        (when (and day year (= 3 (count time-parts)))
          (let [hh  (parse-long (nth time-parts 0))
                mm  (parse-long (nth time-parts 1))
                ss  (parse-long (nth time-parts 2))
                off (tz-offset-seconds tz)]
            (when (and hh mm ss off)
              (* 1000 (- (+ (* 86400 (days-from-civil year month day))
                            (* 3600 hh) (* 60 mm) ss)
                         off)))))))))

(defn parse-retry-after-ms
  "Server-directed retry delay in MILLISECONDS, or nil when unspecified —
   openai-python's `_parse_retry_after_header`, in ms rather than float
   seconds so the whole retry path stays integer arithmetic.

   Precedence, exactly as in the SDK: the non-standard `retry-after-ms`
   header first (more precise than integer seconds), then `retry-after` as a
   number of seconds (floats accepted, as the SDK does), then `retry-after` as
   an HTTP-date relative to now-ms. May legitimately return a zero or negative
   value — the caller applies the SDK's `0 < v <= MAX_RETRY_AFTER_DELAY` gate."
  [headers now-ms]
  (let [ms-header (header-value headers "retry-after-ms")
        ms-val    (when (some? ms-header) (parse-double (str/trim (str ms-header))))]
    (if (some? ms-val)
      (long ms-val)
      (let [ra (header-value headers "retry-after")]
        (when (some? ra)
          (let [s    (str/trim (str ra))
                secs (parse-double s)]
            (if (some? secs)
              (long (* 1000.0 secs))
              (let [epoch-ms (parse-http-date-ms s)]
                (when epoch-ms (- epoch-ms now-ms))))))))))

(defn should-retry?
  "openai-python's `_should_retry`: a `Retry-After` beyond two minutes vetoes
   the retry outright; then the non-standard `x-should-retry` header wins if
   the server sent exactly \"true\" or \"false\" (case-sensitive, as in the
   SDK); otherwise fall back to `retryable-status?`."
  [status headers now-ms]
  (let [ra (parse-retry-after-ms headers now-ms)]
    (if (and (some? ra) (> ra max-retry-after-delay-ms))
      false
      (let [hdr (header-value headers "x-should-retry")]
        (cond
          (= hdr "true") true
          (= hdr "false") false
          :else (retryable-status? status))))))

(defn- backoff-base-ms
  "min(INITIAL_RETRY_DELAY * 2^retries-taken, MAX_RETRY_DELAY), by doubling —
   no Math/pow, no overflow, no 1000-retry cap needed since
   the loop stops at the ceiling."
  [retries-taken]
  (loop [n 0 v initial-retry-delay-ms]
    (if (or (>= n retries-taken) (>= v max-retry-delay-ms))
      (min v max-retry-delay-ms)
      (recur (inc n) (* v 2)))))

(defn retry-delay-ms
  "How long to wait before attempt number (retries-taken + 1), in ms —
   openai-python's `_calculate_retry_timeout`.

   A `Retry-After` within (0, two minutes] is honored verbatim. Otherwise
   exponential backoff from INITIAL_RETRY_DELAY, capped at MAX_RETRY_DELAY,
   scaled by the SDK's `1 - 0.25 * random()` jitter — expressed here in
   integer ms (jitter numerator in [751, 1000] over 1000) so no runtime needs
   float rounding. rand-fn must return a double in [0.0, 1.0); the 3-arity
   uses clojure.core/rand.

   (The SDK derives its exponent as `max_retries - remaining_retries`, which
   is exactly retries-taken, so max-retries is not a parameter here.)"
  ([retries-taken headers now-ms] (retry-delay-ms retries-taken headers now-ms rand))
  ([retries-taken headers now-ms rand-fn]
   (let [ra (parse-retry-after-ms headers now-ms)]
     (if (and (some? ra) (> ra 0) (<= ra max-retry-after-delay-ms))
       ra
       (let [base       (backoff-base-ms retries-taken)
             jitter-num (- 1000 (long (* 250 (rand-fn))))]
         (max 0 (quot (* base jitter-num) 1000)))))))

(defn extract-error-message
  "Best-effort extraction of OpenAI's {\"error\":{\"message\":\"...\"}} shape.
   Returns nil (never throws) on any parse failure. Public (not ^:private) so
   tools.agents.openai.agents reuses this verbatim rather than a second copy
   — the Agents API's error bodies use the exact same shape."
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
;; Request plumbing
;; ---------------------------------------------------------------------------

(defn endpoint-url
  "Join client's base-url with an endpoint path like \"/responses\".

   The base-url already carries `/v1` (openai-python's default is
   `https://api.openai.com/v1`), so this must NOT re-add it the way
   tools.agents.anthropic's messages-url does — that would produce
   `.../v1/v1/responses`. Public (not ^:private) so
   tools.agents.openai.agents reuses this join logic verbatim instead of a
   second copy that could drift from it."
  [base-url path]
  (if (str/ends-with? base-url "/")
    (str base-url (subs path 1))
    (str base-url path)))

(defn- fn-label
  "Error-message prefix for fn-name: a name already qualified with `/` (e.g.
   \"tools.agents.openai.agents/sessions-create\") is used verbatim, a bare
   one is qualified under tools.agents.openai."
  [fn-name]
  (if (str/includes? (str fn-name) "/") (str fn-name) (str "tools.agents.openai/" fn-name)))

(defn- attempt-credential
  "The bearer credential for ONE attempt: a token from the client's
   :credential-source (fetched or refreshed as needed; a fetch failure
   propagates untyped, never retried as a connection error), else :api-key."
  [label client]
  (if-let [src (:credential-source client)]
    (token/token! src)
    (or (:api-key client)
        (throw (ex-info (str label ": client has no :api-key — build it via "
                             "tools.agents.openai/client")
                        {:type :tools.agents.openai/missing-credentials})))))

(defn- request-headers
  "Headers for ONE attempt. `request!` calls this before every attempt and
   never reuses a previous attempt's map, so credentials that change between
   attempts (a refreshed token) land on the retry. `extra` is merged
   last and may override any default."
  [credential client multipart? extra]
  ;; OpenAI-Organization / OpenAI-Project must be ABSENT (not empty-valued)
  ;; when unset — the SDK emits Omit() for them. cond->, never assoc-of-nil.
  ;; A multipart body gets its content-type (with boundary) from
  ;; tools.agents.http/request!, so the JSON one is not set there.
  (merge (cond-> {"authorization" (str "Bearer " credential)}
           (not multipart?)             (assoc "content-type" "application/json")
           (seq (:organization client)) (assoc "openai-organization" (:organization client))
           (seq (:project client))      (assoc "openai-project" (:project client)))
         extra))

(defn- streaming-requested? [body multipart]
  (boolean
   (or (and (map? body) (or (true? (get body :stream)) (true? (get body "stream"))))
       (some (fn [part] (and (= "stream" (str (:name part))) (= "true" (str (:content part)))))
             multipart))))

(defn- reject-streaming! [label body multipart]
  (when (streaming-requested? body multipart)
    (throw (ex-info (str label ": :stream true is not supported — "
                          "SSE streaming is not implemented by this client. See README.")
                     {:type :tools.agents.openai/streaming-unsupported}))))

(defn- own-error?
  "True for an already-typed tools.agents.openai/* ex-info. Matches the
   namespace EXACTLY: moving these keywords under a `.error` sub-namespace
   (as tools.agents.anthropic spells its own) would silently make this
   false for every one of them, and the retry loop would then retry
   permanent refusals. See docs/openai.md's error-hierarchy note.

   openai-python
   re-raises OpenAIError subclasses out of the send path as-is, without
   retrying or rewrapping them, and retrying such a refusal would be pointless
   as well as noisy."
  [e]
  (let [data (ex-data e)]
    (boolean (and data (keyword? (:type data)) (= "tools.agents.openai" (namespace (:type data)))))))

(defn- resolve-max-retries [client]
  (let [n (:max-retries client)]
    (if (number? n) (max 0 (long n)) default-max-retries)))

(defn- invalidate-credentials!
  "`request!` calls this on a 401, at most once per call, with the credential
   that attempt sent. True means the client's :credential-source dropped that
   token and the next attempt may get a fresh one; `request!` then retries
   once, OUTSIDE the :max-retries budget. Static :api-key credentials have
   nothing to refresh, so this returns false and a 401 surfaces at once as
   :tools.agents.openai/authentication-error."
  [client used-credential]
  (if-let [src (:credential-source client)]
    (boolean (token/invalidate! src used-credential))
    false))

(defn- body->string
  "A response body as a String: `:as :bytes` yields byte[], the rest a String."
  [body]
  (cond (nil? body)   nil
        (bytes? body) (String. ^bytes body "UTF-8")
        :else         (str body)))

(defn- slurp-error-stream
  "`:as :stream` only: a non-2xx response's InputStream body read to a String
   and closed, so the retry and error paths below see a String as for :string."
  [as {:keys [status body] :as resp}]
  (if (and (= as :stream) (instance? java.io.InputStream body) (not (and status (<= 200 status 299))))
    (assoc resp :body (with-open [^java.io.InputStream in body] (String. (.readAllBytes in) "UTF-8")))
    resp))

(defn- decode-success [as body]
  (case as
    :json   (when (seq body) (read-json body))
    :string body
    :bytes  body))

(defn request!
  "The one OpenAI transport every resource method uses: tools.agents.openai,
   tools.agents.openai.agents, .embeddings, .realtime and later resource
   namespaces. One call is one logical request: build the URL, encode the
   JSON body ONCE, then loop attempts through tools.agents.http/request!,
   rebuilding headers before EVERY attempt. Transport failures and retryable
   statuses are retried per openai-python's policy (`should-retry?`,
   `retry-delay-ms`: `x-should-retry`, `retry-after-ms`, `retry-after`;
   :max-retries on the client, default 2); errors are typed by `status->type`.

   fn-name labels error messages: a bare name (\"files-create\") is prefixed
   with \"tools.agents.openai/\"; a qualified one
   (\"tools.agents.openai.agents/sessions-create\") is used verbatim. The
   2-arity uses \"request!\".

   req:
     :method     :get | :post | :delete | ... (default :post)
     :path       endpoint path appended to the client's base-url, e.g. \"/files\"
     :query      optional params map, bracket-encoded by
                 tools.agents.http/encode-params
     :body       optional JSON value (map, vector, ...), encoded by `write-json`
     :multipart  optional tools.agents.http multipart parts, instead of :body;
                 no JSON content-type is sent
     :headers    extra headers merged over the defaults on every attempt,
                 e.g. {\"openai-beta\" \"agents=v1\"}
     :as         :json (default: decode a 2xx body, empty body -> nil) |
                 :string (raw String, no decode) | :bytes (raw byte[]) |
                 :stream (return the whole 2xx response {:status :headers
                 :body InputStream}; the caller owns and must close the body;
                 a non-2xx body is read and closed here)

   A `stream` true body field or multipart part throws
   :tools.agents.openai/streaming-unsupported before any network I/O, except
   with `:as :stream`, the one mode that can read an SSE body. Streaming
   callers pass `request!` as tools.agents.stream/open-event-stream's `:send!`,
   so opening a stream gets this same retry loop and error typing.

   Throws ex-info:
     - non-2xx (not retryable, or budget spent): {:type (status->type status)
       :status :body <String> :retries-taken}
     - no response after the budget: :tools.agents.openai/api-connection-error
     - malformed 2xx JSON: :tools.agents.openai/json-parse-error, not retried
       (the SDK decodes after its retry loop)

   401: with a :credential-source client, the first 401 invalidates the token
   that attempt sent and retries once, outside :max-retries and without
   backoff; a second 401 throws. A static :api-key 401 is never retried."
  ([client req] (request! client "request!" req))
  ([client fn-name {:keys [method path query body multipart headers as]
                    :or   {method :post as :json}}]
   (let [label (fn-label fn-name)]
     (when-not (contains? #{:json :string :bytes :stream} as)
       (throw (ex-info (str label ": unsupported :as " (pr-str as) " — expected :json, :string, :bytes or :stream")
                       {:type :tools.agents.openai/invalid-request})))
     (when-not (= as :stream) (reject-streaming! label body multipart))
     (let [url         (endpoint-url (:base-url client) path)
           body-str    (when (some? body) (write-json body))
           max-retries (resolve-max-retries client)]
       (loop [retries-taken 0
              auth-retried? false]
         (let [credential (attempt-credential label client)
               outcome    (try
                            {:resp (slurp-error-stream as (http/request!
                                    (cond-> {:method  method
                                             :url     url
                                             :query   query
                                             :headers (request-headers credential client (some? multipart) headers)
                                             :as      (case as (:bytes :stream) as :string)}
                                      body-str  (assoc :body body-str)
                                      multipart (assoc :multipart multipart))))}
                            (catch Exception e
                              (if (own-error? e) (throw e) {:error e})))]
           (if (:error outcome)
             ;; No response at all: DNS, connection refused, TLS handshake,
             ;; timeout. The SDK retries these without consulting _should_retry.
             (if (< retries-taken max-retries)
               (do (sleep! (retry-delay-ms retries-taken nil (now-ms)))
                   (recur (inc retries-taken) auth-retried?))
               (throw (ex-info (str label ": connection failed: " (:error outcome))
                               {:type :tools.agents.openai/api-connection-error :status nil :body nil
                                :retries-taken retries-taken})))
             (let [{:keys [status] resp-hdrs :headers resp-body :body} (:resp outcome)]
               (cond
                 (and status (>= status 200) (< status 300))
                 (if (= as :stream) (:resp outcome) (decode-success as resp-body))

                 (and (= status 401) (not auth-retried?) (invalidate-credentials! client credential))
                 (recur retries-taken true)

                 (and (< retries-taken max-retries) (should-retry? status resp-hdrs (now-ms)))
                 (do (sleep! (retry-delay-ms retries-taken resp-hdrs (now-ms)))
                     (recur (inc retries-taken) auth-retried?))

                 :else
                 (let [body-s  (body->string resp-body)
                       err-msg (extract-error-message body-s)
                       detail  (cond err-msg err-msg (seq body-s) body-s :else nil)]
                   (throw (ex-info (str label ": HTTP " status (when detail (str " " detail)))
                                   {:type (status->type status) :status status :body body-s
                                    :retries-taken retries-taken}))))))))))))

(defn post-json!
  "POST a JSON request map to path and decode the JSON response: a thin
   wrapper over `request!`, kept public for sibling namespaces
   (tools.agents.openai.embeddings, tools.agents.openai.realtime). Same
   retries, error typing and ex-data as `request!`."
  [client fn-name path request]
  (request! client fn-name {:method :post :path path :body request}))

;; ---------------------------------------------------------------------------
;; Public API — resource methods
;; ---------------------------------------------------------------------------

(defn responses-create
  "POST request (a plain map, passed through to JSON almost verbatim — model,
   input, instructions, max_output_tokens, temperature, reasoning, text,
   tools, store, previous_response_id, etc. pass straight through untouched)
   to POST {base-url}/responses. Returns the decoded response map (string
   keys). The analogue of Python's client.responses.create(**params).

   :stream true throws immediately — see the ns docstring's STREAMING note.

   Throws ex-info on any failure, message prefixed
   \"tools.agents.openai/responses-create: \", ex-data
   {:type <keyword — see docs/openai.md 'Error hierarchy'> :status
   <http-status-or-nil> :body <raw-response-body-or-nil>}."
  [client request]
  (post-json! client "responses-create" "/responses" request))

(defn chat-completions-create
  "POST request to POST {base-url}/chat/completions — the analogue of Python's
   client.chat.completions.create(**params). Same pass-through and error
   contract as `responses-create`; message prefix is
   \"tools.agents.openai/chat-completions-create: \".

   This is openai-python's *legacy* surface; prefer `responses-create` for new
   code, exactly as the SDK's own README does."
  [client request]
  (post-json! client "chat-completions-create" "/chat/completions" request))

;; ---------------------------------------------------------------------------
;; Public API — response accessors
;; ---------------------------------------------------------------------------

(defn output-text
  "Concatenate the \"text\" fields of every \"output_text\" content block
   inside every \"message\" item of a Responses-API response's \"output\"
   array — a direct port of openai-python's Response.output_text property:

     texts = []
     for output in self.output:
         if output.type == \"message\":
             for content in output.content:
                 if content.type == \"output_text\":
                     texts.append(content.text)
     return \"\".join(texts)

   Returns \"\" when there are no output_text blocks (a reasoning-only or
   refusal-only response), matching that property's documented contract.
   NOTE this deliberately DIVERGES from tools.agents.anthropic/output-text,
   which throws rather than ever returning an empty result — see
   docs/divergences.md.

   Structurally malformed responses still throw: a missing/non-vector
   \"output\", a \"message\" item with a non-vector \"content\", or an
   \"output_text\" block whose \"text\" is present but not a string."
  [response]
  (let [output (get response "output")]
    (when-not (vector? output)
      (throw (ex-info "tools.agents.openai/output-text: response has no \"output\" array"
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    (str/join ""
           (reduce
             (fn [acc item]
               (if (and (map? item) (= (get item "type") "message"))
                 (let [content (get item "content")]
                   (when-not (vector? content)
                     (throw (ex-info (str "tools.agents.openai/output-text: \"message\" output item has "
                                           "a non-array \"content\": " (pr-str content))
                                      {:type :tools.agents.openai/invalid-content-shape
                                       :status nil :body nil})))
                   (reduce
                     (fn [acc block]
                       (if (and (map? block)
                                (= (get block "type") "output_text")
                                (contains? block "text"))
                         (let [text (get block "text")]
                           (when-not (string? text)
                             (throw (ex-info (str "tools.agents.openai/output-text: \"output_text\" content "
                                                   "block has a non-string \"text\" value: " (pr-str text))
                                              {:type :tools.agents.openai/invalid-content-shape
                                               :status nil :body nil})))
                           (conj acc text))
                         acc))
                     acc content))
                 acc))
             []
             output))))

(defn completion-text
  "Chat-Completions convenience accessor: the equivalent of Python's
   `completion.choices[0].message.content`. Not an SDK method — the Python SDK
   spells this as plain attribute access — just the shortest portable spelling
   of the same traversal.

   Returns nil when the first choice's \"content\" is JSON null (which is what
   the API sends for a tool-call or refusal response), matching the SDK's
   `.content` being None there. Throws only on structurally malformed input: a
   missing/non-vector/empty \"choices\", a missing \"message\", or a
   \"content\" that is present, non-null and not a string."
  [completion]
  (let [choices (get completion "choices")]
    (when-not (vector? choices)
      (throw (ex-info "tools.agents.openai/completion-text: response has no \"choices\" array"
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    (when (empty? choices)
      (throw (ex-info "tools.agents.openai/completion-text: \"choices\" array is empty"
                       {:type :tools.agents.openai/invalid-response :status nil :body nil})))
    (let [message (get (first choices) "message")]
      (when-not (map? message)
        (throw (ex-info "tools.agents.openai/completion-text: first choice has no \"message\" object"
                         {:type :tools.agents.openai/invalid-response :status nil :body nil})))
      (let [content (get message "content")]
        (when-not (or (nil? content) (string? content))
          (throw (ex-info (str "tools.agents.openai/completion-text: \"message\" has a non-string, "
                                "non-null \"content\": " (pr-str content))
                           {:type :tools.agents.openai/invalid-content-shape :status nil :body nil})))
        content))))

;; ---------------------------------------------------------------------------
;; Message-list helpers
;; ---------------------------------------------------------------------------
;; Chat Completions takes these as its "messages" array; the Responses API
;; takes the same {"role" ... "content" ...} shape as its "input" array (or a
;; bare string, or a top-level "instructions" for the system prompt).

(defn add-user-message
  "Append a {\"role\" \"user\" \"content\" text} turn and return the updated
   message vector (Python's list.append(...) mutation becomes conj-and-return)."
  [messages text]
  (conj (vec messages) {"role" "user" "content" text}))

(defn add-assistant-message
  "Append a {\"role\" \"assistant\" \"content\" text} turn (e.g. for prefill)
   and return the updated message vector."
  [messages text]
  (conj (vec messages) {"role" "assistant" "content" text}))

(defn add-developer-message
  "Append a {\"role\" \"developer\" \"content\" text} turn — the role
   openai-python's own README uses for instructions on Chat Completions."
  [messages text]
  (conj (vec messages) {"role" "developer" "content" text}))

(defn add-system-message
  "Append a {\"role\" \"system\" \"content\" text} turn — the older spelling of
   `developer`, still accepted by the API."
  [messages text]
  (conj (vec messages) {"role" "system" "content" text}))
