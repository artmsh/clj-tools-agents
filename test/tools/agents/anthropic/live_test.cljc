(ns tools.agents.anthropic.live-test
  "Mock-server / transport-level coverage — reproduces test/anthropic_test.zig's
   original mock-server matrix from the hardcoded Zig builtin this library
   replaces. `start-server!` is the ONLY runtime-specific piece (three tiny
   leaves, same pattern as tools.agents.anthropic's own `http-post!`): clz uses
   clojure.http/server+handle+serve (see src/clz/clojure/http.clj's `serve`),
   Babashka uses org.httpkit.server (verified empirically — com.sun.net.
   httpserver.HttpServer is NOT resolvable under bb's native image), and JVM
   Clojure uses com.sun.net.httpserver.HttpServer (built into the JDK, zero
   deps). Every test above `start-server!` exercises identical handler logic
   and identical assertions on all three runtimes."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [examples.anthropic.basic-chat :as ex-basic]
            [examples.anthropic.eval-harness :as ex-eval]
            [examples.anthropic.custom-gateway :as ex-gateway]
            [examples.anthropic.tool-use :as ex-tool]
            [examples.anthropic.ptc-demo :as ex-ptc]
            #?@(:bb  [[org.httpkit.server :as hk]]
                :clj []
                :clz [])))

;; ---------------------------------------------------------------------------
;; Mock-server leaf
;; ---------------------------------------------------------------------------

(defn- start-server!
  "handler: (fn [{:keys [method path headers body]}]) -> {:status :headers :body}.
   headers (in and out) are string-keyed, lower-case names. Returns
   {:port port :stop! (fn [])}."
  [port handler]
  #?(:clz
     ;; Every current test issues exactly one request per server instance, so
     ;; a single `serve-once` (proven to compose correctly with a synchronous
     ;; client call: the socket is already bound+listening before the future
     ;; is spawned, so the client's connect() queues in the OS backlog
     ;; regardless of thread scheduling) is used rather than a persistent
     ;; `serve` loop — closing a listening socket does not reliably unblock
     ;; an in-progress accept() on this platform, which made a `serve`+`stop`
     ;; pattern hang the whole test process at exit (FuturePool never drains).
     (let [srv (clojure.http/server port)
           route (fn [req] (handler {:method (:method req) :path (:path req)
                                      :headers (:headers req) :body (:body req)}))]
       (clojure.http/handle srv "POST" "/v1/messages" route)
       (clojure.http/handle srv "POST" "/v1/messages/count_tokens" route)
       (clojure.http/handle-default srv (fn [_] {:status 404 :body "not found"}))
       (future (clojure.http/serve-once srv))
       {:port port :stop! (fn [] nil)})

     :bb
     (let [server (hk/run-server
                    (fn [req]
                      (let [body (when (:body req) (slurp (:body req)))]
                        (handler {:method (str/upper-case (name (:request-method req))) :path (:uri req)
                                  :headers (:headers req) :body (or body "")})))
                    {:port port :legacy-return-value? false})]
       {:port port :stop! (fn [] (hk/server-stop! server))})

     :clj
     (let [server (com.sun.net.httpserver.HttpServer/create
                    (java.net.InetSocketAddress. "127.0.0.1" port) 0)]
       (.createContext server "/v1/messages"
         (reify com.sun.net.httpserver.HttpHandler
           (handle [_ exchange]
             (let [body    (String. (.readAllBytes (.getRequestBody exchange)) "UTF-8")
                   headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])
                                         (.getRequestHeaders exchange)))
                   req     {:method (.getRequestMethod exchange)
                             :path (.getPath (.getRequestURI exchange))
                             :headers headers :body body}
                   {:keys [status headers body]} (handler req)
                   resp-bytes (.getBytes (or body "") "UTF-8")]
               (doseq [[k v] headers] (.set (.getResponseHeaders exchange) k v))
               (.sendResponseHeaders exchange status (count resp-bytes))
               (with-open [os (.getResponseBody exchange)] (.write os resp-bytes))))))
       (.setExecutor server nil)
       (.start server)
       {:port port :stop! (fn [] (.stop server 0))})))

(defn- canned-response []
  "{\"id\":\"msg_1\",\"content\":[{\"type\":\"text\",\"text\":\"hello back\"}],\"usage\":{\"input_tokens\":3,\"output_tokens\":2}}")

