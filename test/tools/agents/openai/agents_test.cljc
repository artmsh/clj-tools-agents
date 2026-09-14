(ns tools.agents.openai.agents-test
  "Pure logic for tools.agents.openai.agents — zero I/O, zero network,
   identical on both runtimes. Everything transport-shaped (URL building,
   headers, the retry loop, error typing) is exercised end-to-end against a
   mock server instead, in tools.agents.openai.agents.live-test — it reuses
   tools.agents.openai's already-tested pure retry functions verbatim (see
   that namespace's own test suite), so there is nothing new to unit-test in
   isolation here beyond this namespace's two genuinely pure functions."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.openai.agents :as agents]))

;; ---------------------------------------------------------------------------
;; path-segment / query-string — private, tested directly via `#'` because
;; their correct behavior (URL-structural characters encoded, keyword values
;; encoded via `name` not print-form) is impossible to observe reliably
;; through the mock-server suite: `.getPath()` on JVM Clojure's
;; com.sun.net.httpserver DECODES percent-escapes before test-support ever
;; sees them, while httpkit's `:uri` on Babashka does not — so the same wire
;; request produces two different `:path` strings depending on the runtime.
;; Testing the pure encoding function directly sidesteps that asymmetry.
;; ---------------------------------------------------------------------------

(deftest path-segment-url-encodes-hostile-characters
  ;; A session/environment id containing URL-structural characters must not
  ;; silently reroute the request to a different path — every send-request!
  ;; caller wraps its id argument through this function first.
  (is (= "sess%2F1%3Fx%3D1" (#'agents/path-segment "sess/1?x=1"))))

(deftest query-string-encodes-keyword-values-via-name-not-print-form
  ;; Regression: encoding a keyword VALUE via `str` (its print-form) would
  ;; send a literal leading colon (":desc" -> "%3Adesc") instead of "desc".
  (is (= "?order=desc" (#'agents/query-string {"order" :desc})))
  (is (= "?limit=20" (#'agents/query-string {"limit" 20}))))

;; ---------------------------------------------------------------------------
;; items-output-text
;; ---------------------------------------------------------------------------

(defn- message-item [text]
  {"type" "message" "role" "assistant"
   "content" [{"type" "output_text" "text" text}]})

(deftest concatenates-assistant-output-text-across-message-items
  (let [items {"data" [(message-item "Hello, ") (message-item "world.")]}]
    (is (= "Hello, world." (agents/items-output-text items)))))

(deftest ignores-non-message-items
  (let [items {"data" [{"type" "function_call" "name" "get_customer" "arguments" {}}
                        (message-item "done")]}]
    (is (= "done" (agents/items-output-text items)))))

(deftest ignores-non-assistant-message-items
  (let [items {"data" [{"type" "message" "role" "user"
                        "content" [{"type" "input_text" "text" "hi"}]}
                       (message-item "reply")]}]
    (is (= "reply" (agents/items-output-text items)))))

(deftest empty-data-yields-empty-string
  (is (= "" (agents/items-output-text {"data" []}))))

(deftest a-tool-only-turn-with-no-message-item-yields-empty-string
  (let [items {"data" [{"type" "function_call" "name" "run" "arguments" {}}]}]
    (is (= "" (agents/items-output-text items)))))

(deftest non-array-content-on-a-message-item-is-treated-as-empty-not-thrown
  ;; Deliberate divergence from tools.agents.openai/output-text's stricter
  ;; contract — see items-output-text's own docstring for why.
  (let [items {"data" [{"type" "message" "role" "assistant" "content" "not-an-array"}
                        (message-item "ok")]}]
    (is (= "ok" (agents/items-output-text items)))))

(deftest missing-data-array-throws
  (let [e (try (agents/items-output-text {}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

(deftest non-array-data-throws
  (let [e (try (agents/items-output-text {"data" "nope"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

(deftest non-string-output-text-throws
  (let [items {"data" [{"type" "message" "role" "assistant"
                        "content" [{"type" "output_text" "text" 42}]}]}
        e (try (agents/items-output-text items) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-content-shape (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; latest-root-turn / turn-finished?
;; ---------------------------------------------------------------------------

(defn- turn [id status subagent-id]
  {"id" id "object" "agent.session.turn" "status" status "subagent_id" subagent-id})

(deftest latest-root-turn-takes-the-first-root-turn-skipping-subagent-turns
  (let [turns {"data" [(turn "turn_sub" "in_progress" "subagent_1")
                       (turn "turn_2" "failed" nil)
                       (turn "turn_1" "completed" nil)]}]
    (is (= "turn_2" (get (agents/latest-root-turn turns) "id")))))

(deftest latest-root-turn-is-nil-without-a-root-turn
  (is (nil? (agents/latest-root-turn {"data" []})))
  (is (nil? (agents/latest-root-turn {"data" [(turn "turn_sub" "completed" "subagent_1")]}))))

(deftest latest-root-turn-throws-on-a-missing-data-array
  (let [e (try (agents/latest-root-turn {}) nil (catch Exception e e))]
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

(deftest turn-finished-on-terminal-statuses-only
  (doseq [s ["completed" "failed" "cancelled"]]
    (is (agents/turn-finished? {"status" s}) s))
  (doseq [s ["queued" "in_progress" "waiting" nil]]
    (is (not (agents/turn-finished? {"status" s})) (str s)))
  (is (not (agents/turn-finished? nil))))

;; ---------------------------------------------------------------------------
;; self-hosted-executor-command
;; ---------------------------------------------------------------------------

(deftest builds-the-codex-exec-server-argv
  (let [session {"id" "sess_1"
                 "environment" {"id" "ccarenv_1" "remote_url" "https://api.openai.com/v1/agents/api"}}]
    (is (= ["codex" "exec-server" "--remote" "https://api.openai.com/v1/agents/api"
            "--environment-id" "ccarenv_1"]
           (agents/self-hosted-executor-command session)))))

(deftest missing-environment-throws
  (let [e (try (agents/self-hosted-executor-command {"id" "sess_1"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

(deftest empty-string-environment-fields-throw-rather-than-build-a-broken-command
  ;; A blank id/url is not usable either — must fail the same catchable way a
  ;; missing key does, not silently produce ["codex" "exec-server" "--remote" "" ...].
  (let [session {"id" "sess_1" "environment" {"id" "" "remote_url" ""}}
        e (try (agents/self-hosted-executor-command session) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))))

(deftest openai-hosted-session-has-no-executor-command
  ;; An openai_hosted environment's map shape does not carry remote_url/id in
  ;; the way a self_hosted one does — this must throw, not silently succeed.
  (testing "environment present but missing the self-hosted fields"
    (let [session {"id" "sess_1" "environment" {"type" "openai_hosted" "status" "connected"}}
          e (try (agents/self-hosted-executor-command session) nil (catch Exception e e))]
      (is (some? e))
      (is (= :tools.agents.openai/invalid-response (:type (ex-data e)))))))
