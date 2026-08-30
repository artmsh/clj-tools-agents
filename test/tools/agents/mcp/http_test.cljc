(ns tools.agents.mcp.http-test
  "Streamable HTTP (SEP-2243): the portable Base64/UTF-8 primitives, the
   `Mcp-*` header mirror and its `=?base64?...?=` sentinel, `x-mcp-header`
   validation, and `handle-http` — which, like `server/handle`, is a pure
   function and therefore testable without binding a socket.

   The Base64 here is hand-rolled arithmetic rather than java.util.Base64,
   so it is worth testing as a codec in its own right: it is the one piece of
   this library whose correctness cannot be inferred from the JVM's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.http :as http]
            [tools.agents.mcp.server :as server]))

;; ---------------------------------------------------------------------------
;; UTF-8 and Base64
;; ---------------------------------------------------------------------------

(deftest utf8-round-trips-across-the-plane
  (doseq [s ["" "a" "hello" "äöü" "日本語" "naïve café" "🎉" "a\nb\tc"]]
    (is (= s (http/bytes->utf8 (http/utf8-bytes s))) (pr-str s))))

(deftest utf8-byte-lengths-follow-the-encoding
  (is (= 1 (count (http/utf8-bytes "a"))))
  (is (= 2 (count (http/utf8-bytes "ä"))))
  (is (= 3 (count (http/utf8-bytes "日"))))
  (is (= 4 (count (http/utf8-bytes "🎉"))) "an astral character is one 4-byte sequence"))

(deftest base64-matches-the-rfc-4648-vectors
  (is (= "" (http/base64-encode "")))
  (is (= "Zg==" (http/base64-encode "f")))
  (is (= "Zm8=" (http/base64-encode "fo")))
  (is (= "Zm9v" (http/base64-encode "foo")))
  (is (= "Zm9vYg==" (http/base64-encode "foob")))
  (is (= "Zm9vYmE=" (http/base64-encode "fooba")))
  (is (= "Zm9vYmFy" (http/base64-encode "foobar"))))

(deftest base64-round-trips
  (doseq [s ["" "a" "ab" "abc" "hello world" "äöü" "日本語" "🎉" "line\nbreak"]]
    (is (= s (http/base64-decode (http/base64-encode s))) (pr-str s))))

(deftest base64-decodes-the-rfc-vectors
  (is (= "f" (http/base64-decode "Zg==")))
  (is (= "foobar" (http/base64-decode "Zm9vYmFy"))))

;; ---------------------------------------------------------------------------
;; Header value encoding
;; ---------------------------------------------------------------------------

(deftest header-safe?-accepts-only-plain-visible-ascii
  (is (http/header-safe? "get_forecast"))
  (is (http/header-safe? "a b"))
  (is (not (http/header-safe? " leading")))
  (is (not (http/header-safe? "trailing ")))
  (is (not (http/header-safe? "new\nline")))
  (is (not (http/header-safe? "café")))
  (testing "a plain value that looks like the sentinel is not safe"
    (is (not (http/header-safe? "=?base64?Zm9v?=")))))

(deftest ascii-header-values-travel-unencoded
  (is (= "get_forecast" (http/encode-header-value "get_forecast")))
  (is (= "true" (http/encode-header-value true)))
  (is (= "42" (http/encode-header-value 42))))

(deftest non-ascii-header-values-use-the-sentinel
  (is (= "=?base64?w6TDtsO8?=" (http/encode-header-value "äöü")))
  (is (= "äöü" (http/decode-header-value (http/encode-header-value "äöü")))))

(deftest header-values-round-trip
  (doseq [v ["plain" "with space" "äöü" "日本語" "=?base64?Zm9v?=" "tab\there" "new\nline"]]
    (is (= v (http/decode-header-value (http/encode-header-value v))) (pr-str v))))

(deftest decoding-a-plain-value-is-the-identity
  (is (= "plain" (http/decode-header-value "plain")))
  (is (= "=?base64?" (http/decode-header-value "=?base64?") )
      "a truncated sentinel is not a sentinel"))

;; ---------------------------------------------------------------------------
;; x-mcp-header
;; ---------------------------------------------------------------------------

(def annotated-schema
  {"type" "object"
   "properties" {"tenant" {"type" "string" "x-mcp-header" "X-Tenant"}
                 "page" {"type" "integer" "x-mcp-header" "X-Page"}
                 "nested" {"type" "object"
                           "properties" {"flag" {"type" "boolean" "x-mcp-header" "X-Flag"}}}
                 "plain" {"type" "string"}}})

(deftest a-valid-annotation-set-reports-no-problems
  (is (= [] (http/validate-x-mcp-headers annotated-schema)))
  (is (= [] (http/validate-x-mcp-headers {"type" "object"}))))

