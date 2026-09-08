(ns examples.anthropic.visualize-demo
  "Demo of tools.agents.anthropic.visualize, ported alongside visualize.py
   itself (anthropic-cookbooks' tool_use/utils/visualize.py). The
   cookbook's own real usage site is inside its tool_use notebooks,
   capturing whatever client.messages.create(...) returns mid tool-calling
   loop. This example mirrors that shape but against hand-authored FIXTURE
   responses (no network, no ANTHROPIC_API_KEY needed) so it exercises
   text, tool_use and tool_result rendering deterministically — see
   test/tools/agents/anthropic/visualize_test.cljc for the full assertion
   coverage this is a human-readable companion to. (Unlike the other four
   examples/*.clj ports, this one is NOT run against live_test.cljc's mock
   server — there is no network call in the renderer to exercise there.)"
  (:require [tools.agents.anthropic.visualize :as viz]))

;; What messages-create returns mid tool-calling loop: the model's turn
;; asking for a weather lookup.
(def fixture-assistant-turn
  {"role" "assistant"
   "model" "claude-sonnet-4-6"
   "stop_reason" "tool_use"
   "usage" {"input_tokens" 512 "output_tokens" 89}
   "content" [{"type" "text" "text" "Let me check the weather in San Francisco for you."}
              {"type" "tool_use" "id" "toolu_01"
               "name" "get_weather" "input" {"location" "San Francisco"}}]})

;; The reply turn add-tool-results would build — included purely to show
;; tool_result rendering; a real request turn is never itself visualized in
;; the cookbook, but the renderer handles it like any other content block.
(def fixture-tool-result-turn
  {"role" "user"
   "content" [{"type" "tool_result" "tool_use_id" "toolu_01" "content" "72F and sunny"}]})

(def fixture-final-turn
  {"role" "assistant"
   "model" "claude-sonnet-4-6"
   "stop_reason" "end_turn"
   "usage" {"input_tokens" 634 "output_tokens" 21}
   "content" [{"type" "text" "text" "It's 72F and sunny in San Francisco."}]})

(defn run-example
  "Replays a full 3-turn tool-calling exchange through a visualizer
   session, printing each turn as it's captured. No network, no
   credentials required. Returns the visualizer (its :responses atom holds
   all three captured turns)."
  []
  (let [v (viz/visualizer)]
    (viz/capture! v fixture-assistant-turn)
    (viz/capture! v fixture-tool-result-turn)
    (viz/capture! v fixture-final-turn)
    v))

(defn -main [& _]
  (run-example)
  nil)
