(ns tools.agents.mcp.http
  "The Streamable HTTP transport for revision 2026-07-28.

   What this revision changed, and why the code below is so much smaller than
   a 2025-era equivalent would be: the GET endpoint is gone, protocol-level
   sessions and the `Mcp-Session-Id` header are gone, and SSE resumability
   (`Last-Event-ID`, event ids, message redelivery) is gone. What remains is
   a single POST endpoint where each request is answered either by one JSON
   object or by an SSE stream scoped to that one request. There is no
   per-connection state left to keep, which is why `handle-http` is a pure
   function like `tools.agents.mcp.server/handle` underneath it.

   THE SERVER MAPPING IS PURE AND PORTABLE; the HOST is not. `handle-http`
   turns {method, headers, body-string} into {status, headers, body} using
   nothing but clojure.core, so it runs on both runtimes and is tested as
   plain data. Actually binding a socket needs a server: `serve!` below uses
   the JDK's built-in `com.sun.net.httpserver` (still zero dependencies) and
   is therefore JVM Clojure only, since Babashka does not ship that module.
   That is a choice of implementation, not a capability limit — hosting under
   bb would need a bb-only server dependency, which this namespace avoids. On
   Babashka, host `handle-http` in whatever server you already have; that is
   the whole integration.

   THE CLIENT SIDE runs on both runtimes through `tools.agents.http/request!`,
   the shared request function the provider clients use (or a caller's
   injected `:http` fn), and reads an SSE
   answer incrementally through `tools.agents.stream`, so notifications on a
   request's stream (and on a `subscriptions/listen` stream) arrive live.

   HEADER MIRRORING (SEP-2243) is the fiddly part and it is all here:
   `MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name` and `Mcp-Param-{Name}`
   mirror body fields so intermediaries can route without parsing the body,
   and the server MUST reject any mismatch with -32020 and HTTP 400. Values
   that cannot be carried as plain ASCII use the `=?base64?...?=` sentinel,
   implemented here from arithmetic rather than `java.util.Base64` so that a
   malformed sentinel fails as this library's own typed `ex-info` — which the
   -32020 path depends on — rather than as an opaque IllegalArgumentException
   from the JDK."
  (:require [clojure.string :as str]
            [tools.agents.mcp :as mcp]
            [tools.agents.http :as http]
            [tools.agents.mcp.server :as server]
            [tools.agents.sse :as sse]
            [tools.agents.stream :as stream]))

;; ---------------------------------------------------------------------------
;; UTF-8 + Base64, hand-rolled for portability
;; ---------------------------------------------------------------------------

(defn- chars->str
  "Join a seq of characters into a string. `str/join` over stringified
   elements rather than `(apply str cs)`, which spreads one argument per
   character — a Base64'd header value easily runs to thousands."
  [cs]
  (str/join (map str cs)))

