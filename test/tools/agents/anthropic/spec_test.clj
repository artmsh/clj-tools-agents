(ns tools.agents.anthropic.spec-test
  "Plain .clj, mirroring tools.agents.anthropic.spec itself. Every test below
   asserts a spec REJECTS a malformed value, not just that it accepts a good
   one — a spec that only ever says true is not exercising anything."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.spec :as spec]))

;; ---------------------------------------------------------------------------
;; The central lesson: s/keys is blind to string keys. Prove it both ways —
;; the wire-shaped spec must accept the real (string-keyed) shape AND reject
;; the same data reshaped with keyword keys.
;; ---------------------------------------------------------------------------

(deftest request-map-accepts-real-wire-shape
  (is (s/valid? ::spec/request-map {"model" "m" "messages" []})))

(deftest request-map-rejects-missing-model
  (is (not (s/valid? ::spec/request-map {"messages" []}))))

(deftest request-map-rejects-keyword-keyed-lookalike
  ;; The exact trap a naive (s/keys :req-un [::model ::messages]) would miss:
  ;; a keyword-keyed map is NOT a valid request-map, even though it "has a
  ;; model and messages" if you squint at it with keyword eyes.
  (is (not (s/valid? ::spec/request-map {:model "m" :messages []}))))

(deftest message-map-rejects-unknown-role
  (is (s/valid? ::spec/message-map {"role" "user" "content" "hi"}))
  (is (not (s/valid? ::spec/message-map {"role" "system" "content" "hi"}))))

(deftest content-block-map-rejects-non-map-and-keyword-keyed
  (is (s/valid? ::spec/content-block-map (a/text "hi")))
  (is (not (s/valid? ::spec/content-block-map "hi")))
  (is (not (s/valid? ::spec/content-block-map {:type "text" :text "hi"}))))

;; ---------------------------------------------------------------------------
;; Client
;; ---------------------------------------------------------------------------

(deftest client-opts-accepts-partial-map
  (is (s/valid? ::spec/client-opts {:api-key "k"}))
  (is (s/valid? ::spec/client-opts {})))

(deftest client-opts-rejects-wrong-value-types
  (is (not (s/valid? ::spec/client-opts {:api-key 42})))
  (is (not (s/valid? ::spec/client-opts {:max-retries -1}))))

(deftest resolved-client-accepts-real-client-output
  (is (s/valid? ::spec/resolved-client (a/client {:api-key "k"})))
  (is (s/valid? ::spec/resolved-client (a/client {:auth-token "t"}))))

(deftest resolved-client-rejects-map-with-neither-credential
  (is (not (s/valid? ::spec/resolved-client {:base-url a/default-base-url :max-retries 2}))))

(deftest resolved-client-rejects-map-with-both-credentials
  ;; s/or alone only enforces "at least one" -- a map with BOTH :api-key and
  ;; :auth-token satisfies either branch and would wrongly validate without
  ;; the trailing xor predicate in ::resolved-client.
  (is (not (s/valid? ::spec/resolved-client
                      {:base-url a/default-base-url :max-retries 2 :api-key "k" :auth-token "t"}))))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(deftest error-type-rejects-unknown-keyword
  (is (s/valid? ::spec/error-type :tools.agents.anthropic.error/rate-limit))
  (is (not (s/valid? ::spec/error-type :tools.agents.anthropic.error/made-up-type))))

(deftest ex-data-matches-a-real-thrown-exception
  (let [client (a/client {:api-key "k" :base-url "http://127.0.0.1:1"})
        e (try (a/messages-create client {"model" "m" "max_tokens" 1 "messages" [] "stream" true})
               nil (catch Exception e e))]
    (is (some? e))
    (is (s/valid? ::spec/ex-data (ex-data e)))))

(deftest ex-data-rejects-map-missing-type
  (is (not (s/valid? ::spec/ex-data {:status 500}))))

;; ---------------------------------------------------------------------------
;; Tool calling / DSL
;; ---------------------------------------------------------------------------

(deftest tool-call-matches-real-extraction
  (let [response {"content" [{"type" "tool_use" "id" "t1" "name" "n" "input" {"a" 1}}]}]
    (is (s/valid? ::spec/tool-call (first (a/tool-calls response))))))

(deftest tool-call-rejects-non-map-input
  (is (not (s/valid? ::spec/tool-call {:id "t1" :name "n" :input "not-a-map"}))))

(deftest tool-result-descriptor-rejects-non-boolean-is-error
  ;; :content, NOT :tool-result-content -- that's the key add-tool-results
  ;; actually destructures (README/examples/tool_use.clj all use :content
  ;; too). An earlier version of this spec (and this test) used
  ;; :tool-result-content directly, which meant instrumenting
  ;; add-tool-results rejected every real, correctly-shaped call.
  (is (s/valid? ::spec/tool-result-descriptor {:tool-use-id "t1" :content "ok" :is-error false}))
  (is (not (s/valid? ::spec/tool-result-descriptor {:tool-use-id "t1" :content "ok" :is-error "yes"}))))

(deftest tool-result-descriptor-widened-content-still-rejects-non-content
  ;; ::tool-result-content was widened (string, a single content-block map,
  ;; or a mixed seq of strings/maps) to match add-tool-results' actual
  ;; runtime behavior -- confirm it still rejects genuinely bad content
  ;; rather than having become permissive to the point of meaninglessness.
  (is (not (s/valid? ::spec/tool-result-descriptor {:tool-use-id "t1" :content 42})))
  (is (not (s/valid? ::spec/tool-result-descriptor {:tool-use-id "t1" :content [42]}))))

(deftest tool-result-descriptor-rejects-old-wrong-key-name
  ;; Regression guard for the key-mismatch bug itself: a map keyed
  ;; :tool-result-content (the old, wrong spec key) must NOT validate --
  ;; it's missing the :content key the real function and this spec now
  ;; both require.
  (is (not (s/valid? ::spec/tool-result-descriptor {:tool-use-id "t1" :tool-result-content "ok"}))))

;; ---------------------------------------------------------------------------
;; fdef instrumentation — proves the fdefs in tools.agents.anthropic.spec
;; actually constrain the functions they're attached to, not just that they
;; parse. Instrument/unstrument around each test so this file's fdefs don't
;; leak into any other test namespace's run.
;; ---------------------------------------------------------------------------

(deftest instrumented-content-blocks-rejects-bad-arg
  (stest/instrument `a/content-blocks)
  (try
    (testing "valid input still works"
      (is (= [{"type" "text" "text" "hi"}] (a/content-blocks "hi")))
      (is (= [] (a/content-blocks nil))))
    (testing "a number is neither a string, a map, nor a collection -- rejected"
      (let [e (try (a/content-blocks 42) nil (catch Exception e e))]
        (is (some? e))))
    (finally (stest/unstrument `a/content-blocks))))

