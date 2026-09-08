(ns tools.agents.mcp-test
  "Pure logic for the core namespace — codec, JSON-RPC framing, the typed
   error hierarchy, `_meta` accessors, content blocks and the multi round-trip
   constructors. Zero I/O, so it runs identically on JVM Clojure and Babashka.

   The first block is deliberately dull: it asserts the literal VALUE of every
   module constant. A wrong constant loads perfectly and then answers -32022
   to every well-formed request; a test that only exercises functions never
   sees it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]))

;; ---------------------------------------------------------------------------
;; Constants — the protocol values, asserted literally
;; ---------------------------------------------------------------------------

(deftest protocol-constants-have-their-literal-values
  (is (= "2026-07-28" mcp/latest-protocol-version))
  (is (= ["2026-07-28"] mcp/supported-protocol-versions))
  (is (= "2.0" mcp/jsonrpc-version))
  (is (= "complete" mcp/result-type-complete))
  (is (= "input_required" mcp/result-type-input-required)))

(deftest meta-keys-have-their-literal-values
  (is (= "io.modelcontextprotocol/protocolVersion" mcp/meta-protocol-version))
  (is (= "io.modelcontextprotocol/clientCapabilities" mcp/meta-client-caps))
  (is (= "io.modelcontextprotocol/clientInfo" mcp/meta-client-info))
  (is (= "io.modelcontextprotocol/logLevel" mcp/meta-log-level))
  (is (= "io.modelcontextprotocol/serverInfo" mcp/meta-server-info))
  (is (= "io.modelcontextprotocol/subscriptionId" mcp/meta-subscription-id))
  (is (= "progressToken" mcp/meta-progress-token)))

(deftest error-codes-have-their-literal-values
  (is (= -32700 mcp/parse-error))
  (is (= -32600 mcp/invalid-request))
  (is (= -32601 mcp/method-not-found))
  (is (= -32602 mcp/invalid-params))
  (is (= -32603 mcp/internal-error))
  (testing "codes this revision introduced"
    (is (= -32020 mcp/header-mismatch))
    (is (= -32021 mcp/missing-required-client-capability))
    (is (= -32022 mcp/unsupported-protocol-version))))

(deftest logging-levels-are-rfc-5424-order
  (is (= ["debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"]
         mcp/logging-levels)))

(deftest input-request-capability-table
  (is (= {"elicitation/create"     {"elicitation" {}}
          "sampling/createMessage" {"sampling" {}}
          "roots/list"             {"roots" {}}}
         mcp/input-request-capability)))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

(deftest write-json-scalars
  (is (= "null" (mcp/write-json nil)))
  (is (= "true" (mcp/write-json true)))
  (is (= "false" (mcp/write-json false)))
  (is (= "42" (mcp/write-json 42)))
  (is (= "\"hi\"" (mcp/write-json "hi")))
  (is (= "\"hi\"" (mcp/write-json :hi))))

