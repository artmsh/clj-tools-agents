(ns tools.agents.gemini.live-test
  "Mock-server / transport-level coverage. `start-server!` is the ONLY
   runtime-specific piece (three tiny leaves, same pattern as
   tools.agents.gemini's own `http-post!`, tools.agents.anthropic/
   tools.agents.openai's own live-test namespaces): clz uses clojure.http/
   server+handle+serve-once, Babashka uses org.httpkit.server (com.sun.net.
   httpserver.HttpServer is NOT resolvable under bb's native image), and JVM
   Clojure uses com.sun.net.httpserver.HttpServer (built into the JDK, zero
   deps). Every test above `start-server!` exercises identical handler logic
   and identical assertions on all three runtimes.

   Every retry test binds `g/*sleep-fn*` to a no-op — the real backoff floor
   is 1 SECOND (python-genai's tenacity initial=1.0), unlike tools.agents.
   openai's 500ms-honoring-retry-after-ms-of-1 trick, since this library does
   not honor any Retry-After-equivalent header (see gemini.cljc's ns
   docstring)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.gemini :as g]
            #?@(:bb  [[org.httpkit.server :as hk]]
                :clj []
                :clz [])))

;; ---------------------------------------------------------------------------
;; Mock-server leaf
;; ---------------------------------------------------------------------------

(defn- start-server!
  "route: the exact path the mock will answer on, e.g.
   \"/v1beta/models/gemini-2.5-flash:generateContent\". expected-requests: how
   many requests the test will issue against this server (retry tests issue
   several). handler: (fn [{:keys [method path headers body]}]) -> {:status
   :headers :body}. Returns {:port port :stop! (fn [])}."
  ([port route handler] (start-server! port route 1 handler))
  ([port route expected-requests handler]
  #?(:clz
     (let [srv (clojure.http/server port)]
       (clojure.http/handle srv "POST" route
         (fn [req] (handler {:method (:method req) :path (:path req)
                              :headers (:headers req) :body (:body req)})))
       (clojure.http/handle-default srv (fn [_] {:status 404 :body "not found"}))
       (future (loop [i 0]
                 (when (< i expected-requests)
                   (clojure.http/serve-once srv)
                   (recur (inc i)))))
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
       (.createContext server route
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
       {:port port :stop! (fn [] (.stop server 0))}))))

(defn- base-url [port] (str "http://127.0.0.1:" port))

(defn- canned-response []
  (str "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"hello back\"}]},"
       "\"finishReason\":\"STOP\"}],"
       "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5},"
       "\"modelVersion\":\"gemini-2.5-flash\"}"))

;; ---------------------------------------------------------------------------
;; Request formation: method/path, required headers, body content
;; ---------------------------------------------------------------------------

(deftest posts-to-models-generate-content-with-required-headers
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18980 "/v1beta/models/gemini-2.5-flash:generateContent"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (g/client {:api-key "test-key" :base-url (base-url port)})
            resp   (g/generate-content client "gemini-2.5-flash"
                     {"contents" [{"role" "user" "parts" [{"text" "hi"}]}]
                      "generationConfig" {"maxOutputTokens" 10}})]
        (is (= "hello back" (g/output-text resp)))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1beta/models/gemini-2.5-flash:generateContent" (:path req)))
          (is (= "test-key" (get (:headers req) "x-goog-api-key")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (str/includes? (:body req) "\"maxOutputTokens\":10"))
          (is (str/includes? (:body req) "\"contents\""))
          ;; credentials live on the client, not the request map — they must
          ;; never appear in the outbound JSON body.
          (is (not (str/includes? (:body req) "test-key")))))
      (finally (stop!)))))

(deftest bare-model-id-gets-models-prefix-tuned-model-id-does-not
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18981 "/v1beta/tunedModels/my-tuned-model:generateContent"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})]
        (g/generate-content client "tunedModels/my-tuned-model" {"contents" []})
        (is (= "/v1beta/tunedModels/my-tuned-model:generateContent" (:path @captured))))
      (finally (stop!)))))