;; Portability flag, NOT a bare #?(:bb ... :clj ...) reader conditional with
;; no :clz clause: does THIS runtime's start-server! survive more than one
;; request? clz's mock server is deliberately serve-once (see the ns
;; docstring above). A `(when multi-request-mock-server? (deftest ...))`
;; wrapper around a single shared test body — rather than duplicating the
;; whole deftest verbatim across separate :bb/:clj reader-conditional
;; branches — removes ~90 lines of copy-paste-able duplication (three tests
;; below used to each carry two byte-for-byte-identical bodies) while still
;; correctly skipping on clz: verified empirically that `(when false
;; (deftest ...))` never defines the var there at all, so clz's test runner
;; never sees it, same effect as an explicit `:clz nil` branch would have —
;; and unlike an absent-:clz reader-conditional branch, this doesn't depend
;; on clz's `#?()` elision semantics at all (which, as documented above,
;; falls back to matching :clj instead of eliding when :clz is absent).
(def multi-request-mock-server? #?(:clz false :bb true :clj true))

;; ---------------------------------------------------------------------------
;; Request formation: method/path, required headers, body content
;; ---------------------------------------------------------------------------

(deftest posts-to-v1-messages-with-required-headers
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18930
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "claude-3-5-haiku-latest" "max_tokens" 10
                                               "messages" [{"role" "user" "content" "hi"}]})]
        (is (= "hello back" (a/output-text resp)))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/messages" (:path req)))
          (is (= "test-key" (get (:headers req) "x-api-key")))
          (is (= "2023-06-01" (get (:headers req) "anthropic-version")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"max_tokens\":10"))
          (is (str/includes? (:body req) "\"messages\""))
          ;; credentials live on the client, not the request map — they must
          ;; never appear in the outbound JSON body.
          (is (not (str/includes? (:body req) "test-key")))
          (is (not (str/includes? (:body req) "api-key")))))
      (finally (stop!)))))

(deftest auth-token-sends-bearer-and-oauth-beta-header
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18931
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:auth-token "tok-abc" :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (let [req @captured]
          (is (= "Bearer tok-abc" (get (:headers req) "authorization")))
          (is (= "oauth-2025-04-20" (get (:headers req) "anthropic-beta")))
          (is (nil? (get (:headers req) "x-api-key")))))
      (finally (stop!)))))

(deftest api-key-client-with-betas-sends-comma-joined-beta-header
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18972
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "k" :betas ["advanced-tool-use-2025-11-20"]
                               :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "advanced-tool-use-2025-11-20" (get (:headers @captured) "anthropic-beta"))))
      (finally (stop!)))))

(deftest auth-token-client-with-betas-combines-oauth-and-requested-flags
  ;; Regression guard: a naive (merge oauth-headers {"anthropic-beta" ...})
  ;; would drop oauth-2025-04-20 the moment :betas was also set, silently
  ;; breaking OAuth auth — see beta-header-value's docstring.
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18973
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:auth-token "tok-abc" :betas ["advanced-tool-use-2025-11-20"]
                               :base-url (str "http://127.0.0.1:" port)})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "oauth-2025-04-20,advanced-tool-use-2025-11-20"
               (get (:headers @captured) "anthropic-beta"))))
      (finally (stop!)))))

