(ns tools.agents.stream-test
  "tools.agents.stream/open-event-stream end to end against the shared mock
   servers, identical on both runtimes.

   Port range 19140-19159 — next to tools.agents.http-test (19100-19139)."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.json :as json]
            [tools.agents.stream :as stream]
            [tools.agents.test-support :refer [start-server! start-abort-server!]]))

(defn- url [port path] (str "http://127.0.0.1:" port path))

(def ^:private sse-headers {"content-type" "text/event-stream"})

(defn- open [port path & {:as opts}]
  (stream/open-event-stream (merge {:request {:method :post :url (url port path) :body "{}"}} opts)))

(defn- until-gone!
  "Streaming-body helper: send SSE comments every 10 ms until the client
   disconnects, then deliver `gone` true. Gives up after ~5 s (delivers false)."
  [send! gone]
  (loop [i 0]
    (cond
      (not (send! ": ping\n\n")) (deliver gone true)
      (> i 500)                  (deliver gone false)
      :else                      (do (Thread/sleep 10) (recur (inc i))))))

(deftest events-are-delivered-before-the-stream-ends
  (let [seen-first (promise)
        server-saw (promise)
        {:keys [port stop!]}
        (start-server! 19140 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! "event: a\ndata: 1\nid: x\n\n")
                           ;; Event 2 is sent only after the reducer has seen event 1.
                           (deliver server-saw (deref seen-first 5000 :timeout))
                           (send! "data: 2\n\n"))}))]
    (try
      (let [s   (open port "/s")
            out (reduce (fn [acc ev]
                          (when (= "1" (:data ev)) (deliver seen-first :seen))
                          (conj acc ev))
                        [] s)]
        (is (= :seen @server-saw) "event 1 reached the reducer while the server was still blocked")
        (is (= [{:event "a" :data "1" :id "x" :retry nil}
                {:event "message" :data "2" :id "x" :retry nil}]
               out))
        (is (= :eof (stream/outcome s)))
        (is (= 200 (:status (stream/response s))))
        (is (= "text/event-stream" (get-in (stream/response s) [:headers "content-type"]))))
      (finally (deliver seen-first :cleanup) (stop!)))))

(deftest early-termination-closes-the-connection
  (let [gone (promise)
        {:keys [port stop!]}
        (start-server! 19141 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! "data: 1\n\ndata: 2\n\n")
                           (until-gone! send! gone))}))]
    (try
      (let [s (open port "/s")]
        (is (= ["1"] (into [] (comp (map :data) (take 1)) s)))
        (is (= :reduced (stream/outcome s)))
        (is (true? (deref gone 5000 :timeout)) "server observed the disconnect"))
      (finally (stop!)))))

(deftest exception-in-reducer-closes-the-connection
  (let [gone (promise)
        {:keys [port stop!]}
        (start-server! 19142 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! "data: boom\n\n")
                           (until-gone! send! gone))}))]
    (try
      (let [s (open port "/s")
            e (try (reduce (fn [_ _] (throw (ex-info "reducer failed" {:k 1}))) nil s) nil
                   (catch Exception e e))]
        (is (= {:k 1} (ex-data e)) "the reducer's own exception propagates unwrapped")
        (is (= :failed (stream/outcome s)))
        (is (true? (deref gone 5000 :timeout))))
      (finally (stop!)))))

(deftest close-from-another-thread-unblocks-a-pending-read
  (let [gone      (promise)
        closed    (promise)
        {:keys [port stop!]}
        (start-server! 19143 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! "data: 1\n\n")
                           ;; Stay silent: the client is parked in a blocking read.
                           (deref closed 5000 nil)
                           (until-gone! send! gone))}))]
    (try
      (let [s        (open port "/s")
            got-one  (promise)
            result   (future (reduce (fn [acc ev] (deliver got-one true) (conj acc (:data ev))) [] s))]
        (is (true? (deref got-one 5000 :timeout)))
        (Thread/sleep 100)
        (is (not (realized? result)) "reduce is blocked waiting for more bytes")
        (stream/close! s)
        (deliver closed true)
        (is (= ["1"] (deref result 5000 :timeout)) "the cancelled reduce returns what it accumulated")
        (is (= :cancelled (stream/outcome s)))
        (is (true? (deref gone 5000 :timeout)))
        (stream/close! s)
        (is true "close! is idempotent"))
      (finally (deliver closed true) (stop!)))))

(deftest a-stream-is-single-use
  (let [{:keys [port stop!]}
        (start-server! 19144 "/s"
          (fn [_] {:status 200 :headers sse-headers :body (fn [send!] (send! "data: 1\n\n"))}))]
    (try
      (let [s (open port "/s")]
        (is (= ["1"] (mapv :data (into [] s))))
        (let [e (try (reduce conj [] s) nil (catch Exception e e))]
          (is (= ::stream/consumed (:type (ex-data e))))))
      (testing "close! before reduce: the reduce returns init, the next one throws"
        (let [s (open port "/s")]
          (stream/close! s)
          (is (= :init (reduce conj :init s)))
          (is (= :cancelled (stream/outcome s)))
          (is (thrown? Exception (reduce conj [] s)))))
      #?(:bb nil
         :clj (testing "JVM: the stream is java.io.Closeable, so with-open releases an unreduced stream"
                (let [s (open port "/s")]
                  (with-open [^java.io.Closeable _ s])
                  (is (= [] (reduce conj [] s)))
                  (is (= :cancelled (stream/outcome s))))))
      (finally (stop!)))))

