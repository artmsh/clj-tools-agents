(ns tools.agents.gemini.test-runner
  "Entry point for `clojure -M:test-gemini` and `bb test-gemini`."
  (:require [clojure.test :as t]
            tools.agents.gemini-test
            tools.agents.gemini.live-test
            tools.agents.gemini.stream-test))

(def suites
  ['tools.agents.gemini-test
   'tools.agents.gemini.live-test
   'tools.agents.gemini.stream-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
