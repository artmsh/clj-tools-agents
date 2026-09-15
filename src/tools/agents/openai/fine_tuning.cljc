(ns tools.agents.openai.fine-tuning
  "OpenAI fine-tuning jobs and checkpoint permissions — port of
   openai-python's `client.fine_tuning.jobs.*`,
   `client.fine_tuning.jobs.checkpoints.*` and
   `client.fine_tuning.checkpoints.permissions.*`
   (`resources/fine_tuning/jobs/jobs.py`, `jobs/checkpoints.py`,
   `checkpoints/permissions.py`).

     jobs-create                     POST   /fine_tuning/jobs
     jobs-retrieve                   GET    /fine_tuning/jobs/{job_id}
     jobs-list                       GET    /fine_tuning/jobs?after&limit&metadata[k]
     jobs-cancel                     POST   /fine_tuning/jobs/{job_id}/cancel
     jobs-pause                      POST   /fine_tuning/jobs/{job_id}/pause
     jobs-resume                     POST   /fine_tuning/jobs/{job_id}/resume
     jobs-list-events                GET    /fine_tuning/jobs/{job_id}/events?after&limit
     jobs-checkpoints-list           GET    /fine_tuning/jobs/{job_id}/checkpoints?after&limit
     checkpoints-permissions-create  POST   /fine_tuning/checkpoints/{checkpoint}/permissions
     checkpoints-permissions-list    GET    /fine_tuning/checkpoints/{checkpoint}/permissions
     checkpoints-permissions-delete  DELETE /fine_tuning/checkpoints/{checkpoint}/permissions/{permission_id}

   Takes the `OpenAIClient` record built by `tools.agents.openai/client`;
   every call goes through `tools.agents.openai/request!`, so retries, error
   typing and ex-data are identical to `responses-create`. Messages are
   prefixed \"tools.agents.openai.fine-tuning/<fn-name>: \"; `:type`s stay
   `:tools.agents.openai/...`.

   ADMIN KEY. The checkpoint-permission endpoints require an organization
   admin API key. This library has no separate admin credential (the SDK's
   `admin_api_key` / `OPENAI_ADMIN_KEY`): build a client with the admin key
   as `:api-key`, e.g. `(oai/client {:api-key admin-key})`. A project key
   gets a 401/403 typed as usual.

   Not ported: `client.fine_tuning.alpha.graders.run/validate` (alpha) and
   the deprecated `permissions.retrieve` (the same GET as
   `checkpoints-permissions-list`).

   Ids are percent-encoded as the SDK's `path_template` does
   (`tools.agents.http/encode-path-segment`), so a checkpoint name such as
   `ft:gpt-4o-mini-2024-07-18:org:suffix:ckpt-step-100` keeps its colons."
  (:require [tools.agents.http :as http]
            [tools.agents.openai :as oai]))

;; ---------------------------------------------------------------------------
;; Plumbing
;; ---------------------------------------------------------------------------

(def ^:private ns-prefix "tools.agents.openai.fine-tuning/")

(defn- segment
  "`id` as an encoded path segment. An empty or nil id throws
   :tools.agents.openai/invalid-request before any I/O, as the SDK raises
   `ValueError(\"Expected a non-empty value for `<arg>`\")`."
  [fn-name arg id]
  (when (or (nil? id) (= "" (str id)))
    (throw (ex-info (str ns-prefix fn-name ": expected a non-empty " arg ", got " (pr-str id))
                    {:type :tools.agents.openai/invalid-request})))
  (http/encode-path-segment id))

(defn- job-path [fn-name job-id suffix]
  (str "/fine_tuning/jobs/" (segment fn-name "fine-tuning-job-id" job-id) suffix))

(defn- permissions-path [fn-name checkpoint suffix]
  (str "/fine_tuning/checkpoints/" (segment fn-name "fine-tuned-model-checkpoint" checkpoint)
       "/permissions" suffix))

(defn- send! [client fn-name req]
  (oai/request! client (str ns-prefix fn-name) req))

;; ---------------------------------------------------------------------------
;; Jobs
;; ---------------------------------------------------------------------------

