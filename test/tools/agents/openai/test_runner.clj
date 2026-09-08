(ns tools.agents.openai.test-runner
  "Entry point for `clojure -M:test-openai` and `bb test-openai`."
  (:require [clojure.test :as t]
            tools.agents.openai-test
            tools.agents.openai.live-test))

(def suites
  ['tools.agents.openai-test
   'tools.agents.openai.live-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
