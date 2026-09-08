(ns examples.gemini.count-tokens
  "Port of python-genai's README count_tokens example:

     response = client.models.count_tokens(
         model='gemini-2.5-flash',
         contents='why is the sky blue?',
     )
     print(response)

   (The Python example's `contents` is also a bare string — see
   examples.gemini.basic-chat's docstring for why the REST transport here
   always takes the wrapped {\"role\" ... \"parts\" [...]} shape instead.)"
  (:require [tools.agents.gemini :as gemini]))

(def model "gemini-2.5-flash")

(defn run-example [client]
  (gemini/count-tokens client model {"contents" (gemini/add-user-message [] "why is the sky blue?")}))

;; JVM-only manual entry point; GOOGLE_API_KEY/GEMINI_API_KEY supply
;; credentials, never a literal in source.
(defn -main [& _]
  (println (run-example (gemini/client))))