(deftest an-annotation-on-a-number-is-rejected
  (let [problems (http/validate-x-mcp-headers
                  {"type" "object"
                   "properties" {"p" {"type" "number" "x-mcp-header" "X-P"}}})]
    (is (= 1 (count problems)))
    (is (str/includes? (first problems) "number is explicitly excluded"))))

(deftest an-annotation-that-is-not-a-field-name-token-is-rejected
  (is (seq (http/validate-x-mcp-headers
            {"type" "object" "properties" {"p" {"type" "string" "x-mcp-header" "bad header"}}})))
  (is (seq (http/validate-x-mcp-headers
            {"type" "object" "properties" {"p" {"type" "string" "x-mcp-header" ""}}}))))

(deftest duplicate-annotations-are-rejected-case-insensitively
  (is (seq (http/validate-x-mcp-headers
            {"type" "object"
             "properties" {"a" {"type" "string" "x-mcp-header" "X-Dup"}
                           "b" {"type" "string" "x-mcp-header" "x-dup"}}}))))

(deftest annotations-reached-only-through-non-properties-branches-are-invisible
  ;; The spec confines x-mcp-header to statically reachable `properties`
  ;; chains, so an annotation under `items` or `oneOf` is never collected and
  ;; therefore never produces a header.
  (let [schema {"type" "object"
                "properties" {"xs" {"type" "array"
                                    "items" {"type" "object"
                                             "properties"
                                             {"h" {"type" "string" "x-mcp-header" "X-H"}}}}}}]
    (is (= [] (http/validate-x-mcp-headers schema)))
    (is (= {} (http/x-mcp-header-values schema {"xs" [{"h" "v"}]})))))

(deftest header-values-come-only-from-arguments-that-are-present
  (is (= {"Mcp-Param-X-Tenant" "acme" "Mcp-Param-X-Page" "2"}
         (http/x-mcp-header-values annotated-schema {"tenant" "acme" "page" 2 "plain" "ignored"})))
  (is (= {} (http/x-mcp-header-values annotated-schema {})))
  (is (= {} (http/x-mcp-header-values annotated-schema {"tenant" nil}))
      "an explicit null contributes no header")
  (is (= {"Mcp-Param-X-Flag" "true"}
         (http/x-mcp-header-values annotated-schema {"nested" {"flag" true}}))))

;; ---------------------------------------------------------------------------
;; Standard request headers
;; ---------------------------------------------------------------------------

(def a-meta (mcp/request-meta {:protocol-version mcp/latest-protocol-version
                               :client-capabilities {}}))

(defn a-message
  ([method] (a-message method nil))
  ([method params] (mcp/request 1 method (assoc (or params {}) "_meta" a-meta))))

(deftest mcp-name-mirrors-the-right-body-field
  (is (= "echo" (http/mcp-name-source (a-message "tools/call" {"name" "echo"}))))
  (is (= "greet" (http/mcp-name-source (a-message "prompts/get" {"name" "greet"}))))
  (is (= "test://r" (http/mcp-name-source (a-message "resources/read" {"uri" "test://r"}))))
  (testing "and is absent for every method that does not require it"
    (is (nil? (http/mcp-name-source (a-message "tools/list"))))
    (is (nil? (http/mcp-name-source (a-message "server/discover"))))))

(deftest request-headers-are-what-a-conforming-client-sends
  (let [h (http/request-headers (a-message "tools/call" {"name" "echo"}))]
    (is (= "application/json" (get h "Content-Type")))
    (is (= "application/json, text/event-stream" (get h "Accept")))
    (is (= mcp/latest-protocol-version (get h "MCP-Protocol-Version")))
    (is (= "tools/call" (get h "Mcp-Method")))
    (is (= "echo" (get h "Mcp-Name"))))
  (testing "no Mcp-Name where the spec does not require one"
    (is (not (contains? (http/request-headers (a-message "tools/list")) "Mcp-Name"))))
  (testing "a non-ASCII name is sentinel-encoded"
    (is (= "=?base64?w6TDtsO8?="
           (get (http/request-headers (a-message "tools/call" {"name" "äöü"})) "Mcp-Name"))))
  (testing "param headers come from the tool definition"
    (is (= "acme"
           (get (http/request-headers
                 (a-message "tools/call" {"name" "echo" "arguments" {"tenant" "acme"}})
                 {"inputSchema" annotated-schema})
                "Mcp-Param-X-Tenant")))))

;; ---------------------------------------------------------------------------
;; Server-side header validation
;; ---------------------------------------------------------------------------

