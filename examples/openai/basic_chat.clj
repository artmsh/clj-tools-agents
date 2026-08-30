(ns examples.openai.basic-chat
  "Port of openai-python's headline README example — the Responses API with a
   top-level `instructions` system prompt and a bare-string `input`:

     import os
     from openai import OpenAI

     client = OpenAI(api_key=os.environ.get(\"OPENAI_API_KEY\"))

     response = client.responses.create(
         model=\"gpt-5.5\",
         instructions=\"You are a coding assistant that talks like a pirate.\",
         input=\"How do I check if a Python object is an instance of a class?\",
     )

     print(response.output_text)"
  (:require [tools.agents.openai :as oai]))

(def model "gpt-5.5")

(defn respond
  ([client input] (respond client input {}))
  ([client input {:keys [instructions max-output-tokens temperature]}]
   (let [request (cond-> {"model" model "input" input}
                   instructions      (assoc "instructions" instructions)
                   max-output-tokens (assoc "max_output_tokens" max-output-tokens)
                   temperature       (assoc "temperature" temperature))]
     (oai/output-text (oai/responses-create client request)))))

(defn run-example [client]
  (respond client
           "How do I check if a Python object is an instance of a class?"
           {:instructions "You are a coding assistant that talks like a pirate."}))

;; The Python README also shows the streaming call
;; (`client.responses.create(..., stream=True)` -> `for event in stream: ...`).
;; tools.agents.openai rejects :stream true outright rather than silently
;; ignoring it or hanging — see the README's platform-limitations section.
(defn run-streaming-example [client]
  (try
    (oai/responses-create client {"model" model "input" "hi" "stream" true})
    :should-not-reach-here
    (catch Exception e
      {:caught true :type (:type (ex-data e))})))

;; JVM-only manual entry point (not exercised by the automated suite, and
;; never run against the real network by this repo) — credentials come only
;; from OPENAI_API_KEY, never a literal in source.
(defn -main [& _]
  (println (run-example (oai/client))))
