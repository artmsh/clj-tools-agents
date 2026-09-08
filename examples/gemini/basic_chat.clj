(ns examples.gemini.basic-chat
  "Port of python-genai's README headline quickstart — generate_content with a
   systemInstruction and a GenerateContentConfig (max_output_tokens,
   temperature):

     from google import genai
     from google.genai import types

     client = genai.Client(api_key='GEMINI_API_KEY')

     response = client.models.generate_content(
         model='gemini-2.5-flash',
         contents='high',
         config=types.GenerateContentConfig(
             system_instruction='I say high, you say low',
             max_output_tokens=3,
             temperature=0.3,
         ),
     )
     print(response.text)

   `contents` here is a bare string in the Python example — the REST API
   itself only accepts the {\"role\" ... \"parts\" [...]} shape (see
   docs/gemini.md), so `respond` wraps the bare input through
   `gemini/add-user-message` rather than porting a convenience the transport
   layer doesn't have."
  (:require [tools.agents.gemini :as gemini]))

(def model "gemini-2.5-flash")

(defn respond
  ([client input] (respond client input {}))
  ([client input {:keys [system-instruction max-output-tokens temperature]}]
   (let [config  (cond-> {}
                   max-output-tokens (assoc "maxOutputTokens" max-output-tokens)
                   temperature       (assoc "temperature" temperature))
         request (cond-> {"contents" (gemini/add-user-message [] input)}
                   system-instruction (assoc "systemInstruction" {"parts" [{"text" system-instruction}]})
                   (seq config)       (assoc "generationConfig" config))]
     (gemini/output-text (gemini/generate-content client model request)))))

(defn run-example [client]
  (respond client "high" {:system-instruction "I say high, you say low" :max-output-tokens 3 :temperature 0.3}))

;; JVM-only manual entry point (not exercised by the automated suite, and
;; never run against the real network by this repo) — credentials come only
;; from GOOGLE_API_KEY/GEMINI_API_KEY, never a literal in source.
(defn -main [& _]
  (println (run-example (gemini/client))))
