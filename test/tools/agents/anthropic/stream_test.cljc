(ns tools.agents.anthropic.stream-test
  "messages-stream end to end against the shared streaming mock servers
   (tools.agents.test-support), identical on both runtimes.

   Ports are taken from the OS (bind 0, read, release) rather than a fixed
   band, so the suite cannot collide with another suite running concurrently
   on the same host. Every retry test binds `a/*sleep-fn*` to a no-op."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.anthropic :as a]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [start-server! start-abort-server! start-stall-server! sse-chunks rotating-token-cache]]))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort ss)))

(defn- base-url [port] (str "http://127.0.0.1:" port))

(def ^:private sse-headers {"content-type" "text/event-stream"})

(def ^:private path "/v1/messages")

(defn- fixture [name] (slurp (str "test/resources/sse/" name ".sse")))

(defn- sse [event json] (str "event: " event "\ndata: " json "\n\n"))

(def ^:private start-json
  (str "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
       "\"content\":[],\"model\":\"claude-opus-5\",\"stop_reason\":null,\"stop_sequence\":null,"
       "\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}"))

(defn- text-delta-json [text]
  (str "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" text "\"}}"))

(def ^:private block-start-json
  "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}")

(defn- head-events []
  (str (sse "message_start" start-json) (sse "content_block_start" block-start-json)))

(defn- tail-events [output-tokens]
  (str (sse "content_block_stop" "{\"type\":\"content_block_stop\",\"index\":0}")
       (sse "message_delta" (str "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\","
                                 "\"stop_sequence\":null},\"usage\":{\"output_tokens\":" output-tokens "}}"))
       (sse "message_stop" "{\"type\":\"message_stop\"}")))

(defn- ok-stream [text]
  {:status 200 :headers sse-headers
   :body (fn [send!] (send! (str (head-events) (sse "content_block_delta" (text-delta-json text)) (tail-events 3))))})

(def ^:private request {"model" "claude-opus-5" "max_tokens" 64
                        "messages" [{"role" "user" "content" "hi"}]})

(defn- with-server
  ([handler f] (with-server {} handler f))
  ([client-opts handler f]
   (let [{:keys [port stop!]} (start-server! 0 path handler)]
     (try (f (a/client (merge {:api-key "test-key" :base-url (base-url port)} client-opts)))
          (finally (stop!))))))

(defn- caught [thunk] (try (thunk) nil (catch Exception e e)))

(deftest request-line-headers-and-body
  (testing "api key + betas: stream true in the body, anthropic-version, anthropic-beta"
    (let [captured (atom nil)]
      (with-server {:betas ["fine-grained-tool-streaming-2025-05-14" "b2"]}
        (fn [req] (reset! captured req) (ok-stream "hi"))
        (fn [client]
          (let [s (a/messages-stream client (assoc request :stream false))]
            (is (= "hi" (a/output-text (a/accumulate-stream s))))
            (is (= :eof (stream/outcome s)))
            (is (= 200 (:status (stream/response s)))))
          (let [req @captured]
            (is (= "POST" (:method req)))
            (is (= path (:path req)))
            (is (= "test-key" (get (:headers req) "x-api-key")))
            (is (= "2023-06-01" (get (:headers req) "anthropic-version")))
            (is (= "fine-grained-tool-streaming-2025-05-14,b2" (get (:headers req) "anthropic-beta")))
            (is (= "application/json" (get (:headers req) "content-type")))
            (is (= (assoc request "stream" true) (a/read-json (:body req)))
                "a caller's :stream false is replaced by \"stream\": true")
            (is (not (str/includes? (:body req) "test-key"))))))))
  (testing "auth token: Bearer + oauth beta combined with :betas"
    (let [captured (atom nil)
          {:keys [port stop!]} (start-server! 0 path (fn [req] (reset! captured req) (ok-stream "x")))]
      (try
        (let [client (a/client {:auth-token "tok" :betas ["b1"] :base-url (base-url port)})]
          (is (= "x" (a/output-text (a/accumulate-stream (a/messages-stream client request)))))
          (is (= "Bearer tok" (get (:headers @captured) "authorization")))
          (is (= "oauth-2025-04-20,b1" (get (:headers @captured) "anthropic-beta")))
          (is (nil? (get (:headers @captured) "x-api-key"))))
        (finally (stop!))))))

(deftest docs-fixture-accumulates-and-output-text-reads-it
  ;; test/resources/sse/anthropic-text.sse is the Anthropic streaming docs'
  ;; basic example, verbatim (event: lines, a ping, cumulative usage).
  (with-server
    (fn [_] {:status 200 :headers (assoc sse-headers "request-id" "req_abc")
             :body (fn [send!] (send! (fixture "anthropic-text")))})
    (fn [client]
      (let [s (a/messages-stream client request)
            m (a/accumulate-stream s)]
        (is (= {"id" "msg_1nZdL29xx5MUA1yADyHTEsnR8uuvGzszyY" "type" "message" "role" "assistant"
                "content" [{"type" "text" "text" "Hello!"}] "model" "claude-opus-5"
                "stop_reason" "end_turn" "stop_sequence" nil
                "usage" {"input_tokens" 25 "output_tokens" 15}}
               m))
        (is (= "Hello!" (a/output-text m)))
        (is (a/stream-complete? m))
        (is (= "req_abc" (a/request-id m)) "accumulate-stream carries the response headers")
        (is (= :eof (stream/outcome s)))))))

(deftest multi-event-stream-delivers-live-and-accumulates
  (let [release (promise)]
    (with-server
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send! (head-events))
                       (send! (sse "content_block_delta" (text-delta-json "Once ")))
                       (deref release 5000 nil)
                       (send! (sse "ping" "{\"type\":\"ping\"}"))
                       (send! (sse "content_block_delta" (text-delta-json "upon ")))
                       (send! (sse "content_block_delta" (text-delta-json "a time")))
                       (send! (tail-events 7)))})
      (fn [client]
        (let [s      (a/messages-stream client request)
              deltas (atom [])
              types  (atom [])
              m      (transduce (map (fn [ev]
                                       (swap! types conj (get ev "type"))
                                       (when-let [t (get-in ev ["delta" "text"])]
                                         (swap! deltas conj t)
                                         (deliver release true))
                                       ev))
                                a/accumulate-event s)]
          (is (= ["Once " "upon " "a time"] @deltas))
          (is (= ["message_start" "content_block_start" "content_block_delta" "ping" "content_block_delta"
                  "content_block_delta" "content_block_stop" "message_delta" "message_stop"]
                 @types)
              "ping is passed through as an event")
          (is (= "Once upon a time" (a/output-text m)))
          (is (= (apply str @deltas) (a/output-text m)))
          (is (= {"input_tokens" 9 "output_tokens" 7} (get m "usage")))
          (is (a/stream-complete? m)))))))

