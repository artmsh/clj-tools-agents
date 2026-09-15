(ns tools.agents.test-support
  "The one runtime-specific piece the provider and http live-test suites
   share: a local mock HTTP server.

   Babashka uses org.httpkit.server — verified empirically that
   com.sun.net.httpserver.HttpServer is NOT resolvable under bb's native
   image, so this is a deliberate fallback, not a default choice. JVM Clojure
   uses com.sun.net.httpserver.HttpServer (built into the JDK, zero deps).
   Everything the suites assert above this leaf is identical handler logic
   and identical assertions on both runtimes."
  (:require [clojure.string :as str]
            #?@(:bb [[org.httpkit.server :as hk]] :clj [])))

(defn- chunk-bytes ^bytes [chunk]
  (if (bytes? chunk) chunk (.getBytes (str chunk) "UTF-8")))

(defn start-server!
  "Bind `port` and answer requests with `handler`, a
   (fn [{:keys [method path query headers body body-bytes]}]) -> {:status :headers :body}.
   Headers, in and out, are string-keyed with lower-case names. `query` is the
   raw query string with no leading `?` (nil when the request has none) —
   both runtimes strip it from `path` (java.net.URI/.getPath and httpkit's
   `:uri` both exclude it), so a suite asserting `?limit=20`-style params
   needs this key rather than `path`. `body` is the request body decoded as
   UTF-8; `body-bytes` is the same body as raw bytes. Returns
   {:port port :stop! (fn [])}.

   The response `:body` may be a String (sent as UTF-8), a byte[], or a
   STREAMING fn (fn [send!]): status and headers go out first, each
   (send! chunk) — a String or byte[] — is written and flushed at once, and
   the response ends cleanly (terminating chunk) when the fn returns. The fn
   controls pacing itself (sleep, or deref a promise the test delivers).
   `send!` returns true when the chunk was written and false once the client
   has disconnected (JVM: the write threw IOException; bb: httpkit's
   on-close fired or send! failed), so a handler can loop until the client
   goes away and record when that happened. For a response cut off
   MID-STREAM, with no terminating chunk, use `start-abort-server!`. A
   response header whose value is a vector is sent once per element.

   `route` is the path the mock answers on. The two branches treat it
   differently and deliberately: httpkit answers on every path and ignores
   it, while `.createContext` PREFIX-matches, so a route of \"/v1/messages\"
   also serves \"/v1/messages/count_tokens\". Every suite here asserts the
   request's own `:path`, so a mock that over-answers cannot hide a
   URL-building regression."
  [port route handler]
  #?(:bb
     (let [server (hk/run-server
                    (fn [req]
                      (let [bs   (if (:body req)
                                   (.readAllBytes ^java.io.InputStream (:body req))
                                   (byte-array 0))
                            resp (handler {:method (str/upper-case (name (:request-method req))) :path (:uri req)
                                           :query (:query-string req)
                                           :headers (:headers req) :body (String. ^bytes bs "UTF-8")
                                           :body-bytes bs})]
                        (if (fn? (:body resp))
                          (let [gone (atom false)]
                            (hk/as-channel req
                              {:on-close (fn [_ _] (reset! gone true))
                               :on-open  (fn [ch]
                                           (hk/send! ch {:status (:status resp) :headers (:headers resp)} false)
                                           (future
                                             (try ((:body resp) (fn [chunk]
                                                                  (and (not @gone)
                                                                       (boolean (hk/send! ch (chunk-bytes chunk) false)))))
                                                  (finally (hk/close ch)))))}))
                          resp)))
                    {:port port :legacy-return-value? false})]
       {:port port :stop! (fn [] (hk/server-stop! server))})

     :clj
     (let [server (com.sun.net.httpserver.HttpServer/create
                    (java.net.InetSocketAddress. "127.0.0.1" port) 0)]
       (.createContext server ^String route
         (reify com.sun.net.httpserver.HttpHandler
           (handle [_ exchange]
             (let [bs      (.readAllBytes (.getRequestBody exchange))
                   headers (into {} (map (fn [[k vs]] [(str/lower-case k) (first vs)])
                                         (.getRequestHeaders exchange)))
                   req     {:method (.getRequestMethod exchange)
                            :path (.getPath (.getRequestURI exchange))
                            :query (.getQuery (.getRequestURI exchange))
                            :headers headers :body (String. ^bytes bs "UTF-8") :body-bytes bs}
                   {:keys [status headers body]} (handler req)]
               (doseq [[k v] headers
                       v     (if (sequential? v) v [v])]
                 (.add (.getResponseHeaders exchange) k (str v)))
               (if (fn? body)
                 ;; Length 0 means chunked transfer; each flush sends a chunk.
                 (do (.sendResponseHeaders exchange status 0)
                     (let [os (.getResponseBody exchange)]
                       (try
                         (body (fn [chunk]
                                 (try (.write os (chunk-bytes chunk)) (.flush os) true
                                      (catch java.io.IOException _ false))))
                         (finally
                           (try (.close os) (catch java.io.IOException _ nil))))))
                 (let [resp-bytes (chunk-bytes (or body ""))]
                   (.sendResponseHeaders exchange status (count resp-bytes))
                   (with-open [os (.getResponseBody exchange)] (.write os resp-bytes))))))))
       (.setExecutor server nil)
       (.start server)
       {:port port :stop! (fn [] (.stop server 0))})))

(defn start-abort-server!
  "Raw-socket HTTP/1.1 server for a response that is cut off mid-stream,
   which neither httpkit nor com.sun.net.httpserver can produce (both always
   write the terminating chunk). Identical code on both runtimes.

   Every connection: read the request head (the body, if any, is ignored),
   write `status` and `headers` with `transfer-encoding: chunked`, write each
   String of `chunks` as one flushed chunk with `delay-ms` between them, then
   close the socket WITHOUT the terminating zero-length chunk. Returns
   {:port port :stop! (fn [])}."
  [port {:keys [status headers chunks delay-ms] :or {status 200 delay-ms 0}}]
  (let [ss      (java.net.ServerSocket. port 50 (java.net.InetAddress/getByName "127.0.0.1"))
        running (atom true)]
    (future
      (while @running
        (try
          (let [sock (.accept ss)]
            (future
              (with-open [sock sock]
                (let [rdr (java.io.BufferedReader.
                           (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
                      os  (.getOutputStream sock)]
                  (loop [] (when (seq (.readLine rdr)) (recur)))
                  (.write os (chunk-bytes
                              (str "HTTP/1.1 " status " X\r\n"
                                   (apply str (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                                   "transfer-encoding: chunked\r\n\r\n")))
                  (.flush os)
                  (doseq [c chunks]
                    (let [bs (chunk-bytes c)]
                      (.write os (chunk-bytes (str (Integer/toHexString (count bs)) "\r\n")))
                      (.write os bs)
                      (.write os (chunk-bytes "\r\n"))
                      (.flush os)
                      (when (pos? delay-ms) (Thread/sleep (long delay-ms)))))))))
          (catch java.io.IOException _ nil))))
    {:port port :stop! (fn [] (reset! running false) (.close ss))}))
