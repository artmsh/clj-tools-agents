(ns tools.agents.http
  "The one HTTP request function every client in this repo uses.

   `request!` performs exactly one HTTP exchange. It does not retry, classify
   statuses or parse bodies: each client (anthropic, openai, openai.agents,
   gemini, mcp.http) keeps its own error typing on top of it, and the
   retrying clients run their attempts through tools.agents.retry.

   ONE CODE PATH, BOTH RUNTIMES: this is plain java.net.http interop, with no
   reader conditional. Babashka exposes the same HttpClient, HttpRequest,
   BodyPublishers and BodyHandlers classes that JVM Clojure uses, so both
   runtimes send identical bytes, including multipart bodies, and stream
   response bodies the same way.

   HTTP/1.1 IS PINNED, on the client and on every request. For a cleartext
   http:// URL, java.net.http's HTTP/2 default sends the HTTP/2 preface with
   no h2c negotiation, and a plain HTTP/1.1 reverse proxy (observed: Caddy in
   front of an LLM gateway) answers every request with an empty 502. Over
   https:// ALPN would negotiate safely, but the pin keeps both runtimes on
   one wire protocol, and these clients send one request at a time.

   TIMEOUTS default to the official SDKs' values (openai-python and
   anthropic-sdk-python: `httpx.Timeout(timeout=600, connect=5.0)`):
   `default-connect-timeout-ms` 5 s and `default-timeout-ms` 600 s. A request
   map key that is ABSENT gets the default; an explicit nil disables that
   timeout. Semantics, identical on both runtimes:

     :connect-timeout-ms  java.net.http HttpClient connectTimeout (a
                          HttpConnectTimeoutException). Per HttpClient, so
                          request! keeps one shared client per value.
     :timeout-ms          a deadline from send until `.send`'s response is
                          complete: the WHOLE response, body included, for
                          :as :string/:bytes; only until the response HEADERS
                          for :as :stream. A stream's body is never cut by it,
                          so a long SSE stream (or an MCP subscriptions/listen)
                          may run for hours; bound a stalled stream with
                          tools.agents.stream/close! from a watchdog.

   The deadline is NOT HttpRequest.timeout: its scope differs by JDK version.
   Verified empirically: on JDK 26 (JVM Clojure) it also covers the body read,
   so an `ofInputStream` stream is closed mid-body when it fires, while
   Babashka's native image stops it at the headers. Instead request! uses
   `sendAsync` plus a bounded `CompletableFuture.get`, which completes at the
   headers for ofInputStream and after the body otherwise, on both runtimes;
   on expiry the future is cancelled, which aborts the exchange and closes
   its connection, and java.net.http.HttpTimeoutException is thrown.

   httpx's 600 s is a per-read idle timeout (read, write and pool each), not a
   total; the JDK has no idle-read timeout, so the non-streaming mapping is a
   total-exchange deadline and the streaming one a header deadline.

   RESPONSE HEADERS are normalized the same way on both runtimes: names are
   lower-cased, a header sent once is a String, and a header sent more than
   once is a vector of Strings in arrival order."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Query strings — OpenAI-SDK-style bracket encoding
;; ---------------------------------------------------------------------------

(defn url-encode
  "application/x-www-form-urlencoded encoding of one key or value, UTF-8.
   Keywords and symbols encode via `name` (verbatim, no case conversion), so
   `:desc` encodes as `desc`, not `:desc`. Anything else encodes via `str`."
  [v]
  (java.net.URLEncoder/encode (if (or (keyword? v) (symbol? v)) (name v) (str v)) "UTF-8"))

