(ns tools.agents.openai-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka. Mock-server / transport-level coverage lives in
   tools.agents.openai.live-test."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.openai :as oai]))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

(defn- a-read-json-obj
  "Wrap text as the sole string value of a JSON object and decode it — the
   shape a real API response puts a model's reply in."
  [text]
  (oai/read-json (str "{\"content\":" (oai/write-json text) "}")))

(deftest write-json-scalars
  (is (= "null" (oai/write-json nil)))
  (is (= "true" (oai/write-json true)))
  (is (= "false" (oai/write-json false)))
  (is (= "42" (oai/write-json 42)))
  (is (= "1.5" (oai/write-json 1.5)))
  (is (= "\"hi\"" (oai/write-json "hi")))
  (is (= "\"hi\"" (oai/write-json :hi))))

(deftest write-json-string-escaping
  (is (= "\"a\\nb\"" (oai/write-json "a\nb")))
  (is (= "\"a\\tb\"" (oai/write-json "a\tb")))
  (is (= "\"a\\\"b\"" (oai/write-json "a\"b")))
  (is (= "\"a\\\\b\"" (oai/write-json "a\\b"))))

(deftest write-json-collections
  (is (= "[1,2,3]" (oai/write-json [1 2 3])))
  (is (= "[1,2,3]" (oai/write-json '(1 2 3))))
  (is (contains? #{"{\"a\":1,\"b\":2}" "{\"b\":2,\"a\":1}"} (oai/write-json {:a 1 "b" 2})))
  (is (= "{\"max_output_tokens\":10}" (oai/write-json {:max_output_tokens 10}))))

(deftest write-json-rejects-unsupported
  (is (thrown? Exception (oai/write-json (fn [])))))

(deftest write-json-rejects-ratios
  ;; A bare ratio (e.g. from ordinary integer division on JVM/bb) would
  ;; otherwise be stringified as unquoted `n/d` — syntactically invalid JSON.
  (let [e (try (oai/write-json {"max_output_tokens" 2/3}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/json-encode-error (:type (ex-data e))))))

(deftest read-json-scalars
  (is (= nil (oai/read-json "null")))
  (is (= true (oai/read-json "true")))
  (is (= false (oai/read-json "false")))
  (is (= 42 (oai/read-json "42")))
  (is (integer? (oai/read-json "42")))
  (is (= 1.5 (oai/read-json "1.5")))
  (is (= "hi" (oai/read-json "\"hi\""))))

(deftest read-json-string-escapes
  (is (= "a\nb" (oai/read-json "\"a\\nb\"")))
  (is (= "a\tb" (oai/read-json "\"a\\tb\"")))
  (is (= "a\"b" (oai/read-json "\"a\\\"b\"")))
  (is (= "a\\b" (oai/read-json "\"a\\\\b\"")))
  (is (= "é" (oai/read-json "\"\\u00e9\""))))

(deftest read-json-collections
  (is (= [1 2 3] (oai/read-json "[1,2,3]")))
  (is (vector? (oai/read-json "[1,2,3]")))
  (is (= {} (oai/read-json "{}")))
  (is (= [] (oai/read-json "[]")))
  (is (= {"a" 1 "b" [1 2 {"c" true}]} (oai/read-json "{\"a\":1,\"b\":[1,2,{\"c\":true}]}"))))

(deftest read-json-object-keys-are-strings
  (let [decoded (oai/read-json "{\"model\":\"x\"}")]
    (is (= "x" (get decoded "model")))
    (is (not (contains? decoded :model)))))

(deftest read-json-roundtrip
  (let [v {"model" "gpt-5.5" "max_output_tokens" 10
           "input" [{"role" "user" "content" "hi"}]
           "temperature" 1.0 "store" true "previous_response_id" nil}]
    (is (= v (oai/read-json (oai/write-json v))))))

(deftest read-json-rejects-leading-zero-numbers
  ;; JSON's number grammar forbids a leading zero followed by more digits;
  ;; Clojure's reader would otherwise silently misread such a token as an
  ;; *octal* literal ("010" -> 8) if handed straight to read-string.
  (doseq [bad ["010" "-010" "{\"input_tokens\":010}"]]
    (testing bad
      (let [e (try (oai/read-json bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/json-parse-error (:type (ex-data e))))))))

(deftest read-json-handles-long-strings
  ;; Regression: parse-string used to accumulate one piece per character and
  ;; join them with `(apply str pieces)` — one argument per character of the
  ;; response. Both the escape-free fast path and the escaped slow path are
  ;; exercised here at length.
  (doseq [n [600 5000 50000]]
    (testing (str "plain, " n " chars")
      (let [payload (clojure.string/join "" (repeat n "x"))]
        (is (= payload (get (a-read-json-obj payload) "content")))))
    (testing (str "escaped, " n " chars")
      ;; every 10th character is an escape, so the slow path runs too
      (let [payload (clojure.string/join "" (repeat (quot n 10) "abcdefghi\n"))]
        (is (= payload (get (a-read-json-obj payload) "content")))))))

(deftest write-json-handles-long-strings
  (let [payload (clojure.string/join "" (repeat 50000 "x"))]
    (is (= payload (oai/read-json (oai/write-json payload))))))

(deftest output-text-handles-many-blocks
  (let [blocks (vec (repeat 5000 {"type" "output_text" "text" "ab"}))]
    (is (= (* 2 5000) (count (oai/output-text {"output" [{"type" "message" "content" blocks}]}))))))

(deftest read-json-malformed-throws
  (doseq [bad ["{bad" "[1,2" "\"unterminated" "not json at all" "{\"a\":}" "{\"a\" 1}"]]
    (testing bad
      (let [e (try (oai/read-json bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.openai/json-parse-error (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.openai/read-json: "))))))

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
  ;; A server may send any casing, java.net.http/babashka lower-case theirs,
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
