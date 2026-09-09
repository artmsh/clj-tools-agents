(ns examples.converge.provider-demo
  "tools.agents.converge orchestration demo.

   Uses local :call hooks so it needs no API keys or network — swap them for
   :model/:api-key (and drop :call) to hit real providers.

   Run: clojure -Sdeps '{:paths [\"src\" \".\"]}' -M -e \"(require 'examples.converge.provider-demo) (examples.converge.provider-demo/-main)\"
        bb -cp src:. -e \"(require 'examples.converge.provider-demo) (examples.converge.provider-demo/-main)\"

   Migrated from clz's examples/llm_provider_demo.clj, which demonstrated the
   same orchestration against clz's now-removed embedded clz.llm namespace."
  (:require [tools.agents.converge :as converge]))

(def providers
  [{:provider :openai
    :id :concise
    :system "Answer in one sentence."
    :call (fn [_spec prompt]
            (Thread/sleep 50)
            {:text (str "Concise answer for: " prompt)
             :response {:mock true :id :concise}})}
   {:provider :anthropic
    :id :reviewer
    :system "Point out risk and uncertainty."
    :call (fn [_spec prompt]
            {:text (str "Reviewer answer for: " prompt)
             :response {:mock true :id :reviewer}})}
   {:provider :openai
    :id :failing-provider
    :call (fn [_spec _prompt]
            (throw (ex-info "simulated provider failure" {})))}])

(def prompt
  "Should provider HTTP details stay separate from convergence logic?")

(defn -main [& _]
  (let [result (converge/converge providers prompt)]
    (println "Status:" (:status result))
    (println "Answer:")
    (println (:answer result))
    (println)
    (println "Provider results:")
    (doseq [provider-result (:results result)]
      (println " -" (:id provider-result)
               (:provider provider-result)
               (:status provider-result)
               (if (= :ok (:status provider-result))
                 (:text provider-result)
                 (ex-message (:error provider-result)))))))
