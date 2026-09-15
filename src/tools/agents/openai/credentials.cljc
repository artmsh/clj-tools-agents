(ns tools.agents.openai.credentials
  "OpenAI Workload Identity Federation (WIF): a `tools.agents.token/TokenSource`
   that exchanges a workload's external identity token (Kubernetes service
   account, Azure managed identity, GCP instance identity, or any fn) for a
   short-lived OpenAI access token. Port of openai-python 3.14.0 @d421d7ab
   `openai/auth/_workload.py` (`WorkloadIdentity`, `WorkloadIdentityAuth`,
   the three subject token providers) and its client wiring
   (`_client.py:235-259,350-353,538-590`).

     (require '[tools.agents.openai :as oai]
              '[tools.agents.openai.credentials :as creds])

     (oai/client
       {:credential-source
        (creds/workload-identity-source
          {:identity-provider-id \"idp_...\"
           :service-account-id   \"sa_...\"
           :provider             (creds/k8s-service-account-token-provider)})})

   Every request attempt asks the source for a token; the first call (and any
   call inside the refresh buffer) performs the exchange:

     POST https://auth.openai.com/oauth/token      (fixed host, NOT :base-url)
     {\"grant_type\": \"urn:ietf:params:oauth:grant-type:token-exchange\",
      \"subject_token\": <provider token>, \"subject_token_type\": <urn>,
      \"identity_provider_id\": ..., \"service_account_id\": ...}

   The result is cached (`tools.agents.token/token-cache`: single-flight) and
   refreshed `min(refresh-buffer, expires_in/2)` before it expires. A 401 from
   the API invalidates the token that request sent and the client retries once
   with a fresh exchange (`_client.py:579-589`).

   No environment variables are read: the SDK has none for WIF. X.509 (mTLS)
   workload identity (`auth/_x509.py`) is not ported."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.json :as json]
            [tools.agents.token :as token]))

;; ---------------------------------------------------------------------------
;; Constants — `_workload.py:16-23`
;; ---------------------------------------------------------------------------

(def token-exchange-grant-type
  "`TOKEN_EXCHANGE_GRANT_TYPE` (RFC 8693)."
  "urn:ietf:params:oauth:grant-type:token-exchange")

(def default-token-exchange-url
  "`DEFAULT_TOKEN_EXCHANGE_URL`: fixed, independent of the client's :base-url."
  "https://auth.openai.com/oauth/token")

(def default-refresh-buffer-seconds
  "`DEFAULT_REFRESH_BUFFER_SECONDS` (20 minutes)."
  1200)

(def subject-token-types
  "`SUBJECT_TOKEN_TYPES`: provider :token-type -> `subject_token_type` URN."
  {:jwt "urn:ietf:params:oauth:token-type:jwt"
   :id  "urn:ietf:params:oauth:token-type:id_token"})

(def ^:private exchange-timeout-ms
  "`client.post(..., timeout=10.0)` (`_workload.py:378`)."
  10000)

(def ^:private default-max-retries
  "openai-python's client default `max_retries`."
  2)

(def default-k8s-token-file
  "`k8s_service_account_token_provider`'s default `token_file_path`."
  "/var/run/secrets/kubernetes.io/serviceaccount/token")

