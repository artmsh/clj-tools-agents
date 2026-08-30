(ns tools.agents.openai.test-runner
  "Entry point for `clojure -M:test` and `bb test`. clz runs the same files
   through its own `clz test` runner, so there are no per-runtime test shims."
  (:require [clojure.test :as t]
            tools.agents.openai-test
            tools.agents.openai.live-test))

(def suites
  ['tools.agents.openai-test
   'tools.agents.openai.live-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
