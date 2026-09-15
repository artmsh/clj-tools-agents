(ns tools.agents.gemini.stream-test
  "generate-content-stream end to end against the shared streaming mock
   servers (tools.agents.test-support), identical on both runtimes.

   Ports are taken from the OS (bind 0, read, release) rather than a fixed
   band, so the suite cannot collide with another suite running concurrently
   on the same host. Every retry test binds `g/*sleep-fn*` to a no-op."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.gemini :as g]
            [tools.agents.sse :as sse]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [start-server! start-abort-server! start-stall-server! rotating-token-cache]]))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort ss)))

(defn- base-url [port] (str "http://127.0.0.1:" port))

(def ^:private sse-headers {"content-type" "text/event-stream"})

(def ^:private model-path "/v1beta/models/gemini-2.5-flash:streamGenerateContent")

(defn- fixture [name] (slurp (str "test/resources/sse/" name ".sse")))

(defn- chunk-json [text & {:keys [finish usage]}]
  (str "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" text "\"}]},"
       (when finish (str "\"finishReason\":\"" finish "\","))
       "\"index\":0}]"
       (when usage (str ",\"usageMetadata\":{\"totalTokenCount\":" usage "}"))
       ",\"modelVersion\":\"gemini-2.5-flash\"}"))

(defn- sse [json] (str "data: " json "\r\n\r\n"))

(defn- until-gone!
  "Streaming-body helper: SSE comments every 10 ms until the client
   disconnects, then deliver `gone` true (false after ~5 s)."
  [send! gone]
  (loop [i 0]
    (cond
      (not (send! ": ping\r\n\r\n")) (deliver gone true)
      (> i 500)                      (deliver gone false)
      :else                          (do (Thread/sleep 10) (recur (inc i))))))

(defn- with-server [handler f]
  (let [{:keys [port stop!]} (start-server! 0 model-path handler)]
    (try (f (g/client {:api-key "test-key" :base-url (base-url port)}))
         (finally (stop!)))))

(deftest request-line-headers-and-body
  (let [captured (atom nil)]
    (with-server
      (fn [req] (reset! captured req)
        {:status 200 :headers sse-headers :body (fn [send!] (send! (sse (chunk-json "hi" :finish "STOP"))))})
      (fn [client]
        (let [s (g/generate-content-stream client "gemini-2.5-flash"
                  {"contents" [{"role" "user" "parts" [{"text" "hello"}]}]})]
          (is (= [(g/read-json (chunk-json "hi" :finish "STOP"))] (into [] s)))
          (is (= :eof (stream/outcome s)) "gemini has no terminal event: EOF is the normal end")
          (is (= 200 (:status (stream/response s)))))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= model-path (:path req)))
          (is (= "alt=sse" (:query req)))
          (is (= "test-key" (get (:headers req) "x-goog-api-key")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (= "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]}" (:body req)))
          (is (not (str/includes? (:body req) "test-key"))))))))

(deftest multi-chunk-text-accumulates-and-output-text-reads-it
  (let [release (promise)]
    (with-server
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send! (sse (chunk-json "Once " :usage 3)))
                       (deref release 5000 nil)
                       (send! (sse (chunk-json "upon " :usage 5)))
                       (send! (sse (chunk-json "a time" :finish "STOP" :usage 8))))})
      (fn [client]
        (let [s       (g/generate-content-stream client "gemini-2.5-flash" {"contents" []})
              deltas  (atom [])
              joined  (transduce (map (fn [chunk]
                                        (swap! deltas conj (g/output-text chunk))
                                        (deliver release true)
                                        chunk))
                                 g/accumulate-chunk s)]
          (is (= ["Once " "upon " "a time"] @deltas))
          (is (= "Once upon a time" (g/output-text joined)))
          (is (= (apply str @deltas) (g/output-text joined)))
          (is (= [{"text" "Once upon a time"}] (get-in joined ["candidates" 0 "content" "parts"])))
          (is (= "STOP" (get-in joined ["candidates" 0 "finishReason"])))
          (is (= {"totalTokenCount" 8} (get joined "usageMetadata")))
          (is (g/stream-complete? joined))
          (is (= :eof (stream/outcome s))))))))

