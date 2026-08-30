(ns examples.anthropic.eval-harness
  "Port of an eval-harness shape: explicit temperature/stop_sequences, thinking
   disabled, a prefilled assistant turn, and a custom gateway base_url.

     client = anthropic.Anthropic(api_key=\"<REDACTED>\", base_url=\"https://ai-gateway.vercel.sh\")
     model = \"anthropic/claude-haiku-4.5\"
     def chat(messages, system=None, temperature=1.0, stop_sequences=None):
         params = {\"model\": model, \"max_tokens\": 1000, \"messages\": messages, \"temperature\": temperature,
                   \"thinking\": {\"type\": \"disabled\"}}
         if stop_sequences is not None: params[\"stop_sequences\"] = stop_sequences
         if system: params[\"system\"] = system
         message = client.messages.create(**params)
         return message.content[0].text
     messages = []
     messages.append({\"role\": \"user\", \"content\": \"...\"})
     messages.append({\"role\": \"assistant\", \"content\": \"```json\"})   # prefill
     text = chat(messages, stop_sequences=[\"```\"])"
  (:require [tools.agents.anthropic :as a]))

(def model "anthropic/claude-haiku-4.5")

(defn chat
  ([client messages] (chat client messages {}))
  ([client messages {:keys [system temperature stop-sequences] :or {temperature 1.0}}]
   (let [request (cond-> {"model" model "max_tokens" 1000 "messages" messages
                           "temperature" temperature
                           "thinking" {"type" "disabled"}}
                   (some? stop-sequences) (assoc "stop_sequences" stop-sequences)
                   system (assoc "system" system))
         message  (a/messages-create client request)]
     (get-in message ["content" 0 "text"]))))

(defn run-example [client]
  (let [messages (-> []
                      (a/add-user-message "Give me a JSON object with two keys, \"a\" and \"b\".")
                      ;; prefill: assistant turn seeded with an open code fence, model continues from there
                      (a/add-assistant-message "```json"))]
    (chat client messages {:stop-sequences ["```"]})))

;; JVM-only manual entry point; ANTHROPIC_API_KEY / ANTHROPIC_BASE_URL supply
;; credentials and host, never a literal in source.
(defn -main [& _]
  (println (run-example (a/client {:base-url "https://ai-gateway.vercel.sh"}))))
