(ns examples.openai.chat-completions
  "Port of openai-python's legacy Chat Completions example — a `developer`
   instruction turn plus a `user` turn, read back off
   `choices[0].message.content`:

     from openai import OpenAI

     client = OpenAI()

     completion = client.chat.completions.create(
         model=\"gpt-5.5\",
         messages=[
             {\"role\": \"developer\", \"content\": \"Talk like a pirate.\"},
             {\"role\": \"user\",
              \"content\": \"How do I check if a Python object is an instance of a class?\"},
         ],
     )

     print(completion.choices[0].message.content)

   tools.agents.openai ships add-developer-message/add-user-message (Python's
   list literal / list.append(...) becomes conj-and-return), so this file only
   adds the tiny chat() convenience wrapper."
  (:require [tools.agents.openai :as oai]))

(def model "gpt-5.5")

(defn chat
  ([client messages] (chat client messages {}))
  ([client messages {:keys [temperature max-completion-tokens]}]
   (let [request (cond-> {"model" model "messages" messages}
                   temperature           (assoc "temperature" temperature)
                   max-completion-tokens (assoc "max_completion_tokens" max-completion-tokens))]
     (oai/completion-text (oai/chat-completions-create client request)))))

(defn run-example [client]
  (let [messages (-> []
                     (oai/add-developer-message "Talk like a pirate.")
                     (oai/add-user-message "How do I check if a Python object is an instance of a class?"))]
    (chat client messages)))

;; JVM-only manual entry point; OPENAI_API_KEY supplies credentials, never a
;; literal in source.
(defn -main [& _]
  (println (run-example (oai/client))))
