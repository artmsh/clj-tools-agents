(ns tools.agents.core.test-runner
  "Entry point for `clojure -M:test-core` and `bb test-core`: tests for the
   provider-agnostic core namespaces (tools.agents.json, tools.agents.sse, ...)."
  (:require [clojure.test :as t]
            tools.agents.json-test
            tools.agents.sse-test))

(def suites
  ['tools.agents.json-test
   'tools.agents.sse-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
