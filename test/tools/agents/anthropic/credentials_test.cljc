(ns tools.agents.anthropic.credentials-test
  "Workload Identity Federation against a fake token endpoint on the shared
   mock server (OS-assigned ports). Env discovery is exercised through an
   injected getenv map; the real process env is never read."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.credentials :as creds]
            [tools.agents.token :as token]
            [tools.agents.test-support :refer [start-server!]]))

(def ^:private assertion "eyJhbGciOiJSUzI1NiJ9.subject-jwt-SECRET.sig")

(defn- local [port] (str "http://127.0.0.1:" port))

(defn- canned-message []
  "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}")

(defn- token-response
  ([tok] (token-response tok 600))
  ([tok expires-in]
   {:status 200 :headers {"content-type" "application/json"}
    :body (a/write-json {"access_token" tok "token_type" "Bearer" "expires_in" expires-in})}))

(defn- with-server
  "Start a mock on an OS-assigned port answering every path with handler;
   call (f port); stop."
  [handler f]
  (let [{:keys [port stop!]} (start-server! 0 "/" handler)]
    (try (f port) (finally (stop!)))))

(defn- env-fn [m] (fn [k] (get m k)))

(defn- temp-file [content]
  (let [f (java.io.File/createTempFile "wif-token" ".jwt")]
    (.deleteOnExit f)
    (spit f content)
    f))

