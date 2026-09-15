(ns examples.openai.agents-sandbox-task
  "Port of the Agents API quickstart's directory-tree task
   (https://developers.openai.com/api/docs/guides/agents-api/quickstart),
   using OpenAI-hosted-sandbox polling instead of the doc's SSE `stream: true`
   (the streaming equivalent is `(await-root-turn (sessions-create-stream
   client request))`; this example shows the polling path):

     with client.beta.agents.sessions.create(
         agent={\"model\": \"gpt-6-astra\",
                \"instructions\": \"Write clean code, run it, and report the actual output.\"},
         environment={\"type\": \"openai_hosted\"},
         input=\"Create tree.py, a Python script that prints a readable tree of the
files in the current directory. Run it and show me the output.\",
         stream=True,
     ) as events:
         for event in events:
             print(event.to_json(indent=None), flush=True)

   `run-example` instead creates the session with `stream` omitted, polls
   `sessions-retrieve` until the session leaves the two in-flight statuses
   OpenAI's docs name (\"created\", \"in_progress\"), and returns the
   turn that ran (from `sessions-turns-list`) with the assistant's final text
   pulled from `sessions-items-list`. The turn is the outcome: a failed turn
   leaves the session \"idle\"."
  (:require [tools.agents.openai :as oai]
            [tools.agents.openai.agents :as agents]))

(def model "gpt-6-astra")

(def task
  (str "Create tree.py, a Python script that prints a readable tree of the "
       "files in the current directory. Run it and show me the output."))

(defn- terminal-status?
  "True once the session has left \"created\"/\"in_progress\" — \"idle\",
   \"failed\", or \"requires_action\" all stop the poll loop so the caller
   can inspect which one it landed on."
  [session]
  (not (contains? #{"created" "in_progress"} (get session "status"))))

(defn create-task-session
  "Start the task in a fresh OpenAI-hosted sandbox and return the created
   session."
  [client]
  (agents/sessions-create
   client
   {"agent" {"model" model
             "instructions" "Write clean code, run it, and report the actual output."}
    "environment" {"type" "openai_hosted"}
    "input" task}))

(defn poll-until-done
  "Poll every `interval-ms` (default 500) until the session is settled, or
   `max-attempts` (default 60, i.e. ~30s) is spent — at which point it throws
   rather than silently handing back an in-flight session. Returns
   `{:session .. :turn ..}`.

   Settled means the session has left \"created\"/\"in_progress\" AND
   either it is \"requires_action\" or its latest root turn is
   `turn-finished?`. The session status alone is not enough: it can settle
   before the turn list shows a terminal turn (or any turn at all), and a
   failed turn leaves the session \"idle\". `sleep-fn` is injected (default
   `Thread/sleep`) so tests can run this instantly against a mock server, the
   same testability-seam idiom `tools.agents.openai`'s retry loop uses for its
   clock and RNG."
  ([client session-id] (poll-until-done client session-id {}))
  ([client session-id {:keys [interval-ms max-attempts sleep-fn]
                        :or   {interval-ms 500 max-attempts 60
                               sleep-fn (fn [ms] (Thread/sleep (long ms)))}}]
   ;; `attempt` counts polls already made, starting at 1 for the one about to
   ;; happen — checking `(>= attempt max-attempts)` AFTER that poll (not
   ;; before it) means a session stuck in-flight makes exactly `max-attempts`
   ;; polls before this throws, not max-attempts + 1.
   (loop [attempt 1]
     (let [session (agents/sessions-retrieve client session-id)
           turn    (when (terminal-status? session)
                     (agents/latest-root-turn (agents/sessions-turns-list client session-id)))]
       (cond
         (and (terminal-status? session)
              (or (= "requires_action" (get session "status")) (agents/turn-finished? turn)))
         {:session session :turn turn}

         (>= attempt max-attempts)
         (throw (ex-info (str "examples.openai.agents-sandbox-task/poll-until-done: session " session-id
                               " still \"" (get session "status") "\" (turn "
                               (pr-str (get turn "status")) ") after " max-attempts " polls")
                          {:type ::poll-timeout :session session :turn turn}))

         :else (do (sleep-fn interval-ms) (recur (inc attempt))))))))

(defn run-example
  "Create the task session, poll it to completion, and return the session's
   terminal `\"status\"`, the root turn that ran (`:turn`, whose \"status\"
   and \"error\" say whether it succeeded) and the assistant's final text
   (via `items-output-text`)."
  ([client] (run-example client {}))
  ([client poll-opts]
   (let [session        (create-task-session client)
         {final :session
          turn  :turn}  (poll-until-done client (get session "id") poll-opts)
         items          (agents/sessions-items-list client (get session "id") {"order" "asc"})]
     {:status (get final "status")
      :turn   turn
      :output (agents/items-output-text items)})))

;; JVM-only manual entry point (not exercised by the automated suite, and
;; never run against the real network by this repo) — credentials come only
;; from OPENAI_API_KEY, never a literal in source. Creating a real
;; OpenAI-hosted sandbox costs real container time; run this deliberately,
;; e.g. `clojure -M -e "(require 'examples.openai.agents-sandbox-task) (examples.openai.agents-sandbox-task/-main)"`.
(defn -main [& _]
  (let [{:keys [status turn output]} (run-example (oai/client))]
    (println "status:" status "turn:" (get turn "status") (or (get turn "error") ""))
    (println output)))
