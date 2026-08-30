(ns tools.agents.anthropic.test-runner
  "Entry point for `clojure -M:test` and `bb test`. clz runs tools.agents.
   anthropic-test/live-test/visualize-test through its own `clz test` runner
   directly (see script/test-all.sh), so those three need no shim here.

   tools.agents.anthropic.spec-test is JVM Clojure + Babashka ONLY (spec1 has
   no clz implementation — see tools.agents.anthropic.spec's ns docstring) and
   is deliberately never named on script/test-all.sh's `clz test` command
   line; it's safe to require unconditionally in this file precisely because
   this file itself is never given to clz at all."
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
