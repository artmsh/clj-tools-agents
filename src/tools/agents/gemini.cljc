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

   PORTABILITY: all network I/O goes through `tools.agents.http/request!`,
   the shared HTTP request function tools.agents.anthropic and
   tools.agents.openai use too, which needs no reader conditional.
   Everything else — URL/header building, the hand-rolled JSON codec,
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
   two runtimes share no common logging facility). A :credential-source
   (tools.agents.token/TokenSource, e.g. an OAuth access-token cache) is sent
   as `Authorization: Bearer <token>` instead, fetched per attempt.

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

   STREAMING: `generate-content-stream` is python-genai's
   `client.models.generate_content_stream`. The streaming variant is a
   SEPARATE endpoint (`:streamGenerateContent?alt=sse`), not a `:stream true`
   request flag the way anthropic/openai model it, so there is no flag to
   reject on `generate-content`. It returns a single-use reducible (see
   tools.agents.stream) of decoded GenerateContentResponse chunks; retries
   cover only the opening request. `accumulate-chunk`/`accumulate-stream`
   join the chunks into one response that `output-text` reads. Gemini sends
   NO terminal event: EOF is the normal end (`tools.agents.stream/outcome`
   is `:eof` for a complete stream AND for a cut-off one), so completeness
   is read from the data, via `stream-complete?` (a finishReason on every
   candidate, or a promptFeedback.blockReason). See docs/gemini.md
   'Streaming'."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.stream :as stream]
            [tools.agents.token :as token]))

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

(def ^:private json-opts
  {:prefix      "tools.agents.gemini"
   :encode-type :tools.agents.gemini/json-encode-error
   :parse-type  :tools.agents.gemini/json-parse-error})

(def ^:private json-codec (json/codec json-opts))

(defn client-codec
  "The JSON codec `client` uses for its wire traffic: the built-in one, or
   the client's injected :json bound to this namespace's error contract
   (tools.agents.json/wrap-codec). A map {:read :write :read-jsonl :key->str}."
  [client]
  (if-let [j (:json client)]
    (json/wrap-codec j json-opts)
    json-codec))

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
;; Process boundary — `getenv`, `sleep!` and `now-ms` are testability seams.
;; Network I/O is tools.agents.http/request!, called from post-json! below.
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

(defn- resolve-client-credentials
  "The full credential chain `client` uses: an explicit :credential-source,
   else `resolve-credentials`. Returns {:credential-source src} or
   {:api-key s}; further refreshable sources plug in here."
  [opts getenv-fn]
  (if (contains? opts :credential-source)
    (let [src (:credential-source opts)]
      (when-not (token/token-source? src)
        (throw (ex-info (str "tools.agents.gemini/client: :credential-source must satisfy "
                             "tools.agents.token/TokenSource, got: " (type src))
                        {:type :tools.agents.gemini/invalid-credentials})))
      (when (some? (:api-key opts))
        (throw (ex-info "tools.agents.gemini/client: pass either :api-key or :credential-source, not both"
                        {:type :tools.agents.gemini/invalid-credentials})))
      {:credential-source src})
    (resolve-credentials opts getenv-fn)))

