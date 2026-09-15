(ns tools.agents.http
  "The one HTTP request function every client in this repo uses.

   `request!` performs exactly one HTTP exchange. It does not retry, classify
   statuses or parse bodies: each client (anthropic, openai, openai.agents,
   gemini, mcp.http) keeps its own retry loop and error typing on top of it.

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

   TIMEOUTS are off by default: no connect timeout on the default client and
   no request timeout unless `:timeout-ms` is given.

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

(defn client
  "Build a java.net.http.HttpClient pinned to HTTP/1.1. opts:
     :connect-timeout-ms  connect timeout; none when nil (the default)
   Pass the result as `request!`'s `:client` to use a separate connection
   pool or a connect timeout."
  ([] (client nil))
  ([{:keys [connect-timeout-ms]}]
   (let [b (-> (java.net.http.HttpClient/newBuilder)
               (.version java.net.http.HttpClient$Version/HTTP_1_1))]
     (when connect-timeout-ms
       (.connectTimeout b (java.time.Duration/ofMillis (long connect-timeout-ms))))
     (.build b))))

;; One client per process, built lazily and shared by every namespace, so a
;; retry does not allocate a fresh selector thread. java.net.http pools
;; connections per host, so sharing across providers is harmless.
(def ^:private default-client (delay (client)))

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
     :timeout-ms  request timeout (java.net.http's HttpRequest timeout,
                  covering the wait for response headers); none when nil
     :client      a java.net.http.HttpClient; defaults to one shared client

   Returns {:status int :headers map :body String|byte[]|InputStream} for
   ANY HTTP response, 2xx or not; see the ns docstring for the header shape.

   With `:as :stream` the call returns once the response headers arrive, and
   the caller owns the body: close it, e.g. with `with-open`, on every path,
   including non-2xx responses, or the pooled connection stays checked out.

   Throws only when no response arrives: the transport exception (an
   IOException such as ConnectException or HttpTimeoutException, or an
   InterruptedException) propagates unwrapped, as does an invalid URL's
   IllegalArgumentException."
  [{:keys [method url query headers body multipart as timeout-ms] :as req}]
  (when (and (some? body) (some? multipart))
    (throw (ex-info "tools.agents.http/request!: :body and :multipart are mutually exclusive"
                    {:type :tools.agents.http/invalid-request})))
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
    (when timeout-ms
      (.timeout builder (java.time.Duration/ofMillis (long timeout-ms))))
    (.method builder (str/upper-case (name method))
             (if multipart (multipart-publisher multipart boundary) (publisher body)))
    (let [^java.net.http.HttpClient c (or (:client req) @default-client)
          resp (.send c (.build builder) handler)]
      {:status  (.statusCode resp)
       :headers (normalize-headers (.map (.headers resp)))
       :body    (.body resp)})))
