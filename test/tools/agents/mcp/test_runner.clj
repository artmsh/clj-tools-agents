(ns tools.agents.mcp.test-runner
  "Entry point for `clojure -M:test-mcp` and `bb test-mcp`.

   tools.agents.mcp.stdio-child is NOT listed: it is the server half of the
   stdio end-to-end test, launched as a subprocess by
   tools.agents.mcp.stdio-test, and contains no tests of its own."
  (:require [clojure.test :as t]
            tools.agents.mcp-test
            tools.agents.mcp.client-test
            tools.agents.mcp.conformance-test
            tools.agents.mcp.examples-test
            tools.agents.mcp.http-test
            tools.agents.mcp.stdio-test))

(def suites
  ['tools.agents.mcp-test
   'tools.agents.mcp.conformance-test
   'tools.agents.mcp.client-test
   'tools.agents.mcp.http-test
   'tools.agents.mcp.stdio-test
   'tools.agents.mcp.examples-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
