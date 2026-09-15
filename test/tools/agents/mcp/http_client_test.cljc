(ns tools.agents.mcp.http-client-test
  "`http/connect!` over a real socket, identical on both runtimes: the
   shared mock server (tools.agents.test-support) plays the MCP endpoint, so
   the headers asserted are the ones that crossed the wire and the SSE
   events are delivered chunk by chunk, with the server blocking between
   them until the test has seen what it sent.

   Ports are ephemeral (asked of the OS per test), not a fixed range."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.client :as client]
            [tools.agents.mcp.http :as http]
            [tools.agents.mcp.server :as server]
            [tools.agents.test-support :refer [start-server! start-abort-server!]]))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)] (.getLocalPort s)))

(defn- url [port] (str "http://127.0.0.1:" port "/mcp"))

(def ^:private sse-headers {"content-type" "text/event-stream"})

(defn- serve
  "Start the mock on a free port; `handler` gets the request map with its
   body already JSON-decoded under :message."
  [handler]
  (let [port (free-port)]
    (start-server! port "/mcp"
                   (fn [req] (handler (assoc req :message (mcp/read-json (:body req))))))))

(defn- until-gone!
  "Send SSE keep-alive comments every 10 ms until the client disconnects,
   then deliver `gone` true. Gives up after ~5 s (delivers false)."
  [send! gone]
  (loop [i 0]
    (cond
      (not (send! ": keep-alive\n\n")) (deliver gone true)
      (> i 500)                        (deliver gone false)
      :else                            (do (Thread/sleep 10) (recur (inc i))))))

(defn- accepted [] {:status 202 :headers {} :body ""})

(defn- a-client [transport]
  (client/client {:name "http-client-test" :send! (:send! transport)}))

;; ---------------------------------------------------------------------------
;; JSON answers: the request! wiring #20 moved onto the shared HTTP function
;; ---------------------------------------------------------------------------

(def srv
  (server/server {:name "wire"
                  :tools [{:name "echo" :handler (fn [_ctx args] (get args "m" "ok"))}]}))

(deftest json-answers-round-trip-with-mirrored-headers-on-the-wire
  (let [seen (atom [])
        {:keys [port stop!]}
        (serve (fn [req]
                 (swap! seen conj req)
                 (http/handle-http srv {:request-method :post :headers (:headers req) :body (:body req)})))]
    (try
      (let [t (http/connect! (url port) {:headers {"X-Extra" "1"}})
            c (a-client t)]
        (testing "a request answered with application/json"
          (is (= "hi" (mcp/output-text (client/call-tool! c "echo" {"m" "hi"}))))
          (let [{:keys [method path headers]} (last @seen)]
            (is (= ["POST" "/mcp"] [method path]))
            (is (= "tools/call" (get headers "mcp-method")))
            (is (= "echo" (get headers "mcp-name")))
            (is (= mcp/latest-protocol-version (get headers "mcp-protocol-version")))
            (is (= "application/json, text/event-stream" (get headers "accept")))
            (is (= "1" (get headers "x-extra")) "caller headers are merged in")))
        (testing "a JSON-RPC error on a 4xx is still the error response, typed by the client"
          (let [e (try (client/call! c "no/such/method") nil (catch Exception e e))]
            (is (= :tools.agents.mcp.error/method-not-found (:type (ex-data e))))))
        (testing "a notification is 202 Accepted and returns nil"
          (is (nil? ((:send! t) (mcp/cancelled-notification 1 "never mind"))))
          (is (= "notifications/cancelled" (get-in (last @seen) [:headers "mcp-method"])))))
      (finally (stop!)))))

