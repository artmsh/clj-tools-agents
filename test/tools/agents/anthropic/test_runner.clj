(ns tools.agents.anthropic.test-runner
  "Entry point for `clojure -M:test-anthropic` and `bb test-anthropic`."
  (:require [clojure.test :as t]
            tools.agents.anthropic-test
            tools.agents.anthropic.live-test
            tools.agents.anthropic.spec-test
            tools.agents.anthropic.visualize-test))

(def suites
  ['tools.agents.anthropic-test
   'tools.agents.anthropic.live-test
   'tools.agents.anthropic.spec-test
   'tools.agents.anthropic.visualize-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