(deftest accumulate-synthetic-fixture
  ;; test/resources/sse/gemini-text.sse is SYNTHETIC (hand-built; the REST
  ;; docs show only the ?alt=sse curl, no response body).
  (let [chunks (mapv (comp g/read-json :data) (sse/parse-string (fixture "gemini-text")))
        r      (g/accumulate-stream chunks)]
    (is (= 2 (count chunks)))
    (is (= {"candidates"    [{"content" {"parts" [{"text" "Once upon a time, in a town with a magic backpack..."}]
                                         "role"  "model"}
                              "finishReason" "STOP"
                              "index"        0}]
            "usageMetadata" {"promptTokenCount" 10 "candidatesTokenCount" 12 "totalTokenCount" 22}
            "modelVersion"  "gemini-2.0-flash"
            "responseId"    "resp-1"}
           r))
    (is (= (apply str (map g/output-text chunks)) (g/output-text r)))
    (is (g/stream-complete? r))
    (is (false? (g/stream-complete? (g/accumulate-stream (pop chunks)))) "no finishReason: truncated")))

(deftest accumulate-stream-on-the-synthetic-fixture-served-over-http
  (with-server
    (fn [_] {:status 200 :headers sse-headers :body (fn [send!] (send! (fixture "gemini-text")))})
    (fn [client]
      (let [r (g/accumulate-stream (g/generate-content-stream client "gemini-2.5-flash" {"contents" []}))]
        (is (= "Once upon a time, in a town with a magic backpack..." (g/output-text r)))
        (is (= 22 (get-in r ["usageMetadata" "totalTokenCount"])))
        (is (= "resp-1" (get r "responseId")))
        (is (g/stream-complete? r))))))

