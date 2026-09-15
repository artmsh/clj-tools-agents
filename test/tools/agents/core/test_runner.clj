(ns tools.agents.core.test-runner
  "Entry point for `clojure -M:test-core` and `bb test-core`: tests for the
   provider-agnostic core namespaces (tools.agents.json, tools.agents.sse,
   tools.agents.http, tools.agents.stream, ...)."
  (:require [clojure.test :as t]
            tools.agents.http-test
            tools.agents.json-test
            tools.agents.sse-test
            tools.agents.stream-test))

(def suites
  ['tools.agents.http-test
   'tools.agents.json-test
   'tools.agents.sse-test
   'tools.agents.stream-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
