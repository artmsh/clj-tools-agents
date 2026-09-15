(ns tools.agents.anthropic.batches
  "Message Batches API — the equivalent of anthropic-sdk-python's
   `client.messages.batches.*` (src/anthropic/resources/messages/batches.py).

     batches-create    POST   /v1/messages/batches
     batches-retrieve  GET    /v1/messages/batches/{id}
     batches-list      GET    /v1/messages/batches?before_id&after_id&limit
     batches-cancel    POST   /v1/messages/batches/{id}/cancel
     batches-delete    DELETE /v1/messages/batches/{id}
     batches-results   GET    /v1/messages/batches/{id}/results  (JSONL)

   Every call goes through tools.agents.anthropic/request!, so it shares
   messages-create's client, credentials, anthropic-version header, :betas,
   retry policy (408/409/429/5xx + connection failures) and error typing
   (:tools.agents.anthropic.error/*). Requests and responses are plain maps
   with string keys, passed through verbatim.

   Portable clojure.core only: runs unmodified on JVM Clojure and Babashka."
  (:require [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.http :as http]))

(def ^:private collection-path "/v1/messages/batches")

(defn- caller [fn-name] (str "tools.agents.anthropic.batches/" fn-name))

(defn- batch-path
  "Path for one batch, id URL-encoded. An empty or non-string id throws
   :tools.agents.anthropic.error/invalid-argument BEFORE any request — as the
   SDK's `Expected a non-empty value for message_batch_id` ValueError, and
   because \"\" would otherwise turn retrieve into a list and delete into a
   DELETE on the collection."
  [fn-name id suffix]
  (when-not (and (string? id) (seq id))
    (throw (ex-info (str (caller fn-name) ": expected a non-empty message batch id, got: " (pr-str id))
                    {:type :tools.agents.anthropic.error/invalid-argument :status nil :body nil})))
  ;; URLEncoder is form encoding (space -> +); a path segment needs %20.
  (str collection-path "/" (str/replace (http/url-encode id) "+" "%20") suffix))

(defn batches-create
  "POST /v1/messages/batches. request is the full body map, verbatim:
     {\"requests\" [{\"custom_id\" \"a\" \"params\" {<messages-create request>}} ...]}
   Returns the MessageBatch map (\"id\", \"processing_status\",
   \"request_counts\", \"results_url\", ...)."
  [client request]
  (a/request! client (caller "batches-create") {:method :post :path collection-path :body request}))

(defn batches-retrieve
  "GET /v1/messages/batches/{id}. Idempotent; poll it until
   \"processing_status\" is \"ended\". Returns the MessageBatch map."
  [client message-batch-id]
  (a/request! client (caller "batches-retrieve")
              {:method :get :path (batch-path "batches-retrieve" message-batch-id "")}))

(defn batches-list
  "GET /v1/messages/batches — one page, most recently created first.
   params (optional) become the query string: \"limit\" (1..1000, default 20),
   \"after_id\", \"before_id\". Returns the page map verbatim:
     {\"data\" [MessageBatch ...] \"has_more\" bool \"first_id\" s \"last_id\" s}
   See next-page-params / batches-list-all for paging."
  ([client] (batches-list client nil))
  ([client params]
   (a/request! client (caller "batches-list") {:method :get :path collection-path :query params})))

(defn next-page-params
  "params for the page after page, or nil when there is none — the SDK's
   SyncPage.has_next_page/next_page_info: \"has_more\" false ends paging;
   when params carried \"before_id\" paging continues backward with
   {\"before_id\" first_id}, otherwise forward with {\"after_id\" last_id};
   a missing cursor id ends paging. Other params (e.g. \"limit\") carry over."
  [params page]
  (let [before? (seq (str (or (get params "before_id") (get params :before_id))))
        base    (dissoc (or params {}) "before_id" :before_id "after_id" :after_id)]
    (when-not (false? (get page "has_more"))
      (if before?
        (when-let [id (get page "first_id")] (assoc base "before_id" id))
        (when-let [id (get page "last_id")] (assoc base "after_id" id))))))

(defn batches-list-all
  "Lazy seq of every MessageBatch across pages, fetching the next page only
   when the seq is realized that far — the SDK's auto-paging iteration over
   `client.messages.batches.list(...)`. An empty page also ends the seq."
  ([client] (batches-list-all client nil))
  ([client params]
   (lazy-seq
    (let [page  (batches-list client params)
          data  (get page "data")
          nextp (when (seq data) (next-page-params params page))]
      (concat data (when nextp (batches-list-all client nextp)))))))

(defn batches-cancel
  "POST /v1/messages/batches/{id}/cancel (no body). The batch moves to
   \"canceling\"; it reaches \"ended\" once in-flight requests finish.
   Returns the MessageBatch map."
  [client message-batch-id]
  (a/request! client (caller "batches-cancel")
              {:method :post :path (batch-path "batches-cancel" message-batch-id "/cancel")}))

(defn batches-delete
  "DELETE /v1/messages/batches/{id}. Only an ended batch can be deleted —
   cancel an in-progress one first. Returns
   {\"id\" id \"type\" \"message_batch_deleted\"}."
  [client message-batch-id]
  (a/request! client (caller "batches-delete")
              {:method :delete :path (batch-path "batches-delete" message-batch-id "")}))

(defn batches-results
  "GET /v1/messages/batches/{id}/results — a LAZY seq of decoded JSONL lines,
   one MessageBatchIndividualResponse map per request, in no guaranteed order:
     {\"custom_id\" s \"result\" {\"type\" \"succeeded\" \"message\" {...}}}
     {\"custom_id\" s \"result\" {\"type\" \"errored\" \"error\" {\"type\" \"error\" \"error\" {...}}}}
     {\"custom_id\" s \"result\" {\"type\" \"canceled\"}}  / \"expired\"
   An errored line is data, not an exception. A malformed line throws
   :tools.agents.anthropic.error/json-parse (with :line) when realized.
   Response headers ride as metadata, so tools.agents.anthropic/request-id
   works on the returned seq.

   The body is read fully into a String (inside the retry loop, so nothing
   leaks on a retried or failed attempt) and decoded lazily from it. HTTP
   errors (e.g. 404 unknown batch, or a batch without results yet) are typed
   as for every other call. Sends `accept: application/binary`, as the SDK."
  [client message-batch-id]
  (let [resp (a/request! client (caller "batches-results")
                         {:method  :get
                          :path    (batch-path "batches-results" message-batch-id "/results")
                          :headers {"accept" "application/binary"}
                          :as      :response})]
    (with-meta (a/read-jsonl (:body resp)) {:tools.agents.anthropic/headers (:headers resp)})))
