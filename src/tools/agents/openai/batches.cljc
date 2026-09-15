(ns tools.agents.openai.batches
  "OpenAI Batch API — port of openai-python's `client.batches.*`
   (`resources/batches.py`, `types/batch_create_params.py`,
   `types/batch_list_params.py`, `types/batch.py`).

     batches-create    POST /batches
     batches-retrieve  GET  /batches/{batch_id}
     batches-list      GET  /batches?after&limit
     batches-cancel    POST /batches/{batch_id}/cancel

   Takes the `OpenAIClient` record built by `tools.agents.openai/client`;
   every call goes through `tools.agents.openai/request!`, so retries, error
   typing and ex-data are identical to `responses-create`. Messages are
   prefixed \"tools.agents.openai.batches/<fn-name>: \"; `:type`s stay
   `:tools.agents.openai/...`.

   HELPERS (not in the SDK, which has no batch wait or results helper):
   `batch-input-jsonl` builds the input file's JSONL; `batches-results`
   downloads the output (and optionally the error) file via
   `tools.agents.openai.files/files-content` and returns the lines keyed by
   \"custom_id\" — output lines are NOT in input order.

   End to end:

     (let [in    (batch-input-jsonl \"/v1/responses\"
                   [{\"custom_id\" \"a\" \"body\" {\"model\" \"gpt-5\" \"input\" \"hi\"}}])
           file  (files/files-create c {\"file\" {:content (.getBytes in \"UTF-8\")
                                                :filename \"batch.jsonl\"}
                                        \"purpose\" \"batch\"})
           batch (batches-create c {\"input_file_id\" (get file \"id\")
                                    \"endpoint\" \"/v1/responses\"
                                    \"completion_window\" \"24h\"})]
       ;; poll batches-retrieve until \"status\" is \"completed\", then
       (batches-results c (batches-retrieve c (get batch \"id\"))))"
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.openai :as oai]
            [tools.agents.openai.files :as files]))

;; ---------------------------------------------------------------------------
;; Plumbing
;; ---------------------------------------------------------------------------

(def ^:private ns-prefix "tools.agents.openai.batches/")

(defn- invalid! [fn-name msg]
  (throw (ex-info (str ns-prefix fn-name ": " msg)
                  {:type :tools.agents.openai/invalid-request})))

(defn- batch-path
  "\"/batches/{batch_id}\" plus `suffix`, the id encoded as openai-python's
   `path_template` does. An empty or nil id throws
   :tools.agents.openai/invalid-request before any I/O, as the SDK raises
   `ValueError(\"Expected a non-empty value for `batch_id`\")`."
  [fn-name batch-id suffix]
  (when (or (nil? batch-id) (= "" (str batch-id)))
    (invalid! fn-name (str "expected a non-empty batch-id, got " (pr-str batch-id))))
  (str "/batches/" (http/encode-path-segment batch-id) suffix))

(defn- send! [client fn-name req]
  (oai/request! client (str ns-prefix fn-name) req))

(defn- field [m k]
  (let [s (get m k)]
    (if (some? s) s (get m (keyword k)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(def endpoints
  "The `endpoint` values openai-python's `BatchCreateParams` literal lists
   (`types/batch_create_params.py`). Informational only: `batches-create`
   sends any `endpoint` verbatim, as the SDK does at runtime."
  #{"/v1/responses" "/v1/chat/completions" "/v1/embeddings" "/v1/completions"
    "/v1/moderations" "/v1/images/generations" "/v1/images/edits" "/v1/videos"})

(defn batches-create
  "POST {base-url}/batches (JSON) — the analogue of
   `client.batches.create(completion_window=, endpoint=, input_file_id=,
   metadata=, output_expires_after=)`. `request`, sent verbatim as the JSON
   body:

     \"input_file_id\"         required; a file uploaded with purpose
                             \"batch\" (JSONL, up to 50,000 requests and
                             200 MB; see `batch-input-jsonl`)
     \"endpoint\"              required; one of `endpoints`, not validated
     \"completion_window\"     required; only \"24h\"
     \"metadata\"              optional; up to 16 string pairs
     \"output_expires_after\"  optional {\"anchor\" \"created_at\"
                             \"seconds\" n}, n in 3600..2592000; a nested
                             JSON object (not bracket-flattened: this is a
                             JSON body, not multipart)

   Returns the `Batch`: \"id\", \"object\" \"batch\", \"status\"
   (\"validating\" \"failed\" \"in_progress\" \"finalizing\" \"completed\"
   \"expired\" \"cancelling\" \"cancelled\"), \"input_file_id\",
   \"output_file_id\", \"error_file_id\", \"request_counts\", timestamps."
  [client request]
  (send! client "batches-create" {:method :post :path "/batches" :body request}))

(defn batches-retrieve
  "GET {base-url}/batches/{batch-id} — the analogue of
   `client.batches.retrieve(batch_id)`. Returns the `Batch`. An unknown id
   throws :tools.agents.openai/not-found-error; an empty id throws
   :tools.agents.openai/invalid-request before any I/O."
  [client batch-id]
  (send! client "batches-retrieve" {:method :get :path (batch-path "batches-retrieve" batch-id "")}))

(defn batches-list
  "GET {base-url}/batches — the analogue of `client.batches.list(after=,
   limit=)` (`types/batch_list_params.py`). `params`: \"after\" (cursor),
   \"limit\" (1..100, default 20); nil values are dropped. There is no
   \"order\".

   Returns one page: {\"object\" \"list\" \"data\" [Batch ...] \"first_id\"
   \"last_id\" \"has_more\"}. Page with \"after\" set to the previous page's
   \"last_id\" while \"has_more\" is true (openai-python's `SyncCursorPage`)."
  ([client] (batches-list client nil))
  ([client params]
   (send! client "batches-list" {:method :get :path "/batches" :query params})))

(defn batches-cancel
  "POST {base-url}/batches/{batch-id}/cancel — the analogue of
   `client.batches.cancel(batch_id)`. Returns the `Batch`, typically with
   \"status\" \"cancelling\"; it becomes \"cancelled\" within about 10
   minutes, and any completed partial results stay downloadable."
  [client batch-id]
  (send! client "batches-cancel" {:method :post :path (batch-path "batches-cancel" batch-id "/cancel")}))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn batch-input-jsonl
  "The JSONL String for a batch input file: one line per request map,
   `\\n`-separated, with a trailing newline. Each request is
   {\"custom_id\" s \"method\" \"POST\" \"url\" endpoint \"body\" {...}}
   and is encoded verbatim by `tools.agents.openai/write-json` (keys may be
   strings or keywords).

   The 2-arity fills \"method\" \"POST\" and \"url\" `url` (e.g.
   \"/v1/responses\", which must equal the batch's \"endpoint\") into every
   request that lacks them.

   Throws :tools.agents.openai/invalid-request for a request without a
   non-empty string \"custom_id\" or with a duplicate one: the API rejects
   duplicates, and `batches-results` keys results by it."
  ([requests] (batch-input-jsonl nil requests))
  ([url requests]
   (let [fn-name "batch-input-jsonl"]
     (loop [[r & more] (seq requests) seen #{} lines []]
       (if (nil? r)
         (str (str/join "\n" lines) (when (seq lines) "\n"))
         (let [cid (field r "custom_id")
               r   (cond-> r
                     (and url (nil? (field r "method"))) (assoc "method" "POST")
                     (and url (nil? (field r "url")))    (assoc "url" url))]
           (when-not (and (string? cid) (seq cid))
             (invalid! fn-name (str "request " (count lines) " needs a non-empty string \"custom_id\", got "
                                    (pr-str cid))))
           (when (contains? seen cid)
             (invalid! fn-name (str "duplicate \"custom_id\" " (pr-str cid))))
           (recur more (conj seen cid) (conj lines (oai/write-json r)))))))))

(defn- file-lines
  "Download `file-id` and decode it as JSONL, fully realized."
  [client file-id]
  (vec ((:read-jsonl (oai/client-codec client))
        (String. ^bytes (files/files-content client file-id) "UTF-8"))))

(defn- index-by-custom-id [fn-name acc lines]
  (reduce (fn [acc line]
            (let [cid (and (map? line) (get line "custom_id"))]
              (when-not (string? cid)
                (throw (ex-info (str ns-prefix fn-name ": result line without a string \"custom_id\": "
                                     (pr-str line))
                                {:type :tools.agents.openai/invalid-response :line line})))
              (when (contains? acc cid)
                (throw (ex-info (str ns-prefix fn-name ": duplicate \"custom_id\" " (pr-str cid) " in results")
                                {:type :tools.agents.openai/invalid-response :line line})))
              (assoc acc cid line)))
          acc
          lines))

(defn batches-results
  "Download a batch's results and key them by \"custom_id\" — output lines
   come back in NO guaranteed order, so never match them to inputs by
   position. `batch` is a `Batch` map (e.g. from `batches-retrieve`) or a
   batch id, which is retrieved first. Returns {custom_id line}, each line
   the decoded map:

     output file  {\"id\" \"batch_req_...\" \"custom_id\" s
                   \"response\" {\"status_code\" 200 \"request_id\" s
                               \"body\" {...}}
                   \"error\" nil}
     error file   {\"id\" ... \"custom_id\" s \"response\" {\"status_code\"
                   4xx/5xx ...} or nil, \"error\" {\"code\" s \"message\" s}
                   or nil}

   A request that failed is DATA in the error file, not an exception. The
   two files partition the input by custom_id.

   opts:
     :errors?  also read \"error_file_id\" and merge its lines into the same
               map (default false: output file only)

   Throws :tools.agents.openai/invalid-request before downloading when the
   batch has no \"output_file_id\" (without :errors?), or neither file id
   (with :errors?) — e.g. the batch is not finished or every request
   failed. A malformed line throws :tools.agents.openai/json-parse-error
   (`:line` in ex-data, message prefix `tools.agents.openai/read-jsonl`); a
   line without a string \"custom_id\" or a repeated custom_id throws
   :tools.agents.openai/invalid-response. Download errors are typed as for
   `tools.agents.openai.files/files-content`."
  ([client batch] (batches-results client batch nil))
  ([client batch {:keys [errors?]}]
   (let [fn-name "batches-results"
         batch   (if (map? batch)
                   batch
                   (do (batch-path fn-name batch "")
                       (batches-retrieve client batch)))
         out-id  (get batch "output_file_id")
         err-id  (get batch "error_file_id")
         ids     (if errors? (filterv seq [out-id err-id]) (filterv seq [out-id]))]
     (when (empty? ids)
       (invalid! fn-name (str "batch " (pr-str (get batch "id")) " (status " (pr-str (get batch "status")) ") has no "
                              (if errors? "\"output_file_id\" or \"error_file_id\"" "\"output_file_id\""))))
     (reduce (fn [acc file-id] (index-by-custom-id fn-name acc (file-lines client file-id)))
             {}
             ids))))
