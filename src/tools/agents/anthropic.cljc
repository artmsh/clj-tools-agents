(ns tools.agents.anthropic
  "A pure-Clojure client for Anthropic's Messages API, ergonomically modeled on
   the official anthropic-sdk-python client (Anthropic(...) constructor,
   client.messages.create(**params) resource method, typed error hierarchy).

   Runs unmodified on JVM Clojure and Babashka. The 'client object' here is an
   `AnthropicClient` record built by `client`; it retains Clojure's map-style
   keyword lookup and immutable update semantics on both runtimes.

   PORTABILITY: all network I/O goes through `tools.agents.http/request!`,
   the shared HTTP request function, which needs no reader
   conditional, or through the fn a caller injects as the client's `:http`. Everything else — URL/header building, the hand-rolled JSON codec,
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

   STREAMING: `messages-stream` is anthropic-sdk-python's
   `client.messages.create(..., stream=True)`: it POSTs /v1/messages with
   \"stream\": true and returns a single-use reducible (tools.agents.stream)
   of decoded stream events; retries cover only the opening request.
   `accumulate-event`/`accumulate-stream` rebuild the final Message exactly
   as the SDK's `accumulate_event`, and `stream-complete?` tells a finished
   stream (message_stop seen) from a truncated one; nothing throws on
   truncation. `messages-create` still rejects :stream true, pointing at
   `messages-stream`. See docs/anthropic.md, section \"Streaming\"."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.retry :as retry]
            [tools.agents.stream :as stream]
            [tools.agents.token :as token]
            [tools.agents.anthropic.credentials :as credentials]))

(def default-base-url "https://api.anthropic.com")
(def ^:private anthropic-version "2023-06-01")
(def ^:private oauth-beta-header "oauth-2025-04-20")

;; anthropic-sdk-python's and anthropic-sdk-typescript's shared default —
;; DEFAULT_MAX_RETRIES = 2 in both SDKs.
(def default-max-retries 2)

;; Only keys present on every resolved client are record fields. Credentials
;; (:api-key, :auth-token or :credential-source) are mutually exclusive and
;; :betas is optional, so map->AnthropicClient keeps
;; them in the record's extension map and preserves the old `contains?` shape.
(defrecord AnthropicClient [base-url max-retries])

;; ---------------------------------------------------------------------------
;; JSON codec — tools.agents.json, bound to this namespace's error contract.
;; ---------------------------------------------------------------------------

(def ^:private json-opts
  {:prefix      "tools.agents.anthropic"
   :encode-type :tools.agents.anthropic.error/json-encode
   :parse-type  :tools.agents.anthropic.error/json-parse})

(def ^:private json-codec (json/codec json-opts))

(defn client-codec
  "The JSON codec `client` uses for its wire traffic: the built-in one, or
   the client's injected :json bound to this namespace's error contract
   (tools.agents.json/wrap-codec). A map {:read :write :read-jsonl
   :key->str}; tools.agents.anthropic.batches decodes results with it."
  [client]
  (if-let [j (:json client)]
    (json/wrap-codec j json-opts)
    json-codec))

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

(defn read-jsonl
  "Decode JSON Lines from a String or java.io.Reader into a LAZY seq
   (tools.agents.json/read-jsonl). Blank lines are skipped. A malformed line
   throws, when realized, ex-info {:type :tools.agents.anthropic.error/json-parse
   :line n} with message prefix `tools.agents.anthropic/read-jsonl: line n: `."
  [src]
  ((:read-jsonl json-codec) src))

;; ---------------------------------------------------------------------------
;; Process boundary — `getenv` is the env-var seam. Network I/O is
;; tools.agents.http/request!, called from post-json! below.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  (System/getenv name))

(defn- user-home
  "Seam for the profile chain's default config dir (~/.config/anthropic)."
  []
  (System/getProperty "user.home"))

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

