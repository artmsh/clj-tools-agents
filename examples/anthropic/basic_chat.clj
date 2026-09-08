(ns examples.anthropic.basic-chat
  "Port of anthropic-sdk-python's most common shape: a hand-rolled
   add_user_message/add_assistant_message pair plus a small chat() wrapper
   around client.messages.create(**params).

     import anthropic
     client = anthropic.Anthropic(api_key=\"<REDACTED>\")
     model = \"claude-sonnet-4-6\"
     def add_user_message(messages, text):
         messages.append({\"role\": \"user\", \"content\": text})
     def add_assistant_message(messages, text):
         messages.append({\"role\": \"assistant\", \"content\": text})
     def chat(messages, system=None, temperature=None):
         params = {\"model\": model, \"max_tokens\": 1000, \"messages\": messages, \"temperature\": temperature}
         if system: params[\"system\"] = system
         message = client.messages.create(**params)
         return message.content[0].text

   tools.agents.anthropic already ships add-user-message/add-assistant-message
   (Python's list.append(...) mutation becomes Clojure's conj-and-return), so
   this file only has to add the tiny chat() convenience wrapper."
  (:require [tools.agents.anthropic :as a]))

(def model "claude-sonnet-4-6")

(defn chat
  ([client messages] (chat client messages {}))
  ([client messages {:keys [system temperature]}]
   (let [request (cond-> {"model" model "max_tokens" 1000 "messages" messages}
                   temperature (assoc "temperature" temperature)
                   system      (assoc "system" system))
         message  (a/messages-create client request)]
     (get-in message ["content" 0 "text"]))))

(defn run-example [client]
  (let [messages (a/add-user-message [] "Write a 1 sentence description of a fake database")]
    (chat client messages)))

;; The Python example also shows the low-level streaming call
;; (`client.messages.create(..., stream=True)` -> `for event in stream: ...`).
;; tools.agents.anthropic rejects :stream true outright rather than silently
;; ignoring it or hanging — see the README's platform-limitations section.
(defn run-streaming-example [client]
  (let [messages (a/add-user-message [] "Write a 1 sentence description of a fake database")]
    (try
      (a/messages-create client {"model" model "max_tokens" 1000 "messages" messages "stream" true})
      :should-not-reach-here
      (catch Exception e
        {:caught true :type (:type (ex-data e))}))))

;; JVM-only manual entry point (not exercised by the automated suite, and
;; never run against the real network by this repo) — credentials come only
;; from ANTHROPIC_API_KEY, never a literal in source.
(defn -main [& _]
  (println (run-example (a/client))))
