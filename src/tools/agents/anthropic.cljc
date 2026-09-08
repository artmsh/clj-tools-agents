(ns tools.agents.anthropic
  "A pure-Clojure client for Anthropic's Messages API, ergonomically modeled on
   the official anthropic-sdk-python client (Anthropic(...) constructor,
   client.messages.create(**params) resource method, typed error hierarchy).

   Runs unmodified on JVM Clojure and Babashka. The 'client object' here is an
   `AnthropicClient` record built by `client`; it retains Clojure's map-style
   keyword lookup and immutable update semantics on both runtimes.

   PORTABILITY: exactly one function does real network I/O — the private leaf
   `http-post!` below, isolated with a #?(:bb ... :clj ...) reader conditional.
   Everything above it — URL/header building, the hand-rolled JSON codec,
   error typing, credential resolution, output-text extraction — is plain,
   portable clojure.core exercised identically by both test runners.

   JSON: there is no JSON library available on both runtimes without adding a
   dependency (Babashka bundles one, JVM Clojure does not), so
   `write-json`/`read-json` below are a small hand-written codec. It supports
   exactly what the Messages API needs: nil/bool/number/string/keyword/vector/seq/map, with
   map keys as either strings or keywords passed through VERBATIM (no
   kebab<->snake conversion — the same idiom the Python SDK uses with
   literal dict keys like
   \"max_tokens\"). Known gap: control characters other than \\n \\r \\t and
   backspace/form-feed are not \\u00XX-escaped on output (vanishingly rare in
   real message text) — see README.

   STREAMING: not implemented. :stream true is rejected with a clear error
   rather than silently ignored. See README's platform-limitations section."
  (:require [clojure.string :as str]
            #?@(:bb [[babashka.http-client :as http]] :clj [])))

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
;; JSON codec — pure, portable, zero dependencies.
;; ---------------------------------------------------------------------------

(defn- json-encode-error! [v]
  (throw (ex-info (str "tools.agents.anthropic/write-json: unsupported value: " (pr-str v))
                   {:type :tools.agents.anthropic.error/json-encode})))

(defn json-key->str
  "Coerce a map key (string/keyword/symbol) to its wire string form, verbatim
   — no case conversion. Throws the same typed
   {:type :tools.agents.anthropic.error/json-encode} ex-info as the rest of
   write-json on anything else. Public (unlike this codec's other private
   helpers) so callers building their own JSON-shaped output on top of this
   library's wire contract — e.g. visualize.cljc's pretty-json — can reuse
   the exact same coercion/error contract instead of re-implementing a
   narrower, untyped-on-failure fragment of it."
  [k]
  (cond
    (string? k) k
    (keyword? k) (name k)
    (symbol? k) (name k)
    :else (json-key->str (json-encode-error! k))))

(defn- json-encode-string [s]
  (str "\""
       (-> s
           (str/replace "\\" "\\\\")   ;; MUST run first — later rules insert backslashes
           (str/replace "\"" "\\\"")
           (str/replace "\n" "\\n")
           (str/replace "\r" "\\r")
           (str/replace "\t" "\\t")
           (str/replace "\b" "\\b")
           (str/replace "\f" "\\f"))
       "\""))

(defn write-json
  "Encode a Clojure value as a JSON string. Map keys may be strings or
   keywords (encoded via `name`, verbatim — no case conversion). Keyword
   values are encoded the same way as strings."
  [v]
  (cond
    (nil? v) "null"
    (true? v) "true"
    (false? v) "false"
    (string? v) (json-encode-string v)
    (keyword? v) (json-encode-string (name v))
    (and (number? v) (ratio? v)) (json-encode-error! v)
    (number? v) (str v)
    (map? v) (str "{" (str/join "," (map (fn [[k val]] (str (json-encode-string (json-key->str k)) ":" (write-json val))) v)) "}")
    (or (vector? v) (list? v) (seq? v)) (str "[" (str/join "," (map write-json v)) "]")
    :else (json-encode-error! v)))

(defn- json-parse-error! [msg]
  (throw (ex-info (str "tools.agents.anthropic/read-json: " msg)
                   {:type :tools.agents.anthropic.error/json-parse})))

(defn- ws-char? [c] (contains? #{" " "\t" "\n" "\r"} c))
(defn- digit-str? [c] (contains? #{"0" "1" "2" "3" "4" "5" "6" "7" "8" "9"} c))

(defn- peek-char [s i]
  (when (< i (count s)) (subs s i (inc i))))

(defn- skip-ws [s i]
  (let [n (count s)]
    (loop [i i]
      (if (and (< i n) (ws-char? (subs s i (inc i))))
        (recur (inc i))
        i))))

(declare parse-value)

(defn- parse-literal [s i lit val]
  (let [end (+ i (count lit))]
    (if (and (<= end (count s)) (= (subs s i end) lit))
      [val end]
      (json-parse-error! (str "invalid literal at position " i)))))

(defn- json-int-leading-zero?
  "True when tok is an integer token (no '.'/'e'/'E') with a disallowed
   leading zero, e.g. \"010\" or \"-010\". JSON's own number grammar forbids
   this shape, but Clojure's reader silently treats such tokens as *octal*
   literals (\"010\" -> 8) — so parse-number must reject them itself rather
   than hand them to `read-string`."
  [tok]
  (let [digits (if (str/starts-with? tok "-") (subs tok 1) tok)]
    (and (> (count digits) 1)
         (str/starts-with? digits "0")
         (every? digit-str? (map str digits)))))

(defn- parse-number [s i]
  (let [n (count s) start i]
    (loop [j i]
      (if (and (< j n)
               (let [c (subs s j (inc j))]
                 (or (digit-str? c) (contains? #{"-" "+" "." "e" "E"} c))))
        (recur (inc j))
        (if (= j start)
          (json-parse-error! (str "invalid number at position " i))
          (let [tok (subs s start j)]
            (if (and (not (str/includes? tok "."))
                     (not (str/includes? tok "e"))
                     (not (str/includes? tok "E"))
                     (json-int-leading-zero? tok))
              (json-parse-error! (str "invalid number (leading zero) at position " i))
              [(read-string tok) j])))))))

(defn- parse-string-escaped
  "Slow path for a JSON string that actually contains a backslash escape.
   i points at the opening quote."
  [s i]
  (let [n (count s)]
    (loop [j (inc i) pieces []]
      (when (>= j n) (json-parse-error! "unterminated string"))
      (let [c (subs s j (inc j))]
        (cond
          (= c "\"") [(str/join pieces) (inc j)]

          (= c "\\")
          (do
            (when (>= (inc j) n) (json-parse-error! "unterminated escape"))
            (let [esc (subs s (inc j) (+ j 2))]
              (cond
                (= esc "\"") (recur (+ j 2) (conj pieces "\""))
                (= esc "\\") (recur (+ j 2) (conj pieces "\\"))
                (= esc "/")  (recur (+ j 2) (conj pieces "/"))
                (= esc "n")  (recur (+ j 2) (conj pieces "\n"))
                (= esc "r")  (recur (+ j 2) (conj pieces "\r"))
                (= esc "t")  (recur (+ j 2) (conj pieces "\t"))
                (= esc "b")  (recur (+ j 2) (conj pieces "\b"))
                (= esc "f")  (recur (+ j 2) (conj pieces "\f"))
                (= esc "u")
                (do
                  (when (> (+ j 6) n) (json-parse-error! "unterminated unicode escape"))
                  (let [hex (subs s (+ j 2) (+ j 6))
                        code (Integer/parseInt hex 16)]
                    (recur (+ j 6) (conj pieces (str (char code))))))
                :else (json-parse-error! (str "invalid escape at position " j)))))

          ;; Literal run: take it to the next quote or backslash in ONE piece
          ;; rather than one piece per character, so the piece count tracks
          ;; the number of escapes rather than the length of the string.
          :else
          (let [k (loop [k j]
                    (if (or (>= k n)
                            (= (subs s k (inc k)) "\"")
                            (= (subs s k (inc k)) "\\"))
                      k
                      (recur (inc k))))]
            (recur k (conj pieces (subs s j k)))))))))

(defn- parse-string
  "Decode a JSON string starting at the opening quote i. Fast path: scan to
   the closing quote and, if no backslash was seen on the way, one `subs` IS
   the result — no per-character work at all. Only a string that actually
   contains an escape falls through to parse-string-escaped. This keeps the
   common case (a long, escape-free completion) from building a
   character-per-element vector just to join it back together."
  [s i]
  (let [n (count s)]
    (loop [j (inc i)]
      (if (>= j n)
        (json-parse-error! "unterminated string")
        (let [c (subs s j (inc j))]
          (cond
            (= c "\"") [(subs s (inc i) j) (inc j)]
            (= c "\\") (parse-string-escaped s i)
            :else (recur (inc j))))))))

(defn- parse-array [s i]
  (let [i (skip-ws s (inc i))] ;; skip '['
    (if (= (peek-char s i) "]")
      [[] (inc i)]
      (loop [i i acc []]
        (let [[v i2] (parse-value s i)
              acc (conj acc v)
              i3 (skip-ws s i2)
              c (peek-char s i3)]
          (cond
            (= c ",") (recur (skip-ws s (inc i3)) acc)
            (= c "]") [acc (inc i3)]
            :else (json-parse-error! (str "expected ',' or ']' at position " i3))))))))

(defn- parse-object [s i]
  (let [i (skip-ws s (inc i))] ;; skip '{'
    (if (= (peek-char s i) "}")
      [{} (inc i)]
      (loop [i i acc {}]
        (when (not= (peek-char s i) "\"") (json-parse-error! (str "expected string key at position " i)))
        (let [[k i2] (parse-string s i)
              i3 (skip-ws s i2)]
          (when (not= (peek-char s i3) ":") (json-parse-error! (str "expected ':' at position " i3)))
          (let [i4 (skip-ws s (inc i3))
                [v i5] (parse-value s i4)
                acc (assoc acc k v)
                i6 (skip-ws s i5)
                c (peek-char s i6)]
            (cond
              (= c ",") (recur (skip-ws s (inc i6)) acc)
              (= c "}") [acc (inc i6)]
              :else (json-parse-error! (str "expected ',' or '}' at position " i6)))))))))

(defn- parse-value [s i]
  (let [i (skip-ws s i)
        c (peek-char s i)]
    (cond
      (nil? c) (json-parse-error! "unexpected end of input")
      (= c "{") (parse-object s i)
      (= c "[") (parse-array s i)
      (= c "\"") (parse-string s i)
      (= c "t") (parse-literal s i "true" true)
      (= c "f") (parse-literal s i "false" false)
      (= c "n") (parse-literal s i "null" nil)
      (or (digit-str? c) (= c "-")) (parse-number s i)
      :else (json-parse-error! (str "unexpected character '" c "' at position " i)))))

(defn read-json
  "Decode a JSON string into a Clojure value: objects become maps with STRING
   keys, arrays become vectors, numbers stay numbers (ints stay integers).
   Throws ex-info {:type :tools.agents.anthropic.error/json-parse} on malformed
   input — never returns a partial result."
  [s]
  (try
    (first (parse-value s (skip-ws s 0)))
    (catch Exception e
      (throw (ex-info (str "tools.agents.anthropic/read-json: malformed JSON: " (str e))
                       {:type :tools.agents.anthropic.error/json-parse})))))

;; ---------------------------------------------------------------------------
;; I/O leaves — `http-post!` is the only runtime-specific function in this
;; file; `getenv` is here beside it because it is the other side of the
;; process boundary, not because it needs a reader conditional.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  (System/getenv name))

;; One HTTP client per process, built lazily — anthropic-sdk-python likewise
;; reuses a single httpx.Client across attempts. Building one per call would
;; allocate a fresh selector thread for every retry (java.net.http.HttpClient
;; had no close() before Java 21). Both are pinned to HTTP/1.1 for the reason
;; spelled out in http-post!'s :clj branch below.
#?(:bb  (def ^:private bb-http-client
          (delay (http/client (assoc http/default-client-opts :version :http1.1))))
   :clj (def ^:private jvm-http-client
          (delay (-> (java.net.http.HttpClient/newBuilder)
                     (.version java.net.http.HttpClient$Version/HTTP_1_1)
                     (.build)))))

(defn- http-post!
  "POST body to url with headers. Returns {:status int :headers map :body
   string} on ANY HTTP response (2xx or not) — callers classify status
   themselves. Throws only on a genuine transport failure (DNS, connection
   refused, TLS handshake failure, timeout — no response at all)."
  [url headers body]
  #?(:bb
     ;; :version :http1.1 for the same reason as the :clj leaf below —
     ;; babashka.http-client wraps java.net.http and inherits its HTTP_2
     ;; default, which breaks against a plain HTTP/1.1 proxy over cleartext.
     (let [resp (http/post url {:client @bb-http-client :headers headers :body body :throw false})]
       {:status (:status resp) :headers (:headers resp) :body (:body resp)})

     :clj
     (let [builder  (reduce (fn [b [k v]] (.header ^java.net.http.HttpRequest$Builder b (str k) (str v)))
                             (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                             headers)
           req      (-> builder
                        ;; Pin HTTP/1.1 explicitly, don't let HttpClient's
                        ;; default (HTTP_2, upgraded-to opportunistically)
                        ;; apply here. Verified empirically against a real
                        ;; plaintext-http gateway (Caddy in front of a
                        ;; homelab LLM router): the unpinned default sends a
                        ;; cleartext HTTP/2 upgrade attempt that gateway
                        ;; doesn't handle, and EVERY request comes back `502`
                        ;; with an empty body — while curl (HTTP/1.1 by
                        ;; default for http://) and this same request with
                        ;; `.version(HTTP_1_1)` pinned both succeed in ~3s.
                        ;; api.anthropic.com itself is unaffected either way
                        ;; (HTTPS negotiates via ALPN), but a custom
                        ;; `:base-url` gateway on plain http:// — exactly
                        ;; examples/custom_gateway.clj's own scenario — can
                        ;; silently 502 on every single request otherwise.
                        (.version java.net.http.HttpClient$Version/HTTP_1_1)
                        (.POST (java.net.http.HttpRequest$BodyPublishers/ofString body))
                        (.build))
           resp     (.send @jvm-http-client req (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status  (.statusCode resp)
        :headers (into {} (map (fn [[k vs]] [k (first vs)]) (.map (.headers resp))))
        :body    (.body resp)})))

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
   Babashka's http-post! leaf (babashka.http-client) returns a header's
   value as a vector whenever that header name appears more than once in
   the response — verified empirically, `retry-after` sent twice yields
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
  "POST body-str to url with headers via http-post!, classifying a genuine
   transport failure (DNS/refused/TLS/timeout — no response at all) as
   :tools.agents.anthropic.error/api-connection. caller-name (e.g.
   \"tools.agents.anthropic/messages-create\") prefixes that error's message.
   An already-typed tools.agents.anthropic.error/* ex-info thrown by
   http-post! itself surfaces verbatim instead — those are permanent, not
   connection failures."
  [caller-name url headers body-str]
  (try
    (http-post! url headers body-str)
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
  "One HTTP round trip: POST body-str to url with headers, decode-or-throw!
   the response. Shared by messages-create/count-tokens; url/headers/
   body-str are computed ONCE by the caller before entering
   request-with-retries!, not recomputed per attempt — an earlier version
   rebuilt them (including re-serializing the whole request via write-json)
   on every retry, pure wasted CPU/latency on top of the backoff sleep for
   work that's provably identical across attempts."
  [caller-name url headers body-str]
  (decode-or-throw! caller-name (post-json! caller-name url headers body-str)))

(defn- post-request!
  "Shared body of every resource method: build URL + headers, encode the
   request, POST it under the client's retry policy. caller-name prefixes
   any error message; path is appended to the client's base-url."
  [client caller-name path request]
  (let [url      (api-url (:base-url client) path)
        headers  (auth-headers client)
        body-str (write-json request)]
    (request-with-retries! (or (:max-retries client) default-max-retries)
                           #(attempt-request! caller-name url headers body-str))))

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