(deftest base-url-with-trailing-slash-still-builds-correct-path
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18982 "/v1beta/models/gemini-2.5-flash:generateContent"
                                (fn [req] (reset! captured req) {:status 200 :body (canned-response)}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (str (base-url port) "/")})]
        (g/generate-content client "gemini-2.5-flash" {"contents" []})
        (is (= "/v1beta/models/gemini-2.5-flash:generateContent" (:path @captured))))
      (finally (stop!)))))

(deftest count-tokens-posts-to-count-tokens-path-and-decodes
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 18983 "/v1beta/models/gemini-2.5-flash:countTokens"
                                (fn [req] (reset! captured req) {:status 200 :body "{\"totalTokens\":7}"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            resp   (g/count-tokens client "gemini-2.5-flash" {"contents" [{"role" "user" "parts" [{"text" "hi"}]}]})]
        (is (= 7 (get resp "totalTokens")))
        (is (= "/v1beta/models/gemini-2.5-flash:countTokens" (:path @captured))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Successful decode
;; ---------------------------------------------------------------------------

(deftest decodes-nested-response-into-maps-and-vectors
  (let [{:keys [port stop!]} (start-server! 18984 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 200
                                         :body (str "{\"candidates\":[{\"content\":{\"parts\":"
                                                    "[{\"text\":\"hi\"}]}}],"
                                                    "\"usageMetadata\":{\"totalTokenCount\":2},"
                                                    "\"nested\":{\"a\":[1,2,3]}}")}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            resp   (g/generate-content client "m" {"contents" []})]
        (is (vector? (get resp "candidates")))
        (is (= [1 2 3] (get-in resp ["nested" "a"])))
        (is (= 2 (get-in resp ["usageMetadata" "totalTokenCount"])))
        (is (integer? (get-in resp ["usageMetadata" "totalTokenCount"]))))
      (finally (stop!)))))

(deftest safety-blocked-response-yields-nil-output-text
  (let [{:keys [port stop!]} (start-server! 18985 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 200
                                         :body (str "{\"candidates\":[{\"finishReason\":\"SAFETY\","
                                                    "\"safetyRatings\":[]}]}")}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            resp   (g/generate-content client "m" {"contents" []})]
        (is (nil? (g/output-text resp)))
        (is (= "SAFETY" (get-in (first (get resp "candidates")) ["finishReason"]))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Error paths
;; ---------------------------------------------------------------------------

(deftest non-2xx-throws-with-status-and-extracted-message-nested-error-shape
  (let [{:keys [port stop!]} (start-server! 18986 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 429
                                         :body (str "{\"error\":{\"code\":429,"
                                                    "\"message\":\"Resource exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}")}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.gemini/resource-exhausted-error (:type (ex-data e))))
        (is (= 429 (:status (ex-data e))))
        (is (= 0 (:retries-taken (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 429 Resource exhausted"))
        (is (str/starts-with? (str (ex-message e)) "tools.agents.gemini/generate-content: ")))
      (finally (stop!)))))

(deftest non-2xx-flat-error-shape-also-extracts-message
  ;; python-genai's APIError accepts a top-level {"message":...} too, not
  ;; just the nested {"error":{"message":...}} shape.
  (let [{:keys [port stop!]} (start-server! 18987 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 400 :body "{\"code\":400,\"message\":\"bad request\"}"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/invalid-argument-error (:type (ex-data e))))
        (is (str/includes? (str (ex-message e)) "HTTP 400 bad request")))
      (finally (stop!)))))

(deftest non-2xx-without-json-error-shape-falls-back-to-raw-body
  (let [{:keys [port stop!]} (start-server! 18988 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 500 :body "internal explosion, not json"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/internal-server-error (:type (ex-data e))))
        (is (= 500 (:status (ex-data e))))
        (is (str/includes? (str (ex-message e)) "internal explosion, not json")))
      (finally (stop!)))))

(deftest malformed-json-response-throws-catchable-parse-error
  (let [{:keys [port stop!]} (start-server! 18989 "/v1beta/models/m:generateContent"
                                (fn [_] {:status 200 :body "{not valid json"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/json-parse-error (:type (ex-data e)))))
      (finally (stop!)))))

(deftest connection-failure-is-typed-not-leaked
  (let [client (g/client {:api-key "k" :base-url "http://127.0.0.1:18999" :max-retries 0})
        e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))
    (is (= 0 (:retries-taken (ex-data e))))))

