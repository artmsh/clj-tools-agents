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
            [tools.agents.json :as json]
            [tools.agents.token :as token]
            #?@(:bb [[org.httpkit.server :as hk]] :clj [])))

(defn- chunk-bytes ^bytes [chunk]
  (if (bytes? chunk) chunk (.getBytes (str chunk) "UTF-8")))

(defn closed-port
  "A loopback port with nothing listening, for tests that need a refused
   connection (or a base-url that must never be dialled): bind port 0, read
   the OS-assigned port, close the socket. A concurrent bind of port 0
   landing on the same just-freed ephemeral port within the test is
   possible but improbable; unlike a fixed port, it is never systematic.

   Every mock server here binds port 0 and the tests read `:port` back; no
   suite uses a fixed port, so parallel runs cannot collide."
  []
  (with-open [ss (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))]
    (.getLocalPort ss)))

(defn start-server!
  "Bind `port` and answer requests with `handler`, a
   (fn [{:keys [method path query headers body body-bytes]}]) -> {:status :headers :body}.
   Headers, in and out, are string-keyed with lower-case names. `query` is the
   raw query string with no leading `?` (nil when the request has none) —
   both runtimes strip it from `path` (java.net.URI/.getPath and httpkit's
   `:uri` both exclude it), so a suite asserting `?limit=20`-style params
   needs this key rather than `path`. `body` is the request body decoded as
   UTF-8; `body-bytes` is the same body as raw bytes. Returns
   {:port port :stop! (fn [])}; pass port 0 for an OS-assigned port, and
   :port is the bound one.

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
                    ;; loopback, like the JVM branch: on macOS a 0.0.0.0 bind can be handed a port
                    ;; another process holds on 127.0.0.1, which then answers our requests
                    {:ip "127.0.0.1" :port port :legacy-return-value? false})]
       {:port (hk/server-port server) :stop! (fn [] (hk/server-stop! server))})

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
       {:port (.getPort (.getAddress server)) :stop! (fn [] (.stop server 0))})))

