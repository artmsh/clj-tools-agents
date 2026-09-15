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
   the response ends when the fn returns. A response header whose value is a
   vector is sent once per element.

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
                          (hk/as-channel req
                            {:on-open (fn [ch]
                                        (hk/send! ch {:status (:status resp) :headers (:headers resp)} false)
                                        (future
                                          (try ((:body resp) (fn [chunk] (hk/send! ch (chunk-bytes chunk) false)))
                                               (finally (hk/close ch)))))})
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
                     (with-open [os (.getResponseBody exchange)]
                       (body (fn [chunk] (.write os (chunk-bytes chunk)) (.flush os)))))
                 (let [resp-bytes (chunk-bytes (or body ""))]
                   (.sendResponseHeaders exchange status (count resp-bytes))
                   (with-open [os (.getResponseBody exchange)] (.write os resp-bytes))))))))
       (.setExecutor server nil)
       (.start server)
       {:port port :stop! (fn [] (.stop server 0))})))