(defn- resolve-client-credentials
  "The full credential chain `client` uses, first match wins
   (anthropic-sdk-python v1.5.0 _client.py:186-208, lib/credentials/_chain.py:76-155):

     1. explicit :profile or :credential-source, else explicit :api-key >
        :auth-token
     2. ANTHROPIC_API_KEY > ANTHROPIC_AUTH_TOKEN
     3. explicit profile (ANTHROPIC_PROFILE / ANTHROPIC_CONFIG_DIR /
        active_config pointer; credentials/profile-from-env), errors propagate
     4. workload identity federation env vars
        (tools.agents.anthropic.credentials/workload-identity-from-env)
     5. fallback on-disk profile (credentials/fallback-profile), errors
        swallowed
     then `resolve-credentials` throws missing-credentials.

   Steps 1-2 are exactly `resolve-credentials`, unchanged. Returns
   {:credential-source src}, {:api-key s} or {:auth-token s}; a profile adds
   :credential-headers (non-empty only) and :profile-base-url (when the
   config sets base_url). base-url is the client's resolved base URL: a
   token exchange must hit the same deployment as the API calls
   (_chain.py:72). http-fn performs the exchange
   (tools.agents.http/request! in production)."
  ([opts getenv-fn]
   (resolve-client-credentials opts getenv-fn default-base-url http/request!))
  ([opts getenv-fn base-url http-fn]
   (let [profile-creds (fn [{:keys [credential-source credential-headers] :as p}]
                         (cond-> {:credential-source credential-source}
                           (seq credential-headers) (assoc :credential-headers credential-headers)
                           (:base-url p)            (assoc :profile-base-url (:base-url p))))
         profile-opts  {:getenv getenv-fn :home (user-home) :base-url base-url :http-fn http-fn}]
   (cond
     ;; Step 1, SDK `profile=` (_client.py:260-267).
     (some? (:profile opts))
     (do
       (when (or (some? (:api-key opts)) (some? (:auth-token opts)) (contains? opts :credential-source))
         (throw (ex-info (str "tools.agents.anthropic/client: pass only one of :api-key, :auth-token, "
                              ":credential-source or :profile")
                         {:type :tools.agents.anthropic.error/invalid-credentials})))
       (profile-creds (credentials/profile-source (assoc profile-opts :profile (:profile opts)))))

     (contains? opts :credential-source)
     (let [src (:credential-source opts)]
       (when-not (token/token-source? src)
         (throw (ex-info (str "tools.agents.anthropic/client: :credential-source must satisfy "
                              "tools.agents.token/TokenSource, got: " (type src))
                         {:type :tools.agents.anthropic.error/invalid-credentials})))
       (when (or (some? (:api-key opts)) (some? (:auth-token opts)))
         (throw (ex-info (str "tools.agents.anthropic/client: pass only one of :api-key, :auth-token "
                              "or :credential-source")
                         {:type :tools.agents.anthropic.error/invalid-credentials})))
       {:credential-source src})

     ;; Steps 1-2: explicit args, then ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN.
     (or (:api-key opts) (:auth-token opts)
         (seq (getenv-fn "ANTHROPIC_API_KEY")) (seq (getenv-fn "ANTHROPIC_AUTH_TOKEN")))
     (resolve-credentials opts getenv-fn)

     :else
     (or
      ;; Step 3: explicit profile selection. Errors propagate
      ;; (_chain.py:116-129).
      (some-> (credentials/profile-from-env profile-opts) profile-creds)

      ;; Step 4: WIF env vars. Sits above the fallback profile so a leftover
      ;; default profile never beats WIF (_chain.py:131-136).
      (when-let [src (credentials/workload-identity-from-env
                      {:getenv getenv-fn :base-url base-url :http-fn http-fn})]
        {:credential-source src})

      ;; Step 5: fallback active profile from disk. Errors are swallowed and
      ;; the chain falls through (_chain.py:138-153).
      (some-> (credentials/fallback-profile profile-opts) profile-creds)

      (resolve-credentials opts getenv-fn))))))

