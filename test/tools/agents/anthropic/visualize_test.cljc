(ns tools.agents.anthropic.visualize-test
  "Pure logic — zero I/O, zero network, identical on JVM Clojure and
   Babashka (matches tools.agents.anthropic-test's own posture). Assertions
   target render-message's returned STRING directly (visualize-message /
   show-response / capture! / show-all! just println it — no need for
   with-out-str anywhere)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [tools.agents.anthropic.visualize :as viz]))

(defn- long-str
  "n copies of ch joined — `str/join`, not `(apply str (repeat n ch))`,
   which would spread n arguments. See tools.agents.anthropic.visualize's
   strip-ansi docstring."
  ([n] (long-str n "x"))
  ([n ch] (str/join (repeat n ch))))

;; ---------------------------------------------------------------------------
;; parse-response / parse-content-block
;; ---------------------------------------------------------------------------

(deftest parse-response-full-shape
  (let [response {"role" "assistant"
                   "content" [{"type" "text" "text" "hi"}]
                   "model" "claude-sonnet-4-6"
                   "stop_reason" "end_turn"
                   "usage" {"input_tokens" 10 "output_tokens" 5}}
        parsed (viz/parse-response response)]
    (is (= "assistant" (:role parsed)))
    (is (= "claude-sonnet-4-6" (:model parsed)))
    (is (= "end_turn" (:stop-reason parsed)))
    (is (= {"input_tokens" 10 "output_tokens" 5} (:usage parsed)))
    (is (= [{:type "text" :data {"type" "text" "text" "hi"}}] (:content parsed)))))

(deftest parse-response-defaults-on-missing-fields
  (let [parsed (viz/parse-response {})]
    (is (= "unknown" (:role parsed)))
    (is (= [] (:content parsed)))
    (is (nil? (:model parsed)))
    (is (nil? (:stop-reason parsed)))
    (is (= {} (:usage parsed)))))

(deftest parse-content-block-map
  (is (= {:type "tool_use" :data {"type" "tool_use" "id" "t1"}}
         (viz/parse-content-block {"type" "tool_use" "id" "t1"}))))

(deftest parse-content-block-string-falls-back-to-text
  (is (= {:type "text" :data {"text" "hello"}} (viz/parse-content-block "hello"))))

(deftest parse-content-block-other-falls-back-to-unknown
  (is (= "unknown" (:type (viz/parse-content-block 42)))))

;; ---------------------------------------------------------------------------
;; format-json
;; ---------------------------------------------------------------------------

(deftest format-json-pretty-prints
  (let [s (viz/format-json {"a" 1})]
    (is (str/includes? s "\n"))
    (is (str/includes? s "\"a\": 1"))))

(deftest format-json-truncates-past-max-length
  (let [s (viz/format-json {"a" (long-str 1000)} 50)]
    (is (str/includes? s "... (truncated)"))
    (is (<= (count s) (+ 50 (count "\n  ... (truncated)"))))))

(deftest format-json-untruncated-under-max-length
  (is (not (str/includes? (viz/format-json {"a" 1} 500) "truncated"))))

(deftest format-json-non-coercible-key-throws-typed-error
  ;; Regression guard: pretty-json's map-key handling used to reimplement a
  ;; fragment of write-json's key coercion (`(if (string? k) k (name k))`)
  ;; instead of delegating to write-json's own json-key->str, so a key that
  ;; is neither string, keyword, nor symbol threw a raw ClassCastException
  ;; instead of the library's contracted typed :json-encode ex-info -- code
  ;; catching by :type failed to catch it.
  (let [e (try (viz/format-json {42 "v"}) nil (catch Exception e e))]
    (is (some? e))
    (is (= :tools.agents.anthropic.error/json-encode (:type (ex-data e))))))

;; ---------------------------------------------------------------------------
;; render-message — structure / metadata
;; ---------------------------------------------------------------------------

(def ^:private base-message
  {:role "assistant" :content [] :model "claude-sonnet-4-6" :stop-reason "end_turn" :usage {}})

(deftest render-message-includes-role-model-stop-reason
  (let [out (viz/render-message base-message {:color? false})]
    (is (str/includes? out "assistant"))
    (is (str/includes? out "Model: claude-sonnet-4-6"))
    (is (str/includes? out "Stop Reason: end_turn"))))

(deftest render-message-omits-absent-metadata
  (let [out (viz/render-message (assoc base-message :model nil :stop-reason nil) {:color? false})]
    (is (not (str/includes? out "Model:")))
    (is (not (str/includes? out "Stop Reason:")))))