(deftest base-url-with-trailing-slash-still-builds-correct-path
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18932
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port "/")})]
        (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
        (is (= "/v1/messages" (:path @captured))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Successful decode
;; ---------------------------------------------------------------------------

(deftest decodes-nested-response-into-maps-and-vectors
  (let [{:keys [port stop!]} (start-server! 18933
                                (fn [_] {:status 200
                                         :body "{\"id\":\"msg_2\",\"model\":\"claude\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1},\"nested\":{\"a\":[1,2,3]}}"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})]
        (is (= "msg_2" (get resp "id")))
        (is (vector? (get resp "content")))
        (is (= [1 2 3] (get-in resp ["nested" "a"])))
        (is (= 1 (get-in resp ["usage" "input_tokens"])))
        (is (integer? (get-in resp ["usage" "input_tokens"]))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Error paths
;; ---------------------------------------------------------------------------

(deftest non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 18934
                                (fn [_] {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"}))]
    (try
      ;; :max-retries 0: this test's mock server is single-shot on clz (see
      ;; start-server!'s ns docstring) and 429 is retryable by default — this
      ;; test is about the error SHAPE on a non-2xx response, not retries
      ;; (which get their own dedicated coverage below / in the pure-logic
      ;; suite), so pin one attempt only.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 slow down"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.anthropic/messages-create: ")))
      (finally (stop!)))))

(deftest non-2xx-without-json-error-shape-falls-back-to-raw-body
  (let [{:keys [port stop!]} (start-server! 18935
                                (fn [_] {:status 500 :body "internal explosion, not json"}))]
    (try
      ;; :max-retries 0 — see the comment on non-2xx-throws-with-status-and-
      ;; extracted-message above: 500 is retryable by default and this
      ;; server is single-shot on clz.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/internal-server (:type (ex-data e))))
        (is (= 500 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "internal explosion, not json")))
      (finally (stop!)))))

(deftest malformed-json-response-throws-catchable-parse-error
  (let [{:keys [port stop!]} (start-server! 18936 (fn [_] {:status 200 :body "{not valid json"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/json-parse (:type (ex-data e)))))
      (finally (stop!)))))

(deftest content-less-response-makes-output-text-throw
  (let [{:keys [port stop!]} (start-server! 18937
                                (fn [_] {:status 200 :body "{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\"}]}"}))]
    (try
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
            e (try (a/output-text resp) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/no-text-content (:type (ex-data e)))))
      (finally (stop!)))))

(deftest missing-api-key-throws-before-any-network-activity
  ;; No server started at this port at all — if a network call were ever
  ;; attempted, this would hang/error with a connection failure instead of
  ;; the expected missing-credentials error.
  (let [e (try (a/client {:base-url "http://127.0.0.1:18999"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/missing-credentials (:type (ex-data e))))))

;; clz-only: this used to assert a REFUSAL. clz's clojure.http/request once
;; inferred TLS from `port == 443` alone, so an https:// base-url on any other
;; port would have gone out in plaintext with the credentials in it, and this
;; leaf refused rather than let that happen. clz v44 takes an explicit tls?
;; flag, so the scheme decides and the refusal is gone — what must now be true
;; is that the call reaches the network and fails there. No server is started
;; at this port, so a connect() failure is the whole expected outcome; anything
;; still throwing :tls-port-unsupported would mean a dead guard survived.
#?(:clz
   (deftest https-on-non-443-port-now-attempts-tls-instead-of-refusing
     (let [client (a/client {:api-key "should-never-leave-process"
                              :base-url "https://127.0.0.1:18999"})
           e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                  nil (catch Exception e e))]
       (is (some? e))
       (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e)))))))

;; ---------------------------------------------------------------------------
;; count-tokens — same request/response/error plumbing as messages-create,
;; against a different path.
;; ---------------------------------------------------------------------------

(deftest count-tokens-posts-to-count-tokens-path-and-decodes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18941
                                (fn [req] (reset! captured req) {:status 200 :body "{\"input_tokens\":42}"}))]
    (try
      (let [client   (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port)})
            resp     (a/count-tokens client {"model" "m" "messages" [{"role" "user" "content" "hi"}]})]
        (is (= 42 (get resp "input_tokens")))
        (is (= "/v1/messages/count_tokens" (:path @captured)))
        (is (= "k" (get (:headers @captured) "x-api-key"))))
      (finally (stop!)))))

