(ns examples.anthropic.tool-use
  "Port of anthropic-sdk-python's tool-calling loop shape:

     import anthropic
     client = anthropic.Anthropic(api_key=\"<REDACTED>\")
     tools = [{\"name\": \"get_weather\", \"description\": \"Get current weather for a location\",
               \"input_schema\": {\"type\": \"object\",
                                  \"properties\": {\"location\": {\"type\": \"string\"}},
                                  \"required\": [\"location\"]}}]
     def run_tool(name, input):
         if name == \"get_weather\": return f\"72F and sunny in {input['location']}\"
         raise ValueError(f\"unknown tool: {name}\")
     messages = [{\"role\": \"user\", \"content\": \"What's the weather in San Francisco?\"}]
     while True:
         response = client.messages.create(model=\"claude-sonnet-4-6\", max_tokens=1024,
                                            tools=tools, messages=messages)
         if response.stop_reason != \"tool_use\":
             break
         messages.append({\"role\": \"assistant\", \"content\": response.content})
         results = []
         for block in response.content:
             if block.type == \"tool_use\":
                 results.append({\"type\": \"tool_result\", \"tool_use_id\": block.id,
                                 \"content\": run_tool(block.name, block.input)})
         messages.append({\"role\": \"user\", \"content\": results})
     text = response.content[0].text

   \"tools\" already passes straight through messages-create untouched (this
   library is data-transparent by design) — tools.agents.anthropic only adds
   ergonomics around the two things the Python port above hand-rolls:
   tool-use?/tool-calls on the response side, and add-tool-results for
   bundling every parallel tool_use call's result into the single following
   user turn the API requires (never one message per result)."
  (:require [tools.agents.anthropic :as a]))

(def model "claude-sonnet-4-6")

(def tools
  [{"name" "get_weather"
    "description" "Get current weather for a location"
    "input_schema" {"type" "object"
                     "properties" {"location" {"type" "string"}}
                     "required" ["location"]}}])

(defn run-tool [name input]
  (cond
    (= name "get_weather") (str "72F and sunny in " (get input "location"))
    :else (throw (ex-info (str "unknown tool: " name) {:tool name}))))

(defn tool-loop
  "Drives messages-create until the model stops asking for tools, running
   every requested tool locally via run-tool and feeding all of a turn's
   results back in a single user message. Returns the final response map."
  [client messages]
  (loop [messages messages]
    (let [response (a/messages-create client
                     {"model" model "max_tokens" 1024 "tools" tools "messages" messages})]
      (if (a/tool-use? response)
        (let [messages (a/add-assistant-message messages (get response "content"))
              results  (mapv (fn [{:keys [id name input]}]
                                (try
                                  {:tool-use-id id :content (run-tool name input)}
                                  (catch Exception e
                                    {:tool-use-id id :content (str e) :is-error true})))
                              (a/tool-calls response))]
          (recur (a/add-tool-results messages results)))
        response))))

(defn run-example [client]
  (let [messages (a/add-user-message [] "What's the weather in San Francisco?")]
    (a/output-text (tool-loop client messages))))

;; JVM-only manual entry point; ANTHROPIC_API_KEY supplies credentials, never
;; a literal in source.
(defn -main [& _]
  (println (run-example (a/client))))