;; ---------------------------------------------------------------------------
;; Errors. Types stay under :tools.agents.openai/* (openai's `own-error?`
;; matches that namespace exactly). Neither the subject token, the access
;; token nor a token-endpoint body is ever put in a message or ex-data.
;; ---------------------------------------------------------------------------

(defn- fail!
  ([type msg] (fail! type msg nil nil))
  ([type msg data] (fail! type msg data nil))
  ([type msg data cause]
   (throw (ex-info (str "tools.agents.openai.credentials/" msg)
                   (merge {:type type} data)
                   cause))))

(defn- own-error? [e]
  (let [t (:type (ex-data e))]
    (and (keyword? t) (= "tools.agents.openai" (namespace t)))))

;; ---------------------------------------------------------------------------
;; Subject token providers — `_workload.py:78-204`
;; ---------------------------------------------------------------------------

(defn- provider-error!
  ([msg] (provider-error! msg nil nil))
  ([msg data cause]
   (fail! :tools.agents.openai/subject-token-provider-error msg data cause)))

(defn k8s-service-account-token-provider
  "`k8s_service_account_token_provider`: a provider reading the mounted
   service account token file (default `default-k8s-token-file`) on EVERY
   exchange, trimmed. A missing, unreadable or empty file throws
   :tools.agents.openai/subject-token-provider-error. :token-type :jwt."
  ([] (k8s-service-account-token-provider default-k8s-token-file))
  ([token-file-path]
   (let [path (str token-file-path)]
     {:token-type :jwt
      :get-token  (fn []
                    (let [token (try
                                  (str/trim (slurp path))
                                  (catch Exception e
                                    (provider-error! (str "k8s-service-account-token-provider: failed to read the token file at "
                                                          path ": " (.getName (class e)))
                                                     {:path path} e)))]
                      (when (str/blank? token)
                        (provider-error! (str "k8s-service-account-token-provider: the token file at " path " is empty")
                                         {:path path} nil))
                      token))})))

(defn- metadata-get!
  "One GET to a cloud metadata endpoint -> the response body String.
   `label` prefixes errors."
  [http-fn label url query headers timeout-ms]
  (let [resp (try
               ((or http-fn http/request!) {:method :get :url url :query query :headers headers
                               :timeout-ms timeout-ms :as :string})
               (catch Exception e
                 (provider-error! (str label ": request failed: " (.getName (class e))) nil e)))
        status (:status resp)]
    (when-not (and status (<= 200 status 399))
      (provider-error! (str label ": HTTP " status) {:status status} nil))
    (str (:body resp))))

(defn azure-managed-identity-token-provider
  "`azure_managed_identity_token_provider`: GET the Azure IMDS token endpoint
   (`Metadata: true`) and use its `access_token` as a :jwt subject token.

   opts (all optional, SDK names):
     :resource     default \"https://management.azure.com/\"
     :object-id :client-id :msi-res-id  pick one of several assigned identities
     :api-version  default \"2018-02-01\"
     :timeout-ms   default 10000
     :url          IMDS endpoint override (tests; the SDK injects `http_client`)
     :http         request fn with tools.agents.http/request!'s contract,
                   default request!

   A non-2xx, unparseable body or missing `access_token` throws
   :tools.agents.openai/subject-token-provider-error."
  ([] (azure-managed-identity-token-provider nil))
  ([{:keys [resource object-id client-id msi-res-id api-version timeout-ms url http]
     :or   {resource    "https://management.azure.com/"
            api-version "2018-02-01"
            timeout-ms  10000
            url         "http://169.254.169.254/metadata/identity/oauth2/token"}}]
   (let [label "azure-managed-identity-token-provider"]
     {:token-type :jwt
      :get-token  (fn []
                    (let [body  (metadata-get! http label url
                                               (cond-> {"api-version" api-version "resource" resource}
                                                 object-id  (assoc "object_id" object-id)
                                                 client-id  (assoc "client_id" client-id)
                                                 msi-res-id (assoc "msi_res_id" msi-res-id))
                                               {"Metadata" "true"} timeout-ms)
                          data  (try (json/read-json body) (catch Exception _ nil))
                          token (when (map? data) (get data "access_token"))]
                      (if (and (string? token) (seq token))
                        token
                        (provider-error! (str label ": Azure IMDS response did not include an access_token")))))})))

(defn gcp-id-token-provider
  "`gcp_id_token_provider`: GET the GCP instance identity token from the
   metadata server (`Metadata-Flavor: Google`) as an :id subject token.

   opts (all optional):
     :audience    default \"https://api.openai.com/v1\"
     :timeout-ms  default 10000
     :url         metadata endpoint override (tests)
     :http        request fn with tools.agents.http/request!'s contract,
                  default request!

   A non-2xx or empty body throws :tools.agents.openai/subject-token-provider-error."
  ([] (gcp-id-token-provider nil))
  ([{:keys [audience timeout-ms url http]
     :or   {audience   "https://api.openai.com/v1"
            timeout-ms 10000
            url        "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity"}}]
   (let [label "gcp-id-token-provider"]
     {:token-type :id
      :get-token  (fn []
                    (let [token (str/trim (metadata-get! http label url {"audience" audience}
                                                         {"Metadata-Flavor" "Google"} timeout-ms))]
                      (if (seq token)
                        token
                        (provider-error! (str label ": GCP metadata server returned an empty token")))))})))

;; ---------------------------------------------------------------------------
;; Token exchange — `WorkloadIdentityAuth._fetch_token_from_exchange` and
;; `_handle_token_response` (`_workload.py:283-310,355-387`)
;; ---------------------------------------------------------------------------

(defn- exchange-error! [msg data]
  (fail! :tools.agents.openai/token-exchange-error (str "workload-identity-source: " msg) data))

(defn- parse-body
  "The decoded JSON body, or nil for an empty or unparseable one
   (`response.json() if response.content else None`, ValueError -> None)."
  [s]
  (when (seq s)
    (try (json/read-json s) (catch Exception _ nil))))

(defn- handle-token-response
  "{:access-token String :expires-in number} from one exchange response, or throw."
  [{:keys [status body]}]
  (let [data (parse-body body)]
    (cond
      (contains? #{400 401 403} status)
      ;; OAuthError (`_exceptions.py:124-138`): message is a string
      ;; error_description when present; `error` is the OAuth error code.
      (let [code (when (map? data) (get data "error"))
            desc (when (map? data) (get data "error_description"))]
        (fail! :tools.agents.openai/oauth-error
               (str "workload-identity-source: "
                    (if (and (string? desc) (seq desc)) desc "OAuth authentication error."))
               (cond-> {:status status}
                 (string? code) (assoc :error code))))

      (and status (<= 200 status 299))
      (let [_            (when (nil? data)
                           (exchange-error! (if (seq body)
                                              "token exchange succeeded but response body was not a JSON object"
                                              "token exchange succeeded but response body was empty")
                                            {:status status}))
            _            (when-not (map? data)
                           (exchange-error! "token exchange succeeded but response body was not a JSON object"
                                            {:status status}))
            access-token (get data "access_token")
            expires-in   (get data "expires_in")]
        (when-not (and (string? access-token) (seq access-token))
          (exchange-error! "token exchange response did not include a valid access_token" {:status status}))
        (when-not (number? expires-in)
          (exchange-error! "token exchange response did not include a valid expires_in" {:status status}))
        {:access-token access-token :expires-in expires-in})

      :else
      (exchange-error! (str "token exchange failed with status " status) {:status status}))))

(defn- subject-token!
  "`_get_subject_token` (`_workload.py:382-387`)."
  [get-token]
  (let [t (get-token)]
    (when-not (and (string? t) (seq t))
      (exchange-error! "the workload identity provider returned an empty subject token" nil))
    t))

(defn- exchange-once!
  [{:keys [token-exchange-url identity-provider-id service-account-id subject-token-type get-token http]}]
  (let [subject (subject-token! get-token)
        resp    (http {:method     :post
                                :url        token-exchange-url
                                :headers    {"content-type" "application/json"
                                             "accept"       "application/json"}
                                :body       (json/write-json
                                             {"grant_type"           token-exchange-grant-type
                                              "subject_token"        subject
                                              "subject_token_type"   subject-token-type
                                              "identity_provider_id" identity-provider-id
                                              "service_account_id"   service-account-id})
                                :timeout-ms exchange-timeout-ms
                                :as         :string})]
    (handle-token-response resp)))

(defn- backoff-ms
  "`_calculate_retry_timeout` with no Retry-After: min(0.5 * 2^n, 8) s scaled
   by `1 - 0.25 * random()`."
  [retries-taken]
  (let [base (min 8000 (* 500 (bit-shift-left 1 (min retries-taken 5))))]
    (long (* base (- 1.0 (* 0.25 (rand)))))))

(defn- exchange-with-retries!
  "The SDK exchanges inside the API request's `_send_request`
   (`_client.py:568-577,599`), i.e. inside `_base_client.request`'s retry loop
   (`_base_client.py:1076-1111`): an `OpenAIError` (OAuth error, bad status,
   bad body, provider error) propagates at once, while any other exception
   (transport failure, timeout, a raw exception from a provider fn) is retried
   with backoff and finally raised as `APIConnectionError`. Here the source
   owns that budget (:max-retries), since the client asks for its token
   before its own retry loop."
  [cfg max-retries sleep!]
  (loop [retries-taken 0]
    (let [outcome (try
                    {:ok (exchange-once! cfg)}
                    (catch InterruptedException e (throw e))
                    (catch Exception e
                      (if (own-error? e) (throw e) {:error e})))]
      (if (contains? outcome :ok)
        (:ok outcome)
        (if (< retries-taken max-retries)
          (do (sleep! (backoff-ms retries-taken))
              (recur (inc retries-taken)))
          (let [e (:error outcome)]
            (fail! :tools.agents.openai/api-connection-error
                   (str "workload-identity-source: token exchange connection failed: " (.getName (class e)))
                   {:status nil :body nil :retries-taken retries-taken}
                   e)))))))

;; ---------------------------------------------------------------------------
;; Public constructor
;; ---------------------------------------------------------------------------

(defn- invalid-options! [msg]
  (fail! :tools.agents.openai/invalid-credentials (str "workload-identity-source: " msg)))

(defn- non-blank-string? [x] (and (string? x) (not (str/blank? x))))

(defn workload-identity-source
  "A `tools.agents.token/TokenSource` for openai-python's `workload_identity`
   (`WorkloadIdentity`, `_workload.py:26-43`). Pass it as
   `(tools.agents.openai/client {:credential-source src})`; combining it with
   :api-key throws :tools.agents.openai/invalid-credentials, as the SDK's
   mutually exclusive arguments do (`_client.py:235-236`). Nothing is fetched
   here.

   opts:
     :identity-provider-id    required, `identity_provider_id`
     :service-account-id      required, `service_account_id`
     :provider                required, `SubjectTokenProvider`:
                              {:token-type :jwt | :id  (or \"jwt\" / \"id\")
                               :get-token  (fn [] subject-token-String)}
                              e.g. `k8s-service-account-token-provider`,
                              `azure-managed-identity-token-provider`,
                              `gcp-id-token-provider`. :get-token runs on
                              every exchange.
     :refresh-buffer-seconds  refresh this long before expiry, capped at half
                              the token's lifetime; default 1200
     :token-exchange-url      default `default-token-exchange-url`
     :max-retries             retries of an exchange that got no response
                              (transport failure/timeout), default 2
     :now-ms                  clock (fn [] epoch-ms), for tests
     :http                    request fn for the exchange POST, with
                              tools.agents.http/request!'s contract; default
                              request!. Not inherited from a client's :http:
                              the source is built before the client. Pass
                              the same fn to both, and to a metadata
                              provider, to route every exchange through it.

   token! throws (no token in any message or ex-data):
     :tools.agents.openai/oauth-error                 exchange HTTP 400/401/403;
                                                      message = error_description,
                                                      ex-data {:status :error}
     :tools.agents.openai/token-exchange-error        other non-2xx (not retried),
                                                      malformed 2xx body, empty
                                                      subject token
     :tools.agents.openai/subject-token-provider-error  a built-in provider failed
     :tools.agents.openai/api-connection-error        no exchange response after
                                                      :max-retries
   A throwing :get-token fn is retried like a transport failure (as in the SDK)."
  [{:keys [identity-provider-id service-account-id provider refresh-buffer-seconds
           token-exchange-url max-retries now-ms http]
    :or   {refresh-buffer-seconds default-refresh-buffer-seconds
           token-exchange-url     default-token-exchange-url
           max-retries            default-max-retries
           now-ms                 #(System/currentTimeMillis)}
    :as   opts}]
  (when-not (non-blank-string? identity-provider-id)
    (invalid-options! ":identity-provider-id must be a non-blank String"))
  (when-not (non-blank-string? service-account-id)
    (invalid-options! ":service-account-id must be a non-blank String"))
  (when-not (and (map? provider) (ifn? (:get-token provider)))
    (invalid-options! ":provider must be a map {:token-type :jwt|:id :get-token (fn [])}"))
  (let [tt   (:token-type provider)
        tt-k (cond (keyword? tt) tt (string? tt) (keyword tt))
        urn  (get subject-token-types tt-k)]
    (when-not urn
      (invalid-options! (str "unsupported :token-type " (pr-str tt) ", supported: :jwt, :id")))
    (when-not (and (number? refresh-buffer-seconds) (>= refresh-buffer-seconds 0))
      (invalid-options! ":refresh-buffer-seconds must be a non-negative number"))
    (when-not (non-blank-string? token-exchange-url)
      (invalid-options! ":token-exchange-url must be a non-blank String"))
    (when-not (and (integer? max-retries) (>= max-retries 0))
      (invalid-options! ":max-retries must be a non-negative integer"))
    (when (contains? opts :now-ms)
      (when-not (ifn? now-ms) (invalid-options! ":now-ms must be a function")))
    (when (contains? opts :http)
      (when-not (http/request-fn? http)
        (invalid-options! ":http must be a request fn with tools.agents.http/request!'s contract")))
    (let [cfg    {:token-exchange-url   token-exchange-url
                  :identity-provider-id identity-provider-id
                  :service-account-id   service-account-id
                  :subject-token-type   urn
                  :get-token            (:get-token provider)
                  :http                 (or http http/request!)}
          sleep! (fn [ms] (when (pos? ms) (Thread/sleep (long ms))))]
      (token/token-cache
       {:refresh-skew-ms (long (* 1000 refresh-buffer-seconds))
        :now-ms          now-ms
        :fetch!          (fn []
                           (let [{:keys [access-token expires-in]} (exchange-with-retries! cfg max-retries sleep!)
                                 ttl-ms (long (* 1000 expires-in))]
                             ;; `get_token` raises when the stored token is
                             ;; already expired (`_workload.py:248-249`).
                             (when-not (pos? ttl-ms)
                               (exchange-error! "token is unusable after refresh completed (expires_in <= 0)"
                                                nil))
                             {:token access-token :expires-at (+ (now-ms) ttl-ms)}))}))))
