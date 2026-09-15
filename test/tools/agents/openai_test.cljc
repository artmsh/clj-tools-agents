(ns tools.agents.openai-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka. Mock-server / transport-level coverage lives in
   tools.agents.openai.live-test."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.openai :as oai]
            [tools.agents.openai.embeddings :as embeddings]
            [tools.agents.openai.files :as files]
            [tools.agents.stream :as stream]
            [tools.agents.token :as token]
            [tools.agents.test-support :refer [recording-http input-stream recording-codec throwing-codec]]))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

;; Codec behaviour is tested once, in tools.agents.json-test. This pins the
;; wrapper wiring and this namespace's documented error contract.

(deftest json-codec-error-contract
  (is (= {"a" [1 "x"]} (oai/read-json (oai/write-json {:a [1 "x"]}))))
  (testing "every character below 0x20 is \\u00XX-escaped"
    (is (= "\"a\\u0001b\"" (oai/write-json (str "a" (char 1) "b")))))
  (testing "encode"
    (let [e (try (oai/write-json {"n" 2/3}) nil (catch Exception e e))]
      (is (= :tools.agents.openai/json-encode-error (:type (ex-data e))))
      (is (clojure.string/starts-with? (ex-message e) "tools.agents.openai/write-json: "))))
  (testing "parse"
    (doseq [bad ["{bad" "010"]]
      (let [e (try (oai/read-json bad) nil (catch Exception e e))]
        (is (= :tools.agents.openai/json-parse-error (:type (ex-data e))) bad)
        (is (clojure.string/starts-with? (ex-message e) "tools.agents.openai/read-json: ") bad)))))

(deftest output-text-handles-many-blocks
  (let [blocks (vec (repeat 5000 {"type" "output_text" "text" "ab"}))]
    (is (= (* 2 5000) (count (oai/output-text {"output" [{"type" "message" "content" blocks}]}))))))

;; ---------------------------------------------------------------------------
;; Credential resolution (pure — env access is injected, real env untouched)
;; ---------------------------------------------------------------------------

(deftest resolve-credentials-explicit-api-key-wins
  (is (= {:api-key "explicit"}
         (oai/resolve-credentials {:api-key "explicit"} (fn [_] "env-value")))))

(deftest resolve-credentials-falls-back-to-env-api-key
  (is (= {:api-key "from-env"}
         (oai/resolve-credentials {} (fn [n] (when (= n "OPENAI_API_KEY") "from-env"))))))

(deftest resolve-credentials-ignores-anthropic-env-var
  ;; Guard against a copy-paste regression from the sibling library.
  (let [e (try (oai/resolve-credentials {} (fn [n] (when (= n "ANTHROPIC_API_KEY") "wrong")))
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/missing-credentials (:type (ex-data e))))))

