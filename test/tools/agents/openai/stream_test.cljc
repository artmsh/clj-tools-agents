(ns tools.agents.openai.stream-test
  "responses-stream and chat-completions-stream end to end against the shared streaming mock servers
   (tools.agents.test-support), identical on both runtimes.

   Ports are taken from the OS (bind 0, read, release) rather than a fixed
   band, so the suite cannot collide with another suite running concurrently
   on the same host. openai.cljc has no *sleep-fn*: retryable mock responses
   carry `retry-after-ms: 1`, which the retry policy honors verbatim."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.openai :as oai]
            [tools.agents.sse :as sse]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [start-server! start-abort-server! start-stall-server! sse-chunks rotating-token-cache]]))

(defn- free-port []
  (with-open [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort ss)))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(def ^:private sse-headers {"content-type" "text/event-stream"})

(def ^:private fast-retry {"retry-after-ms" "1"})

(defn- fixture [name] (slurp (str "test/resources/sse/" name ".sse")))

(defn- sse
  ([json] (str "data: " json "\n\n"))
  ([event json] (str "event: " event "\ndata: " json "\n\n")))

(defn- ev-json [m] (oai/write-json m))

(defn- with-server [route handler f]
  (let [{:keys [port stop!]} (start-server! 0 route handler)]
    (try (f (oai/client {:api-key "test-key" :base-url (base-url port)}))
         (finally (stop!)))))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

;; ---------------------------------------------------------------------------
;; Responses
;; ---------------------------------------------------------------------------

(def ^:private r-route "/v1/responses")

(defn- text-delta [s] {"type" "response.output_text.delta" "item_id" "msg_1" "output_index" 0
                       "content_index" 0 "delta" s})

(def ^:private r-created {"type" "response.created"
                          "response" {"id" "resp_1" "object" "response" "status" "in_progress" "output" []}})

(def ^:private r-item-added {"type" "response.output_item.added" "output_index" 0
                             "item" {"id" "msg_1" "type" "message" "role" "assistant" "content" []}})

(defn- r-terminal [t status text]
  {"type" t "response" {"id" "resp_1" "object" "response" "status" status
                        "output" [{"id" "msg_1" "type" "message" "role" "assistant"
                                   "content" [{"type" "output_text" "text" text "annotations" []}]}]}})

(defn- send-events! [send! events]
  (doseq [e events] (send! (sse (get e "type") (ev-json e)))))

(deftest responses-request-line-headers-and-body
  (let [captured (atom nil)]
    (with-server r-route
      (fn [req] (reset! captured req)
        {:status 200 :headers sse-headers
         :body (fn [send!] (send-events! send! [r-created (r-terminal "response.completed" "completed" "hi")]))})
      (fn [client]
        (let [s   (oai/responses-stream client {"model" "gpt-6-astra" "input" "hello" :stream false})
              evs (into [] s)]
          (is (= [r-created (r-terminal "response.completed" "completed" "hi")] evs))
          (is (= "response.created" (:tools.agents.sse/event (meta (first evs)))))
          (is (= :eof (stream/outcome s)))
          (is (= 200 (:status (stream/response s)))))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/responses" (:path req)))
          (is (= "Bearer test-key" (get (:headers req) "authorization")))
          (is (= "application/json" (get (:headers req) "content-type")))
          (is (= {"model" "gpt-6-astra" "input" "hello" "stream" true} (oai/read-json (:body req)))
              "\"stream\" true replaces a caller's :stream")
          (is (not (str/includes? (:body req) "test-key"))))))))

(deftest responses-incremental-accumulation
  (let [release (promise)]
    (with-server r-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send-events! send! [r-created r-item-added (text-delta "Once ")])
                       (deref release 5000 nil)
                       (send-events! send! [(text-delta "upon a time")
                                            (r-terminal "response.completed" "completed" "Once upon a time")]))})
      (fn [client]
        (let [s      (oai/responses-stream client {"model" "m" "input" "x"})
              deltas (atom [])
              r      (transduce (map (fn [e]
                                       (when (= "response.output_text.delta" (get e "type"))
                                         (swap! deltas conj (get e "delta"))
                                         (deliver release true))
                                       e))
                                oai/accumulate-response-event s)]
          (is (= ["Once " "upon a time"] @deltas))
          (is (= "Once upon a time" (oai/output-text r)))
          (is (= (apply str @deltas) (oai/output-text r)))
          (is (oai/stream-complete? r)))))))

