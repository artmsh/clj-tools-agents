(ns tools.agents.sse-test
  "WHATWG event-stream interpretation rules, the chunk-boundary line splitter,
   and vendor stream fixtures under test/resources/sse/. None are live
   captures; provenance:
     anthropic-text         verbatim SSE example, platform.claude.com
                            build-with-claude/streaming
     openai-chat-text       chunk JSON from the chat completions
                            streaming-events Example, framed as `data:` + [DONE]
     openai-responses-text  per-event Example JSON (response.created,
                            output_text.delta), trimmed; completed/done events
                            assembled from the same schema
     gemini-text            SYNTHETIC: hand-built GenerateContentResponse chunks
                            (docs show only the ?alt=sse curl, no response
                            body); also reduced by gemini-test's accumulator
     agents-turn            per-event schema Examples from the agents
                            streaming-events reference, placeholders replaced"
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [tools.agents.sse :as sse]))

(defn- ev
  ([data] (ev "message" data))
  ([event data] {:event event :data data :id nil :retry nil}))

;; ---------------------------------------------------------------------------
;; Field parsing
;; ---------------------------------------------------------------------------

(deftest single-event-defaults-to-message
  (is (= [(ev "hello")] (sse/parse-string "data: hello\n\n"))))

(deftest event-field-sets-type
  (is (= [(ev "ping" "x")] (sse/parse-string "event: ping\ndata: x\n\n")))
  (testing "empty event value falls back to message"
    (is (= [(ev "x")] (sse/parse-string "event:\ndata: x\n\n"))))
  (testing "event type does not leak into the next event"
    (is (= [(ev "a" "1") (ev "2")]
           (sse/parse-string "event: a\ndata: 1\n\ndata: 2\n\n")))))

(deftest multiple-data-lines-join-with-lf
  (is (= [(ev "a\nb\nc")] (sse/parse-string "data: a\ndata: b\ndata: c\n\n")))
  (is (= [(ev "\n")] (sse/parse-string "data\ndata\n\n"))
      "two empty data lines -> one LF"))

(deftest exactly-one-leading-space-stripped
  (is (= [(ev "x")] (sse/parse-string "data:x\n\n")) "no space")
  (is (= [(ev "x")] (sse/parse-string "data: x\n\n")) "one space")
  (is (= [(ev " x")] (sse/parse-string "data:  x\n\n")) "second space kept")
  (is (= [(ev "x ")] (sse/parse-string "data: x \n\n")) "trailing space kept")
  (is (= [(ev "\tx")] (sse/parse-string "data:\tx\n\n")) "tab is not stripped")
  (is (= [(ev "a: b")] (sse/parse-string "data: a: b\n\n")) "only first colon splits"))

(deftest comments-are-ignored
  (is (= [] (sse/parse-string ": keep-alive\n\n")) "comment-only stream")
  (is (= [(ev "x")] (sse/parse-string ":c1\ndata: x\n: c2\n\n")))
  (is (= [] (sse/parse-string ":data: x\n\n")) "comment that looks like a field"))

(deftest field-without-colon-has-empty-value
  (is (= [(ev "")] (sse/parse-string "data\n\n")))
  (is (= [(ev "x")] (sse/parse-string "event\ndata: x\n\n")) "`event` alone -> message")
  (testing "id without colon resets last event id to empty string"
    (is (= [{:event "message" :data "1" :id "a" :retry nil}
            {:event "message" :data "2" :id "" :retry nil}]
           (sse/parse-string "id: a\ndata: 1\n\nid\ndata: 2\n\n")))))

(deftest unknown-fields-are-ignored
  (is (= [(ev "x")] (sse/parse-string "foo: bar\nDATA: nope\ndata: x\nEvent: y\n\n"))
      "field names are case-sensitive"))

(deftest empty-data-value-dispatches-empty-string
  (is (= [(ev "")] (sse/parse-string "data:\n\n")))
  (is (= [(ev "")] (sse/parse-string "data: \n\n"))))

(deftest no-data-no-dispatch
  (is (= [] (sse/parse-string "event: foo\n\n")) "event without data")
  (is (= [] (sse/parse-string "id: 1\n\n")))
  (is (= [] (sse/parse-string "\n\n\n")) "only blank lines")
  (testing "event type is reset by the non-dispatching blank line"
    (is (= [(ev "x")] (sse/parse-string "event: foo\n\ndata: x\n\n")))))

;; ---------------------------------------------------------------------------
;; id / retry
;; ---------------------------------------------------------------------------

(deftest id-persists-across-events
  (is (= [{:event "message" :data "1" :id "7" :retry nil}
          {:event "message" :data "2" :id "7" :retry nil}
          {:event "message" :data "3" :id "8" :retry nil}]
         (sse/parse-string "id: 7\ndata: 1\n\ndata: 2\n\nid: 8\ndata: 3\n\n")))
  (testing "id set by a non-dispatched block still applies"
    (is (= [{:event "message" :data "x" :id "9" :retry nil}]
           (sse/parse-string "id: 9\n\ndata: x\n\n")))))