(deftest write-json-collections
  (is (= "[1,2,3]" (mcp/write-json [1 2 3])))
  (is (= "[1,2,3]" (mcp/write-json '(1 2 3))))
  (is (= "{\"a\":1}" (mcp/write-json {:a 1})))
  (is (= "{}" (mcp/write-json {}))))

(deftest write-json-escapes-every-control-character
  ;; stdio frames one message per LINE. A tool that echoes a newline back must
  ;; not be able to split its own response in two, so the encoder escapes the
  ;; whole C0 range rather than only the six characters JSON requires.
  (is (= "\"a\\nb\"" (mcp/write-json "a\nb")))
  (is (= "\"a\\rb\"" (mcp/write-json "a\rb")))
  (is (= "\"a\\tb\"" (mcp/write-json "a\tb")))
  (is (= "\"a\\\"b\"" (mcp/write-json "a\"b")))
  (is (= "\"a\\\\b\"" (mcp/write-json "a\\b")))
  (is (= "\"a\\u0000b\"" (mcp/write-json (str "a" (char 0) "b"))))
  (is (= "\"a\\u001fb\"" (mcp/write-json (str "a" (char 31) "b"))))
  (testing "no encoded message can contain a raw newline"
    (let [line (mcp/write-json (mcp/result-response 1 (mcp/tool-result "x\ny\r\nz")))]
      (is (not (str/includes? line "\n")))
      (is (not (str/includes? line "\r"))))))

(deftest read-json-round-trips
  (doseq [v [nil true false 42 -7 "hi" "a\nb" "äöü" "日本語" "🎉" []
             [1 "two" nil] {} {"a" 1 "b" [true nil]}]]
    (is (= v (mcp/read-json (mcp/write-json v))) (pr-str v))))

(deftest read-json-decodes-unicode-escapes
  (is (= "a\nb" (mcp/read-json "\"a\\u000ab\"")))
  (is (= "ä" (mcp/read-json "\"\\u00e4\"")))
  (is (= "ä" (mcp/read-json "\"ä\"")))
  (testing "a code point above the BMP arrives as a surrogate PAIR of escapes"
    ;; The two halves must be decoded together — a lone surrogate is not a
    ;; character.
    (is (= "🎉" (mcp/read-json "\"\\ud83c\\udf89\"")))
    (is (= "a🎉b" (mcp/read-json "\"a\\ud83c\\udf89b\"")))))

(deftest read-json-rejects-garbage
  (is (thrown? Exception (mcp/read-json "{")))
  (is (thrown? Exception (mcp/read-json "not json"))))

(deftest json-key->str-normalises
  (is (= "a" (mcp/json-key->str :a)))
  (is (= "a" (mcp/json-key->str "a"))))

;; ---------------------------------------------------------------------------
;; JSON-RPC framing
;; ---------------------------------------------------------------------------

(deftest request-shape
  (is (= {"jsonrpc" "2.0" "id" 1 "method" "tools/list"} (mcp/request 1 "tools/list")))
  (is (= {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "echo"}}
         (mcp/request 1 "tools/call" {"name" "echo"})))
  (is (mcp/request? (mcp/request 1 "tools/list")))
  (is (not (mcp/request? (mcp/notification "notifications/cancelled")))))

(deftest notification-shape
  (is (= {"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}
         mcp/tools-list-changed-notification))
  (is (mcp/notification? mcp/tools-list-changed-notification))
  (testing "a notification is not a request: it carries no id"
    (is (not (contains? mcp/tools-list-changed-notification "id")))))

(deftest result-always-carries-a-result-type
  (is (= {"resultType" "complete"} (mcp/result {})))
  (is (= {"resultType" "complete"} (mcp/result nil)))
  (is (= {"x" 1 "resultType" "complete"} (mcp/result {"x" 1})))
  (testing "result-response stamps one when the body lacks it"
    (is (= {"jsonrpc" "2.0" "id" 1 "result" {"x" 1 "resultType" "complete"}}
           (mcp/result-response 1 {"x" 1}))))
  (testing "and leaves an explicit one alone"
    (is (= "input_required"
           (get-in (mcp/result-response
                    1 (mcp/input-required {:input-requests {"who" (mcp/list-roots)}}))
                   ["result" "resultType"])))))

(deftest error-response-shape
  (is (= {"jsonrpc" "2.0" "id" 1 "error" {"code" -32601 "message" "nope"}}
         (mcp/error-response 1 mcp/method-not-found "nope")))
  (is (= {"code" -32602 "message" "bad" "data" {"k" "v"}}
         (get (mcp/error-response 1 mcp/invalid-params "bad" {"k" "v"}) "error")))
  (is (mcp/error-response? (mcp/error-response 1 -32601 "x")))
  (is (not (mcp/error-response? (mcp/result-response 1 {})))))

(deftest unsupported-protocol-version-response-names-both-sides
  (let [r (mcp/unsupported-protocol-version-response 1 "2025-06-18")]
    (is (= -32022 (get-in r ["error" "code"])))
    (is (= ["2026-07-28"] (get-in r ["error" "data" "supported"])))
    (is (= "2025-06-18" (get-in r ["error" "data" "requested"])))))

;; ---------------------------------------------------------------------------
;; Typed errors
;; ---------------------------------------------------------------------------

(deftest error-bang-throws-a-typed-protocol-error
  (let [e (try (mcp/invalid-params! "bad") nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.mcp.error/protocol (:type (ex-data e))))
    (is (= -32602 (:code (ex-data e))))))

(deftest not-found-is-invalid-params-in-this-revision
  ;; -32002 ResourceNotFound was deleted; a missing resource is a bad param.
  (let [e (try (mcp/not-found! "gone") nil (catch Exception e e))]
    (is (= -32602 (:code (ex-data e))))))

(deftest missing-client-capability-carries-the-requirement
  (let [e (try (mcp/missing-client-capability! {"elicitation" {}}) nil (catch Exception e e))]
    (is (= -32021 (:code (ex-data e))))
    (is (= {"elicitation" {}} (get (:data (ex-data e)) "requiredCapabilities")))))

(deftest error-ex->response-converts-both-kinds
  (testing "a protocol error keeps its code, message and data"
    (let [e (try (mcp/invalid-params! "bad" {"k" "v"}) (catch Exception e e))
          r (mcp/error-ex->response 7 e)]
      (is (= 7 (get r "id")))
      (is (= -32602 (get-in r ["error" "code"])))
      (is (= {"k" "v"} (get-in r ["error" "data"])))))
  (testing "anything else becomes -32603 without leaking internals"
    (let [r (mcp/error-ex->response 7 (ex-info "boom" {:secret "s"}))]
      (is (= -32603 (get-in r ["error" "code"])))
      (is (nil? (get-in r ["error" "data" :secret]))))))

;; ---------------------------------------------------------------------------
;; _meta
;; ---------------------------------------------------------------------------

(def caps {"elicitation" {"url" {}} "sampling" {} "roots" {}})

(defn a-request
  ([] (a-request nil))
  ([extra]
   (mcp/request 1 "tools/call"
                (merge {"name" "echo"
                        "_meta" (mcp/request-meta
                                 (merge {:protocol-version mcp/latest-protocol-version
                                         :client-capabilities caps
                                         :client-info {"name" "t" "version" "1"}}
                                        extra))}
                       nil))))

(deftest request-meta-uses-the-reserved-keys
  (let [m (mcp/request-meta {:protocol-version "2026-07-28"
                             :client-capabilities caps
                             :client-info {"name" "t"}
                             :log-level "info"
                             :progress-token "p1"
                             :extra {"x.example/thing" 1}})]
    (is (= "2026-07-28" (get m mcp/meta-protocol-version)))
    (is (= caps (get m mcp/meta-client-caps)))
    (is (= {"name" "t"} (get m mcp/meta-client-info)))
    (is (= "info" (get m mcp/meta-log-level)))
    (is (= "p1" (get m mcp/meta-progress-token)))
    (is (= 1 (get m "x.example/thing")))))

(deftest meta-accessors-read-off-a-request
  (let [r (a-request {:log-level "warning" :progress-token 99})]
    (is (= mcp/latest-protocol-version (mcp/protocol-version r)))
    (is (= caps (mcp/client-capabilities r)))
    (is (= {"name" "t" "version" "1"} (mcp/client-info r)))
    (is (= "warning" (mcp/log-level r)))
    (is (= 99 (mcp/progress-token r))))
  (testing "absent optional fields read as nil, not as a default"
    (let [r (a-request)]
      (is (nil? (mcp/log-level r)))
      (is (nil? (mcp/progress-token r))))))

(deftest log-level-at-least?-follows-severity-order
  (is (mcp/log-level-at-least? "error" "info"))
  (is (mcp/log-level-at-least? "info" "info"))
  (is (not (mcp/log-level-at-least? "debug" "info")))
  (is (mcp/log-level-at-least? "emergency" "debug")))

;; ---------------------------------------------------------------------------
;; Capabilities
;; ---------------------------------------------------------------------------

(deftest client-supports?-walks-a-path
  (is (mcp/client-supports? caps ["elicitation"]))
  (is (mcp/client-supports? caps ["elicitation" "url"]))
  (is (not (mcp/client-supports? {"elicitation" {}} ["elicitation" "url"])))
  (is (not (mcp/client-supports? nil ["elicitation"]))))

(deftest missing-capabilities-returns-only-the-gap
  (is (nil? (mcp/missing-capabilities caps {"elicitation" {}})))
  (is (nil? (mcp/missing-capabilities caps {"elicitation" {"url" {}}})))
  (is (= {"elicitation" {"url" {}}}
         (mcp/missing-capabilities {"elicitation" {}} {"elicitation" {"url" {}}})))
  (is (= {"sampling" {}} (mcp/missing-capabilities {} {"sampling" {}}))))

(deftest require-capabilities!-throws-only-on-a-gap
  (is (true? (mcp/require-capabilities! caps {"roots" {}})))
  (let [e (try (mcp/require-capabilities! {} {"sampling" {}}) nil (catch Exception e e))]
    (is (= -32021 (:code (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Content blocks
;; ---------------------------------------------------------------------------

(deftest content-block-shapes
  (is (= {"type" "text" "text" "hi"} (mcp/text "hi")))
  (is (= {"type" "image" "data" "b" "mimeType" "image/png"} (mcp/image "b" "image/png")))
  (is (= {"type" "audio" "data" "b" "mimeType" "audio/wav"} (mcp/audio "b" "audio/wav")))
  (is (= {"type" "resource_link" "uri" "u" "name" "n" "mimeType" "text/plain"}
         (mcp/resource-link {:uri "u" :name "n" :mime-type "text/plain"})))
  (is (= {"type" "resource" "resource" {"uri" "u" "text" "t"}}
         (mcp/embedded-resource (mcp/text-contents "u" "t")))))

(deftest annotations-shape
  (is (= {"audience" ["user"] "priority" 0.5 "lastModified" "t"}
         (mcp/annotations {:audience ["user"] :priority 0.5 :last-modified "t"})))
  (testing "an absent field is omitted rather than sent as null"
    (is (= {"priority" 1} (mcp/annotations {:priority 1})))))

(deftest resource-contents-shapes
  (is (= {"uri" "u" "text" "t" "mimeType" "text/plain"} (mcp/text-contents "u" "t" "text/plain")))
  (is (= {"uri" "u" "blob" "YQ==" "mimeType" "application/octet-stream"}
         (mcp/blob-contents "u" "YQ==" "application/octet-stream"))))

(deftest message-shapes
  (is (= {"role" "user" "content" {"type" "text" "text" "hi"}}
         (mcp/user-message (mcp/text "hi"))))
  (is (= {"role" "assistant" "content" {"type" "text" "text" "hi"}}
         (mcp/assistant-message (mcp/text "hi"))))
  (is (= {"role" "user" "content" {"type" "text" "text" "hi"}}
         (mcp/prompt-message "user" (mcp/text "hi")))))

(deftest tool-result-shapes
  (is (= {"content" [{"type" "text" "text" "hi"}] "resultType" "complete"}
         (mcp/tool-result "hi")))
  (is (= {"x" 1} (get (mcp/tool-result [] {:structured-content {"x" 1}}) "structuredContent")))
  (is (true? (get (mcp/tool-error "boom") "isError")))
  (is (= "boom" (mcp/output-text (mcp/tool-error "boom"))))
  (is (= "a\nb" (mcp/output-text (mcp/tool-result [(mcp/text "a") (mcp/text "b")]))))
  (testing "output-text ignores non-text blocks rather than stringifying them"
    (is (= "a" (mcp/output-text (mcp/tool-result [(mcp/text "a") (mcp/image "b" "image/png")]))))))

;; ---------------------------------------------------------------------------
;; Notifications
;; ---------------------------------------------------------------------------

(deftest progress-notification-shape
  (is (= {"jsonrpc" "2.0" "method" "notifications/progress"
          "params" {"progressToken" "p" "progress" 2 "total" 5 "message" "half"}}
         (mcp/progress-notification "p" 2 {:total 5 :message "half"})))
  (is (= {"progressToken" "p" "progress" 2}
         (get (mcp/progress-notification "p" 2 nil) "params"))))

(deftest log-notification-shape
  (is (= {"jsonrpc" "2.0" "method" "notifications/message"
          "params" {"level" "info" "logger" "l" "data" "d"}}
         (mcp/log-notification "info" {:logger "l" :data "d"}))))

(deftest cancelled-notification-shape
  (is (= {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" {"requestId" 7}}
         (mcp/cancelled-notification 7)))
  (is (= "why" (get-in (mcp/cancelled-notification 7 "why") ["params" "reason"]))))

(deftest with-subscription-id-targets-the-right-meta
  (testing "a notification is tagged in params._meta"
    (is (= "s1" (get-in (mcp/with-subscription-id (mcp/resource-updated-notification "u") "s1")
                        ["params" "_meta" mcp/meta-subscription-id]))))
  (testing "a graceful-closure response is tagged in result._meta"
    (is (= "s1" (get-in (mcp/with-subscription-id (mcp/result-response 1 {}) "s1")
                        ["result" "_meta" mcp/meta-subscription-id])))))

;; ---------------------------------------------------------------------------
;; Multi round-trip request pattern
;; ---------------------------------------------------------------------------

(deftest input-request-constructors
  (is (= {"method" "elicitation/create"
          "params" {"mode" "form" "message" "m" "requestedSchema" {"type" "object"}}}
         (mcp/elicit-form "m" {"type" "object"})))
  (is (= {"method" "elicitation/create" "params" {"mode" "url" "message" "m" "url" "https://x"}}
         (mcp/elicit-url "m" "https://x")))
  (is (= {"method" "roots/list"} (mcp/list-roots)))
  (is (= "sampling/createMessage"
         (get (mcp/create-message {:messages [] :max-tokens 10}) "method"))))

(deftest input-required-shape
  (let [r (mcp/input-required {:input-requests {"who" (mcp/list-roots)} :request-state "st"})]
    (is (= "input_required" (get r "resultType")))
    (is (= {"who" {"method" "roots/list"}} (get r "inputRequests")))
    (is (= "st" (get r "requestState")))
    (is (mcp/input-required? r))
    (is (not (mcp/input-required? (mcp/tool-result "x")))))
  (testing "a result with neither inputRequests nor requestState is forbidden"
    (is (thrown? Exception (mcp/input-required {})))))

(deftest input-request-required-capability-distinguishes-url-mode
  (is (= {"roots" {}} (mcp/input-request-required-capability (mcp/list-roots))))
  (is (= {"elicitation" {}}
         (mcp/input-request-required-capability (mcp/elicit-form "m" {}))))
  (testing "URL-mode elicitation needs elicitation.url, not merely elicitation"
    (is (= {"elicitation" {"url" {}}}
           (mcp/input-request-required-capability (mcp/elicit-url "m" "https://x")))))
  (is (= {"sampling" {}}
         (mcp/input-request-required-capability (mcp/create-message {:messages [] :max-tokens 1})))))

(deftest retry-fields-read-off-the-followup-request
  (let [r (mcp/request 2 "tools/call"
                       {"name" "echo"
                        "inputResponses" {"who" {"roots" []}}
                        "requestState" "st"})]
    (is (= {"who" {"roots" []}} (mcp/input-responses r)))
    (is (= "st" (mcp/request-state r)))))
