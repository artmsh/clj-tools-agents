(ns tools.agents.fusion.test-runner
  "Entry point for `clojure -M:test-fusion` and `bb test-fusion`."
  (:require [clojure.test :as t]
            tools.agents.fusion-test))

(def suites
  ['tools.agents.fusion-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