(deftest http-error-before-the-stream-is-typed-and-thrown-at-call-time
  (let [hits (atom 0)]
    (with-server
      (fn [_] (swap! hits inc)
        {:status 400 :body "{\"error\":{\"code\":400,\"message\":\"bad model\",\"status\":\"INVALID_ARGUMENT\"}}"})
      (fn [client]
        (let [e (try (g/generate-content-stream client "gemini-2.5-flash" {"contents" []}) nil
                     (catch Exception e e))]
          (is (= :tools.agents.gemini/invalid-argument-error (:type (ex-data e))))
          (is (= 400 (:status (ex-data e))))
          (is (str/includes? (:body (ex-data e)) "bad model"))
          (is (= 0 (:retries-taken (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.gemini/generate-content-stream: HTTP 400 bad model"))
          (is (= 1 @hits)))))))

(deftest opening-request-is-retried-per-the-gemini-policy
  (testing "503 twice, then a stream"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (if (< (swap! hits inc) 3)
                  {:status 503 :body "{\"error\":{\"message\":\"overloaded\"}}"}
                  {:status 200 :headers sse-headers :body (fn [send!] (send! (sse (chunk-json "ok" :finish "STOP"))))}))
        (fn [client]
          (binding [g/*sleep-fn* (fn [_] nil)]
            (is (= "ok" (g/output-text (g/accumulate-stream
                                         (g/generate-content-stream client "gemini-2.5-flash" {"contents" []})))))
            (is (= 3 @hits)))))))
  (testing "retries exhausted: the status error carries :retries-taken"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) {:status 429 :body "{\"error\":{\"message\":\"quota\"}}"})
        (fn [client]
          (binding [g/*sleep-fn* (fn [_] nil)]
            (let [e (try (g/generate-content-stream (assoc client :max-retries 2) "gemini-2.5-flash" {}) nil
                         (catch Exception e e))]
              (is (= :tools.agents.gemini/resource-exhausted-error (:type (ex-data e))))
              (is (= 2 (:retries-taken (ex-data e))))
              (is (= 3 @hits))))))))
  (testing "connection refused: retried, then typed"
    (let [slept (atom 0)
          client (g/client {:api-key "k" :base-url (base-url (free-port)) :max-retries 1})]
      (binding [g/*sleep-fn* (fn [_] (swap! slept inc))]
        (let [e (try (g/generate-content-stream client "m" {}) nil (catch Exception e e))]
          (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
          (is (= 1 (:retries-taken (ex-data e))))
          (is (= 1 @slept)))))))

(deftest error-chunk-mid-stream-throws-typed-after-earlier-chunks
  (let [hits (atom 0)
        gone (promise)
        err  "{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\",\"status\":\"UNAVAILABLE\"}}"]
    (with-server
      (fn [_] (swap! hits inc)
        {:status 200 :headers sse-headers
         :body (fn [send!]
                 (send! (sse (chunk-json "partial")))
                 (send! (str (sse err) (sse (chunk-json "never"))))
                 (until-gone! send! gone))})
      (fn [client]
        (binding [g/*sleep-fn* (fn [_] nil)]
          (let [s    (g/generate-content-stream client "gemini-2.5-flash" {})
                seen (atom [])
                e    (try (run! #(swap! seen conj (g/output-text %)) s) nil (catch Exception e e))]
            (is (= ["partial"] @seen) "chunks before the error are delivered, none after")
            (is (= :tools.agents.gemini/internal-server-error (:type (ex-data e))))
            (is (= 503 (:status (ex-data e))))
            (is (= "UNAVAILABLE" (get-in (ex-data e) [:error "status"])))
            (is (= "The model is overloaded." (get-in (ex-data e) [:event "error" "message"])))
            (is (= err (:body (ex-data e))))
            (is (str/includes? (ex-message e) "The model is overloaded."))
            (is (= :failed (stream/outcome s)))
            (is (true? (deref gone 5000 :timeout)) "the server observed the disconnect")
            (is (= 1 @hits) "nothing is retried once the stream has started")))))))

(deftest transport-failure-mid-stream-is-a-connection-error
  (let [{:keys [port stop!]} (start-abort-server! 0
                               {:headers sse-headers
                                :chunks  [(sse (chunk-json "cut"))]})]
    (try
      (let [client (g/client {:api-key "k" :base-url (base-url port)})
            s      (g/generate-content-stream client "gemini-2.5-flash" {})
            seen   (atom [])
            e      (try (run! #(swap! seen conj %) s) nil (catch Exception e e))]
        (is (= 1 (count @seen)))
        (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

(deftest a-stream-cut-at-a-chunk-boundary-is-eof-but-not-complete
  (with-server
    (fn [_] {:status 200 :headers sse-headers
             :body (fn [send!] (send! (sse (chunk-json "no finish" :usage 4))))})
    (fn [client]
      (let [s (g/generate-content-stream client "gemini-2.5-flash" {})
            r (g/accumulate-stream s)]
        (is (= :eof (stream/outcome s)))
        (is (= "no finish" (g/output-text r)))
        (is (false? (g/stream-complete? r)))))))

(deftest early-termination-and-close!-release-the-connection
  (testing "(into [] (take 1) s) closes; the server sees the client go away"
    (let [gone (promise)]
      (with-server
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! (sse (chunk-json (str "c" i))))) (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (g/generate-content-stream client "gemini-2.5-flash" {})]
            (is (= "c0" (g/output-text (first (into [] (take 1) s)))))
            (is (= :reduced (stream/outcome s)))
            (is (true? (deref gone 5000 :timeout))))))))
  (testing "close! on an unreduced stream"
    (let [gone (promise)]
      (with-server
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! ": ping\n\n")) (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (g/generate-content-stream client "gemini-2.5-flash" {})]
            (stream/close! s)
            (is (true? (deref gone 5000 :timeout)))
            (is (nil? (g/accumulate-stream s)) "a reduce after close! returns init")
            (is (= :cancelled (stream/outcome s)))))))))

(deftest credential-source-401-invalidates-and-retries-the-opening-request-once
  (testing "401 -> invalidate -> retry with the refreshed Bearer token, outside :max-retries"
    (let [auths (atom [])
          {:keys [source fetches]} (rotating-token-cache)
          {:keys [port stop!]} (start-server! 0 model-path
                                 (fn [req]
                                   (swap! auths conj [(get (:headers req) "authorization")
                                                      (get (:headers req) "x-goog-api-key")])
                                   (if (= 1 (count @auths))
                                     {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                                     {:status 200 :headers sse-headers
                                      :body (fn [send!] (send! (sse (chunk-json "ok" :finish "STOP"))))})))]
      (try
        (let [client (g/client {:credential-source source :base-url (base-url port) :max-retries 0})]
          (is (= "ok" (g/output-text (g/accumulate-stream
                                       (g/generate-content-stream client "gemini-2.5-flash" {"contents" []})))))
          (is (= [["Bearer tok-1" nil] ["Bearer tok-2" nil]] @auths))
          (is (= 2 @fetches)))
        (finally (stop!)))))
  (testing "a second 401 surfaces as the typed status error"
    (let [paths (atom [])
          {:keys [source]} (rotating-token-cache)
          {:keys [port stop!]} (start-server! 0 model-path
                                 (fn [req] (swap! paths conj (:path req)) {:status 401 :body "{\"error\":{\"message\":\"nope\"}}"}))]
      (try
        (let [client (g/client {:credential-source source :base-url (base-url port) :max-retries 0})
              e      (try (g/generate-content-stream client "gemini-2.5-flash" {}) nil (catch Exception e e))]
          (is (= 401 (:status (ex-data e))))
          (is (= [model-path model-path] @paths))
          (is (not (str/includes? (pr-str (ex-data e)) "tok-"))))
        (finally (stop!)))))
  (testing "a static key's 401 is not retried"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) {:status 401 :body "{\"error\":{\"message\":\"bad key\"}}"})
        (fn [client]
          (let [e (try (g/generate-content-stream client "gemini-2.5-flash" {}) nil (catch Exception e e))]
            (is (= 401 (:status (ex-data e))))
            (is (= 1 @hits))))))))

;; ---------------------------------------------------------------------------
;; Timeouts against a stalling server
;; ---------------------------------------------------------------------------

(deftest a-stalled-server-times-out-is-retried-and-typed
  (let [{:keys [port stop! accepted]} (start-stall-server! {:mode :no-headers})
        client (g/client {:api-key "k" :base-url (base-url port) :max-retries 1 :timeout-ms 200})]
    (try
      (doseq [[label f] [["create" #(g/generate-content client "m" {})]
                         ["stream" #(g/generate-content-stream client "m" {})]]]
        (reset! accepted 0)
        (let [e (binding [g/*sleep-fn* (fn [_])] (try (f) nil (catch Exception e e)))]
          (is (= :tools.agents.gemini/api-connection-error (:type (ex-data e))) label)
          (is (true? (:timeout? (ex-data e))) label)
          (is (= 2 @accepted) (str label ": one retry"))))
      (finally (stop!)))))

(deftest a-stream-is-not-cut-by-the-request-timeout
  (let [chunks (mapv #(sse (chunk-json %)) ["a" "b" "c" "d" "e" "f"])
        {:keys [port stop!]} (start-stall-server! {:mode :trickle :headers sse-headers
                                                   :chunks (conj chunks (sse (chunk-json "!" :finish "STOP")))
                                                   :delay-ms 60})
        client (g/client {:api-key "k" :base-url (base-url port) :timeout-ms 150})]
    (try
      (let [r (g/accumulate-stream (g/generate-content-stream client "m" {}))]
        (is (g/stream-complete? r))
        (is (= "abcdef!" (g/output-text r))))
      (finally (stop!)))))
