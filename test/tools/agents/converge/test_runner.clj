(ns tools.agents.converge.test-runner
  "Entry point for `clojure -M:test-converge` and `bb test-converge`."
  (:require [clojure.test :as t]
            tools.agents.converge-test))

(def suites
  ['tools.agents.converge-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