(deftest id-containing-nul-is-ignored
  (is (= [{:event "message" :data "1" :id "ok" :retry nil}
          {:event "message" :data "2" :id "ok" :retry nil}]
         (sse/parse-string "id: ok\ndata: 1\n\nid: a\u0000b\ndata: 2\n\n"))))

(deftest retry-accepts-ascii-digits-only
  (is (= 3000 (:retry (first (sse/parse-string "retry: 3000\ndata: x\n\n")))))
  (doseq [bad ["abc" "12a" "-5" "+5" "  5" "5 " "٣" "1.5" ""]]
    (is (nil? (:retry (first (sse/parse-string (str "retry:" bad "\ndata: x\n\n")))))
        (pr-str bad)))
  (testing "invalid value keeps the previous valid one; retry persists"
    (is (= [1000 1000 2000]
           (map :retry (sse/parse-string
                        "retry: 1000\ndata: a\n\nretry: nope\ndata: b\n\nretry: 2000\ndata: c\n\n")))))
  (testing "retry-only block is not dispatched but applies to later events"
    (is (= [{:event "message" :data "x" :id nil :retry 500}]
           (sse/parse-string "retry: 500\n\ndata: x\n\n")))))

;; ---------------------------------------------------------------------------
;; Line endings, BOM, EOF
;; ---------------------------------------------------------------------------

(deftest all-line-endings
  (let [expected [(ev "e" "a\nb") (ev "c")]]
    (is (= expected (sse/parse-string "event: e\ndata: a\ndata: b\n\ndata: c\n\n")) "LF")
    (is (= expected (sse/parse-string "event: e\r\ndata: a\r\ndata: b\r\n\r\ndata: c\r\n\r\n")) "CRLF")
    (is (= expected (sse/parse-string "event: e\rdata: a\rdata: b\r\rdata: c\r\r")) "CR")
    (is (= expected (sse/parse-string "event: e\r\ndata: a\rdata: b\n\r\ndata: c\r\r\n")) "mixed")))

(deftest leading-bom-is-stripped-once
  (is (= [(ev "x")] (sse/parse-string "\uFEFFdata: x\n\n")))
  (is (= [] (sse/parse-string "\uFEFF\uFEFFdata: x\n\n"))
      "second BOM is part of the field name -> unknown field")
  (is (= [(ev "\uFEFFx")] (sse/parse-string "data: \uFEFFx\n\n")) "BOM inside a value kept")
  (is (= [(ev "a")] (sse/parse-string "data: a\n\n\uFEFFdata: b\n\n"))
      "only at stream start: a later BOM makes an unknown field name"))

(deftest incomplete-trailing-event-is-discarded
  (is (= [(ev "1")] (sse/parse-string "data: 1\n\ndata: 2\n")) "no blank line")
  (is (= [(ev "1")] (sse/parse-string "data: 1\n\ndata: 2")) "no line terminator")
  (is (= [] (sse/parse-string "data: only")))
  (is (= [] (sse/parse-string ""))))

;; ---------------------------------------------------------------------------
;; Transducers
;; ---------------------------------------------------------------------------

(deftest lines-splitter
  (is (= ["a" "b" "" "c"] (into [] (sse/lines) ["a\nb\r\n\rc"])))
  (is (= ["a" "b"] (into [] (sse/lines) ["a\r" "\nb"])) "CRLF split across chunks = one terminator")
  (is (= ["a" "" "b"] (into [] (sse/lines) ["a\r" "\r" "\nb"])) "CR | CRLF")
  (is (= ["a" "b"] (into [] (sse/lines) ["a\r" "" "\nb"])) "empty chunk between CR and LF"))

(deftest lines-splitter-edge-cases
  (is (= ["a" "x"] (into [] (sse/lines) ["a\r" "x"])) "CR then non-LF")
  (is (= ["abc" "de"] (into [] (sse/lines) ["a" "b" "c\n" "d" "e"])) "partial line spans chunks, flushed at end")
  (is (= ["" ""] (into [] (sse/lines) ["\n" "\n"])))
  (is (= [""] (into [] (sse/lines) ["\r"])) "lone CR at EOF")
  (is (= [] (into [] (sse/lines) [])))
  (is (= [] (into [] (sse/lines) ["" ""]))))

(deftest lines-splitter-honours-reduced
  (is (= ["a"] (into [] (comp (sse/lines) (take 1)) ["a\nb\nc\n"])))
  (is (= ["a" "b"] (into [] (comp (sse/lines) (take 2)) ["a\nb" "\nc\nd"]))))

