(ns tools.agents.anthropic.credentials
  "Anthropic Workload Identity Federation (WIF): exchange an external OIDC
   JWT (a Kubernetes projected service-account token, a CI OIDC token, ...)
   for a short-lived Anthropic access token via the RFC 7523 jwt-bearer
   grant, as a tools.agents.token/TokenSource.

   Ported from anthropic-sdk-python v1.5.0 @ eb21a435
   (src/anthropic/lib/credentials/): `_workload.py`
   WorkloadIdentityCredentials, `_chain.py` _build_federation_result,
   `_constants.py`. Line citations below are against that commit.

     workload-identity-source   explicit opts -> TokenSource
     workload-identity-from-env ANTHROPIC_* env vars -> TokenSource or nil
     exchange-token!            one jwt-bearer POST, no caching

   `tools.agents.anthropic/client` calls `workload-identity-from-env` as
   step 4 of its credential chain, so a process with the WIF env vars set
   and no ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN needs no code changes.

   Errors are ex-info under the anthropic error namespace:
     :tools.agents.anthropic.error/token-exchange       endpoint unreachable,
        non-2xx, oversized or malformed response, oversized assertion.
        ex-data {:type :status :body :request-id}; :body is redacted to the
        RFC 6749 §5.2 fields (error, error_description, error_uri) or a
        256-char prefix, with the assertion scrubbed.
     :tools.agents.anthropic.error/identity-token        the identity token
        file or fn could not produce a token. ex-data {:type :path}.
     :tools.agents.anthropic.error/invalid-credentials  bad options,
        including a cleartext non-loopback base-url.
   Neither the assertion nor the access token ever appears in an error
   message or ex-data.

   Depends on tools.agents.http and tools.agents.json only, never on
   tools.agents.anthropic (which requires this namespace)."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.token :as token]))

;; ---------------------------------------------------------------------------
;; Constants (_constants.py, _workload.py @ eb21a435)
;; ---------------------------------------------------------------------------

(def default-base-url "https://api.anthropic.com")

(def grant-type-jwt-bearer
  "_constants.py:10 GRANT_TYPE_JWT_BEARER."
  "urn:ietf:params:oauth:grant-type:jwt-bearer")

(def token-endpoint
  "_constants.py:12 TOKEN_ENDPOINT, appended to the base URL."
  "/v1/oauth/token")

(def jwt-bearer-beta-header
  "`anthropic-beta` for the jwt-bearer POST (_workload.py:36): BOTH
   oauth-2025-04-20 (_constants.py:21) and the routing switch
   oidc-federation-2026-04-01 (_constants.py:28). The switch must ONLY be
   sent on jwt-bearer exchanges: on a refresh_token grant it misroutes the
   request (_constants.py:23-27)."
  "oauth-2025-04-20,oidc-federation-2026-04-01")

(def token-exchange-timeout-ms
  "_constants.py:16 TOKEN_EXCHANGE_TIMEOUT = 30.0 s."
  30000)

(def default-refresh-skew-ms
  "Refresh this long before expiry: the SDK's advisory window,
   _constants.py:31 ADVISORY_REFRESH_SECONDS = 120. tools.agents.token's
   cache is single-tier, so a failed refresh inside this window throws
   instead of serving the still-valid token (the SDK serves stale until
   MANDATORY_REFRESH_SECONDS = 30, _cache.py:118-129)."
  120000)

(def ^:private max-assertion-bytes (* 16 1024))   ; _workload.py:49
(def ^:private max-response-bytes (bit-shift-left 1 20)) ; _workload.py:50
(def ^:private max-error-body-chars 256)          ; _workload.py:42

(def ^:private env-identity-token "ANTHROPIC_IDENTITY_TOKEN")           ; _constants.py:46
(def ^:private env-identity-token-file "ANTHROPIC_IDENTITY_TOKEN_FILE") ; _constants.py:47
(def ^:private env-federation-rule-id "ANTHROPIC_FEDERATION_RULE_ID")   ; _constants.py:48
(def ^:private env-organization-id "ANTHROPIC_ORGANIZATION_ID")         ; _constants.py:49
(def ^:private env-service-account-id "ANTHROPIC_SERVICE_ACCOUNT_ID")   ; _constants.py:50
(def ^:private env-workspace-id "ANTHROPIC_WORKSPACE_ID")               ; _constants.py:51
(def ^:private env-base-url "ANTHROPIC_BASE_URL")                       ; _constants.py:53

(def ^:private json-codec
  (json/codec {:prefix      "tools.agents.anthropic"
               :encode-type :tools.agents.anthropic.error/json-encode
               :parse-type  :tools.agents.anthropic.error/json-parse}))

;; ---------------------------------------------------------------------------
;; Errors and redaction
;; ---------------------------------------------------------------------------

(defn- invalid-options! [msg]
  (throw (ex-info (str "tools.agents.anthropic.credentials/workload-identity-source: " msg)
                  {:type :tools.agents.anthropic.error/invalid-credentials})))

(defn- scrub
  "Replace every occurrence of each secret in s. The SDK only truncates
   (_workload.py:58-73); an endpoint echoing the assertion inside its first
   256 chars would still leak it there, so this also scrubs."
  [s secrets]
  (reduce (fn [acc secret]
            (if (and (string? secret) (seq secret))
              (str/replace acc secret "[REDACTED]")
              acc))
          s secrets))

(defn- redact-body
  "_workload.py:58-73 _redact_body: a JSON object keeps only the RFC 6749
   §5.2 error fields, a string is cut to 256 chars, anything else is nil."
  [body secrets]
  (cond
    (nil? body) nil
    (string? body)
    (let [s (scrub body secrets)]
      (if (<= (count s) max-error-body-chars)
        s
        (str (subs s 0 max-error-body-chars) "... <" (- (count s) max-error-body-chars) " more chars>")))
    (map? body)
    ;; Only string values survive: the SDK keeps any value verbatim, so a
    ;; nested object echoing the assertion would reach ex-data unscrubbed.
    (into {} (keep (fn [k] (let [v (get body k)]
                             (when (string? v) [k (scrub v secrets)]))))
          ["error" "error_description" "error_uri"])
    :else nil))

(defn- header [headers lc-name]
  (let [v (get headers lc-name)]
    (if (sequential? v) (first v) v)))

(defn- exchange-error!
  ([msg] (exchange-error! msg nil))
  ([msg {:keys [status body request-id]}]
   (throw (ex-info (str "tools.agents.anthropic.credentials/exchange-token!: " msg
                        (when request-id (str " [request_id=" request-id "]")))
                   {:type       :tools.agents.anthropic.error/token-exchange
                    :status     status
                    :body       body
                    :request-id request-id}))))

;; ---------------------------------------------------------------------------
;; Base URL (_constants.py:116-132 _require_https)
;; ---------------------------------------------------------------------------

(defn- require-https!
  "The token POST carries the assertion, so only https:// or a loopback
   http:// URL is accepted."
  [url]
  (let [lowered (str/lower-case (str url))]
    (when-not (or (str/starts-with? lowered "https://")
                  (str/starts-with? lowered "http://localhost")
                  (str/starts-with? lowered "http://127.0.0.1")
                  (str/starts-with? lowered "http://[::1]"))
      (invalid-options! (str "base-url must use https (got " (pr-str url) "); the token-exchange "
                             "endpoint carries secret material and cannot be used over cleartext HTTP.")))))

(defn- strip-trailing-slashes [s]
  (str/replace s #"/+$" ""))

;; ---------------------------------------------------------------------------
;; Identity token providers
;; ---------------------------------------------------------------------------

(defn- identity-token-error! [msg path]
  (throw (ex-info (str "tools.agents.anthropic.credentials/identity-token: " msg)
                  {:type :tools.agents.anthropic.error/identity-token :path path})))

(defn identity-token-file
  "A zero-arg fn reading the identity token from `path` on EVERY call,
   trimmed: Kubernetes rotates projected tokens in place
   (_providers.py:736-781 IdentityTokenFile). Throws
   :tools.agents.anthropic.error/identity-token when the file is missing,
   unreadable, a directory or empty."
  [path]
  (let [path (str path)]
    (fn []
      (let [f (java.io.File. path)]
        (cond
          (.isDirectory f)
          (identity-token-error! (str "identity token path " path " is a directory, not a file. "
                                      "Point at the projected token file itself.") path)
          (not (.exists f))
          (identity-token-error! (str "identity token file not found at " path ".") path))
        (let [content (try (str/trim (slurp f :encoding "UTF-8"))
                           (catch Exception e
                             (identity-token-error! (str "identity token file at " path
                                                         " could not be read: " (.getName (class e)))
                                                    path)))]
          (when (str/blank? content)
            (identity-token-error! (str "identity token file at " path " is empty. If this is a "
                                        "Kubernetes projected service-account token, check the volume "
                                        "mount and the serviceAccountToken projection audience.") path))
          content)))))

;; ---------------------------------------------------------------------------
;; The exchange (_workload.py:249-364 WorkloadIdentityCredentials.__call__)
;; ---------------------------------------------------------------------------

(defn- parse-expires-in
  "Python's int(x) on a JSON number or numeric string; nil when unparseable."
  [v]
  (cond
    (integer? v) (long v)
    (and (number? v) (not (ratio? v))) (long v)
    (string? v) (try (Long/parseLong (str/trim v)) (catch Exception _ nil))
    :else nil))

(defn- json-type-name [v]
  (cond (vector? v) "list" (string? v) "str" (number? v) "number"
        (boolean? v) "bool" (nil? v) "null" :else "value"))

(defn exchange-token!
  "Perform ONE jwt-bearer exchange and return {:token String :expires-at
   epoch-ms}, the shape tools.agents.token/token-cache's :fetch! returns.

   opts:
     :assertion           the external OIDC JWT, required
     :federation-rule-id  required
     :organization-id     required
     :service-account-id  optional, sent when non-nil
     :workspace-id        optional, sent when non-nil
     :base-url            default https://api.anthropic.com; POSTs to
                          {base-url}/v1/oauth/token
     :http-fn             default tools.agents.http/request!
     :timeout-ms          default 30000
     :now-ms              clock, default System/currentTimeMillis

   No retry here: the SDK POSTs once (_workload.py:276-289); its token
   cache retries a 401 once (_cache.py:96-100), which
   workload-identity-source mirrors."
  [{:keys [assertion federation-rule-id organization-id service-account-id workspace-id
           base-url http-fn timeout-ms now-ms]
    :or   {base-url   default-base-url
           http-fn    http/request!
           timeout-ms token-exchange-timeout-ms
           now-ms     #(System/currentTimeMillis)}}]
  (when-not (string? assertion)
    (exchange-error! "identity token provider returned no String assertion."))
  (let [assertion-bytes (alength (.getBytes ^String assertion "UTF-8"))]
    (when (> assertion-bytes max-assertion-bytes)
      (exchange-error! (str "identity token assertion is " assertion-bytes " bytes, which exceeds the "
                            max-assertion-bytes "-byte limit. This is almost certainly not a JWT; check "
                            "that the identity-token path points at the projected token, not a key or cert."))))
  (let [base    (strip-trailing-slashes base-url)
        _       (require-https! base)
        url     (str base token-endpoint)
        secrets [assertion]
        body    (cond-> {"grant_type"         grant-type-jwt-bearer
                         "assertion"          assertion
                         "federation_rule_id" federation-rule-id
                         "organization_id"    organization-id}
                  (some? service-account-id) (assoc "service_account_id" service-account-id)
                  (some? workspace-id)       (assoc "workspace_id" workspace-id))
        resp    (try
                  (http-fn {:method     :post
                            :url        url
                            :headers    {"anthropic-beta" jwt-bearer-beta-header
                                         "content-type"   "application/json"}
                            :body       ((:write json-codec) body)
                            :as         :bytes
                            :timeout-ms timeout-ms})
                  (catch Exception e
                    ;; The class name only: a transport message never
                    ;; carries the body, but a custom http-fn's might.
                    (exchange-error! (str "failed to reach token endpoint " url ": "
                                          (.getName (class e))
                                          (when-let [m (ex-message e)] (str ": " (scrub m secrets)))))))
        status     (:status resp)
        request-id (header (:headers resp) "request-id")
        raw        (:body resp)
        bs         (cond (bytes? raw) raw
                         (string? raw) (.getBytes ^String raw "UTF-8")
                         :else (byte-array 0))]
    (when (> (alength ^bytes bs) max-response-bytes)
      (exchange-error! (str "token endpoint response body exceeds " max-response-bytes " bytes (got "
                            (alength ^bytes bs) "); refusing to parse.")
                       {:status status :request-id request-id}))
    (let [text   (String. ^bytes bs "UTF-8")
          parsed (try {:ok ((:read json-codec) text)} (catch Exception _ {:invalid true}))]
      (when (and status (>= status 400))
        (let [redacted (redact-body (if (:invalid parsed) text (:ok parsed)) secrets)
              hint     (when (= status 401)
                         (str "Ensure your federation rule matches your identity token. "
                              (when (nil? workspace-id)
                                (str "If your federation rule is scoped to multiple workspaces, set the "
                                     "ANTHROPIC_WORKSPACE_ID environment variable or the :workspace-id option. "))
                              "View your authentication events in the Workload identity page of Claude "
                              "Console for more details."))]
          (exchange-error! (str "token exchange failed (HTTP " status "): " (pr-str redacted)
                                (when hint (str " " hint)))
                           {:status status :body redacted :request-id request-id})))
      (when (:invalid parsed)
        (let [redacted (redact-body text secrets)]
          (exchange-error! (str "token endpoint returned non-JSON response (status " status "): "
                                (pr-str redacted))
                           {:status status :body redacted :request-id request-id})))
      (let [data (:ok parsed)]
        (when-not (map? data)
          (exchange-error! (str "token endpoint returned a JSON " (json-type-name data) " (status " status
                                "); expected an object.")
                           {:status status :request-id request-id}))
        (let [token-type (get data "token_type")]
          (when (and (some? token-type) (not= "bearer" (str/lower-case (str token-type))))
            (exchange-error! (str "token endpoint returned unsupported token_type "
                                  (pr-str (scrub (str token-type) secrets)) " (expected 'Bearer').")
                             {:status status :body (redact-body data secrets) :request-id request-id})))
        (let [tok        (get data "access_token")
              expires-in (parse-expires-in (get data "expires_in"))]
          (when-not (and (string? tok) (seq tok) (some? expires-in))
            (exchange-error! "token endpoint response missing required fields (access_token / expires_in)."
                             {:status status :body (redact-body data (conj secrets tok))
                              :request-id request-id}))
          {:token tok :expires-at (+ (now-ms) (* 1000 expires-in))})))))

;; ---------------------------------------------------------------------------
;; TokenSource constructors
;; ---------------------------------------------------------------------------

(defn- non-blank-string? [x] (and (string? x) (not (str/blank? x))))

(defn workload-identity-source
  "A tools.agents.token/token-cache TokenSource whose fetch! runs a
   jwt-bearer exchange (`exchange-token!`). Pass it to
   `tools.agents.anthropic/client` as :credential-source, with the SAME
   :base-url: a token is only valid on the deployment that minted it
   (_workload.py:183-188).

   opts:
     :federation-rule-id   required (fdrl_...)
     :organization-id      required (organization UUID)
     exactly one of:
       :identity-token-file  path re-read on every exchange (`identity-token-file`)
       :identity-token-fn    (fn [] jwt-string), called on every exchange
     :service-account-id   optional (svac_...)
     :workspace-id         optional (wrkspc_... or \"default\"); the minted
                           token is workspace-scoped
     :scope                accepted and IGNORED, as in the SDK: the server
                           derives scope from the rule (_workload.py:178-182)
     :base-url             default https://api.anthropic.com; https:// or
                           loopback http:// only
     :http-fn              default tools.agents.http/request!
     :timeout-ms           per exchange, default 30000
     :refresh-skew-ms      default `default-refresh-skew-ms` (120000)
     :now-ms               clock, default System/currentTimeMillis

   Every exchange calls the identity token provider again. An exchange
   answered with HTTP 401 is retried once, re-reading the identity token
   (_cache.py:88-103). Nothing is fetched at construction."
  [{:keys [federation-rule-id organization-id identity-token-file identity-token-fn
           service-account-id workspace-id base-url http-fn timeout-ms refresh-skew-ms now-ms]
    :or   {base-url        default-base-url
           http-fn         http/request!
           timeout-ms      token-exchange-timeout-ms
           refresh-skew-ms default-refresh-skew-ms
           now-ms          #(System/currentTimeMillis)}}]
  (when-not (non-blank-string? federation-rule-id)
    (invalid-options! ":federation-rule-id is required"))
  (when-not (non-blank-string? organization-id)
    (invalid-options! ":organization-id is required"))
  (when (= (some? identity-token-file) (some? identity-token-fn))
    (invalid-options! "pass exactly one of :identity-token-file or :identity-token-fn"))
  (when (and identity-token-fn (not (ifn? identity-token-fn)))
    (invalid-options! ":identity-token-fn must be a function"))
  (when-not (string? base-url)
    (invalid-options! ":base-url must be a String"))
  (require-https! (strip-trailing-slashes base-url))
  (let [provider (or identity-token-fn (tools.agents.anthropic.credentials/identity-token-file identity-token-file))
        exchange #(exchange-token! {:assertion          (provider)
                                    :federation-rule-id federation-rule-id
                                    :organization-id    organization-id
                                    :service-account-id service-account-id
                                    :workspace-id       workspace-id
                                    :base-url           base-url
                                    :http-fn            http-fn
                                    :timeout-ms         timeout-ms
                                    :now-ms             now-ms})]
    (token/token-cache
     {:fetch!          (fn []
                         (try
                           (exchange)
                           (catch clojure.lang.ExceptionInfo e
                             (let [{:keys [type status]} (ex-data e)]
                               (if (and (= type :tools.agents.anthropic.error/token-exchange) (= status 401))
                                 (exchange)
                                 (throw e))))))
      :refresh-skew-ms refresh-skew-ms
      :now-ms          now-ms})))

(defn workload-identity-from-env
  "A `workload-identity-source` configured from environment variables, or
   nil when WIF is not configured (_chain.py:29-73
   _build_federation_result).

   Configured means ANTHROPIC_FEDERATION_RULE_ID and
   ANTHROPIC_ORGANIZATION_ID are non-empty AND either
   ANTHROPIC_IDENTITY_TOKEN_FILE is non-empty or ANTHROPIC_IDENTITY_TOKEN is
   set (even empty). The file wins over the literal. The literal is read
   through getenv on every exchange, so a rotated value is picked up.
   Optional: ANTHROPIC_SERVICE_ACCOUNT_ID (sent when set, even empty, as in
   the SDK), ANTHROPIC_WORKSPACE_ID (empty means unset), ANTHROPIC_SCOPE
   (ignored, see workload-identity-source).

   opts (all optional):
     :getenv    (fn [name] value-or-nil), default System/getenv. Tests pass
                a map lookup instead of touching the process env.
     :base-url  default ANTHROPIC_BASE_URL, else https://api.anthropic.com.
                tools.agents.anthropic/client passes its own resolved
                base-url.
     and :http-fn :timeout-ms :refresh-skew-ms :now-ms as in
     workload-identity-source.

   This does NOT consult ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN; the
   client's chain checks those first."
  ([] (workload-identity-from-env {}))
  ([{:keys [getenv base-url] :or {getenv #(System/getenv ^String %)} :as opts}]
   (let [rule-id    (getenv env-federation-rule-id)
         org-id     (getenv env-organization-id)
         token-file (getenv env-identity-token-file)
         token-file (when (seq token-file) token-file)
         literal?   (some? (getenv env-identity-token))]
     (when (and (seq rule-id) (seq org-id) (or token-file literal?))
       (do
         (workload-identity-source
          (merge (select-keys opts [:http-fn :timeout-ms :refresh-skew-ms :now-ms])
                 {:federation-rule-id rule-id
                  :organization-id    org-id
                  :service-account-id (getenv env-service-account-id)
                  :workspace-id       (let [w (getenv env-workspace-id)] (when (seq w) w))
                  :base-url           (or base-url (let [b (getenv env-base-url)] (when (seq b) b))
                                          default-base-url)}
                 (if token-file
                   {:identity-token-file token-file}
                   {:identity-token-fn
                    (fn []
                      (or (getenv env-identity-token)
                          (identity-token-error!
                           (str env-identity-token " is not set; the workload-identity chain selected "
                                "this provider at construction time but the env var is no longer present.")
                           nil)))}))))))))