;; Regression guard: unlike messages-create, count-tokens had exactly one
;; test (the happy path above) -- a copy-paste slip dropping its
;; :max-retries pass-through, or breaking its decode-or-throw!/post-json!
;; error wiring, would have passed the suite clean. Mirrors messages-
;; create's non-2xx-throws-with-status-and-extracted-message.
(deftest count-tokens-non-2xx-throws-with-status-and-extracted-message
  (let [{:keys [port stop!]} (start-server! 18944
                                (fn [_] {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"}))]
    (try
      ;; :max-retries 0 -- same single-shot-mock-server reasoning as
      ;; messages-create's identically-named test above.
      (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 0})
            e (try (a/count-tokens client {"model" "m" "messages" []})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 slow down"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.anthropic/count-tokens: ")))
      (finally (stop!)))))

;; Same serve-once limitation as messages-create's retries-then-succeeds
;; test below -- see multi-request-mock-server?'s definition above.
(when multi-request-mock-server?
  (deftest count-tokens-respects-client-max-retries-then-succeeds
    (let [attempts (atom 0)
          {:keys [port stop!]} (start-server! 18945
                                  (fn [_]
                                    (swap! attempts inc)
                                    (if (< @attempts 3)
                                      {:status 429 :body "{\"error\":{\"message\":\"slow down\"}}"}
                                      {:status 200 :body "{\"input_tokens\":42}"})))]
      (try
        (binding [a/*sleep-fn* (fn [_] nil)]
          (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 5})
                resp   (a/count-tokens client {"model" "m" "messages" []})]
            (is (= 42 (get resp "input_tokens")))
            (is (= 3 @attempts))))
        (finally (stop!))))))

;; ---------------------------------------------------------------------------
;; Retries end-to-end — needs a mock server that survives MULTIPLE requests,
;; which start-server! only provides on Babashka/JVM Clojure: clz's server is
;; deliberately serve-once (see start-server!'s ns docstring above), so a
;; real second request from a retry has nothing listening to answer it. The
;; retry LOOP itself (count/backoff/Retry-After selection) already has full
;; zero-I/O coverage in tools.agents.anthropic-test; this only proves the real
;; wiring — client -> messages-create -> request-with-retries! -> a second
;; real HTTP round trip — actually fires.
;;
;; NOTE: clz's own #?() reader falls back to matching :clj when no :clz
;; clause is present in the form (verified empirically against clz v43 — an
;; unlisted-tag form is NOT elided the way it is on JVM Clojure/Babashka) —
;; see multi-request-mock-server?'s definition above for why this uses a
;; plain `when` guard instead of a #?() reader conditional.
(when multi-request-mock-server?
  (deftest retries-then-succeeds-against-real-mock-server
    (let [attempts (atom 0)
          {:keys [port stop!]} (start-server! 18942
                                  (fn [_]
                                    (swap! attempts inc)
                                    (if (< @attempts 3)
                                      {:status 429 :body "{\"error\":{\"message\":\"slow down\"}}"}
                                      {:status 200 :body (canned-response)})))]
      (try
        (binding [a/*sleep-fn* (fn [_] nil)]
          (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 5})
                resp   (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})]
            (is (= "hello back" (a/output-text resp)))
            (is (= 3 @attempts))))
        (finally (stop!))))))

;; Regression guard for the "no test covers a real HTTP error after retries
;; are actually exhausted" coverage gap: every other non-2xx test above pins
;; :max-retries 0 (to dodge clz's single-shot mock server), and the
;; pure-logic retry-exhaustion tests in anthropic_test.cljc use a fake
;; attempt-fn whose ex-data never includes :body — so nothing anywhere
;; exercised "a real HTTP response, retried, then exhausted" together. This
;; is itself bb/clj only (needs a mock server that survives multiple real
;; requests), same reasoning as the test above.
(when multi-request-mock-server?
  (deftest non-2xx-after-retries-exhausted-still-carries-status-and-body
    (let [attempts (atom 0)
          {:keys [port stop!]} (start-server! 18946
                                  (fn [_]
                                    (swap! attempts inc)
                                    {:status 429 :body "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"still slow\"}}"}))]
      (try
        (binding [a/*sleep-fn* (fn [_] nil)]
          (let [client (a/client {:api-key "k" :base-url (str "http://127.0.0.1:" port) :max-retries 2})
                e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" []})
                       nil (catch Exception e e))]
            (is (some? e))
            (is (= 3 @attempts)) ;; 1 initial + 2 retries, all exhausted
            (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e))))
            (is (= 429 (:status (ex-data e))))
            (is (str/includes? (str (:body (ex-data e))) "still slow"))
            (is (str/includes? (str (ex-message e)) "HTTP 429 still slow"))))
        (finally (stop!))))))

;; ---------------------------------------------------------------------------
;; examples/*.clj wired end-to-end against the mock server
;; ---------------------------------------------------------------------------

(deftest example-a-basic-chat-runs-against-mock-server
  (let [{:keys [port stop!]} (start-server! 18938
                                (fn [_] {:status 200 :body (canned-response)}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})]
        (is (= "hello back" (ex-basic/run-example client))))
      (finally (stop!)))))

(deftest example-a-streaming-path-is-rejected-cleanly
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})]
    (is (= {:caught true :type :tools.agents.anthropic.error/streaming-unsupported}
           (ex-basic/run-streaming-example client)))))

(deftest example-b-eval-harness-prefill-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18939
                                (fn [req] (reset! captured req)
                                  {:status 200
                                   :body "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1,\\\"b\\\":2}\"}]}"}))]
    (try
      (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
            result (ex-eval/run-example client)]
        (is (= "{\"a\":1,\"b\":2}" result))
        ;; prefill: the assistant turn we seeded must be in the outbound request
        (is (str/includes? (:body @captured) "```json"))
        (is (str/includes? (:body @captured) "\"stop_sequences\":[\"```\"]"))
        (is (str/includes? (:body @captured) "\"thinking\":{\"type\":\"disabled\"}")))
      (finally (stop!)))))

(deftest example-c-custom-gateway-auth-token-runs-against-mock-server
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18940
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"content\":[{\"type\":\"text\",\"text\":\"{}\"}]}"}))]
    (try
      (let [client (a/client {:auth-token "gw-token" :base-url (str "http://127.0.0.1:" port)})
            result (ex-gateway/run-example client)]
        (is (= "{}" result))
        (is (= "Bearer gw-token" (get (:headers @captured) "authorization")))
        (is (str/includes? (:body @captured) "claude-sonnet-4-5-20250929")))
      (finally (stop!)))))

;; Two sequential requests (tool_use round, then final text round) needs a
;; mock server that survives multiple requests — same serve-once limitation
;; as the retries end-to-end test above (see multi-request-mock-server?).
(when multi-request-mock-server?
  (deftest example-d-tool-use-runs-against-mock-server
    (let [requests (atom [])
          {:keys [port stop!]} (start-server! 18943
                                  (fn [req]
                                    (swap! requests conj req)
                                    (if (= 1 (count @requests))
                                      {:status 200
                                       :body (str "{\"stop_reason\":\"tool_use\",\"content\":["
                                                   "{\"type\":\"tool_use\",\"id\":\"toolu_1\","
                                                   "\"name\":\"get_weather\",\"input\":{\"location\":\"San Francisco\"}}]}")}
                                      {:status 200
                                       :body "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\"It's 72F and sunny.\"}]}"})))]
      (try
        (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
              result (ex-tool/run-example client)]
          (is (= "It's 72F and sunny." result))
          (is (= 2 (count @requests)))
          ;; 2nd request must carry the tool_result keyed to the 1st response's tool_use id
          (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_1\""))
          (is (str/includes? (:body (second @requests)) "72F and sunny in San Francisco")))
        (finally (stop!))))))

;; ---------------------------------------------------------------------------
;; examples/ptc_demo.clj — Programmatic Tool Calling, 3-turn mock sequence:
;; turn 1 (direct-caller code_execution + a code-execution-caller tool_use),
;; turn 2 (two more code-execution-caller tool_use calls, no "container" key
;; this time — must carry over from turn 1's response), turn 3 (end_turn).
;; Same multi-request-mock-server? gating as example-d above.
;; ---------------------------------------------------------------------------

(when multi-request-mock-server?
  (deftest example-e-ptc-runs-against-mock-server-with-container-and-caller-routing
    (let [requests (atom [])
          turn-1 (str "{\"stop_reason\":\"tool_use\","
                      "\"container\":{\"id\":\"cntr_abc123\",\"expires_at\":\"2024-01-01T00:05:00Z\"},"
                      "\"usage\":{\"input_tokens\":500,\"output_tokens\":50},"
                      "\"content\":[{\"type\":\"text\",\"text\":\"Writing code to analyze this.\"},"
                      "{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"code_execution\","
                      "\"caller\":{\"type\":\"direct\"},\"input\":{\"code\":\"...\"}},"
                      "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_team_members\","
                      "\"caller\":{\"type\":\"direct\"},\"input\":{\"department\":\"engineering\"}}]}")
          turn-2 (str "{\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":300,\"output_tokens\":40},"
                      "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"get_expenses\","
                      "\"caller\":{\"type\":\"code_execution_20250825\"},"
                      "\"input\":{\"employee_id\":\"ENG002\",\"quarter\":\"Q3\"}},"
                      "{\"type\":\"tool_use\",\"id\":\"toolu_3\",\"name\":\"get_custom_budget\","
                      "\"caller\":{\"type\":\"code_execution_20250825\"},\"input\":{\"user_id\":\"ENG002\"}}]}")
          turn-3 (str "{\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":100,\"output_tokens\":60},"
                      "\"content\":[{\"type\":\"code_execution_tool_result\","
                      "\"content\":{\"stdout\":\"done\",\"return_code\":0}},"
                      "{\"type\":\"text\",\"text\":\"ENG002 exceeded the standard budget but is within their custom limit.\"}]}")
          {:keys [port stop!]} (start-server! 18974
                                  (fn [req]
                                    (swap! requests conj req)
                                    {:status 200
                                     :body (case (count @requests) 1 turn-1 2 turn-2 turn-3)}))]
      (try
        (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
              result (ex-ptc/run-agent-with-ptc client "irrelevant query")]
          (is (= 3 (count @requests)))
          (is (= "ENG002 exceeded the standard budget but is within their custom limit." (:text result)))
          (is (= 3 (:api-calls result)))
          (is (= 1050 (:total-tokens result))) ;; (500+50) + (300+40) + (100+60)
          (is (= "cntr_abc123" (:container-id result)))
          (is (= [{:name "get_team_members" :caller-type "direct"}
                  {:name "get_expenses" :caller-type "code_execution_20250825"}
                  {:name "get_custom_budget" :caller-type "code_execution_20250825"}]
                 (:caller-log result)))
          ;; turn 1's request must carry the PTC beta header and ptc-tools
          ;; (allowed_callers on our tools, the code_execution server tool
          ;; appended)
          (is (= "advanced-tool-use-2025-11-20" (get (:headers (first @requests)) "anthropic-beta")))
          (is (str/includes? (:body (first @requests)) "\"allowed_callers\":[\"code_execution_20250825\"]"))
          (is (str/includes? (:body (first @requests))
                              "\"type\":\"code_execution_20250825\",\"name\":\"code_execution\""))
          ;; turn 2's request must carry the container id from turn 1's
          ;; response, and toolu_1's real tool_result — dispatched to the
          ;; ACTUAL mock expense API (examples.anthropic.ptc-expense-api), not a stub,
          ;; proving run-tool/local-tool-names routing actually reaches it
          (is (str/includes? (:body (second @requests)) "\"container\":\"cntr_abc123\""))
          (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_1\""))
          (is (str/includes? (:body (second @requests)) "ENG001"))
          ;; turn 3's request must carry BOTH turn-2 tool_results in a
          ;; SINGLE user turn (add-tool-results' contract)
          (is (str/includes? (:body (nth @requests 2)) "\"tool_use_id\":\"toolu_2\""))
          (is (str/includes? (:body (nth @requests 2)) "ENG002_Q3_00"))
          (is (str/includes? (:body (nth @requests 2)) "\"tool_use_id\":\"toolu_3\""))
          (is (str/includes? (:body (nth @requests 2)) "ENG002")))
        (finally (stop!))))))

(when multi-request-mock-server?
  (deftest example-f-baseline-without-ptc-runs-against-mock-server
    (let [requests (atom [])
          {:keys [port stop!]} (start-server! 18975
                                  (fn [req]
                                    (swap! requests conj req)
                                    (if (= 1 (count @requests))
                                      {:status 200
                                       :body (str "{\"stop_reason\":\"tool_use\","
                                                   "\"usage\":{\"input_tokens\":10,\"output_tokens\":5},"
                                                   "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_9\","
                                                   "\"name\":\"get_team_members\","
                                                   "\"input\":{\"department\":\"engineering\"}}]}")}
                                      {:status 200
                                       :body (str "{\"stop_reason\":\"end_turn\","
                                                   "\"usage\":{\"input_tokens\":8,\"output_tokens\":4},"
                                                   "\"content\":[{\"type\":\"text\",\"text\":\"Nobody exceeded budget.\"}]}")})))]
      (try
        (let [client (a/client {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)})
              result (ex-ptc/run-agent-without-ptc client "irrelevant query")]
          (is (= 2 (count @requests)))
          (is (= "Nobody exceeded budget." (:text result)))
          (is (= 2 (:api-calls result)))
          (is (= 27 (:total-tokens result)))
          (is (str/includes? (:body (second @requests)) "\"tool_use_id\":\"toolu_9\""))
          (is (str/includes? (:body (second @requests)) "ENG001")))
        (finally (stop!))))))
