(ns tools.agents.openai.test-runner
  "Entry point for `clojure -M:test-openai` and `bb test-openai`."
  (:require [clojure.test :as t]
            tools.agents.openai-test
            tools.agents.openai.live-test
            tools.agents.openai.agents-test
            tools.agents.openai.agents.live-test
            tools.agents.openai.embeddings-test
            tools.agents.openai.files-test
            tools.agents.openai.images-test
            tools.agents.openai.webhooks-test
            tools.agents.openai.realtime-test))

(def suites
  ['tools.agents.openai-test
   'tools.agents.openai.live-test
   'tools.agents.openai.agents-test
   'tools.agents.openai.agents.live-test
   'tools.agents.openai.embeddings-test
   'tools.agents.openai.files-test
   'tools.agents.openai.images-test
   'tools.agents.openai.webhooks-test
   'tools.agents.openai.realtime-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply t/run-tests suites)]
    (System/exit (if (pos? (+ fail error)) 1 0))))
