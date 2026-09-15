(ns examples.openai.azure
  "Port of Microsoft's Azure OpenAI v1 API shape: the plain OpenAI client
   pointed at the Azure resource, with no AzureOpenAI class and no api-version
   (https://learn.microsoft.com/en-us/azure/foundry/openai/api-version-lifecycle):

     client = OpenAI(
         api_key=os.getenv(\"AZURE_OPENAI_API_KEY\"),
         base_url=\"https://YOUR-RESOURCE-NAME.openai.azure.com/openai/v1/\",
     )

     response = client.responses.create(
         model=\"gpt-4.1-nano\",  # your model deployment name
         input=\"This is a test.\",
     )

   tools.agents.openai sends the credential as `Authorization: Bearer <:api-key>`.
   Azure v1 accepts both an API key and a Microsoft Entra ID access token in
   that header. The legacy `api-key: <key>` header is not sent. A static Entra
   token is not refreshed here; see docs/openai.md, section Azure OpenAI (v1 API)."
  (:require [clojure.string :as str]
            [tools.agents.openai :as oai]))

(defn azure-v1-base-url
  "`https://<resource>.openai.azure.com` (trailing slashes allowed) ->
   `https://<resource>.openai.azure.com/openai/v1`."
  [endpoint]
  (str (str/replace endpoint #"/+$" "") "/openai/v1"))

(defn run-example [client]
  (-> (oai/responses-create client {"model" "gpt-4.1-nano"   ; the deployment name, not the model family
                                    "input" "This is a test."})
      (oai/output-text)))

;; JVM-only manual entry point (not exercised by the automated suite, and never
;; run against a live Azure resource by this repo). Credentials come only from
;; the environment:
;;   AZURE_OPENAI_ENDPOINT    https://<resource>.openai.azure.com
;;   AZURE_OPENAI_API_KEY     resource API key, or
;;   AZURE_OPENAI_AUTH_TOKEN  Entra ID access token (scope https://ai.azure.com/.default),
;;                            e.g. `az account get-access-token --scope https://ai.azure.com/.default`
(defn -main [& _]
  (let [endpoint (or (System/getenv "AZURE_OPENAI_ENDPOINT")
                     (throw (ex-info "AZURE_OPENAI_ENDPOINT is not set" {})))
        token    (or (System/getenv "AZURE_OPENAI_API_KEY")
                     (System/getenv "AZURE_OPENAI_AUTH_TOKEN")
                     (throw (ex-info "set AZURE_OPENAI_API_KEY or AZURE_OPENAI_AUTH_TOKEN" {})))]
    (println (run-example (oai/client {:base-url (azure-v1-base-url endpoint)
                                       :api-key  token})))))
