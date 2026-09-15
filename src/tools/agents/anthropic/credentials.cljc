(ns tools.agents.anthropic.credentials
  "Anthropic refreshable credentials as tools.agents.token/TokenSources:

   - Workload Identity Federation (WIF): exchange an external OIDC JWT (a
     Kubernetes projected service-account token, a CI OIDC token, ...) for a
     short-lived Anthropic access token via the RFC 7523 jwt-bearer grant.
   - Profiles (#36): the `configs/<profile>.json` + `credentials/<profile>.json`
     pair under the Anthropic config directory (written by the `ant` CLI),
     either `user_oauth` (refresh_token grant with atomic 0600 write-back,
     or an externally rotated file) or `oidc_federation` (WIF with a
     cross-process disk cache).

   Ported from anthropic-sdk-python v1.5.0 @ eb21a435
   (src/anthropic/lib/credentials/): `_workload.py`
   WorkloadIdentityCredentials, `_providers.py` CredentialsFile, `_chain.py`
   default_credentials, `_constants.py`, `_cache.py`. Line citations below
   are against that commit.

     workload-identity-source   explicit opts -> TokenSource
     workload-identity-from-env ANTHROPIC_* env vars -> TokenSource or nil
     exchange-token!            one jwt-bearer POST, no caching
     profile-source             one named (or the active) profile -> profile map
     profile-from-env           chain step 3 (explicit selection) -> profile map or nil
     fallback-profile           chain step 5 (on-disk default) -> profile map or nil
     config-dir, active-profile path resolution

   A profile map is {:credential-source TokenSource :profile name
   :credential-headers {\"anthropic-workspace-id\" ...} :base-url s-or-nil}.

   `tools.agents.anthropic/client` calls `profile-from-env`,
   `workload-identity-from-env` and `fallback-profile` as steps 3, 4 and 5
   of its credential chain, so a process with no ANTHROPIC_API_KEY /
   ANTHROPIC_AUTH_TOKEN needs no code changes.

   Errors are ex-info under the anthropic error namespace:
     :tools.agents.anthropic.error/token-exchange       endpoint unreachable,
        non-2xx, oversized or malformed response, oversized assertion, a
        user_oauth credentials file without refresh_token, an oidc_federation
        profile without federation_rule_id / organization_id (the SDK's
        WorkloadIdentityError). ex-data {:type :status :body :request-id};
        :body is redacted to the RFC 6749 §5.2 fields (error,
        error_description, error_uri) or a 256-char prefix, with the
        assertion / refresh token scrubbed.
     :tools.agents.anthropic.error/identity-token        the identity token
        file or fn could not produce a token. ex-data {:type :path}.
     :tools.agents.anthropic.error/profile              a profile name,
        config file or credentials file is missing, malformed, unsafe
        (symlink, group/other permissions) or could not be written back
        (the SDK's CredentialsError). ex-data {:type :profile :path}.
     :tools.agents.anthropic.error/invalid-credentials  bad options,
        including a cleartext non-loopback base-url.
   No assertion, access token or refresh token ever appears in an error
   message or ex-data.

   Depends on tools.agents.http and tools.agents.json only, never on
   tools.agents.anthropic (which requires this namespace)."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.token :as token])
  (:import [java.nio.file CopyOption FileSystems Files LinkOption OpenOption Path
            StandardCopyOption StandardOpenOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission PosixFilePermissions]
           [java.nio.channels FileChannel]))

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

(defn- https-or-loopback? [url]
  (let [lowered (str/lower-case (str url))]
    (or (str/starts-with? lowered "https://")
        (str/starts-with? lowered "http://localhost")
        (str/starts-with? lowered "http://127.0.0.1")
        (str/starts-with? lowered "http://[::1]"))))

(defn- cleartext-message [field url]
  (str field " must use https (got " (pr-str url) "); the token-exchange "
       "endpoint carries secret material and cannot be used over cleartext HTTP."))

(defn- require-https!
  "The token POST carries the assertion, so only https:// or a loopback
   http:// URL is accepted."
  [url]
  (when-not (https-or-loopback? url)
    (invalid-options! (cleartext-message "base-url" url))))

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

;; ===========================================================================
;; Profiles (#36): configs/<profile>.json + credentials/<profile>.json
;; ===========================================================================

;; ---------------------------------------------------------------------------
;; Constants (_constants.py, _providers.py @ eb21a435)
;; ---------------------------------------------------------------------------

(def default-profile
  "_constants.py:34 DEFAULT_PROFILE."
  "default")

(def grant-type-refresh-token
  "_constants.py:11 GRANT_TYPE_REFRESH_TOKEN."
  "refresh_token")

(def refresh-beta-header
  "`anthropic-beta` for the refresh_token POST: oauth-2025-04-20 ONLY
   (_constants.py:21 OAUTH_API_BETA_HEADER, _providers.py:559-563). Adding
   oidc-federation-2026-04-01 routes the POST to the jwt-bearer handler,
   which rejects the grant (_constants.py:23-28)."
  "oauth-2025-04-20")

(def ^:private env-profile "ANTHROPIC_PROFILE")       ; _constants.py:43
(def ^:private env-config-dir "ANTHROPIC_CONFIG_DIR") ; _constants.py:42
(def ^:private env-scope "ANTHROPIC_SCOPE")           ; _constants.py:52

(def ^:private auth-type-oidc-federation "oidc_federation") ; _providers.py:83
(def ^:private auth-type-user-oauth "user_oauth")           ; _providers.py:84
(def ^:private credentials-file-type "oauth_token")         ; _providers.py:76
(def ^:private credentials-file-version "1.0")              ; _providers.py:80
(def ^:private default-refresh-expires-in 3600)             ; _providers.py:597

(def ^:private federation-disk-margin-ms
  "_constants.py:32 MANDATORY_REFRESH_SECONDS = 30: an oidc_federation disk
   token is reused only while now < expires_at - 30 s (_providers.py:655-660)."
  30000)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- profile-error!
  ([msg] (profile-error! msg nil))
  ([msg path]
   (throw (ex-info (str "tools.agents.anthropic.credentials/profile: " msg)
                   {:type :tools.agents.anthropic.error/profile
                    :path (some-> path str)}))))

(defn- anthropic-error? [e]
  (let [t (:type (ex-data e))]
    (and (keyword? t) (= "tools.agents.anthropic.error" (namespace t)))))

(defn- truthy?
  "Python truthiness for a parsed JSON value: nil, false, \"\", empty
   collections and 0 are falsy. The SDK tests `if not x` on profile fields."
  [v]
  (not (or (nil? v) (false? v)
           (and (string? v) (empty? v))
           (and (coll? v) (empty? v))
           (and (number? v) (zero? v)))))

(defn- non-empty [s] (when (and (string? s) (seq s)) s))

(defn- default-getenv [k] (System/getenv ^String k))
(defn- default-home [] (System/getProperty "user.home"))
(defn- default-now-ms [] (System/currentTimeMillis))
(defn- windows? [] (str/starts-with? (str (System/getProperty "os.name")) "Windows"))

(defn- posix-fs?
  "True where POSIX permission bits exist. The SDK gates its credentials-file
   checks on os.name == \"posix\" (_providers.py:369); on a non-POSIX
   filesystem (Windows) they are skipped and files are written without a
   mode, exactly as there."
  []
  (contains? (set (.supportedFileAttributeViews (FileSystems/getDefault))) "posix"))

;; ---------------------------------------------------------------------------
;; Paths (_constants.py:67-190)
;; ---------------------------------------------------------------------------

(defn config-dir
  "The Anthropic config directory as a String (_constants.py:67-85):
   ANTHROPIC_CONFIG_DIR (non-empty) > ~/.config/anthropic on Linux AND macOS
   (never ~/Library/Application Support) > %APPDATA%\\Anthropic on Windows
   (~/AppData/Roaming/Anthropic without APPDATA).

   opts: :getenv (fn [name]) default System/getenv; :home default the
   user.home system property."
  ([] (config-dir {}))
  ([{:keys [getenv home] :or {getenv default-getenv}}]
   (let [home (str (or home (default-home)))]
     (if-let [env (non-empty (getenv env-config-dir))]
       env
       (if (windows?)
         (str (java.io.File. (str (or (non-empty (getenv "APPDATA"))
                                      (java.io.File. (java.io.File. home "AppData") "Roaming")))
                             "Anthropic"))
         (str (java.io.File. (java.io.File. home ".config") "anthropic")))))))

(defn- validate-profile-name!
  "_constants.py:135-158: non-empty, no surrounding whitespace, no leading
   dot, no path separator, no NUL."
  [profile source]
  (when-not (and (string? profile) (seq profile))
    (profile-error! (str source " must not be empty.")))
  (when (not= profile (str/trim profile))
    (profile-error! (str source " " (pr-str profile) " has leading or trailing whitespace.")))
  (when (str/starts-with? profile ".")
    (profile-error! (str source " " (pr-str profile) " must not start with a dot.")))
  (doseq [sep (distinct ["/" "\\" java.io.File/separator])]
    (when (str/includes? profile sep)
      (profile-error! (str source " " (pr-str profile) " must not contain path separators; profiles are "
                           "filenames under the config directory. Pick a name without " (pr-str sep) "."))))
  (when (str/includes? profile (str (char 0)))
    (profile-error! (str source " " (pr-str profile) " must not contain null bytes.")))
  profile)

(defn- read-active-config-pointer
  "Trimmed <config-dir>/active_config; nil when missing, unreadable or empty
   (_constants.py:88-95)."
  [dir]
  (try (non-empty (str/trim (slurp (java.io.File. (str dir) "active_config") :encoding "UTF-8")))
       (catch Exception _ nil)))

(defn active-profile
  "The active profile name (_constants.py:98-113): ANTHROPIC_PROFILE
   (non-empty) > <config-dir>/active_config > \"default\", validated. Throws
   :tools.agents.anthropic.error/profile on an invalid name.

   opts: :getenv and :home as in `config-dir`; :config-dir overrides the
   directory."
  ([] (active-profile {}))
  ([{:keys [getenv] :or {getenv default-getenv} :as opts}]
   (if-let [env (non-empty (getenv env-profile))]
     (validate-profile-name! env env-profile)
     (if-let [pointer (read-active-config-pointer (or (:config-dir opts) (config-dir opts)))]
       (validate-profile-name! pointer "active_config pointer file")
       default-profile))))

(defn- profile-file
  "<dir>/<sub>/<profile>.json, which must canonicalize under dir so a
   symlinked configs/ or credentials/ cannot escape it (_constants.py:161-190).
   Returns the unresolved File."
  ^java.io.File [dir sub profile]
  (validate-profile-name! profile "profile name")
  (let [candidate (java.io.File. (java.io.File. (str dir) ^String sub) (str profile ".json"))
        b         (.toPath (.getCanonicalFile (java.io.File. (str dir))))
        c         (.toPath (.getCanonicalFile candidate))]
    (when-not (.startsWith c b)
      (profile-error! (str "resolved path " c " escapes config directory " b ".") c))
    candidate))

(defn- expand-user
  "pathlib.Path.expanduser for a leading ~ (_providers.py:348)."
  [s home]
  (cond
    (= s "~") (str home)
    (or (str/starts-with? s "~/") (str/starts-with? s "~\\")) (str home (subs s 1))
    :else s))

;; ---------------------------------------------------------------------------
;; Config file (_providers.py:87-116, 304-353)
;; ---------------------------------------------------------------------------

(defn- fill-missing-from-env
  "_providers.py:87-116: env vars fill only the fields the profile left
   absent or falsy; empty env values count as unset. client_id and
   credentials_path are never env-filled."
  [config getenv]
  (let [fill   (fn [m k env-var]
                 (if-let [v (and (not (truthy? (get m k))) (non-empty (getenv env-var)))]
                   (assoc m k v)
                   m))
        config (-> config
                   (fill "base_url" env-base-url)
                   (fill "organization_id" env-organization-id)
                   (fill "workspace_id" env-workspace-id))
        auth   (get config "authentication")
        auth   (condp = (get auth "type")
                 auth-type-oidc-federation
                 (let [auth (-> auth
                                (fill "federation_rule_id" env-federation-rule-id)
                                (fill "service_account_id" env-service-account-id)
                                (fill "scope" env-scope))
                       file (non-empty (getenv env-identity-token-file))]
                   (if (and (not (truthy? (get auth "identity_token"))) file)
                     (assoc auth "identity_token" {"source" "file" "path" file})
                     auth))
                 auth-type-user-oauth
                 (fill auth "scope" env-scope)
                 auth)]
    (assoc config "authentication" auth)))

(defn- load-config!
  "Read, parse and env-fill configs/<profile>.json. The config file is not
   secret, so JSON parse detail is kept in the message (_providers.py:309-341)."
  [^java.io.File f profile getenv]
  (when-not (.exists f)
    (profile-error! (str "Config file not found at " f " (profile " (pr-str profile) "). Set "
                         env-profile " to select a different profile, or set " env-config-dir
                         " to relocate the config directory.")
                    f))
  (let [text   (try (slurp f :encoding "UTF-8")
                    (catch Exception e
                      (profile-error! (str "Config file at " f " could not be read: " (.getName (class e))
                                           (when-let [m (ex-message e)] (str ": " m)))
                                      f)))
        config (try ((:read json-codec) text)
                    (catch Exception e
                      (profile-error! (str "Config file at " f " is not valid JSON: " (ex-message e)) f)))]
    (when-not (map? config)
      (profile-error! (str "Config file at " f " must contain a JSON object, not "
                           (json-type-name config) ".")
                      f))
    (when-not (map? (get config "authentication"))
      (profile-error! (str "Config file at " f " is missing the 'authentication' object. Expected shape: "
                           "{\"authentication\": {\"type\": \"" auth-type-oidc-federation "\"|\""
                           auth-type-user-oauth "\", ...}, ...}")
                      f))
    (fill-missing-from-env config getenv)))

;; ---------------------------------------------------------------------------
;; Credentials file (_providers.py:355-417, 447-487)
;; ---------------------------------------------------------------------------

(def ^:private no-follow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(def ^:private perm-bits
  {PosixFilePermission/OWNER_READ     0400 PosixFilePermission/OWNER_WRITE     0200
   PosixFilePermission/OWNER_EXECUTE  0100 PosixFilePermission/GROUP_READ      040
   PosixFilePermission/GROUP_WRITE    020  PosixFilePermission/GROUP_EXECUTE   010
   PosixFilePermission/OTHERS_READ    04   PosixFilePermission/OTHERS_WRITE    02
   PosixFilePermission/OTHERS_EXECUTE 01})

(defn- read-credentials!
  "Read credentials/<profile>.json on every call (daemons rotate it). On
   POSIX, refuse a symlink or any group/other permission bit
   (_providers.py:369-386). Errors name the path only: neither the file text
   nor the JSON parser's message (which may quote it) is ever included."
  [^java.io.File f profile auth-type]
  (let [p         (.toPath f)
        not-found #(profile-error! (str "Credentials file not found at " f " (profile " (pr-str profile) ").") f)]
    (when (posix-fs?)
      (when-not (Files/exists p no-follow) (not-found))
      (when (Files/isSymbolicLink p)
        (profile-error! (str "Credentials file at " f " is a symlink; refusing to follow (move the real "
                             "file into place to keep secret material on the expected filesystem).")
                        f))
      (let [mode (reduce + 0 (map perm-bits (Files/getPosixFilePermissions p no-follow)))]
        (when (pos? (bit-and mode 077))
          (profile-error! (str "Credentials file at " f " is accessible by group or others (mode 0o"
                               (Integer/toOctalString mode) "); run `chmod 600 " f "` before retrying.")
                          f))))
    (let [text   (try (String. (Files/readAllBytes p) "UTF-8")
                      (catch java.nio.file.NoSuchFileException _ (not-found))
                      (catch Exception e
                        (profile-error! (str "Credentials file at " f " could not be read: " (.getName (class e))) f)))
          parsed (try ((:read json-codec) text)
                      (catch Exception _
                        (profile-error! (str "Credentials file at " f " is not valid JSON.") f)))]
      (when-not (map? parsed)
        (profile-error! (str "Credentials file at " f " must contain a JSON object, not "
                             (json-type-name parsed) ".")
                        f))
      (let [actual (get parsed "type")]
        (when (and (some? actual) (not= actual credentials-file-type))
          (profile-error! (str "credentials file has type " (pr-str (str actual)) "; expected "
                               (pr-str credentials-file-type) " for authentication.type " (pr-str auth-type))
                          f)))
      parsed)))

(defn- write-credentials!
  "Atomic replace of the credentials file (_providers.py:447-487): parent
   directory created 0700 if missing, a uniquely named temp file created
   0600 in the SAME directory (no window at a wider mode), written and
   fsynced, then renamed over the target with ATOMIC_MOVE. On any failure
   the temp file is deleted and the original is untouched. Throws the
   underlying IOException. Not done: the SDK's best-effort fsync of the
   parent directory (no portable JVM API)."
  [^java.io.File f data]
  (let [bs     (.getBytes ^String ((:write json-codec) data) "UTF-8")
        p      (.toAbsolutePath (.toPath f))
        parent (.getParent p)
        posix? (posix-fs?)
        attrs  (fn [perms]
                 (into-array FileAttribute
                             (when posix?
                               [(PosixFilePermissions/asFileAttribute (PosixFilePermissions/fromString perms))])))]
    (Files/createDirectories parent (attrs "rwx------"))
    (let [tmp (Files/createTempFile parent (str "." (.getFileName p) ".") ".tmp" (attrs "rw-------"))]
      (try
        (with-open [ch (FileChannel/open tmp (into-array OpenOption [StandardOpenOption/WRITE]))]
          (let [buf (java.nio.ByteBuffer/wrap bs)]
            (while (.hasRemaining buf) (.write ch buf)))
          (.force ch true))
        (Files/move tmp p (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                  StandardCopyOption/REPLACE_EXISTING]))
        (catch Throwable e
          (try (Files/deleteIfExists tmp) (catch Exception _ nil))
          (throw e))))))

(defn- coerce-expires-at
  "_providers.py:59-71: nil stays nil (no expiry); otherwise Python int()
   semantics (integer, number, numeric string). ISO8601 is rejected."
  [v ^java.io.File f]
  (when (some? v)
    (or (when-not (boolean? v) (parse-expires-in v))
        (profile-error! (str "credentials file at " f " has invalid 'expires_at' " (pr-str v)
                             "; expected an integer Unix timestamp in seconds. ISO8601 is not parsed.")
                        f))))

;; ---------------------------------------------------------------------------
;; refresh_token grant (_providers.py:509-617)
;; ---------------------------------------------------------------------------

(defn- refresh-access-token!
  "One refresh_token POST. Returns {:token :expires-at-s :refresh-token}
   where :refresh-token is the rotated one when the response carries it,
   else the one sent."
  [{:keys [base-url refresh-token client-id http-fn timeout-ms now-ms]}]
  (let [url     (str base-url token-endpoint)
        secrets [refresh-token]
        resp    (try
                  (http-fn {:method     :post
                            :url        url
                            :headers    {"anthropic-beta" refresh-beta-header
                                         "content-type"   "application/json"}
                            :body       ((:write json-codec) {"grant_type"    grant-type-refresh-token
                                                              "refresh_token" refresh-token
                                                              "client_id"     client-id})
                            :as         :bytes
                            :timeout-ms timeout-ms})
                  (catch Exception e
                    (exchange-error! (str "user_oauth refresh failed to reach token endpoint " url ": "
                                          (.getName (class e))
                                          (when-let [m (ex-message e)] (str ": " (scrub m secrets)))))))
        status     (:status resp)
        request-id (header (:headers resp) "request-id")
        raw        (:body resp)
        bs         (cond (bytes? raw) raw
                         (string? raw) (.getBytes ^String raw "UTF-8")
                         :else (byte-array 0))]
    (when (> (alength ^bytes bs) max-response-bytes)
      (exchange-error! (str "user_oauth refresh response body exceeds " max-response-bytes " bytes; refusing to parse.")
                       {:status status :request-id request-id}))
    (let [text   (String. ^bytes bs "UTF-8")
          parsed (try {:ok ((:read json-codec) text)} (catch Exception _ {:invalid true}))]
      (when (not= 200 status)
        (let [redacted (redact-body (if (:invalid parsed) text (:ok parsed)) secrets)]
          (exchange-error! (str "user_oauth refresh failed (HTTP " status "): " (pr-str redacted))
                           {:status status :body redacted :request-id request-id})))
      (when (:invalid parsed)
        (exchange-error! (str "user_oauth refresh returned a non-JSON response (status " status ").")
                         {:status status :request-id request-id}))
      (let [data (:ok parsed)]
        (when-not (map? data)
          (exchange-error! (str "user_oauth refresh returned a JSON " (json-type-name data) " (status " status
                                "); expected an object.")
                           {:status status :request-id request-id}))
        (let [access (get data "access_token")
              raw-in (get data "expires_in" default-refresh-expires-in)
              exp-in (when-not (boolean? raw-in) (parse-expires-in raw-in))]
          (when-not (and (string? access) (seq access))
            (exchange-error! "user_oauth refresh response missing 'access_token'"
                             {:status status :request-id request-id}))
          (when-not exp-in
            (exchange-error! (str "user_oauth refresh response has invalid 'expires_in' "
                                  (scrub (pr-str raw-in) (conj secrets access))
                                  "; expected an integer number of seconds.")
                             {:status status :request-id request-id}))
          {:token         access
           :expires-at-s  (+ (quot (now-ms) 1000) exp-in)
           :refresh-token (let [r (get data "refresh_token")] (if (truthy? r) r refresh-token))})))))

;; ---------------------------------------------------------------------------
;; Token fetchers, one per authentication.type (_providers.py:494-733)
;; ---------------------------------------------------------------------------

(defn- fetch-user-oauth
  "_providers.py:509-617. Without client_id the file is externally rotated:
   return what it holds, never refresh. With client_id: the disk token while
   now < expires_at (strict; the cache owns proactive refresh), unless
   force?; otherwise a refresh_token grant whose result is written back
   atomically before it is returned. A failed write-back THROWS: the refresh
   token may already have rotated server-side."
  [{:keys [credentials-file profile config base-url http-fn timeout-ms now-ms]} force?]
  (let [f         credentials-file
        auth      (get config "authentication")
        creds     (read-credentials! f profile auth-type-user-oauth)
        access    (get creds "access_token")
        client-id (get auth "client_id")]
    (when-not (truthy? access)
      (profile-error! (str "Credentials file at " f " is missing 'access_token'.") f))
    (if-not (truthy? client-id)
      {:token access :expires-at (some-> (coerce-expires-at (get creds "expires_at") f) (* 1000))}
      (let [refresh (get creds "refresh_token")]
        (when-not (truthy? refresh)
          (exchange-error! (str "credentials file for profile " (pr-str profile) " (authentication.type "
                                (pr-str auth-type-user-oauth) " with client_id) must include 'refresh_token': " f)))
        (let [exp (coerce-expires-at (get creds "expires_at") f)]
          (if (and (not force?) exp (< (now-ms) (* 1000 exp)))
            {:token access :expires-at (* 1000 exp)}
            (let [{:keys [token expires-at-s refresh-token]}
                  (refresh-access-token! {:base-url base-url :refresh-token (str refresh)
                                          :client-id client-id :http-fn http-fn
                                          :timeout-ms timeout-ms :now-ms now-ms})]
              (try
                (write-credentials! f (assoc creds
                                             "version"       credentials-file-version
                                             "type"          credentials-file-type
                                             "access_token"  token
                                             "expires_at"    expires-at-s
                                             "refresh_token" refresh-token))
                (catch Exception e
                  (profile-error! (str "refreshed credentials could not be written back to " f ": "
                                       (.getName (class e)) "; the refresh token may have rotated "
                                       "server-side and been lost.")
                                  f)))
              {:token token :expires-at (* 1000 expires-at-s)})))))))

(defn- federation-delegate
  "_providers.py:682-733: the jwt-bearer inputs for an oidc_federation
   profile. identity_token must be {\"source\": \"file\", \"path\": ...};
   absent, ANTHROPIC_IDENTITY_TOKEN_FILE is used."
  [{:keys [config config-file profile getenv]}]
  (let [auth    (get config "authentication")
        rule-id (get auth "federation_rule_id")
        org-id  (get config "organization_id")]
    (when-not (and (truthy? rule-id) (truthy? org-id))
      (exchange-error! (str "config file with authentication.type " (pr-str auth-type-oidc-federation)
                            " must include 'authentication.federation_rule_id' and top-level "
                            "'organization_id': " config-file)))
    (let [it   (get auth "identity_token")
          path (if (some? it)
                 (let [source (when (map? it) (get it "source"))]
                   (when (not= "file" source)
                     (profile-error! (str "identity_token source " (pr-str source)
                                          " is not supported; only 'file' is implemented")
                                     config-file))
                   (or (non-empty (get it "path"))
                       (profile-error! (str "identity_token source 'file' requires a non-empty path; profile "
                                            (pr-str profile) " at " config-file ".")
                                       config-file)))
                 (or (non-empty (getenv env-identity-token-file))
                     (profile-error! (str "No identity token file path given. Set authentication.identity_token "
                                          "in " config-file " or the " env-identity-token-file
                                          " environment variable.")
                                     config-file)))]
      {:provider           (identity-token-file path)
       :federation-rule-id rule-id
       :organization-id    org-id
       :service-account-id (get auth "service_account_id")
       :workspace-id       (get config "workspace_id")})))

(defn- fetch-oidc-federation
  "_providers.py:634-680. credentials/<profile>.json is a cross-process disk
   cache: reused while now < expires_at - 30 s unless force?. After an
   exchange it is rewritten atomically, best effort: an I/O failure is
   ignored (a malformed or unsafe EXISTING file still throws on read)."
  [{:keys [credentials-file profile delegate base-url http-fn timeout-ms now-ms] :as ctx} force?]
  (let [f      credentials-file
        d      (or @delegate (reset! delegate (federation-delegate ctx)))
        cached (when (.exists ^java.io.File f)
                 (read-credentials! f profile auth-type-oidc-federation))
        hit    (when (and cached (not force?))
                 (let [access (get cached "access_token")
                       exp    (let [v (get cached "expires_at")] (when-not (boolean? v) (parse-expires-in v)))]
                   (when (and (truthy? access) exp (< (now-ms) (- (* 1000 exp) federation-disk-margin-ms)))
                     {:token (str access) :expires-at (* 1000 exp)})))]
    (or hit
        (let [{:keys [token expires-at] :as result}
              (exchange-token! {:assertion          ((:provider d))
                                :federation-rule-id (:federation-rule-id d)
                                :organization-id    (:organization-id d)
                                :service-account-id (:service-account-id d)
                                :workspace-id       (:workspace-id d)
                                :base-url           base-url
                                :http-fn            http-fn
                                :timeout-ms         timeout-ms
                                :now-ms             now-ms})]
          (try
            (write-credentials! f (assoc (or cached {})
                                         "version"      credentials-file-version
                                         "type"         credentials-file-type
                                         "access_token" token
                                         "expires_at"   (quot expires-at 1000)))
            (catch java.io.IOException _ nil))
          result))))

;; ---------------------------------------------------------------------------
;; TokenSource with the SDK's one-shot force_refresh (_cache.py:76-103,177-185)
;; ---------------------------------------------------------------------------

(defn- forcing-source
  "A tools.agents.token/token-cache around (fetch force?) plus the SDK
   TokenCache's force flag: invalidate! of the token last fetched arms a
   one-shot force? so the next fetch bypasses the disk freshness check
   instead of re-serving a revoked token. The flag is cleared only after a
   successful fetch. A token-endpoint 401 is retried once with force? true."
  [fetch refresh-skew-ms now-ms]
  (let [force? (atom false)
        last   (atom nil)
        cache  (token/token-cache
                {:fetch!          (fn []
                                    (let [result (try
                                                   (fetch @force?)
                                                   (catch clojure.lang.ExceptionInfo e
                                                     (let [{:keys [type status]} (ex-data e)]
                                                       (if (and (= type :tools.agents.anthropic.error/token-exchange)
                                                                (= status 401))
                                                         (fetch true)
                                                         (throw e)))))]
                                      (reset! force? false)
                                      (reset! last (:token result))
                                      result))
                 :refresh-skew-ms refresh-skew-ms
                 :now-ms          now-ms})]
    (reify token/TokenSource
      (-token [_] (token/token! cache))
      (-invalidate [_ used-token]
        (when (or (nil? used-token) (= used-token @last))
          (reset! force? true))
        (token/invalidate! cache used-token)))))

;; ---------------------------------------------------------------------------
;; Public constructors
;; ---------------------------------------------------------------------------

(defn profile-source
  "Load one profile (the SDK's CredentialsFile, _providers.py:146-733) and
   return a profile map:

     {:credential-source  TokenSource (Bearer token per attempt)
      :profile            the resolved profile name
      :credential-headers {\"anthropic-workspace-id\" ws} for a non-federation
                          profile with workspace_id, else {}
      :base-url           the config's base_url (trailing / stripped) or nil}

   opts (all optional):
     :profile          profile name; default `active-profile`
                       (ANTHROPIC_PROFILE > active_config > \"default\")
     :config-dir       default `config-dir` (ANTHROPIC_CONFIG_DIR >
                       ~/.config/anthropic)
     :getenv           default System/getenv; also feeds the config's env
                       fill-in (ANTHROPIC_BASE_URL, ANTHROPIC_ORGANIZATION_ID,
                       ANTHROPIC_WORKSPACE_ID, ANTHROPIC_FEDERATION_RULE_ID,
                       ANTHROPIC_SERVICE_ACCOUNT_ID, ANTHROPIC_SCOPE,
                       ANTHROPIC_IDENTITY_TOKEN_FILE)
     :home             user home for config-dir and ~ in credentials_path
     :base-url         the owning client's base URL: the token endpoint is
                       config base_url > this > https://api.anthropic.com
     :http-fn          default tools.agents.http/request!
     :timeout-ms       token POST timeout, default 30000
     :refresh-skew-ms  default `default-refresh-skew-ms` (120000)
     :now-ms           clock, default System/currentTimeMillis

   The config file is read NOW: a bad name, a missing / malformed config or a
   cleartext base URL throws :tools.agents.anthropic.error/profile. The
   credentials file is read on every token fetch, so its errors surface on
   the first request. Pass the whole map's pieces to
   `tools.agents.anthropic/client` via its :profile option rather than using
   :credential-source directly, or the workspace header and base URL are
   lost."
  ([] (profile-source {}))
  ([{:keys [profile getenv home base-url http-fn timeout-ms refresh-skew-ms now-ms]
     :or   {getenv          default-getenv
            http-fn         http/request!
            timeout-ms      token-exchange-timeout-ms
            refresh-skew-ms default-refresh-skew-ms
            now-ms          default-now-ms}
     :as   opts}]
   (let [home        (str (or home (default-home)))
         dir         (or (:config-dir opts) (config-dir {:getenv getenv :home home}))
         profile     (if (some? profile)
                       (validate-profile-name! profile "profile name")
                       (active-profile {:getenv getenv :config-dir dir}))
         config-file (profile-file dir "configs" profile)
         bound       (when (some? base-url)
                       (let [b (strip-trailing-slashes (str base-url))]
                         (when-not (https-or-loopback? b)
                           (profile-error! (cleartext-message (str config-file ": base_url") b) config-file))
                         b))
         config      (load-config! config-file profile getenv)
         auth        (get config "authentication")
         cfg-base    (when (truthy? (get config "base_url"))
                       (strip-trailing-slashes (str (get config "base_url"))))
         token-base  (or cfg-base bound default-base-url)
         _           (when-not (https-or-loopback? token-base)
                       (profile-error! (cleartext-message (str config-file ": base_url") token-base) config-file))
         override    (get auth "credentials_path")
         creds-file  (if (truthy? override)
                       (java.io.File. ^String (expand-user (str override) home))
                       (profile-file dir "credentials" profile))
         ctx         {:credentials-file creds-file
                      :config-file      config-file
                      :profile          profile
                      :config           config
                      :getenv           getenv
                      :delegate         (atom nil)
                      :base-url         token-base
                      :http-fn          http-fn
                      :timeout-ms       timeout-ms
                      :now-ms           now-ms}
         fetch       (fn [force?]
                       (let [t (get auth "type")]
                         (condp = t
                           auth-type-oidc-federation (fetch-oidc-federation ctx force?)
                           auth-type-user-oauth      (fetch-user-oauth ctx force?)
                           (profile-error! (str "Unknown authentication.type " (pr-str t) " at " config-file
                                                ". Expected " (pr-str auth-type-oidc-federation) " or "
                                                (pr-str auth-type-user-oauth) ".")
                                           config-file))))
         workspace   (get config "workspace_id")]
     {:credential-source  (forcing-source fetch refresh-skew-ms now-ms)
      :profile            profile
      :credential-headers (if (and (not= auth-type-oidc-federation (get auth "type")) (truthy? workspace))
                            {"anthropic-workspace-id" (str workspace)}
                            {})
      :base-url           cfg-base})))

(defn profile-from-env
  "Credential chain step 3, explicit profile selection (_chain.py:116-129):
   when ANTHROPIC_PROFILE or ANTHROPIC_CONFIG_DIR is non-empty, or
   <config-dir>/active_config is non-empty, the `profile-source` of the
   active profile; otherwise nil. Errors PROPAGATE: a user who selected a
   profile expects a broken one to surface.

   opts: as `profile-source` minus :profile and :config-dir (the env
   decides both)."
  ([] (profile-from-env {}))
  ([{:keys [getenv] :or {getenv default-getenv} :as opts}]
   (let [opts (-> opts (dissoc :profile :config-dir) (assoc :getenv getenv))
         dir  (config-dir opts)]
     (when (or (non-empty (getenv env-profile))
               (non-empty (getenv env-config-dir))
               (read-active-config-pointer dir))
       (profile-source (assoc opts :config-dir dir))))))

(defn fallback-profile
  "Credential chain step 5, the on-disk active profile (_chain.py:138-153):
   when configs/<active>.json is a regular file, its `profile-source`;
   otherwise nil. A cleartext :base-url still throws (the SDK binds the
   client's base URL outside its try); any other profile error is
   SWALLOWED and returns nil, so a corrupt unselected profile never breaks
   the chain. Only the credentials file's errors, read per request, can
   still surface later.

   opts: as `profile-from-env`."
  ([] (fallback-profile {}))
  ([{:keys [getenv base-url] :or {getenv default-getenv} :as opts}]
   (let [opts (-> opts (dissoc :profile :config-dir) (assoc :getenv getenv))
         dir  (config-dir opts)]
     (when (try (.isFile (profile-file dir "configs" (active-profile (assoc opts :config-dir dir))))
                (catch Exception _ false))
       (when (and (some? base-url) (not (https-or-loopback? (strip-trailing-slashes (str base-url)))))
         (profile-error! (cleartext-message "base-url" base-url)))
       (try
         (profile-source (assoc opts :config-dir dir))
         (catch clojure.lang.ExceptionInfo e
           (if (anthropic-error? e) nil (throw e))))))))
