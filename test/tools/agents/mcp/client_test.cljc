(ns tools.agents.mcp.client-test
  "Client-side logic, driven against a real `tools.agents.mcp.server/handle`
   through an in-process loopback transport.

   That loopback is not a mock. `handle` is a pure function from a request map
   to a response map, and `client`'s only I/O seam is `:send!`, which is a
   function of exactly that shape — so wiring them together is the whole
   protocol running end to end with the transport removed. What a real
   transport adds on top (framing, headers, subprocesses) is tested in
   tools.agents.mcp.stdio-test and tools.agents.mcp.http-test."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.mcp :as mcp]
            [tools.agents.mcp.client :as client]
            [tools.agents.mcp.server :as server]))

;; ---------------------------------------------------------------------------
;; Loopback transport
;; ---------------------------------------------------------------------------

(defn loopback
  "Returns {:send! f :sent atom :notifications atom} — `send!` runs the
   request through `handle` and hands back the response, keeping every
   request it saw and every notification the server emitted."
  [srv]
  (let [sent (atom [])
        notes (atom [])]
    {:sent sent
     :notifications notes
     :send! (fn [req]
              (swap! sent conj req)
              (let [r (server/handle srv req)]
                (swap! notes into (:notifications r))
                (:response r)))}))

(defn demo-server []
  (server/server
   {:name "demo" :version "1.2.3"
    :page-size 2
    :tools
    [{:name "echo" :handler (fn [_ctx args] (get args "m"))}
     {:name "hello"
      :handler (fn [ctx _args]
                 (if-let [r (get (:input-responses ctx) "who")]
                   (str "hello " (get-in r ["content" "name"]))
                   (mcp/input-required
                    {:input-requests
                     {"who" (mcp/elicit-form "Who are you?"
                                             {"type" "object"
                                              "properties" {"name" {"type" "string"}}})}
                     :request-state "opaque-blob"})))}
     {:name "greedy"
      ;; Never satisfied — exercises the retry bound.
      :handler (fn [_ctx _args]
                 (mcp/input-required {:input-requests {"again" (mcp/list-roots)}
                                      :request-state "s"}))}
     {:name "wants-sampling"
      :handler (fn [_ctx _args]
                 (mcp/input-required
                  {:input-requests {"s" (mcp/create-message {:messages [] :max-tokens 1})}}))}
     {:name "t3" :handler (fn [_ _] "3")}
     {:name "t4" :handler (fn [_ _] "4")}]
    :prompts [{:name "greet" :handler (fn [_ _] [(mcp/user-message (mcp/text "hi"))])}]
    :resources [{:name "r" :uri "test://r" :handler (fn [_ uri] "body")}]
    :resource-templates [{:name "n" :uri-template "test://n/{id}"
                          :handler (fn [_ uri vars] (str "n=" (get vars "id")))}]
    :completions (fn [_ctx _ref argument _context]
                   (vec (filter #(str/starts-with? % (get argument "value" ""))
                                ["ada" "alan"])))}))

(defn a-client
  ([lb] (a-client lb nil))
  ([lb overrides]
   (client/client (merge {:name "demo-client" :version "0.1"
                          :capabilities {"elicitation" {} "roots" {}}
                          :send! (:send! lb)
                          :input-handlers
                          {"elicitation/create"
                           (fn [_req] {"action" "accept" "content" {"name" "ada"}})
                           "roots/list" (fn [_req] {"roots" []})}}
                         overrides))))

;; ---------------------------------------------------------------------------
;; Request construction
;; ---------------------------------------------------------------------------

(deftest every-request-carries-the-required-meta
  (let [lb (loopback (demo-server))
        c (a-client lb)]
    (client/discover! c)
    (let [m (get-in (first @(:sent lb)) ["params" "_meta"])]
      (is (= mcp/latest-protocol-version (get m mcp/meta-protocol-version)))
      (is (= {"elicitation" {} "roots" {}} (get m mcp/meta-client-caps)))
      (is (= {"name" "demo-client" "version" "0.1"} (get m mcp/meta-client-info))))))

(deftest client-info-can-be-suppressed
  (let [lb (loopback (demo-server))
        c (a-client lb {:advertise-client-info? false})]
    (client/discover! c)
    (is (nil? (get-in (first @(:sent lb)) ["params" "_meta" mcp/meta-client-info])))))

