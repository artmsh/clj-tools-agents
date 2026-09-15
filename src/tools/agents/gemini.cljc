(ns tools.agents.gemini
  "A pure-Clojure client for the Gemini Developer API
   (https://generativelanguage.googleapis.com), ergonomically modeled on the
   official google-genai Python client (`genai.Client(api_key=...)`
   constructor, `client.models.generate_content(model=..., contents=...,
   config=...)` resource method, typed error hierarchy).

   Sibling of tools.agents.anthropic and tools.agents.openai — same
   architecture, same two runtimes, same leaf-I/O and JSON-codec design.
   See docs/gemini.md 'Parity with python-genai' for the SDK parity table,
   and docs/divergences.md for the deliberate divergences from the sibling
   clients.

   Runs unmodified on JVM Clojure and Babashka. The 'client object' here is a
   `GeminiClient` record built by `client`; it retains Clojure's map-style
   keyword lookup and immutable update semantics on both runtimes.

   PORTABILITY: exactly one function does real network I/O — the private leaf
   `http-post!` below, isolated with a #?(:bb ... :clj ...) reader conditional,
   same pattern as tools.agents.anthropic/tools.agents.openai's own leaf I/O.
   Everything above it — URL/header building, the hand-rolled JSON codec,
   error typing, credential resolution, the retry policy, output-text
   extraction — is plain, portable clojure.core exercised identically by both
   test runners. `getenv` (env-var access) and `sleep!` (retry backoff) touch
   the process too, but need no reader conditional — they are testability
   seams, not runtime-specific leaves.

   AUTH: unlike anthropic-sdk-python's api_key/auth_token split and
   openai-python's single Bearer token, the Gemini Developer API authenticates
   every request with an `x-goog-api-key` header carrying a bare API key — no
   `Bearer ` prefix (confirmed from python-genai's `_api_client.py`: `self.
   _http_options.headers['x-goog-api-key'] = self.api_key`). Credential
   precedence: explicit :api-key -> GOOGLE_API_KEY env var -> GEMINI_API_KEY
   env var -> throw (same precedence and same two env vars as python-genai's
   `get_env_api_key`, which logs a warning and prefers GOOGLE_API_KEY when
   both are set — this library just picks GOOGLE_API_KEY silently, since the
   two runtimes share no common logging facility).

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
   `retryable-status?`/`retry-delay-ms` are pure, with the RNG injected;
   there is no `should-retry?`, since no header input exists to decide on.

   JSON: there is no JSON library available on both runtimes without
   adding a dependency, so `write-json`/`read-json` below wrap the small
   hand-written codec shared in tools.agents.json. Map keys are strings or
   keywords passed through VERBATIM (no kebab<->snake conversion — camelCase
   request/response keys like \"generationConfig\"/\"maxOutputTokens\" are
   typed exactly as the REST API expects). See docs/gemini.md 'JSON: a small
   hand-rolled codec, not a dependency'.

   STREAMING: not offered. The Gemini REST API's streaming variant is a
   SEPARATE endpoint (`:streamGenerateContent`), not a `:stream true` request
   flag the way anthropic/openai model it — so there is no flag to reject
   here, only an absent function. See docs/gemini.md 'Streaming is not
   supported'."
  (:require [clojure.string :as str]
            [tools.agents.json :as json]
            #?@(:bb [[babashka.http-client :as http]] :clj [])))

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
(def default-max-retries 4)

(defrecord GeminiClient [api-key base-url api-version max-retries])

;; ---------------------------------------------------------------------------
;; JSON codec — tools.agents.json, bound to this namespace's error contract.
;; ---------------------------------------------------------------------------

(def ^:private json-codec
  (json/codec {:prefix      "tools.agents.gemini"
               :encode-type :tools.agents.gemini/json-encode-error
               :parse-type  :tools.agents.gemini/json-parse-error}))

(defn write-json
  "Encode a Clojure value as a JSON string (tools.agents.json/write-json). Map
   keys may be strings or keywords, encoded verbatim; every character below
   0x20 is \\u00XX-escaped. Throws ex-info
   {:type :tools.agents.gemini/json-encode-error} on an unsupported value."
  [v]
  ((:write json-codec) v))

(defn read-json
  "Decode a JSON string into a Clojure value (tools.agents.json/read-json):
   objects become maps with STRING keys, arrays become vectors, numbers stay
   numbers. Throws ex-info {:type :tools.agents.gemini/json-parse-error} on
   malformed input — never returns a partial result."
  [s]
  ((:read json-codec) s))

;; ---------------------------------------------------------------------------
;; I/O leaves — `http-post!` is the only runtime-specific function in this
;; file; `getenv`, `sleep!` and `now-ms` are here beside it because they are
;; the other side of the process boundary, not because they need a reader
;; conditional.
;; ---------------------------------------------------------------------------

(defn- getenv [name]
  (System/getenv name))

(defn- sleep! [millis]
  (let [ms (long millis)]
    (when (pos? ms)
      (Thread/sleep ms)
      nil)))

(defn- now-ms [] (System/currentTimeMillis))

;; Rebindable retry sleep — same pattern as tools.agents.anthropic's
;; *sleep-fn*: tests bind this to a no-op so the retry-loop tests below run in
;; milliseconds rather than paying the real (>= 1s) backoff delay.
(def ^:dynamic *sleep-fn* sleep!)

;; One HTTP client per process, built lazily — same rationale as
;; tools.agents.openai's own bb-http-client/jvm-http-client: avoids allocating
;; a fresh selector thread per retry.
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
  "Build a GeminiClient record — the 'client object' analogue of Python's
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
     (map->GeminiClient
      (merge {:base-url    (or (:base-url opts) (getenv "GOOGLE_GEMINI_BASE_URL") default-base-url)
              :api-version (or (:api-version opts) default-api-version)
              :max-retries (if (number? (:max-retries opts))
                             (max 0 (long (:max-retries opts)))
                             default-max-retries)}
             creds)))))

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
  ;; n is always small (retries-taken, bounded by max-retries), so a plain
  ;; loop beats Math/pow's float round-trip.
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
  "True for an already-typed tools.agents.gemini/* ex-info — an SDK-side
   permanent error, which it would be pointless as well as noisy to retry.

   Matches the namespace EXACTLY: moving these keywords under a `.error`
   sub-namespace (as tools.agents.anthropic spells its own) would silently
   make this false for every one of them. See docs/gemini.md's
   error-hierarchy note."
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
   {:type <keyword — see docs/gemini.md 'Error hierarchy'> :status
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
   parts — the two runtimes here share no common logging facility, and a
   silent best-effort extraction is the more useful library default."
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
