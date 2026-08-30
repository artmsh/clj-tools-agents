(ns examples.anthropic.custom-gateway
  "Port of the auth_token + custom base_url shape (a third-party
   Anthropic-compatible gateway instead of api.anthropic.com):

     client = anthropic.Anthropic(base_url=\"https://api.deepseek.com/anthropic\", auth_token=\"<REDACTED>\")
     response = client.messages.create(
         model=\"claude-sonnet-4-5-20250929\", max_tokens=1024, stop_sequences=[\"```\"],
         thinking={\"type\": \"disabled\"},
         messages=[
             {\"role\": \"user\", \"content\": \"Generate a very short event bridge rule as json\"},
             {\"role\": \"assistant\", \"content\": \"```json\\n\"},
         ],
     )
     json_body = response.content[0].text"
  (:require [tools.agents.anthropic :as a]))

(defn run-example [client]
  (let [response (a/messages-create
                   client
                   {"model" "claude-sonnet-4-5-20250929"
                    "max_tokens" 1024
                    "stop_sequences" ["```"]
                    "thinking" {"type" "disabled"}
                    "messages" [{"role" "user" "content" "Generate a very short event bridge rule as json"}
                                {"role" "assistant" "content" "```json\n"}]})]
    (get-in response ["content" 0 "text"])))

;; JVM-only manual entry point. :auth-token is resolved from
;; ANTHROPIC_AUTH_TOKEN by tools.agents.anthropic/client itself when omitted —
;; shown explicit here only to mirror the Python example's constructor call.
(defn -main [& _]
  (println (run-example (a/client {:base-url "https://api.deepseek.com/anthropic"}))))
