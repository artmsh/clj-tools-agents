(ns examples.openai.custom-gateway
  "Port of the base_url-override shape (a self-hosted or OpenAI-compatible
   gateway instead of api.openai.com) plus the org/project scoping headers:

     client = OpenAI(
         base_url=\"http://my.test.server.example.com:8083/v1\",
         organization=\"org-abc\",
         project=\"proj-xyz\",
     )

     response = client.responses.create(
         model=\"gpt-4o\",
         input=[{\"role\": \"user\", \"content\": \"Generate a very short event bridge rule as json\"}],
         max_output_tokens=1024,
         text={\"format\": {\"type\": \"json_object\"}},
     )
     json_body = response.output_text

   Note the base_url carries the `/v1` path segment itself — tools.agents.openai
   appends only `/responses` to it (see the endpoint-url note in the source)."
  (:require [tools.agents.openai :as oai]))

(defn run-example [client]
  (let [response (oai/responses-create
                   client
                   {"model" "gpt-4o"
                    "max_output_tokens" 1024
                    "text" {"format" {"type" "json_object"}}
                    "input" (oai/add-user-message [] "Generate a very short event bridge rule as json")})]
    (oai/output-text response)))

;; JVM-only manual entry point. :organization / :project are resolved from
;; OPENAI_ORG_ID / OPENAI_PROJECT_ID by tools.agents.openai/client itself when
;; omitted — shown explicit here only to mirror the Python constructor call.
(defn -main [& _]
  (println (run-example (oai/client {:base-url "http://my.test.server.example.com:8083/v1"}))))
