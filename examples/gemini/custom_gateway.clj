(ns examples.gemini.custom-gateway
  "Port of the base_url-override shape (a self-hosted or Gemini-compatible
   gateway instead of generativelanguage.googleapis.com), plus a JSON-mode
   generationConfig:

     client = genai.Client(
         api_key='gw-key',
         http_options=types.HttpOptions(base_url='http://my.test.server.example.com:8083'),
     )

     response = client.models.generate_content(
         model='gemini-2.5-flash',
         contents='Generate a very short event bridge rule as json',
         config=types.GenerateContentConfig(
             max_output_tokens=1024,
             response_mime_type='application/json',
         ),
     )
     json_body = response.text

   Note :base-url here does NOT carry the `/v1beta` path segment — unlike
   tools.agents.openai's base_url, which includes `/v1` itself,
   tools.agents.gemini appends `/{api-version}/models/{model}:...` (see the
   URL-shape note in gemini.cljc's ns docstring)."
  (:require [tools.agents.gemini :as gemini]))

(defn run-example [client]
  (let [response (gemini/generate-content
                   client "gemini-2.5-flash"
                   {"contents" (gemini/add-user-message [] "Generate a very short event bridge rule as json")
                    "generationConfig" {"maxOutputTokens" 1024
                                        "responseMimeType" "application/json"}})]
    (gemini/output-text response)))

;; JVM-only manual entry point.
(defn -main [& _]
  (println (run-example (gemini/client {:api-key "gw-key" :base-url "http://my.test.server.example.com:8083"}))))
