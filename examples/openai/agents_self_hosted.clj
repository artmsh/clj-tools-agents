(ns examples.openai.agents-self-hosted
  "Port of the Agents API self-hosted sandbox guide's session-creation step
   (https://developers.openai.com/api/docs/guides/agents-api/environments/self-hosted):

     session = client.beta.agents.sessions.create(
         agent={
             \"model\": \"gpt-6-astra\",
             \"instructions\": \"You are a helpful coding assistant. Write clean code and verify that it works.\",
         },
         environment={\"type\": \"self_hosted\", \"workspace_directory\": \"/workspace\"},
     )

   `run-example` does the same and returns the `codex exec-server` command
   line to run inside your own infrastructure — see
   `tools.agents.openai.agents/self-hosted-executor-command`'s docstring for
   exactly what this repo does, and does not do, about actually starting that
   executor."
  (:require [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.agents :as agents]))

(def model "gpt-6-astra")

(defn run-example [client]
  (let [session (agents/sessions-create
                 client
                 {"agent" {"model" model
                           "instructions" (str "You are a helpful coding assistant. "
                                                "Write clean code and verify that it works.")}
                  "environment" {"type" "self_hosted" "workspace_directory" "/workspace"}})]
    {:session-id (get session "id")
     :executor-command (agents/self-hosted-executor-command session)}))

;; JVM-only manual entry point (not exercised by the automated suite, and
;; never run against the real network by this repo) — credentials come only
;; from OPENAI_API_KEY, never a literal in source. Printing the executor
;; command is as far as this repo goes: actually running it means starting
;; `codex exec-server` inside infrastructure YOU provision and trust, holding
;; a restricted environment key as CODEX_API_KEY (never OPENAI_API_KEY) — see
;; docs/openai-agents.md's Self-hosted sandboxes section.
(defn -main [& _]
  (let [{:keys [session-id executor-command]} (run-example (oai/client))]
    (println "session:" session-id)
    (println "run this inside your own environment:")
    (println (str/join " " executor-command))))