(deftest render-message-usage-suffix-groups-thousands
  (let [out (viz/render-message (assoc base-message :usage {"input_tokens" 1234 "output_tokens" 2}) {:color? false})]
    (is (str/includes? out "1,234 in"))
    (is (str/includes? out "2 out"))
    (is (str/includes? out "1,236 total"))))

(deftest render-message-no-usage-suffix-when-usage-empty
  (is (not (str/includes? (viz/render-message base-message {:color? false}) "tokens:"))))

(deftest render-message-usage-suffix-null-token-count-defaults-to-zero
  ;; Regression guard: `(get usage "input_tokens" 0)` only substitutes the
  ;; default when the key is ABSENT, not when present with an explicit JSON
  ;; null -- which decodes to Clojure nil and crashed `(+ nil out)` with an
  ;; unhandled NullPointerException on every render-message call, not just
  ;; this one content-block renderer.
  (let [out (viz/render-message (assoc base-message :usage {"input_tokens" nil "output_tokens" 5}) {:color? false})]
    (is (str/includes? out "0 in"))
    (is (str/includes? out "5 out"))
    (is (str/includes? out "5 total"))))

;; ---------------------------------------------------------------------------
;; render-message — content blocks
;; ---------------------------------------------------------------------------

(defn- with-content [blocks]
  (assoc base-message :content (mapv viz/parse-content-block blocks)))

(deftest render-message-text-block
  (let [out (viz/render-message (with-content [{"type" "text" "text" "hello world"}]) {:color? false})]
    (is (str/includes? out "Text"))
    (is (str/includes? out "hello world"))
    (is (str/includes? out "Content (1 blocks)"))
    (is (str/includes? out "Block 1"))))

(deftest render-message-text-block-blank-text-omitted
  (is (not (str/includes? (viz/render-message (with-content [{"type" "text" "text" ""}]) {:color? false}) "Text"))))

(deftest render-message-text-block-non-string-text-value-coerced-not-crashed
  ;; Regression guard: a non-string "text" value is the same reachable wire
  ;; shape tools.agents.anthropic's output-text explicitly guards against --
  ;; render-text-content read the identical field with no such guard,
  ;; crashing `(seq 42)` with an untyped IllegalArgumentException instead of
  ;; displaying it (this renderer's job is display, so it follows
  ;; render-tool-result's own established str-coercion precedent elsewhere
  ;; in this file rather than throwing).
  (let [out (viz/render-message (with-content [{"type" "text" "text" 42}]) {:color? false})]
    (is (str/includes? out "Text"))
    (is (str/includes? out "42"))))

(deftest render-message-text-block-truncates-long-text
  (let [long-text (long-str 1500 "a")
        out (viz/render-message (with-content [{"type" "text" "text" long-text}]) {:color? false})]
    (is (str/includes? out "... (truncated)"))
    (is (not (str/includes? out long-text)))))

(deftest render-message-tool-use-block
  (let [out (viz/render-message
              (with-content [{"type" "tool_use" "id" "toolu_1" "name" "get_weather"
                               "input" {"location" "SF"}}])
              {:color? false})]
    (is (str/includes? out "Tool Use:"))
    (is (str/includes? out "get_weather"))
    (is (str/includes? out "ID: toolu_1"))
    (is (str/includes? out "Input:"))
    (is (str/includes? out "\"location\": \"SF\""))))

(deftest render-message-tool-use-caller-labels
  (let [render (fn [caller-type]
                 (viz/render-message
                   (with-content [{"type" "tool_use" "id" "t" "name" "n" "input" {}
                                    "caller" {"type" caller-type}}])
                   {:color? false}))]
    (is (str/includes? (render "code_execution_20250825") "Caller: code execution environment"))
    (is (str/includes? (render "direct") "Caller: model (direct)"))
    (is (str/includes? (render "something_else") "Caller: something_else"))))

(deftest render-message-tool-result-success
  (let [out (viz/render-message
              (with-content [{"type" "tool_result" "tool_use_id" "toolu_1" "content" "72F and sunny"}])
              {:color? false})]
    (is (str/includes? out "Tool Result: Success"))
    (is (str/includes? out "Tool Use ID: toolu_1"))
    (is (str/includes? out "72F and sunny"))))

(deftest render-message-tool-result-error
  (let [out (viz/render-message
              (with-content [{"type" "tool_result" "tool_use_id" "toolu_1"
                               "content" "boom" "is_error" true}])
              {:color? false})]
    (is (str/includes? out "Tool Result: Error"))))