(deftest resolve-credentials-throws-before-any-network-call
  (let [e (try (oai/resolve-credentials {} (fn [_] nil)) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/missing-credentials (:type (ex-data e))))
    (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/client: "))))

;; ---------------------------------------------------------------------------
;; client construction — fail fast
;; ---------------------------------------------------------------------------

(deftest client-uses-explicit-api-key
  (is (= "explicit" (:api-key (oai/client {:api-key "explicit" :base-url "http://x/v1"})))))

(deftest client-default-base-url-carries-v1
  ;; openai-python's default base_url includes /v1 — endpoints append only
  ;; "/responses" to it. Regressing this to a host-only default (the sibling
  ;; library's shape) would silently produce ".../responses" without /v1.
  (is (= "https://api.openai.com/v1" oai/default-base-url))
  (is (= oai/default-base-url (:base-url (oai/client {:api-key "k"})))))

(deftest client-custom-base-url
  (is (= "http://127.0.0.1:9/v1" (:base-url (oai/client {:api-key "k" :base-url "http://127.0.0.1:9/v1"})))))

(deftest client-omits-organization-and-project-when-unset
  (let [c (oai/client {:api-key "k" :base-url "http://x/v1"})]
    (is (not (contains? c :organization)))
    (is (not (contains? c :project)))))

(deftest client-keeps-explicit-organization-and-project
  (let [c (oai/client {:api-key "k" :base-url "http://x/v1"
                       :organization "org-abc" :project "proj-xyz"})]
    (is (= "org-abc" (:organization c)))
    (is (= "proj-xyz" (:project c)))))

;; ---------------------------------------------------------------------------
;; output-text (Responses API)
;; ---------------------------------------------------------------------------

(deftest output-text-concatenates-output-text-blocks
  (is (= "hello world"
         (oai/output-text {"output" [{"type" "message" "role" "assistant"
                                      "content" [{"type" "output_text" "text" "hello "}
                                                 {"type" "output_text" "text" "world"}]}]}))))

(deftest output-text-spans-multiple-message-items
  (is (= "ab"
         (oai/output-text {"output" [{"type" "message" "content" [{"type" "output_text" "text" "a"}]}
                                     {"type" "message" "content" [{"type" "output_text" "text" "b"}]}]}))))

(deftest output-text-skips-non-message-output-items
  ;; The SDK property only descends into items whose type == "message" —
  ;; reasoning / function_call / web_search_call items are passed over.
  (is (= "visible"
         (oai/output-text {"output" [{"type" "reasoning" "summary" []}
                                     {"type" "function_call" "name" "f" "arguments" "{}"}
                                     {"type" "message" "content" [{"type" "output_text" "text" "visible"}]}]}))))

(deftest output-text-returns-empty-string-for-reasoning-only-output
  ;; Documented SDK contract: "If no `output_text` content blocks exist, then
  ;; an empty string is returned." DIVERGES from tools.agents.anthropic, which
  ;; throws instead — deliberate, see README.
  (is (= "" (oai/output-text {"output" [{"type" "reasoning" "summary" []}]})))
  (is (= "" (oai/output-text {"output" []}))))

(deftest output-text-returns-empty-string-for-refusal-only-message
  (is (= "" (oai/output-text {"output" [{"type" "message" "role" "assistant"
                                         "content" [{"type" "refusal" "refusal" "I can't help with that."}]}]}))))

(deftest output-text-skips-output-text-block-missing-text-key
  (is (= "only this"
         (oai/output-text {"output" [{"type" "message"
                                      "content" [{"type" "output_text"}
                                                 {"type" "output_text" "text" "only this"}]}]}))))

(deftest output-text-throws-when-output-missing
  (doseq [bad [{} {"output" "not-an-array"} {"output" {"type" "message"}}]]
    (testing (pr-str bad)
      (let [e (try (oai/output-text bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/output-text: "))))))

(deftest output-text-throws-on-message-with-non-array-content
  (let [e (try (oai/output-text {"output" [{"type" "message" "content" "oops"}]})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-content-shape (:type (ex-data e))))))

(deftest output-text-throws-on-non-string-text-value
  (doseq [bad-text [42 {"nested" "map"} [1 2] true]]
    (testing (pr-str bad-text)
      (let [e (try (oai/output-text {"output" [{"type" "message"
                                                "content" [{"type" "output_text" "text" bad-text}]}]})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/invalid-content-shape (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/output-text: "))))))

;; ---------------------------------------------------------------------------
;; completion-text (Chat Completions API)
;; ---------------------------------------------------------------------------

(deftest completion-text-reads-first-choice-message-content
  (is (= "ahoy"
         (oai/completion-text {"choices" [{"index" 0
                                           "message" {"role" "assistant" "content" "ahoy"}
                                           "finish_reason" "stop"}]}))))

(deftest completion-text-ignores-later-choices
  (is (= "first"
         (oai/completion-text {"choices" [{"message" {"content" "first"}}
                                          {"message" {"content" "second"}}]}))))

(deftest completion-text-returns-nil-for-null-content
  ;; The API sends "content": null on tool-call and refusal responses; the
  ;; SDK's `.content` is None there. Returning nil (not throwing) matches it.
  (is (nil? (oai/completion-text {"choices" [{"message" {"role" "assistant" "content" nil
                                                         "tool_calls" [{"id" "call_1"}]}
                                              "finish_reason" "tool_calls"}]})))
  (is (nil? (oai/completion-text {"choices" [{"message" {"role" "assistant"
                                                         "refusal" "I can't help with that."}}]}))))

(deftest completion-text-throws-on-malformed-shapes
  (doseq [[bad expected] [[{} :tools.agents.openai/invalid-response]
                          [{"choices" "nope"} :tools.agents.openai/invalid-response]
                          [{"choices" []} :tools.agents.openai/invalid-response]
                          [{"choices" [{}]} :tools.agents.openai/invalid-response]
                          [{"choices" [{"message" {"content" 42}}]} :tools.agents.openai/invalid-content-shape]]]
    (testing (pr-str bad)
      (let [e (try (oai/completion-text bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= expected (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/completion-text: "))))))

;; ---------------------------------------------------------------------------
;; Retry policy — pure port of openai-python's _base_client retry logic.
;; "now" and the RNG are injected, so nothing here sleeps or flakes.
;; ---------------------------------------------------------------------------

(deftest retryable-status-matches-python-sdk-policy
  (doseq [s [408 409 429 500 502 503 529]]
    (testing s (is (true? (oai/retryable-status? s)))))
  (doseq [s [200 201 400 401 403 404 422 nil]]
    (testing (pr-str s) (is (false? (oai/retryable-status? s))))))

(def ^:private t0 1445412480000) ;; 2015-10-21T07:28:00Z, in millis

(deftest parse-retry-after-ms-prefers-the-milliseconds-header
  ;; The SDK tries the non-standard retry-after-ms FIRST, being more precise
  ;; than integer-seconds retry-after.
  (is (= 1500 (oai/parse-retry-after-ms {"retry-after-ms" "1500" "retry-after" "9"} t0)))
  (is (= 1500 (oai/parse-retry-after-ms {"retry-after-ms" "1500"} t0))))

(deftest parse-retry-after-ms-reads-seconds-header
  (is (= 3000 (oai/parse-retry-after-ms {"retry-after" "3"} t0)))
  ;; "the spec says integer, but if someone sends a float there's no reason
  ;; for us to not respect it"
  (is (= 2500 (oai/parse-retry-after-ms {"retry-after" "2.5"} t0)))
  (is (= 3000 (oai/parse-retry-after-ms {"retry-after" " 3 "} t0))))

(deftest parse-retry-after-ms-is-case-insensitive-and-shape-tolerant
  ;; A server may send any casing (tools.agents.http lower-cases names),
  ;; and a multi-value header can arrive as a vector.
  (is (= 3000 (oai/parse-retry-after-ms {"Retry-After" "3"} t0)))
  (is (= 3000 (oai/parse-retry-after-ms {"RETRY-AFTER" "3"} t0)))
  (is (= 3000 (oai/parse-retry-after-ms {:retry-after "3"} t0)))
  (is (= 3000 (oai/parse-retry-after-ms {"retry-after" ["3" "9"]} t0))))

(deftest parse-retry-after-ms-reads-http-date
  ;; 60s past t0.
  (is (= 60000 (oai/parse-retry-after-ms {"retry-after" "Wed, 21 Oct 2015 07:29:00 GMT"} t0)))
  ;; Explicit numeric zone offset.
  (is (= 60000 (oai/parse-retry-after-ms {"retry-after" "Wed, 21 Oct 2015 09:29:00 +0200"} t0)))
  ;; A date already in the past yields a negative delay, which the callers'
  ;; `0 < v` gate then discards — same as the SDK.
  (is (= -60000 (oai/parse-retry-after-ms {"retry-after" "Wed, 21 Oct 2015 07:27:00 GMT"} t0))))

(deftest parse-retry-after-ms-handles-leap-day-and-epoch
  (is (= 0 (oai/parse-retry-after-ms {"retry-after" "Thu, 01 Jan 1970 00:00:00 GMT"} 0)))
  (is (= 1582977600000 (oai/parse-retry-after-ms {"retry-after" "Sat, 29 Feb 2020 12:00:00 GMT"} 0))))

(deftest parse-retry-after-ms-nil-when-absent-or-unparseable
  (doseq [headers [nil {} {"other" "x"}
                   {"retry-after" "not-a-date-or-number"}
                   ;; obsolete RFC-850 spelling — deliberately unsupported,
                   ;; callers fall back to exponential backoff
                   {"retry-after" "Wednesday, 21-Oct-15 07:29:00 GMT"}]]
    (testing (pr-str headers)
      (is (nil? (oai/parse-retry-after-ms headers t0))))))

(deftest should-retry-honors-x-should-retry-header
  ;; The server's explicit instruction beats the status code in both directions.
  (is (true? (oai/should-retry? 400 {"x-should-retry" "true"} t0)))
  (is (false? (oai/should-retry? 500 {"x-should-retry" "false"} t0)))
  ;; Case-sensitive exact match, as in the SDK — anything else falls through.
  (is (false? (oai/should-retry? 400 {"x-should-retry" "TRUE"} t0)))
  (is (true? (oai/should-retry? 500 {"x-should-retry" "maybe"} t0))))

(deftest should-retry-vetoes-retry-after-beyond-two-minutes
  (is (false? (oai/should-retry? 429 {"retry-after" "121"} t0)))
  (is (true? (oai/should-retry? 429 {"retry-after" "120"} t0)))
  (is (true? (oai/should-retry? 429 {"retry-after" "5"} t0))))

(deftest should-retry-falls-back-to-status
  (doseq [s [408 409 429 500 503]]
    (testing s (is (true? (oai/should-retry? s {} t0)))))
  (doseq [s [400 401 403 404 422 200]]
    (testing s (is (false? (oai/should-retry? s {} t0))))))

(deftest retry-delay-ms-exponential-backoff-capped
  ;; rand-fn 0.0 => jitter factor exactly 1.0, so this is the raw curve:
  ;; 0.5s, 1s, 2s, 4s, 8s, then capped at MAX_RETRY_DELAY = 8s.
  (let [no-jitter (fn [] 0.0)]
    (is (= [500 1000 2000 4000 8000 8000 8000]
           (mapv #(oai/retry-delay-ms % nil t0 no-jitter) (range 7))))))

(deftest retry-delay-ms-applies-jitter-within-sdk-bounds
  ;; SDK jitter is `1 - 0.25 * random()`, i.e. (0.75, 1.0].
  (let [max-jitter (fn [] 0.999)]
    (is (= 375 (oai/retry-delay-ms 0 nil t0 max-jitter))))
  (doseq [r [0.0 0.13 0.5 0.87 0.999]]
    (testing r
      (let [d (oai/retry-delay-ms 3 nil t0 (fn [] r))]   ;; base 4000
        (is (<= 3000 d 4000)))))
  ;; The 3-arity uses clojure.core/rand — still inside the same window.
  (let [d (oai/retry-delay-ms 1 nil t0)]                 ;; base 1000
    (is (<= 750 d 1000))))

(deftest retry-delay-ms-honors-retry-after-within-the-window
  (let [no-jitter (fn [] 0.0)]
    (is (= 5000 (oai/retry-delay-ms 0 {"retry-after" "5"} t0 no-jitter)))
    (is (= 250 (oai/retry-delay-ms 0 {"retry-after-ms" "250"} t0 no-jitter)))
    ;; Outside (0, 2min] the header is ignored and backoff takes over.
    (is (= 500 (oai/retry-delay-ms 0 {"retry-after" "0"} t0 no-jitter)))
    (is (= 500 (oai/retry-delay-ms 0 {"retry-after" "-5"} t0 no-jitter)))
    (is (= 500 (oai/retry-delay-ms 0 {"retry-after" "121"} t0 no-jitter)))))

(deftest client-defaults-and-overrides-max-retries
  (is (= 2 oai/default-max-retries))
  (is (= 2 (:max-retries (oai/client {:api-key "k" :base-url "http://x/v1"}))))
  (is (= 5 (:max-retries (oai/client {:api-key "k" :base-url "http://x/v1" :max-retries 5}))))
  (is (= 0 (:max-retries (oai/client {:api-key "k" :base-url "http://x/v1" :max-retries 0}))))
  ;; negative is clamped, not honored
  (is (= 0 (:max-retries (oai/client {:api-key "k" :base-url "http://x/v1" :max-retries -3})))))

(deftest client-accepts-credential-source
  (let [src (token/token-cache {:fetch! (fn [] (throw (ex-info "must not fetch at construction" {})))})
        c   (oai/client {:credential-source src :base-url "http://x/v1"})]
    (is (identical? src (:credential-source c)))
    (is (nil? (:api-key c))))
  (testing "conflicts and non-sources are rejected"
    (doseq [opts [{:credential-source (token/token-cache {:fetch! (fn [])}) :api-key "k"}
                  {:credential-source "not-a-source"}
                  {:credential-source nil}]]
      (is (= :tools.agents.openai/invalid-credentials
             (:type (ex-data (try (oai/client (assoc opts :base-url "http://x/v1")) nil
                                  (catch Exception e e)))))))))

(deftest client-returns-openai-client-record
  (let [client (oai/client {:api-key "k" :base-url "http://x/v1"})]
    (is (record? client))
    (is (= "k" (:api-key client)))
    (is (record? (assoc client :base-url "http://example.test/v1")))))

;; ---------------------------------------------------------------------------
;; Message-list helpers
;; ---------------------------------------------------------------------------

(deftest add-user-message-appends
  (is (= [{"role" "user" "content" "hi"}] (oai/add-user-message [] "hi"))))

(deftest add-assistant-message-appends
  (is (= [{"role" "user" "content" "hi"} {"role" "assistant" "content" "yo"}]
         (oai/add-assistant-message [{"role" "user" "content" "hi"}] "yo"))))

(deftest add-developer-and-system-messages-append
  (is (= [{"role" "developer" "content" "Talk like a pirate."}]
         (oai/add-developer-message [] "Talk like a pirate.")))
  (is (= [{"role" "system" "content" "Talk like a pirate."}]
         (oai/add-system-message [] "Talk like a pirate."))))

(deftest message-helpers-compose
  (is (= [{"role" "developer" "content" "d"} {"role" "user" "content" "u"}]
         (-> [] (oai/add-developer-message "d") (oai/add-user-message "u")))))

;; ---------------------------------------------------------------------------
;; :stream true rejection — never touches the network
;; ---------------------------------------------------------------------------

(deftest stream-true-rejected-on-responses-create
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1"})
        e (try (oai/responses-create client {"model" "m" "input" "hi" "stream" true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))
    (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/responses-create: "))))

(deftest stream-true-rejected-on-chat-completions-create
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1"})
        e (try (oai/chat-completions-create client {"model" "m" "messages" [] "stream" true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))
    (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/chat-completions-create: "))))

;; ---------------------------------------------------------------------------
;; Hand-built client maps — the credential check that `client` normally makes
;; unreachable. Throws before any network activity (nothing listens on :1).
;; ---------------------------------------------------------------------------

(deftest hand-built-client-without-api-key-is-rejected-per-fn
  (doseq [[f req prefix] [[oai/responses-create {"model" "m" "input" "hi"}
                           "tools.agents.openai/responses-create: "]
                          [oai/chat-completions-create {"model" "m" "messages" []}
                           "tools.agents.openai/chat-completions-create: "]]]
    (testing prefix
      (let [e (try (f {:base-url "http://127.0.0.1:1/v1"} req) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/missing-credentials (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) prefix))))))

(deftest stream-true-rejected-with-keyword-key-too
  (let [client (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1"})
        e (try (oai/responses-create client {:model "m" :input "hi" :stream true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Responses stream accumulation (pure)
;; ---------------------------------------------------------------------------

(def ^:private msg-item {"id" "msg_1" "type" "message" "role" "assistant" "status" "in_progress" "content" []})
(def ^:private fc-item {"id" "fc_1" "type" "function_call" "call_id" "call_1" "name" "lookup"
                        "arguments" "" "status" "in_progress"})

(defn- created [] {"type" "response.created"
                   "response" {"id" "resp_1" "object" "response" "status" "in_progress" "output" [] "error" nil}})

(def ^:private delta-events
  [(created)
   {"type" "response.output_item.added" "output_index" 0 "item" msg-item}
   {"type" "response.content_part.added" "output_index" 0 "content_index" 0
    "part" {"type" "output_text" "text" "" "annotations" []}}
   {"type" "response.output_item.added" "output_index" 1 "item" fc-item}
   {"type" "response.output_text.delta" "output_index" 0 "content_index" 0 "delta" "{\"answer\":"}
   {"type" "response.function_call_arguments.delta" "output_index" 1 "delta" "{\"q\":"}
   {"type" "response.output_text.delta" "output_index" 0 "content_index" 0 "delta" "4}"}
   {"type" "response.function_call_arguments.delta" "output_index" 1 "delta" "1}"}])

(deftest accumulate-response-event-arities
  (is (nil? (oai/accumulate-response-event)))
  (is (nil? (oai/accumulate-response-stream [])))
  (is (nil? (oai/accumulate-response-stream nil)))
  (is (= {"a" 1} (oai/accumulate-response-event {"a" 1})))
  (is (false? (oai/stream-complete? nil)))
  (is (false? (oai/stream-complete? {"status" "completed"})) "only a folded terminal event counts"))

(deftest accumulate-response-assembles-from-deltas-when-truncated
  (let [r (oai/accumulate-response-stream delta-events)]
    (is (false? (oai/stream-complete? r)))
    (is (= "in_progress" (get r "status")))
    (is (= "{\"answer\":4}" (oai/output-text r)))
    (is (vector? (get r "output")))
    (is (= [(assoc msg-item "content" [{"type" "output_text" "text" "{\"answer\":4}" "annotations" []}])
            (assoc fc-item "arguments" "{\"q\":1}")]
           (get r "output")))
    (is (empty? (dissoc (meta r) :tools.agents.openai/stream-complete?))
        "side state is stripped by the completion arity"))
  (testing "*.done values replace the accumulated ones"
    (let [r (oai/accumulate-response-stream
             (conj delta-events
                   {"type" "response.output_text.done" "output_index" 0 "content_index" 0 "text" "final"}
                   {"type" "response.function_call_arguments.done" "output_index" 1 "arguments" "{}"}))]
      (is (= "final" (oai/output-text r)))
      (is (= "{}" (get-in r ["output" 1 "arguments"])))))
  (testing "output_item.done replaces the item"
    (let [done (assoc fc-item "status" "completed" "arguments" "{\"q\":2}")
          r    (oai/accumulate-response-stream
                (conj delta-events {"type" "response.output_item.done" "output_index" 1 "item" done}))]
      (is (= done (get-in r ["output" 1])))))
  (testing "text delta without content_part.added creates the part"
    (let [r (oai/accumulate-response-stream
             [(created)
              {"type" "response.output_item.added" "output_index" 0 "item" msg-item}
              {"type" "response.output_text.delta" "output_index" 0 "content_index" 0 "delta" "hi"}])]
      (is (= "hi" (oai/output-text r)))))
  (testing "refusal deltas"
    (let [r (oai/accumulate-response-stream
             [(created)
              {"type" "response.output_item.added" "output_index" 0 "item" msg-item}
              {"type" "response.refusal.delta" "output_index" 0 "content_index" 0 "delta" "I can't"}
              {"type" "response.refusal.delta" "output_index" 0 "content_index" 0 "delta" " help."}])]
      (is (= [{"type" "refusal" "refusal" "I can't help."}] (get-in r ["output" 0 "content"])))
      (is (= "" (oai/output-text r))))))

(deftest accumulate-response-tolerates-null-items-and-orphan-deltas
  (let [r (oai/accumulate-response-stream
           [(created)
            {"type" "response.output_item.added" "output_index" 0}
            {"type" "response.output_item.added" "output_index" 0 "item" nil}
            {"type" "response.output_text.delta" "output_index" 0 "content_index" 0 "delta" "lost"}
            {"type" "response.output_item.added" "output_index" 2 "item" msg-item}
            {"type" "response.output_text.delta" "output_index" 2 "content_index" 0 "delta" "kept"}
            {"type" "response.function_call_arguments.delta" "output_index" 2 "delta" "wrong item type"}
            {"type" "response.reasoning_summary_text.delta" "output_index" 2 "delta" "unknown to the fold"}])]
    (is (= 1 (count (get r "output"))) "index gaps do not create nil items")
    (is (= "kept" (oai/output-text r)))
    (is (not (contains? (get-in r ["output" 0]) "arguments")))))

(deftest accumulate-response-terminal-events
  (testing "response.completed is the result, and later events are ignored"
    (let [final {"id" "resp_1" "status" "completed"
                 "output" [{"type" "message" "content" [{"type" "output_text" "text" "T"}]}]
                 "usage" {"total_tokens" 3}}
          r     (oai/accumulate-response-stream
                 (conj delta-events
                       {"type" "response.completed" "response" final}
                       {"type" "response.output_text.delta" "output_index" 0 "content_index" 0 "delta" "late"}))]
      (is (= final r))
      (is (oai/stream-complete? r))
      (is (= "T" (oai/output-text r)))))
  (testing "a null or missing output is recovered from output_item.done items"
    (let [done-msg (assoc msg-item "status" "completed" "content" [{"type" "output_text" "text" "done text"}])
          events   (conj delta-events
                         {"type" "response.output_item.done" "output_index" 1 "item" fc-item}
                         {"type" "response.output_item.done" "output_index" 0 "item" done-msg})]
      (doseq [resp [{"id" "resp_1" "status" "completed" "output" nil}
                    {"id" "resp_1" "status" "completed"}]]
        (let [r (oai/accumulate-response-stream (conj events {"type" "response.completed" "response" resp}))]
          (is (= [done-msg fc-item] (get r "output")))
          (is (= "done text" (oai/output-text r)))
          (is (oai/stream-complete? r))))))
  (testing "response.failed and response.incomplete are returned, not thrown"
    (doseq [[t resp] [["response.failed" {"id" "resp_1" "status" "failed" "output" []
                                          "error" {"code" "server_error"
                                                   "message" "The model failed to generate a response."}}]
                      ["response.incomplete" {"id" "resp_1" "status" "incomplete" "output" []
                                              "incomplete_details" {"reason" "max_tokens"}}]]]
      (let [r (oai/accumulate-response-stream [(created) {"type" t "response" resp}])]
        (is (= resp r) t)
        (is (oai/stream-complete? r) t)
        (is (= "" (oai/output-text r)) t)))))

(deftest accumulate-response-throws-on-error-events
  (let [e (try (oai/accumulate-response-stream
                [(created) {"type" "error" "code" "ERR_SOMETHING" "message" "Something went wrong"
                            "param" nil "sequence_number" 1}])
               nil (catch Exception e e))]
    (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
    (is (nil? (:status (ex-data e))))
    (is (= {"code" "ERR_SOMETHING" "message" "Something went wrong" "param" nil} (:error (ex-data e))))
    (is (= "tools.agents.openai/accumulate-response-event: stream error: Something went wrong" (ex-message e))))
  (testing "a top-level error object (openai-python _streaming.py rule)"
    (let [e (try (oai/accumulate-response-event nil {"error" {"message" "boom" "type" "server_error"}})
                 nil (catch Exception e e))]
      (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
      (is (= {"message" "boom" "type" "server_error"} (:error (ex-data e))))))
  (testing "a null error, or one nested inside response, is not an error (Responses)"
    (is (map? (oai/accumulate-response-event nil {"type" "x" "error" nil})))
    (is (map? (oai/accumulate-response-event nil {"type" "response.failed"
                                                  "response" {"status" "failed" "error" {"message" "m"}}})))))

;; ---------------------------------------------------------------------------
;; Chat Completions stream accumulation (pure)
;; ---------------------------------------------------------------------------

(defn- chunk [choices & {:as extra}]
  (merge {"id" "chatcmpl-1" "object" "chat.completion.chunk" "created" 1 "model" "m"
          "system_fingerprint" "fp_1" "choices" choices}
         extra))

(defn- ch [delta & {:as extra}]
  (merge {"index" 0 "delta" delta "logprobs" nil "finish_reason" nil} extra))

(deftest accumulate-chat-chunk-arities
  (is (nil? (oai/accumulate-chat-completion-chunk)))
  (is (= {"a" 1} (oai/accumulate-chat-completion-chunk {"a" 1})))
  (is (nil? (oai/accumulate-chat-completion-stream [])))
  (is (nil? (oai/accumulate-chat-completion-stream nil))))

(deftest accumulate-chat-text-and-seed
  (let [r (oai/accumulate-chat-completion-stream
           [(chunk [(ch {"role" "assistant" "content" ""})] "obfuscation" "x1")
            (chunk [(ch {"content" "Hel"})] "obfuscation" "x2")
            (chunk [(ch {"content" "lo"})] "system_fingerprint" "fp_2")
            (chunk [(ch {} "finish_reason" "stop")])])]
    (is (= {"id" "chatcmpl-1" "object" "chat.completion" "created" 1 "model" "m"
            "system_fingerprint" "fp_1" "usage" nil
            "choices" [{"index" 0 "message" {"role" "assistant" "content" "Hello"}
                        "logprobs" nil "finish_reason" "stop"}]}
           r)
        "system_fingerprint and usage follow the last chunk, as in the SDK")
    (is (= "Hello" (oai/completion-text r)))
    (is (not (contains? r "obfuscation")))
    (is (false? (oai/stream-complete? r)) "a collection never saw [DONE]")))

(deftest accumulate-chat-refusal
  (let [r (oai/accumulate-chat-completion-stream
           [(chunk [(ch {"role" "assistant" "content" nil "refusal" nil})])
            (chunk [(ch {"refusal" "I can't"})])
            (chunk [(ch {"refusal" " help with that."} "finish_reason" "stop")])])]
    (is (= "I can't help with that." (get-in r ["choices" 0 "message" "refusal"])))
    (is (nil? (oai/completion-text r)))))

(deftest accumulate-chat-tool-calls-by-index
  (let [r (oai/accumulate-chat-completion-stream
           [(chunk [(ch {"role" "assistant" "content" nil})])
            (chunk [(ch {"tool_calls" [{"index" 0 "id" "call_a" "type" "function"
                                        "function" {"name" "f" "arguments" ""}}
                                       {"index" 0 "function" {"arguments" "{\"x\""}}]})])
            (chunk [(ch {"tool_calls" [{"index" 1 "id" "call_b" "type" "function"
                                        "function" {"name" "g" "arguments" "{}"}}]})])
            (chunk [(ch {"tool_calls" [{"index" 0 "type" "function" "function" {"arguments" ":1}"}}]})])
            (chunk [(ch {} "finish_reason" "tool_calls")])])]
    (is (= [{"index" 0 "id" "call_a" "type" "function" "function" {"name" "f" "arguments" "{\"x\":1}"}}
            {"index" 1 "id" "call_b" "type" "function" "function" {"name" "g" "arguments" "{}"}}]
           (get-in r ["choices" 0 "message" "tool_calls"]))
        "two fragments for one index in the same chunk merge; type is replaced, not concatenated")
    (is (vector? (get-in r ["choices" 0 "message" "tool_calls"])))
    (is (= "tool_calls" (get-in r ["choices" 0 "finish_reason"])))
    (is (nil? (oai/completion-text r)))))

(deftest accumulate-chat-choices-by-index-logprobs-and-usage
  (let [lp (fn [& toks] {"content" (mapv (fn [t] {"token" t "logprob" -0.1}) toks) "refusal" nil})
        r  (oai/accumulate-chat-completion-stream
            [(chunk [(ch {"role" "assistant" "content" "A"} "logprobs" (lp "A"))])
             (chunk [(ch {"role" "assistant" "content" "B"} "index" 1 "logprobs" (lp "B"))
                     (ch {"content" "a"} "logprobs" (lp "a"))])
             (chunk [(ch {"content" "b"} "index" 1 "logprobs" {"content" [] "refusal" nil} "finish_reason" "length")
                     (ch {} "finish_reason" "stop" "logprobs" nil)])
             (chunk [] "usage" {"prompt_tokens" 5 "completion_tokens" 4 "total_tokens" 9})])]
    (is (= ["Aa" "Bb"] (mapv #(get-in % ["message" "content"]) (get r "choices"))))
    (is (= [0 1] (mapv #(get % "index") (get r "choices"))))
    (is (= [["A" "a"] ["B"]] (mapv (fn [c] (mapv #(get % "token") (get-in c ["logprobs" "content"]))) (get r "choices")))
        "a choice first seen mid-stream does not get its logprobs twice")
    (is (= ["stop" "length"] (mapv #(get % "finish_reason") (get r "choices"))))
    (is (= {"prompt_tokens" 5 "completion_tokens" 4 "total_tokens" 9} (get r "usage"))
        "include_usage: the last chunk has empty choices and the usage")
    (is (= "Aa" (oai/completion-text r)))))

(deftest accumulate-chat-skips-non-chunk-objects-and-throws-on-errors
  (let [r (oai/accumulate-chat-completion-stream
           [{"object" "" "choices" [] "prompt_filter_results" []}
            (chunk [(ch {"role" "assistant" "content" "ok"})])
            {"object" "" "choices" [{"index" 0 "delta" {"content" "!"}}]}])]
    (is (= "ok" (oai/completion-text r))))
  (let [e (try (oai/accumulate-chat-completion-stream
                [(chunk [(ch {"content" "a"})]) {"error" {"message" "Rate limited" "code" "rate_limit_exceeded"}}])
               nil (catch Exception e e))]
    (is (= :tools.agents.openai/stream-error (:type (ex-data e))))
    (is (= "tools.agents.openai/accumulate-chat-completion-chunk: stream error: Rate limited" (ex-message e)))
    (is (= "rate_limit_exceeded" (get-in (ex-data e) [:error "code"]))))
  (let [e (try (oai/accumulate-chat-completion-stream
                [(chunk [(ch {"tool_calls" [{"index" 0 "id" "call_a"}]})])
                 (chunk [(ch {"tool_calls" [{"id" "no-index"}]})])])
               nil (catch Exception e e))]
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; Injected :http (#10) — every exchange goes through the caller's fn
;; ---------------------------------------------------------------------------

(defn- fake-client [http & {:as extra}]
  (oai/client (merge {:api-key "k" :base-url "https://fake.example/v1" :http http} extra)))

(deftest injected-http-carries-responses-create
  (let [{:keys [http calls]} (recording-http
                              (fn [_] {:status 200 :headers {}
                                       :body "{\"id\":\"resp_1\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}"}))
        r (oai/responses-create (fake-client http) {"model" "m" "input" "x"})]
    (is (= "hi" (oai/output-text r)))
    (let [[req & more] @calls]
      (is (empty? more))
      (is (= :post (:method req)))
      (is (= "https://fake.example/v1/responses" (:url req)))
      (is (= "Bearer k" (get (:headers req) "authorization")))
      (is (= {"model" "m" "input" "x"} (oai/read-json (:body req)))))))

(deftest injected-http-carries-resource-namespaces
  (testing "embeddings (JSON)"
    (let [{:keys [http calls]} (recording-http
                                (fn [_] {:status 200 :headers {}
                                         :body "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.5]}]}"}))
          r (embeddings/embeddings-create (fake-client http) {"model" "e" "input" "x" "encoding_format" "float"})]
      (is (= [0.5] (get-in r ["data" 0 "embedding"])))
      (is (= "https://fake.example/v1/embeddings" (:url (first @calls))))))
  (testing "files-content (:as :bytes)"
    (let [{:keys [http calls]} (recording-http (fn [_] {:status 200 :headers {} :body (.getBytes "raw" "UTF-8")}))
          r (files/files-content (fake-client http) "file_1")]
      (is (= "raw" (String. ^bytes r "UTF-8")))
      (is (= :bytes (:as (first @calls)))))))

(deftest injected-http-carries-a-stream
  (let [sse (slurp "test/resources/sse/openai-chat-text.sse")]
    (doseq [[label body-fn] [["InputStream body" input-stream] ["String body (lenient)" identity]]]
      (testing label
        (let [{:keys [http calls]} (recording-http (fn [_] {:status 200
                                                            :headers {"content-type" "text/event-stream"}
                                                            :body (body-fn sse)}))
              s (oai/chat-completions-stream (fake-client http) {"model" "m" "messages" []})
              r (oai/accumulate-chat-completion-stream s)]
          (is (= "Hello" (oai/completion-text r)))
          (is (= :done (stream/outcome s)))
          (is (= :stream (:as (first @calls))))
          (is (= "https://fake.example/v1/chat/completions" (:url (first @calls)))))))))

(deftest injected-http-stream-error-status-is-typed
  (let [{:keys [http]} (recording-http (fn [_] {:status 400 :headers {}
                                                :body (input-stream "{\"error\":{\"message\":\"bad\"}}")}))
        e (try (oai/responses-stream (fake-client http) {"model" "m"}) nil (catch Exception e e))]
    (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))))

(deftest injected-http-transport-failure-is-retried-then-typed
  (let [{:keys [http calls]} (recording-http (fn [_] (throw (java.io.IOException. "reset"))))
        e (try (oai/responses-create (fake-client http :max-retries 0) {"model" "m"}) nil
               (catch Exception e e))]
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (= 1 (count @calls)))))

(deftest invalid-http-option-is-rejected-at-construction
  (doseq [bad [nil "request!" {:method :get} :http]]
    (let [e (try (oai/client {:api-key "k" :http bad}) nil (catch Exception e e))]
      (is (= :tools.agents.openai/invalid-options (:type (ex-data e))) (pr-str bad))))
  (is (not (contains? (oai/client {:api-key "k"}) :http)) "absent unless injected"))

;; ---------------------------------------------------------------------------
;; Injected :json (#10)
;; ---------------------------------------------------------------------------

(deftest injected-json-encodes-and-decodes-the-wire
  (let [{:keys [json reads writes]} (recording-codec)
        {:keys [http calls]} (recording-http
                              (fn [_] {:status 200 :headers {}
                                       :body "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}"}))
        c (fake-client http :json json)]
    (is (= "hi" (oai/output-text (oai/responses-create c {"model" "m"}))))
    (is (= [1 1] [@writes @reads]))
    (is (clojure.string/starts-with? (:body (first @calls)) " "))))

(deftest injected-json-decodes-stream-events
  (let [{:keys [json reads]} (recording-codec)
        {:keys [http]} (recording-http (fn [_] {:status 200 :headers {}
                                                :body (input-stream (slurp "test/resources/sse/openai-chat-text.sse"))}))
        r (oai/accumulate-chat-completion-stream
           (oai/chat-completions-stream (fake-client http :json json) {"model" "m" "messages" []}))]
    (is (= "Hello" (oai/completion-text r)))
    (is (pos? @reads))))

(deftest injected-json-errors-are-this-namespaces-types
  (let [{:keys [http]} (recording-http (fn [_] {:status 200 :headers {} :body "{\"output\":[]}"}))
        thrown (fn [f] (try (f) nil (catch Exception e e)))
        enc (thrown #(oai/responses-create (fake-client http :json throwing-codec) {"model" "m"}))
        dec (thrown #(oai/responses-create (fake-client http :json (assoc throwing-codec :write oai/write-json))
                                           {"model" "m"}))]
    (is (= :tools.agents.openai/json-encode-error (:type (ex-data enc))))
    (is (= "codec write boom" (ex-message (ex-cause enc))))
    (is (= :tools.agents.openai/json-parse-error (:type (ex-data dec))))
    (is (clojure.string/starts-with? (ex-message dec) "tools.agents.openai/read-json: "))
    (is (= "codec read boom" (ex-message (ex-cause dec))))))

(deftest invalid-json-option-is-rejected-at-construction
  (doseq [bad [nil {} {:write oai/write-json} "jsonista"]]
    (let [e (try (oai/client {:api-key "k" :json bad}) nil (catch Exception e e))]
      (is (= :tools.agents.openai/invalid-options (:type (ex-data e))) (pr-str bad)))))

#?(:bb
   (deftest cheshire-codec-end-to-end
     ;; bb bundles cheshire; the JVM classpath has no alternative codec.
     (require 'cheshire.core)
     (let [parse (resolve 'cheshire.core/parse-string)
           gen   (resolve 'cheshire.core/generate-string)
           {:keys [http calls]} (recording-http
                                 (fn [req]
                                   {:status 200 :headers {}
                                    :body (if (= :stream (:as req))
                                            (input-stream (slurp "test/resources/sse/openai-chat-text.sse"))
                                            "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}")}))
           c (fake-client http :json {:read #(parse %) :write #(gen %)})]
       (is (= "hi" (oai/output-text (oai/responses-create c {"model" "m" "input" [{"role" "user"}]}))))
       (is (= {"model" "m" "input" [{"role" "user"}]} (oai/read-json (:body (first @calls)))))
       (is (= "Hello" (oai/completion-text (oai/accumulate-chat-completion-stream
                                            (oai/chat-completions-stream c {"model" "m" "messages" []})))))
       (let [e (try (oai/responses-create (fake-client (constantly {:status 200 :headers {} :body "{bad"})
                                                       :json {:read #(parse %) :write #(gen %)})
                                          {"model" "m"})
                    nil (catch Exception e e))]
         (is (= :tools.agents.openai/json-parse-error (:type (ex-data e))))
         (is (some? (ex-cause e)))))))

;; ---------------------------------------------------------------------------
;; SDK default timeouts
;; ---------------------------------------------------------------------------

(deftest client-timeouts-default-to-the-sdk-values
  (let [c (oai/client {:api-key "k"})]
    (is (= [600000 5000] [(:timeout-ms c) (:connect-timeout-ms c)])))
  (let [c (oai/client {:api-key "k" :timeout-ms 1000 :connect-timeout-ms nil})]
    (is (= [1000 nil] [(:timeout-ms c) (:connect-timeout-ms c)]) "nil disables, not defaults"))
  (doseq [[k v] [[:timeout-ms 0] [:timeout-ms "600"] [:connect-timeout-ms -5]]]
    (let [e (try (oai/client {:api-key "k" k v}) nil (catch Exception e e))]
      (is (= :tools.agents.openai/invalid-options (:type (ex-data e))) (pr-str [k v]))
      (is (= k (:option (ex-data e)))))))

(deftest timeouts-reach-the-http-fn-with-client-and-per-request-overrides
  (let [{:keys [http calls]} (recording-http
                              (fn [req] {:status 200 :headers {}
                                         :body (if (= :stream (:as req)) (input-stream "") "{\"output\":[]}")}))
        c (fake-client http)]
    (oai/responses-create c {"model" "m"})
    (oai/responses-create (assoc c :timeout-ms 42) {"model" "m"})
    (oai/request! c {:method :get :path "/models" :timeout-ms nil :connect-timeout-ms 7})
    (into [] (oai/responses-stream (fake-client http :timeout-ms 9) {"model" "m"}))
    (is (= [[600000 5000] [42 5000] [nil 7] [9 5000]]
           (mapv (juxt :timeout-ms :connect-timeout-ms) @calls)))))

(deftest a-timeout-is-retried-then-typed-as-a-timed-out-connection-error
  (let [{:keys [http calls]} (recording-http (fn [_] (throw (java.net.http.HttpTimeoutException. "request timed out"))))
        e (with-redefs [oai/sleep! (fn [_])]
            (try (oai/responses-create (fake-client http :max-retries 2) {"model" "m"}) nil
                 (catch Exception e e)))]
    (is (= :tools.agents.openai/api-connection-error (:type (ex-data e))))
    (is (true? (:timeout? (ex-data e))))
    (is (= 2 (:retries-taken (ex-data e))))
    (is (instance? java.net.http.HttpTimeoutException (ex-cause e)))
    (is (= 3 (count @calls)) "timeouts are retried like other transport failures, as in the SDK"))
  (let [{:keys [http]} (recording-http (fn [_] (throw (java.net.ConnectException. "refused"))))
        e (try (oai/responses-create (fake-client http :max-retries 0) {"model" "m"}) nil (catch Exception e e))]
    (is (not (contains? (ex-data e) :timeout?)) "only a timeout is flagged")))