(defn client
  "Build an AnthropicClient record — the 'client object' analogue of Python's
   Anthropic(...) constructor. Resolves credentials eagerly (fails fast with
   a catchable ex-info BEFORE any network request, matching the original
   builtin's contract) unless :api-key/:auth-token/env vars are present.
   Chain: :profile | :credential-source | :api-key | :auth-token >
   ANTHROPIC_API_KEY > ANTHROPIC_AUTH_TOKEN > explicit profile
   (ANTHROPIC_PROFILE / ANTHROPIC_CONFIG_DIR / active_config) > workload
   identity federation env vars > ~/.config/anthropic/configs/default.json >
   throw. Profiles and WIF are refreshable sources, see
   tools.agents.anthropic.credentials. See resolve-client-credentials.

   opts:
     :profile      profile name under the Anthropic config dir (SDK
                   `profile=`); replaces the other credentials and the env
                   chain. Its config's base_url is used when neither
                   :base-url nor ANTHROPIC_BASE_URL is set, and its
                   workspace_id is sent as `anthropic-workspace-id`.
     :api-key      explicit API key -> sent as `x-api-key`
     :auth-token   explicit bearer/OAuth token -> sent as `Authorization: Bearer`
                   (+ the required `anthropic-beta: oauth-2025-04-20` header)
     :credential-source  a tools.agents.token/TokenSource (e.g.
                   `tools.agents.token/token-cache`) asked for a token before
                   every attempt, sent like :auth-token (Bearer + oauth beta).
                   Replaces :api-key/:auth-token and the env vars; combining
                   it with either throws
                   {:type :tools.agents.anthropic.error/invalid-credentials}.
                   A 401 invalidates the token and retries once outside
                   :max-retries.
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
                   See messages-create's retry note.
     :timeout-ms   request deadline in ms, default 600000
                   (anthropic-sdk-python's `DEFAULT_TIMEOUT` 600 s); nil
                   disables it. Covers the whole response for non-streaming
                   calls and only the wait for the response headers for
                   `messages-stream`, whose body is never timed (see
                   tools.agents.http). Override per call with
                   `(assoc client :timeout-ms n)`, or per request in
                   `request!`'s opts.
     :connect-timeout-ms  connect timeout in ms, default 5000 (the SDK's
                   `connect=5.0`); nil disables it.
                   A timeout is a connection failure: retried, then
                   :tools.agents.anthropic.error/api-connection with
                   :timeout? true. Not used by the profile / workload-identity
                   token exchanges, which keep their own 30 s timeout.
                   Anything but nil or a positive number throws
                   {:type :tools.agents.anthropic.error/invalid-options}.
     :http         request fn replacing tools.agents.http/request! for every
                   exchange this client makes: API calls, streaming, batches
                   and the profile / workload-identity token exchanges
                   resolved here. Same contract as request!: takes its
                   request map ({:method :url :headers :body :query :as
                   :timeout-ms}), returns {:status :headers :body} for any
                   status, throws only on transport failure. With :as
                   :stream the body should be an InputStream (a String or
                   byte[] is accepted). Anything but a fn throws
                   {:type :tools.agents.anthropic.error/invalid-options}.
     :json         JSON codec {:read (fn [String]) :write (fn [value])} used
                   for request bodies, responses, error bodies, stream
                   events and batch results (:read-jsonl is derived). :read
                   must yield string-keyed maps. Anything it throws becomes
                   :json-parse / :json-encode with the original as cause.
                   Not used by accumulate-event's tool-input parsing, the
                   public read-json/write-json, visualize, or credential
                   files and token exchanges. A non-codec throws
                   {:type :tools.agents.anthropic.error/invalid-options}."
  ([] (client {}))
  ([opts]
   (let [explicit    (or (:base-url opts) (getenv "ANTHROPIC_BASE_URL"))
         http-opt    (:http opts)
         _           (when (and (contains? opts :http) (not (http/request-fn? http-opt)))
                       (throw (ex-info (str "tools.agents.anthropic/client: :http must be a request fn "
                                            "with tools.agents.http/request!'s contract, got: "
                                            (pr-str (type http-opt)))
                                       {:type :tools.agents.anthropic.error/invalid-options :option :http})))
         _           (when (and (contains? opts :json) (not (json/codec-map? (:json opts))))
                       (throw (ex-info (str "tools.agents.anthropic/client: :json must be a map "
                                            "{:read (fn [s]) :write (fn [v])}, got: " (pr-str (:json opts)))
                                       {:type :tools.agents.anthropic.error/invalid-options :option :json})))
         _           (doseq [k [:timeout-ms :connect-timeout-ms]
                             :when (and (contains? opts k) (not (http/timeout-option? (get opts k))))]
                       (throw (ex-info (str "tools.agents.anthropic/client: " k " must be nil or a positive "
                                            "number of milliseconds, got: " (pr-str (get opts k)))
                                       {:type :tools.agents.anthropic.error/invalid-options :option k})))
         creds       (resolve-client-credentials opts getenv (or explicit default-base-url)
                                                 (or http-opt http/request!))
         ;; kwarg > ANTHROPIC_BASE_URL > profile base_url > default (_client.py:231-240,260-274)
         base-url    (or explicit (:profile-base-url creds) default-base-url)
         creds       (dissoc creds :profile-base-url)
         max-retries (or (:max-retries opts) default-max-retries)]
     (when-not (and (integer? max-retries) (>= max-retries 0))
       (throw (ex-info (str "tools.agents.anthropic/client: :max-retries must be a non-negative "
                             "integer, got: " (pr-str max-retries))
                        {:type :tools.agents.anthropic.error/invalid-max-retries})))
     (map->AnthropicClient
      (cond-> (merge {:base-url base-url
                      :max-retries max-retries}
                     (http/timeout-opts opts)
                     creds)
        (seq (:betas opts)) (assoc :betas (vec (:betas opts)))
        http-opt            (assoc :http http-opt)
        (:json opts)        (assoc :json (:json opts)))))))

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
  [codec body]
  (when (seq body)
    (try
      (let [parsed ((:read codec) body)]
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
   count-tokens are both just attempt-fn callers around this.

   opts (3-arity):
     :on-unauthorized  (fn [] boolean), called on the first 401 only. Truthy
                       means the credential was invalidated: attempt-fn runs
                       once more at once, without backoff and without
                       consuming a retry. A second 401, or a falsy return,
                       throws. Absent (the 2-arity), a 401 is never retried."
  ([max-retries attempt-fn] (request-with-retries! max-retries attempt-fn nil))
  ([max-retries attempt-fn {:keys [on-unauthorized]}]
   (retry/with-retries
    {:max-retries     max-retries
     :attempt         (fn [_] (attempt-fn))
     :unauthorized?   (fn [{:keys [error]}] (= 401 (:status (ex-data error))))
     :on-unauthorized (when on-unauthorized (fn [_] (on-unauthorized)))
     :retryable?      (fn [{:keys [error]}]
                        (let [data (ex-data error)]
                          (and error
                               (or (= (:type data) :tools.agents.anthropic.error/api-connection)
                                   (retryable-status? (:status data))))))
     :delay           (fn [{:keys [retries-taken error]}]
                        (backoff-seconds retries-taken (parse-retry-after (:headers (ex-data error)))))
     :sleep!          #(*sleep-fn* %)})))

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

(defn- bearer-headers [client token]
  {"authorization" (str "Bearer " token)
   "anthropic-version" anthropic-version
   "anthropic-beta" (beta-header-value oauth-beta-header (:betas client))
   "content-type" "application/json"})

(defn- auth-headers
  "Headers for ONE attempt. A :credential-source client fetches its token
   here (a fetch failure propagates as-is) and reports it via `on-token`, so
   a 401 can invalidate exactly the token that attempt sent."
  ([client] (auth-headers client (fn [_])))
  ([client on-token]
   (merge
    ;; A profile's anthropic-workspace-id (_chain.py:124-128), under the auth headers.
    (:credential-headers client)
   (cond
     (:api-key client)
     (cond-> {"x-api-key" (:api-key client)
              "anthropic-version" anthropic-version
              "content-type" "application/json"}
       (seq (:betas client)) (assoc "anthropic-beta" (beta-header-value nil (:betas client))))

     (:auth-token client)
     (bearer-headers client (:auth-token client))

     (:credential-source client)
     (let [tok (token/token! (:credential-source client))]
       (on-token tok)
       (bearer-headers client tok))

     :else
     (throw (ex-info (str "tools.agents.anthropic: client has neither :api-key nor "
                           ":auth-token — build it via tools.agents.anthropic/client")
                      {:type :tools.agents.anthropic.error/missing-credentials}))))))

(defn- send-http!
  "One exchange for req through the client's :http fn (default
   tools.agents.http/request!), classifying a genuine
   transport failure (DNS/refused/TLS/timeout — no response at all) as
   :tools.agents.anthropic.error/api-connection. caller-name (e.g.
   \"tools.agents.anthropic/messages-create\") prefixes that error's message.
   A timeout adds :timeout? true; the transport exception is the cause.
   An already-typed tools.agents.anthropic.error/* ex-info surfaces verbatim
   instead — those are permanent, not connection failures.

   req gets the client's :timeout-ms / :connect-timeout-ms unless it already
   carries them (a per-request override)."
  [client caller-name req]
  (try
    ((or (:http client) http/request!) (http/with-timeouts req client req))
    (catch Exception e
      (let [data (ex-data e)]
        (if (and data (keyword? (:type data)) (= "tools.agents.anthropic.error" (namespace (:type data))))
          (throw e)
          (let [timeout? (http/timeout-exception? e)]
            (throw (ex-info (str caller-name ": " (if timeout? "request timed out" "connection failed") ": " (str e))
                            (cond-> {:type :tools.agents.anthropic.error/api-connection :status nil :body nil}
                              timeout? (assoc :timeout? true))
                            e))))))))

(defn- post-json!
  "POST body-str to url with headers — send-http! with :method :post."
  [client caller-name url headers body-str]
  (send-http! client caller-name {:method :post :url url :headers headers :body body-str}))

(defn- decode-or-throw!
  "Shared response handling for every request: 2xx decodes the body and
   attaches resp's :headers as metadata (see request-id below) — or, with
   as :response, returns resp itself untouched — anything else throws a typed
   ex-info with :status/:body/:headers in ex-data (:headers feeds
   request-with-retries!'s Retry-After handling)."
  ([codec caller-name resp] (decode-or-throw! codec caller-name resp :json))
  ([codec caller-name resp as]
   (let [status    (:status resp)
         resp-body (:body resp)]
     (if (and status (>= status 200) (< status 300))
       (if (= as :response)
         resp
         (with-meta ((:read codec) resp-body) {::headers (:headers resp)}))
       (let [err-msg (extract-error-message codec resp-body)
             detail  (cond err-msg err-msg (seq resp-body) resp-body :else nil)]
         (throw (ex-info (str caller-name ": HTTP " status (when detail (str " " detail)))
                          {:type (status->type status) :status status :body resp-body :headers (:headers resp)})))))))

(defn- attempt-request!
  "One HTTP round trip: build headers, POST body-str to url, decode-or-throw!
   the response. Shared by messages-create/count-tokens.

   headers-fn is called at the start of EVERY attempt, so credentials that
   change between attempts (a refreshed token) land on the retry. url and
   body-str are computed ONCE by the caller before entering
   request-with-retries! — re-serializing the whole request via write-json
   on every retry would be wasted work identical across attempts."
  [client caller-name url headers-fn body-str]
  (let [headers (headers-fn)]
    (decode-or-throw! (client-codec client) caller-name (post-json! client caller-name url headers body-str))))

(defn- post-request!
  "Shared body of every resource method: build the URL, encode the request,
   POST it under the client's retry policy with headers rebuilt per attempt.
   A :credential-source client's first 401 invalidates the token it sent and
   retries once outside :max-retries; static credentials never retry a 401.
   caller-name prefixes any error message; path is appended to the client's
   base-url."
  [client caller-name path request]
  (let [url        (api-url (:base-url client) path)
        src        (:credential-source client)
        used-token (volatile! nil)
        headers-fn #(auth-headers client (fn [tok] (vreset! used-token tok)))
        body-str   ((:write (client-codec client)) request)]
    (request-with-retries! (or (:max-retries client) default-max-retries)
                           #(attempt-request! client caller-name url headers-fn body-str)
                           (when src
                             {:on-unauthorized #(boolean (token/invalidate! src @used-token))}))))

(defn request!
  "The general request function every Anthropic resource method can build on
   (tools.agents.anthropic.batches uses it): one API call under client's
   retry policy (request-with-retries!), with auth headers rebuilt on EVERY
   attempt and non-2xx responses typed exactly as messages-create's (see the
   error-hierarchy table in docs/anthropic.md).

   caller-name prefixes every error message, e.g.
   \"tools.agents.anthropic.batches/batches-list\". opts:
     :method   :get | :post | :delete ..., default :post
     :path     API path starting with \"/\", appended to client's :base-url
     :query    optional query-params map (tools.agents.http/encode-params)
     :body     optional request map, JSON-encoded ONCE before the retry loop;
               nil sends no body
     :headers  optional extra headers (lower-case string names), merged over
               the auth headers
     :as       :json (default) -> the decoded 2xx body with response headers
               as metadata (request-id works on it);
               :response -> the 2xx response map {:status :headers :body} with
               :body an undecoded String
     :timeout-ms / :connect-timeout-ms
               per-request override of the client's values (nil disables)
               a distinct :connect-timeout-ms selects a cached shared
               HttpClient per value, so vary it on the client, not per call

   `content-type: application/json` is sent on bodyless requests too, as
   anthropic-sdk-python's default_headers do."
  [client caller-name {:keys [method path query body headers as] :or {method :post as :json} :as opts}]
  (let [url        (api-url (:base-url client) path)
        codec      (client-codec client)
        body-str   (when (some? body) ((:write codec) body))
        src        (:credential-source client)
        used-token (volatile! nil)]
    (request-with-retries!
     (or (:max-retries client) default-max-retries)
     (fn []
       (let [auth (auth-headers client (fn [tok] (vreset! used-token tok)))]
         (decode-or-throw! codec caller-name
                           (send-http! client caller-name
                                       (merge (select-keys opts [:timeout-ms :connect-timeout-ms])
                                              {:method method :url url :query query
                                               :headers (merge auth headers)
                                               :body body-str}))
                           as)))
     (when src
       {:on-unauthorized #(boolean (token/invalidate! src @used-token))}))))

(defn messages-create
  "POST request (a plain map, passed through to JSON almost verbatim — model,
   max_tokens, messages, system, temperature, stop_sequences, thinking, tools,
   etc. pass straight through untouched, matching the original builtin's
   data-transparency contract) to POST /v1/messages against client's
   base-url. Returns the decoded response map (string keys), with the raw
   response headers attached as metadata — see `request-id` below for the
   Python SDK's `message._request_id` equivalent.

   :stream true throws :streaming-unsupported immediately — use
   `messages-stream` instead (see the ns docstring's STREAMING note).

   Retries client's :max-retries times (default 2, see `client`) on
   408/409/429/5xx and connection failures, with exponential backoff honoring
   a Retry-After header — see request-with-retries!. Permanent refusals
   (:streaming-unsupported, 4xx other than 408/409/429)
   are never retried, except a :credential-source client's first 401,
   retried once after invalidating its token (see post-request!).

   Throws ex-info on any failure, message prefixed
   \"tools.agents.anthropic/messages-create: \", ex-data
   {:type <keyword — see the error-hierarchy table in docs/anthropic.md> :status
   <http-status-or-nil> :body <raw-response-body-or-nil> :headers
   <response-headers-map-or-nil>}."
  [client request]
  (when (or (true? (get request :stream)) (true? (get request "stream")))
    (throw (ex-info (str "tools.agents.anthropic/messages-create: :stream true is not supported — "
                          "use tools.agents.anthropic/messages-stream. See docs/anthropic.md.")
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

;; ---------------------------------------------------------------------------
;; Public API — streaming
;; ---------------------------------------------------------------------------

(defn- stream-error-type
  "Keyword for an in-stream `error` event's error.type, following the
   error-type table of Anthropic's errors docs (overloaded_error is HTTP 529).
   A DIVERGENCE from anthropic-sdk-python, whose `_make_status_error`
   dispatches on the HTTP status only, which is 200 for a stream, so every
   in-stream error is a plain APIStatusError there."
  [error-type]
  (case error-type
    "invalid_request_error" :tools.agents.anthropic.error/bad-request
    "authentication_error"  :tools.agents.anthropic.error/authentication
    "permission_error"      :tools.agents.anthropic.error/permission-denied
    "not_found_error"       :tools.agents.anthropic.error/not-found
    "rate_limit_error"      :tools.agents.anthropic.error/rate-limit
    "api_error"             :tools.agents.anthropic.error/internal-server
    "overloaded_error"      :tools.agents.anthropic.error/overloaded
    :tools.agents.anthropic.error/api-status))

(defn- error-event? [ev]
  (and (map? ev) (= "error" (get ev "type"))))

(defn- stream-error
  "Typed ex-info for a decoded `error` event
   {\"type\" \"error\" \"error\" {\"type\" t \"message\" m}}: ex-data
   :error the error object, :event the decoded event (nil when its data
   was not JSON)."
  [caller-name ev body headers]
  (let [err  (get ev "error")
        et   (when (map? err) (get err "type"))
        msg  (when (map? err) (get err "message"))]
    (ex-info (str caller-name ": stream error"
                  (when (string? et) (str " " et))
                  (when (string? msg) (str " " msg)))
             {:type (stream-error-type et) :status nil :body body :headers headers
              :error-type et :error err :event ev})))

(defn messages-stream
  "POST request (same map as messages-create) with \"stream\" true to
   POST /v1/messages — anthropic-sdk-python's
   client.messages.create(..., stream=True).

   The request is sent NOW, under messages-create's retry policy
   (request-with-retries!: 408/409/429/5xx and connection failures,
   :max-retries, Retry-After, *sleep-fn*; a :credential-source client's first
   401 invalidates the token and retries once outside :max-retries), with
   auth and `anthropic-beta` headers rebuilt per attempt. Retries cover the
   opening exchange only; once a 2xx arrives nothing is retried. A final
   non-2xx or connection failure throws from this call with messages-create's
   :type/:status/:body/:headers ex-data.

   Returns a SINGLE-USE reducible (tools.agents.stream/open-event-stream) of
   decoded event maps (string keys): message_start, content_block_start,
   content_block_delta, content_block_stop, message_delta, message_stop,
   ping, and any event type added later, all passed through verbatim:

     (let [s (messages-stream client req)]
       (run! #(some-> (get-in % [\"delta\" \"text\"]) print) s))
     (let [m (accumulate-stream (messages-stream client req))]
       (when (stream-complete? m) (output-text m)))

   Reducing closes the connection (EOF, early termination, exception). A
   stream that is never reduced must be released with
   (tools.agents.stream/close! s), which is also the cross-thread cancel;
   `.close`/with-open work on the JVM only. (tools.agents.stream/response s)
   gives the 2xx {:status :headers}.

   While reducing, throws:
     - an `error` event (SSE event name `error`, as the SDK's Stream checks,
       or data \"type\" \"error\") — ex-info typed from error.type (overloaded_error ->
       :overloaded, rate_limit_error -> :rate-limit, api_error ->
       :internal-server, invalid_request_error -> :bad-request, ...; see
       docs/anthropic.md), :status nil, :body the raw data string, :error-type
       the wire error.type, :error the error object, :event the decoded event
       (nil for non-JSON data, which still throws, as in the SDK); events
       before it reach the reducer, nothing after it does, and the
       connection is closed;
     - a mid-stream transport failure — :api-connection, cause the IOException;
     - an undecodable event — :json-parse.

   Truncation never throws: a body that ends without message_stop reduces
   normally and (tools.agents.stream/outcome s) is :eof either way. Check
   `stream-complete?` on the accumulated message."
  [client request]
  (let [caller-name "tools.agents.anthropic/messages-stream"
        url         (api-url (:base-url client) "/v1/messages")
        codec       (client-codec client)
        body-str    ((:write codec) (-> (if (contains? request :stream) (dissoc request :stream) request)
                                    (assoc "stream" true)))
        src         (:credential-source client)
        used-token  (volatile! nil)
        headers     (volatile! nil)
        resp-hdrs   (volatile! nil)
        open!       (fn [attempt]
                      (let [resp (request-with-retries!
                                  (or (:max-retries client) default-max-retries)
                                  (fn []
                                    (vreset! headers (auth-headers client (fn [tok] (vreset! used-token tok))))
                                    (let [resp (attempt)]
                                      (if (and (:status resp) (<= 200 (:status resp) 299))
                                        resp
                                        ;; non-2xx :body is already a String
                                        (decode-or-throw! codec caller-name resp))))
                                  (when src
                                    {:on-unauthorized #(boolean (token/invalidate! src @used-token))}))]
                        (vreset! resp-hdrs (:headers resp))
                        resp))]
    (stream/open-event-stream
     {:request       {:method :post :url url :body body-str}
      :send!         (fn [req] (send-http! client caller-name (assoc req :headers @headers)))
      :open!         open!
      ;; Decoded here rather than via :decode, which sees only the data: the
      ;; SDK's Stream.__stream__ raises on the SSE event NAME `error` and
      ;; fills a missing data "type" from the event name.
      :xform         (map (fn [{:keys [event data]}]
                            (let [ev (if (= "error" event)
                                       ;; the SDK raises even when an error
                                       ;; event's data is not JSON
                                       (try ((:read codec) data) (catch Exception _ nil))
                                       ((:read codec) data))
                                  ev (if (and (map? ev) (not (contains? ev "type")) (string? event))
                                       (assoc ev "type" event)
                                       ev)]
                              (if (or (= "error" event) (error-event? ev))
                                (throw (stream-error caller-name ev data @resp-hdrs))
                                ev))))
      :on-read-error (fn [e]
                       (ex-info (str caller-name ": connection failed mid-stream: " e)
                                {:type :tools.agents.anthropic.error/api-connection :status nil :body nil}
                                e))})))

;; ---------------------------------------------------------------------------
;; Stream accumulation — pure
;; ---------------------------------------------------------------------------
;; A port of anthropic-sdk-python src/anthropic/lib/streaming/_messages.py
;; `accumulate_event` (@ eb21a4352015686c30f5759e8c2f02d70f5371e2). Side
;; state (per-index input_json buffers, message_stop seen) lives in metadata
;; on the message map, so the result stays equal to the non-streamed Message.

(def ^:private tracks-tool-input
  "SDK TRACKS_TOOL_INPUT = (ToolUseBlock, ServerToolUseBlock)."
  #{"tool_use" "server_tool_use"})

(def ^:private message-event-types
  #{"message_start" "content_block_start" "content_block_delta" "content_block_stop"
    "message_delta" "message_stop"})

(defn- invalid-stream [msg]
  (ex-info (str "tools.agents.anthropic/accumulate-event: " msg)
           {:type :tools.agents.anthropic.error/invalid-response :status nil :body nil}))

(defn- assoc-grow
  "assoc x at index i of vector v, nil-padding when i is past the end."
  [v i x]
  (let [v (vec v)]
    (if (< i (count v))
      (assoc v i x)
      (conj (into v (repeat (- i (count v)) nil)) x))))

(defn- event-index [ev]
  (let [i (get ev "index")]
    (when-not (and (integer? i) (>= i 0))
      (throw (invalid-stream (str (get ev "type") " has no valid \"index\": " (pr-str i)))))
    i))

(defn- block-at [acc ev]
  (let [i     (event-index ev)
        block (get-in acc ["content" i])]
    (when-not (map? block)
      (throw (invalid-stream (str (get ev "type") " for content index " i " with no content_block_start"))))
    [i block]))

(defn- apply-delta [acc ev]
  (let [[i block] (block-at acc ev)
        delta     (get ev "delta")
        btype     (get block "type")]
    (case (get delta "type")
      "text_delta"
      (if (= "text" btype)
        (update-in acc ["content" i "text"] str (get delta "text"))
        acc)

      "input_json_delta"
      (if (contains? tracks-tool-input btype)
        (vary-meta acc update-in [::json-bufs i] str (get delta "partial_json"))
        acc)

      "citations_delta"
      (if (= "text" btype)
        (update-in acc ["content" i "citations"] (fnil conj []) (get delta "citation"))
        acc)

      "thinking_delta"
      (if (= "thinking" btype)
        (update-in acc ["content" i "thinking"] str (get delta "thinking"))
        acc)

      "signature_delta"
      (if (= "thinking" btype)
        (assoc-in acc ["content" i "signature"] (get delta "signature"))
        acc)

      acc)))

(defn- stop-block [acc ev]
  (let [[i block] (block-at acc ev)
        buf       (get-in (meta acc) [::json-bufs i])]
    (if (and (contains? tracks-tool-input (get block "type")) (seq buf))
      (let [input (try (read-json buf)
                       (catch Exception e
                         (throw (ex-info (str "tools.agents.anthropic/accumulate-event: unable to parse tool "
                                              "input JSON at content index " i ": " (ex-message e))
                                         {:type :tools.agents.anthropic.error/json-parse :status nil :body buf}
                                         e))))]
        (-> (assoc-in acc ["content" i "input"] input)
            (vary-meta update ::json-bufs dissoc i)))
      acc)))

(def ^:private optional-usage-keys
  ["input_tokens" "cache_creation_input_tokens" "cache_read_input_tokens"
   "server_tool_use" "output_tokens_details"])

(defn- apply-message-delta [acc ev]
  (let [delta (get ev "delta")
        usage (get ev "usage")
        acc   (reduce (fn [a k]
                        ;; SDK assigns these unconditionally (None when absent);
                        ;; a key neither side has stays absent.
                        (if (or (contains? delta k) (contains? a k)) (assoc a k (get delta k)) a))
                      acc ["stop_reason" "stop_sequence" "stop_details"])
        acc   (if (some? (get delta "container")) (assoc acc "container" (get delta "container")) acc)
        acc   (assoc-in acc ["usage" "output_tokens"] (get usage "output_tokens"))]
    (reduce (fn [a k] (if (some? (get usage k)) (assoc-in a ["usage" k] (get usage k)) a))
            acc optional-usage-keys)))

(defn accumulate-event
  "Reducing fn rebuilding the final Message map from messages-stream events,
   ported from anthropic-sdk-python `accumulate_event` (lib/streaming/
   _messages.py):

     - message_start        the message, \"content\" forced to a vector.
                            A content_block_*/message_* event before it throws
                            :invalid-response (the SDK's RuntimeError); ping
                            and unknown types before it are ignored.
     - content_block_start  the block assoc'ed at \"index\" (the SDK appends).
     - content_block_delta  text_delta appends \"text\"; citations_delta conjs
                            \"citation\" onto \"citations\" (text blocks);
                            thinking_delta appends \"thinking\";
                            signature_delta sets \"signature\" (thinking
                            blocks); input_json_delta buffers \"partial_json\"
                            (tool_use/server_tool_use); a delta whose type does
                            not match its block, or an unknown delta type, is
                            ignored.
     - content_block_stop   a tool_use/server_tool_use block with a non-empty
                            buffer gets \"input\" = the parsed buffer; an empty
                            buffer keeps content_block_start's input ({}). A
                            buffer that does not parse throws :json-parse (the
                            SDK's ValueError). Until content_block_stop the
                            partial JSON is never parsed, so a truncated
                            tool_use never throws and keeps its start input
                            (the SDK instead keeps a jiter partial-mode snapshot).
     - message_delta        stop_reason/stop_sequence/stop_details overwritten,
                            container when non-nil; usage is cumulative, so
                            output_tokens is overwritten and input_tokens,
                            cache_creation_input_tokens, cache_read_input_tokens,
                            server_tool_use, output_tokens_details only when
                            non-nil.
     - message_stop         marks the message complete (`stream-complete?`).
     - error                throws the same typed ex-info as messages-stream.
     - ping, unknown types  ignored.

   Arities: [] -> nil, [acc] -> acc, [acc event] -> acc (reduce with init nil,
   or transduce). Pure; side state is metadata, so the result is `=` to the
   non-streamed Message and `output-text`/`tool-calls` work on it."
  ([] nil)
  ([acc] acc)
  ([acc ev]
   (let [etype (get ev "type")]
     (cond
       (error-event? ev)
       (throw (stream-error "tools.agents.anthropic/accumulate-event" ev nil nil))

       (nil? acc)
       (cond
         (= "message_start" etype)
         (let [m (get ev "message")]
           (when-not (map? m) (throw (invalid-stream "message_start has no \"message\" map")))
           (assoc m "content" (vec (get m "content"))))

         (contains? message-event-types etype)
         (throw (invalid-stream (str "unexpected event order, got " (pr-str etype) " before \"message_start\"")))

         ;; ping/unknown never reach the SDK's accumulate_event (Stream.__stream__
         ;; skips them), so they cannot violate its ordering check either.
         :else nil)

       :else
       (case etype
         "content_block_start" (assoc acc "content"
                                      (assoc-grow (get acc "content") (event-index ev) (get ev "content_block")))
         "content_block_delta" (apply-delta acc ev)
         "content_block_stop"  (stop-block acc ev)
         "message_delta"       (apply-message-delta acc ev)
         "message_stop"        (vary-meta acc assoc ::complete true)
         acc)))))

(defn accumulate-stream
  "Reduce `events` — a messages-stream reducible (consumed and closed) or any
   collection of event maps — with accumulate-event. Returns the Message map,
   or nil for no events. Given a messages-stream, the response headers are
   attached so `request-id` works on the result. Never throws on truncation;
   check `stream-complete?`."
  [events]
  (let [m (transduce identity accumulate-event events)]
    (if (and m (satisfies? stream/EventStream events))
      (vary-meta m assoc ::headers (:headers (stream/response events)))
      m)))

(defn stream-complete?
  "True iff the accumulated message saw message_stop. A stream cut off before
   it (connection closed cleanly at an event boundary) accumulates normally
   and reads false here; so does a hand-built or non-streamed message."
  [message]
  (true? (::complete (meta message))))

(defn request-id
  "The `request-id` response header for a successful messages-create/
   count-tokens response — Python SDK equivalent: `message._request_id`.
   Returns nil if response carries no such metadata (e.g. a map built by
   hand rather than returned by this library, or a header genuinely absent
   from the response).

   The Python SDK exposes this via a hidden attribute on the response
   object; this library has no object to hang it off of — messages-create
   returns the decoded body as a plain map, verbatim, per its
   data-transparency contract (see docs/anthropic.md, parity table). So the header is carried as
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
