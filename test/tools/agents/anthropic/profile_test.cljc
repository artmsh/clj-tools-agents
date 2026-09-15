(ns tools.agents.anthropic.profile-test
  "Profile credentials (#36) against temp config dirs and a fake token
   endpoint on the shared mock server (OS-assigned ports). The env is an
   injected map and user.home a temp dir: the real process env and the real
   ~/.config/anthropic are never read."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.credentials :as creds]
            [tools.agents.token :as token]
            [tools.agents.test-support :refer [start-server!]])
  (:import [java.io File]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private refresh-secret "rt-SECRET-refresh-token-0123456789")
(def ^:private access-secret "at-SECRET-disk-access-token-0123456789")

(defn- temp-dir ^File [prefix]
  (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0))))

(defn- chmod! [f perms]
  (Files/setPosixFilePermissions (.toPath (File. (str f))) (PosixFilePermissions/fromString perms)))

(defn- perms [f]
  (PosixFilePermissions/toString
   (Files/getPosixFilePermissions (.toPath (File. (str f))) (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))))

(defn- file [& parts] (File. ^String (str/join File/separator (map str parts))))

(defn- write-config! [dir profile config]
  (let [f (file dir "configs" (str profile ".json"))]
    (.mkdirs (.getParentFile f))
    (spit f (a/write-json config))
    f))

(defn- write-creds!
  ([dir profile m] (write-creds! dir profile m "rw-------"))
  ([dir profile m mode]
   (let [f (file dir "credentials" (str profile ".json"))]
     (.mkdirs (.getParentFile f))
     (spit f (if (string? m) m (a/write-json m)))
     (chmod! f mode)
     f)))

(defn- read-creds [dir profile] (a/read-json (slurp (file dir "credentials" (str profile ".json")))))

(defn- dir-listing [dir] (set (.list (File. (str dir)))))

(defn- env-fn [m] (fn [k] (get m k)))
(defn- local [port] (str "http://127.0.0.1:" port))
(defn- now-s [] (quot (System/currentTimeMillis) 1000))

(defn- thrown [f] (try (f) nil (catch Exception e e)))
(defn- err-type [f] (:type (ex-data (thrown f))))