(deftest error-event-mid-stream-is-typed-and-not-retried
  (let [hits (atom 0)
        err  "{\"type\": \"error\", \"error\": {\"type\": \"overloaded_error\", \"message\": \"Overloaded\"}}"]
    (with-server
      (fn [_] (swap! hits inc)
        {:status 200 :headers (assoc sse-headers "request-id" "req_err")
         :body (fn [send!]
                 (send! (str (head-events) (sse "content_block_delta" (text-delta-json "partial"))
                             (sse "error" err)
                             (sse "content_block_delta" (text-delta-json "never"))
                             (tail-events 2))))})
      (fn [client]
        (binding [a/*sleep-fn* (fn [_] nil)]
          (let [s    (a/messages-stream client request)
                seen (atom [])
                e    (caught #(run! (fn [ev] (swap! seen conj (get ev "type"))) s))
                data (ex-data e)]
            (is (= ["message_start" "content_block_start" "content_block_delta"] @seen)
                "nothing after the error event is delivered")
            (is (= :tools.agents.anthropic.error/overloaded (:type data)))
            (is (= "overloaded_error" (:error-type data)))
            (is (nil? (:status data)))
            (is (= err (:body data)))
            (is (= "req_err" (get (:headers data) "request-id")))
            (is (str/starts-with? (ex-message e) "tools.agents.anthropic/messages-stream: stream error overloaded_error Overloaded"))
            (is (= :failed (stream/outcome s)))
            (is (= 1 @hits) "nothing is retried once the stream has started"))))))
  (testing "matched by the SSE event name even when data carries no type (SDK Stream.__stream__)"
    (with-server
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!] (send! (str (head-events)
                                             (sse "error" "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow\"}}"))))})
      (fn [client]
        (let [e (caught #(a/accumulate-stream (a/messages-stream client request)))]
          (is (= :tools.agents.anthropic.error/rate-limit (:type (ex-data e)))))))))

(deftest http-error-before-the-stream-is-typed-and-thrown-at-call-time
  (let [hits (atom 0)]
    (with-server
      (fn [_] (swap! hits inc)
        {:status 400 :headers {"request-id" "req_400"}
         :body "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: required\"}}"})
      (fn [client]
        (let [e (caught #(a/messages-stream client request))]
          (is (= :tools.agents.anthropic.error/bad-request (:type (ex-data e))))
          (is (= 400 (:status (ex-data e))))
          (is (str/includes? (:body (ex-data e)) "max_tokens: required"))
          (is (= "req_400" (get (:headers (ex-data e)) "request-id")))
          (is (= "tools.agents.anthropic/messages-stream: HTTP 400 max_tokens: required" (ex-message e)))
          (is (= 1 @hits)))))))

(deftest opening-request-is-retried-per-the-anthropic-policy
  (testing "529 then 503, then a stream"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (case (swap! hits inc)
                  1 {:status 529 :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"}
                  2 {:status 503 :body "unavailable"}
                  (ok-stream "ok")))
        (fn [client]
          (binding [a/*sleep-fn* (fn [_] nil)]
            (is (= "ok" (a/output-text (a/accumulate-stream (a/messages-stream client request)))))
            (is (= 3 @hits)))))))
  (testing "Retry-After is honored on the opening request"
    (let [hits (atom 0) slept (atom [])]
      (with-server
        (fn [_] (if (= 1 (swap! hits inc))
                  {:status 429 :headers {"retry-after" "2"} :body "{}"}
                  (ok-stream "ok")))
        (fn [client]
          (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
            (is (= "ok" (a/output-text (a/accumulate-stream (a/messages-stream client request)))))
            (is (= [2.0] @slept)))))))
  (testing "retries exhausted: the last status error surfaces"
    (let [hits (atom 0)]
      (with-server {:max-retries 2}
        (fn [_] (swap! hits inc) {:status 529 :body "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"})
        (fn [client]
          (binding [a/*sleep-fn* (fn [_] nil)]
            (let [e (caught #(a/messages-stream client request))]
              (is (= 529 (:status (ex-data e))))
              (is (= :tools.agents.anthropic.error/internal-server (:type (ex-data e))))
              (is (= 3 @hits))))))))
  (testing "connection refused: retried, then :api-connection"
    (let [slept  (atom 0)
          client (a/client {:api-key "k" :base-url (base-url (free-port)) :max-retries 1})]
      (binding [a/*sleep-fn* (fn [_] (swap! slept inc))]
        (let [e (caught #(a/messages-stream client request))]
          (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e))))
          (is (= 1 @slept))))))
  (testing "a 400 is not retried"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) {:status 400 :body "{}"})
        (fn [client]
          (binding [a/*sleep-fn* (fn [_] (throw (ex-info "must not sleep" {})))]
            (is (= 400 (:status (ex-data (caught #(a/messages-stream client request))))))
            (is (= 1 @hits))))))))

(deftest transport-failure-mid-stream-is-a-connection-error
  (let [{:keys [port stop!]} (start-abort-server! 0
                               {:headers sse-headers
                                :chunks  [(sse "message_start" start-json)]})]
    (try
      (let [client (a/client {:api-key "k" :base-url (base-url port)})
            s      (a/messages-stream client request)
            seen   (atom [])
            e      (caught #(run! (fn [ev] (swap! seen conj ev)) s))]
        (is (= 1 (count @seen)))
        (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

(deftest a-stream-without-message-stop-is-eof-but-not-complete
  (with-server
    (fn [_] {:status 200 :headers sse-headers
             :body (fn [send!] (send! (str (head-events) (sse "content_block_delta" (text-delta-json "cut")))))})
    (fn [client]
      (let [s (a/messages-stream client request)
            m (a/accumulate-stream s)]
        (is (= :eof (stream/outcome s)))
        (is (= "cut" (a/output-text m)))
        (is (nil? (get m "stop_reason")))
        (is (false? (a/stream-complete? m)))))))

(deftest early-termination-and-close!-release-the-connection
  (testing "(into [] (take 1) s) closes; the server sees the client go away"
    (let [gone (promise)]
      (with-server
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (send! (head-events))
                         (loop [i 0]
                           (cond
                             (not (send! (sse "content_block_delta" (text-delta-json (str "c" i))))) (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (a/messages-stream client request)]
            (is (= [{"type" "message_start"}] (mapv #(select-keys % ["type"]) (into [] (take 1) s))))
            (is (= :reduced (stream/outcome s)))
            (is (true? (deref gone 5000 :timeout))))))))
  (testing "close! on an unreduced stream"
    (let [gone (promise)]
      (with-server
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! (sse "ping" "{\"type\":\"ping\"}"))) (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (a/messages-stream client request)]
            (stream/close! s)
            (is (true? (deref gone 5000 :timeout)))
            (is (nil? (a/accumulate-stream s)) "a reduce after close! returns init")
            (is (= :cancelled (stream/outcome s)))))))))

(deftest credential-source-401-invalidates-and-retries-the-opening-request-once
  (testing "401 -> invalidate -> retry with the refreshed Bearer token, outside :max-retries"
    (let [auths (atom [])
          {:keys [source fetches]} (rotating-token-cache)
          {:keys [port stop!]} (start-server! 0 path
                                 (fn [req]
                                   (swap! auths conj [(get (:headers req) "authorization")
                                                      (get (:headers req) "x-api-key")
                                                      (get (:headers req) "anthropic-beta")])
                                   (if (= 1 (count @auths))
                                     {:status 401 :body "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"expired\"}}"}
                                     (ok-stream "ok"))))]
      (try
        (let [client (a/client {:credential-source source :base-url (base-url port) :max-retries 0})]
          (binding [a/*sleep-fn* (fn [_] (throw (ex-info "must not sleep" {})))]
            (is (= "ok" (a/output-text (a/accumulate-stream (a/messages-stream client request))))))
          (is (= [["Bearer tok-1" nil "oauth-2025-04-20"] ["Bearer tok-2" nil "oauth-2025-04-20"]] @auths))
          (is (= 2 @fetches)))
        (finally (stop!)))))
  (testing "a second 401 surfaces as the typed status error"
    (let [hits (atom 0)
          {:keys [source]} (rotating-token-cache)
          {:keys [port stop!]} (start-server! 0 path
                                 (fn [_] (swap! hits inc) {:status 401 :body "{\"error\":{\"message\":\"nope\"}}"}))]
      (try
        (let [client (a/client {:credential-source source :base-url (base-url port) :max-retries 0})
              e      (caught #(a/messages-stream client request))]
          (is (= :tools.agents.anthropic.error/authentication (:type (ex-data e))))
          (is (= 2 @hits))
          (is (not (str/includes? (pr-str (ex-data e)) "tok-"))))
        (finally (stop!)))))
  (testing "a static key's 401 is not retried"
    (let [hits (atom 0)]
      (with-server
        (fn [_] (swap! hits inc) {:status 401 :body "{\"error\":{\"message\":\"bad key\"}}"})
        (fn [client]
          (is (= 401 (:status (ex-data (caught #(a/messages-stream client request))))))
          (is (= 1 @hits)))))))

(deftest messages-create-still-refuses-stream-true
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})
        e      (caught #(a/messages-create client (assoc request "stream" true)))]
    (is (= :tools.agents.anthropic.error/streaming-unsupported (:type (ex-data e))))
    (is (str/includes? (ex-message e) "messages-stream"))))

;; ---------------------------------------------------------------------------
;; Timeouts against a stalling server
;; ---------------------------------------------------------------------------

(deftest a-stalled-server-times-out-is-retried-and-typed
  (let [{:keys [port stop! accepted]} (start-stall-server! {:mode :no-headers})
        client (a/client {:api-key "k" :base-url (base-url port) :max-retries 1 :timeout-ms 200})
        req    {"model" "m" "max_tokens" 1 "messages" []}]
    (try
      (doseq [[label f] [["create" #(a/messages-create client req)]
                         ["stream" #(a/messages-stream client req)]]]
        (reset! accepted 0)
        (let [e (binding [a/*sleep-fn* (fn [_])] (try (f) nil (catch Exception e e)))]
          (is (= :tools.agents.anthropic.error/api-connection (:type (ex-data e))) label)
          (is (true? (:timeout? (ex-data e))) label)
          (is (= 2 @accepted) (str label ": one retry"))))
      (finally (stop!)))))

(deftest a-stream-is-not-cut-by-the-request-timeout
  (let [{:keys [port stop!]} (start-stall-server! {:mode :trickle :headers sse-headers
                                                   :chunks (sse-chunks (slurp "test/resources/sse/anthropic-text.sse"))
                                                   :delay-ms 60})
        client (a/client {:api-key "k" :base-url (base-url port) :timeout-ms 150})]
    (try
      (let [m (a/accumulate-stream (a/messages-stream client {"model" "m" "max_tokens" 1 "messages" []}))]
        (is (a/stream-complete? m))
        (is (= "Hello!" (a/output-text m))))
      (finally (stop!)))))