(defn empty-home
  "A fresh empty directory standing in for user.home, so the profile steps of
   the chain never look at the real ~/.config/anthropic."
  []
  (str (java.nio.file.Files/createTempDirectory "anthropic-home" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- thrown [f]
  (try (f) nil (catch Exception e e)))

(def ^:private wif-env
  {"ANTHROPIC_FEDERATION_RULE_ID" "fdrl_1"
   "ANTHROPIC_ORGANIZATION_ID"    "org-uuid"
   "ANTHROPIC_IDENTITY_TOKEN"     assertion})

;; ---------------------------------------------------------------------------
;; Request shape
;; ---------------------------------------------------------------------------

(deftest exchange-request-shape
  (let [reqs (atom [])]
    (with-server
      (fn [req] (swap! reqs conj req) (token-response "at-1"))
      (fn [port]
        (let [src (creds/workload-identity-source
                   {:federation-rule-id "fdrl_1" :organization-id "org-uuid"
                    :identity-token-fn (constantly assertion)
                    :service-account-id "svac_1" :workspace-id "wrkspc_1" :scope "ignored"
                    :base-url (str (local port) "/")})]
          (is (= "at-1" (token/token! src)))
          (let [[req & more] @reqs
                body (a/read-json (:body req))]
            (is (empty? more))
            (is (= "POST" (:method req)))
            (is (= "/v1/oauth/token" (:path req)) "trailing slash on base-url is stripped")
            (is (= "oauth-2025-04-20,oidc-federation-2026-04-01" (get (:headers req) "anthropic-beta")))
            (is (= "application/json" (get (:headers req) "content-type")))
            (is (nil? (get (:headers req) "anthropic-version")))
            (is (nil? (get (:headers req) "authorization")))
            (is (nil? (get (:headers req) "x-api-key")))
            (is (= {"grant_type"         "urn:ietf:params:oauth:grant-type:jwt-bearer"
                    "assertion"          assertion
                    "federation_rule_id" "fdrl_1"
                    "organization_id"    "org-uuid"
                    "service_account_id" "svac_1"
                    "workspace_id"       "wrkspc_1"}
                   body)
                "scope is accepted but never sent")))))))

(deftest optional-ids-omitted-when-nil
  (let [body (atom nil)]
    (with-server
      (fn [req] (reset! body (a/read-json (:body req))) (token-response "at"))
      (fn [port]
        (token/token! (creds/workload-identity-source
                       {:federation-rule-id "r" :organization-id "o"
                        :identity-token-fn (constantly assertion) :base-url (local port)}))
        (is (= #{"grant_type" "assertion" "federation_rule_id" "organization_id"}
               (set (keys @body))))))))

;; ---------------------------------------------------------------------------
;; End to end: env discovery in `client`, messages with the exchanged token
;; ---------------------------------------------------------------------------

(deftest client-discovers-wif-from-env-and-sends-bearer
  (let [reqs (atom [])]
    (with-server
      (fn [req]
        (swap! reqs conj req)
        (if (= "/v1/oauth/token" (:path req))
          (token-response "at-env")
          {:status 200 :body (canned-message)}))
      (fn [port]
        (with-redefs [a/getenv (env-fn (assoc wif-env "ANTHROPIC_BASE_URL" (local port)))
                      a/user-home (constantly (empty-home))]
          (let [c (a/client)]
            (is (token/token-source? (:credential-source c)))
            (is (empty? @reqs) "nothing is exchanged at construction")
            (is (= "hi" (a/output-text (a/messages-create c {"model" "m" "max_tokens" 1 "messages" []}))))
            (a/messages-create c {"model" "m" "max_tokens" 1 "messages" []})
            (let [[ex m1 m2 & more] @reqs]
              (is (empty? more) "the token is cached across requests")
              (is (= "/v1/oauth/token" (:path ex)) "exchange hits the client's ANTHROPIC_BASE_URL")
              (doseq [m [m1 m2]]
                (is (= "/v1/messages" (:path m)))
                (is (= "Bearer at-env" (get (:headers m) "authorization")))
                (is (= "oauth-2025-04-20" (get (:headers m) "anthropic-beta"))
                    "API requests carry only the oauth beta, never the federation switch")
                (is (nil? (get (:headers m) "anthropic-workspace-id")))))))))))

(deftest messages-401-invalidates-and-re-exchanges
  (let [exchanges (atom 0)
        auths     (atom [])]
    (with-server
      (fn [req]
        (if (= "/v1/oauth/token" (:path req))
          (token-response (str "at-" (swap! exchanges inc)))
          (let [auth (get (:headers req) "authorization")]
            (swap! auths conj auth)
            (if (= "Bearer at-1" auth)
              {:status 401 :body "{\"error\":{\"message\":\"revoked\"}}"}
              {:status 200 :body (canned-message)}))))
      (fn [port]
        (let [c (a/client {:credential-source (creds/workload-identity-source
                                               {:federation-rule-id "r" :organization-id "o"
                                                :identity-token-fn (constantly assertion)
                                                :base-url (local port)})
                           :base-url (local port) :max-retries 0})]
          (is (= "hi" (a/output-text (a/messages-create c {"model" "m" "max_tokens" 1 "messages" []}))))
          (is (= 2 @exchanges))
          (is (= ["Bearer at-1" "Bearer at-2"] @auths)))))))

;; ---------------------------------------------------------------------------
;; Expiry and subject-token re-read
;; ---------------------------------------------------------------------------

(deftest refreshes-before-expiry-and-re-reads-the-token-file
  (let [assertions (atom [])
        now        (atom 1000000)
        f          (temp-file (str "  jwt-one\n"))]
    (with-server
      (fn [req]
        (let [n (count (swap! assertions conj (get (a/read-json (:body req)) "assertion")))]
          (token-response (str "at-" n) 600)))
      (fn [port]
        (let [src (creds/workload-identity-source
                   {:federation-rule-id "r" :organization-id "o"
                    :identity-token-file (str f) :base-url (local port) :now-ms #(deref now)})]
          (is (= "at-1" (token/token! src)))
          (spit f "jwt-two")
          (swap! now + (* 1000 (- 600 121)))
          (is (= "at-1" (token/token! src)) "still outside the 120 s refresh window")
          (swap! now + 2000)
          (is (= "at-2" (token/token! src)) "inside the window: re-exchanged")
          (is (= ["jwt-one" "jwt-two"] @assertions) "file re-read and trimmed per exchange"))))))

(deftest env-literal-token-is-re-read-per-exchange
  (let [assertions (atom [])
        env        (atom wif-env)]
    (with-server
      (fn [req]
        (swap! assertions conj (get (a/read-json (:body req)) "assertion"))
        (token-response "at"))
      (fn [port]
        (let [src (creds/workload-identity-from-env {:getenv (fn [k] (get @env k)) :base-url (local port)})]
          (token/token! src)
          (swap! env assoc "ANTHROPIC_IDENTITY_TOKEN" "rotated-jwt")
          (token/invalidate! src)
          (token/token! src)
          (is (= [assertion "rotated-jwt"] @assertions))
          (swap! env dissoc "ANTHROPIC_IDENTITY_TOKEN")
          (token/invalidate! src)
          (is (= :tools.agents.anthropic.error/identity-token
                 (:type (ex-data (thrown #(token/token! src)))))))))))

;; ---------------------------------------------------------------------------
;; Token-endpoint errors
;; ---------------------------------------------------------------------------

(deftest token-endpoint-401-is-retried-once-then-throws-with-hint
  (let [hits (atom 0)]
    (with-server
      (fn [_] (swap! hits inc)
        {:status 401 :headers {"request-id" "req_wif"}
         :body (a/write-json {"error" "invalid_grant" "error_description" "rule mismatch"})})
      (fn [port]
        (let [src (creds/workload-identity-source
                   {:federation-rule-id "r" :organization-id "o"
                    :identity-token-fn (constantly assertion) :base-url (local port)})
              e   (thrown #(token/token! src))]
          (is (= 2 @hits))
          (is (= {:type :tools.agents.anthropic.error/token-exchange :status 401
                  :body {"error" "invalid_grant" "error_description" "rule mismatch"}
                  :request-id "req_wif"}
                 (ex-data e)))
          (is (str/includes? (ex-message e) "Ensure your federation rule matches"))
          (is (str/includes? (ex-message e) "ANTHROPIC_WORKSPACE_ID"))
          (is (str/includes? (ex-message e) "[request_id=req_wif]")))))))

(deftest token-endpoint-5xx-is-not-retried
  (let [hits (atom 0)]
    (with-server
      (fn [_] (swap! hits inc) {:status 503 :body "unavailable"})
      (fn [port]
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (is (= 1 @hits))
          (is (= 503 (:status (ex-data e))))
          (is (= "unavailable" (:body (ex-data e)))))))))

(defn- assert-no-leak [e & secrets]
  (let [text (str (ex-message e) " " (pr-str (ex-data e)))]
    (doseq [s secrets]
      (is (not (str/includes? text s)) (str "leaked " s)))))

(deftest errors-never-leak-assertion-or-access-token
  (testing "JSON error echoing the assertion keeps only RFC 6749 fields, scrubbed"
    (with-server
      (fn [_] {:status 400
               :body (a/write-json {"error" "invalid_request"
                                    "error_description" (str "bad assertion " assertion)
                                    "assertion" assertion "debug" {"token" assertion}})})
      (fn [port]
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
          (is (= #{"error" "error_description"} (set (keys (:body (ex-data e))))))
          (assert-no-leak e assertion)))))
  (testing "a non-string RFC 6749 field echoing the assertion is dropped"
    (with-server
      (fn [_] {:status 400
               :body (a/write-json {"error" "invalid_request"
                                    "error_description" {"assertion" assertion}
                                    "error_uri" [assertion]})})
      (fn [port]
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (is (= {"error" "invalid_request"} (:body (ex-data e))))
          (assert-no-leak e assertion)))))
  (testing "non-JSON error body echoing the assertion"
    (with-server
      (fn [_] {:status 500 :body (str "<html>" assertion "</html>")})
      (fn [port]
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (assert-no-leak e assertion)))))
  (testing "2xx without expires_in never exposes the access token"
    (with-server
      (fn [_] {:status 200 :body (a/write-json {"access_token" "at-SECRET" "assertion" assertion})})
      (fn [port]
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (is (str/includes? (ex-message e) "missing required fields"))
          (assert-no-leak e assertion "at-SECRET")))))
  (testing "unsupported token_type, JSON array, non-JSON 2xx"
    (doseq [[body expect] [[(a/write-json {"access_token" "at-SECRET" "expires_in" 60 "token_type" "mac"})
                            "unsupported token_type"]
                           [(a/write-json [assertion]) "JSON list"]
                           [(str "not json " assertion) "non-JSON response"]]]
      (with-server
        (fn [_] {:status 200 :body body})
        (fn [port]
          (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                   :organization-id "o" :base-url (local port)}))]
            (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
            (is (str/includes? (ex-message e) expect))
            (assert-no-leak e assertion "at-SECRET"))))))
  (testing "a messages 401 after a successful exchange does not leak the token"
    (with-server
      (fn [req] (if (= "/v1/oauth/token" (:path req))
                  (token-response "at-SECRET")
                  {:status 401 :body "{\"error\":{\"message\":\"nope\"}}"}))
      (fn [port]
        (let [c (a/client {:credential-source (creds/workload-identity-source
                                               {:federation-rule-id "r" :organization-id "o"
                                                :identity-token-fn (constantly assertion)
                                                :base-url (local port)})
                           :base-url (local port)})
              e (thrown #(a/messages-create c {"model" "m" "max_tokens" 1 "messages" []}))]
          (is (= :tools.agents.anthropic.error/authentication (:type (ex-data e))))
          (assert-no-leak e assertion "at-SECRET"))))))

(deftest expires-in-accepts-numeric-strings-and-converts-seconds-to-ms
  (with-server
    (fn [_] {:status 200 :body "{\"access_token\":\"at\",\"expires_in\":\"90\"}"})
    (fn [port]
      (is (= {:token "at" :expires-at (+ 5000 90000)}
             (creds/exchange-token! {:assertion assertion :federation-rule-id "r" :organization-id "o"
                                     :base-url (local port) :now-ms (constantly 5000)}))))))

(deftest oversized-assertion-and-response-are-refused
  (let [hits (atom 0)]
    (with-server
      (fn [_] (swap! hits inc) {:status 200 :body (apply str (repeat (inc (bit-shift-left 1 20)) "x"))})
      (fn [port]
        (let [big (apply str (repeat (inc (* 16 1024)) "a"))
              e   (thrown #(creds/exchange-token! {:assertion big :federation-rule-id "r"
                                                   :organization-id "o" :base-url (local port)}))]
          (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
          (is (zero? @hits) "rejected before any POST"))
        (let [e (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                                 :organization-id "o" :base-url (local port)}))]
          (is (str/includes? (ex-message e) "exceeds 1048576 bytes")))))))

(deftest unreachable-endpoint-is-a-token-exchange-error
  (let [port (let [{:keys [port stop!]} (start-server! 0 "/" (fn [_] {:status 200 :body ""}))]
               (stop!) port)
        e    (thrown #(creds/exchange-token! {:assertion assertion :federation-rule-id "r"
                                              :organization-id "o" :base-url (local port)}))]
    (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))
    (is (str/includes? (ex-message e) "failed to reach token endpoint"))))

(deftest identity-token-file-errors
  (let [missing (str (java.io.File. (System/getProperty "java.io.tmpdir") (str "no-such-" (random-uuid))))]
    (is (= {:type :tools.agents.anthropic.error/identity-token :path missing}
           (ex-data (thrown (creds/identity-token-file missing))))))
  (let [f (temp-file "  \n")]
    (is (str/includes? (ex-message (thrown (creds/identity-token-file f))) "is empty")))
  (is (str/includes? (ex-message (thrown (creds/identity-token-file (System/getProperty "java.io.tmpdir"))))
                     "is a directory")))

(deftest options-are-validated
  (doseq [opts [{:organization-id "o" :identity-token-fn (constantly "j")}
                {:federation-rule-id "r" :identity-token-fn (constantly "j")}
                {:federation-rule-id "r" :organization-id "o"}
                {:federation-rule-id "r" :organization-id "o" :identity-token-fn (constantly "j")
                 :identity-token-file "/x"}
                {:federation-rule-id "r" :organization-id "o" :identity-token-fn (constantly "j")
                 :base-url "http://gateway.example.com"}]]
    (is (= :tools.agents.anthropic.error/invalid-credentials
           (:type (ex-data (thrown #(creds/workload-identity-source opts)))))
        (pr-str opts)))
  (testing "https and loopback http are accepted"
    (doseq [u ["https://api.example.com" "http://localhost:8080" "http://127.0.0.1:1" "http://[::1]:1"]]
      (is (token/token-source? (creds/workload-identity-source
                                {:federation-rule-id "r" :organization-id "o"
                                 :identity-token-fn (constantly "j") :base-url u}))))))

;; ---------------------------------------------------------------------------
;; Env discovery and chain precedence (injected env, no network)
;; ---------------------------------------------------------------------------

(defn- exchange-body
  "The body `src` POSTs on its first exchange, captured by a fake http-fn."
  [make-src]
  (let [captured (atom nil)
        http-fn  (fn [req]
                   (reset! captured (assoc (a/read-json (:body req)) ::url (:url req)))
                   {:status 200 :headers {} :body (.getBytes "{\"access_token\":\"t\",\"expires_in\":60}" "UTF-8")})]
    (token/token! (make-src http-fn))
    @captured))

(deftest from-env-configuration
  (testing "nil unless rule id, org id and a token source are all present"
    (doseq [env [{}
                 (dissoc wif-env "ANTHROPIC_FEDERATION_RULE_ID")
                 (dissoc wif-env "ANTHROPIC_ORGANIZATION_ID")
                 (dissoc wif-env "ANTHROPIC_IDENTITY_TOKEN")
                 (assoc wif-env "ANTHROPIC_FEDERATION_RULE_ID" "")
                 (-> wif-env (dissoc "ANTHROPIC_IDENTITY_TOKEN") (assoc "ANTHROPIC_IDENTITY_TOKEN_FILE" ""))]]
      (is (nil? (creds/workload-identity-from-env {:getenv (env-fn env)})) (pr-str env))))
  (testing "an empty ANTHROPIC_IDENTITY_TOKEN still counts as set (SDK: `in os.environ`)"
    (is (some? (creds/workload-identity-from-env
                {:getenv (env-fn (assoc wif-env "ANTHROPIC_IDENTITY_TOKEN" ""))}))))
  (testing "file wins over literal; optional ids; empty workspace dropped; scope not sent"
    (let [f    (temp-file "file-jwt")
          env  (assoc wif-env "ANTHROPIC_IDENTITY_TOKEN_FILE" (str f)
                      "ANTHROPIC_SERVICE_ACCOUNT_ID" "svac_9" "ANTHROPIC_WORKSPACE_ID" ""
                      "ANTHROPIC_SCOPE" "user:inference")
          body (exchange-body #(creds/workload-identity-from-env {:getenv (env-fn env) :http-fn %}))]
      (is (= "file-jwt" (get body "assertion")))
      (is (= "svac_9" (get body "service_account_id")))
      (is (not (contains? body "workspace_id")))
      (is (not (contains? body "scope")))
      (is (= "https://api.anthropic.com/v1/oauth/token" (::url body)))))
  (testing "base-url: opts > ANTHROPIC_BASE_URL > default"
    (let [env (assoc wif-env "ANTHROPIC_BASE_URL" "https://env.example.com")]
      (is (= "https://env.example.com/v1/oauth/token"
             (::url (exchange-body #(creds/workload-identity-from-env {:getenv (env-fn env) :http-fn %})))))
      (is (= "https://opt.example.com/v1/oauth/token"
             (::url (exchange-body #(creds/workload-identity-from-env
                                     {:getenv (env-fn env) :http-fn % :base-url "https://opt.example.com"})))))))
  (testing "a cleartext non-loopback base-url with WIF configured fails fast"
    (is (= :tools.agents.anthropic.error/invalid-credentials
           (:type (ex-data (thrown #(creds/workload-identity-from-env
                                     {:getenv (env-fn wif-env) :base-url "http://gw.example.com"}))))))))

(def ^:private resolve-chain #'a/resolve-client-credentials)

(deftest chain-precedence
  (let [no-http (fn [_] (throw (ex-info "must not exchange during resolution" {})))
        home    (empty-home)
        resolve (fn [opts env] (with-redefs [a/user-home (constantly home)]
                                 (resolve-chain opts (env-fn env) "https://api.anthropic.com" no-http)))]
    (testing "static env credentials shadow WIF; precedence among them is unchanged"
      (is (= {:api-key "k"} (resolve {} (assoc wif-env "ANTHROPIC_API_KEY" "k"))))
      (is (= {:auth-token "t"} (resolve {} (assoc wif-env "ANTHROPIC_AUTH_TOKEN" "t"))))
      (is (= {:api-key "k"} (resolve {} (assoc wif-env "ANTHROPIC_API_KEY" "k" "ANTHROPIC_AUTH_TOKEN" "t")))))
    (testing "explicit args shadow env WIF"
      (is (= {:api-key "x"} (resolve {:api-key "x"} wif-env)))
      (is (= {:auth-token "x"} (resolve {:auth-token "x"} wif-env)))
      (let [src (token/token-cache {:fetch! (fn [] {:token "u" :expires-at nil})})]
        (is (identical? src (:credential-source (resolve {:credential-source src} wif-env))))))
    (testing "empty static env vars do not shadow WIF"
      (is (token/token-source?
           (:credential-source (resolve {} (assoc wif-env "ANTHROPIC_API_KEY" "" "ANTHROPIC_AUTH_TOKEN" "")))))
      (is (= #{:credential-source} (set (keys (resolve {} wif-env))))))
    (testing "nothing configured still throws missing-credentials"
      (is (= :tools.agents.anthropic.error/missing-credentials
             (:type (ex-data (thrown #(resolve {} {}))))))
      (is (= :tools.agents.anthropic.error/missing-credentials
             (:type (ex-data (thrown #(resolve {} (dissoc wif-env "ANTHROPIC_ORGANIZATION_ID"))))))))))

;; ---------------------------------------------------------------------------
;; Injected :http (#10): the env-discovered token exchange uses it too
;; ---------------------------------------------------------------------------

(deftest client-http-carries-the-wif-exchange
  (let [calls (atom [])
        http  (fn [req]
                (swap! calls conj req)
                (if (str/ends-with? (:url req) "/v1/oauth/token")
                  (token-response "at-injected")
                  {:status 200 :headers {} :body (canned-message)}))]
    (with-redefs [a/getenv (env-fn (assoc wif-env "ANTHROPIC_BASE_URL" "https://fake.example"))
                  a/user-home (constantly (empty-home))]
      (let [c (a/client {:http http})]
        (is (= "hi" (a/output-text (a/messages-create c {"model" "m" "max_tokens" 1 "messages" []}))))
        (let [[ex m & more] @calls]
          (is (empty? more))
          (is (= "https://fake.example/v1/oauth/token" (:url ex)))
          (is (= "https://fake.example/v1/messages" (:url m)))
          (is (= "Bearer at-injected" (get (:headers m) "authorization"))))))))