(deftest crlf-split-across-chunks-does-not-dispatch-twice
  (is (= [(ev "x") (ev "y")]
         (sse/chunks->events ["data: x\r" "\n\r" "\ndata: y\r\n\r" "\n"])))
  (is (= [(ev "x")]
         (sse/chunks->events ["data: x\r" "\n" "\r"]))
      "blank CR line at EOF dispatches"))

(deftest events-transducer
  (is (= [(ev "a") (ev "b")] (sse/lines->events ["data: a" "" "data: b" ""])))
  (is (= [(ev "a")] (into [] (comp (sse/events) (take 1)) ["data: a" "" "data: b" ""])))
  (testing "state is per transducer instantiation"
    (let [xf (sse/events)]
      (is (= [(ev "a")] (into [] xf ["data: a" ""])))
      (is (= [(ev "b")] (into [] xf ["\uFEFFdata: b" ""]))
          "second use starts fresh: BOM stripped again, no carried-over data"))))

(defn- all-splits
  "Every way to cut s into two chunks, plus 1-char chunks."
  [s]
  (concat (for [i (range (inc (count s)))] [(subs s 0 i) (subs s i)])
          [(map str s)]))

(deftest chunking-is-transparent
  (let [s "\uFEFFevent: e\r\nid: 1\r\nretry: 10\r\ndata: a\rdata: b\n\r\n: c\r\rdata: z\r\n\r\n"
        expected (sse/parse-string s)]
    (is (= 2 (count expected)))
    (doseq [chunks (all-splits s)]
      (is (= expected (sse/chunks->events chunks)) (pr-str chunks)))))

;; ---------------------------------------------------------------------------
;; Vendor fixtures
;; ---------------------------------------------------------------------------

(defn- fixture [name] (slurp (str "test/resources/sse/" name ".sse")))

(defn- check-fixture-framings
  "Same events regardless of line ending (LF / CRLF / CR) and chunking."
  [s]
  (let [expected (sse/parse-string s)]
    (is (= expected (sse/parse-string (str/replace s "\n" "\r\n"))) "CRLF")
    (is (= expected (sse/parse-string (str/replace s "\n" "\r"))) "CR")
    (is (= expected (sse/chunks->events (map #(apply str %) (partition-all 7 s)))) "7-char chunks")
    (is (= expected (sse/chunks->events (map str (str/replace s "\n" "\r\n")))) "1-char CRLF chunks")
    expected))

(deftest anthropic-fixture
  (let [evs (check-fixture-framings (fixture "anthropic-text"))]
    (is (= ["message_start" "content_block_start" "ping" "content_block_delta"
            "content_block_delta" "content_block_stop" "message_delta" "message_stop"]
           (map :event evs)))
    (testing "SSE event name matches data type"
      (doseq [{:keys [event data]} evs]
        (is (str/includes? data (str "\"type\": \"" event "\"")) event)))))

(deftest openai-responses-fixture
  (let [evs (check-fixture-framings (fixture "openai-responses-text"))]
    (is (= ["response.created" "response.output_text.delta" "response.output_text.delta"
            "response.output_text.done" "response.completed"]
           (map :event evs)))
    (is (every? #(str/starts-with? (:data %) "{\"type\":") evs))))

(deftest openai-chat-fixture
  (let [evs (check-fixture-framings (fixture "openai-chat-text"))]
    (is (= 4 (count evs)))
    (is (every? #(= "message" (:event %)) evs) "chat chunks carry no event names")
    (is (every? #(str/includes? (:data %) "chat.completion.chunk") (butlast evs)))
    (is (= "[DONE]" (:data (last evs))) "[DONE] is plain data at the parser level")))

(deftest gemini-fixture
  (let [evs (check-fixture-framings (fixture "gemini-text"))]
    (is (= 2 (count evs)))
    (is (every? #(= "message" (:event %)) evs))
    (is (str/includes? (:data (last evs)) "\"finishReason\": \"STOP\""))))

(deftest openai-agents-fixture
  (let [evs (check-fixture-framings (fixture "agents-turn"))]
    (is (= 6 (count evs)))
    (is (every? #(= "message" (:event %)) evs) "dispatch on data type, not event name")
    (is (= ["agent.session.turn.created" "agent.session.turn.output_text.delta"
            "agent.session.turn.output_text.delta" "agent.session.turn.output_text.done"
            "agent.session.turn.completed" "agent.session.idle"]
           (map #(second (re-find #"^\{\"type\":\"([^\"]+)\"" (:data %))) evs)))))

(deftest truncated-vendor-stream-drops-last-event
  (let [s (fixture "anthropic-text")
        cut (subs s 0 (- (count s) 2))]
    (is (= "message_delta" (:event (last (sse/parse-string cut))))
        "message_stop without its blank line is not dispatched")))