(deftest render-message-tool-result-list-content
  (let [out (viz/render-message
              (with-content [{"type" "tool_result" "tool_use_id" "t"
                               "content" [{"type" "text" "text" "line one"}
                                          {"type" "text" "text" "line two"}]}])
              {:color? false})]
    (is (str/includes? out "line one"))
    (is (str/includes? out "line two"))
    (is (= 2 (count (re-seq #"Output:" out))))))

(deftest render-message-server-tool-use-with-code
  (let [out (viz/render-message
              (with-content [{"type" "server_tool_use" "id" "s1"
                               "input" {"code" "print(1)\nprint(2)"}}])
              {:color? false})]
    (is (str/includes? out "Server Tool Use"))
    (is (str/includes? out "Code:"))
    (is (str/includes? out "1 | print(1)"))
    (is (str/includes? out "2 | print(2)"))))

(deftest render-message-server-tool-use-without-code-falls-back-to-input
  (let [out (viz/render-message
              (with-content [{"type" "server_tool_use" "id" "s1" "input" {"query" "x"}}])
              {:color? false})]
    (is (str/includes? out "Input:"))
    (is (str/includes? out "\"query\": \"x\""))))

(deftest render-message-code-execution-result-success
  (let [out (viz/render-message
              (with-content [{"type" "code_execution_tool_result"
                               "content" {"return_code" 0 "stdout" "42\n" "stderr" ""}}])
              {:color? false})]
    (is (str/includes? out "Success (exit 0)"))
    (is (str/includes? out "stdout:"))
    (is (str/includes? out "42"))
    (is (not (str/includes? out "stderr:")))))

(deftest render-message-code-execution-result-error
  (let [out (viz/render-message
              (with-content [{"type" "code_execution_tool_result"
                               "content" {"return_code" 1 "stdout" "" "stderr" "Traceback"}}])
              {:color? false})]
    (is (str/includes? out "Error (exit 1)"))
    (is (str/includes? out "stderr:"))
    (is (str/includes? out "Traceback"))))

(deftest render-message-code-execution-result-no-output
  (let [out (viz/render-message
              (with-content [{"type" "code_execution_tool_result"
                               "content" {"return_code" 0 "stdout" "" "stderr" ""}}])
              {:color? false})]
    (is (str/includes? out "(no output)"))))

(deftest render-message-code-execution-result-null-return-code-defaults-to-zero
  ;; Regression guard: `(get nested "return_code" 0)` only substitutes the
  ;; default when the key is ABSENT, not when present with an explicit JSON
  ;; null (a killed/timed-out execution with no real exit code) -- which
  ;; decodes to Clojure nil and crashed `(zero? nil)` with an unhandled
  ;; NullPointerException instead of rendering "Success (exit 0)".
  (let [out (viz/render-message
              (with-content [{"type" "code_execution_tool_result"
                               "content" {"return_code" nil "stdout" "" "stderr" ""}}])
              {:color? false})]
    (is (str/includes? out "Success (exit 0)"))))

(deftest render-message-code-execution-result-stdout-trailing-newline-no-stray-blank-line
  ;; Regression guard for split-lines: a trailing \n is a line TERMINATOR,
  ;; not a separator introducing one more (empty) line -- "42\n" is one
  ;; line, not two. Before the fix, a trailing newline rendered one MORE
  ;; tree line (a stray empty child node) than the same text without it;
  ;; comparing total rendered line counts catches that directly without
  ;; depending on the exact connector/padding formatting.
  (let [render (fn [stdout]
                 (viz/render-message
                   (with-content [{"type" "code_execution_tool_result"
                                    "content" {"return_code" 0 "stdout" stdout "stderr" ""}}])
                   {:color? false}))
        lines-without-nl (count (str/split-lines (render "42")))
        lines-with-nl (count (str/split-lines (render "42\n")))]
    (is (= lines-without-nl lines-with-nl))))

(deftest render-message-unknown-content-type-falls-back-to-json-dump
  (let [out (viz/render-message
              (with-content [{"type" "citations" "value" "x"}])
              {:color? false})]
    (is (str/includes? out "Unknown Type: citations"))
    (is (str/includes? out "\"value\": \"x\""))))

;; ---------------------------------------------------------------------------
;; Color
;; ---------------------------------------------------------------------------

(deftest render-message-color-true-injects-escape-codes
  (is (str/includes? (viz/render-message base-message {:color? true}) (str (char 27) "["))))

(deftest render-message-color-false-injects-no-escape-codes
  (is (not (str/includes? (viz/render-message base-message {:color? false}) (str (char 27) "[")))))

(deftest render-message-default-color-is-true
  (is (str/includes? (viz/render-message base-message) (str (char 27) "["))))

;; ---------------------------------------------------------------------------
;; Panel border — top/bottom borders and every content line share one width.
;; ---------------------------------------------------------------------------

(deftest panel-border-lines-share-one-width
  (let [out (viz/render-message (with-content [{"type" "text" "text" "hello"}]) {:color? false})
        lines (str/split-lines out)
        widths (map count lines)]
    (is (>= (count lines) 3))
    (is (str/starts-with? (first lines) "+"))
    (is (str/starts-with? (last lines) "+"))
    (is (= 1 (count (distinct widths))))))

(deftest panel-border-lines-share-one-width-with-multi-line-content
  ;; Regression guard for the bug text-nodes fixes: a pretty-printed
  ;; tool_use "input" JSON blob is itself MULTI-LINE. Before text-nodes
  ;; existed, that whole blob was crammed into a single node :label —
  ;; correct on the substring checks elsewhere in this file, but it broke
  ;; box-border alignment (an embedded \n mid-line, uncounted by
  ;; visible-length, throws off every pad-line after it). This fixture's
  ;; input pretty-prints to 3+ lines, so a border-width regression here
  ;; would actually be caught, unlike the single-line fixtures above.
  (let [out (viz/render-message
              (with-content [{"type" "tool_use" "id" "t" "name" "n"
                               "input" {"location" "San Francisco"}}])
              {:color? false})
        lines (str/split-lines out)
        widths (map count lines)]
    (is (>= (count (filter #(str/includes? % "\"location\"") lines)) 1))
    (is (>= (count lines) 6))
    (is (= 1 (count (distinct widths))))))

(deftest panel-does-not-crash-past-applys-arg-limit
  ;; Regression guard: panel computed its content width via `(apply max 0
  ;; (map visible-length lines))` — one argument per rendered line, which
  ;; realistic tool output reaches (a code_execution_tool_result whose stdout
  ;; has many lines, well within this file's own 2000-char stdout truncation
  ;; limit, e.g. 1500 blank lines = 1500 chars total). `reduce max` has no
  ;; such arg-count limit.
  (let [many-lines-stdout (str/join (repeat 1500 "\n"))
        out (viz/render-message
              (with-content [{"type" "code_execution_tool_result"
                               "content" {"return_code" 0 "stdout" many-lines-stdout "stderr" ""}}])
              {:color? false})
        lines (str/split-lines out)]
    (is (>= (count lines) 1500))
    (is (= 1 (count (distinct (map count lines)))))))

;; ---------------------------------------------------------------------------
;; visualizer / capture! / show-all! — state only, ignore the println side
;; effect (it's a plain println, harmless under clojure.test, and testing it
;; doesn't require capturing stdout at all).
;; ---------------------------------------------------------------------------

(deftest visualizer-defaults-to-auto-show
  (is (true? (:auto-show? (viz/visualizer)))))

(deftest capture-appends-and-returns-viz
  (let [v (viz/visualizer {:auto-show? false})
        response {"role" "assistant" "content" [{"type" "text" "text" "hi"}]}]
    (is (identical? v (viz/capture! v response)))
    (is (= [response] @(:responses v)))
    (viz/capture! v response)
    (is (= 2 (count @(:responses v))))))

(deftest show-all-returns-viz-without-mutating-responses
  (let [v (viz/visualizer {:auto-show? false})]
    (viz/capture! v {"role" "user" "content" []})
    (is (identical? v (viz/show-all! v)))
    (is (= 1 (count @(:responses v))))))

(deftest capture-with-auto-show-does-not-throw
  ;; auto-show? true means capture! also prints via show-response — just
  ;; assert it doesn't throw and still tracks state correctly.
  (let [v (viz/visualizer)]
    (viz/capture! v {"role" "assistant" "content" [{"type" "text" "text" "hi"}]
                      "model" "m" "stop_reason" "end_turn" "usage" {"input_tokens" 1 "output_tokens" 1}})
    (is (= 1 (count @(:responses v))))))

(deftest visualizer-defaults-color-to-true
  (is (true? (:color? (viz/visualizer)))))

(deftest visualizer-color-false-is-stored
  ;; Regression guard: the capture session used to have no way at all to
  ;; forward {:color? false} through to capture!/show-all!'s internal
  ;; show-response calls -- a session built for non-TTY/log output couldn't
  ;; disable ANSI codes without bypassing the session wrapper entirely.
  (is (false? (:color? (viz/visualizer {:color? false})))))

(deftest clear-empties-responses-and-returns-viz
  (let [v (viz/visualizer {:auto-show? false})]
    (viz/capture! v {"role" "user" "content" []})
    (viz/capture! v {"role" "user" "content" []})
    (is (= 2 (count @(:responses v))))
    (is (identical? v (viz/clear! v)))
    (is (= [] @(:responses v)))
    ;; still usable afterward
    (viz/capture! v {"role" "user" "content" []})
    (is (= 1 (count @(:responses v))))))
