(ns tools.agents.anthropic-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka. Mock-server / transport-level coverage lives in
   tools.agents.anthropic.live-test."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.anthropic :as a]))

;; ---------------------------------------------------------------------------
;; JSON codec
;; ---------------------------------------------------------------------------

(deftest write-json-scalars
  (is (= "null" (a/write-json nil)))
  (is (= "true" (a/write-json true)))
  (is (= "false" (a/write-json false)))
  (is (= "42" (a/write-json 42)))
  (is (= "1.5" (a/write-json 1.5)))
  (is (= "\"hi\"" (a/write-json "hi")))
  (is (= "\"hi\"" (a/write-json :hi))))

(deftest write-json-string-escaping
  (is (= "\"a\\nb\"" (a/write-json "a\nb")))
  (is (= "\"a\\tb\"" (a/write-json "a\tb")))
  (is (= "\"a\\\"b\"" (a/write-json "a\"b")))
  (is (= "\"a\\\\b\"" (a/write-json "a\\b"))))

(deftest write-json-collections
  (is (= "[1,2,3]" (a/write-json [1 2 3])))
  (is (= "[1,2,3]" (a/write-json '(1 2 3))))
  (is (contains? #{"{\"a\":1,\"b\":2}" "{\"b\":2,\"a\":1}"} (a/write-json {:a 1 "b" 2})))
  (is (= "{\"max_tokens\":10}" (a/write-json {:max_tokens 10}))))

(deftest write-json-rejects-unsupported
  (is (thrown? Exception (a/write-json (fn [])))))

(deftest write-json-rejects-ratios
  ;; A bare ratio (e.g. from ordinary integer division on JVM/bb) would
  ;; otherwise be stringified as unquoted `n/d` — syntactically invalid JSON.
  (let [e (try (a/write-json {"max_tokens" 2/3}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/json-encode (:type (ex-data e))))))

(deftest read-json-scalars
  (is (= nil (a/read-json "null")))
  (is (= true (a/read-json "true")))
  (is (= false (a/read-json "false")))
  (is (= 42 (a/read-json "42")))
  (is (integer? (a/read-json "42")))
  (is (= 1.5 (a/read-json "1.5")))
  (is (= "hi" (a/read-json "\"hi\""))))

(deftest read-json-string-escapes
  (is (= "a\nb" (a/read-json "\"a\\nb\"")))
  (is (= "a\tb" (a/read-json "\"a\\tb\"")))
  (is (= "a\"b" (a/read-json "\"a\\\"b\"")))
  (is (= "a\\b" (a/read-json "\"a\\\\b\"")))
  (is (= "é" (a/read-json "\"\\u00e9\""))))

(deftest read-json-collections
  (is (= [1 2 3] (a/read-json "[1,2,3]")))
  (is (vector? (a/read-json "[1,2,3]")))
  (is (= {} (a/read-json "{}")))
  (is (= [] (a/read-json "[]")))
  (is (= {"a" 1 "b" [1 2 {"c" true}]} (a/read-json "{\"a\":1,\"b\":[1,2,{\"c\":true}]}"))))

(deftest read-json-object-keys-are-strings
  (let [decoded (a/read-json "{\"model\":\"x\"}")]
    (is (= "x" (get decoded "model")))
    (is (not (contains? decoded :model)))))

(deftest read-json-roundtrip
  (let [v {"model" "claude" "max_tokens" 10 "messages" [{"role" "user" "content" "hi"}]
           "temperature" 1.0 "ok" true "nothing" nil}]
    (is (= v (a/read-json (a/write-json v))))))

(deftest read-json-rejects-leading-zero-numbers
  ;; JSON's number grammar forbids a leading zero followed by more digits;
  ;; Clojure's reader would otherwise silently misread such a token as an
  ;; *octal* literal ("010" -> 8) if handed straight to read-string.
  (doseq [bad ["010" "-010" "{\"input_tokens\":010}"]]
    (testing bad
      (let [e (try (a/read-json bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/json-parse (:type (ex-data e))))))))

(deftest read-json-decodes-a-long-string-value-without-crashing
  ;; Regression guard: parse-string used to collect every character of a
  ;; JSON string value into a vector and spread it through `(apply str
  ;; pieces)` — one argument per character. str/join has no such arg-count
  ;; limit.
  (let [long-text (clojure.string/join (repeat 1500 "x"))]
    (is (= long-text (a/read-json (a/write-json long-text)))))
  ;; Longer, and through the escaped slow path too (every 10th char is an
  ;; escape) — the fast path returns one `subs`, the slow path accumulates
  ;; whole literal runs, and both must round-trip identically.
  (doseq [n [5000 50000]]
    (testing (str "plain, " n " chars")
      (let [text (clojure.string/join (repeat n "x"))]
        (is (= text (get (a/read-json (str "{\"t\":" (a/write-json text) "}")) "t")))))
    (testing (str "escaped, " n " chars")
      (let [text (clojure.string/join (repeat (quot n 10) "abcdefghi\n"))]
        (is (= text (get (a/read-json (str "{\"t\":" (a/write-json text) "}")) "t")))))))

(deftest output-text-concatenates-many-blocks-without-crashing
  ;; Same arg-count trap as read-json's, one argument per text BLOCK rather
  ;; than per character: `(apply str texts)` on a completion carrying enough
  ;; text blocks (a long tool-use turn reaches this). str/join has no such
  ;; limit.
  (let [content (vec (repeat 5000 {"type" "text" "text" "ab"}))]
    (is (= (* 2 5000) (count (a/output-text {"content" content}))))))

(deftest read-json-malformed-throws
  (doseq [bad ["{bad" "[1,2" "\"unterminated" "not json at all" "{\"a\":}" "{\"a\" 1}"]]
    (testing bad
      (let [e (try (a/read-json bad) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/json-parse (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.anthropic/read-json: "))))))

;; ---------------------------------------------------------------------------
;; Credential resolution (pure — env access is injected, real env untouched)
;; ---------------------------------------------------------------------------

(deftest resolve-credentials-explicit-api-key-wins
  (is (= {:api-key "explicit"}
         (a/resolve-credentials {:api-key "explicit"} (fn [_] "env-value")))))

(deftest resolve-credentials-explicit-auth-token-wins
  (is (= {:auth-token "explicit"}
         (a/resolve-credentials {:auth-token "explicit"} (fn [_] "env-value")))))

(deftest resolve-credentials-falls-back-to-env-api-key
  (is (= {:api-key "from-env"}
         (a/resolve-credentials {} (fn [n] (when (= n "ANTHROPIC_API_KEY") "from-env"))))))

(deftest resolve-credentials-falls-back-to-env-auth-token
  (is (= {:auth-token "from-env"}
         (a/resolve-credentials {} (fn [n] (when (= n "ANTHROPIC_AUTH_TOKEN") "from-env"))))))

(deftest resolve-credentials-api-key-env-beats-auth-token-env
  (is (= {:api-key "k"}
         (a/resolve-credentials {} (fn [n] (case n "ANTHROPIC_API_KEY" "k" "ANTHROPIC_AUTH_TOKEN" "t" nil))))))

(deftest resolve-credentials-throws-before-any-network-call
  (let [e (try (a/resolve-credentials {} (fn [_] nil)) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/missing-credentials (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; output-text
;; ---------------------------------------------------------------------------

(deftest output-text-concatenates-text-blocks
  (is (= "hello world"
         (a/output-text {"content" [{"type" "text" "text" "hello "}
                                     {"type" "text" "text" "world"}]}))))

(deftest output-text-skips-non-text-blocks
  (is (= "only this"
         (a/output-text {"content" [{"type" "thinking" "thinking" "ignored"}
                                     {"type" "text" "text" "only this"}]}))))

(deftest output-text-throws-when-no-text-blocks
  (let [e (try (a/output-text {"content" [{"type" "tool_use"}]}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/no-text-content (:type (ex-data e))))
    (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.anthropic/output-text: "))))

(deftest output-text-throws-when-content-missing
  (let [e (try (a/output-text {}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/invalid-response (:type (ex-data e))))))

(deftest output-text-skips-text-block-missing-text-key
  ;; Matches the original Zig builtin's collectOutputText: a "text"-typed
  ;; block with no "text" field at all is skipped (`orelse continue`), NOT
  ;; an error — only a *present but non-string* "text" value throws.
  (is (= "only this"
         (a/output-text {"content" [{"type" "text"}
                                     {"type" "text" "text" "only this"}]}))))

(deftest output-text-throws-on-non-string-text-value
  (doseq [bad-text [42 {"nested" "map"} [1 2] true]]
    (testing (pr-str bad-text)
      (let [e (try (a/output-text {"content" [{"type" "text" "text" bad-text}]})
                   nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/invalid-content-shape (:type (ex-data e))))
        (is (clojure.string/starts-with? (str (ex-message e)) "tools.agents.anthropic/output-text: "))))))

;; ---------------------------------------------------------------------------
;; Message-list helpers
;; ---------------------------------------------------------------------------

(deftest add-user-message-appends
  (is (= [{"role" "user" "content" "hi"}] (a/add-user-message [] "hi"))))

(deftest add-assistant-message-appends
  (is (= [{"role" "user" "content" "hi"} {"role" "assistant" "content" "yo"}]
         (a/add-assistant-message [{"role" "user" "content" "hi"}] "yo"))))

(deftest message-helpers-compose
  (is (= [{"role" "user" "content" "a"} {"role" "assistant" "content" "```json"}]
         (-> [] (a/add-user-message "a") (a/add-assistant-message "```json")))))

;; ---------------------------------------------------------------------------
;; Message content-block DSL
;; ---------------------------------------------------------------------------

(deftest content-blocks-wraps-a-string
  (is (= [{"type" "text" "text" "hi"}] (a/content-blocks "hi"))))

(deftest content-blocks-wraps-a-single-map
  (is (= [{"type" "tool_use" "id" "t1" "name" "n" "input" {}}]
         (a/content-blocks {"type" "tool_use" "id" "t1" "name" "n" "input" {}}))))

(deftest content-blocks-normalizes-a-mixed-seq
  (is (= [{"type" "text" "text" "look"} {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]
         (a/content-blocks ["look" {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]))))

(deftest content-blocks-empty-and-nil-normalize-to-empty-vector
  (is (= [] (a/content-blocks nil)))
  (is (= [] (a/content-blocks []))))

(deftest content-blocks-accepts-a-lazy-seq-not-just-a-vector
  ;; The new top-level coll? guard (added to reject bare scalars
  ;; consistently, see content-blocks-rejects-bare-scalar-consistently)
  ;; must not regress the accept path for a non-vector seqable -- every
  ;; other content-blocks test here happens to use a literal vector.
  (is (= [{"type" "text" "text" "A"} {"type" "text" "text" "B"}]
         (a/content-blocks (map str ["A" "B"])))))

(deftest content-blocks-rejects-non-string-non-map-items
  (let [e (try (a/content-blocks [42]) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/invalid-content-block (:type (ex-data e))))))

(deftest content-blocks-drops-nil-items-from-a-mixed-seq
  ;; Regression guard: a nil seq item (the ordinary `(when guard? val)`
  ;; idiom producing nil when its guard is false, e.g. `[(when
  ;; include-image? (a/image-url u)) (a/text \"hi\")]`) used to throw --
  ;; treating a common Clojure conditional pattern as malformed input,
  ;; where it previously passed through embedded verbatim before this
  ;; widening existed at all.
  (is (= [{"type" "text" "text" "hi"}]
         (a/content-blocks [nil (a/text "hi") nil])))
  (is (= [] (a/content-blocks [nil nil]))))

(deftest content-blocks-rejects-bare-scalar-consistently
  ;; Regression guard: a bare scalar (neither string, map, nor collection)
  ;; used to fall through to `map`/`seq` and throw an untyped
  ;; java.lang.IllegalArgumentException. Now it's a typed ex-info.
  (doseq [bad [42 true :kw]]
    (let [e (try (a/content-blocks bad) nil (catch Exception e e))]
      (is (some? e))
      (is (= :tools.agents.anthropic.error/invalid-content-block (:type (ex-data e)))))))

(deftest add-user-message-rejects-bare-scalar-content-consistently
  (let [e (try (a/add-user-message [] 42) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/invalid-content-block (:type (ex-data e))))))

(deftest text-block-with-and-without-opts
  (is (= {"type" "text" "text" "hi"} (a/text "hi")))
  (is (= {"type" "text" "text" "hi" "cache_control" {"type" "ephemeral"}}
         (a/text "hi" {"cache_control" {"type" "ephemeral"}}))))

(deftest image-and-document-block-constructors
  (is (= {"type" "image" "source" {"type" "base64" "media_type" "image/png" "data" "AAAA"}}
         (a/image-base64 "image/png" "AAAA")))
  (is (= {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}
         (a/image-url "http://x/y.png")))
  (is (= {"type" "document" "source" {"type" "base64" "media_type" "application/pdf" "data" "AAAA"}}
         (a/document-base64 "application/pdf" "AAAA")))
  (is (= {"type" "document" "source" {"type" "url" "url" "http://x/y.pdf"}}
         (a/document-url "http://x/y.pdf")))
  (is (= {"type" "document" "source" {"type" "text" "media_type" "text/plain" "data" "raw text"}}
         (a/document-text "raw text"))))

(deftest thinking-and-tool-use-block-constructors
  (is (= {"type" "thinking" "thinking" "reasoning..." "signature" "sig123"}
         (a/thinking "reasoning..." "sig123")))
  (is (= {"type" "tool_use" "id" "toolu_1" "name" "get_weather" "input" {"location" "SF"}}
         (a/tool-use "toolu_1" "get_weather" {"location" "SF"}))))

(deftest add-user-message-widened-string-unchanged
  (is (= [{"role" "user" "content" "hi"}] (a/add-user-message [] "hi"))))

(deftest add-user-message-widened-accepts-mixed-content
  (is (= [{"role" "user" "content" [{"type" "text" "text" "look"}
                                     {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]}]
         (a/add-user-message [] [(a/text "look") (a/image-url "http://x/y.png")]))))

(deftest add-user-message-with-conditional-nil-in-content-does-not-throw
  ;; The common `(when guard? val)` idiom -- a false guard leaves a nil in
  ;; the content vector, ordinary Clojure, not malformed input.
  (is (= [{"role" "user" "content" [{"type" "text" "text" "look"}]}]
         (a/add-user-message [] [(when false (a/image-url "http://x/y.png")) (a/text "look")]))))

(deftest add-assistant-message-widened-replays-response-content-verbatim
  (let [response-content [{"type" "thinking" "thinking" "..." "signature" "sig"}
                          {"type" "tool_use" "id" "t1" "name" "n" "input" {}}]]
    (is (= [{"role" "assistant" "content" response-content}]
           (a/add-assistant-message [] response-content)))))

(deftest system-prompt-uses-content-blocks-directly
  (is (= [{"type" "text" "text" "preamble" "cache_control" {"type" "ephemeral"}}
          {"type" "text" "text" "Be terse."}]
         (a/content-blocks [(a/text "preamble" {"cache_control" {"type" "ephemeral"}})
                             (a/text "Be terse.")]))))

;; ---------------------------------------------------------------------------
;; :stream true rejection — never touches the network
;; ---------------------------------------------------------------------------

(deftest stream-true-rejected-with-explicit-error
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})
        e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" [] "stream" true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/streaming-unsupported (:type (ex-data e))))))

(deftest stream-true-rejected-with-keyword-key-too
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})
        e (try (a/messages-create client {:model "m" :max_tokens 1 :messages [] :stream true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/streaming-unsupported (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; client construction — fail fast
;; ---------------------------------------------------------------------------

(deftest client-returns-anthropic-client-record
  (let [client (a/client {:api-key "k"})]
    (is (record? client))
    (is (= "k" (:api-key client)))
    (is (record? (assoc client :base-url "http://example.test")))))

(deftest client-uses-explicit-api-key
  (is (= "explicit" (:api-key (a/client {:api-key "explicit" :base-url "http://x"})))))

(deftest client-default-base-url
  (is (= a/default-base-url (:base-url (a/client {:api-key "k"})))))

(deftest client-custom-base-url
  (is (= "http://127.0.0.1:9" (:base-url (a/client {:api-key "k" :base-url "http://127.0.0.1:9"})))))

(deftest client-default-max-retries
  (is (= a/default-max-retries (:max-retries (a/client {:api-key "k"})))))

(deftest client-explicit-max-retries
  (is (= 0 (:max-retries (a/client {:api-key "k" :max-retries 0})))))

(deftest client-rejects-negative-max-retries
  ;; Regression guard: a negative :max-retries used to be accepted
  ;; silently and disabled all retries with no signal at all -- -1 is
  ;; truthy in Clojure's `or`, so it passed straight through, and
  ;; (< attempt -1) is always false, so a transient 429/5xx threw on the
  ;; very first attempt with zero retries and no error about the bad
  ;; config. spec.clj's own ::max-retries spec already documented
  ;; non-negative as the contract; client now enforces it directly.
  (let [e (try (a/client {:api-key "k" :max-retries -1}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/invalid-max-retries (:type (ex-data e))))))

(deftest client-rejects-non-integer-max-retries
  (let [e (try (a/client {:api-key "k" :max-retries 1.5}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/invalid-max-retries (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; request-with-retries! — pure logic, zero I/O: attempt-fn is a plain
;; function, never a real network call, so retry COUNT and backoff/
;; Retry-After SELECTION are both fully testable without a mock server.
;; ---------------------------------------------------------------------------

(defn- throwing [type status headers]
  (fn [] (throw (ex-info "boom" (cond-> {:type type}
                                   status (assoc :status status)
                                   headers (assoc :headers headers))))))

(deftest retries-retryable-status-until-success
  (let [calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (< @calls 3) ((throwing :tools.agents.anthropic.error/rate-limit 429 nil)) :ok))]
    (binding [a/*sleep-fn* (fn [_] nil)]
      (is (= :ok (a/request-with-retries! 5 attempt-fn)))
      (is (= 3 @calls)))))

(deftest gives-up-after-max-retries-exhausted
  (let [calls (atom 0)
        attempt-fn (fn [] (swap! calls inc) ((throwing :tools.agents.anthropic.error/internal-server 500 nil)))]
    (binding [a/*sleep-fn* (fn [_] nil)]
      (let [e (try (a/request-with-retries! 2 attempt-fn) nil (catch Exception e e))]
        (is (some? e))
        (is (= :tools.agents.anthropic.error/internal-server (:type (ex-data e))))
        ;; max-retries 2 -> 3 total attempts (1 initial + 2 retries)
        (is (= 3 @calls))))))

(deftest retries-connection-failures
  (let [calls (atom 0)
        attempt-fn (fn [] (swap! calls inc) ((throwing :tools.agents.anthropic.error/api-connection nil nil)))]
    (binding [a/*sleep-fn* (fn [_] nil)]
      (let [e (try (a/request-with-retries! 1 attempt-fn) nil (catch Exception e e))]
        (is (some? e))
        (is (= 2 @calls))))))

(deftest does-not-retry-permanent-errors
  (doseq [[type status] [[:tools.agents.anthropic.error/streaming-unsupported nil]
                          [:tools.agents.anthropic.error/bad-request 400]
                          [:tools.agents.anthropic.error/not-found 404]]]
    (testing type
      (let [calls (atom 0)
            attempt-fn (fn [] (swap! calls inc) ((throwing type status nil)))]
        (binding [a/*sleep-fn* (fn [_] nil)]
          (let [e (try (a/request-with-retries! 5 attempt-fn) nil (catch Exception e e))]
            (is (some? e))
            (is (= type (:type (ex-data e))))
            ;; permanent error -> exactly one attempt, no retry consumed
            (is (= 1 @calls))))))))

(deftest max-retries-zero-means-exactly-one-attempt
  (let [calls (atom 0)
        attempt-fn (fn [] (swap! calls inc) ((throwing :tools.agents.anthropic.error/rate-limit 429 nil)))]
    (binding [a/*sleep-fn* (fn [_] nil)]
      (let [e (try (a/request-with-retries! 0 attempt-fn) nil (catch Exception e e))]
        (is (some? e))
        (is (= 1 @calls))))))

(deftest honors-retry-after-header-over-computed-backoff
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429 {"retry-after" "7"}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      (is (= [7.0] @slept)))))

(deftest honors-retry-after-header-when-its-value-is-a-vector
  ;; Regression guard: Babashka's http-post! leaf (babashka.http-client)
  ;; returns a header's value as a VECTOR of strings, not a bare string,
  ;; whenever that header name appears more than once in the response --
  ;; a real occurrence when a proxy/gateway in front of a custom :base-url
  ;; duplicates or folds a singleton header, exactly the adversarial-
  ;; gateway class parse-retry-after already defends against for other
  ;; reasons. `(str ["30" "60"])` is not a valid double, so this used to
  ;; fail parse and get silently swallowed, discarding the real Retry-After
  ;; value in favor of the much shorter computed backoff.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429 {"retry-after" ["30" "60"]}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      (is (= [30.0] @slept)))))

(deftest retry-after-header-parsing-never-evaluates-its-input
  ;; A Retry-After VALUE is attacker-reachable (this library talks to
  ;; arbitrary :base-urls, not just api.anthropic.com — a malicious/
  ;; compromised gateway or a MITM'd plaintext hop controls it). Parsing it
  ;; with `read-string` instead of `Double/parseDouble` would let
  ;; `#=(...)`-style reader-eval forms actually run at parse time — this
  ;; asserts a hostile value is inert: it neither throws nor executes
  ;; anything, and just falls back to the computed backoff.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429
                                  {"retry-after" "#=(throw (ex-info \"pwned\" {}))"}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      ;; malformed -> parse-retry-after returns nil -> falls back to
      ;; backoff-seconds' own computed (non-nil, non-zero) delay
      (is (= 1 (count @slept)))
      (is (number? (first @slept))))))

(deftest retry-after-header-with-reader-eval-payload-that-would-honor-a-number-is-inert
  ;; The test above can't actually discriminate a safe Double/parseDouble
  ;; parser from a vulnerable read-string-based one: under read-string, the
  ;; `#=(throw ...)` payload's own throw fires DURING parsing, which
  ;; parse-retry-after's own catch swallows either way -- both
  ;; implementations converge on the same nil-then-computed-backoff outcome.
  ;; A payload read-string would evaluate to a plain NUMBER instead of a
  ;; throw is what actually tells the two implementations apart: read-string
  ;; would honor it as a literal Retry-After ("wins outright" per
  ;; backoff-seconds), sleeping exactly 5.0s, while Double/parseDouble can't
  ;; parse it as a double at all and falls back to attempt-0's computed,
  ;; jittered backoff -- always well under 1s. A regression back to
  ;; read-string here would sleep 5.0s and this test would catch it.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429
                                  {"retry-after" "#=(identity 5)"}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      (is (= 1 (count @slept)))
      (is (< (first @slept) 1.0)))))

(deftest retry-after-header-negative-value-does-not-crash-the-retry-loop
  ;; Regression guard: a negative Retry-After (malformed, hostile, or from a
  ;; misbehaving gateway) used to sail straight through to *sleep-fn*/
  ;; Thread-sleep and crash the whole retry loop with an unrelated, untyped
  ;; IllegalArgumentException, masking the real rate-limit error. Now it's
  ;; clamped away (treated the same as an unparseable value) and the retry
  ;; falls back to computed backoff instead of crashing.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429 {"retry-after" "-5"}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      (is (= 1 (count @slept)))
      (is (< (first @slept) 1.0)))))

(deftest retry-after-header-huge-value-is-clamped
  ;; Regression guard: an absurdly large-but-well-formed Retry-After (e.g. a
  ;; malicious/misconfigured gateway saying "retry-after: 86400") used to be
  ;; honored verbatim with no upper bound at all.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (= @calls 1)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429 {"retry-after" "86400"}))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 3 attempt-fn)))
      (is (= [60.0] @slept)))))

(deftest backoff-seconds-never-exceeds-its-documented-8s-cap
  ;; Regression guard: jitter used to be added ON TOP of the capped base
  ;; (base + base*0.25*rand()), which could return up to ~10s -- exceeding
  ;; the docstring's own "capped at 8s" claim. Jitter now only ever
  ;; shortens the delay (base * (1 - 0.25*rand())), same direction as
  ;; anthropic-sdk-python's own retry jitter, so the cap actually holds.
  ;; Driven through the public request-with-retries! rather than reaching
  ;; into the private backoff-seconds directly. 6 retries with no
  ;; Retry-After header walks attempt
  ;; 0..5, which saturates the exponential base at 8.0 by attempt 4.
  (let [slept (atom [])
        calls (atom 0)
        attempt-fn (fn [] (swap! calls inc)
                     (if (<= @calls 6)
                       ((throwing :tools.agents.anthropic.error/rate-limit 429 nil))
                       :ok))]
    (binding [a/*sleep-fn* (fn [s] (swap! slept conj s))]
      (is (= :ok (a/request-with-retries! 10 attempt-fn)))
      (is (= 6 (count @slept)))
      (is (every? #(<= % 8.0) @slept)))))

;; ---------------------------------------------------------------------------
;; Tool calling — pure logic (extraction/rendering, no network)
;; ---------------------------------------------------------------------------

(deftest tool-use-predicate
  (is (true? (a/tool-use? {"stop_reason" "tool_use"})))
  (is (false? (a/tool-use? {"stop_reason" "end_turn"})))
  (is (false? (a/tool-use? {}))))

(deftest tool-calls-extracts-tool-use-blocks
  (is (= [{:id "toolu_1" :name "get_weather" :input {"location" "SF"}}]
         (a/tool-calls {"content" [{"type" "text" "text" "checking..."}
                                    {"type" "tool_use" "id" "toolu_1" "name" "get_weather"
                                     "input" {"location" "SF"}}]}))))

(deftest tool-calls-extracts-multiple-parallel-calls
  (is (= 2 (count (a/tool-calls {"content" [{"type" "tool_use" "id" "t1" "name" "a" "input" {}}
                                             {"type" "tool_use" "id" "t2" "name" "b" "input" {}}]})))))

(deftest tool-calls-empty-when-no-tool-use-blocks
  (is (= [] (a/tool-calls {"content" [{"type" "text" "text" "hi"}]})))
  (is (= [] (a/tool-calls {}))))

(deftest add-tool-results-appends-one-user-turn-with-multiple-blocks
  (is (= [{"role" "user"
           "content" [{"type" "tool_result" "tool_use_id" "t1" "content" "72F"}
                      {"type" "tool_result" "tool_use_id" "t2" "content" "sunny"}]}]
         (a/add-tool-results [] [{:tool-use-id "t1" :content "72F"}
                                  {:tool-use-id "t2" :content "sunny"}]))))

(deftest add-tool-results-marks-is-error
  (is (= [{"role" "user"
           "content" [{"type" "tool_result" "tool_use_id" "t1" "content" "boom" "is_error" true}]}]
         (a/add-tool-results [] [{:tool-use-id "t1" :content "boom" :is-error true}]))))

(deftest add-tool-result-single-convenience
  (is (= [{"role" "user"
           "content" [{"type" "tool_result" "tool_use_id" "t1" "content" "72F"}]}]
         (a/add-tool-result [] "t1" "72F"))))

(deftest add-tool-results-normalizes-mixed-content-like-sibling-message-helpers
  ;; Regression guard: :content used to reach the wire completely
  ;; unnormalized -- a mixed string/map seq left a bare string embedded in
  ;; the content-block array instead of wrapping it into a text block, an
  ;; inconsistency with add-user-message/add-assistant-message given
  ;; identical input.
  (is (= [{"role" "user"
           "content" [{"type" "tool_result" "tool_use_id" "t1"
                       "content" [{"type" "text" "text" "hi"}
                                  {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]}]}]
         (a/add-tool-results [] [{:tool-use-id "t1" :content ["hi" (a/image-url "http://x/y.png")]}]))))

(deftest add-tool-result-normalizes-bare-content-block-map
  ;; Regression guard: the docstring's own "an image block" example used to
  ;; ship a bare map as "content" instead of a one-element array -- not a
  ;; valid tool_result.content shape (must be a string or an array).
  (is (= [{"role" "user"
           "content" [{"type" "tool_result" "tool_use_id" "t1"
                       "content" [{"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]}]}]
         (a/add-tool-result [] "t1" (a/image-url "http://x/y.png")))))

(deftest tool-loop-round-trip-composes-with-existing-helpers
  (let [assistant-response {"content" [{"type" "tool_use" "id" "toolu_1" "name" "get_weather"
                                         "input" {"location" "SF"}}]
                             "stop_reason" "tool_use"}
        messages (-> []
                     (a/add-user-message "What's the weather in SF?")
                     (a/add-assistant-message [{"type" "tool_use" "id" "toolu_1"
                                                 "name" "get_weather" "input" {"location" "SF"}}]))]
    (is (a/tool-use? assistant-response))
    (let [[{:keys [id name input]}] (a/tool-calls assistant-response)]
      (is (= "toolu_1" id))
      (is (= "get_weather" name))
      (is (= {"location" "SF"} input))
      (let [final (a/add-tool-results messages [{:tool-use-id id :content "72F and sunny"}])]
        (is (= "user" (get (last final) "role")))
        (is (= "tool_result" (get-in (last final) ["content" 0 "type"])))))))