(defn- ex-code [f]
  (try (f) nil (catch Exception e (:code (ex-data e)))))

(deftest matching-headers-validate
  (let [m (a-message "tools/call" {"name" "echo"})]
    (is (true? (http/validate-headers! (http/request-headers m) m)))))

(deftest a-missing-required-header-is-32020
  (let [m (a-message "tools/call" {"name" "echo"})
        h (http/request-headers m)]
    (is (= -32020 (ex-code #(http/validate-headers! (dissoc h "MCP-Protocol-Version") m))))
    (is (= -32020 (ex-code #(http/validate-headers! (dissoc h "Mcp-Method") m))))
    (is (= -32020 (ex-code #(http/validate-headers! (dissoc h "Mcp-Name") m))))))

(deftest a-header-that-disagrees-with-the-body-is-32020
  ;; This is the security property, not a formality: a proxy may route on the
  ;; header while the server executes on the body.
  (let [m (a-message "tools/call" {"name" "echo"})
        h (http/request-headers m)]
    (is (= -32020 (ex-code #(http/validate-headers! (assoc h "Mcp-Name" "other") m))))
    (is (= -32020 (ex-code #(http/validate-headers! (assoc h "Mcp-Method" "tools/list") m))))
    (is (= -32020 (ex-code #(http/validate-headers! (assoc h "MCP-Protocol-Version" "2025-06-18") m))))))

(deftest header-names-are-matched-case-insensitively
  (let [m (a-message "tools/call" {"name" "echo"})]
    (is (true? (http/validate-headers!
                {"mcp-protocol-version" mcp/latest-protocol-version
                 "MCP-METHOD" "tools/call"
                 "mCp-NaMe" "echo"}
                m)))))

(deftest param-headers-are-validated-against-the-arguments
  (let [tool {"inputSchema" annotated-schema}
        m (a-message "tools/call" {"name" "echo" "arguments" {"tenant" "acme"}})
        h (http/request-headers m tool)]
    (is (true? (http/validate-param-headers! h m tool)))
    (is (= -32020 (ex-code #(http/validate-param-headers! (dissoc h "Mcp-Param-X-Tenant") m tool))))
    (is (= -32020 (ex-code #(http/validate-param-headers!
                             (assoc h "Mcp-Param-X-Tenant" "evil") m tool))))
    (testing "without a tool definition there is nothing to check"
      (is (true? (http/validate-param-headers! {} m nil))))))

;; ---------------------------------------------------------------------------
;; Status mapping and SSE framing
;; ---------------------------------------------------------------------------

(deftest error-codes-map-to-the-statuses-the-spec-pairs-them-with
  (is (= 400 (http/error-code->status mcp/parse-error)))
  (is (= 400 (http/error-code->status mcp/invalid-request)))
  (is (= 404 (http/error-code->status mcp/method-not-found)))
  (is (= 400 (http/error-code->status mcp/invalid-params)))
  (is (= 400 (http/error-code->status mcp/header-mismatch)))
  (is (= 400 (http/error-code->status mcp/missing-required-client-capability)))
  (is (= 400 (http/error-code->status mcp/unsupported-protocol-version)))
  (is (= 500 (http/error-code->status mcp/internal-error)))
  (is (= 200 (http/error-code->status -31000)) "an unpinned code is still a successful exchange"))

(deftest sse-events-frame-and-parse
  (let [msg (mcp/result-response 1 {"x" 1})]
    (is (= (str "event: message\ndata: " (mcp/write-json msg) "\n\n") (http/sse-event msg)))
    (is (= [msg] (http/parse-sse (http/sse-event msg))))
    (is (= [msg msg] (http/parse-sse (str (http/sse-event msg) (http/sse-event msg)))))))

(deftest parse-sse-skips-keep-alive-comments
  (let [msg (mcp/result-response 1 {"x" 1})]
    (is (= [msg] (http/parse-sse (str ": keep-alive\n\n" (http/sse-event msg)))))
    (is (= [] (http/parse-sse ": keep-alive\n\n")))
    (is (= [] (http/parse-sse "")))))

;; ---------------------------------------------------------------------------
;; handle-http
;; ---------------------------------------------------------------------------

(defn http-server []
  (server/server
   {:name "http-demo"
    :tools [{:name "echo"
             :input-schema annotated-schema
             :handler (fn [_ctx args] (get args "m" "ok"))}
            {:name "noisy"
             :handler (fn [ctx _args] ((:progress! ctx) 1 nil) "done")}]
    :resources-subscribe? true
    :tools-list-changed? true}))

(def srv (http-server))
(def tools-by-name {"echo" {"inputSchema" annotated-schema}})

(defn post
  ([message] (post message nil nil))
  ([message header-overrides] (post message header-overrides nil))
  ([message header-overrides opts]
   (http/handle-http srv
                     {:request-method :post
                      :headers (merge (http/request-headers message (get tools-by-name
                                                                         (get-in message ["params" "name"])))
                                      header-overrides)
                      :body (mcp/write-json message)}
                     (merge {:tools-by-name tools-by-name} opts))))

(deftest a-plain-request-answers-json
  (let [r (post (a-message "tools/list"))]
    (is (= 200 (:status r)))
    (is (= "application/json" (get (:headers r) "Content-Type")))
    (is (= 2 (count (get-in (mcp/read-json (:body r)) ["result" "tools"]))))))

(deftest only-post-is-accepted
  ;; SEP-2243 removed the GET endpoint; subscriptions/listen replaced it.
  (let [r (http/handle-http srv {:request-method :get :headers {} :body ""} nil)]
    (is (= 405 (:status r)))
    (is (= "POST" (get (:headers r) "Allow")))))

(deftest origin-is-validated-against-dns-rebinding
  (let [m (a-message "tools/list")
        allowed {:allowed-origins #{"http://localhost:3000"}}]
    (is (= 200 (:status (post m {"Origin" "http://localhost:3000"} allowed))))
    (is (= 403 (:status (post m {"Origin" "https://evil.example"} allowed))))
    (testing "an absent Origin is not a browser request and is not checked"
      (is (= 200 (:status (post m nil allowed)))))
    (testing "a predicate works as well as a set"
      (is (= 200 (:status (post m {"Origin" "http://localhost:9"}
                                {:allowed-origins #(str/starts-with? % "http://localhost:")})))))))

(deftest a-malformed-body-is-a-parse-error
  (let [r (http/handle-http srv {:request-method :post
                                 :headers {"MCP-Protocol-Version" mcp/latest-protocol-version
                                           "Mcp-Method" "tools/list"}
                                 :body "{not json"} nil)]
    (is (= 400 (:status r)))
    (is (= -32700 (get-in (mcp/read-json (:body r)) ["error" "code"])))))

(deftest a-notification-post-is-202-with-no-body
  (let [r (http/handle-http srv {:request-method :post
                                 :headers {"MCP-Protocol-Version" mcp/latest-protocol-version
                                           "Mcp-Method" "notifications/cancelled"}
                                 :body (mcp/write-json (mcp/cancelled-notification 1 "x"))} nil)]
    (is (= 202 (:status r)))
    (is (= "" (:body r)))))

(deftest a-header-mismatch-is-http-400-and-32020
  (let [r (post (a-message "tools/call" {"name" "echo"}) {"Mcp-Name" "other"})]
    (is (= 400 (:status r)))
    (is (= -32020 (get-in (mcp/read-json (:body r)) ["error" "code"])))))

(deftest a-param-header-mismatch-is-also-400
  (let [r (post (a-message "tools/call" {"name" "echo" "arguments" {"tenant" "acme"}})
                {"Mcp-Param-X-Tenant" "other-tenant"})]
    (is (= 400 (:status r)))
    (is (= -32020 (get-in (mcp/read-json (:body r)) ["error" "code"])))))

(deftest an-unknown-method-is-http-404
  (is (= 404 (:status (post (a-message "no/such/method"))))))

(deftest a-request-whose-handler-emits-notifications-answers-sse
  (let [msg (mcp/request 1 "tools/call"
                         {"name" "noisy" "arguments" {}
                          "_meta" (mcp/request-meta
                                   {:protocol-version mcp/latest-protocol-version
                                    :client-capabilities {}
                                    :progress-token "p"})})
        r (post msg)
        events (http/parse-sse (:body r))]
    (is (= 200 (:status r)))
    (is (= "text/event-stream" (get (:headers r) "Content-Type")))
    (is (= "no" (get (:headers r) "X-Accel-Buffering"))
        "buffering proxies would hold progress until the stream ended")
    (testing "the notifications precede the response, which terminates the stream"
      (is (= ["notifications/progress" nil] (mapv #(get % "method") events)))
      (is (= "done" (mcp/output-text (get (last events) "result")))))))

(deftest subscriptions-listen-opens-a-stream-instead-of-answering
  (let [m (a-message "subscriptions/listen" {"notifications" {"toolsListChanged" true}})
        r (post m)
        events (http/parse-sse (:body r))]
    (is (= 200 (:status r)))
    (is (= "text/event-stream" (get (:headers r) "Content-Type")))
    (is (= {:id 1 :filter {"toolsListChanged" true}} (:subscription r)))
    (is (= ["notifications/subscriptions/acknowledged"] (mapv #(get % "method") events)))))
