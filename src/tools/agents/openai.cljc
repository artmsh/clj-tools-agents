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

   STREAMING: `responses-stream` / `chat-completions-stream` are
   `client.responses.create(..., stream=True)` /
   `client.chat.completions.create(..., stream=True)`. Each opens the request
   NOW through `request!` (`:as :stream`: same retries, 401 retry and error
   typing, before the first byte only) and returns a single-use reducible
   (tools.agents.stream) of decoded event / chunk maps.
   `accumulate-response-stream` folds Responses events into the final
   Response that `output-text` reads; `accumulate-chat-completion-stream`
   folds chunks into a ChatCompletion that `completion-text` reads;
   `stream-complete?` tells a finished stream from a cut-off one. `:stream
   true` on `responses-create` / `chat-completions-create` / `request!`
   (without `:as :stream`) is still rejected. See docs/openai.md
   'Streaming'."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.retry :as retry]
            [tools.agents.stream :as stream]
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

(def ^:private json-opts
  {:prefix      "tools.agents.openai"
   :encode-type :tools.agents.openai/json-encode-error
   :parse-type  :tools.agents.openai/json-parse-error})

(def ^:private json-codec (json/codec json-opts))

(defn client-codec
  "The JSON codec `client` uses for its wire traffic: the built-in one, or
   the client's injected :json bound to this namespace's error contract
   (tools.agents.json/wrap-codec). A map {:read :write :read-jsonl
   :key->str}, used by every resource namespace built on `request!`. nil
   (no client) gives the built-in codec."
  [client]
  (if-let [j (:json client)]
    (json/wrap-codec j json-opts)
    json-codec))

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

(defn- api-key-fn-source
  "A TokenSource over a zero-arg `:api-key` fn, mirroring openai-python's
   callable `api_key` (openai-python 3.14.0 @d421d7ab):
     - called before EVERY attempt, retries included, never cached:
       `_prepare_options` -> `_refresh_api_key` (`_client.py:671-672,688-690`)
       runs at the top of each retry-loop iteration (`_base_client.py:1052-1054`)
     - never called at construction (`_client.py:253-255,266-274`)
     - a 401 is not retried: `_send_with_auth_retry` returns the response
       unless workload identity is configured (`_client.py:578-579`), and
       `_should_retry` has no 401 branch (`_base_client.py:815`). So
       invalidate! declines and `request!` surfaces the 401 at once.
     - a throwing fn propagates as-is before the request is built.
   Divergence: the SDK type-checks nothing (an empty value drops the
   Authorization header and trips `_validate_headers`' TypeError,
   `_client.py:655-665`; a non-string is f-string formatted). Here a
   non-string or blank return throws :tools.agents.openai/invalid-api-key,
   carrying neither the value nor its type."
  [f]
  (reify token/TokenSource
    (-token [_]
      (let [k (f)]
        (if (and (string? k) (not (str/blank? k)))
          k
          (throw (ex-info "tools.agents.openai: the :api-key fn must return a non-blank String"
                          {:type :tools.agents.openai/invalid-api-key})))))
    (-invalidate [_ _] false)))

(defn- resolve-client-credentials
  "The full credential chain `client` uses: an explicit :credential-source,
   else a zero-arg :api-key fn (wrapped by `api-key-fn-source`), else
   `resolve-credentials` (explicit :api-key > OPENAI_API_KEY > throw).
   Returns {:credential-source src} or {:api-key s}. Refreshable sources
   are never discovered here: workload identity
   (tools.agents.openai.credentials/workload-identity-source) is passed
   explicitly as :credential-source, since openai-python reads no env vars
   for it."
  [opts getenv-fn]
  (cond
    (contains? opts :credential-source)
    (let [src (:credential-source opts)]
      (when-not (token/token-source? src)
        (throw (ex-info (str "tools.agents.openai/client: :credential-source must satisfy "
                             "tools.agents.token/TokenSource, got: " (type src))
                        {:type :tools.agents.openai/invalid-credentials})))
      (when (some? (:api-key opts))
        (throw (ex-info "tools.agents.openai/client: pass either :api-key or :credential-source, not both"
                        {:type :tools.agents.openai/invalid-credentials})))
      {:credential-source src})

    (fn? (:api-key opts))
    {:credential-source (api-key-fn-source (:api-key opts))}

    :else
    (resolve-credentials opts getenv-fn)))

