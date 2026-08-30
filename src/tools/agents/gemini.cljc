(ns tools.agents.gemini
  "A pure-Clojure client for the Gemini Developer API
   (https://generativelanguage.googleapis.com), ergonomically modeled on the
   official google-genai Python client (`genai.Client(api_key=...)`
   constructor, `client.models.generate_content(model=..., contents=...,
   config=...)` resource method, typed error hierarchy).

   Sibling of tools.agents.anthropic and tools.agents.openai — same
   architecture, same three runtimes, same leaf-I/O and JSON-codec design.
   See README's parity tables for the google-genai parity and the deliberate
   divergences from the other two providers.

   Runs unmodified on JVM Clojure, Babashka and clz. clz has no classes, so
   the 'client object' here is a plain map built by `client`.

   PORTABILITY: exactly one function does real network I/O — the private leaf
   `http-post!` below, isolated with a #?(:clz ... :bb ... :clj ...) reader
   conditional, same pattern as tools.agents.anthropic/tools.agents.openai's
   own leaf I/O. Everything above it — URL/header building, the hand-rolled
   JSON codec, error typing, credential resolution, the retry policy,
   output-text extraction — is plain, portable clojure.core exercised
   identically by all three test runners. Only two other leaves exist:
   `getenv` (env-var access) and `sleep!` (retry backoff).

   AUTH: unlike anthropic-sdk-python's api_key/auth_token split and
   openai-python's single Bearer token, the Gemini Developer API authenticates
   every request with an `x-goog-api-key` header carrying a bare API key — no
   `Bearer ` prefix (confirmed from python-genai's `_api_client.py`: `self.
   _http_options.headers['x-goog-api-key'] = self.api_key`). Credential
   precedence: explicit :api-key -> GOOGLE_API_KEY env var -> GEMINI_API_KEY
   env var -> throw (same precedence and same two env vars as python-genai's
   `get_env_api_key`, which logs a warning and prefers GOOGLE_API_KEY when
   both are set — this library just picks GOOGLE_API_KEY silently, since none
   of the three runtimes share a common logging facility).

   URL SHAPE: unlike anthropic/openai, the model is part of the URL path, not
   the request body — `client.models.generate_content(model=\"gemini-2.5-
   flash\", ...)` becomes `POST {base-url}/{api-version}/models/{model}:
   generateContent`. `generate-content`/`count-tokens` below take `model` as
   an explicit argument for exactly this reason; `request` never carries a
   \"model\" key. A `model` already prefixed with \"models/\" or
   \"tunedModels/\" is used verbatim; a bare model id gets \"models/\"
   prepended, matching the SDK's own path-building.

   RETRIES: on by default (:max-retries, default 4 — python-genai's
   `_RETRY_ATTEMPTS = 5` including the initial call, so 4 retries) on
   408/429/500/502/503/504 and connection failures, exponential backoff
   ported from python-genai's tenacity `wait_exponential_jitter(initial=1.0,
   max=60.0, exp_base=2, jitter=1)`: `delay = min(max, initial * exp_base^n +
   random.uniform(0, jitter))` where n is retries-already-taken. Unlike
   tools.agents.openai's port of openai-python's retry policy, this is NOT
   confirmed to honor a server-sent `Retry-After`/`X-Should-Retry` header —
   python-genai's retry predicate is a bare status-code set with no header
   inspection found in `_api_client.py`, so none is implemented here either.
   `should-retry?`/`retry-delay-ms` are pure, with the RNG injected.

   CLZ GOTCHAS worked around here (verified empirically on clz v43, same as
   tools.agents.openai): `(def x \"doc\" v)` binds the DOCSTRING as the
   value, so every `def` below is docstring-free with a comment instead;
   `keep-indexed` is missing; `nth`'s 3-arity default is ignored; `Math/pow`/
   `Math/round` are missing; the string reader does not decode \\b/\\f. See
   README's \"clz gotchas\" section.

   JSON: there is no JSON library available on all three runtimes without
   adding a dependency, so `write-json`/`read-json` below are the same small
   hand-written codec as tools.agents.anthropic/tools.agents.openai. Map keys
   are strings or keywords passed through VERBATIM (no kebab<->snake
   conversion — camelCase request/response keys like \"generationConfig\"/
   \"maxOutputTokens\" are typed exactly as the REST API expects). Known gap:
   control characters other than \\n \\r \\t and backspace/form-feed are not
   \\u00XX-escaped on output — see README.

   STREAMING: not offered. The Gemini REST API's streaming variant is a
   SEPARATE endpoint (`:streamGenerateContent`), not a `:stream true` request
   flag the way anthropic/openai model it — so there is no flag to reject
   here, only an absent function. clz's only HTTPS-capable request builtin
   (clojure.http/request) does not stream, and its streaming builtin
   (clojure.http/request-stream) does not support TLS — the same platform
   blocker documented in tools.agents.anthropic/tools.agents.openai's ns
   docstrings — so a `stream-generate-content` could not be implemented on
   clz today regardless. See README's platform-limitations section."
  (:require [clojure.string :as str]
            #?@(:bb [[babashka.http-client :as http]] :clj [] :clz [])))

;; python-genai's HttpOptions defaults (Gemini Developer API, not Vertex):
;; base_url = 'https://generativelanguage.googleapis.com/', api_version =
;; 'v1beta'. Kept as two separate values (not baked into one string) so
;; `messages-url` below can join them the same way tools.agents.anthropic
;; joins base-url + path, rather than string-concatenating a third segment.
(def default-base-url "https://generativelanguage.googleapis.com")
(def default-api-version "v1beta")

;; python-genai's _RETRY_ATTEMPTS = 5 (the initial call plus 4 retries) — see
;; the ns docstring's RETRIES section. Unlike tools.agents.anthropic/
;; tools.agents.openai, which each store max-retries as "retries after the
;; first attempt" too, this is deliberately the same convention (a count of
;; RETRIES, not attempts), so default-max-retries = 4 here, not 5.
;; NOTE written as a bare `def` with a comment rather than a docstring: clz
;; binds the DOCSTRING as the var's value for `(def name "doc" value)`
;; (verified empirically on clz v43), which silently turns this 4 into a
;; string. Every other `def` in this file is docstring-free for the same
;; reason; `defn` docstrings are handled correctly and are used throughout.
(def default-max-retries 4)

;; Backspace/form-feed as computed chars: clz's string-literal reader does not
;; decode `\b`/`\f` escapes (verified empirically — they stay literal 2-char
;; `\` + `b`/`f`), unlike `\n`/`\r`/`\t` which it decodes correctly. Building
;; these via `char` sidesteps the gap and works identically on all runtimes.
(def ^:private BACKSPACE (str (char 8)))
(def ^:private FORM-FEED (str (char 12)))

;; ---------------------------------------------------------------------------
;; JSON codec — pure, portable, zero dependencies. Byte-for-byte the same
;; algorithm as tools.agents.anthropic/tools.agents.openai's own codecs
;; (kept as separate copies rather than a shared ns — see those files' own
;; ns docstrings for why each provider ns stays a self-contained sibling).
;; ---------------------------------------------------------------------------

(defn- json-encode-error! [v]
  (throw (ex-info (str "tools.agents.gemini/write-json: unsupported value: " (pr-str v))
                   {:type :tools.agents.gemini/json-encode-error})))

(defn- json-key->str [k]
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
           (str/replace BACKSPACE "\\b")
           (str/replace FORM-FEED "\\f"))
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
  (throw (ex-info (str "tools.agents.gemini/read-json: " msg)
                   {:type :tools.agents.gemini/json-parse-error})))

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
  "Slow path for a JSON string that actually contains a backslash escape:
   accumulate decoded pieces and join them. i points at the opening quote."
  [s i]
  (let [n (count s)]
    (loop [j (inc i) pieces []]
      (when (>= j n) (json-parse-error! "unterminated string"))
      (let [c (subs s j (inc j))]
        (cond
          ;; str/join, NOT (apply str pieces): clz overflows its stack applying
          ;; a function to a few hundred arguments, so the original `apply`
          ;; spelling crashed on any response string longer than that — i.e.
          ;; on essentially every real model reply. str/join is fine into the
          ;; hundreds of thousands of pieces (verified on clz v43).
          (= c "\"") [(str/join "" pieces) (inc j)]

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
                (= esc "b")  (recur (+ j 2) (conj pieces BACKSPACE))
                (= esc "f")  (recur (+ j 2) (conj pieces FORM-FEED))
                (= esc "u")
                (do
                  (when (> (+ j 6) n) (json-parse-error! "unterminated unicode escape"))
                  (let [hex (subs s (+ j 2) (+ j 6))
                        code (Integer/parseInt hex 16)]
                    (recur (+ j 6) (conj pieces (str (char code))))))
                :else (json-parse-error! (str "invalid escape at position " j)))))

          ;; Literal run: take it to the next quote or backslash in ONE piece
          ;; rather than one piece per character, so the piece count tracks the
          ;; number of escapes rather than the length of the string.
          :else
          (let [k (loop [k j]
                    (if (or (>= k n)
                            (= (subs s k (inc k)) "\"")
                            (= (subs s k (inc k)) "\\"))
                      k
                      (recur (inc k))))]
            (recur k (conj pieces (subs s j k)))))))))

(defn- parse-string
  "Decode a JSON string starting at the opening quote i. Fast path: scan to the
   closing quote and, if no backslash was seen on the way, one `subs` IS the
   result — no per-character work at all. Only a string that actually contains
   an escape falls through to parse-string-escaped."
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
   Throws ex-info {:type :tools.agents.gemini/json-parse-error} on malformed
   input — never returns a partial result."
  [s]
  (try
    (first (parse-value s (skip-ws s 0)))
    (catch Exception e
      (throw (ex-info (str "tools.agents.gemini/read-json: malformed JSON: " (str e))
                       {:type :tools.agents.gemini/json-parse-error})))))

;; ---------------------------------------------------------------------------
;; Leaf I/O — the only two runtime-specific functions in this file.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  #?(:clz (sys/getenv name)
     :bb (System/getenv name)
     :clj (System/getenv name)))

(defn- sleep! [millis]
  ;; clz registers `sleep` as a bare global builtin (src/stdlib/time_builtins.zig)
  ;; and has no Thread class; JVM/bb have no bare `sleep`.
  (let [ms (long millis)]
    (when (pos? ms)
      #?(:clz (sleep ms)
         :bb (Thread/sleep ms)
         :clj (Thread/sleep ms))
      nil)))

;; `System/currentTimeMillis` is a real builtin on all three runtimes (clz
;; registers it in time_builtins.zig), so this one needs no reader conditional.
(defn- now-ms [] (System/currentTimeMillis))

;; Rebindable retry sleep — same pattern as tools.agents.anthropic's
;; *sleep-fn*: tests bind this to a no-op so the retry-loop tests below run in
;; milliseconds rather than paying the real (>= 1s) backoff delay.
(def ^:dynamic *sleep-fn* sleep!)

;; One HTTP client per process, built lazily — same rationale as
;; tools.agents.openai's own bb-http-client/jvm-http-client: avoids allocating
;; a fresh selector thread per retry. clz's clojure.http/request is stateless,
;; so it needs no equivalent.
#?(:clz nil
   :bb  (def ^:private bb-http-client
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
  #?(:clz
     (let [parsed (clz.url/parse url)
           proto  (:protocol parsed)
           host   (:host parsed)
           port   (or (:port parsed) (if (= proto "https") 443 80))
           base   (or (:path parsed) "/")
           path   (if (:query parsed) (str base "?" (:query parsed)) base)]
       ;; The 7th argument is clz v44's explicit tls? flag — see
       ;; tools.agents.anthropic/http-post!'s comment for the full rationale
       ;; (a plaintext fallback on a non-443 https:// base-url would otherwise
       ;; leak the API key and full JSON body).
       (clojure.http/request "POST" host port path headers body (= proto "https")))

     :bb
     (let [resp (http/post url {:client @bb-http-client :headers headers :body body :throw false})]
       {:status (:status resp) :headers (:headers resp) :body (:body resp)})

     :clj
     (let [builder  (reduce (fn [b [k v]] (.header ^java.net.http.HttpRequest$Builder b (str k) (str v)))
                             (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                             headers)
           req      (-> builder
                        ;; HTTP/1.1 explicitly — same rationale as
                        ;; tools.agents.openai's :clj leaf (a plain HTTP/1.1
                        ;; reverse proxy in front of a custom :base-url
                        ;; gateway 502s on Java's default HTTP/2 preface).
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
  "Pure credential-resolution logic, factored out for testability (process env
   vars can't be reliably unset mid-test-run, so tests inject a fake getenv-fn
   instead of touching the real environment).

   Precedence: explicit :api-key -> GOOGLE_API_KEY env var -> GEMINI_API_KEY
   env var -> throw — same order and same two env vars as python-genai's
   `get_env_api_key` (see ns docstring's AUTH section). Returns {:api-key s}."
  [{:keys [api-key]} getenv-fn]
  (if api-key
    {:api-key api-key}
    (let [google-key (getenv-fn "GOOGLE_API_KEY")
          gemini-key (getenv-fn "GEMINI_API_KEY")]
      (cond
        (seq google-key) {:api-key google-key}
        (seq gemini-key) {:api-key gemini-key}
        :else (throw (ex-info (str "tools.agents.gemini/client: no credentials found — pass :api-key, "
                                    "or set the GOOGLE_API_KEY or GEMINI_API_KEY environment variable")
                               {:type :tools.agents.gemini/missing-credentials}))))))

(defn client
  "Build a client config map — the 'client object' analogue of Python's
   genai.Client(api_key=...) constructor. Resolves credentials eagerly (fails
   fast with a catchable ex-info BEFORE any network request).

   opts:
     :api-key      explicit API key -> sent as the `x-goog-api-key` header
                   (falls back to GOOGLE_API_KEY, then GEMINI_API_KEY)
     :base-url     override API root, default
                   `https://generativelanguage.googleapis.com` (also resolved
                   from GOOGLE_GEMINI_BASE_URL — not an official env var name,
                   this library's own convention, matching how tools.agents.
                   openai resolves OPENAI_BASE_URL)
     :api-version  URL version segment, default \"v1beta\" (python-genai's
                   own default for the Gemini Developer API)
     :max-retries  how many times to retry a failed request, default 4
                   (`default-max-retries`). 0 disables retries."
  ([] (client {}))
  ([opts]
   (let [creds (resolve-credentials opts getenv)]
     (merge {:base-url    (or (:base-url opts) (getenv "GOOGLE_GEMINI_BASE_URL") default-base-url)
             :api-version (or (:api-version opts) default-api-version)
             :max-retries (if (number? (:max-retries opts))
                            (max 0 (long (:max-retries opts)))
                            default-max-retries)}
            creds))))

;; ---------------------------------------------------------------------------
;; Error typing
;; ---------------------------------------------------------------------------

(defn- status->type [status]
  (cond
    (= status 400) :tools.agents.gemini/invalid-argument-error
    (= status 401) :tools.agents.gemini/authentication-error
    (= status 403) :tools.agents.gemini/permission-denied-error
    (= status 404) :tools.agents.gemini/not-found-error
    (= status 429) :tools.agents.gemini/resource-exhausted-error
    (and status (>= status 500)) :tools.agents.gemini/internal-server-error
    :else :tools.agents.gemini/api-status-error))

(defn retryable-status?
  "True for the HTTP statuses python-genai's `_api_client.py` retries: 408,
   429, 500, 502, 503, 504 — a bare status-code set, unlike tools.agents.
   openai's header-driven should-retry? (see ns docstring's RETRIES note).
   Deliberately excludes 409, unlike tools.agents.openai's set."
  [status]
  (boolean (and status (contains? #{408 429 500 502 503 504} status))))

;; ---------------------------------------------------------------------------
;; Retry policy — a port of python-genai's tenacity `wait_exponential_jitter`
;; backoff (see ns docstring's RETRIES section for the exact formula and its
;; source). Pure: the caller supplies the RNG, so every branch is
;; unit-testable without sleeping and without randomness.
;; ---------------------------------------------------------------------------

;; `default-max-retries` is defined at the top of the file — `client` needs it.
(def ^:private retry-initial-delay-ms 1000)  ;; tenacity's initial=1.0s
(def ^:private retry-max-delay-ms 60000)     ;; tenacity's max=60.0s
(def ^:private retry-jitter-ms 1000)         ;; tenacity's jitter=1 (seconds)

(defn- pow2 [n]
  ;; No Math/pow on clz (see ns docstring's CLZ GOTCHAS) — n is always small
  ;; (retries-taken, bounded by max-retries), so a plain loop is fine.
  (loop [i 0 v 1] (if (>= i n) v (recur (inc i) (* v 2)))))

(defn retry-delay-ms
  "How long to wait before attempt number (retries-taken + 1), in ms —
   python-genai's tenacity `wait_exponential_jitter(initial=1.0, max=60.0,
   exp_base=2, jitter=1)`: `min(max, initial * 2^n + random.uniform(0,
   jitter))` where n = retries-taken. rand-fn must return a double in [0.0,
   1.0); the 1-arity uses clojure.core/rand."
  ([retries-taken] (retry-delay-ms retries-taken rand))
  ([retries-taken rand-fn]
   (let [exp    (* retry-initial-delay-ms (pow2 retries-taken))
         jitter (long (* retry-jitter-ms (rand-fn)))]
     (min retry-max-delay-ms (+ exp jitter)))))

(defn- extract-error-message
  "Best-effort extraction of the Gemini API's error message — python-genai's
   APIError accepts BOTH a nested `{\"error\":{\"message\":...}}` shape and a
   flat `{\"message\":...}` shape (confirmed from errors.py: top level is
   checked first, then falls back to `.error.message`). Returns nil (never
   throws) on any parse failure."
  [body]
  (when (seq body)
    (try
      (let [parsed (read-json body)]
        (when (map? parsed)
          (let [msg (get parsed "message")]
            (if (string? msg)
              msg
              (let [nested (get parsed "error")]
                (when (map? nested)
                  (let [nested-msg (get nested "message")]
                    (when (string? nested-msg) nested-msg))))))))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Request plumbing
;; ---------------------------------------------------------------------------

(defn- model-path
  "\"models/gemini-2.5-flash\" verbatim if model already carries a
   \"models/\"/\"tunedModels/\" prefix, else \"models/\" + model — the same
   normalization google-genai's own path-building applies."
  [model]
  (if (or (str/starts-with? model "models/") (str/starts-with? model "tunedModels/"))
    model
    (str "models/" model)))

(defn- endpoint-url
  "Join client's base-url + api-version + \"models/{model}:{method}\", e.g.
   .../v1beta/models/gemini-2.5-flash:generateContent."
  [{:keys [base-url api-version]} model method]
  (let [base (if (str/ends-with? base-url "/") (subs base-url 0 (dec (count base-url))) base-url)]
    (str base "/" api-version "/" (model-path model) ":" method)))

(defn- request-headers [fn-name client]
  (when-not (:api-key client)
    (throw (ex-info (str "tools.agents.gemini/" fn-name ": client has no :api-key — build it via "
                          "tools.agents.gemini/client")
                     {:type :tools.agents.gemini/missing-credentials})))
  {"x-goog-api-key" (:api-key client)
   "content-type"   "application/json"})

(defn- own-error?
  "True for an already-typed tools.agents.gemini/* ex-info — the clz
   TLS-on-non-443-port refusal is this library's equivalent of an SDK-side
   permanent error, and retrying it would be pointless as well as noisy."
  [e]
  (let [data (ex-data e)]
    (boolean (and data (keyword? (:type data)) (= "tools.agents.gemini" (namespace (:type data)))))))

(defn- resolve-max-retries [client]
  (let [n (:max-retries client)]
    (if (number? n) (max 0 (long n)) default-max-retries)))

(defn- post-json!
  "Shared transport for generate-content/count-tokens: build URL + headers,
   encode the request map, POST it, classify the status, decode the body —
   retrying transport failures and retryable statuses per retryable-status?/
   retry-delay-ms (:max-retries on the client, default 4).

   Non-retryable failures, and retryable ones once the budget is spent, throw
   with :retries-taken in ex-data. A malformed body on an otherwise-successful
   2xx is NOT retried."
  [client fn-name model method request]
  (let [url         (endpoint-url client model method)
        headers     (request-headers fn-name client)
        body-str    (write-json request)
        max-retries (resolve-max-retries client)]
    (loop [retries-taken 0]
      (let [outcome (try
                      {:resp (http-post! url headers body-str)}
                      (catch Exception e
                        (if (own-error? e) (throw e) {:error e})))]
        (if (:error outcome)
          ;; No response at all: DNS, connection refused, TLS handshake,
          ;; timeout. Retried the same as a retryable status.
          (if (< retries-taken max-retries)
            (do (*sleep-fn* (retry-delay-ms retries-taken))
                (recur (inc retries-taken)))
            (throw (ex-info (str "tools.agents.gemini/" fn-name ": connection failed: " (str (:error outcome)))
                             {:type :tools.agents.gemini/api-connection-error :status nil :body nil
                              :retries-taken retries-taken})))
          (let [resp      (:resp outcome)
                status    (:status resp)
                resp-body (:body resp)]
            (if (and status (>= status 200) (< status 300))
              (read-json resp-body)
              (if (and (< retries-taken max-retries) (retryable-status? status))
                (do (*sleep-fn* (retry-delay-ms retries-taken))
                    (recur (inc retries-taken)))
                (let [err-msg (extract-error-message resp-body)
                      detail  (cond err-msg err-msg (seq resp-body) resp-body :else nil)]
                  (throw (ex-info (str "tools.agents.gemini/" fn-name ": HTTP " status (when detail (str " " detail)))
                                   {:type (status->type status) :status status :body resp-body
                                    :retries-taken retries-taken})))))))))))

;; ---------------------------------------------------------------------------
;; Public API — resource methods
;; ---------------------------------------------------------------------------

(defn generate-content
  "POST request (a plain map, passed through to JSON almost verbatim —
   contents, generationConfig, systemInstruction, safetySettings, tools,
   cachedContent, etc. pass straight through untouched, matching python-genai's
   data-transparency contract) to POST {base-url}/{api-version}/models/{model}
   :generateContent. Returns the decoded response map (string keys). The
   analogue of Python's client.models.generate_content(model=model,
   contents=..., config=request).

   Throws ex-info on any failure, message prefixed
   \"tools.agents.gemini/generate-content: \", ex-data
   {:type <keyword — see the error-hierarchy table in README> :status
   <http-status-or-nil> :body <raw-response-body-or-nil> :retries-taken
   <int>}."
  [client model request]
  (post-json! client "generate-content" model "generateContent" request))

(defn count-tokens
  "POST request (same shape as generate-content's request map — contents,
   etc.) to POST {base-url}/{api-version}/models/{model}:countTokens. Returns
   the decoded response map, e.g. {\"totalTokens\" 12}. The analogue of
   Python's client.models.count_tokens(model=model, contents=...).

   Same retry policy, error hierarchy and ex-data shape as generate-content."
  [client model request]
  (post-json! client "count-tokens" model "countTokens" request))

;; ---------------------------------------------------------------------------
;; Public API — response accessors
;; ---------------------------------------------------------------------------

(defn output-text
  "The concatenated text of response's first candidate — a port of
   google-genai's `GenerateContentResponse.text` property: join every
   \"text\" field across candidates[0].content.parts where \"text\" is a
   string and the part is not a \"thought\" part (thinking-model reasoning
   traces are excluded from the SDK's own .text the same way).

   Returns nil (never throws), matching the Python property returning None,
   when: \"candidates\" is missing/empty/not an array; the first candidate has
   no \"content\"/\"parts\" array; or no part in it carries a string \"text\"
   (a tool-call-only or safety-blocked response, mirroring the same shape
   tools.agents.openai/completion-text handles by returning nil).

   Unlike the SDK, this does not warn on multiple candidates or non-text
   parts — none of the three runtimes here share a common logging facility,
   and a silent best-effort extraction is the more useful library default."
  [response]
  (let [candidates (get response "candidates")]
    (when (and (vector? candidates) (seq candidates))
      (let [parts (get-in (first candidates) ["content" "parts"])]
        (when (vector? parts)
          (let [texts (keep (fn [part]
                               (when (and (map? part)
                                          (string? (get part "text"))
                                          (not (true? (get part "thought"))))
                                 (get part "text")))
                             parts)]
            ;; str/join, NOT (apply str texts) — same clz stack-depth limit
            ;; documented in the JSON parser above.
            (when (seq texts) (str/join "" texts))))))))

;; ---------------------------------------------------------------------------
;; Contents-list helpers
;; ---------------------------------------------------------------------------
;; generate-content's request takes these as its "contents" array. Gemini
;; uses "user"/"model" role names (NOT anthropic's "assistant" or openai's
;; "assistant"/"developer"/"system" — there is no system role in "contents"
;; at all; system prompts go in the separate top-level "systemInstruction").

(defn add-user-message
  "Append a {\"role\" \"user\" \"parts\" [{\"text\" text}]} turn and return
   the updated contents vector (Python's list.append(...) mutation becomes
   conj-and-return)."
  [contents text]
  (conj (vec contents) {"role" "user" "parts" [{"text" text}]}))

(defn add-model-message
  "Append a {\"role\" \"model\" \"parts\" [{\"text\" text}]} turn (e.g. for
   prefill/few-shot) and return the updated contents vector."
  [contents text]
  (conj (vec contents) {"role" "model" "parts" [{"text" text}]}))