(deftest responses-function-call-fixture-served-over-http
  (let [fx (fixture "openai-responses-function-call")]
    (with-server r-route
      (fn [_] {:status 200 :headers sse-headers :body (fn [send!] (send! fx))})
      (fn [client]
        (let [r        (oai/accumulate-response-stream (oai/responses-stream client {"model" "m"}))
              events   (mapv (comp oai/read-json :data) (sse/parse-string fx))
              expected (get (peek events) "response")]
          (is (= expected r))
          (is (oai/stream-complete? r))
          (is (= "Checking the weather." (oai/output-text r)))
          (is (= "{ \"arg\": 123 }" (get-in r ["output" 1 "arguments"])))
          (testing "the same events without the terminal one assemble the same output"
            (let [partial (oai/accumulate-response-stream (pop events))]
              (is (false? (oai/stream-complete? partial)))
              (is (= (get expected "output") (get partial "output")))))
          (testing "deltas alone (no *.done events) assemble text and arguments"
            (let [partial (oai/accumulate-response-stream
                           (remove #(str/ends-with? (get % "type") ".done") (pop events)))]
              (is (= "Checking the weather." (oai/output-text partial)))
              (is (= "{ \"arg\": 123 }" (get-in partial ["output" 1 "arguments"])))
              (is (= "in_progress" (get-in partial ["output" 1 "status"]))))))))))

(deftest responses-failed-and-incomplete-are-complete-results
  (doseq [[t status] [["response.failed" "failed"] ["response.incomplete" "incomplete"]]]
    (with-server r-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!] (send-events! send! [r-created r-item-added (text-delta "par")
                                                      (r-terminal t status "par")]))})
      (fn [client]
        (let [r (oai/accumulate-response-stream (oai/responses-stream client {"model" "m"}))]
          (is (= status (get r "status")) t)
          (is (oai/stream-complete? r) t)
          (is (= "par" (oai/output-text r)) t))))))