(defn jobs-create
  "POST {base-url}/fine_tuning/jobs (JSON) — the analogue of
   `client.fine_tuning.jobs.create(...)`. `request`, sent verbatim as the
   JSON body (`types/fine_tuning/job_create_params.py`):

     \"model\"            required; e.g. \"gpt-4o-mini-2024-07-18\"
     \"training_file\"    required; a file id uploaded with purpose \"fine-tune\"
     \"validation_file\"  optional file id
     \"method\"           optional {\"type\" \"supervised\"|\"dpo\"|\"reinforcement\" ...}
     \"hyperparameters\"  deprecated in favour of \"method\"
     \"integrations\"     optional, e.g. [{\"type\" \"wandb\" \"wandb\" {...}}]
     \"metadata\" \"seed\" \"suffix\"

   Nested values are JSON objects, not bracket-flattened. Returns the
   `FineTuningJob` (\"id\" \"ftjob-...\", \"status\" \"validating_files\"
   \"queued\" \"running\" \"succeeded\" \"failed\" \"cancelled\" ...,
   \"fine_tuned_model\", \"result_files\", ...)."
  [client request]
  (send! client "jobs-create" {:method :post :path "/fine_tuning/jobs" :body request}))

(defn jobs-retrieve
  "GET {base-url}/fine_tuning/jobs/{job-id} — the analogue of
   `client.fine_tuning.jobs.retrieve(fine_tuning_job_id)`. Returns the
   `FineTuningJob`; an unknown id throws :tools.agents.openai/not-found-error,
   an empty id :tools.agents.openai/invalid-request before any I/O."
  [client job-id]
  (send! client "jobs-retrieve" {:method :get :path (job-path "jobs-retrieve" job-id "")}))

(defn jobs-list
  "GET {base-url}/fine_tuning/jobs — the analogue of
   `client.fine_tuning.jobs.list(after=, limit=, metadata=)`
   (`types/fine_tuning/job_list_params.py`). `params`: \"after\" (cursor),
   \"limit\", \"metadata\" {k v} (sent bracket-encoded as `metadata[k]=v`, a
   filter). nil values are dropped, so the SDK's `metadata=None` (\"jobs
   with no metadata\", sent as `metadata=null`) is spelled
   {\"metadata\" \"null\"}.

   Returns one page: {\"object\" \"list\" \"data\" [FineTuningJob ...]
   \"has_more\" ...}; page with \"after\" = the last item's \"id\" while
   \"has_more\" is true (`SyncCursorPage`)."
  ([client] (jobs-list client nil))
  ([client params]
   (send! client "jobs-list" {:method :get :path "/fine_tuning/jobs" :query params})))

(defn jobs-cancel
  "POST {base-url}/fine_tuning/jobs/{job-id}/cancel — the analogue of
   `client.fine_tuning.jobs.cancel(id)`. Returns the `FineTuningJob`."
  [client job-id]
  (send! client "jobs-cancel" {:method :post :path (job-path "jobs-cancel" job-id "/cancel")}))

(defn jobs-pause
  "POST {base-url}/fine_tuning/jobs/{job-id}/pause — the analogue of
   `client.fine_tuning.jobs.pause(id)` (`jobs.py`). Returns the
   `FineTuningJob`."
  [client job-id]
  (send! client "jobs-pause" {:method :post :path (job-path "jobs-pause" job-id "/pause")}))

(defn jobs-resume
  "POST {base-url}/fine_tuning/jobs/{job-id}/resume — the analogue of
   `client.fine_tuning.jobs.resume(id)` (`jobs.py`). Returns the
   `FineTuningJob`."
  [client job-id]
  (send! client "jobs-resume" {:method :post :path (job-path "jobs-resume" job-id "/resume")}))

(defn jobs-list-events
  "GET {base-url}/fine_tuning/jobs/{job-id}/events — the analogue of
   `client.fine_tuning.jobs.list_events(id, after=, limit=)`. Returns one
   cursor page of `FineTuningJobEvent` (\"id\" \"created_at\" \"level\"
   \"message\" \"type\" \"data\")."
  ([client job-id] (jobs-list-events client job-id nil))
  ([client job-id params]
   (send! client "jobs-list-events"
          {:method :get :path (job-path "jobs-list-events" job-id "/events") :query params})))

(defn jobs-checkpoints-list
  "GET {base-url}/fine_tuning/jobs/{job-id}/checkpoints — the analogue of
   `client.fine_tuning.jobs.checkpoints.list(id, after=, limit=)`
   (`jobs/checkpoints.py`). Returns one cursor page of
   `FineTuningJobCheckpoint` (\"id\" \"fine_tuned_model_checkpoint\"
   \"step_number\" \"metrics\" ...)."
  ([client job-id] (jobs-checkpoints-list client job-id nil))
  ([client job-id params]
   (send! client "jobs-checkpoints-list"
          {:method :get :path (job-path "jobs-checkpoints-list" job-id "/checkpoints") :query params})))

;; ---------------------------------------------------------------------------
;; Checkpoint permissions (admin API key)
;; ---------------------------------------------------------------------------

(defn checkpoints-permissions-create
  "POST {base-url}/fine_tuning/checkpoints/{checkpoint}/permissions — the
   analogue of `client.fine_tuning.checkpoints.permissions.create(
   fine_tuned_model_checkpoint, project_ids=)`. `request` is
   {\"project_ids\" [\"proj_...\" ...]}, sent verbatim. Requires an admin
   API key as the client's `:api-key`. Returns a page
   {\"object\" \"list\" \"data\" [{\"id\" \"cp_...\" \"project_id\" ...}]}
   (the SDK types it as `SyncPage`)."
  [client checkpoint request]
  (send! client "checkpoints-permissions-create"
         {:method :post :path (permissions-path "checkpoints-permissions-create" checkpoint "") :body request}))

(defn checkpoints-permissions-list
  "GET {base-url}/fine_tuning/checkpoints/{checkpoint}/permissions — the
   analogue of `client.fine_tuning.checkpoints.permissions.list(
   fine_tuned_model_checkpoint, after=, limit=, order=, project_id=)`, and of
   the deprecated `permissions.retrieve`, which sends the identical request.
   `params`: \"after\", \"limit\", \"order\" (\"ascending\"|\"descending\"),
   \"project_id\". Requires an admin API key. Returns one page
   {\"data\" [...] \"has_more\" \"first_id\" \"last_id\"}."
  ([client checkpoint] (checkpoints-permissions-list client checkpoint nil))
  ([client checkpoint params]
   (send! client "checkpoints-permissions-list"
          {:method :get :path (permissions-path "checkpoints-permissions-list" checkpoint "") :query params})))

(defn checkpoints-permissions-delete
  "DELETE {base-url}/fine_tuning/checkpoints/{checkpoint}/permissions/{permission-id}
   — the analogue of `client.fine_tuning.checkpoints.permissions.delete(
   permission_id, fine_tuned_model_checkpoint=)`; arguments follow the URL
   order. Requires an admin API key. Returns {\"id\" ... \"object\"
   \"checkpoint.permission\" \"deleted\" true}. The checkpoint is validated
   before the permission id, as in the SDK."
  [client checkpoint permission-id]
  (let [fn-name "checkpoints-permissions-delete"
        base    (permissions-path fn-name checkpoint "/")]
    (send! client fn-name
           {:method :delete :path (str base (segment fn-name "permission-id" permission-id))})))