(deftest ids-are-minted-per-send-and-never-reused
  (let [lb (loopback (demo-server))
        c (a-client lb)]
    (is (= 1 (client/next-request-id c)))
    (is (= 2 (client/next-request-id c)))
    (client/discover! c)
    (client/list-tools! c)
    (is (= [3 4] (mapv #(get % "id") @(:sent lb))))))

(deftest opt-ins-are-per-request
  (let [lb (loopback (demo-server))
        c (a-client lb)]
    (client/call-tool! c "echo" {"m" "x"} {:log-level "info" :progress-token "p"})
    (let [m (get-in (first @(:sent lb)) ["params" "_meta"])]
      (is (= "info" (get m mcp/meta-log-level)))
      (is (= "p" (get m mcp/meta-progress-token))))
    (client/call-tool! c "echo" {"m" "x"})
    (let [m (get-in (second @(:sent lb)) ["params" "_meta"])]
      (is (nil? (get m mcp/meta-log-level)))
      (is (nil? (get m mcp/meta-progress-token))))))

;; ---------------------------------------------------------------------------
;; Results and errors
;; ---------------------------------------------------------------------------

(deftest result-of-backfills-an-absent-result-type
  ;; A server from an earlier revision omits resultType; the client MUST read
  ;; that as "complete" rather than rejecting the response.
  (is (= "complete" (get (client/result-of {"jsonrpc" "2.0" "id" 1 "result" {"x" 1}})
                         "resultType")))
  (is (= "input_required"
         (get (client/result-of {"jsonrpc" "2.0" "id" 1
                                 "result" {"resultType" "input_required"}})
              "resultType"))))

(deftest result-of-rejects-a-malformed-response
  (is (thrown? Exception (client/result-of "nope")))
  (is (thrown? Exception (client/result-of {"jsonrpc" "2.0" "id" 1}))))

(deftest error-responses-become-typed-exceptions
  (doseq [[code t] [[-32700 :tools.agents.mcp.error/parse]
                    [-32600 :tools.agents.mcp.error/invalid-request]
                    [-32601 :tools.agents.mcp.error/method-not-found]
                    [-32602 :tools.agents.mcp.error/invalid-params]
                    [-32603 :tools.agents.mcp.error/internal]
                    [-32020 :tools.agents.mcp.error/header-mismatch]
                    [-32021 :tools.agents.mcp.error/missing-client-capability]
                    [-32022 :tools.agents.mcp.error/unsupported-protocol-version]
                    [-31999 :tools.agents.mcp.error/api]]]
    (let [e (client/error-response->ex (mcp/error-response 1 code "msg" {"d" 1}))]
      (is (= t (:type (ex-data e))) (str code))
      (is (= code (:code (ex-data e))))
      (is (= {"d" 1} (:data (ex-data e)))))))

(deftest a-server-error-throws-through-the-client
  (let [lb (loopback (demo-server))
        c (a-client lb)
        e (try (client/call-tool! c "nope" {}) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/invalid-params (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Discovery, listing, pagination
;; ---------------------------------------------------------------------------

(deftest discover-returns-the-server-side-of-the-handshake-that-no-longer-exists
  (let [c (a-client (loopback (demo-server)))
        r (client/discover! c)]
    (is (= ["2026-07-28"] (get r "supportedVersions")))
    (is (= {"name" "demo" "version" "1.2.3"}
           (get-in r ["_meta" mcp/meta-server-info])))))

(deftest single-page-listing-exposes-the-cursor
  (let [c (a-client (loopback (demo-server)))
        p1 (client/list-tools! c)]
    (is (= 2 (count (get p1 "tools"))))
    (is (some? (get p1 "nextCursor")))
    (is (= 2 (count (get (client/list-tools! c (get p1 "nextCursor")) "tools"))))))

(deftest list-all-follows-every-cursor
  (let [lb (loopback (demo-server))
        c (a-client lb)]
    (is (= ["echo" "hello" "greedy" "wants-sampling" "t3" "t4"]
           (mapv #(get % "name") (client/list-all-tools! c))))
    (is (= 3 (count @(:sent lb))) "six tools at two per page is three requests")
    (is (= ["greet"] (mapv #(get % "name") (client/list-all-prompts! c))))
    (is (= ["test://r"] (mapv #(get % "uri") (client/list-all-resources! c))))
    (is (= ["test://n/{id}"]
           (mapv #(get % "uriTemplate") (client/list-all-resource-templates! c))))))

;; ---------------------------------------------------------------------------
;; Reads, prompts, completions
;; ---------------------------------------------------------------------------

(deftest reads-and-prompts-and-completions
  (let [c (a-client (loopback (demo-server)))]
    (is (= "body" (get-in (client/read-resource! c "test://r") ["contents" 0 "text"])))
    (is (= "n=9" (get-in (client/read-resource! c "test://n/9") ["contents" 0 "text"])))
    (is (= "hi" (get-in (client/get-prompt! c "greet") ["messages" 0 "content" "text"])))
    (is (= ["ada" "alan"]
           (get-in (client/complete! c (client/prompt-ref "greet") {"name" "who" "value" "a"})
                   ["completion" "values"])))))

(deftest refs-have-the-schema-shapes
  (is (= {"type" "ref/prompt" "name" "greet"} (client/prompt-ref "greet")))
  (is (= {"type" "ref/resource" "uri" "test://n/{id}"} (client/resource-ref "test://n/{id}"))))

;; ---------------------------------------------------------------------------
;; Multi round-trip requests
;; ---------------------------------------------------------------------------

(deftest mrtr-round-trip-completes-the-call
  (let [lb (loopback (demo-server))
        c (a-client lb)
        r (client/call-tool! c "hello" {})]
    (is (= "hello ada" (mcp/output-text r)))
    (is (= 2 (count @(:sent lb))) "one ask, one retry")
    (let [[first-req retry] @(:sent lb)]
      (testing "the retry is an independent request with a NEW id"
        (is (not= (get first-req "id") (get retry "id"))))
      (testing "requestState is echoed back verbatim, never parsed"
        (is (= "opaque-blob" (get-in retry ["params" "requestState"]))))
      (testing "the responses are keyed exactly as the server keyed the asks"
        (is (= {"who" {"action" "accept" "content" {"name" "ada"}}}
               (get-in retry ["params" "inputResponses"]))))
      (testing "the original params survive the retry"
        (is (= "hello" (get-in retry ["params" "name"])))))))

(deftest mrtr-gives-up-rather-than-looping-forever
  (let [c (a-client (loopback (demo-server)) {:max-rounds 2})
        e (try (client/call-tool! c "greedy" {}) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/mrtr-exhausted (:type (ex-data e))))))

(deftest mrtr-is-refused-on-methods-that-do-not-support-it
  (let [c (a-client (loopback (demo-server)))
        e (try (client/with-mrtr c "tools/list" {}) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/mrtr-unsupported-method (:type (ex-data e))))))

(deftest a-client-refuses-an-input-request-it-never-declared
  ;; The server MUST NOT have asked. Answering anyway would hand it data it
  ;; was not entitled to request, so this is the client's own backstop.
  (let [srv (demo-server)
        lb (loopback srv)
        ;; Reach past the server's -32021 by asking the result directly.
        res (mcp/input-required
             {:input-requests {"s" (mcp/create-message {:messages [] :max-tokens 1})}})
        c (a-client lb)
        e (try (client/fulfil-input-requests c res) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/undeclared-input-request (:type (ex-data e))))
    (is (= {"sampling" {}} (:required (ex-data e))))))

(deftest a-client-without-a-handler-says-so
  (let [c (a-client (loopback (demo-server))
                    {:capabilities {"sampling" {}} :input-handlers {}})
        res (mcp/input-required
             {:input-requests {"s" (mcp/create-message {:messages [] :max-tokens 1})}})
        e (try (client/fulfil-input-requests c res) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/no-input-handler (:type (ex-data e))))))

(deftest the-server-refuses-first-when-the-capability-was-not-declared
  (let [c (a-client (loopback (demo-server)) {:capabilities {}})
        e (try (client/call-tool! c "wants-sampling" {}) nil (catch Exception ex ex))]
    (is (= :tools.agents.mcp.error/missing-client-capability (:type (ex-data e))))
    (is (= {"sampling" {}} (get (:data (ex-data e)) "requiredCapabilities")))))

;; ---------------------------------------------------------------------------
;; Subscriptions and cancellation
;; ---------------------------------------------------------------------------

(deftest listen!-builds-the-request-without-sending-it
  (let [lb (loopback (demo-server))
        c (a-client lb)
        r (client/listen! c {"toolsListChanged" true})]
    (is (= "subscriptions/listen" (get r "method")))
    (is (= {"toolsListChanged" true} (get-in r ["params" "notifications"])))
    (is (empty? @(:sent lb)) "the stream is the transport's business")))

(deftest cancel!-sends-a-notification-and-returns-nothing
  (let [lb (loopback (demo-server))
        c (a-client lb)]
    (is (nil? (client/cancel! c 3 "user quit")))
    (is (= {"jsonrpc" "2.0" "method" "notifications/cancelled"
            "params" {"requestId" 3 "reason" "user quit"}}
           (first @(:sent lb))))))

;; ---------------------------------------------------------------------------
;; Backward-compatibility probe
;; ---------------------------------------------------------------------------

(deftest probe-classifies-a-modern-server
  (let [c (a-client (loopback (demo-server)))
        p (client/probe! c)]
    (is (= :modern (:era p)))
    (is (= ["2026-07-28"] (get (:result p) "supportedVersions")))))

(deftest probe-classifies-a-modern-server-that-refuses-the-version
  (let [c (client/client
           {:name "p"
            :send! (fn [req] (mcp/unsupported-protocol-version-response
                              (get req "id") (get-in req ["params" "_meta"
                                                          mcp/meta-protocol-version])))})
        p (client/probe! c)]
    (is (= :modern (:era p)))
    (is (= {:supported ["2026-07-28"] :requested "2026-07-28"} (:unsupported-version p)))))

(deftest probe-falls-back-for-anything-else
  ;; A pre-2026-07-28 server answers an unknown request with whatever it
  ;; likes — or with nothing — so the fallback is not keyed to one code.
  (doseq [resp [(mcp/error-response 1 -32601 "Method not found")
                (mcp/error-response 1 -32602 "Invalid request parameters")
                (mcp/error-response 1 -32603 "Internal error")]]
    (let [c (client/client {:name "p" :send! (fn [_] resp)})]
      (is (= :legacy (:era (client/probe! c))) (pr-str resp))))
  (testing "a transport that throws is also a legacy classification"
    (let [c (client/client {:name "p" :send! (fn [_] (throw (ex-info "EOF" {})))})]
      (is (= :legacy (:era (client/probe! c)))))))