(defn- leaks? [e & secrets]
  (let [s (str (ex-message e) (pr-str (ex-data e)))]
    (boolean (some #(str/includes? s %) secrets))))

(defn- with-server [handler f]
  (let [{:keys [port stop!]} (start-server! 0 "/" handler)]
    (try (f port) (finally (stop!)))))

(defn- json-response [m]
  {:status 200 :headers {"content-type" "application/json"} :body (a/write-json m)})

(defn- canned-message []
  "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}")

(defn- oauth-config
  ([] (oauth-config {}))
  ([extra] (merge {"version" "1.0"
                   "authentication" {"type" "user_oauth" "client_id" "client-abc"}
                   "organization_id" "org-1"}
                  extra)))

(def ^:private create-req {"model" "m" "max_tokens" 1 "messages" []})

(def ^:private resolve-chain #'a/resolve-client-credentials)

;; ---------------------------------------------------------------------------
;; Paths and names (_constants.py:67-190)
;; ---------------------------------------------------------------------------

(deftest config-dir-resolution
  (let [home (temp-dir "home")]
    (is (= (str (file home ".config" "anthropic")) (creds/config-dir {:getenv (env-fn {}) :home home}))
        "~/.config/anthropic on every POSIX OS, macOS included")
    (is (= "/elsewhere" (creds/config-dir {:getenv (env-fn {"ANTHROPIC_CONFIG_DIR" "/elsewhere"}) :home home})))
    (is (= (str (file home ".config" "anthropic"))
           (creds/config-dir {:getenv (env-fn {"ANTHROPIC_CONFIG_DIR" ""}) :home home}))
        "empty ANTHROPIC_CONFIG_DIR is unset")))

(deftest active-profile-precedence-and-validation
  (let [dir (temp-dir "cfg")
        ap  (fn [env] (creds/active-profile {:getenv (env-fn env) :config-dir dir}))]
    (is (= "default" (ap {})))
    (spit (file dir "active_config") "  work \n")
    (is (= "work" (ap {})) "pointer file, trimmed")
    (is (= "dev" (ap {"ANTHROPIC_PROFILE" "dev"})) "ANTHROPIC_PROFILE beats the pointer")
    (is (= "work" (ap {"ANTHROPIC_PROFILE" ""})) "empty ANTHROPIC_PROFILE is unset")
    (spit (file dir "active_config") "")
    (is (= "default" (ap {})) "empty pointer is ignored")
    (doseq [bad [" dev" "dev " ".hidden" "a/b" "a\\b" (str "a" (char 0) "b") "../../etc/passwd"]]
      (is (= :tools.agents.anthropic.error/profile (err-type #(ap {"ANTHROPIC_PROFILE" bad}))) (pr-str bad)))
    (spit (file dir "active_config") "../escape")
    (is (= :tools.agents.anthropic.error/profile (err-type #(ap {}))))))

(deftest symlinked-configs-dir-cannot-escape
  (let [dir     (temp-dir "cfg")
        outside (temp-dir "outside")]
    (write-config! outside "default" (oauth-config))
    (.mkdirs (file dir))
    (Files/createSymbolicLink (.toPath (file dir "configs")) (.toPath (file outside "configs"))
                              (make-array FileAttribute 0))
    (let [e (thrown #(creds/profile-source {:config-dir dir :getenv (env-fn {})}))]
      (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))))
      (is (re-find #"escapes config directory" (ex-message e))))))

;; ---------------------------------------------------------------------------
;; Chain precedence (_chain.py:76-155)
;; ---------------------------------------------------------------------------

(defn- resolve-with [home opts env]
  (with-redefs [a/user-home (constantly (str home))]
    (resolve-chain opts (env-fn env) "https://api.anthropic.com"
                   (fn [_] (throw (ex-info "must not POST during resolution" {}))))))

(def ^:private wif-env
  {"ANTHROPIC_FEDERATION_RULE_ID" "fdrl_1"
   "ANTHROPIC_ORGANIZATION_ID"    "org-uuid"
   "ANTHROPIC_IDENTITY_TOKEN"     "jwt"})

(deftest chain-step-3-triggers-and-errors-propagate
  (let [home    (temp-dir "home")
        default (file home ".config" "anthropic")
        custom  (temp-dir "cfg")]
    (testing "nothing on disk, no signals: missing-credentials"
      (is (= :tools.agents.anthropic.error/missing-credentials (err-type #(resolve-with home {} {})))))
    (testing "ANTHROPIC_CONFIG_DIR alone selects the default profile there; missing config throws"
      (let [e (thrown #(resolve-with home {} {"ANTHROPIC_CONFIG_DIR" (str custom)}))]
        (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))))
        (is (re-find #"Config file not found" (ex-message e))))
      (write-config! custom "default" (oauth-config))
      (is (token/token-source? (:credential-source (resolve-with home {} {"ANTHROPIC_CONFIG_DIR" (str custom)})))))
    (testing "ANTHROPIC_PROFILE names a profile under the default dir; missing throws"
      (is (= :tools.agents.anthropic.error/profile (err-type #(resolve-with home {} {"ANTHROPIC_PROFILE" "dev"}))))
      (write-config! default "dev" (oauth-config {"workspace_id" "wrkspc_dev"}))
      (is (= {"anthropic-workspace-id" "wrkspc_dev"}
             (:credential-headers (resolve-with home {} {"ANTHROPIC_PROFILE" "dev"})))))
    (testing "an active_config pointer is explicit selection; a missing target throws"
      (spit (file default "active_config") "ghost")
      (is (= :tools.agents.anthropic.error/profile (err-type #(resolve-with home {} {}))))
      (spit (file default "active_config") "dev")
      (is (= {"anthropic-workspace-id" "wrkspc_dev"} (:credential-headers (resolve-with home {} {}))))
      (.delete (file default "active_config")))
    (testing "a malformed explicitly selected config throws at resolution"
      (spit (file default "configs" "broken.json") "{not json")
      (is (= :tools.agents.anthropic.error/profile
             (err-type #(resolve-with home {} {"ANTHROPIC_PROFILE" "broken"})))))
    (testing "ANTHROPIC_PROFILE beats WIF env vars"
      (is (contains? (resolve-with home {} (assoc wif-env "ANTHROPIC_PROFILE" "dev")) :credential-headers)))
    (testing "static env credentials beat an explicit profile"
      (is (= {:api-key "k"} (resolve-with home {} {"ANTHROPIC_PROFILE" "dev" "ANTHROPIC_API_KEY" "k"})))
      (is (= {:auth-token "t"} (resolve-with home {} {"ANTHROPIC_PROFILE" "dev" "ANTHROPIC_AUTH_TOKEN" "t"}))))))

(deftest chain-step-5-fallback-profile
  (let [home    (temp-dir "home")
        default (file home ".config" "anthropic")]
    (write-config! default "default" (oauth-config {"workspace_id" "wrkspc_default"}))
    (testing "configs/default.json is picked up with no signals"
      (is (= {"anthropic-workspace-id" "wrkspc_default"} (:credential-headers (resolve-with home {} {})))))
    (testing "WIF env vars beat a leftover default profile"
      (is (= #{:credential-source} (set (keys (resolve-with home {} wif-env))))))
    (testing "a malformed fallback config is swallowed: missing-credentials, not a profile error"
      (spit (file default "configs" "default.json") "[1,2]")
      (is (= :tools.agents.anthropic.error/missing-credentials (err-type #(resolve-with home {} {}))))
      (write-config! default "default" {"authentication" {"type" "user_oauth"} "base_url" "http://evil.example.com"})
      (is (= :tools.agents.anthropic.error/missing-credentials (err-type #(resolve-with home {} {})))
          "a cleartext config base_url is swallowed too"))
    (testing "only config loading is guarded: a broken federation identity path resolves, then throws on first token"
      (write-config! default "default" {"authentication" {"type" "oidc_federation" "federation_rule_id" "r"
                                                          "identity_token" {"source" "file" "path" "/nonexistent/tok"}}
                                        "organization_id" "o"})
      (let [src (:credential-source (resolve-with home {} {}))]
        (is (some? src))
        (is (= :tools.agents.anthropic.error/identity-token (err-type #(token/token! src))))))))

(deftest explicit-profile-option
  (let [home    (temp-dir "home")
        default (file home ".config" "anthropic")]
    (write-config! default "work" (oauth-config))
    (testing ":profile beats static env credentials (explicit args disable env lookup)"
      (is (token/token-source? (:credential-source (resolve-with home {:profile "work"} {"ANTHROPIC_API_KEY" "k"})))))
    (testing ":profile with another credential is rejected"
      (doseq [extra [{:api-key "k"} {:auth-token "t"} {:credential-source (token/token-cache {:fetch! (fn [])})}]]
        (is (= :tools.agents.anthropic.error/invalid-credentials
               (err-type #(resolve-with home (merge {:profile "work"} extra) {}))))))
    (testing "bad or missing :profile throws"
      (is (= :tools.agents.anthropic.error/profile (err-type #(resolve-with home {:profile "../x"} {}))))
      (is (= :tools.agents.anthropic.error/profile (err-type #(resolve-with home {:profile "nope"} {})))))))

;; ---------------------------------------------------------------------------
;; Config format, env fill-in, base URL, workspace header
;; ---------------------------------------------------------------------------

(deftest config-format-and-env-fill
  (let [dir (temp-dir "cfg")
        src (fn [env] (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn env)}))]
    (testing "profile fields win; env only fills absent or empty ones"
      (write-config! dir "p" (oauth-config {"workspace_id" "wrkspc_file" "base_url" "https://file.example.com/"}))
      (let [p (src {"ANTHROPIC_WORKSPACE_ID" "wrkspc_env" "ANTHROPIC_BASE_URL" "https://env.example.com"})]
        (is (= {"anthropic-workspace-id" "wrkspc_file"} (:credential-headers p)))
        (is (= "https://file.example.com" (:base-url p)) "trailing slash stripped")
        (is (= "p" (:profile p))))
      (write-config! dir "p" (oauth-config {"workspace_id" ""}))
      (let [p (src {"ANTHROPIC_WORKSPACE_ID" "wrkspc_env" "ANTHROPIC_BASE_URL" "https://env.example.com"})]
        (is (= {"anthropic-workspace-id" "wrkspc_env"} (:credential-headers p)))
        (is (= "https://env.example.com" (:base-url p))))
      (let [p (src {})]
        (is (= {} (:credential-headers p)))
        (is (nil? (:base-url p)))))
    (testing "oidc_federation profiles send no workspace header"
      (write-config! dir "p" {"authentication" {"type" "oidc_federation" "federation_rule_id" "r"}
                              "organization_id" "o" "workspace_id" "wrkspc_1"})
      (is (= {} (:credential-headers (src {})))))
    (testing "malformed configs throw at construction"
      (doseq [[content re] [["{oops" #"not valid JSON"]
                            ["[]" #"must contain a JSON object"]
                            ["{\"version\":\"1.0\"}" #"missing the 'authentication' object"]]]
        (spit (file dir "configs" "p.json") content)
        (let [e (thrown #(src {}))]
          (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))) content)
          (is (re-find re (ex-message e)) content)
          (is (str/ends-with? (:path (ex-data e)) "p.json")))))
    (testing "a cleartext config base_url throws at construction"
      (write-config! dir "p" (oauth-config {"base_url" "http://gw.example.com"}))
      (is (re-find #"must use https" (ex-message (thrown #(src {}))))))
    (testing "an unknown authentication.type fails on first token, not at construction"
      (write-config! dir "p" {"authentication" {"type" "api_key"}})
      (let [p (src {})]
        (is (re-find #"Unknown authentication.type" (ex-message (thrown #(token/token! (:credential-source p))))))))))

(deftest client-adopts-profile-base-url-and-workspace-header
  (let [home (temp-dir "home")
        dir  (file home ".config" "anthropic")
        reqs (atom [])]
    (with-server
      (fn [req] (swap! reqs conj req) {:status 200 :body (canned-message)})
      (fn [port]
        (write-config! dir "default" (oauth-config {"authentication" {"type" "user_oauth"}
                                                    "workspace_id" "wrkspc_42"
                                                    "base_url" (local port)}))
        (write-creds! dir "default" {"type" "oauth_token" "access_token" access-secret "expires_at" nil})
        (with-redefs [a/user-home (constantly (str home))
                      a/getenv    (env-fn {})]
          (let [c (a/client)]
            (is (= (local port) (:base-url c)) "profile base_url used when nothing explicit")
            (is (= "hi" (a/output-text (a/messages-create c create-req))))
            (let [h (:headers (first @reqs))]
              (is (= (str "Bearer " access-secret) (get h "authorization")))
              (is (= "oauth-2025-04-20" (get h "anthropic-beta")))
              (is (= "wrkspc_42" (get h "anthropic-workspace-id"))))
            (is (= "https://explicit.example.com" (:base-url (a/client {:base-url "https://explicit.example.com"}))))))
        (with-redefs [a/user-home (constantly (str home))
                      a/getenv    (env-fn {"ANTHROPIC_BASE_URL" "https://env.example.com"})]
          (is (= "https://env.example.com" (:base-url (a/client))) "ANTHROPIC_BASE_URL beats the profile"))))))

;; ---------------------------------------------------------------------------
;; user_oauth without client_id: externally rotated file
;; ---------------------------------------------------------------------------

(deftest user-oauth-without-client-id-re-reads-and-never-refreshes
  (let [dir (temp-dir "cfg")]
    (write-config! dir "p" {"authentication" {"type" "user_oauth"}})
    (write-creds! dir "p" {"access_token" "rotated-1" "expires_at" (- (now-s) 10)})
    (let [http (fn [_] (throw (ex-info "must never POST" {})))
          src  (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {}) :http-fn http}))]
      (is (= "rotated-1" (token/token! src)) "an expired token is still served: the daemon owns freshness")
      (write-creds! dir "p" {"access_token" "rotated-2" "expires_at" nil})
      (is (= "rotated-2" (token/token! src)) "expired => refetch => re-read")
      (write-creds! dir "p" {"access_token" "rotated-3"})
      (is (= "rotated-2" (token/token! src)) "nil expires_at is cached until invalidated")
      (token/invalidate! src "rotated-2")
      (is (= "rotated-3" (token/token! src))))))

;; ---------------------------------------------------------------------------
;; user_oauth refresh_token grant + write-back
;; ---------------------------------------------------------------------------

(deftest refresh-request-shape-write-back-and-rotation
  (let [dir  (temp-dir "cfg")
        reqs (atom [])]
    (write-config! dir "p" (oauth-config {"workspace_id" "wrkspc_1"}))
    (write-creds! dir "p" {"version" "1.0" "type" "oauth_token" "access_token" access-secret
                           "expires_at" (- (now-s) 5) "refresh_token" refresh-secret "extra" "kept"})
    (with-server
      (fn [req]
        (swap! reqs conj req)
        (json-response {"access_token" "at-new" "expires_in" 900 "refresh_token" "rt-rotated" "token_type" "Bearer"}))
      (fn [port]
        (let [before (now-s)
              src    (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                                :base-url (local port)}))]
          (is (= "at-new" (token/token! src)))
          (let [[req & more] @reqs]
            (is (empty? more))
            (is (= "POST" (:method req)))
            (is (= "/v1/oauth/token" (:path req)))
            (is (= "oauth-2025-04-20" (get (:headers req) "anthropic-beta")) "never the oidc-federation flag")
            (is (= "application/json" (get (:headers req) "content-type")))
            (is (nil? (get (:headers req) "authorization")))
            (is (= {"grant_type" "refresh_token" "refresh_token" refresh-secret "client_id" "client-abc"}
                   (a/read-json (:body req)))))
          (let [on-disk (read-creds dir "p")]
            (is (= "at-new" (get on-disk "access_token")))
            (is (= "rt-rotated" (get on-disk "refresh_token")))
            (is (= "oauth_token" (get on-disk "type")))
            (is (= "1.0" (get on-disk "version")))
            (is (= "kept" (get on-disk "extra")) "unknown fields survive the rewrite")
            (is (<= (+ before 900) (get on-disk "expires_at") (+ (now-s) 900)) "unix SECONDS"))
          (is (= "rw-------" (perms (file dir "credentials" "p.json"))))
          (is (= #{"p.json"} (dir-listing (file dir "credentials"))) "no temp file left behind")
          (is (= "at-new" (token/token! src)) "cached: no second POST")
          (is (= 1 (count @reqs))))))
    (testing "no refresh_token in the response keeps the old one; expires_in defaults to 3600"
      (write-creds! dir "p" {"access_token" "x" "expires_at" 0 "refresh_token" refresh-secret})
      (with-server
        (fn [_] (json-response {"access_token" "at-3600"}))
        (fn [port]
          (let [before (now-s)
                src    (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                                  :base-url (local port)}))]
            (is (= "at-3600" (token/token! src)))
            (let [on-disk (read-creds dir "p")]
              (is (= refresh-secret (get on-disk "refresh_token")))
              (is (<= (+ before 3600) (get on-disk "expires_at") (+ (now-s) 3600))))))))))

(deftest refresh-near-expiry-follows-disk-then-refreshes-at-expiry
  (let [dir   (temp-dir "cfg")
        now   (atom (* 1000 2000000000))
        posts (atom 0)
        exp   (+ 2000000000 600)]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" {"access_token" "disk-1" "expires_at" exp "refresh_token" refresh-secret})
    (with-server
      (fn [_] (json-response {"access_token" (str "fresh-" (swap! posts inc)) "expires_in" 600}))
      (fn [port]
        (let [src (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                             :base-url (local port) :now-ms #(deref now)}))]
          (is (= "disk-1" (token/token! src)) "fresh disk token, no grant")
          (reset! now (* 1000 (- exp 119)))
          (write-creds! dir "p" {"access_token" "disk-2" "expires_at" exp "refresh_token" refresh-secret})
          (is (= "disk-2" (token/token! src))
              "inside the 120 s skew the file is re-read (another process may have refreshed) ...")
          (is (zero? @posts) "... but the grant waits for strict expiry (_providers.py:536-543)")
          (reset! now (* 1000 exp))
          (is (= "fresh-1" (token/token! src)) "expired: one refresh grant")
          (is (= 1 @posts))
          (is (= "fresh-1" (get (read-creds dir "p") "access_token"))))))))

(deftest concurrent-callers-share-one-refresh
  (let [dir   (temp-dir "cfg")
        posts (atom 0)]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" {"access_token" "old" "expires_at" 1 "refresh_token" refresh-secret})
    (with-server
      (fn [_] (swap! posts inc) (Thread/sleep 200) (json-response {"access_token" "shared" "expires_in" 600}))
      (fn [port]
        (let [src     (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                                 :base-url (local port)}))
              results (mapv deref (mapv (fn [_] (future (token/token! src))) (range 8)))]
          (is (= (repeat 8 "shared") results))
          (is (= 1 @posts)))))))

(deftest messages-401-forces-refresh-past-a-fresh-disk-token
  (let [dir   (temp-dir "cfg")
        posts (atom 0)
        auths (atom [])]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" {"access_token" "revoked" "expires_at" (+ (now-s) 3600) "refresh_token" refresh-secret})
    (with-server
      (fn [req]
        (if (= "/v1/oauth/token" (:path req))
          (json-response {"access_token" (str "fresh-" (swap! posts inc)) "expires_in" 600})
          (let [auth (get (:headers req) "authorization")]
            (swap! auths conj auth)
            (if (= "Bearer revoked" auth)
              {:status 401 :body "{\"error\":{\"message\":\"revoked\"}}"}
              {:status 200 :body (canned-message)}))))
      (fn [port]
        (let [c (a/client {:credential-source (:credential-source
                                               (creds/profile-source {:profile "p" :config-dir dir
                                                                      :getenv (env-fn {}) :base-url (local port)}))
                           :base-url (local port) :max-retries 0})]
          (is (= "hi" (a/output-text (a/messages-create c create-req))))
          (is (= ["Bearer revoked" "Bearer fresh-1"] @auths)
              "without force the disk token (not yet expired) would be re-served")
          (is (= 1 @posts))
          (is (= "fresh-1" (get (read-creds dir "p") "access_token")))
          (a/messages-create c create-req)
          (is (= 1 @posts) "force is one-shot"))))))

(deftest force-survives-a-failed-refresh
  ;; _cache.py:92-103: force_refresh is cleared only after a successful fetch.
  (let [dir   (temp-dir "cfg")
        posts (atom 0)]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" {"access_token" "revoked" "expires_at" (+ (now-s) 3600) "refresh_token" refresh-secret})
    (with-server
      (fn [_]
        (if (= 1 (swap! posts inc))
          {:status 500 :body "{\"error\":\"transient\"}"}
          (json-response {"access_token" "fresh" "expires_in" 600})))
      (fn [port]
        (let [src (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                             :base-url (local port)}))]
          (is (= "revoked" (token/token! src)))
          (token/invalidate! src "revoked")
          (is (= :tools.agents.anthropic.error/token-exchange (err-type #(token/token! src))))
          (is (= 1 @posts))
          (is (= "fresh" (token/token! src)) "force still armed after the failed attempt")
          (is (= 2 @posts)))))))

(deftest token-endpoint-401-is-retried-once-then-throws-redacted
  (let [dir   (temp-dir "cfg")
        posts (atom 0)]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" {"access_token" access-secret "expires_at" 1 "refresh_token" refresh-secret})
    (with-server
      (fn [_] (swap! posts inc)
        {:status 401 :headers {"request-id" "req_rt"}
         :body (a/write-json {"error" "invalid_grant" "error_description" (str "bad " refresh-secret)
                              "echo" refresh-secret})})
      (fn [port]
        (let [src (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                             :base-url (local port)}))
              e   (thrown #(token/token! src))]
          (is (= 2 @posts))
          (is (= {:type :tools.agents.anthropic.error/token-exchange :status 401 :request-id "req_rt"
                  :body {"error" "invalid_grant" "error_description" "bad [REDACTED]"}}
                 (ex-data e)))
          (is (not (leaks? e refresh-secret access-secret))))))))

;; ---------------------------------------------------------------------------
;; Atomic write-back failure modes
;; ---------------------------------------------------------------------------

(deftest failed-write-back-throws-and-leaves-the-file-intact
  (let [dir      (temp-dir "cfg")
        creds-d  (file dir "credentials")
        original {"access_token" access-secret "expires_at" 1 "refresh_token" refresh-secret}]
    (write-config! dir "p" (oauth-config))
    (write-creds! dir "p" original)
    (let [bytes-before (slurp (file creds-d "p.json"))]
      (with-server
        (fn [_] (json-response {"access_token" "at-lost" "expires_in" 600 "refresh_token" "rt-lost"}))
        (fn [port]
          (chmod! creds-d "r-x------")
          (try
            (let [src (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                                 :base-url (local port)}))
                  e   (thrown #(token/token! src))]
              (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))))
              (is (re-find #"could not be written back" (ex-message e)))
              (is (not (leaks? e refresh-secret access-secret "at-lost" "rt-lost"))))
            (finally (chmod! creds-d "rwx------")))
          (is (= bytes-before (slurp (file creds-d "p.json"))) "original bytes untouched")
          (is (= #{"p.json"} (dir-listing creds-d)) "no temp residue"))))))

(deftest malformed-refresh-responses-write-nothing
  (doseq [[resp re] [[{:status 200 :body (str "<html>" refresh-secret "</html>")} #"non-JSON"]
                     [{:status 200 :body "[\"x\"]"} #"JSON list"]
                     [(json-response {"expires_in" 60}) #"missing 'access_token'"]
                     [(json-response {"access_token" "at" "expires_in" "soon"}) #"invalid 'expires_in'"]
                     [{:status 500 :body (str "boom " refresh-secret)} #"HTTP 500"]]]
    (let [dir (temp-dir "cfg")]
      (write-config! dir "p" (oauth-config))
      (write-creds! dir "p" {"access_token" access-secret "expires_at" 1 "refresh_token" refresh-secret})
      (let [before (slurp (file dir "credentials" "p.json"))]
        (with-server
          (constantly resp)
          (fn [port]
            (let [src (:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                                 :base-url (local port)}))
                  e   (thrown #(token/token! src))]
              (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))) (str re))
              (is (re-find re (ex-message e)) (ex-message e))
              (is (not (leaks? e refresh-secret access-secret)) (str re)))))
        (is (= before (slurp (file dir "credentials" "p.json"))))
        (is (= #{"p.json"} (dir-listing (file dir "credentials"))))))))

;; ---------------------------------------------------------------------------
;; Credentials file safety and malformed content (lazy: first token)
;; ---------------------------------------------------------------------------

(deftest credentials-file-errors-surface-on-first-token-without-secrets
  (let [dir (temp-dir "cfg")
        src #(:credential-source (creds/profile-source {:profile "p" :config-dir dir :getenv (env-fn {})
                                                        :http-fn (fn [_] (throw (ex-info "no POST" {})))}))]
    (write-config! dir "p" (oauth-config))
    (testing "missing file: construction succeeds, first token throws"
      (let [s (src)]
        (is (re-find #"Credentials file not found" (ex-message (thrown #(token/token! s)))))))
    (testing "group/other readable file is refused"
      (write-creds! dir "p" {"access_token" access-secret "expires_at" 1 "refresh_token" refresh-secret} "rw-r--r--")
      (let [e (thrown #(token/token! (src)))]
        (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))))
        (is (re-find #"mode 0o644" (ex-message e)))
        (is (not (leaks? e access-secret refresh-secret)))))
    (testing "a symlink is refused"
      (let [real (file dir "real.json")
            link (file dir "credentials" "p.json")]
        (spit real (a/write-json {"access_token" access-secret}))
        (chmod! real "rw-------")
        (.delete link)
        (Files/createSymbolicLink (.toPath link) (.toPath real) (make-array FileAttribute 0))
        (is (re-find #"is a symlink" (ex-message (thrown #(token/token! (src))))))
        (.delete link)))
    (doseq [[content re] [[(str "{\"access_token\": \"" access-secret "\", ") #"is not valid JSON"]
                          [(str "\"" access-secret "\"") #"must contain a JSON object, not str"]
                          [(a/write-json {"type" "private_key" "access_token" access-secret}) #"has type \"private_key\""]
                          [(a/write-json {"expires_at" 1 "refresh_token" refresh-secret}) #"missing 'access_token'"]
                          [(a/write-json {"access_token" access-secret "expires_at" "2026-01-01T00:00:00Z"
                                          "refresh_token" refresh-secret})
                           #"invalid 'expires_at'"]]]
      (write-creds! dir "p" content)
      (let [e (thrown #(token/token! (src)))]
        (is (= :tools.agents.anthropic.error/profile (:type (ex-data e))) (str re))
        (is (re-find re (ex-message e)) (ex-message e))
        (is (not (leaks? e access-secret refresh-secret)) (str re))))
    (testing "client_id without refresh_token is a token-exchange error"
      (write-creds! dir "p" {"access_token" access-secret "expires_at" 1})
      (let [e (thrown #(token/token! (src)))]
        (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
        (is (re-find #"must include 'refresh_token'" (ex-message e)))
        (is (not (leaks? e access-secret)))))))

(deftest credentials-path-override-expands-home
  (let [home (temp-dir "home")
        dir  (temp-dir "cfg")]
    (write-config! dir "p" {"authentication" {"type" "user_oauth" "credentials_path" "~/secrets/anthropic.json"}})
    (.mkdirs (file home "secrets"))
    (spit (file home "secrets" "anthropic.json") (a/write-json {"access_token" "from-override"}))
    (chmod! (file home "secrets" "anthropic.json") "rw-------")
    (is (= "from-override"
           (token/token! (:credential-source (creds/profile-source {:profile "p" :config-dir dir :home home
                                                                    :getenv (env-fn {})})))))))

;; ---------------------------------------------------------------------------
;; oidc_federation profile (WIF in a profile) with its disk cache
;; ---------------------------------------------------------------------------

(deftest oidc-federation-profile-exchanges-and-caches-on-disk
  (let [dir      (temp-dir "cfg")
        jwt-file (file dir "jwt")
        reqs     (atom [])
        now      (atom (* 1000 2000000000))]
    (spit jwt-file "  subject-jwt \n")
    (write-config! dir "fed" {"authentication" {"type" "oidc_federation" "federation_rule_id" "fdrl_1"
                                                "service_account_id" "svac_1"
                                                "identity_token" {"source" "file" "path" (str jwt-file)}}
                              "organization_id" "org-1" "workspace_id" "wrkspc_1"})
    (with-server
      (fn [req] (swap! reqs conj req)
        (json-response {"access_token" (str "fed-" (count @reqs)) "expires_in" 600 "token_type" "Bearer"}))
      (fn [port]
        (let [mk (fn [] (:credential-source (creds/profile-source {:profile "fed" :config-dir dir :getenv (env-fn {})
                                                                   :base-url (local port) :now-ms #(deref now)})))]
          (is (= "fed-1" (token/token! (mk))))
          (let [req (first @reqs)]
            (is (= "oauth-2025-04-20,oidc-federation-2026-04-01" (get (:headers req) "anthropic-beta")))
            (is (= {"grant_type" "urn:ietf:params:oauth:grant-type:jwt-bearer" "assertion" "subject-jwt"
                    "federation_rule_id" "fdrl_1" "organization_id" "org-1"
                    "service_account_id" "svac_1" "workspace_id" "wrkspc_1"}
                   (a/read-json (:body req)))))
          (is (= {"version" "1.0" "type" "oauth_token" "access_token" "fed-1" "expires_at" (+ 2000000000 600)}
                 (read-creds dir "fed")))
          (is (= "rw-------" (perms (file dir "credentials" "fed.json"))))
          (testing "a new source (another process) reuses the disk token"
            (is (= "fed-1" (token/token! (mk))))
            (is (= 1 (count @reqs))))
          (testing "inside the 30 s disk margin a new source re-exchanges"
            (reset! now (* 1000 (- (+ 2000000000 600) 29)))
            (is (= "fed-2" (token/token! (mk))))
            (is (= "fed-2" (get (read-creds dir "fed") "access_token"))))
          (testing "401 on the API forces past the disk cache"
            (let [s (mk)]
              (is (= "fed-2" (token/token! s)))
              (reset! now (* 1000 2000000000))
              (spit (file dir "credentials" "fed.json") (a/write-json {"access_token" "fed-2" "expires_at" 2100000000}))
              (token/invalidate! s "fed-2")
              (is (= "fed-3" (token/token! s))))))))))

(deftest oidc-federation-write-back-is-best-effort
  (let [dir      (temp-dir "cfg")
        jwt-file (file dir "jwt")]
    (spit jwt-file "jwt")
    (write-config! dir "fed" {"authentication" {"type" "oidc_federation" "federation_rule_id" "r"}
                              "organization_id" "o"})
    (.mkdirs (file dir "credentials"))
    (chmod! (file dir "credentials") "r-x------")
    (try
      (with-server
        (fn [_] (json-response {"access_token" "fed-ok" "expires_in" 600}))
        (fn [port]
          (is (= "fed-ok" (token/token! (:credential-source
                                         (creds/profile-source {:profile "fed" :config-dir dir
                                                                :getenv (env-fn {"ANTHROPIC_IDENTITY_TOKEN_FILE" (str jwt-file)})
                                                                :base-url (local port)}))))
              "identity token from ANTHROPIC_IDENTITY_TOKEN_FILE; unwritable cache ignored")))
      (finally (chmod! (file dir "credentials") "rwx------")))
    (is (empty? (dir-listing (file dir "credentials"))))))

(deftest oidc-federation-profile-config-errors
  (let [dir (temp-dir "cfg")
        tok (fn [config env]
              (write-config! dir "fed" config)
              (thrown #(token/token! (:credential-source (creds/profile-source {:profile "fed" :config-dir dir
                                                                                :getenv (env-fn env)})))))]
    (let [e (tok {"authentication" {"type" "oidc_federation"} "organization_id" "o"} {})]
      (is (= :tools.agents.anthropic.error/token-exchange (:type (ex-data e))))
      (is (re-find #"federation_rule_id" (ex-message e))))
    (is (= "r-env" (-> (tok {"authentication" {"type" "oidc_federation"} "organization_id" "o"}
                            {"ANTHROPIC_FEDERATION_RULE_ID" "r-env"})
                       ex-message
                       (#(when (re-find #"No identity token file path" %) "r-env"))))
        "rule id is env-filled; then the missing identity token path is reported")
    (is (re-find #"source \"url\" is not supported"
                 (ex-message (tok {"authentication" {"type" "oidc_federation" "federation_rule_id" "r"
                                                     "identity_token" {"source" "url" "url" "https://x"}}
                                   "organization_id" "o"} {}))))
    (is (re-find #"requires a non-empty path"
                 (ex-message (tok {"authentication" {"type" "oidc_federation" "federation_rule_id" "r"
                                                     "identity_token" {"source" "file" "path" ""}}
                                   "organization_id" "o"}
                                  {"ANTHROPIC_IDENTITY_TOKEN_FILE" "/ignored"}))))))
