(ns tools.agents.openai.credentials-test
  "tools.agents.openai.credentials (#38, Workload Identity Federation). One
   local mock server per test (OS-assigned port) answers both the token
   exchange (/oauth/token, via :token-exchange-url) and the API (/v1/...), so
   no test can reach auth.openai.com. Identical on JVM Clojure and Babashka."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.agents :as agents]
            [tools.agents.openai.credentials :as creds]
            [tools.agents.token :as token]
            [tools.agents.test-support :refer [start-server!]]))

(defn- free-port
  "An unbound port, for the one test that needs a refused connection."
  []
  (with-open [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort ss)))

(defn- with-server
  "Start a mock on a free port answering every path with `handler`; call
   (f port) and stop the server afterwards."
  [handler f]
  (let [{:keys [port stop!]} (start-server! 0 "/" handler)]
    (try (f port) (finally (stop!)))))

(defn- token-url [port] (str "http://127.0.0.1:" port "/oauth/token"))
(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- exchange-ok [access-token expires-in]
  {:status 200 :headers {"content-type" "application/json"}
   :body (str "{\"access_token\":\"" access-token "\",\"token_type\":\"Bearer\",\"expires_in\":" expires-in "}")})

(defn- source
  "A workload-identity-source against the mock; `extra` overrides opts."
  [port & {:as extra}]
  (creds/workload-identity-source
   (merge {:identity-provider-id "idp_123"
           :service-account-id   "sa_456"
           :provider             {:token-type :jwt :get-token (constantly "subject-jwt-SECRET")}
           :token-exchange-url   (token-url port)}
          extra)))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(defn- leak-free?
  "No secret appears in the exception's message, ex-data or cause chain."
  [e & secrets]
  (let [text (loop [e e acc ""]
               (if e
                 (recur (ex-cause e) (str acc (ex-message e) (pr-str (ex-data e))))
                 acc))]
    (not-any? #(str/includes? text %) secrets)))

;; ---------------------------------------------------------------------------
;; Exchange request shape, caching, API header
;; ---------------------------------------------------------------------------

(deftest exchange-request-shape-and-api-bearer
  (let [exchanges (atom [])
        api       (atom [])]
    (with-server
      (fn [req]
        (if (= "/oauth/token" (:path req))
          (do (swap! exchanges conj req) (exchange-ok "oai-access-1" 3600))
          (do (swap! api conj req)
              {:status 200 :body "{\"id\":\"resp_1\",\"output\":[]}"})))
      (fn [port]
        (let [c (oai/client {:credential-source (source port) :base-url (base-url port)})]
          (is (= "resp_1" (get (oai/responses-create c {"model" "m" "input" "a"}) "id")))
          (is (= "resp_1" (get (oai/responses-create c {"model" "m" "input" "b"}) "id")))
          (is (= 1 (count @exchanges)) "token cached across requests")
          (let [ex (first @exchanges)]
            (is (= "POST" (:method ex)))
            (is (= "/oauth/token" (:path ex)))
            (is (str/starts-with? (get (:headers ex) "content-type") "application/json"))
            (is (nil? (get (:headers ex) "authorization")) "the exchange carries no bearer")
            (is (= {"grant_type"           "urn:ietf:params:oauth:grant-type:token-exchange"
                    "subject_token"        "subject-jwt-SECRET"
                    "subject_token_type"   "urn:ietf:params:oauth:token-type:jwt"
                    "identity_provider_id" "idp_123"
                    "service_account_id"   "sa_456"}
                   (oai/read-json (:body ex)))))
          (is (= ["Bearer oai-access-1" "Bearer oai-access-1"]
                 (map #(get (:headers %) "authorization") @api)))
          (is (= ["/v1/responses" "/v1/responses"] (map :path @api))))))))

(deftest id-token-type-and-string-token-type
  (doseq [tt [:id "id"]]
    (let [body (atom nil)]
      (with-server
        (fn [req] (reset! body (oai/read-json (:body req))) (exchange-ok "t" 3600))
        (fn [port]
          (is (= "t" (token/token! (source port :provider {:token-type tt :get-token (constantly "gcp-id")}))))
          (is (= "urn:ietf:params:oauth:token-type:id_token" (get @body "subject_token_type")) (pr-str tt)))))))

(deftest defaults-mirror-the-sdk
  (is (= "https://auth.openai.com/oauth/token" creds/default-token-exchange-url))
  (is (= 1200 creds/default-refresh-buffer-seconds))
  (is (= "/var/run/secrets/kubernetes.io/serviceaccount/token" creds/default-k8s-token-file))
  (is (= :jwt (:token-type (creds/k8s-service-account-token-provider))))
  (is (= :jwt (:token-type (creds/azure-managed-identity-token-provider))))
  (is (= :id (:token-type (creds/gcp-id-token-provider)))))

;; ---------------------------------------------------------------------------
;; Refresh buffer (1200 s, capped at half the lifetime)
;; ---------------------------------------------------------------------------

(deftest refresh-inside-the-buffer
  (testing "expires_in 3600: cached until 2400 s, re-exchanged from then on"
    (let [n     (atom 0)
          clock (atom 1000000)]
      (with-server
        (fn [_] (exchange-ok (str "tok-" (swap! n inc)) 3600))
        (fn [port]
          (let [src (source port :now-ms #(deref clock))]
            (is (= "tok-1" (token/token! src)))
            (reset! clock (+ 1000000 (* 1000 2399)))
            (is (= "tok-1" (token/token! src)))
            (reset! clock (+ 1000000 (* 1000 2400)))
            (is (= "tok-2" (token/token! src)))
            (is (= 2 @n)))))))
  (testing "expires_in 60: the buffer is capped at half the lifetime (30 s)"
    (let [n     (atom 0)
          clock (atom 0)]
      (with-server
        (fn [_] (exchange-ok (str "tok-" (swap! n inc)) 60))
        (fn [port]
          (let [src (source port :now-ms #(deref clock))]
            (is (= "tok-1" (token/token! src)))
            (reset! clock 29999)
            (is (= "tok-1" (token/token! src)))
            (reset! clock 30000)
            (is (= "tok-2" (token/token! src))))))))
  (testing ":refresh-buffer-seconds overrides the default; float expires_in accepted"
    (let [n     (atom 0)
          clock (atom 0)]
      (with-server
        (fn [_] (exchange-ok (str "tok-" (swap! n inc)) 3600.5))
        (fn [port]
          (let [src (source port :now-ms #(deref clock) :refresh-buffer-seconds 100)]
            (is (= "tok-1" (token/token! src)))
            (reset! clock 3500499)
            (is (= "tok-1" (token/token! src)))
            (reset! clock 3500500)
            (is (= "tok-2" (token/token! src)))))))))

(deftest non-positive-expires-in-is-rejected
  (with-server
    (fn [_] (exchange-ok "oai-access-ZERO" 0))
    (fn [port]
      (let [e (thrown #(token/token! (source port)))]
        (is (= :tools.agents.openai/token-exchange-error (:type (ex-data e))))
        (is (leak-free? e "oai-access-ZERO"))))))

;; ---------------------------------------------------------------------------
;; Single-flight
;; ---------------------------------------------------------------------------

(deftest concurrent-callers-share-one-exchange
  (let [n       (atom 0)
        release (promise)]
    (with-server
      (fn [_] (swap! n inc) (deref release 5000 nil) (exchange-ok "shared" 3600))
      (fn [port]
        (let [src     (source port)
              futures (doall (repeatedly 8 #(future (token/token! src))))]
          (Thread/sleep 200)
          (deliver release :go)
          (is (= (repeat 8 "shared") (map #(deref % 5000 :timeout) futures)))
          (is (= 1 @n)))))))

;; ---------------------------------------------------------------------------
;; 401 on the API -> invalidate -> one re-exchange
;; ---------------------------------------------------------------------------

(deftest api-401-reexchanges-once
  (testing "first 401 re-exchanges and the retry succeeds"
    (let [n     (atom 0)
          auths (atom [])]
      (with-server
        (fn [req]
          (if (= "/oauth/token" (:path req))
            (exchange-ok (str "tok-" (swap! n inc)) 3600)
            (do (swap! auths conj (get (:headers req) "authorization"))
                (if (= 1 (count @auths))
                  {:status 401 :body "{\"error\":{\"message\":\"revoked\"}}"}
                  {:status 200 :body "{\"id\":\"resp_1\"}"}))))
        (fn [port]
          (let [c (oai/client {:credential-source (source port) :base-url (base-url port) :max-retries 0})]
            (is (= "resp_1" (get (oai/responses-create c {"model" "m"}) "id")))
            (is (= ["Bearer tok-1" "Bearer tok-2"] @auths))
            (is (= 2 @n)))))))
  (testing "a second 401 surfaces as authentication-error; only one re-exchange"
    (let [n    (atom 0)
          hits (atom 0)]
      (with-server
        (fn [req]
          (if (= "/oauth/token" (:path req))
            (exchange-ok (str "tok-" (swap! n inc)) 3600)
            (do (swap! hits inc) {:status 401 :body "{\"error\":{\"message\":\"no\"}}"})))
        (fn [port]
          (let [c (oai/client {:credential-source (source port) :base-url (base-url port) :max-retries 2})
                e (thrown #(oai/responses-create c {"model" "m"}))]
            (is (= :tools.agents.openai/authentication-error (:type (ex-data e))))
            (is (= 2 @hits))
            (is (= 2 @n))
            (is (leak-free? e "tok-1" "tok-2" "subject-jwt-SECRET"))
            (testing "the second 401 invalidated tok-2 too: the next call re-exchanges"
              (is (some? (thrown #(oai/responses-create c {"model" "m"}))))
              (is (= 4 @hits))
              (is (= 4 @n) "tok-3 for the next call, tok-4 for its auth retry")))))))
  (testing "stream open: two 401s invalidate twice, next open re-exchanges"
    (let [n     (atom 0)
          auths (atom [])]
      (with-server
        (fn [req]
          (if (= "/oauth/token" (:path req))
            (exchange-ok (str "tok-" (swap! n inc)) 3600)
            (do (swap! auths conj (get (:headers req) "authorization"))
                (if (<= (count @auths) 2)
                  {:status 401 :body "{\"error\":{\"message\":\"no\"}}"}
                  {:status 200 :headers {"content-type" "text/event-stream"}
                   :body "data: {\"type\":\"ping\"}\n\n"}))))
        (fn [port]
          (let [c (oai/client {:credential-source (source port) :base-url (base-url port) :max-retries 0})]
            (is (= :tools.agents.openai/authentication-error
                   (:type (ex-data (thrown #(oai/responses-stream c {"model" "m"}))))))
            (is (= ["ping"] (map #(get % "type") (into [] (oai/responses-stream c {"model" "m"})))))
            (is (= ["Bearer tok-1" "Bearer tok-2" "Bearer tok-3"] @auths))
            (is (= 3 @n))))))))

;; ---------------------------------------------------------------------------
;; Exchange failures: typed, never retried except without a response, no leaks
;; ---------------------------------------------------------------------------

(deftest exchange-oauth-error
  (let [hits (atom 0)]
    (with-server
      (fn [req]
        (if (= "/oauth/token" (:path req))
          (do (swap! hits inc)
              {:status 401 :body "{\"error\":\"invalid_grant\",\"error_description\":\"Subject token expired\"}"})
          {:status 500 :body "api must not be reached"}))
      (fn [port]
        (let [c (oai/client {:credential-source (source port) :base-url (base-url port)})
              e (thrown #(oai/responses-create c {"model" "m"}))]
          (is (= :tools.agents.openai/oauth-error (:type (ex-data e))))
          (is (= {:type :tools.agents.openai/oauth-error :status 401 :error "invalid_grant"} (ex-data e)))
          (is (str/includes? (ex-message e) "Subject token expired"))
          (is (= 1 @hits) "an OAuth error is not retried")
          (is (leak-free? e "subject-jwt-SECRET")))))
    (testing "400/403 too; a non-JSON body gets the SDK's default message"
      (doseq [status [400 403]]
        (with-server
          (fn [_] {:status status :body "<html>nope</html>"})
          (fn [port]
            (let [e (thrown #(token/token! (source port)))]
              (is (= {:type :tools.agents.openai/oauth-error :status status} (ex-data e)))
              (is (str/includes? (ex-message e) "OAuth authentication error.")))))))))

(deftest exchange-other-status-is-not-retried
  (doseq [status [500 429 404]]
    (let [hits (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) {:status status :body "{\"error\":\"x\"}"})
        (fn [port]
          (let [e (thrown #(token/token! (source port)))]
            (is (= {:type :tools.agents.openai/token-exchange-error :status status} (ex-data e)))
            (is (str/includes? (ex-message e) (str "status " status)))
            (is (= 1 @hits) (str status " not retried"))))))))

(deftest exchange-malformed-2xx-does-not-leak
  (doseq [[body why] [["{\"access_token\":\"oai-LEAKED\"}" "missing expires_in"]
                      ["{\"access_token\":\"oai-LEAKED\",\"expires_in\":\"3600\"}" "string expires_in"]
                      ["{\"access_token\":\"\",\"expires_in\":3600}" "empty access_token"]
                      ["[\"oai-LEAKED\"]" "not an object"]
                      ["oai-LEAKED" "not JSON"]
                      ["" "empty body"]]]
    (with-server
      (fn [_] {:status 200 :body body})
      (fn [port]
        (let [e (thrown #(token/token! (source port)))]
          (is (= {:type :tools.agents.openai/token-exchange-error :status 200} (ex-data e)) why)
          (is (leak-free? e "oai-LEAKED" "subject-jwt-SECRET") why))))))

(deftest provider-failures
  (testing "an empty subject token is a typed error, not retried, no request sent"
    (let [hits (atom 0) calls (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) (exchange-ok "t" 3600))
        (fn [port]
          (let [e (thrown #(token/token! (source port :provider {:token-type :jwt
                                                                  :get-token (fn [] (swap! calls inc) "")})))]
            (is (= :tools.agents.openai/token-exchange-error (:type (ex-data e))))
            (is (= [1 0] [@calls @hits])))))))
  (testing "a raw exception from :get-token is retried like a transport failure"
    (let [calls (atom 0)]
      (with-server
        (fn [_] (exchange-ok "t" 3600))
        (fn [port]
          (let [e (thrown #(token/token! (source port :max-retries 1
                                                 :provider {:token-type :jwt
                                                            :get-token (fn [] (swap! calls inc)
                                                                         (throw (RuntimeException. "boom")))})))]
            (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
            (is (= 2 @calls))))))))

(deftest exchange-transport-failure-retries-then-types
  (let [calls (atom 0)
        port  (free-port)
        src   (creds/workload-identity-source
               {:identity-provider-id "idp" :service-account-id "sa" :max-retries 1
                :provider {:token-type :jwt :get-token (fn [] (swap! calls inc) "subject-jwt-SECRET")}
                :token-exchange-url (token-url port)})
        e     (thrown #(token/token! src))]
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (= 1 (:retries-taken (ex-data e))))
    (is (= 2 @calls) "subject token re-read per exchange attempt")
    (is (leak-free? e "subject-jwt-SECRET")))
  (testing "a failed exchange leaves no state: the next call exchanges again"
    (let [n (atom 0)]
      (with-server
        (fn [_] (if (= 1 (swap! n inc)) {:status 500 :body ""} (exchange-ok "t2" 3600)))
        (fn [port]
          (let [src (source port)]
            (is (some? (thrown #(token/token! src))))
            (is (= "t2" (token/token! src)))))))))

;; ---------------------------------------------------------------------------
;; Built-in subject token providers
;; ---------------------------------------------------------------------------

(deftest k8s-token-file-provider
  (let [f (java.io.File/createTempFile "k8s-token" ".jwt")]
    (try
      (spit f "  file-jwt\n")
      (let [body (atom nil)]
        (with-server
          (fn [req] (reset! body (oai/read-json (:body req))) (exchange-ok "t" 3600))
          (fn [port]
            (is (= "t" (token/token! (source port :provider (creds/k8s-service-account-token-provider (.getPath f))))))
            (is (= "file-jwt" (get @body "subject_token")) "read and trimmed")
            (is (= "urn:ietf:params:oauth:token-type:jwt" (get @body "subject_token_type"))))))
      (testing "re-read on every call"
        (spit f "rotated")
        (is (= "rotated" ((:get-token (creds/k8s-service-account-token-provider (.getPath f)))))))
      (testing "empty file"
        (spit f " \n")
        (let [e (thrown (:get-token (creds/k8s-service-account-token-provider (.getPath f))))]
          (is (= :tools.agents.openai/subject-token-provider-error (:type (ex-data e))))))
      (finally (.delete f))))
  (testing "missing file"
    (let [e (thrown (:get-token (creds/k8s-service-account-token-provider "/nonexistent/k8s/token")))]
      (is (= :tools.agents.openai/subject-token-provider-error (:type (ex-data e))))
      (is (str/includes? (ex-message e) "/nonexistent/k8s/token")))))

(deftest azure-imds-provider
  (let [seen (atom nil)]
    (with-server
      (fn [req] (reset! seen req) {:status 200 :body "{\"access_token\":\"azure-jwt\",\"expires_in\":\"3599\"}"})
      (fn [port]
        (let [p (creds/azure-managed-identity-token-provider
                 {:client-id "cid" :url (str "http://127.0.0.1:" port "/metadata/identity/oauth2/token")})]
          (is (= "azure-jwt" ((:get-token p))))
          (is (= "GET" (:method @seen)))
          (is (= "true" (get (:headers @seen) "metadata")))
          (is (= {"api-version" "2018-02-01" "resource" "https://management.azure.com/" "client_id" "cid"}
                 (into {} (map #(let [[k v] (str/split % #"=" 2)] [k (java.net.URLDecoder/decode ^String v "UTF-8")]))
                       (str/split (:query @seen) #"&"))))))))
  (with-server
    (fn [_] {:status 400 :body "{}"})
    (fn [port]
      (let [p (creds/azure-managed-identity-token-provider {:url (str "http://127.0.0.1:" port "/x")})
            e (thrown (:get-token p))]
        (is (= {:type :tools.agents.openai/subject-token-provider-error :status 400} (ex-data e))))))
  (with-server
    (fn [_] {:status 200 :body "{}"})
    (fn [port]
      (let [p (creds/azure-managed-identity-token-provider {:url (str "http://127.0.0.1:" port "/x")})]
        (is (= :tools.agents.openai/subject-token-provider-error (:type (ex-data (thrown (:get-token p))))))))))

(deftest gcp-metadata-provider
  (let [seen (atom nil)]
    (with-server
      (fn [req] (reset! seen req) {:status 200 :body "gcp-id-token\n"})
      (fn [port]
        (let [p (creds/gcp-id-token-provider {:url (str "http://127.0.0.1:" port "/identity")})]
          (is (= "gcp-id-token" ((:get-token p))))
          (is (= "Google" (get (:headers @seen) "metadata-flavor")))
          ;; JVM's URI/getQuery decodes, httpkit's does not: compare decoded.
          (is (= "audience=https://api.openai.com/v1"
                 (java.net.URLDecoder/decode ^String (:query @seen) "UTF-8")))))))
  (with-server
    (fn [_] {:status 200 :body "  "})
    (fn [port]
      (let [p (creds/gcp-id-token-provider {:url (str "http://127.0.0.1:" port "/identity")})]
        (is (= :tools.agents.openai/subject-token-provider-error (:type (ex-data (thrown (:get-token p))))))))))

;; ---------------------------------------------------------------------------
;; Construction
;; ---------------------------------------------------------------------------

(deftest construction-validates-and-never-fetches
  (let [never {:token-type :jwt :get-token #(throw (ex-info "must not be called" {}))}
        base  {:identity-provider-id "idp" :service-account-id "sa" :provider never
               :token-exchange-url "http://127.0.0.1:1/oauth/token"}]
    (is (token/token-source? (creds/workload-identity-source base)))
    (is (some? (oai/client {:credential-source (creds/workload-identity-source base)})))
    (testing "mutually exclusive with :api-key"
      (is (= :tools.agents.openai/invalid-credentials
             (:type (ex-data (thrown #(oai/client {:credential-source (creds/workload-identity-source base)
                                                   :api-key "k"})))))))
    (doseq [bad [(dissoc base :identity-provider-id)
                 (assoc base :service-account-id "")
                 (dissoc base :provider)
                 (assoc base :provider {:token-type :jwt})
                 (assoc-in base [:provider :token-type] :saml)
                 (assoc base :refresh-buffer-seconds -1)
                 (assoc base :max-retries -1)
                 (assoc base :token-exchange-url "")]]
      (is (= :tools.agents.openai/invalid-credentials
             (:type (ex-data (thrown #(creds/workload-identity-source bad)))))
          (pr-str (dissoc bad :provider))))))

;; ---------------------------------------------------------------------------
;; Agents (JSON and streaming open)
;; ---------------------------------------------------------------------------

(deftest agents-json-and-stream-open-use-the-exchanged-token
  (let [n     (atom 0)
        auths (atom [])]
    (with-server
      (fn [req]
        (cond
          (= "/oauth/token" (:path req))
          (exchange-ok (str "tok-" (swap! n inc)) 3600)

          (= "/v1/agents/sessions" (:path req))
          (do (swap! auths conj [:create (get (:headers req) "authorization")])
              {:status 200 :body "{\"id\":\"sess_1\",\"object\":\"agent.session\",\"status\":\"in_progress\"}"})

          :else
          (do (swap! auths conj [:events (get (:headers req) "authorization")])
              (if (= 1 (count (filter #(= :events (first %)) @auths)))
                {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                {:status 200 :headers {"content-type" "text/event-stream"}
                 :body "data: {\"type\":\"agent.session.in_progress\"}\n\n"}))))
      (fn [port]
        (let [c (oai/client {:credential-source (source port) :base-url (base-url port) :max-retries 0})]
          (is (= "sess_1" (get (agents/sessions-create c {"agent" {"model" "m"} "input" "hi"}) "id")))
          (let [events (into [] (agents/sessions-events-stream c "sess_1"))]
            (is (= ["agent.session.in_progress"] (map #(get % "type") events))))
          (is (= [[:create "Bearer tok-1"] [:events "Bearer tok-1"] [:events "Bearer tok-2"]] @auths))
          (is (= 2 @n)))))))

(deftest responses-and-chat-streams-reexchange-on-401
  (doseq [[path open!] [["/v1/responses" #(oai/responses-stream % {"model" "m" "input" "x"})]
                        ["/v1/chat/completions" #(oai/chat-completions-stream % {"model" "m" "messages" []})]]]
    (let [n     (atom 0)
          auths (atom [])]
      (with-server
        (fn [req]
          (if (= "/oauth/token" (:path req))
            (exchange-ok (str "tok-" (swap! n inc)) 3600)
            (do (swap! auths conj [(:path req) (get (:headers req) "authorization")])
                (if (= 1 (count @auths))
                  {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                  {:status 200 :headers {"content-type" "text/event-stream"}
                   :body "data: {\"type\":\"ping\"}\n\ndata: [DONE]\n\n"}))))
        (fn [port]
          (let [c      (oai/client {:credential-source (source port) :base-url (base-url port) :max-retries 0})
                events (into [] (open! c))]
            (is (= ["ping"] (map #(get % "type") events)) path)
            (is (= [[path "Bearer tok-1"] [path "Bearer tok-2"]] @auths) path)
            (is (= 2 @n) path)))))))

;; ---------------------------------------------------------------------------
;; Injected :http (#10)
;; ---------------------------------------------------------------------------

(deftest injected-http-carries-exchange-and-metadata-providers
  (let [calls (atom [])
        http  (fn [req]
                (swap! calls conj req)
                (cond
                  (str/includes? (:url req) "metadata") {:status 200 :headers {}
                                                         :body "{\"access_token\":\"azure-jwt\"}"}
                  (str/ends-with? (:url req) "/oauth/token") (exchange-ok "at-injected" 3600)
                  :else {:status 200 :headers {} :body "{\"id\":\"resp_1\",\"output\":[]}"}))
        src   (creds/workload-identity-source
               {:identity-provider-id "idp_123"
                :service-account-id   "sa_456"
                :provider             (creds/azure-managed-identity-token-provider
                                       {:url "http://metadata.fake/token" :http http})
                :token-exchange-url   "https://auth.fake/oauth/token"
                :http                 http})
        c     (oai/client {:credential-source src :base-url "https://api.fake/v1" :http http})]
    (oai/responses-create c {"model" "m"})
    (let [[md ex api & more] @calls]
      (is (empty? more))
      (is (= :get (:method md)))
      (is (= "azure-jwt" (get (oai/read-json (:body ex)) "subject_token")))
      (is (= "https://api.fake/v1/responses" (:url api)))
      (is (= "Bearer at-injected" (get (:headers api) "authorization")))))
  (let [e (thrown #(creds/workload-identity-source
                    {:identity-provider-id "i" :service-account-id "s"
                     :provider {:token-type :jwt :get-token (constantly "t")} :http "nope"}))]
    (is (= :tools.agents.openai/invalid-credentials (:type (ex-data e))))))