(defn client
  "Build an OpenAIClient record — the 'client object' analogue of Python's
   OpenAI(...) constructor. Resolves credentials eagerly (fails fast with a
   catchable ex-info BEFORE any network request); a callable :api-key or a
   :credential-source is only checked here and called per attempt.

   opts:
     :api-key      explicit API key -> sent as `Authorization: Bearer <key>`
                   (falls back to OPENAI_API_KEY). May be a zero-arg fn
                   returning the key (openai-python's callable `api_key`,
                   e.g. an Entra ID token provider): NOT called here, but
                   before every attempt, retries included, with no caching.
                   A non-string/blank return throws
                   {:type :tools.agents.openai/invalid-api-key}; a 401 is
                   not retried. Stored as :credential-source.
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
                   unwrap still wins.
     :timeout-ms   request deadline in ms, default 600000 (openai-python's
                   `DEFAULT_TIMEOUT` 600 s); nil disables it. Covers the whole
                   response for non-streaming calls and only the wait for the
                   response headers for streams, whose body is never timed
                   (see tools.agents.http). `(assoc client :timeout-ms n)` is
                   the `with_options(timeout=...)` per-call override;
                   `request!` also takes it per request.
     :connect-timeout-ms  connect timeout in ms, default 5000 (the SDK's
                   `connect=5.0`); nil disables it.
                   A timeout is a transport failure: retried like any other,
                   then :tools.agents.openai/api-connection-error with
                   :timeout? true. Anything but nil or a positive number
                   throws {:type :tools.agents.openai/invalid-options}.
     :http         request fn replacing tools.agents.http/request! for every
                   exchange through this client (all resource namespaces and
                   streaming). Same contract as request!: takes its request
                   map, returns {:status :headers :body} for any status,
                   throws only on transport failure; an :as :stream body
                   should be an InputStream (a String or byte[] is accepted).
                   A credential source built separately (e.g.
                   tools.agents.openai.credentials/workload-identity-source)
                   takes its own :http. Anything but a fn throws
                   {:type :tools.agents.openai/invalid-options}.
     :json         JSON codec {:read (fn [String]) :write (fn [value])} for
                   every request body, response, error body and stream event
                   through `request!` (all resource namespaces), batch result
                   files and `webhooks/unwrap` given :client; :read-jsonl is
                   derived. :read must yield string-keyed maps. Anything it
                   throws becomes :json-parse-error / :json-encode-error with
                   the original as cause. Not used by the public
                   read-json/write-json, `batches/batch-input-jsonl` (no
                   client) or workload-identity exchanges. A non-codec throws
                   {:type :tools.agents.openai/invalid-options}."
  ([] (client {}))
  ([opts]
   (when (and (contains? opts :http) (not (http/request-fn? (:http opts))))
     (throw (ex-info (str "tools.agents.openai/client: :http must be a request fn with "
                          "tools.agents.http/request!'s contract, got: " (pr-str (type (:http opts))))
                     {:type :tools.agents.openai/invalid-options :option :http})))
   (when (and (contains? opts :json) (not (json/codec-map? (:json opts))))
     (throw (ex-info (str "tools.agents.openai/client: :json must be a map {:read (fn [s]) :write (fn [v])}, got: "
                          (pr-str (:json opts)))
                     {:type :tools.agents.openai/invalid-options :option :json})))
   (doseq [k [:timeout-ms :connect-timeout-ms]
           :when (and (contains? opts k) (not (http/timeout-option? (get opts k))))]
     (throw (ex-info (str "tools.agents.openai/client: " k " must be nil or a positive number of milliseconds, got: "
                          (pr-str (get opts k)))
                     {:type :tools.agents.openai/invalid-options :option k})))
   (let [creds (resolve-client-credentials opts getenv)
         org   (or (:organization opts) (getenv "OPENAI_ORG_ID"))
         proj  (or (:project opts) (getenv "OPENAI_PROJECT_ID"))
         whsec (if (some? (:webhook-secret opts)) (:webhook-secret opts) (getenv "OPENAI_WEBHOOK_SECRET"))]
     (map->OpenAIClient
      (cond-> (merge {:base-url    (or (:base-url opts) (getenv "OPENAI_BASE_URL") default-base-url)
                      :max-retries (if (number? (:max-retries opts))
                                     (max 0 (long (:max-retries opts)))
                                     default-max-retries)}
                     (http/timeout-opts opts)
                     creds)
        (seq org)  (assoc :organization org)
        (seq proj)    (assoc :project proj)
        (some? whsec) (assoc :webhook-secret whsec)
        (:http opts)  (assoc :http (:http opts))
        (:json opts)  (assoc :json (:json opts)))))))

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
   — the Agents API's error bodies use the exact same shape. The 2-arity
   parses with `codec` (see `client-codec`)."
  ([body] (extract-error-message json-codec body))
  ([codec body]
   (when (seq body)
     (try
       (let [parsed ((:read codec) body)]
         (when (map? parsed)
           (let [err (get parsed "error")]
             (when (map? err)
               (let [msg (get err "message")]
                 (when (string? msg) msg))))))
       (catch Exception _ nil)))))

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
    (if (fn? (:api-key client))
      ;; `(assoc client :api-key f)`: same per-attempt call and validation
      ;; as a fn passed to `client`.
      (token/token! (api-key-fn-source (:api-key client)))
      (or (:api-key client)
        (throw (ex-info (str label ": client has no :api-key — build it via "
                             "tools.agents.openai/client")
                        {:type :tools.agents.openai/missing-credentials}))))))

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
    (throw (ex-info (str label ": :stream true is not supported here — "
                          "use tools.agents.openai/responses-stream or chat-completions-stream for SSE streaming")
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
  "`request!` calls this on EVERY 401, with the credential that attempt
   sent, including a 401 on the auth retry itself: openai-python invalidates
   before checking `retried` (`_client.py:582-584`), so the next call does not
   reuse a token rejected twice. True means the client's :credential-source
   dropped that token and the next attempt may get a fresh one; `request!`
   then retries, once per call, OUTSIDE the :max-retries budget. Static :api-key credentials have
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
  (cond
    (not= as :stream)            resp
    (and status (<= 200 status 299)) (assoc resp :body (http/stream-body body))
    :else (assoc resp :body (with-open [^java.io.InputStream in (http/stream-body body)]
                              (String. (.readAllBytes in) "UTF-8")))))

(defn- decode-success [codec as body]
  (case as
    :json   (when (seq body) ((:read codec) body))
    :string body
    :bytes  body))

(defn request!
  "The one OpenAI transport every resource method uses: tools.agents.openai,
   tools.agents.openai.agents, .embeddings, .realtime and later resource
   namespaces. One call is one logical request: build the URL, encode the
   JSON body ONCE, then loop attempts through the client's :http fn
   (default tools.agents.http/request!),
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
     :timeout-ms / :connect-timeout-ms
                 per-request override of the client's values (nil disables)
                 a distinct :connect-timeout-ms selects a cached shared
                 HttpClient per value, so vary it on the client, not per call

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

   401: with a :credential-source client, every 401 invalidates the token
   that attempt sent (`_client.py:582`); the first also retries once, outside
   :max-retries and without backoff; a second 401 throws. A static :api-key
   401 is never retried."
  ([client req] (request! client "request!" req))
  ([client fn-name {:keys [method path query body multipart headers as]
                    :or   {method :post as :json}
                    :as   req}]
   (let [label (fn-label fn-name)]
     (when-not (contains? #{:json :string :bytes :stream} as)
       (throw (ex-info (str label ": unsupported :as " (pr-str as) " — expected :json, :string, :bytes or :stream")
                       {:type :tools.agents.openai/invalid-request})))
     (when-not (= as :stream) (reject-streaming! label body multipart))
     (let [url         (endpoint-url (:base-url client) path)
           codec       (client-codec client)
           body-str    (when (some? body) ((:write codec) body))
           max-retries (resolve-max-retries client)]
       (retry/with-retries
        {:max-retries           max-retries
         ;; Outside the try: a token fetch failure is not a connection failure.
         :prepare               (fn [_] (attempt-credential label client))
         :attempt               (fn [credential]
                                  (slurp-error-stream as ((or (:http client) http/request!)
                                                          (cond-> (http/with-timeouts
                                                                   {:method  method
                                                                    :url     url
                                                                    :query   query
                                                                    :headers (request-headers credential client (some? multipart) headers)
                                                                    :as      (case as (:bytes :stream) as :string)}
                                                                   client req)
                                                            body-str  (assoc :body body-str)
                                                            multipart (assoc :multipart multipart)))))
         :rethrow?              own-error?
         :unauthorized?         (fn [{:keys [response]}] (= 401 (:status response)))
         ;; Invalidate first, on every 401; retry only once.
         :on-unauthorized       (fn [{:keys [prepared]}] (invalidate-credentials! client prepared))
         :invalidate-every-401? true
         ;; No response at all (DNS, connection refused, TLS handshake,
         ;; timeout): the SDK retries these without consulting _should_retry.
         :retryable?            (fn [{:keys [error response]}]
                                  (or (some? error)
                                      (let [status (:status response)]
                                        (and (not (and status (>= status 200) (< status 300)))
                                             (should-retry? status (:headers response) (now-ms))))))
         :delay                 (fn [{:keys [retries-taken error response]}]
                                  (retry-delay-ms retries-taken (when-not error (:headers response)) (now-ms)))
         :sleep!                #(sleep! %)
         :finish                (fn [{:keys [retries-taken error response]}]
                                  (if error
                                    (throw (ex-info (str label ": " (if (http/timeout-exception? error) "request timed out" "connection failed")
                                                         ": " error)
                                                    (cond-> {:type :tools.agents.openai/api-connection-error :status nil :body nil
                                                             :retries-taken retries-taken}
                                                      (http/timeout-exception? error) (assoc :timeout? true))
                                                    error))
                                    (let [{:keys [status] resp-body :body} response]
                                      (if (and status (>= status 200) (< status 300))
                                        (if (= as :stream) response (decode-success codec as resp-body))
                                        (let [body-s  (body->string resp-body)
                                              err-msg (extract-error-message codec body-s)
                                              detail  (cond err-msg err-msg (seq body-s) body-s :else nil)]
                                          (throw (ex-info (str label ": HTTP " status (when detail (str " " detail)))
                                                          {:type (status->type status) :status status :body body-s
                                                           :retries-taken retries-taken})))))))})))))

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

   :stream true throws immediately; stream with `responses-stream`.

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
   code, exactly as the SDK's own README does. :stream true throws
   immediately; stream with `chat-completions-stream`."
  [client request]
  (post-json! client "chat-completions-create" "/chat/completions" request))

;; ---------------------------------------------------------------------------
;; Public API — streaming
;; ---------------------------------------------------------------------------
;; Wire format: developers.openai.com/api/docs/guides/streaming-responses and
;; .../api/reference/resources/responses/streaming-events. Frame handling is
;; openai-python src/openai/_streaming.py `Stream.__stream__` (@ d421d7a):
;; `data` starting with "[DONE]" ends the stream undecoded, a data object with
;; a truthy top-level "error" raises, and the response is closed in `finally`.

(defn- py-truthy?
  "Python truthiness, for the SDK's `data.get(\"error\")` test."
  [v]
  (cond (or (nil? v) (false? v)) false
        (number? v)              (not (zero? v))
        (or (string? v) (coll? v)) (boolean (seq v))
        :else                    true))

(defn stream-event-error
  "The typed ex-info for an in-stream error event, or nil. Shared by every
   openai stream (responses, chat completions, openai.agents sessions) and
   their accumulators. Two shapes, both :tools.agents.openai/stream-error
   with :status nil, :body the raw data string (nil from a pure
   accumulator), :error the error object and :event the decoded event:
     - any data object with a truthy top-level \"error\" (openai-python
       _streaming.py `Stream.__stream__` raises APIError for it, whatever
       the endpoint: the agents `error` event, AgentSessionErrorEvent, has
       one);
     - the Responses `error` event {\"type\" \"error\" \"code\" \"message\"
       \"param\"}, which has no \"error\" key. openai-python yields that one
       as a ResponseErrorEvent; this client throws it (docs/divergences.md,
       in-stream errors).
   `fn-name` is bare (qualified under tools.agents.openai) or already
   qualified."
  [fn-name event raw]
  (when (map? event)
    (let [label (fn-label fn-name)
          err   (get event "error")
          fail  (fn [msg err]
                  (ex-info (str label ": stream error: "
                                (if (and (string? msg) (seq msg)) msg "An error occurred during streaming"))
                           {:type :tools.agents.openai/stream-error :status nil :body raw :error err
                            :event event}))]
      (cond
        (py-truthy? err)              (fail (when (map? err) (get err "message")) err)
        (= "error" (get event "type")) (fail (get event "message") (select-keys event ["code" "message" "param"]))))))

(defn- open-sse-stream
  "POST `request` with \"stream\" true to `path` and return the event
   reducible. Opening goes through `request!` with `:as :stream` as
   open-event-stream's `:send!`, so per-attempt headers, the retry policy,
   the credential-source 401 retry and `status->type` typing are exactly
   `request!`'s, and all of them happen before the first byte."
  [client fn-name path request]
  (let [label (fn-label fn-name)
        codec (client-codec client)]
    (stream/open-event-stream
     {:request       {:method :post :path path
                      :body   (-> (or request {}) (dissoc :stream) (assoc "stream" true))}
      :send!         (fn [req] (request! client fn-name req))
      ;; request! already throws the typed error for a final non-2xx.
      :on-error      (fn [{:keys [status body]}]
                       (let [msg (extract-error-message codec body)]
                         (throw (ex-info (str label ": HTTP " status (when (or msg (seq body)) (str " " (or msg body))))
                                         {:type (status->type status) :status status :body body}))))
      :on-read-error (fn [e]
                       (ex-info (str label ": connection failed mid-stream: " e)
                                {:type :tools.agents.openai/api-connection-error :status nil :body nil}
                                e))
      :done?         #(str/starts-with? (str (:data %)) "[DONE]")
      :decode        (fn [data]
                       (if (str/blank? data)
                         ::keep-alive
                         (let [event ((:read codec) data)]
                           (when-let [e (stream-event-error fn-name event data)] (throw e))
                           event)))
      :xform         (comp (remove #(= ::keep-alive (:data %)))
                           (map (fn [{:keys [event id data]}]
                                  (if (map? data)
                                    (with-meta data {:tools.agents.sse/event event :tools.agents.sse/id id})
                                    data))))})))

(defn responses-stream
  "POST request (the `responses-create` map) with \"stream\" true to
   {base-url}/responses: openai-python's
   client.responses.create(**params, stream=True).

   The request is sent NOW through `request!` (`:as :stream`): retries per
   the client's policy, the credential-source 401 retry, and a final non-2xx
   or connection failure thrown from this call with the same
   :type/:status/:body/:retries-taken as `responses-create`. Nothing is
   retried once a 2xx arrives.

   Returns a SINGLE-USE reducible (tools.agents.stream/open-event-stream) of
   decoded event maps (string keys; dispatch on \"type\", unknown types pass
   through), each with its SSE frame's name and id as metadata
   :tools.agents.sse/event / :tools.agents.sse/id:

     (let [s (responses-stream client {\"model\" \"gpt-5\" \"input\" \"hi\"})]
       (run! #(when (= \"response.output_text.delta\" (get % \"type\"))
                (print (get % \"delta\")))
             s))
     (output-text (accumulate-response-stream (responses-stream client req)))

   Reducing closes the connection (EOF, early termination, exception); a
   stream never reduced must be released with (tools.agents.stream/close! s),
   which is also the cross-thread cancel.

   While reducing, throws :tools.agents.openai/stream-error for an `error`
   event (or any event with a top-level \"error\" object; ex-data :status
   nil, :body the raw data, :error, :event); events before it reach the
   reducer, nothing after it does, and the connection is closed,
   :tools.agents.openai/api-connection-error for a mid-stream transport
   failure, :tools.agents.openai/json-parse-error for undecodable data.

   The stream ends at EOF (a data: [DONE], if a server sends one, also ends
   it). A cut-off stream reduces like a complete one; check
   `stream-complete?` on the accumulated response."
  [client request]
  (open-sse-stream client "responses-stream" "/responses" request))

(defn chat-completions-stream
  "POST request (the `chat-completions-create` map) with \"stream\" true to
   {base-url}/chat/completions: openai-python's
   client.chat.completions.create(**params, stream=True). Pass
   {\"stream_options\" {\"include_usage\" true}} for a final usage chunk.

   Opening, retries, HTTP errors, lifecycle and in-stream errors as in
   `responses-stream` (message prefix
   \"tools.agents.openai/chat-completions-stream: \").

   Returns a SINGLE-USE reducible of decoded chat.completion.chunk maps.
   `data: [DONE]` ends it without being emitted, and
   (tools.agents.stream/outcome s) is then :done; :eof means the body ended
   without [DONE].

     (let [s (chat-completions-stream client {\"model\" \"gpt-5\" \"messages\" msgs})]
       (run! #(some-> (get-in % [\"choices\" 0 \"delta\" \"content\"]) print) s))
     (completion-text (accumulate-chat-completion-stream
                        (chat-completions-stream client req)))"
  [client request]
  (open-sse-stream client "chat-completions-stream" "/chat/completions" request))

;; ---------------------------------------------------------------------------
;; Stream accumulation — pure
;; ---------------------------------------------------------------------------

(defn stream-complete?
  "True when an accumulated stream result shows the stream ended on purpose;
   read from `result`'s metadata:
     - Responses (`accumulate-response-stream` / `accumulate-response-event`):
       a terminal event was folded (response.completed, response.failed or
       response.incomplete). Works on collections of events too.
     - Chat Completions (`accumulate-chat-completion-stream`): the stream
       itself hit `data: [DONE]` ((tools.agents.stream/outcome s) is :done).
       [DONE] is a transport marker the chunks never carry, so a result
       folded from a plain collection, or from a stream wrapped in an
       eduction, is never complete.
   False for a stream that ended (EOF, early termination, close!) without
   its marker, whose result is the partial response assembled so far."
  [result]
  (true? (::stream-complete? (meta result))))

(def ^:private terminal-response-types
  #{"response.completed" "response.failed" "response.incomplete"})

(def ^:private snapshot-response-types
  #{"response.created" "response.in_progress" "response.queued"})

(defn- assoc-grow
  "assoc at index i, padding with nil when i is past the end."
  [v i x]
  (let [v (if (vector? v) v [])]
    (if (< i (count v)) (assoc v i x) (conj (into v (repeat (- i (count v)) nil)) x))))

(defn- response-items [acc] (or (::items (meta acc)) (sorted-map)))

(defn- with-items
  "acc with output_index -> item `items` kept in metadata and \"output\"
   rebuilt from them in index order."
  [acc items]
  (vary-meta (assoc acc "output" (vec (vals items))) assoc ::items items))

(defn- update-item
  "Apply f to the output item at idx when it exists and (get item \"type\")
   is in `types` (nil: any type)."
  [acc idx types f]
  (let [items (response-items acc)
        item  (when (integer? idx) (get items idx))]
    (if (and (map? item) (or (nil? types) (contains? types (get item "type"))))
      (with-items acc (assoc items idx (f item)))
      acc)))

(defn- update-part
  "Apply f to content part ci of a message item, starting from `default`
   when no content_part.added created it."
  [item ci default f]
  (if (integer? ci)
    (let [content (get item "content")
          part    (get content ci)]
      (assoc item "content" (assoc-grow content ci (f (if (map? part) part default)))))
    item))

(defn accumulate-response-event
  "Reducing fn folding Responses stream events into the final Response map
   (pure). Ported from openai-python lib/streaming/responses/_responses.py
   `ResponseStreamState.accumulate_event` (@ d421d7a):

     - response.completed | response.failed | response.incomplete: the
       result IS that event's \"response\" (a failed response is returned,
       not thrown, as `responses-create` returns one). When its \"output\" is
       null or missing, the items from response.output_item.done are used,
       as the SDK does. Marks the result complete (`stream-complete?`);
       later events are ignored.
     - without a terminal event the result is assembled from the deltas:
       response.created / in_progress / queued give the top-level fields;
       response.output_item.added/done place items by output_index (a null
       item is ignored); content_part.added/done place parts by
       content_index; output_text.delta / refusal.delta append to a message
       part and function_call_arguments.delta to a function_call item's
       \"arguments\"; every *.done replaces the accumulated value. A delta for
       an item never added is ignored (the SDK raises).
     - `error` event, or a top-level \"error\" object:
       throws :tools.agents.openai/stream-error.
     - other event types: ignored.

   \"output\" and message \"content\" are vectors, so `output-text` works on
   partial and final results. Arities: [] -> nil, [acc] -> acc,
   [acc event] -> acc."
  ([] nil)
  ([acc] (when acc (vary-meta acc dissoc ::items ::done-items)))
  ([acc event]
   (when-let [e (stream-event-error "accumulate-response-event" event nil)] (throw e))
   (let [acc (or acc {})
         t   (when (map? event) (get event "type"))
         idx (when (map? event) (get event "output_index"))
         ci  (when (map? event) (get event "content_index"))
         s   (fn [k] (let [v (get event k)] (when (string? v) v)))]
     (cond
       (or (nil? t) (stream-complete? acc)) acc

       (contains? terminal-response-types t)
       (let [resp (get event "response")]
         (if (map? resp)
           (let [done   (::done-items (meta acc))
                 output (cond (vector? (get resp "output")) (get resp "output")
                              (seq done) (vec (vals done))
                              :else (vec (vals (response-items acc))))]
             (with-meta (assoc resp "output" output) (assoc (meta acc) ::stream-complete? true)))
           acc))

       (contains? snapshot-response-types t)
       (let [resp (get event "response")]
         (if (map? resp)
           (let [items (response-items acc)
                 out   (get resp "output")
                 items (if (and (empty? items) (vector? out))
                         (into (sorted-map) (keep-indexed (fn [i x] (when (map? x) [i x]))) out)
                         items)]
             (with-items (merge acc (dissoc resp "output")) items))
           acc))

       :else
       (case t
         ("response.output_item.added" "response.output_item.done")
         (let [item (get event "item")]
           (if (and (map? item) (integer? idx))
             (cond-> (with-items acc (assoc (response-items acc) idx item))
               (= t "response.output_item.done")
               (vary-meta update ::done-items (fnil assoc (sorted-map)) idx item))
             acc))

         ("response.content_part.added" "response.content_part.done")
         (let [part (get event "part")]
           (if (map? part)
             (update-item acc idx nil #(update-part % ci part (constantly part)))
             acc))

         "response.output_text.delta"
         (update-item acc idx #{"message"}
                      #(update-part % ci {"type" "output_text" "text" "" "annotations" []}
                                    (fn [p] (update p "text" str (s "delta")))))

         "response.output_text.done"
         (if-let [text (s "text")]
           (update-item acc idx #{"message"}
                        #(update-part % ci {"type" "output_text" "text" "" "annotations" []}
                                      (fn [p] (assoc p "text" text))))
           acc)

         "response.refusal.delta"
         (update-item acc idx #{"message"}
                      #(update-part % ci {"type" "refusal" "refusal" ""}
                                    (fn [p] (update p "refusal" str (s "delta")))))

         "response.refusal.done"
         (if-let [refusal (s "refusal")]
           (update-item acc idx #{"message"}
                        #(update-part % ci {"type" "refusal" "refusal" ""}
                                      (fn [p] (assoc p "refusal" refusal))))
           acc)

         "response.function_call_arguments.delta"
         (update-item acc idx #{"function_call"} #(update % "arguments" str (s "delta")))

         "response.function_call_arguments.done"
         (if-let [args (s "arguments")]
           (update-item acc idx #{"function_call"} #(assoc % "arguments" args))
           acc)

         acc)))))

(defn accumulate-response-stream
  "Reduce `events` (a `responses-stream` reducible, consumed and closed, or
   any collection of event maps) with `accumulate-response-event`. Returns
   the Response map, or nil for no events. Never throws on truncation; check
   `stream-complete?`."
  [events]
  (transduce identity accumulate-response-event events))

;; Chat Completions: openai-python lib/streaming/chat/_completions.py
;; `ChatCompletionStreamState._accumulate_chunk` and
;; `_convert_initial_chunk_into_snapshot`, with lib/streaming/_deltas.py
;; `accumulate_delta` (@ d421d7a).

(defn- indexed-entries?
  "_deltas.py `_has_indexed_entries`: a list with a dict carrying \"index\"."
  [v]
  (boolean (and (sequential? v) (some #(and (map? %) (contains? % "index")) v))))

(declare accumulate-delta)

(defn- merge-indexed-entry
  "One entry of an indexed list delta (e.g. tool_calls): merged into the
   entry at its \"index\", or appended when there is none yet."
  [v entry]
  (let [bad (fn [msg] (ex-info (str "tools.agents.openai/accumulate-chat-completion-chunk: " msg)
                               {:type :tools.agents.openai/invalid-response :status nil :body nil}))]
    (when-not (map? entry)
      (throw (bad (str "list delta entry is not an object: " (pr-str entry)))))
    (let [i (get entry "index")]
      (when-not (and (integer? i) (not (neg? i)))
        (throw (bad (str "list delta entry has no non-negative integer \"index\": " (pr-str entry)))))
      (if (< i (count v))
        (let [existing (nth v i)]
          (when-not (map? existing)
            (throw (bad (str "list entry at index " i " is not an object"))))
          (assoc v i (accumulate-delta existing entry)))
        (conj v entry)))))

(defn- accumulate-delta
  "_deltas.py `accumulate_delta`: merge a delta object into acc. An absent or
   null value is set (an indexed list starts from []); \"index\" and \"type\"
   are replaced; strings concatenate, numbers add, objects merge
   recursively; a list of scalars is extended; an indexed list merges entry
   by \"index\". Any other combination keeps acc's value."
  [acc delta]
  (if-not (map? delta)
    acc
    (reduce-kv
     (fn [acc k dv]
       (let [av (get acc k)]
         (cond
           (and (nil? av) (not (indexed-entries? dv))) (assoc acc k dv)
           (contains? #{"index" "type"} k)            (assoc acc k dv)
           :else
           (let [av (if (nil? av) [] av)]
             (cond
               (and (string? av) (string? dv)) (assoc acc k (str av dv))
               (and (number? av) (number? dv)) (assoc acc k (+ av dv))
               (and (map? av) (map? dv))       (assoc acc k (accumulate-delta av dv))
               (and (sequential? av) (sequential? dv))
               (if (and (every? #(or (string? %) (number? %)) av)
                        (or (seq av) (not (indexed-entries? dv))))
                 (assoc acc k (into (vec av) dv))
                 (assoc acc k (reduce merge-indexed-entry (vec av) dv)))
               :else (assoc acc k av))))))
     (if (map? acc) acc {})
     delta)))

(defn- choice-index [choice]
  (let [i (get choice "index")] (if (integer? i) i 0)))

(defn- choice-snapshot
  "A chunk choice as a completion choice: its fields minus \"delta\", plus
   \"message\" accumulated from the delta."
  [choice]
  (assoc (dissoc choice "delta") "message" (accumulate-delta {} (get choice "delta"))))

(defn- merge-logprobs
  "_accumulate_chunk's logprobs rule: the first non-null logprobs object is
   taken as {content, refusal}; later non-empty content/refusal arrays are
   appended."
  [choice lp]
  (if (nil? (get choice "logprobs"))
    (assoc choice "logprobs" {"content" (get lp "content") "refusal" (get lp "refusal")})
    (cond-> choice
      (py-truthy? (get lp "content")) (update-in ["logprobs" "content"] (fnil into []) (get lp "content"))
      (py-truthy? (get lp "refusal")) (update-in ["logprobs" "refusal"] (fnil into []) (get lp "refusal")))))

(defn- fold-choice [choices choice]
  (let [idx (choice-index choice)
        pos (first (keep-indexed (fn [i c] (when (= idx (choice-index c)) i)) choices))]
    (if (nil? pos)
      ;; A choice first seen now is built whole from this chunk (finish_reason
      ;; and logprobs included), so its logprobs are not appended twice.
      (vec (sort-by choice-index (conj choices (choice-snapshot choice))))
      (let [fr (get choice "finish_reason")
            lp (get choice "logprobs")]
        (cond-> (update-in choices [pos "message"] accumulate-delta (get choice "delta"))
          (py-truthy? fr) (assoc-in [pos "finish_reason"] fr)
          (some? lp)      (update pos merge-logprobs lp))))))

(defn accumulate-chat-completion-chunk
  "Reducing fn folding chat.completion.chunk maps into one ChatCompletion
   map (pure), a port of openai-python's `ChatCompletionStreamState`:

     - the first chunk seeds the completion: its fields (minus
       \"obfuscation\"), \"object\" \"chat.completion\", \"system_fingerprint\"
       defaulting to null;
     - choices are merged by \"index\" into a vector sorted by index. The
       delta merges into \"message\" (`accumulate_delta`): role and other
       scalars set once, content and refusal concatenated, tool_calls merged
       by their own \"index\" (id, type and function.name set, then
       function.arguments concatenated); a truthy finish_reason replaces;
       logprobs.content / .refusal arrays are appended;
     - \"usage\" and \"system_fingerprint\" are taken from every chunk, so the
       stream_options.include_usage chunk (empty choices, sent last) sets
       usage;
     - a chunk whose \"object\" is not \"chat.completion.chunk\" (e.g. Azure's
       asynchronous content-filter events) is skipped, as the SDK does;
     - a chunk with a top-level \"error\" object throws
       :tools.agents.openai/stream-error.

   `completion-text` works on the result. Arities: [] -> nil, [acc] -> acc,
   [acc chunk] -> acc."
  ([] nil)
  ([acc] acc)
  ([acc chunk]
   (when-let [e (stream-event-error "accumulate-chat-completion-chunk" chunk nil)] (throw e))
   (cond
     (not (and (map? chunk) (= "chat.completion.chunk" (get chunk "object"))))
     acc

     (nil? acc)
     (merge {"system_fingerprint" nil}
            (-> chunk
                (dissoc "obfuscation")
                (assoc "object" "chat.completion"
                       "choices" (reduce fold-choice [] (filter map? (get chunk "choices"))))))

     :else
     (-> acc
         (update "choices" #(reduce fold-choice (if (vector? %) % []) (filter map? (get chunk "choices"))))
         (assoc "usage" (get chunk "usage")
                "system_fingerprint" (get chunk "system_fingerprint"))))))

(defn accumulate-chat-completion-stream
  "Reduce `chunks` — a `chat-completions-stream` reducible (consumed and
   closed) or any collection of chunk maps — with
   `accumulate-chat-completion-chunk`. Returns the ChatCompletion map, or nil
   for no chunks. Given the stream itself, the result's metadata records
   whether it ended at `data: [DONE]` (see `stream-complete?`). Never throws
   on truncation."
  [chunks]
  (let [r (transduce identity accumulate-chat-completion-chunk chunks)]
    (if (and (some? r) (satisfies? stream/EventStream chunks))
      (vary-meta r assoc ::stream-complete? (= :done (stream/outcome chunks)))
      r)))

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