(deftest non-2xx-reads-the-body-as-a-string-before-any-stream
  (let [hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19145 "/s"
          (fn [_] (swap! hits inc)
            {:status 429 :headers {"retry-after" "7" "content-type" "application/json"}
             :body "{\"error\":{\"message\":\"slow down\"}}"}))]
    (try
      (testing "on-error receives the response with a String body and its throw wins"
        (let [seen (atom nil)
              e    (try (open port "/s" :on-error (fn [resp]
                                                    (reset! seen resp)
                                                    (throw (ex-info "typed" {:type :client/rate-limit}))))
                        nil
                        (catch Exception e e))]
          (is (= :client/rate-limit (:type (ex-data e))))
          (is (= 429 (:status @seen)))
          (is (= "7" (get-in @seen [:headers "retry-after"])))
          (is (= "{\"error\":{\"message\":\"slow down\"}}" (:body @seen)))))
      (testing "without on-error (or when it returns) ::http-error is thrown"
        (let [e (try (open port "/s" :on-error (fn [_] :ignored)) nil (catch Exception e e))]
          (is (= ::stream/http-error (:type (ex-data e))))
          (is (= 429 (:status (ex-data e))))
          (is (string? (:body (ex-data e))))))
      (finally (stop!)))))

(deftest open!-retries-before-the-first-byte-only
  (let [hits (atom 0)
        {:keys [port stop!]}
        (start-server! 19146 "/s"
          (fn [_]
            (if (= 1 (swap! hits inc))
              {:status 503 :body "overloaded"}
              {:status 200 :headers sse-headers :body (fn [send!] (send! "data: ok\n\n"))})))]
    (try
      (let [statuses (atom [])
            retry    (fn [attempt]
                       (loop [n 0]
                         (let [resp (attempt)]
                           (swap! statuses conj [(:status resp) (string? (:body resp))])
                           (if (and (= 503 (:status resp)) (< n 2)) (recur (inc n)) resp))))
            s        (open port "/s" :open! retry)]
        (is (= [[503 true] [200 false]] @statuses)
            "the retried non-2xx body was already read into a String; the 2xx body is an unread stream")
        (is (= ["ok"] (mapv :data (into [] s))))
        (is (= 2 @hits)))
      (finally (stop!)))))

(deftest transport-failure-propagates-from-open
  (let [attempts (atom 0)
        e (try (stream/open-event-stream
                {:request {:method :get :url "http://127.0.0.1:1/"}
                 :open!   (fn [attempt]
                            (try (swap! attempts inc) (attempt)
                                 (catch java.io.IOException _ (swap! attempts inc) (attempt))))})
               nil
               (catch Exception e e))]
    (is (instance? java.io.IOException e))
    (is (= 2 @attempts))))

(deftest decode-and-done?
  (let [{:keys [port stop!]}
        (start-server! 19147 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!]
                           (send! "data: {\"n\":1}\n\n")
                           (send! "data: {\"n\":2}\r\n\r\n")
                           (send! "data: [DONE]\n\n")
                           (send! "data: {\"n\":3}\n\n"))}))]
    (try
      (let [decoded (atom [])
            s       (open port "/s"
                          :decode (fn [d] (swap! decoded conj d) (json/read-json d))
                          :done?  #(= "[DONE]" (:data %)))]
        (is (= [{"n" 1} {"n" 2}] (mapv :data (into [] s))))
        (is (= :done (stream/outcome s)))
        (is (= ["{\"n\":1}" "{\"n\":2}"] @decoded)
            "done? sees the raw event: [DONE] never reaches the decoder"))
      (testing ":xform shapes events after decoding"
        (let [s (open port "/s" :decode json/read-json :done? #(= "[DONE]" (:data %))
                      :xform (map #(get-in % [:data "n"])))]
          (is (= 3 (reduce + 0 s)))))
      (testing "a decode failure propagates and closes the stream"
        (let [s (open port "/s" :decode json/read-json)
              e (try (into [] s) nil (catch Exception e e))]
          (is (some? e))
          (is (= :failed (stream/outcome s)))))
      (finally (stop!)))))

(deftest eof-without-a-terminal-event-is-reported-not-thrown
  (let [{:keys [port stop!]}
        (start-server! 19148 "/s"
          (fn [_] {:status 200 :headers sse-headers
                   :body (fn [send!] (send! "data: 1\n\ndata: incomplete"))}))]
    (try
      (let [s (open port "/s" :done? #(= "[DONE]" (:data %)))]
        (is (= ["1"] (mapv :data (into [] s))) "the unterminated final event is dropped")
        (is (= :eof (stream/outcome s)) "truncation is visible to the caller as :eof, not :done"))
      (finally (stop!)))))

(deftest mid-stream-disconnect-throws-from-reduce
  (let [{:keys [port stop!]}
        (start-abort-server! 19149 {:headers sse-headers
                                    :chunks  ["data: 1\n\n" "data: 2\n\n"]
                                    :delay-ms 50})]
    (try
      (testing "the IOException propagates after the events that did arrive"
        (let [s    (open port "/s")
              seen (atom [])
              e    (try (reduce (fn [_ ev] (swap! seen conj (:data ev))) nil s) nil
                        (catch Exception e e))]
          (is (instance? java.io.IOException e))
          (is (= ["1" "2"] @seen))
          (is (= :failed (stream/outcome s)))))
      (testing ":on-read-error maps it to the client's own error"
        (let [s (open port "/s" :on-read-error (fn [ex] (ex-info "conn" {:type :client/connection} ex)))
              e (try (into [] s) nil (catch Exception e e))]
          (is (= :client/connection (:type (ex-data e))))
          (is (instance? java.io.IOException (ex-cause e)))))
      (finally (stop!)))))