(defn client
  "Build a GeminiClient record — the 'client object' analogue of Python's
   genai.Client(api_key=...) constructor. Resolves credentials eagerly (fails
   fast with a catchable ex-info BEFORE any network request).

   opts:
     :api-key      explicit API key -> sent as the `x-goog-api-key` header
                   (falls back to GOOGLE_API_KEY, then GEMINI_API_KEY)
     :credential-source  a tools.agents.token/TokenSource asked for a token
                   before every attempt -> `Authorization: Bearer <token>`
                   (e.g. an OAuth access token). Replaces :api-key and the
                   env vars; passing both throws
                   {:type :tools.agents.gemini/invalid-credentials}. A 401
                   invalidates the token and retries once outside
                   :max-retries.
     :base-url     override API root, default
                   `https://generativelanguage.googleapis.com` (also resolved
                   from GOOGLE_GEMINI_BASE_URL — not an official env var name,
                   this library's own convention, matching how tools.agents.
                   openai resolves OPENAI_BASE_URL)
     :api-version  URL version segment, default \"v1beta\" (python-genai's
                   own default for the Gemini Developer API)
     :max-retries  how many times to retry a failed request, default 4
                   (`default-max-retries`). 0 disables retries.
     :http         request fn replacing tools.agents.http/request! for every
                   exchange, streaming included. Same contract as request!:
                   takes its request map, returns {:status :headers :body}
                   for any status, throws only on transport failure; an :as
                   :stream body should be an InputStream (a String or byte[]
                   is accepted). Anything but a fn throws
                   {:type :tools.agents.gemini/invalid-options}.
     :json         JSON codec {:read (fn [String]) :write (fn [value])} for
                   request bodies, responses, error bodies and stream chunks.
                   :read must yield string-keyed maps. Anything it throws
                   becomes :json-parse-error / :json-encode-error with the
                   original as cause. The public read-json/write-json stay
                   built-in. A non-codec throws
                   {:type :tools.agents.gemini/invalid-options}."
  ([] (client {}))
  ([opts]
   (when (and (contains? opts :json) (not (json/codec-map? (:json opts))))
     (throw (ex-info (str "tools.agents.gemini/client: :json must be a map {:read (fn [s]) :write (fn [v])}, got: "
                          (pr-str (:json opts)))
                     {:type :tools.agents.gemini/invalid-options :option :json})))
   (when (and (contains? opts :http) (not (http/request-fn? (:http opts))))
     (throw (ex-info (str "tools.agents.gemini/client: :http must be a request fn with "
                          "tools.agents.http/request!'s contract, got: " (pr-str (type (:http opts))))
                     {:type :tools.agents.gemini/invalid-options :option :http})))
   (let [creds (resolve-client-credentials opts getenv)]
     (map->GeminiClient
      (cond-> (merge {:base-url    (or (:base-url opts) (getenv "GOOGLE_GEMINI_BASE_URL") default-base-url)
                      :api-version (or (:api-version opts) default-api-version)
                      :max-retries (if (number? (:max-retries opts))
                                     (max 0 (long (:max-retries opts)))
                                     default-max-retries)}
                     creds)
        (:http opts) (assoc :http (:http opts))
        (:json opts) (assoc :json (:json opts)))))))

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
  [codec body]
  (when (seq body)
    (try
      (let [parsed ((:read codec) body)]
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

(defn- request-headers
  "Headers for ONE attempt, plus the token they carry (nil for a static key):
   [headers used-token]. A :credential-source fetch failure propagates as-is."
  [fn-name client]
  (if-let [src (:credential-source client)]
    (let [tok (token/token! src)]
      [{"authorization" (str "Bearer " tok)
        "content-type"  "application/json"}
       tok])
    (do
      (when-not (:api-key client)
        (throw (ex-info (str "tools.agents.gemini/" fn-name ": client has no :api-key — build it via "
                             "tools.agents.gemini/client")
                        {:type :tools.agents.gemini/missing-credentials})))
      [{"x-goog-api-key" (:api-key client)
        "content-type"   "application/json"}
       nil])))

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

(defn- http-status-error
  "The typed ex-info for a final non-2xx response."
  [codec fn-name status resp-body retries-taken]
  (let [err-msg (extract-error-message codec resp-body)
        detail  (cond err-msg err-msg (seq resp-body) resp-body :else nil)]
    (ex-info (str "tools.agents.gemini/" fn-name ": HTTP " status (when detail (str " " detail)))
             {:type (status->type status) :status status :body resp-body
              :retries-taken retries-taken})))

(defn- post-json!
  "Shared transport for generate-content/count-tokens: build URL + headers,
   encode the request map, POST it, classify the status, decode the body —
   retrying transport failures and retryable statuses per retryable-status?/
   retry-delay-ms (:max-retries on the client, default 4).

   Non-retryable failures, and retryable ones once the budget is spent, throw
   with :retries-taken in ex-data. A malformed body on an otherwise-successful
   2xx is NOT retried. A :credential-source client's first 401 invalidates
   the token it sent and retries once outside the budget; a static key's 401
   is never retried."
  [client fn-name model method request]
  (let [url         (endpoint-url client model method)
        codec       (client-codec client)
        body-str    ((:write codec) request)
        max-retries (resolve-max-retries client)]
    (loop [retries-taken 0
           auth-retried? false]
      ;; Headers are rebuilt on every attempt, so credentials that change
      ;; between attempts (a refreshed token) land on the retry. Built outside
      ;; the try: a token fetch failure is not a connection failure.
      (let [[headers used-token] (request-headers fn-name client)
            outcome (try
                      {:resp ((or (:http client) http/request!) {:method :post :url url
                                             :headers headers
                                             :body body-str})}
                      (catch Exception e
                        (if (own-error? e) (throw e) {:error e})))]
        (if (:error outcome)
          ;; No response at all: DNS, connection refused, TLS handshake,
          ;; timeout. Retried the same as a retryable status.
          (if (< retries-taken max-retries)
            (do (*sleep-fn* (retry-delay-ms retries-taken))
                (recur (inc retries-taken) auth-retried?))
            (throw (ex-info (str "tools.agents.gemini/" fn-name ": connection failed: " (str (:error outcome)))
                             {:type :tools.agents.gemini/api-connection-error :status nil :body nil
                              :retries-taken retries-taken})))
          (let [resp      (:resp outcome)
                status    (:status resp)
                resp-body (:body resp)]
            (cond
              (and status (>= status 200) (< status 300))
              ((:read codec) resp-body)

              (and (= status 401) (not auth-retried?) (:credential-source client)
                   (token/invalidate! (:credential-source client) used-token))
              (recur retries-taken true)

              (and (< retries-taken max-retries) (retryable-status? status))
              (do (*sleep-fn* (retry-delay-ms retries-taken))
                  (recur (inc retries-taken) auth-retried?))

              :else
              (throw (http-status-error codec fn-name status resp-body retries-taken)))))))))

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
;; Public API — streaming
;; ---------------------------------------------------------------------------

(defn- error-chunk?
  "python-genai `_api_client.request_streamed` raises on a chunk whose JSON
   starts with `{\"error\":`; here: any chunk with a top-level \"error\" map."
  [chunk]
  (and (map? chunk) (map? (get chunk "error"))))

(defn- stream-error
  "The typed ex-info for an in-stream error chunk. Like python-genai's
   `APIError.raise_error(error.code, ...)`, `error.code` stands in for the
   status: it picks the :type and is :status (nil when not an integer)."
  [fn-name chunk body retries-taken]
  (let [err    (get chunk "error")
        code   (get err "code")
        status (when (integer? code) code)
        msg    (get err "message")]
    (ex-info (str "tools.agents.gemini/" fn-name ": stream error"
                  (when status (str " " status)) (when (string? msg) (str " " msg)))
             {:type (status->type status) :status status :body body
              :retries-taken retries-taken})))

(defn generate-content-stream
  "POST request (same map as generate-content) to POST {base-url}/
   {api-version}/models/{model}:streamGenerateContent?alt=sse — python-genai's
   client.models.generate_content_stream(model=model, contents=...,
   config=request), whose path is literally
   '{model}:streamGenerateContent?alt=sse'.

   The request is sent NOW, with generate-content's retry policy
   (retryable-status?/retry-delay-ms, :max-retries on the client) applied to
   the opening exchange only; once a 2xx arrives nothing is retried. A final
   non-2xx or connection failure throws from this call with the same
   :type/:status/:body/:retries-taken ex-data as generate-content.

   Returns a SINGLE-USE reducible (tools.agents.stream/open-event-stream) of
   decoded GenerateContentResponse chunk maps:

     (let [s (generate-content-stream client \"gemini-2.5-flash\" req)]
       (run! #(print (output-text %)) s))               ; per-chunk text
     (output-text (accumulate-stream
                    (generate-content-stream client model req))) ; joined

   Reducing closes the connection (EOF, early termination, exception). A
   stream that is never reduced must be released with
   (tools.agents.stream/close! s), which is also the cross-thread cancel;
   `.close`/with-open work on the JVM only. (tools.agents.stream/response s)
   gives the 2xx {:status :headers}.

   While reducing, throws:
     - an error chunk ({\"error\" {\"code\" c \"message\" m}}) — ex-info typed
       from `c` as a status (e.g. 429 -> :resource-exhausted-error), :body
       the raw data string;
     - a mid-stream transport failure — :tools.agents.gemini/api-connection-error;
     - an undecodable chunk — :tools.agents.gemini/json-parse-error.

   There is no terminal event: EOF ends a complete stream and a cut-off one
   alike, and (tools.agents.stream/outcome s) is :eof for both. Check
   `stream-complete?` on the accumulated response."
  [client model request]
  (let [fn-name     "generate-content-stream"
        url         (str (endpoint-url client model "streamGenerateContent") "?alt=sse")
        codec       (client-codec client)
        body-str    ((:write codec) request)
        max-retries (resolve-max-retries client)
        retries     (atom 0)
        ;; Headers for the attempt about to run, set by open! before each
        ;; (attempt) and read by send!, so a refreshed token lands on retries.
        headers     (volatile! nil)
        backoff!    (fn [n] (*sleep-fn* (retry-delay-ms n)) (swap! retries inc))
        open!       (fn [attempt]
                      (loop [auth-retried? false]
                        ;; Outside the try: a token fetch failure is not a
                        ;; connection failure.
                        (let [[hs used-token] (request-headers fn-name client)
                              _       (vreset! headers hs)
                              n       @retries
                              outcome (try {:resp (attempt)}
                                           (catch Exception e
                                             (if (own-error? e) (throw e) {:error e})))]
                          (if-let [e (:error outcome)]
                            (if (< n max-retries)
                              (do (backoff! n) (recur auth-retried?))
                              (throw (ex-info (str "tools.agents.gemini/" fn-name ": connection failed: " e)
                                              {:type :tools.agents.gemini/api-connection-error :status nil
                                               :body nil :retries-taken n})))
                            (let [status (:status (:resp outcome))]
                              (cond
                                (and (= status 401) (not auth-retried?) (:credential-source client)
                                     (token/invalidate! (:credential-source client) used-token))
                                (recur true)

                                (and (not (and status (<= 200 status 299)))
                                     (retryable-status? status)
                                     (< n max-retries))
                                (do (backoff! n) (recur auth-retried?))

                                :else
                                (:resp outcome)))))))]
    (stream/open-event-stream
     {:request       {:method :post :url url :body body-str}
      :send!         (fn [req] ((or (:http client) http/request!) (assoc req :headers @headers)))
      :open!         open!
      :on-error      (fn [{:keys [status body]}]
                       (throw (http-status-error codec fn-name status body @retries)))
      :decode        (fn [data]
                       (let [chunk ((:read codec) data)]
                         (if (error-chunk? chunk)
                           (throw (stream-error fn-name chunk data @retries))
                           chunk)))
      :xform         (map :data)
      :on-read-error (fn [e]
                       (ex-info (str "tools.agents.gemini/" fn-name ": connection failed mid-stream: " e)
                                {:type :tools.agents.gemini/api-connection-error :status nil :body nil
                                 :retries-taken @retries}
                                e))})))

;; ---------------------------------------------------------------------------
;; Stream accumulation — pure
;; ---------------------------------------------------------------------------
;; python-genai has no chunk-joining helper: generate_content_stream yields
;; chunks and Chat.send_message_stream stores each chunk's Content unmerged.
;; The join below ports the deprecated google-generativeai SDK's
;; `generation_types._join_chunks` (google-gemini/deprecated-generative-ai-
;; python @ 7a7cc54), the documented merge behind its streamed
;; `response.resolve()`, adapted to REST JSON; see docs/gemini.md 'Streaming'.

(defn- text-part? [part]
  (and (map? part) (string? (get part "text"))))

(defn- join-parts
  "_join_contents' merge of two ADJACENT parts, or nil when they stay
   separate. Text + text concatenate, but only when both carry the same
   `thought` flag (a divergence: the old SDK predates thinking, and merging
   across that boundary would break output-text's thought exclusion); the
   later part's other keys (e.g. thoughtSignature) win. executableCode joins
   `code` (language from the first), codeExecutionResult joins `output`
   (outcome from the later)."
  [a b]
  (cond
    (and (text-part? a) (text-part? b)
         (= (true? (get a "thought")) (true? (get b "thought"))))
    (assoc (merge a b) "text" (str (get a "text") (get b "text")))

    (and (map? (get a "executableCode")) (map? (get b "executableCode")))
    (update-in a ["executableCode" "code"] str (get-in b ["executableCode" "code"]))

    (and (map? (get a "codeExecutionResult")) (map? (get b "codeExecutionResult")))
    (-> a
        (update-in ["codeExecutionResult" "output"] str (get-in b ["codeExecutionResult" "output"]))
        (assoc-in ["codeExecutionResult" "outcome"] (get-in b ["codeExecutionResult" "outcome"])))))

(defn- append-parts [parts new-parts]
  (reduce (fn [acc part]
            (if-let [joined (when-let [prev (peek acc)] (join-parts prev part))]
              (conj (pop acc) joined)
              (conj acc part)))
          (vec parts)
          new-parts))

(defn- last-non-nil
  "`acc` with every non-nil entry of `m` (except `skip` keys) assoc'ed over it."
  [acc m skip]
  (reduce-kv (fn [a k v] (if (or (nil? v) (contains? skip k)) a (assoc a k v))) acc m))

(defn- join-candidate
  "_join_candidates: content parts appended and joined, role from the first
   chunk that has one; every other field (finishReason, safetyRatings,
   citationMetadata, groundingMetadata, tokenCount, ...) last non-nil."
  [acc cand]
  (let [base    (last-non-nil (or acc {}) cand #{"content"})
        content (get cand "content")]
    (if (map? content)
      (let [old   (get acc "content")
            parts (get content "parts")]
        (assoc base "content"
               (assoc (merge (dissoc content "parts") (dissoc old "parts"))
                      "parts" (append-parts (get old "parts" []) (if (sequential? parts) parts [])))))
      base)))

(defn- candidate-index [cand]
  (let [i (get cand "index")] (if (integer? i) i 0)))

(defn- join-candidate-list
  "_join_candidate_lists: chunks' candidates grouped by \"index\" (default 0),
   returned as a vector sorted by index."
  [cands new-cands]
  (reduce (fn [acc cand]
            (let [idx (candidate-index cand)
                  pos (first (keep-indexed (fn [i c] (when (= idx (candidate-index c)) i)) acc))]
              (if pos
                (update acc pos join-candidate cand)
                (vec (sort-by candidate-index (conj acc (join-candidate nil cand)))))))
          (vec cands)
          (filter map? new-cands)))

(defn accumulate-chunk
  "Reducing fn joining GenerateContentResponse chunks into one response
   (pure; the join is the deprecated google-generativeai SDK's
   `_join_chunks`, see docs/gemini.md 'Streaming'):

     - candidates grouped by \"index\" (default 0) into a VECTOR sorted by
       index; per candidate, content.parts appended with ADJACENT text parts
       concatenated when their `thought` flag matches (so output-text still
       skips thoughts), adjacent executableCode/codeExecutionResult parts
       joined, other parts (functionCall, inlineData, ...) kept as-is;
       content.role from the first chunk; finishReason, safetyRatings,
       citationMetadata, groundingMetadata etc. last non-nil
     - top-level usageMetadata, modelVersion, responseId and any other key:
       last non-nil (usage is cumulative per chunk)
     - promptFeedback: the first one seen

   Arities: [] -> nil, [acc] -> acc, [acc chunk] -> acc, so it works with
   reduce (init nil) and transduce. Throws the same typed ex-info as the
   stream on an error chunk ({\"error\" {...}}), so a fixture reduced
   without the transport still fails loudly."
  ([] nil)
  ([acc] acc)
  ([acc chunk]
   (when (error-chunk? chunk)
     (throw (stream-error "accumulate-chunk" chunk nil nil)))
   (let [acc       (or acc {})
         top       (last-non-nil acc chunk #{"candidates" "promptFeedback"})
         top       (if (and (nil? (get acc "promptFeedback")) (some? (get chunk "promptFeedback")))
                     (assoc top "promptFeedback" (get chunk "promptFeedback"))
                     top)
         new-cands (get chunk "candidates")]
     (if (sequential? new-cands)
       (assoc top "candidates" (join-candidate-list (get acc "candidates" []) new-cands))
       top))))

(defn accumulate-stream
  "Reduce `chunks` — a generate-content-stream reducible (consumed and
   closed) or any collection of chunk maps — with accumulate-chunk. Returns
   the joined response map, or nil for no chunks. Never throws on a
   truncated stream; check `stream-complete?`."
  [chunks]
  (transduce identity accumulate-chunk chunks))

(defn stream-complete?
  "True when a (joined) response shows the stream ended on purpose: at least
   one candidate and every candidate has a \"finishReason\", or the prompt was
   blocked (promptFeedback.blockReason, which comes with no candidates).
   Gemini has no terminal SSE event, so a stream cut off at a chunk boundary
   looks like a clean EOF; this is the only signal. The same criterion
   python-genai's Chat.send_message_stream uses to keep a streamed turn out
   of its curated history (finish_reason is None -> invalid)."
  [response]
  (let [cands (get response "candidates")]
    (boolean
     (or (some? (get-in response ["promptFeedback" "blockReason"]))
         (and (sequential? cands) (seq cands)
              (every? #(some? (get % "finishReason")) cands))))))

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