(deftest instrumented-client-rejects-malformed-opts
  (stest/instrument `a/client)
  (try
    (let [e (try (a/client {:api-key 42}) nil (catch Exception e e))]
      (is (some? e))
      ;; spec instrumentation failures are ex-info too, distinguishable from
      ;; tools.agents.anthropic's own domain errors by the absence of a
      ;; tools.agents.anthropic.error/* :type.
      (is (not= :tools.agents.anthropic.error/missing-credentials (:type (ex-data e)))))
    (finally (stest/unstrument `a/client))))

(deftest instrumented-add-tool-results-accepts-the-documented-call-shape
  ;; Regression guard for the ::tool-result-descriptor key-mismatch bug:
  ;; instrumenting add-tool-results and calling it with the EXACT shape
  ;; README.md/examples/tool_use.clj document ({:tool-use-id .. :content ..})
  ;; must succeed, not throw a spec-conformance error. Covers every
  ;; :content shape add-tool-results actually accepts (a plain string, the
  ;; docstring's own bare-image-block-map example, and a mixed string/map
  ;; seq) -- a first version of this test only exercised the plain-string
  ;; case, which is exactly why the ::tool-result-content value spec being
  ;; narrower than the real (widened) runtime behavior went unnoticed.
  (stest/instrument `a/add-tool-results)
  (try
    (is (= [{"role" "user"
             "content" [{"type" "tool_result" "tool_use_id" "t1" "content" "72F"}]}]
           (a/add-tool-results [] [{:tool-use-id "t1" :content "72F"}])))
    (is (= [{"role" "user"
             "content" [{"type" "tool_result" "tool_use_id" "t1"
                         "content" [{"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]}]}]
           (a/add-tool-result [] "t1" (a/image-url "http://x/y.png"))))
    (is (= [{"role" "user"
             "content" [{"type" "tool_result" "tool_use_id" "t1"
                         "content" [{"type" "text" "text" "hi"}
                                    {"type" "image" "source" {"type" "url" "url" "http://x/y.png"}}]}]}]
           (a/add-tool-results [] [{:tool-use-id "t1" :content ["hi" (a/image-url "http://x/y.png")]}])))
    (finally (stest/unstrument `a/add-tool-results))))