(def ^:private path-safe
  "RFC 3986 §3.3 pchar minus pct-encoded: unreserved, sub-delims, `:`, `@`."
  (set (str "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            "!$&'()*+,;=:@")))

(defn encode-path-segment
  "Percent-encode one URL path segment exactly as openai-python's
   `_quote_path_segment_part` (`_utils/_path.py`: `quote(value,
   safe=\"!$&'()*+,;=:@\")`): RFC 3986 pchar characters stay literal, every
   other UTF-8 byte becomes `%XX` (upper-case hex). Unlike `url-encode`
   (form encoding) a space is `%20`, not `+`, and `:` stays literal, so
   `ft:gpt-4o-mini:org::ckpt-step-1` is sent verbatim while `/`, `?`, `#`
   and `%` cannot reroute the request. Keywords encode via `name`."
  [v]
  (let [s  (if (or (keyword? v) (symbol? v)) (name v) (str v))
        sb (StringBuilder.)]
    (doseq [b (.getBytes ^String s "UTF-8")]
      (let [u (bit-and (int b) 0xff)
            c (char u)]
        (if (and (< u 128) (contains? path-safe c))
          (.append sb c)
          (.append sb (str "%" (str/upper-case (format "%02x" u)))))))
    (str sb)))

(defn- param-name [k]
  (if (or (keyword? k) (symbol? k)) (name k) (str k)))

(defn flatten-params
  "Flatten a params map into [name value] string pairs with bracket nesting,
   the scheme openai-python pins (`Querystring(array_format=\"brackets\")`,
   also used for its multipart fields):

     {\"metadata\" {\"k\" \"v\"}}  -> [[\"metadata[k]\" \"v\"]]
     {\"image\" [\"a\" \"b\"]}     -> [[\"image[]\" \"a\"] [\"image[]\" \"b\"]]
     {\"stream\" true}           -> [[\"stream\" \"true\"]]

   nil values and values that render as \"\" are dropped, as in the SDK.
   Keys and keyword values render via `name`."
  [params]
  (letfn [(item [k v]
            (cond
              (map? v)        (mapcat (fn [[sk sv]] (item (str k "[" (param-name sk) "]") sv)) v)
              (sequential? v) (mapcat #(item (str k "[]") %) v)
              :else           (let [s (cond (nil? v) ""
                                            (or (keyword? v) (symbol? v)) (name v)
                                            :else (str v))]
                                (if (= "" s) [] [[k s]]))))]
    (vec (mapcat (fn [[k v]] (item (param-name k) v)) params))))

(defn encode-params
  "URL-encoded query string for `params` (no leading `?`), bracket-nested as
   in `flatten-params`, or nil when nothing remains to encode."
  [params]
  (let [pairs (flatten-params params)]
    (when (seq pairs)
      (str/join "&" (map (fn [[k v]] (str (url-encode k) "=" (url-encode v))) pairs)))))

;; ---------------------------------------------------------------------------
;; Multipart — pure chunk rendering, streamed on the wire
;; ---------------------------------------------------------------------------

(def ^:dynamic *boundary-fn*
  "Zero-arg fn returning a fresh multipart boundary. Rebind it for golden
   tests that compare exact bytes."
  (fn [] (str "tools-agents-" (str/replace (str (random-uuid)) "-" ""))))

(defn- utf8 ^bytes [^String s] (.getBytes s "UTF-8"))

(defn- escape-disposition
  "Quote-safe `name`/`filename` parameter value, per the HTML form-data
   encoding RFC 7578 §4.2 refers to: `\"` -> %22, CR -> %0D, LF -> %0A.
   Other characters, non-ASCII included, are sent as raw UTF-8."
  [s]
  (-> (str s)
      (str/replace "\"" "%22")
      (str/replace "\r" "%0D")
      (str/replace "\n" "%0A")))

(defn- file-name-of [content]
  (cond
    (instance? java.io.File content)     (.getName ^java.io.File content)
    (instance? java.nio.file.Path content) (str (.getFileName ^java.nio.file.Path content))
    :else nil))

(defn multipart-chunks
  "Render `parts` as a vector of body chunks: byte arrays for the framing and
   in-memory content, and the File, Path or InputStream content objects
   themselves, so large files are streamed from disk rather than loaded.

   Each part is {:name n :content c :filename f :content-type t}:
     :content      String (sent as UTF-8), byte[], java.io.File,
                   java.nio.file.Path or java.io.InputStream
     :filename     optional; defaults to the file's own name for File/Path
     :content-type optional; defaults to application/octet-stream when the
                   part has a filename, and is omitted otherwise

   Names are sent verbatim, so repeated names (`image[]` twice) and
   bracket-style names (`metadata[k]`) are just ordinary parts. Adjacent
   byte chunks are merged."
  [parts boundary]
  (let [out    (java.io.ByteArrayOutputStream.)
        chunks (transient [])
        flush! (fn []
                 (when (pos? (.size out))
                   (conj! chunks (.toByteArray out))
                   (.reset out)))
        write! (fn [^bytes b] (.write out b 0 (alength b)))]
    (doseq [{:keys [content filename content-type] :as part} parts]
      (let [filename     (or filename (file-name-of content))
            content-type (or content-type (when filename "application/octet-stream"))]
        (write! (utf8 (str "--" boundary "\r\n"
                           "Content-Disposition: form-data; name=\"" (escape-disposition (:name part)) "\""
                           (when filename (str "; filename=\"" (escape-disposition filename) "\""))
                           "\r\n"
                           (when content-type (str "Content-Type: " content-type "\r\n"))
                           "\r\n")))
        (cond
          (string? content) (write! (utf8 content))
          (bytes? content)  (write! content)
          (or (instance? java.io.File content)
              (instance? java.nio.file.Path content)
              (instance? java.io.InputStream content))
          (do (flush!) (conj! chunks content))
          :else (throw (ex-info (str "tools.agents.http/multipart-chunks: unsupported :content for part "
                                     (pr-str (:name part)) ": " (pr-str (type content)))
                                {:type :tools.agents.http/invalid-request})))
        (write! (utf8 "\r\n"))))
    (write! (utf8 (str "--" boundary "--\r\n")))
    (flush!)
    (persistent! chunks)))

;; ---------------------------------------------------------------------------
;; java.net.http plumbing
;; ---------------------------------------------------------------------------

(defn- ->path ^java.nio.file.Path [x]
  (if (instance? java.io.File x) (.toPath ^java.io.File x) x))

(defn- publisher
  "BodyPublisher for one body value. A String, byte[], File or Path body
   has a known length (sent with Content-Length); an InputStream body is sent
   chunked and can be read only once."
  [body]
  (cond
    (nil? body)    (java.net.http.HttpRequest$BodyPublishers/noBody)
    (string? body) (java.net.http.HttpRequest$BodyPublishers/ofString body)
    (bytes? body)  (java.net.http.HttpRequest$BodyPublishers/ofByteArray body)
    (or (instance? java.io.File body) (instance? java.nio.file.Path body))
    (java.net.http.HttpRequest$BodyPublishers/ofFile (->path body))
    (instance? java.io.InputStream body)
    (java.net.http.HttpRequest$BodyPublishers/ofInputStream
     (reify java.util.function.Supplier (get [_] body)))
    :else (throw (ex-info (str "tools.agents.http/request!: unsupported :body type " (pr-str (type body)))
                          {:type :tools.agents.http/invalid-request}))))

(defn- multipart-publisher
  "BodyPublishers/concat over the rendered chunks. concat keeps a known
   Content-Length as long as every chunk has one; an InputStream part makes
   the whole body chunked."
  [parts boundary]
  (java.net.http.HttpRequest$BodyPublishers/concat
   (into-array java.net.http.HttpRequest$BodyPublisher
               (map publisher (multipart-chunks parts boundary)))))

(def default-timeout-ms
  "Default `:timeout-ms`: 600 s, openai-python's and anthropic-sdk-python's
   `DEFAULT_TIMEOUT = httpx.Timeout(timeout=600, connect=5.0)`."
  600000)

(def default-connect-timeout-ms
  "Default `:connect-timeout-ms`: 5 s, the same SDKs' `connect=5.0`."
  5000)

(defn timeout-option?
  "True for a valid `:timeout-ms` / `:connect-timeout-ms` value: nil
   (disabled) or a positive number of milliseconds (truncated to a long)."
  [x]
  (or (nil? x) (and (number? x) (pos? x))))

(defn timeout-opts
  "The two timeout keys resolved from an options map: a key present in `opts`
   keeps its value (nil disables), an absent key gets the default. Clients
   merge this into their record at construction."
  [opts]
  {:timeout-ms         (if (contains? opts :timeout-ms) (:timeout-ms opts) default-timeout-ms)
   :connect-timeout-ms (if (contains? opts :connect-timeout-ms) (:connect-timeout-ms opts) default-connect-timeout-ms)})

(defn with-timeouts
  "req with :timeout-ms and :connect-timeout-ms set from `overrides` when it
   has the key (a per-request override, nil included), else from `client`,
   else the defaults. Every client passes both keys on every request map, so
   an injected `:http` fn sees the effective values."
  ([req client] (with-timeouts req client nil))
  ([req client overrides]
   (let [pick (fn [k default]
                (cond (contains? overrides k) (get overrides k)
                      (and client (contains? client k)) (get client k)
                      :else default))]
     (assoc req
            :timeout-ms (pick :timeout-ms default-timeout-ms)
            :connect-timeout-ms (pick :connect-timeout-ms default-connect-timeout-ms)))))

(defn timeout-exception?
  "True when `e`, or any exception in its cause chain, is a transport timeout:
   java.net.http.HttpTimeoutException (HttpConnectTimeoutException included)
   or java.net.SocketTimeoutException, the latter for injected `:http` fns
   built on socket-level clients."
  [e]
  (boolean (some #(or (instance? java.net.http.HttpTimeoutException %)
                      (instance? java.net.SocketTimeoutException %))
                 (take-while some? (iterate #(.getCause ^Throwable %) e)))))

(defn client
  "Build a java.net.http.HttpClient pinned to HTTP/1.1. opts:
     :connect-timeout-ms  connect timeout, default `default-connect-timeout-ms`
                          (5 s); nil disables it
   Pass the result as `request!`'s `:client` to use a separate connection
   pool; `request!`'s own `:connect-timeout-ms` is then ignored."
  ([] (client nil))
  ([opts]
   (let [{:keys [connect-timeout-ms]} (timeout-opts opts)
         b (-> (java.net.http.HttpClient/newBuilder)
               (.version java.net.http.HttpClient$Version/HTTP_1_1))]
     (when connect-timeout-ms
       (.connectTimeout b (java.time.Duration/ofMillis (max 1 (long connect-timeout-ms)))))
     (.build b))))

;; One client per connect timeout per process, built lazily and shared by
;; every namespace, so a retry does not allocate a fresh selector thread.
;; java.net.http pools connections per host, so sharing across providers is
;; harmless. `locking`, not swap!: a retried swap! fn would build (and leak
;; the selector thread of) a second HttpClient.
(def ^:private shared-clients (atom {}))

(defn- shared-client ^java.net.http.HttpClient [connect-timeout-ms]
  (or (get @shared-clients connect-timeout-ms)
      (locking shared-clients
        (or (get @shared-clients connect-timeout-ms)
            (let [c (client {:connect-timeout-ms connect-timeout-ms})]
              (swap! shared-clients assoc connect-timeout-ms c)
              c)))))

(defn normalize-headers
  "A java.net.http header map (name -> list of values) as a Clojure map:
   lower-cased names; a single value as a String, repeated values as a
   vector. Names differing only in case are merged."
  [m]
  (reduce (fn [acc [k vs]]
            (let [k    (str/lower-case (str k))
                  prev (get acc k)
                  all  (into (cond (nil? prev) [] (vector? prev) prev :else [prev]) vs)]
              (assoc acc k (if (= 1 (count all)) (first all) all))))
          {}
          m))

(defn- has-header? [headers lc-name]
  (some (fn [[k _]] (= lc-name (str/lower-case (param-name k)))) headers))

(defn- with-query [url query]
  (if-let [qs (encode-params query)]
    (str url (if (str/includes? url "?") "&" "?") qs)
    url))

(defn- body-handler [as]
  (case (or as :string)
    :string (java.net.http.HttpResponse$BodyHandlers/ofString)
    :bytes  (java.net.http.HttpResponse$BodyHandlers/ofByteArray)
    :stream (java.net.http.HttpResponse$BodyHandlers/ofInputStream)))

(defn- await-response
  "The response of a sendAsync future, waiting at most timeout-ms (nil: no
   limit). The future completes when the body handler does: at the headers
   for ofInputStream, after the body for ofString/ofByteArray. On expiry the
   future is cancelled, which aborts the exchange and closes its connection,
   and HttpTimeoutException is thrown; if it completed in the meantime, that
   outcome wins. A failed exchange rethrows its cause unwrapped, as `.send`
   does. An interrupt cancels the exchange and rethrows."
  [^java.util.concurrent.CompletableFuture f timeout-ms]
  (try
    (if timeout-ms
      (.get f (max 1 (long timeout-ms)) java.util.concurrent.TimeUnit/MILLISECONDS)
      (.get f))
    (catch java.util.concurrent.TimeoutException _
      (if (.cancel f true)
        (throw (java.net.http.HttpTimeoutException. "request timed out"))
        (await-response f nil)))
    (catch java.util.concurrent.ExecutionException e
      (throw (or (.getCause e) e)))
    (catch InterruptedException e
      (.cancel f true)
      (throw e))))

(defn request!
  "Perform one HTTP request. Takes a map:

     :method      :get | :post | :put | :patch | :delete (any keyword works;
                  it is upper-cased as the request method) — required
     :url         absolute URL string — required
     :query       optional params map, bracket-encoded by `encode-params` and
                  appended to :url
     :headers     optional map of name -> value (a vector value sends the
                  header once per element; nil values are skipped)
     :body        nil | String (UTF-8) | byte[] | java.io.File |
                  java.nio.file.Path | java.io.InputStream
     :multipart   parts for `multipart-chunks`, instead of :body. Sets
                  `content-type: multipart/form-data; boundary=...` unless
                  :headers already has a content-type
     :as          :string (default) | :bytes (raw byte[], untouched) |
                  :stream (java.io.InputStream)
     :timeout-ms  deadline in ms for the whole response with :as
                  :string/:bytes, for the response headers only with :as
                  :stream (the body of a stream is never timed); default
                  `default-timeout-ms` (600 s) when the key is absent, none
                  when nil. See the ns docstring
     :connect-timeout-ms
                  TCP/TLS connect timeout in ms; default
                  `default-connect-timeout-ms` (5 s) when absent, none when
                  nil. Ignored when :client is given
     :client      a java.net.http.HttpClient; defaults to a shared client
                  per :connect-timeout-ms value

   Returns {:status int :headers map :body String|byte[]|InputStream} for
   ANY HTTP response, 2xx or not; see the ns docstring for the header shape.

   With `:as :stream` the call returns once the response headers arrive, and
   the caller owns the body: close it, e.g. with `with-open`, on every path,
   including non-2xx responses, or the pooled connection stays checked out.

   Throws only when no response arrives: the transport exception (an
   IOException such as ConnectException, HttpConnectTimeoutException or
   HttpTimeoutException, or an InterruptedException) propagates unwrapped,
   as does an invalid URL's IllegalArgumentException. `timeout-exception?`
   tells a timeout apart. An invalid timeout value throws
   :tools.agents.http/invalid-request before any I/O."
  [{:keys [method url query headers body multipart as] :as req}]
  (when (and (some? body) (some? multipart))
    (throw (ex-info "tools.agents.http/request!: :body and :multipart are mutually exclusive"
                    {:type :tools.agents.http/invalid-request})))
  (let [{:keys [timeout-ms connect-timeout-ms]} (timeout-opts req)]
    (doseq [[k v] [[:timeout-ms timeout-ms] [:connect-timeout-ms connect-timeout-ms]]]
      (when-not (timeout-option? v)
        (throw (ex-info (str "tools.agents.http/request!: " k " must be nil or a positive number of milliseconds, got: " (pr-str v))
                        {:type :tools.agents.http/invalid-request :option k}))))
    (let [boundary  (when multipart (*boundary-fn*))
          headers   (cond-> (or headers {})
                      (and multipart (not (has-header? headers "content-type")))
                      (assoc "content-type" (str "multipart/form-data; boundary=" boundary)))
          builder   (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create (with-query url query)))
                        (.version java.net.http.HttpClient$Version/HTTP_1_1))
          handler   (body-handler as)]
      (doseq [[k v] headers
              v     (if (sequential? v) v [v])
              :when (some? v)]
        (.header builder (param-name k) (str v)))
      (.method builder (str/upper-case (name method))
               (if multipart (multipart-publisher multipart boundary) (publisher body)))
      (let [^java.net.http.HttpClient c (or (:client req) (shared-client connect-timeout-ms))
            ^java.net.http.HttpResponse resp
            (await-response (.sendAsync c (.build builder) handler) timeout-ms)]
        {:status  (.statusCode resp)
         :headers (normalize-headers (.map (.headers resp)))
         :body    (.body resp)}))))

(defn stream-body
  "The body of an `:as :stream` response as a java.io.InputStream. An
   injected request fn (a client's `:http` option) may return a String
   (UTF-8) or byte[] body instead of a stream; it is wrapped in a
   ByteArrayInputStream. nil becomes an empty stream."
  ^java.io.InputStream [body]
  (cond
    (instance? java.io.InputStream body) body
    (nil? body)    (java.io.ByteArrayInputStream. (byte-array 0))
    (string? body) (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))
    (bytes? body)  (java.io.ByteArrayInputStream. ^bytes body)
    :else (throw (ex-info (str "tools.agents.http/stream-body: unsupported :as :stream body "
                               (.getName (class body)))
                          {:type :tools.agents.http/invalid-response}))))

(defn request-fn?
  "True when x can serve as a client's `:http` option: a function or var
   (anything invocable that is not a collection, keyword or symbol). The fn
   must honor `request!`'s contract: the same request map, the same
   {:status :headers :body} response for every status, throwing only when no
   response arrives."
  [x]
  (boolean (or (fn? x) (var? x)
               (and (ifn? x) (not (coll? x)) (not (keyword? x)) (not (symbol? x))))))