(def ^:private b64-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")

(def ^:private b64-index
  (into {} (map-indexed (fn [i c] [c i]) b64-alphabet)))

(defn utf8-bytes
  "UTF-8 code units of a string, as a vector of integers 0-255. Written from
   code points rather than `(.getBytes s \"UTF-8\")` so the encoder and the
   Base64 codec below share one representation — a vector of ints — and both
   halves of the sentinel round-trip through the same code. A surrogate pair
   is recombined into the single code point it encodes."
  [s]
  (loop [cs (seq s) out []]
    (if-not cs
      out
      (let [c (int (first cs))
            [cp rest-cs] (if (and (<= 0xD800 c 0xDBFF) (next cs)
                                  (<= 0xDC00 (int (second cs)) 0xDFFF))
                           [(+ 0x10000 (* 0x400 (- c 0xD800)) (- (int (second cs)) 0xDC00))
                            (nnext cs)]
                           [c (next cs)])]
        (recur rest-cs
               (cond
                 (< cp 0x80) (conj out cp)
                 (< cp 0x800) (conj out (bit-or 0xC0 (bit-shift-right cp 6))
                                    (bit-or 0x80 (bit-and cp 0x3F)))
                 (< cp 0x10000) (conj out (bit-or 0xE0 (bit-shift-right cp 12))
                                      (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                                      (bit-or 0x80 (bit-and cp 0x3F)))
                 :else (conj out (bit-or 0xF0 (bit-shift-right cp 18))
                             (bit-or 0x80 (bit-and (bit-shift-right cp 12) 0x3F))
                             (bit-or 0x80 (bit-and (bit-shift-right cp 6) 0x3F))
                             (bit-or 0x80 (bit-and cp 0x3F)))))))))

(defn bytes->utf8
  "Inverse of `utf8-bytes`. A code point above the BMP comes back as the
   surrogate pair that represents it, so `(bytes->utf8 (utf8-bytes s))` is
   `s`."
  [bs]
  (loop [bs (seq bs) out []]
    (if-not bs
      (chars->str out)
      (let [b (first bs)
            [cp rest-bs]
            (cond
              (< b 0x80) [b (next bs)]
              (= 0xC0 (bit-and b 0xE0)) [(bit-or (bit-shift-left (bit-and b 0x1F) 6)
                                                 (bit-and (second bs) 0x3F))
                                         (nnext bs)]
              (= 0xE0 (bit-and b 0xF0)) [(bit-or (bit-shift-left (bit-and b 0x0F) 12)
                                                 (bit-shift-left (bit-and (nth bs 1) 0x3F) 6)
                                                 (bit-and (nth bs 2) 0x3F))
                                         (nthnext bs 3)]
              :else [(bit-or (bit-shift-left (bit-and b 0x07) 18)
                             (bit-shift-left (bit-and (nth bs 1) 0x3F) 12)
                             (bit-shift-left (bit-and (nth bs 2) 0x3F) 6)
                             (bit-and (nth bs 3) 0x3F))
                     (nthnext bs 4)])]
        (recur rest-bs
               (if (> cp 0xFFFF)
                 ;; Strings are UTF-16, so a code point above the BMP is two
                 ;; chars and `(char cp)` would throw — emit the pair.
                 (let [v (- cp 0x10000)]
                   (conj out (char (+ 0xD800 (bit-shift-right v 10)))
                         (char (+ 0xDC00 (bit-and v 0x3FF)))))
                 (conj out (char cp))))))))

(defn base64-encode
  "Standard Base64 (RFC 4648) of a string's UTF-8 bytes, with padding.

   Sized for header values, not for large blobs: this allocates per output
   character, which is free at the ~100-byte sentinel values it exists for
   and roughly linear-with-a-large-constant beyond that. A megabyte-scale
   `mcp/blob-contents` payload is better encoded by the caller."
  [s]
  (chars->str
   (mapcat (fn [group]
             (let [[a b c] group
                   n (count group)
                   v (bit-or (bit-shift-left a 16)
                             (bit-shift-left (or b 0) 8)
                             (or c 0))]
               (concat [(nth b64-alphabet (bit-and (bit-shift-right v 18) 63))
                        (nth b64-alphabet (bit-and (bit-shift-right v 12) 63))]
                       (if (>= n 2) [(nth b64-alphabet (bit-and (bit-shift-right v 6) 63))] ["="])
                       (if (>= n 3) [(nth b64-alphabet (bit-and v 63))] ["="]))))
           (partition-all 3 (utf8-bytes s)))))

(defn base64-decode
  "Inverse of `base64-encode`. Throws a typed ex-info on a character outside
   the alphabet, so a malformed header value fails loudly rather than
   decoding to garbage that then 'mismatches' the body."
  [s]
  (let [chars (remove #(= \= %) (seq s))]
    (doseq [c chars]
      (when-not (contains? b64-index c)
        (throw (ex-info (str "tools.agents.mcp.http/base64-decode: invalid character " (pr-str c))
                        {:type :tools.agents.mcp.error/invalid-base64}))))
    (bytes->utf8
     (mapcat (fn [group]
               (let [n (count group)
                     v (reduce (fn [acc c] (bit-or (bit-shift-left acc 6) (b64-index c)))
                               0
                               (concat group (repeat (- 4 n) \A)))]
                 (take (dec n) [(bit-and (bit-shift-right v 16) 255)
                                (bit-and (bit-shift-right v 8) 255)
                                (bit-and v 255)])))
             (partition-all 4 chars)))))

;; ---------------------------------------------------------------------------
;; Header value encoding — the `=?base64?...?=` sentinel
;; ---------------------------------------------------------------------------

(def ^:private sentinel-prefix "=?base64?")
(def ^:private sentinel-suffix "?=")

(defn header-safe?
  "True when a value can travel as a plain HTTP field value: visible ASCII,
   space and tab only, no leading/trailing whitespace, and not itself
   looking like the sentinel."
  [v]
  (and (string? v)
       (not (str/starts-with? v " ")) (not (str/ends-with? v " "))
       (not (str/starts-with? v "\t")) (not (str/ends-with? v "\t"))
       (every? (fn [c] (let [n (int c)] (or (<= 0x21 n 0x7E) (= n 0x20) (= n 0x09)))) v)
       (not (and (str/starts-with? v sentinel-prefix) (str/ends-with? v sentinel-suffix)))))

(defn encode-header-value
  "Encode a value for `Mcp-Name` / `Mcp-Param-{Name}`. Plain ASCII goes
   as-is; anything else — and any plain value that would be mistaken for the
   sentinel — is Base64'd inside the sentinel markers."
  [v]
  (let [s (cond (string? v) v
                (true? v) "true"
                (false? v) "false"
                :else (str v))]
    (if (header-safe? s) s (str sentinel-prefix (base64-encode s) sentinel-suffix))))

(defn decode-header-value
  "Decode a header value, undoing the sentinel if present. Servers MUST do
   this before comparing a header against the body."
  [v]
  (if (and (string? v)
           (str/starts-with? v sentinel-prefix)
           (str/ends-with? v sentinel-suffix)
           (>= (count v) (+ (count sentinel-prefix) (count sentinel-suffix))))
    (base64-decode (subs v (count sentinel-prefix) (- (count v) (count sentinel-suffix))))
    v))

;; ---------------------------------------------------------------------------
;; x-mcp-header
;; ---------------------------------------------------------------------------

(def ^:private tchar-set
  (set "!#$%&'*+-.^_`|~0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"))

(defn- statically-reachable-headers
  "Walk `properties` chains only. An `x-mcp-header` annotation reached
   through `items`, `oneOf`/`anyOf`/`allOf`/`not`, `if`/`then`/`else` or
   `$ref` is invalid by the spec, so those branches are simply not walked —
   an annotation there is never collected, and `validate-x-mcp-headers`
   reports it as unreachable."
  [schema path]
  (mapcat (fn [[pname pschema]]
            (let [p (conj path pname)]
              (concat (when-let [h (get pschema "x-mcp-header")]
                        [{:header h :path p :schema pschema}])
                      (statically-reachable-headers pschema p))))
          (get schema "properties")))

(defn validate-x-mcp-headers
  "Check every `x-mcp-header` annotation in a tool's inputSchema against the
   spec's constraints, returning a vector of human-readable problems (empty
   when valid).

   A CLIENT using Streamable HTTP MUST reject a tool definition that
   violates any of these — 'reject' meaning exclude that one tool from
   `tools/list`, not discard the whole list — and SHOULD log why. Hence a
   vector of reasons rather than a boolean."
  [input-schema]
  (let [found (statically-reachable-headers input-schema [])
        names (map :header found)
        lowered (map str/lower-case (filter string? names))
        dupes (->> (frequencies lowered) (keep (fn [[k n]] (when (> n 1) k))))]
    (vec
     (concat
      (mapcat (fn [{:keys [header path schema]}]
                (let [t (get schema "type")]
                  (concat
                   (when-not (and (string? header) (seq header))
                     [(str "x-mcp-header at " (pr-str path) " must be a non-empty string")])
                   (when (and (string? header) (not (every? tchar-set header)))
                     [(str "x-mcp-header " (pr-str header) " is not a valid HTTP field-name token")])
                   (when-not (contains? #{"string" "integer" "boolean"} t)
                     [(str "x-mcp-header " (pr-str header) " annotates a property of type "
                           (pr-str t) "; only string, integer and boolean are permitted"
                           (when (= "number" t) " (number is explicitly excluded)"))]))))
              found)
      (map (fn [d] (str "x-mcp-header " (pr-str d) " is not case-insensitively unique")) dupes)))))

(defn x-mcp-header-values
  "The `Mcp-Param-{Name}` headers a `tools/call` MUST carry, given the tool's
   inputSchema and the call arguments. A property absent from the arguments,
   or present as null, contributes no header."
  [input-schema arguments]
  (into {} (keep (fn [{:keys [header path]}]
                   (let [v (get-in arguments path ::absent)]
                     (when-not (or (= ::absent v) (nil? v))
                       [(str "Mcp-Param-" header) (encode-header-value v)])))
                 (statically-reachable-headers input-schema []))))

;; ---------------------------------------------------------------------------
;; Standard request headers
;; ---------------------------------------------------------------------------

(defn- lower-keys [headers]
  (into {} (map (fn [[k v]] [(str/lower-case (if (keyword? k) (name k) (str k))) v]) headers)))

(defn mcp-name-source
  "The body field `Mcp-Name` mirrors: `params.name` for tools/call and
   prompts/get, `params.uri` for resources/read. nil for every other method,
   which is exactly the set for which the header is not required."
  [message]
  (case (get message "method")
    ("tools/call" "prompts/get") (get-in message ["params" "name"])
    "resources/read" (get-in message ["params" "uri"])
    nil))

(defn request-headers
  "The headers a conforming client MUST send with a request POST. `tool`, if
   given, is the tool definition whose `x-mcp-header` annotations produce the
   `Mcp-Param-*` headers."
  ([message] (request-headers message nil))
  ([message tool]
   (let [nm (mcp-name-source message)]
     (merge {"Content-Type" "application/json"
             "Accept" "application/json, text/event-stream"
             "MCP-Protocol-Version" (or (get (mcp/params-meta message) mcp/meta-protocol-version)
                                        mcp/latest-protocol-version)
             "Mcp-Method" (get message "method")}
            (when (some? nm) {"Mcp-Name" (encode-header-value nm)})
            (when (and tool (= "tools/call" (get message "method")))
              (x-mcp-header-values (get tool "inputSchema")
                                   (get-in message ["params" "arguments"] {})))))))

(defn validate-headers!
  "Server-side header/body agreement. Raises HeaderMismatch (-32020) on any
   missing required header or any value that disagrees with the body, after
   decoding the sentinel. This exists because a load balancer may route on
   the header while the server executes on the body — letting the two differ
   is the vulnerability, not an inconvenience."
  [headers message]
  (let [h (lower-keys headers)
        pv-header (get h "mcp-protocol-version")
        pv-body (get (mcp/params-meta message) mcp/meta-protocol-version)
        method-header (get h "mcp-method")
        method-body (get message "method")
        name-body (mcp-name-source message)
        name-header (get h "mcp-name")]
    (when-not pv-header
      (mcp/header-mismatch! "Missing required header: MCP-Protocol-Version"))
    (when (not= pv-header pv-body)
      (mcp/header-mismatch! (str "Header mismatch: MCP-Protocol-Version " (pr-str pv-header)
                                 " does not match body value " (pr-str pv-body))))
    (when-not method-header
      (mcp/header-mismatch! "Missing required header: Mcp-Method"))
    (when (not= method-header method-body)
      (mcp/header-mismatch! (str "Header mismatch: Mcp-Method " (pr-str method-header)
                                 " does not match body value " (pr-str method-body))))
    (when (some? name-body)
      (when-not name-header
        (mcp/header-mismatch! "Missing required header: Mcp-Name"))
      (let [decoded (try (decode-header-value name-header)
                         (catch Exception _
                           (mcp/header-mismatch! "Mcp-Name header value contains invalid characters")))]
        (when (not= decoded name-body)
          (mcp/header-mismatch! (str "Header mismatch: Mcp-Name header value " (pr-str decoded)
                                     " does not match body value " (pr-str name-body))))))
    true))

(defn validate-param-headers!
  "Validate the `Mcp-Param-*` headers of a `tools/call` against the tool's
   `x-mcp-header` annotations and the call's own arguments. A conforming
   client sends exactly one header per annotated property that has a
   non-null value, so a missing, extra or disagreeing one is -32020."
  [headers message tool]
  (when (and tool (= "tools/call" (get message "method")))
    (let [h (lower-keys headers)
          expected (x-mcp-header-values (get tool "inputSchema")
                                        (get-in message ["params" "arguments"] {}))]
      (doseq [[hname hval] expected]
        (let [got (get h (str/lower-case hname))]
          (when (nil? got)
            (mcp/header-mismatch! (str "Missing required header: " hname)))
          (let [decoded (try (decode-header-value got)
                             (catch Exception _
                               (mcp/header-mismatch! (str hname " contains invalid characters"))))]
            (when (not= decoded (decode-header-value hval))
              (mcp/header-mismatch! (str "Header mismatch: " hname " " (pr-str decoded)
                                         " does not match the request body"))))))))
  true)

;; ---------------------------------------------------------------------------
;; Server: HTTP request -> HTTP response, purely
;; ---------------------------------------------------------------------------

(defn error-code->status
  "The HTTP status the spec pairs with each JSON-RPC error code. Everything
   the spec does not pin down stays 200: a JSON-RPC error is a successful
   HTTP exchange unless the transport itself says otherwise."
  [code]
  (condp = code
    mcp/parse-error 400
    mcp/invalid-request 400
    mcp/method-not-found 404
    mcp/invalid-params 400
    mcp/header-mismatch 400
    mcp/missing-required-client-capability 400
    mcp/unsupported-protocol-version 400
    mcp/internal-error 500
    200))

(defn sse-event
  "One SSE `message` event carrying `message`. The 2-arity encodes with the
   server's :json codec (see `server/server`); use it for the events a host
   writes to a held subscription stream."
  ([message] (sse-event nil message))
  ([srv message]
   (str "event: message\ndata: " ((:write (mcp/codec-of srv)) message) "\n\n")))

(def ^:private json-headers {"Content-Type" "application/json"})

(def ^:private sse-headers
  ;; X-Accel-Buffering: no tells nginx and friends not to buffer, which would
  ;; otherwise hold progress notifications until the stream ended and defeat
  ;; the point of streaming them.
  {"Content-Type" "text/event-stream"
   "Cache-Control" "no-cache"
   "Connection" "keep-alive"
   "X-Accel-Buffering" "no"})

(defn- error-http [codec id code message data]
  (let [resp (mcp/error-response id code message data)]
    {:status (error-code->status code) :headers json-headers :body ((:write codec) resp)}))

(defn handle-http
  "Map one HTTP request onto the MCP server. Pure — no sockets, no atoms, no
   clock. `req` is {:request-method :headers :body}, `body` a string.

   Returns {:status :headers :body}, plus :subscription when the request was
   a `subscriptions/listen` and the host should now hold the stream open and
   keep writing `sse-event`s to it.

   Options:
     :allowed-origins  a set, or a predicate. Servers MUST validate `Origin`
                       to prevent DNS rebinding; an invalid one is 403. An
                       absent Origin header is not checked (it is not a
                       browser request).
     :tools-by-name    map of name -> tool definition, enabling the
                       `Mcp-Param-*` half of header validation. Optional:
                       without it the standard headers are still checked."
  ([srv req] (handle-http srv req nil))
  ([srv {:keys [request-method headers body]} {:keys [allowed-origins tools-by-name]}]
   (let [h (lower-keys headers)
         origin (get h "origin")
         codec (mcp/codec-of srv)
         write (:write codec)]
     (cond
       (and (some? origin) allowed-origins
            (not (if (fn? allowed-origins) (allowed-origins origin) (contains? (set allowed-origins) origin))))
       {:status 403 :headers json-headers
        :body (write (mcp/error-response nil mcp/invalid-request
                                         (str "Forbidden origin: " origin)))}

       (not= :post (some-> request-method name str/lower-case keyword))
       {:status 405 :headers (assoc json-headers "Allow" "POST")
        :body (write (mcp/error-response nil mcp/invalid-request
                                         "The MCP endpoint accepts POST only"))}

       :else
       (let [parsed (try [::ok ((:read codec) body)] (catch Exception e [::bad e]))]
         (if (= ::bad (first parsed))
           (error-http codec nil mcp/parse-error (str "Parse error: " (ex-message (second parsed))) nil)
           (let [message (second parsed)
                 id (when (map? message) (get message "id"))]
             (if (mcp/notification? message)
               ;; A notification the server accepts gets 202 and no body.
               {:status 202 :headers {} :body ""}
               (try
                 (validate-headers! headers message)
                 (validate-param-headers! headers message
                                          (get tools-by-name (get-in message ["params" "name"])))
                 (let [{:keys [response notifications subscription]} (server/handle srv message)]
                   (cond
                     subscription
                     {:status 200 :headers sse-headers
                      :body (str/join (map #(sse-event srv %) notifications))
                      :subscription subscription}

                     (seq notifications)
                     ;; Request-scoped notifications precede the final
                     ;; response on that request's own stream, and the
                     ;; response terminates it.
                     {:status 200 :headers sse-headers
                      :body (str (str/join (map #(sse-event srv %) notifications)) (sse-event srv response))}

                     :else
                     {:status (if (mcp/error-response? response)
                                (error-code->status (get-in response ["error" "code"]))
                                200)
                      :headers json-headers
                      :body (write response)}))
                 (catch Exception e
                   (let [d (ex-data e)]
                     (if (= :tools.agents.mcp.error/protocol (:type d))
                       (error-http codec id (:code d) (or (:message d) (ex-message e)) (:data d))
                       (error-http codec id mcp/internal-error
                                   (str "Internal error: " (or (ex-message e) (str e))) nil))))))))))))) 

(defn- response-to?
  "True when `m` is the JSON-RPC response to request `msg`."
  [msg m]
  (and (map? m)
       (= (get m "id") (get msg "id"))
       (or (contains? m "result") (contains? m "error"))))

(defn- slurp-body
  "Read a streamed response body to a UTF-8 String, closing it."
  [^java.io.InputStream in]
  (if in
    (with-open [in in] (String. (.readAllBytes in) "UTF-8"))
    ""))

(defn- read-sse-response!
  "Consume one request's SSE stream incrementally. Every message is decoded
   and dispatched as it arrives: the response to `msg` ends the stream
   (`reduced`, which closes the body); anything else goes to
   `on-notification`. Returns the response, or nil when the stream was
   cancelled. Throws ::error/transport when the body ends, or breaks,
   before the response arrives."
  [msg resp {:keys [codec on-notification register! unregister!]}]
  (let [s (stream/open-event-stream
           {:request {:method :post}
            ;; The response is already open (its headers chose this path);
            ;; hand it over instead of sending a second request.
            :send! (fn [_] resp)
            :on-read-error (fn [e]
                             (ex-info (str "tools.agents.mcp.http: SSE stream broke before responding: "
                                           (ex-message e))
                                      {:type :tools.agents.mcp.error/transport :request msg}
                                      e))})]
    (register! s)
    (try
      (let [result (reduce (fn [_ ev]
                             (let [data (:data ev)]
                               (if (str/blank? data)
                                 nil
                                 (let [m ((:read codec) data)]
                                   (if (response-to? msg m)
                                     (reduced m)
                                     (do (on-notification m) nil))))))
                           nil s)]
        (case (stream/outcome s)
          :reduced result
          :cancelled nil
          (throw (ex-info "tools.agents.mcp.http: server closed the SSE stream before responding"
                          {:type :tools.agents.mcp.error/transport :request msg
                           :status (:status resp) :headers (:headers resp)}))))
      (finally
        (unregister! s)
        (stream/close! s)))))

(defn connect!
  "A Streamable HTTP client transport. Returns

     {:url url  :send! f  :close! f}

   `:send!` goes straight into `tools.agents.mcp.client/client`. It mirrors
   the required headers off the body, POSTs, and returns the JSON-RPC
   response, whether the server answered with one JSON object or with an
   SSE stream — from the caller's point of view the two are the same value.

   AN SSE ANSWER IS READ LIVE (tools.agents.stream): each message is handed
   to `:on-notification` the moment its event arrives, not when the stream
   ends. Everything on the stream other than the response to this request's
   id goes there — notifications, and any other JSON-RPC message; this
   revision has no server-to-client requests. The response closes the
   stream, which is also how a `subscriptions/listen` ends gracefully: the
   server's closure response (`server/close-subscription`) carries the
   listen request's id. A body that ends, or breaks, before the response
   throws ::error/transport.

   CANCELLATION closes the stream, and a cancelled `:send!` returns nil:
     - `:send!` of `notifications/cancelled` closes the in-flight stream of
       the request it names, then POSTs the notification, so
       `client/cancel!` ends a listen on HTTP as it does on stdio;
     - `:close!` closes every in-flight stream of this transport.

   `:tools-by-name` (name -> tool definition, e.g. from
   `client/list-all-tools!`) enables the `Mcp-Param-*` headers that
   `x-mcp-header` annotations demand. A client MUST send them when the tool
   asks for them, so a caller that has the definitions should pass them.

   A JSON answer is returned whatever its status: a 4xx/5xx carrying a
   JSON-RPC error is that error response, for `client/result-of` to type.
   Notifications POST with no expectation of a body: 202 Accepted returns
   nil. A transport failure before any response propagates unwrapped.

   `:http` replaces tools.agents.http/request! for every POST: a request fn
   with request!'s contract, called with `:as :stream` (a String or byte[]
   body is accepted in place of an InputStream). Anything but a fn throws
   ::error/invalid-options.

   `:json` is the codec {:read (fn [s]) :write (fn [v])} for request bodies
   and responses, JSON or SSE; default the built-in codec. A non-codec throws
   ::error/invalid-options."
  ([url] (connect! url nil))
  ([url {:keys [headers on-notification tools-by-name http]
         :or {on-notification (fn [_])}
         :as opts}]
   (when (and (contains? opts :http) (not (http/request-fn? http)))
     (throw (ex-info (str "tools.agents.mcp.http/connect!: :http must be a request fn with "
                          "tools.agents.http/request!'s contract, got: " (pr-str (type http)))
                     {:type :tools.agents.mcp.error/invalid-options :option :http})))
   (mcp/validate-json-option! "tools.agents.mcp.http/connect!" opts)
   (let [send-http (or http http/request!)
         codec (mcp/codec-of opts)
         in-flight (atom {})          ; stream -> request id
         close-where (fn [pred]
                       (doseq [[s id] @in-flight :when (pred id)]
                         (stream/close! s)))]
     {:url url
      :close! (fn [] (close-where (constantly true)))
      :send!
      (fn [msg]
        (when (= "notifications/cancelled" (get msg "method"))
          ;; Close first: the client stops reading at once, and a server
          ;; that serves one exchange at a time is freed to take the POST.
          (let [target (get-in msg ["params" "requestId"])]
            (close-where #(= target %))))
        (let [tool (get tools-by-name (get-in msg ["params" "name"]))
              req-headers (merge (request-headers msg tool) headers)
              resp (-> (send-http {:method :post :url url :headers req-headers
                                   :body ((:write codec) msg) :as :stream})
                       (update :body http/stream-body))
              status (:status resp)
              ctype (let [c (get (:headers resp) "content-type")]
                      (str/lower-case (str (if (sequential? c) (first c) c))))]
          (cond
            (= 202 status)
            (do (slurp-body (:body resp)) nil)

            (and (<= 200 status 299) (str/includes? ctype "text/event-stream"))
            (read-sse-response! msg resp
                                {:codec codec
                                 :on-notification on-notification
                                 :register! #(swap! in-flight assoc % (get msg "id"))
                                 :unregister! #(swap! in-flight dissoc %)})

            :else
            (let [body (slurp-body (:body resp))]
              (cond
                (str/includes? ctype "text/event-stream")
                ;; A non-2xx SSE answer (handle-http never sends one; other servers
                ;; may): kept buffered, same dispatch as the live path.
                (let [msgs (->> (sse/parse-string body)
                                (remove (comp str/blank? :data))
                                (map (comp (:read codec) :data)))]
                  (or (some (fn [m] (if (response-to? msg m) m (do (on-notification m) nil))) msgs)
                      (throw (ex-info (str "tools.agents.mcp.http: SSE body without a response, HTTP " status)
                                      {:type :tools.agents.mcp.error/transport
                                       :response (assoc resp :body body)}))))
                (seq body) ((:read codec) body)
                :else (throw (ex-info (str "tools.agents.mcp.http: empty response body, HTTP " status)
                                      {:type :tools.agents.mcp.error/transport
                                       :response (assoc resp :body body)})))))))})))

;; ---------------------------------------------------------------------------
;; Host adapter — JVM Clojure only
;; ---------------------------------------------------------------------------

(defn serve!
  "Bind an HTTP endpoint and host `handle-http` on it, using the JDK's
   built-in com.sun.net.httpserver — still zero external dependencies.

   JVM CLOJURE ONLY: Babashka does not ship the jdk.httpserver module, and
   this namespace will not take a bb-only server dependency to work around
   that. Under bb, call `handle-http` from whatever server you already run —
   that is the entire integration surface, a pure function from an HTTP
   request map to an HTTP response map.

   Binds 127.0.0.1 by default: the spec SHOULDs localhost-only for a local
   server, because binding 0.0.0.0 exposes it to the network. Returns a map
   with :port and :stop!.

   `:path` defaults to \"/mcp\". Options are otherwise `handle-http`'s."
  ([srv] (serve! srv nil))
  ([srv {:keys [port host path allowed-origins tools-by-name]
         :or {port 0 host "127.0.0.1" path "/mcp"}}]
   #?(:bb
      (throw (ex-info "tools.agents.mcp.http/serve!: Babashka does not ship com.sun.net.httpserver — host handle-http in your own server, or use tools.agents.mcp.stdio/serve!"
                      {:type :tools.agents.mcp.error/unsupported-runtime}))
      :clj
      (let [server (com.sun.net.httpserver.HttpServer/create
                    (java.net.InetSocketAddress. ^String host (int port)) 0)]
        (.createContext
         server ^String path
         (reify com.sun.net.httpserver.HttpHandler
           (handle [_ exchange]
             (let [hs (into {} (map (fn [[k v]] [k (first v)])
                                    (.getRequestHeaders exchange)))
                   body (slurp (.getRequestBody exchange))
                   {:keys [status headers body]}
                   (try
                     (handle-http srv {:request-method (str/lower-case (.getRequestMethod exchange))
                                       :headers hs :body body}
                                  {:allowed-origins allowed-origins
                                   :tools-by-name tools-by-name})
                     (catch Exception e
                       {:status 500 :headers {"Content-Type" "application/json"}
                        ;; the built-in codec: this is the fallback for a
                        ;; handler (or an injected codec) that threw
                        :body (mcp/write-json
                               (mcp/error-response nil mcp/internal-error (str e)))}))
                   out (.getBytes ^String (or body "") "UTF-8")]
               (doseq [[k v] headers] (.set (.getResponseHeaders exchange) (str k) (str v)))
               (.sendResponseHeaders exchange (int status) (long (alength out)))
               (with-open [os (.getResponseBody exchange)] (.write os out))))))
        (.start server)
        {:port (.getPort (.getAddress server))
         :server server
         :stop! (fn [] (.stop server 0))}))))
