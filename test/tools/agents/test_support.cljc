(ns tools.agents.test-support
  "The one runtime-specific piece the three provider live-test suites share: a
   local mock HTTP server.

   Babashka uses org.httpkit.server — verified empirically that
   com.sun.net.httpserver.HttpServer is NOT resolvable under bb's native
   image, so this is a deliberate fallback, not a default choice. JVM Clojure
   uses com.sun.net.httpserver.HttpServer (built into the JDK, zero deps).
   Everything the suites assert above this leaf is identical handler logic
   and identical assertions on both runtimes."
  (:require [clojure.string :as str]
            #?@(:bb [[org.httpkit.server :as hk]] :clj [])))

(defn start-server!
  "Bind `port` and answer requests with `handler`, a
   (fn [{:keys [method path headers body]}]) -> {:status :headers :body}.
   Headers, in and out, are string-keyed with lower-case names. Returns
   {:port port :stop! (fn [])}.

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
                      (let [body (when (:body req) (slurp (:body req)))]
                        (handler {:method (str/upper-case (name (:request-method req))) :path (:uri req)
                                  :headers (:headers req) :body (or body "")})))
                    {:port port :legacy-return-value? false})]
       {:port port :stop! (fn [] (hk/server-stop! server))})

     :clj
     (let [server (com.sun.net.httpserver.HttpServer/create
                    (java.net.InetSocketAddress. "127.0.0.1" port) 0)]
       (.createContext server ^String route
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