(deftest responses-error-event-throws-typed-after-earlier-events
  (let [hits (atom 0)
        err  {"type" "error" "code" "ERR_SOMETHING" "message" "Something went wrong" "param" nil "sequence_number" 3}]
    (with-server r-route
      (fn [_] (swap! hits inc)
        {:status 200 :headers sse-headers
         :body (fn [send!] (send-events! send! [r-created (text-delta "partial") err]))})
      (fn [client]
        (let [s    (oai/responses-stream client {"model" "m"})
              seen (atom [])
              e    (thrown #(run! (fn [ev] (swap! seen conj (get ev "type"))) s))]
          (is (= ["response.created" "response.output_text.delta"] @seen))
          (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
          (is (nil? (:status (ex-data e))))
          (is (= (ev-json err) (:body (ex-data e))))
          (is (= "ERR_SOMETHING" (get-in (ex-data e) [:error "code"])))
          (is (= "tools.agents.openai/responses-stream: stream error: Something went wrong" (ex-message e)))
          (is (= :failed (stream/outcome s)))
          (is (= 1 @hits) "nothing is retried once the stream has started")))))
  (testing "any event with a top-level error object"
    (with-server r-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!] (send! (sse "{\"error\":{\"message\":\"overloaded\",\"type\":\"server_error\"}}")))})
      (fn [client]
        (let [e (thrown #(into [] (oai/responses-stream client {"model" "m"})))]
          (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
          (is (= {"message" "overloaded" "type" "server_error"} (:error (ex-data e)))))))))

(deftest responses-http-error-before-the-stream-is-thrown-at-call-time
  (let [hits (atom 0)]
    (with-server r-route
      (fn [_] (swap! hits inc)
        {:status 400 :body "{\"error\":{\"message\":\"bad model\",\"type\":\"invalid_request_error\"}}"})
      (fn [client]
        (let [e (thrown #(oai/responses-stream client {"model" "nope"}))]
          (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))
          (is (= 400 (:status (ex-data e))))
          (is (str/includes? (:body (ex-data e)) "bad model"))
          (is (= 0 (:retries-taken (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.openai/responses-stream: HTTP 400 bad model"))
          (is (= 1 @hits)))))))

(deftest responses-opening-request-is-retried-per-the-openai-policy
  (testing "503 twice, then a stream"
    (let [hits (atom 0)]
      (with-server r-route
        (fn [_] (if (< (swap! hits inc) 3)
                  {:status 503 :headers fast-retry :body "{\"error\":{\"message\":\"overloaded\"}}"}
                  {:status 200 :headers sse-headers
                   :body (fn [send!] (send-events! send! [(r-terminal "response.completed" "completed" "ok")]))}))
        (fn [client]
          (is (= "ok" (oai/output-text (oai/accumulate-response-stream (oai/responses-stream client {"model" "m"})))))
          (is (= 3 @hits))))))
  (testing "x-should-retry false on a 503 is not retried"
    (let [hits (atom 0)]
      (with-server r-route
        (fn [_] (swap! hits inc) {:status 503 :headers {"x-should-retry" "false"} :body "{}"})
        (fn [client]
          (is (= :tools.agents.openai/internal-server-error
                 (:type (ex-data (thrown #(oai/responses-stream client {"model" "m"}))))))
          (is (= 1 @hits))))))
  (testing "retries exhausted: the status error carries :retries-taken"
    (let [hits (atom 0)]
      (with-server r-route
        (fn [_] (swap! hits inc) {:status 429 :headers fast-retry :body "{\"error\":{\"message\":\"quota\"}}"})
        (fn [client]
          (let [e (thrown #(oai/responses-stream (assoc client :max-retries 2) {"model" "m"}))]
            (is (= :tools.agents.openai/rate-limit-error (:type (ex-data e))))
            (is (= 2 (:retries-taken (ex-data e))))
            (is (= 3 @hits)))))))
  (testing "connection refused: retried, then typed"
    (let [client (oai/client {:api-key "k" :base-url (base-url (free-port)) :max-retries 1})
          e      (thrown #(oai/responses-stream client {"model" "m"}))]
      (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
      (is (= 1 (:retries-taken (ex-data e)))))))

(deftest responses-credential-source-401-invalidates-and-retries-the-open-once
  (let [auths (atom [])
        {:keys [source fetches]} (rotating-token-cache)
        {:keys [port stop!]} (start-server! 0 r-route
                               (fn [req]
                                 (swap! auths conj (get (:headers req) "authorization"))
                                 (if (= 1 (count @auths))
                                   {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                                   {:status 200 :headers sse-headers
                                    :body (fn [send!] (send-events! send! [(r-terminal "response.completed" "completed" "ok")]))})))]
    (try
      (let [client (oai/client {:credential-source source :base-url (base-url port) :max-retries 0})]
        (is (= "ok" (oai/output-text (oai/accumulate-response-stream (oai/responses-stream client {"model" "m"})))))
        (is (= ["Bearer tok-1" "Bearer tok-2"] @auths))
        (is (= 2 @fetches)))
      (finally (stop!))))
  (testing "a static key's 401 is not retried"
    (let [hits (atom 0)]
      (with-server r-route
        (fn [_] (swap! hits inc) {:status 401 :body "{\"error\":{\"message\":\"bad key\"}}"})
        (fn [client]
          (is (= :tools.agents.openai/authentication-error
                 (:type (ex-data (thrown #(oai/responses-stream client {"model" "m"}))))))
          (is (= 1 @hits)))))))

(deftest responses-truncated-stream-is-eof-but-not-complete
  (with-server r-route
    (fn [_] {:status 200 :headers sse-headers
             :body (fn [send!] (send-events! send! [r-created r-item-added (text-delta "cut")]))})
    (fn [client]
      (let [s (oai/responses-stream client {"model" "m"})
            r (oai/accumulate-response-stream s)]
        (is (= :eof (stream/outcome s)))
        (is (= "cut" (oai/output-text r)))
        (is (= "in_progress" (get r "status")))
        (is (false? (oai/stream-complete? r))))))
  (testing "[DONE], if sent, ends the stream without reaching the decoder"
    (with-server r-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send-events! send! [(r-terminal "response.completed" "completed" "ok")])
                       (send! (sse "[DONE]"))
                       (send! (sse "not json")))})
      (fn [client]
        (let [s (oai/responses-stream client {"model" "m"})
              r (oai/accumulate-response-stream s)]
          (is (= :done (stream/outcome s)))
          (is (oai/stream-complete? r)))))))

(deftest responses-transport-failure-mid-stream-is-a-connection-error
  (let [{:keys [port stop!]} (start-abort-server! 0
                               {:headers sse-headers
                                :chunks  [(sse "response.created" (ev-json r-created))]})]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port)})
            s      (oai/responses-stream client {"model" "m"})
            seen   (atom [])
            e      (thrown #(run! (fn [ev] (swap! seen conj ev)) s))]
        (is (= [r-created] @seen))
        (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

(deftest responses-early-termination-and-close!-release-the-connection
  (testing "(into [] (take 1) s) closes; the server sees the client go away"
    (let [gone (promise)]
      (with-server r-route
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! (sse "response.output_text.delta" (ev-json (text-delta (str "d" i))))))
                             (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (oai/responses-stream client {"model" "m"})]
            (is (= "d0" (get (first (into [] (take 1) s)) "delta")))
            (is (= :reduced (stream/outcome s)))
            (is (true? (deref gone 5000 :timeout))))))))
  (testing "close! on an unreduced stream"
    (let [gone (promise)]
      (with-server r-route
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! ": ping\n\n")) (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (oai/responses-stream client {"model" "m"})]
            (stream/close! s)
            (is (true? (deref gone 5000 :timeout)))
            (is (nil? (oai/accumulate-response-stream s)) "a reduce after close! returns init")
            (is (= :cancelled (stream/outcome s)))))))))

;; ---------------------------------------------------------------------------
;; Chat Completions
;; ---------------------------------------------------------------------------

(def ^:private c-route "/v1/chat/completions")

(defn- c-chunk [choices & {:as extra}]
  (merge {"id" "chatcmpl-1" "object" "chat.completion.chunk" "created" 1 "model" "gpt-6-astra"
          "system_fingerprint" "fp_1" "choices" choices}
         extra))

(defn- c-delta [delta & {:as extra}]
  (merge {"index" 0 "delta" delta "logprobs" nil "finish_reason" nil} extra))

(defn- send-chunks! [send! chunks & {:keys [done?] :or {done? true}}]
  (doseq [c chunks] (send! (sse (ev-json c))))
  (when done? (send! "data: [DONE]\n\n")))

(def ^:private hello-chunks
  [(c-chunk [(c-delta {"role" "assistant" "content" ""})])
   (c-chunk [(c-delta {"content" "Hello"})])
   (c-chunk [(c-delta {} "finish_reason" "stop")])])

(deftest chat-request-line-headers-body-and-done
  (let [captured (atom nil)]
    (with-server c-route
      (fn [req] (reset! captured req)
        {:status 200 :headers sse-headers :body (fn [send!] (send-chunks! send! hello-chunks))})
      (fn [client]
        (let [s   (oai/chat-completions-stream client {"model" "gpt-6-astra"
                                                       "messages" [{"role" "user" "content" "hi"}]
                                                       "stream_options" {"include_usage" true}})
              evs (into [] s)]
          (is (= hello-chunks evs) "[DONE] is not emitted")
          (is (= :done (stream/outcome s))))
        (let [req @captured]
          (is (= "POST" (:method req)))
          (is (= "/v1/chat/completions" (:path req)))
          (is (= "Bearer test-key" (get (:headers req) "authorization")))
          (is (= {"model" "gpt-6-astra" "messages" [{"role" "user" "content" "hi"}]
                  "stream_options" {"include_usage" true} "stream" true}
                 (oai/read-json (:body req)))))))))

(deftest chat-incremental-accumulation-and-completion-text
  (let [release (promise)]
    (with-server c-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!]
                       (send-chunks! send! [(c-chunk [(c-delta {"role" "assistant" "content" "Once "})])] :done? false)
                       (deref release 5000 nil)
                       (send-chunks! send! [(c-chunk [(c-delta {"content" "upon a time"} "finish_reason" "stop")])]))})
      (fn [client]
        (let [s      (oai/chat-completions-stream client {"model" "m" "messages" []})
              deltas (atom [])
              r      (oai/accumulate-chat-completion-stream
                      (reify clojure.lang.IReduceInit
                        (reduce [_ f init]
                          (reduce (fn [acc c]
                                    (swap! deltas conj (get-in c ["choices" 0 "delta" "content"]))
                                    (deliver release true)
                                    (f acc c))
                                  init s))))]
          (is (= ["Once " "upon a time"] @deltas))
          (is (= "Once upon a time" (oai/completion-text r)))
          (is (= "chat.completion" (get r "object")))
          (is (false? (oai/stream-complete? r)) "a wrapped stream carries no [DONE] signal")
          (is (= :done (stream/outcome s))))))))

(deftest chat-tool-calls-usage-fixture-served-over-http
  (with-server c-route
    (fn [_] {:status 200 :headers sse-headers
             :body (fn [send!] (send! (fixture "openai-chat-tool-calls-usage")))})
    (fn [client]
      (let [s (oai/chat-completions-stream client {"model" "m" "messages" []
                                                   "stream_options" {"include_usage" true}})
            r (oai/accumulate-chat-completion-stream s)]
        (is (= :done (stream/outcome s)))
        (is (oai/stream-complete? r))
        (is (= {"id" "chatcmpl-456" "object" "chat.completion" "created" 1694268190 "model" "gpt-6-astra"
                "system_fingerprint" "fp_44709d6fcb"
                "usage" {"prompt_tokens" 82 "completion_tokens" 37 "total_tokens" 119}
                "choices" [{"index" 0 "logprobs" nil "finish_reason" "tool_calls"
                            "message" {"role" "assistant" "content" nil "refusal" nil
                                       "tool_calls" [{"index" 0 "id" "call_kyiv" "type" "function"
                                                      "function" {"name" "get_weather" "arguments" "{\"city\":\"Kyiv\"}"}}
                                                     {"index" 1 "id" "call_oslo" "type" "function"
                                                      "function" {"name" "get_weather" "arguments" "{\"city\":\"Oslo\"}"}}]}}]}
               r))
        (is (nil? (oai/completion-text r)))))))

(deftest chat-openai-chat-text-fixture-matches-completion-text
  (with-server c-route
    (fn [_] {:status 200 :headers sse-headers :body (fn [send!] (send! (fixture "openai-chat-text")))})
    (fn [client]
      (let [r (oai/accumulate-chat-completion-stream (oai/chat-completions-stream client {"model" "m"}))]
        (is (= "Hello" (oai/completion-text r)))
        (is (= "stop" (get-in r ["choices" 0 "finish_reason"])))
        (is (oai/stream-complete? r))))))

(deftest chat-truncated-stream-is-eof-and-not-complete
  (with-server c-route
    (fn [_] {:status 200 :headers sse-headers :body (fn [send!] (send-chunks! send! (pop hello-chunks) :done? false))})
    (fn [client]
      (let [s (oai/chat-completions-stream client {"model" "m"})
            r (oai/accumulate-chat-completion-stream s)]
        (is (= :eof (stream/outcome s)))
        (is (= "Hello" (oai/completion-text r)))
        (is (false? (oai/stream-complete? r))))))
  (testing "finish_reason and usage without [DONE] are still not complete"
    (with-server c-route
      (fn [_] {:status 200 :headers sse-headers
               :body (fn [send!] (send-chunks! send! (conj hello-chunks (c-chunk [] "usage" {"total_tokens" 3}))
                                               :done? false))})
      (fn [client]
        (let [r (oai/accumulate-chat-completion-stream (oai/chat-completions-stream client {"model" "m"}))]
          (is (= {"total_tokens" 3} (get r "usage")))
          (is (false? (oai/stream-complete? r)))))))
  (testing "early termination: :reduced, not complete, connection released"
    (let [gone (promise)]
      (with-server c-route
        (fn [_] {:status 200 :headers sse-headers
                 :body (fn [send!]
                         (loop [i 0]
                           (cond
                             (not (send! (sse (ev-json (c-chunk [(c-delta {"content" (str "c" i)})])))))
                             (deliver gone true)
                             (> i 500) (deliver gone false)
                             :else (do (Thread/sleep 10) (recur (inc i))))))})
        (fn [client]
          (let [s (oai/chat-completions-stream client {"model" "m"})
                r (oai/accumulate-chat-completion-stream (eduction (take 2) s))]
            (is (= "c0c1" (oai/completion-text r)))
            (is (= :reduced (stream/outcome s)))
            (is (false? (oai/stream-complete? r)))
            (is (true? (deref gone 5000 :timeout)))))))))

(deftest chat-error-chunk-mid-stream-throws-typed
  (let [hits (atom 0)
        err  "{\"error\":{\"message\":\"The server had an error\",\"type\":\"server_error\",\"code\":null}}"]
    (with-server c-route
      (fn [_] (swap! hits inc)
        {:status 200 :headers sse-headers
         :body (fn [send!] (send-chunks! send! [(first hello-chunks)] :done? false) (send! (sse err)))})
      (fn [client]
        (let [s    (oai/chat-completions-stream client {"model" "m"})
              seen (atom 0)
              e    (thrown #(run! (fn [_] (swap! seen inc)) s))]
          (is (= 1 @seen))
          (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
          (is (= err (:body (ex-data e))))
          (is (= "server_error" (get-in (ex-data e) [:error "type"])))
          (is (= "tools.agents.openai/chat-completions-stream: stream error: The server had an error" (ex-message e)))
          (is (= :failed (stream/outcome s)))
          (is (= 1 @hits)))))))

(deftest chat-opening-errors-retries-and-401
  (testing "HTTP error before the stream"
    (with-server c-route
      (fn [_] {:status 404 :body "{\"error\":{\"message\":\"The model `nope` does not exist\"}}"})
      (fn [client]
        (let [e (thrown #(oai/chat-completions-stream client {"model" "nope"}))]
          (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
          (is (= 404 (:status (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.openai/chat-completions-stream: HTTP 404 The model"))))))
  (testing "a 500 is retried before the stream starts"
    (let [hits (atom 0)]
      (with-server c-route
        (fn [_] (if (= 1 (swap! hits inc))
                  {:status 500 :headers fast-retry :body "{}"}
                  {:status 200 :headers sse-headers :body (fn [send!] (send-chunks! send! hello-chunks))}))
        (fn [client]
          (let [r (oai/accumulate-chat-completion-stream (oai/chat-completions-stream client {"model" "m"}))]
            (is (= "Hello" (oai/completion-text r)))
            (is (oai/stream-complete? r))
            (is (= 2 @hits)))))))
  (testing "credential-source 401: invalidate and retry the open once"
    (let [auths (atom [])
          {:keys [source]} (rotating-token-cache)
          {:keys [port stop!]} (start-server! 0 c-route
                                 (fn [req]
                                   (swap! auths conj (get (:headers req) "authorization"))
                                   (if (= 1 (count @auths))
                                     {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                                     {:status 200 :headers sse-headers
                                      :body (fn [send!] (send-chunks! send! hello-chunks))})))]
      (try
        (let [client (oai/client {:credential-source source :base-url (base-url port) :max-retries 0})]
          (is (= "Hello" (oai/completion-text (oai/accumulate-chat-completion-stream
                                                (oai/chat-completions-stream client {"model" "m"})))))
          (is (= ["Bearer tok-1" "Bearer tok-2"] @auths)))
        (finally (stop!))))))

;; ---------------------------------------------------------------------------
;; Timeouts against a stalling server
;; ---------------------------------------------------------------------------

(deftest a-stalled-server-times-out-is-retried-and-typed
  (let [{:keys [port stop! accepted]} (start-stall-server! {:mode :no-headers})
        client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 1 :timeout-ms 200})]
    (try
      (doseq [[label f] [["create" #(oai/responses-create client {"model" "m"})]
                         ["stream" #(oai/responses-stream client {"model" "m"})]]]
        (reset! accepted 0)
        (let [e (with-redefs [oai/sleep! (fn [_])] (try (f) nil (catch Exception e e)))]
          (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))) label)
          (is (true? (:timeout? (ex-data e))) label)
          (is (= 2 @accepted) (str label ": one retry"))))
      (finally (stop!)))))

(deftest a-stream-is-not-cut-by-the-request-timeout
  (let [{:keys [port stop!]} (start-stall-server! {:mode :trickle :headers sse-headers
                                                   :chunks (sse-chunks (fixture "openai-responses-text"))
                                                   :delay-ms 60})
        client (oai/client {:api-key "k" :base-url (base-url port) :timeout-ms 150})]
    (try
      (let [r (oai/accumulate-response-stream (oai/responses-stream client {"model" "m"}))]
        (is (oai/stream-complete? r))
        (is (seq (oai/output-text r))))
      (finally (stop!)))))