(defn start-abort-server!
  "Raw-socket HTTP/1.1 server for a response that is cut off mid-stream,
   which neither httpkit nor com.sun.net.httpserver can produce (both always
   write the terminating chunk). Identical code on both runtimes.

   Every connection: read the request head and drain a Content-Length body,
   write `status` and `headers` with `transfer-encoding: chunked`, write each
   String of `chunks` as one flushed chunk with `delay-ms` between them, then
   close the socket WITHOUT the terminating zero-length chunk. Returns
   {:port port :stop! (fn [])}; pass port 0 for an OS-assigned port, and
   :port is the bound one."
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
                  ;; Drain the request body too: closing a socket with unread
                  ;; input sends RST, which can discard the chunks below
                  ;; before the client reads them.
                  (let [len (loop [len 0]
                              (let [line (.readLine rdr)]
                                (if (seq line)
                                  (recur (if-let [[_ n] (re-matches #"(?i)content-length:\s*(\d+)\s*" line)]
                                           (parse-long n)
                                           len))
                                  len)))]
                    (dotimes [_ len] (.read rdr)))
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
    {:port (.getLocalPort ss) :stop! (fn [] (reset! running false) (.close ss))}))

(defn start-stall-server!
  "Raw-socket HTTP/1.1 server on an OS-assigned port that stalls, for timeout
   tests. Identical code on both runtimes. Every connection reads the request
   head, then by `mode`:

     :no-headers  sends nothing and holds the socket open until the client
                  closes it (or `stop!`)
     :body        sends `status`/`headers` with `content-length` 1000 and
                  the first byte of the body, then stalls the same way
     :trickle     sends `status`/`headers` chunked, then each String of
                  `chunks` as one flushed chunk `delay-ms` apart, then the
                  terminating chunk

   Returns {:port :stop! :accepted atom (connections accepted) :closed atom
   (connections the client closed while the server stalled)}."
  [{:keys [mode status headers chunks delay-ms] :or {status 200 delay-ms 0}}]
  (let [ss       (java.net.ServerSocket. 0 50 (java.net.InetAddress/getByName "127.0.0.1"))
        running  (atom true)
        accepted (atom 0)
        closed   (atom 0)
        socks    (atom #{})
        head     (fn [extra]
                   (chunk-bytes (str "HTTP/1.1 " status " X\r\n"
                                     (apply str (map (fn [[k v]] (str k ": " v "\r\n")) headers))
                                     extra "\r\n")))]
    (future
      (while @running
        (try
          (let [sock (.accept ss)]
            (swap! accepted inc)
            (swap! socks conj sock)
            (future
              (try
                (with-open [sock sock]
                  (let [in  (.getInputStream sock)
                        rdr (java.io.BufferedReader. (java.io.InputStreamReader. in "UTF-8"))
                        os  (.getOutputStream sock)
                        ;; drain (and ignore) any request body until the client closes
                        hold! (fn [] (loop [] (if (= -1 (.read rdr)) (swap! closed inc) (recur))))]
                    (loop [] (when (seq (.readLine rdr)) (recur)))
                    (case mode
                      :no-headers (hold!)
                      :body       (do (.write os (head "content-length: 1000\r\n"))
                                      (.write os (chunk-bytes "x"))
                                      (.flush os)
                                      (hold!))
                      :trickle    (do (.write os (head "transfer-encoding: chunked\r\n"))
                                      (.flush os)
                                      (doseq [c chunks]
                                        (let [bs (chunk-bytes c)]
                                          (.write os (chunk-bytes (str (Integer/toHexString (count bs)) "\r\n")))
                                          (.write os bs)
                                          (.write os (chunk-bytes "\r\n"))
                                          (.flush os)
                                          (when (pos? delay-ms) (Thread/sleep (long delay-ms)))))
                                      (.write os (chunk-bytes "0\r\n\r\n"))
                                      (.flush os)
                                      (hold!)))))
                (catch java.io.IOException _ nil)
                (finally (swap! socks disj sock)))))
          (catch java.io.IOException _ nil))))
    {:port     (.getLocalPort ss)
     :accepted accepted
     :closed   closed
     :stop!    (fn []
                 (reset! running false)
                 (.close ss)
                 (doseq [^java.net.Socket s @socks] (try (.close s) (catch java.io.IOException _ nil))))}))

(defn sse-chunks
  "An SSE body split after each blank line, one String per event, for
   `start-stall-server!`'s :trickle mode."
  [^String body]
  (vec (remove empty? (str/split body #"(?<=\r?\n\r?\n)"))))

(defn recording-http
  "A fake `:http` request fn (tools.agents.http/request!'s contract) with no
   network: `respond` maps each request map to a {:status :headers :body}
   response, or throws to simulate a transport failure. Returns
   {:http f :calls atom}; :calls holds every request map in order."
  [respond]
  (let [calls (atom [])]
    {:calls calls
     :http  (fn [req] (swap! calls conj req) (respond req))}))

(defn recording-codec
  "A caller-supplied `:json` codec that is NOT the built-in one: it delegates
   to tools.agents.json but counts calls and prefixes every encoding with a
   space (still valid JSON), so a test can see its output on the wire.
   Returns {:json codec :reads atom :writes atom}."
  []
  (let [reads (atom 0) writes (atom 0)]
    {:reads  reads
     :writes writes
     :json   {:read  (fn [s] (swap! reads inc) (json/read-json s))
              :write (fn [v] (swap! writes inc) (str " " (json/write-json v)))}}))

(def throwing-codec
  "A `:json` codec whose fns throw untyped exceptions."
  {:read  (fn [_] (throw (IllegalStateException. "codec read boom")))
   :write (fn [_] (throw (IllegalStateException. "codec write boom")))})

(defn input-stream
  "A UTF-8 ByteArrayInputStream over s: a streamed response body."
  ^java.io.InputStream [^String s]
  (java.io.ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn rotating-token-cache
  "A tools.agents.token/token-cache whose fetch! returns \"tok-1\",
   \"tok-2\", ... that never expire, so only invalidate! causes a refetch.
   Returns {:source :fetches}; :fetches is an atom counting fetch! calls."
  []
  (let [fetches (atom 0)]
    {:fetches fetches
     :source  (token/token-cache {:fetch! (fn [] {:token (str "tok-" (swap! fetches inc)) :expires-at nil})})}))

(defn per-call-token-source
  "A TokenSource returning a new token (\"call-1\", \"call-2\", ...) on every
   token! call and declining invalidate!: shows that a client asks for
   credentials per attempt, and that a declined invalidate! means no 401 retry."
  []
  (let [n (atom 0)]
    (reify token/TokenSource
      (-token [_] (str "call-" (swap! n inc)))
      (-invalidate [_ _] false))))