(deftest an-empty-body-is-a-transport-error
  (let [{:keys [port stop!]} (serve (fn [_] {:status 500 :headers {} :body ""}))]
    (try
      (let [e (try ((:send! (http/connect! (url port))) (mcp/request 1 "tools/list" {})) nil
                   (catch Exception e e))]
        (is (= :tools.agents.mcp.error/transport (:type (ex-data e))))
        (is (= 500 (get-in (ex-data e) [:response :status]))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; SSE answers are read live
;; ---------------------------------------------------------------------------

(deftest a-notification-is-delivered-before-the-response-arrives
  (let [observed   (promise)
        server-saw (promise)
        {:keys [port stop!]}
        (serve (fn [{:keys [message]}]
                 (let [id (get message "id")]
                   {:status 200 :headers sse-headers
                    :body (fn [send!]
                            (send! ": keep-alive\n\n")
                            (send! (http/sse-event (mcp/notification "notifications/progress"
                                                                     {"progressToken" "p" "progress" 1})))
                            ;; Hold the response until the client has handed
                            ;; the notification to :on-notification.
                            (Thread/sleep 300)
                            (deliver server-saw (deref observed 5000 :timeout))
                            (send! (http/sse-event (mcp/result-response id (mcp/tool-result [(mcp/text "done")]))))
                            ;; Anything after the response is never read.
                            (send! (http/sse-event (mcp/notification "notifications/late" {}))))})))]
    (try
      (let [notes (atom [])
            t (http/connect! (url port) {:on-notification (fn [m]
                                                            (swap! notes conj (get m "method"))
                                                            (deliver observed :seen))})
            res (client/call-tool! (a-client t) "noisy")]
        (is (= :seen @server-saw) "the notification reached the client while the server held the response")
        (is (= ["notifications/progress"] @notes))
        (is (= "done" (mcp/output-text res))))
      (finally (deliver observed :cleanup) (stop!)))))

(deftest a-stream-that-ends-before-responding-is-a-transport-error
  (let [{:keys [port stop!]}
        (serve (fn [_] {:status 200 :headers sse-headers
                        :body (fn [send!] (send! (http/sse-event (mcp/notification "notifications/x" {}))))}))]
    (try
      (let [notes (atom [])
            t (http/connect! (url port) {:on-notification #(swap! notes conj (get % "method"))})
            e (try ((:send! t) (mcp/request 3 "tools/list" {})) nil (catch Exception e e))]
        (is (= :tools.agents.mcp.error/transport (:type (ex-data e))))
        (is (= ["notifications/x"] @notes)))
      (finally (stop!)))))

(deftest a-stream-cut-off-mid-body-is-a-transport-error
  (let [port (free-port)
        {:keys [stop!]} (start-abort-server! port {:headers sse-headers
                                                   :chunks [(http/sse-event (mcp/notification "notifications/x" {}))]})]
    (try
      (let [e (try ((:send! (http/connect! (url port))) (mcp/request 3 "tools/list" {})) nil
                   (catch Exception e e))]
        (is (= :tools.agents.mcp.error/transport (:type (ex-data e))))
        (is (instance? java.io.IOException (ex-cause e))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; subscriptions/listen
;; ---------------------------------------------------------------------------

(defn- listen-server
  "A listen endpoint: acknowledgment, then `after-ack` drives the rest.
   A `notifications/cancelled` POST is accepted and recorded."
  [cancels after-ack]
  (serve (fn [{:keys [message]}]
           (if (mcp/notification? message)
             (do (swap! cancels conj message) (accepted))
             (let [sub {:id (get message "id") :filter {"toolsListChanged" true}}]
               {:status 200 :headers sse-headers
                :body (fn [send!]
                        (send! (http/sse-event
                                (mcp/with-subscription-id
                                 (mcp/notification "notifications/subscriptions/acknowledged"
                                                   {"notifications" (:filter sub)})
                                 (:id sub))))
                        (after-ack send! sub))})))))

(deftest listen-delivers-notifications-live-then-closes-gracefully
  (let [acked    (promise)
        changed  (promise)
        {:keys [port stop!]}
        (listen-server (atom [])
                       (fn [send! sub]
                         (deref acked 5000 nil)
                         (send! (http/sse-event (server/subscription-notification
                                                 sub (mcp/notification "notifications/tools/list_changed" {}))))
                         (deref changed 5000 nil)
                         (send! (http/sse-event (server/close-subscription (:id sub))))))]
    (try
      (let [notes (atom [])
            t (http/connect! (url port)
                             {:on-notification (fn [m]
                                                 (swap! notes conj (get m "method"))
                                                 (case (get m "method")
                                                   "notifications/subscriptions/acknowledged" (deliver acked true)
                                                   "notifications/tools/list_changed" (deliver changed true)
                                                   nil))})
            c (a-client t)
            req (client/listen! c {"toolsListChanged" true})
            fut (future ((:send! t) req))]
        (is (true? (deref acked 5000 :timeout)) "acknowledgment delivered while the stream is open")
        (is (true? (deref changed 5000 :timeout)) "change notification delivered while the stream is open")
        (let [res (deref fut 5000 :timeout)]
          (is (= (get req "id") (get res "id")) "the graceful-closure response ends the listen")
          (is (= {} (dissoc (client/result-of res) "_meta" "resultType"))))
        (is (= ["notifications/subscriptions/acknowledged" "notifications/tools/list_changed"] @notes)))
      (finally (deliver acked true) (deliver changed true) (stop!)))))

(deftest cancelling-a-listen-closes-its-connection
  (testing "client/cancel! POSTs notifications/cancelled and closes the stream it names"
    (let [cancels (atom [])
          acked   (promise)
          gone    (promise)
          {:keys [port stop!]} (listen-server cancels (fn [send! _] (until-gone! send! gone)))]
      (try
        (let [t   (http/connect! (url port) {:on-notification (fn [_] (deliver acked true))})
              c   (a-client t)
              req (client/listen! c {"toolsListChanged" true})
              fut (future ((:send! t) req))]
          (is (true? (deref acked 5000 :timeout)))
          (is (not (realized? fut)))
          (client/cancel! c (get req "id") "done")
          (is (nil? (deref fut 5000 :timeout)) "a cancelled send! returns nil")
          (is (true? (deref gone 5000 :timeout)) "the server observed the disconnect")
          (is (= [(get req "id")] (map #(get-in % ["params" "requestId"]) @cancels))))
        (finally (stop!)))))
  (testing ":close! closes every in-flight stream"
    (let [acked (promise)
          gone  (promise)
          {:keys [port stop!]} (listen-server (atom []) (fn [send! _] (until-gone! send! gone)))]
      (try
        (let [t   (http/connect! (url port) {:on-notification (fn [_] (deliver acked true))})
              fut (future ((:send! t) (client/listen! (a-client t) {"toolsListChanged" true})))]
          (is (true? (deref acked 5000 :timeout)))
          ((:close! t))
          (is (nil? (deref fut 5000 :timeout)))
          (is (true? (deref gone 5000 :timeout)) "the server observed the disconnect"))
        (finally (stop!))))))

;; ---------------------------------------------------------------------------
;; Injected :http (#10): no socket, the fake hosts handle-http in-process
;; ---------------------------------------------------------------------------

(defn- lower-keys [m] (into {} (map (fn [[k v]] [(clojure.string/lower-case (str k)) v])) m))

(deftest injected-http-carries-every-post
  (let [calls (atom [])
        fake  (fn [req]
                (swap! calls conj req)
                (let [r (http/handle-http srv {:request-method :post :headers (lower-keys (:headers req))
                                               :body (:body req)})]
                  ;; a String body: connect! reads it as the stream
                  (update r :headers lower-keys)))
        t (http/connect! "https://mcp.fake/mcp" {:http fake})
        c (a-client t)]
    (is (= "hi" (mcp/output-text (client/call-tool! c "echo" {"m" "hi"}))))
    (is (every? #(= "https://mcp.fake/mcp" (:url %)) @calls))
    (is (every? #(= :stream (:as %)) @calls))
    (is (= "tools/call" (get-in (last @calls) [:headers "Mcp-Method"])))))

(deftest injected-http-sse-answer-is-read
  (let [notes (atom [])
        fake  (fn [req]
                (let [id (get (mcp/read-json (:body req)) "id")]
                  {:status 200 :headers sse-headers
                   :body (java.io.ByteArrayInputStream.
                          (.getBytes (str (http/sse-event (mcp/notification "notifications/progress" {"progress" 1}))
                                          (http/sse-event (mcp/result-response id (mcp/tool-result [(mcp/text "done")]))))
                                     "UTF-8"))}))
        t (http/connect! "https://mcp.fake/mcp" {:http fake :on-notification #(swap! notes conj (get % "method"))})]
    (is (= "done" (mcp/output-text (client/call-tool! (a-client t) "noisy"))))
    (is (= ["notifications/progress"] @notes))))

(deftest invalid-http-option-is-rejected
  (let [e (try (http/connect! "https://mcp.fake/mcp" {:http "nope"}) nil (catch Exception e e))]
    (is (= :tools.agents.mcp.error/invalid-options (:type (ex-data e))))))