(deftest missing-api-key-throws-before-any-network-activity
  ;; Assumes GOOGLE_API_KEY/GEMINI_API_KEY are unset in the test environment.
  (let [e (try (g/client {:base-url "http://127.0.0.1:18999"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.gemini/missing-credentials (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Retry loop — g/*sleep-fn* bound to a no-op throughout, see ns docstring.
;; ---------------------------------------------------------------------------

(deftest retries-a-503-then-succeeds
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18990 "/v1beta/models/gemini-2.5-flash:generateContent" 3
                                (fn [_]
                                  (if (< (swap! hits inc) 3)
                                    {:status 503 :body "{\"error\":{\"message\":\"overloaded\"}}"}
                                    {:status 200 :body (canned-response)})))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})]  ;; default 4 retries
        (binding [g/*sleep-fn* (fn [_] nil)]
          (is (= "hello back" (g/output-text (g/generate-content client "gemini-2.5-flash" {"contents" []}))))
          (is (= 3 @hits))))
      (finally (stop!)))))

(deftest retries-are-exhausted-then-the-status-error-surfaces
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18991 "/v1beta/models/m:generateContent" 5
                                (fn [_] (swap! hits inc)
                                  {:status 500 :body "{\"error\":{\"message\":\"overloaded\"}}"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})]
        (binding [g/*sleep-fn* (fn [_] nil)]
          (let [e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
            (is (some? e))
            (is (= :tools.agents.gemini/internal-server-error (:type (ex-data e))))
            (is (= 4 (:retries-taken (ex-data e))))
            ;; 1 initial attempt + 4 retries
            (is (= 5 @hits)))))
      (finally (stop!)))))

(deftest non-retryable-status-is-not-retried
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18992 "/v1beta/models/m:generateContent"
                                (fn [_] (swap! hits inc)
                                  {:status 400 :body "{\"error\":{\"message\":\"bad request\"}}"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/invalid-argument-error (:type (ex-data e))))
        (is (= 0 (:retries-taken (ex-data e))))
        (is (= 1 @hits)))
      (finally (stop!)))))

(deftest max-retries-zero-disables-retrying
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18993 "/v1beta/models/m:generateContent"
                                (fn [_] (swap! hits inc) {:status 429 :body "{}"}))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (= :tools.agents.gemini/resource-exhausted-error (:type (ex-data e))))
        (is (= 1 @hits)))
      (finally (stop!)))))

(deftest count-tokens-retries-too
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 18994 "/v1beta/models/gemini-2.5-flash:countTokens" 2
                                (fn [_]
                                  (if (< (swap! hits inc) 2)
                                    {:status 429 :body "{}"}
                                    {:status 200 :body "{\"totalTokens\":9}"})))]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})]
        (binding [g/*sleep-fn* (fn [_] nil)]
          (is (= 9 (get (g/count-tokens client "gemini-2.5-flash" {"contents" []}) "totalTokens")))
          (is (= 2 @hits))))
      (finally (stop!)))))

(deftest connection-errors-are-retried-then-typed
  (let [client (g/client {:api-key "k" :base-url "http://127.0.0.1:18999" :max-retries 1})]
    (binding [g/*sleep-fn* (fn [_] nil)]
      (let [e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
        (is (= 1 (:retries-taken (ex-data e))))))))

;; clz-only: same TLS-port rationale as tools.agents.anthropic/tools.agents.
;; openai's identically-named test — clz v44 attempts real TLS on a non-443
;; port rather than refusing outright, so this now reaches (and fails) the
;; network instead of throwing a dead :tls-port-unsupported guard.
#?(:clz
   (deftest https-on-non-443-port-now-attempts-tls-instead-of-refusing
     (let [client (g/client {:api-key "should-never-leave-process"
                              :base-url "https://127.0.0.1:18999" :max-retries 0})
           e (try (g/generate-content client "m" {"contents" []}) nil (catch Exception e e))]
       (is (some? e))
       (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
       (is (str/starts-with? (str (ex-message e)) "tools.agents.gemini/generate-content: ")))))
