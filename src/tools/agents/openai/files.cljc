(ns tools.agents.openai.files
  "OpenAI Files API — port of openai-python's `client.files.*`
   (`resources/files.py`, `lib/_files.py`, `types/file_create_params.py`,
   `types/file_list_params.py`, `types/file_purpose.py`).

   Takes the `OpenAIClient` record built by `tools.agents.openai/client`;
   every call goes through `tools.agents.openai/request!`, so retries, error
   typing and ex-data are identical to `responses-create`. Messages are
   prefixed \"tools.agents.openai.files/<fn-name>: \"; `:type`s stay
   `:tools.agents.openai/...`.

   UPLOADS: `files-create` sends `POST /files` as multipart/form-data. A
   java.io.File or java.nio.file.Path is streamed from disk with a known
   Content-Length, never loaded into memory. The endpoint caps a file at
   512 MB. The resumable Uploads API (`/uploads`, up to 8 GB in 64 MB parts,
   `resources/uploads/uploads.py`) is NOT ported.

   DOWNLOADS: `files-content` returns the raw `byte[]`, byte-exact for any
   file (images, JSONL). openai-python's deprecated `retrieve_content`
   (`resources/files.py:331`, the same GET decoded as `str`) is not ported:
   `(String. (files-content c id) \"UTF-8\")` is its equivalent."
  (:require [tools.agents.http :as http]
            [tools.agents.openai :as oai]))

;; ---------------------------------------------------------------------------
;; Plumbing
;; ---------------------------------------------------------------------------

(def ^:private ns-prefix "tools.agents.openai.files/")

(defn- invalid! [fn-name msg]
  (throw (ex-info (str ns-prefix fn-name ": " msg)
                  {:type :tools.agents.openai/invalid-request})))

(defn- path-segment
  "URL-encode a caller-supplied file id before splicing it into a URL, so an
   id containing `/`, `?`, `#` or `%` cannot reroute the request."
  [id]
  (http/url-encode id))

(defn- file-path
  "\"/files/{file_id}\" plus `suffix`. An empty or nil id throws
   :tools.agents.openai/invalid-request before any I/O, as the SDK raises
   `ValueError(\"Expected a non-empty value for `file_id`\")`."
  [fn-name file-id suffix]
  (when (or (nil? file-id) (= "" (str file-id)))
    (invalid! fn-name (str "expected a non-empty file-id, got " (pr-str file-id))))
  (str "/files/" (path-segment file-id) suffix))

(defn- send! [client fn-name req]
  (oai/request! client (str ns-prefix fn-name) req))

(defn- field [m k]
  (let [s (get m k)]
    (if (some? s) s (get m (keyword k)))))

;; ---------------------------------------------------------------------------
;; Multipart body for POST /files
;; ---------------------------------------------------------------------------

(def purposes
  "The upload purposes openai-python's `FilePurpose` literal lists
   (`types/file_purpose.py`). Informational only: `files-create` sends any
   `purpose` verbatim, as the SDK does at runtime."
  #{"assistants" "batch" "fine-tune" "vision" "user_data" "evals"})

(defn- streamable? [x]
  (or (instance? java.io.File x) (instance? java.nio.file.Path x)))

(defn file-part
  "The multipart part for the `file` field. `file` is one of:

     java.io.File / java.nio.file.Path  streamed from disk; filename is the
                                        file's own name
     {:content c :filename f :content-type t}
                                        c is a byte[], File or Path;
                                        :filename is required for byte[];
                                        :content-type is optional

   The part's Content-Type is `:content-type`, else
   application/octet-stream (the API infers the file type from the
   filename). A bare byte[] or String is rejected with
   :tools.agents.openai/invalid-request: a byte[] has no filename, and a
   String is ambiguous between a path and content."
  ([file] (file-part "files-create" file))
  ([fn-name file]
   (let [{:keys [content filename content-type]}
         (cond
           (streamable? file) {:content file}
           (map? file)        file
           (bytes? file)      (invalid! fn-name "a byte[] file needs a filename — pass {:content bytes :filename \"name.ext\"}")
           (string? file)     (invalid! fn-name (str "a String file is ambiguous — pass a java.io.File or "
                                                     "java.nio.file.Path, or {:content bytes :filename ...}"))
           :else              (invalid! fn-name (str "unsupported file " (pr-str (type file)))))]
     (when-not (or (bytes? content) (streamable? content))
       (invalid! fn-name (str "unsupported file :content " (pr-str (type content))
                              " — expected byte[], java.io.File or java.nio.file.Path")))
     (when-not (or (seq filename) (streamable? content))
       (invalid! fn-name "file :filename is required for byte[] content"))
     (cond-> {:name "file" :content content :content-type (or content-type "application/octet-stream")}
       (seq filename) (assoc :filename filename)))))

(defn create-parts
  "The multipart parts `files-create` sends for `request`: every non-file
   field first, bracket-flattened as openai-python's
   `_serialize_multipartform` does (`\"expires_after\" {\"anchor\"
   \"created_at\" \"seconds\" 3600}` → parts `expires_after[anchor]` and
   `expires_after[seconds]`), with no filename and no Content-Type; then the
   `file` part (`file-part`). httpx also emits data fields before files.
   Keys may be strings or keywords."
  ([request] (create-parts "files-create" request))
  ([fn-name request]
   (let [file (field request "file")]
     (when (nil? file) (invalid! fn-name "request needs \"file\""))
     (conj (mapv (fn [[n v]] {:name n :content v})
                 (http/flatten-params (dissoc request "file" :file)))
           (file-part fn-name file)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn files-create
  "POST {base-url}/files (multipart/form-data) — the analogue of
   `client.files.create(file=, purpose=, expires_after=)`. `request`:

     \"file\"           required; java.io.File, java.nio.file.Path or
                      {:content byte[]|File|Path :filename s
                       :content-type s} — see `file-part`
     \"purpose\"        required; \"assistants\" | \"batch\" | \"fine-tune\" |
                      \"vision\" | \"user_data\" | \"evals\" (`purposes`),
                      sent verbatim, not validated
     \"expires_after\"  optional {\"anchor\" \"created_at\" \"seconds\" n},
                      n in 3600..2592000; sent as `expires_after[anchor]` and
                      `expires_after[seconds]`. Without it `batch` files
                      expire after 30 days and others persist.

   Any other key is sent as another bracket-flattened form field. Returns
   the `FileObject`: \"id\", \"object\" \"file\", \"bytes\", \"created_at\",
   \"expires_at\", \"filename\", \"purpose\", \"status\" (deprecated).

   Throws :tools.agents.openai/invalid-request before any I/O for a missing
   or unsupported \"file\"; otherwise ex-info with the same
   `:type`/`:status`/`:body` contract as `tools.agents.openai/request!`,
   message prefix \"tools.agents.openai.files/files-create: \"."
  [client request]
  (send! client "files-create"
         {:method :post :path "/files" :multipart (create-parts "files-create" request)}))

(defn files-retrieve
  "GET {base-url}/files/{file-id} — the analogue of
   `client.files.retrieve(file_id)`. Returns the `FileObject`. An unknown id
   throws :tools.agents.openai/not-found-error; an empty id throws
   :tools.agents.openai/invalid-request before any I/O."
  [client file-id]
  (send! client "files-retrieve" {:method :get :path (file-path "files-retrieve" file-id "")}))

(defn files-list
  "GET {base-url}/files — the analogue of `client.files.list(**params)`.
   `params`, if given, is a map of query parameters (`types/file_list_params.py`):
   \"purpose\" (any string), \"limit\" (1..10000, default 10000), \"order\"
   (\"asc\"|\"desc\", by created_at), \"after\" (cursor). nil values are
   dropped.

   Returns one page: {\"object\" \"list\" \"data\" [FileObject ...]
   \"first_id\" \"last_id\" \"has_more\"}. Page with \"after\" set to the
   previous page's \"last_id\" while \"has_more\" is true (openai-python's
   `SyncCursorPage`); keep \"order\" fixed across pages."
  ([client] (files-list client nil))
  ([client params]
   (send! client "files-list" {:method :get :path "/files" :query params})))

(defn files-delete
  "DELETE {base-url}/files/{file-id} — the analogue of
   `client.files.delete(file_id)`; also removes the file from every vector
   store. Returns {\"id\" ... \"object\" \"file\" \"deleted\" true}."
  [client file-id]
  (send! client "files-delete" {:method :delete :path (file-path "files-delete" file-id "")}))

(defn files-content
  "GET {base-url}/files/{file-id}/content — the analogue of
   `client.files.content(file_id)`, sent with `Accept: application/binary`
   as the SDK does. Returns the body as a raw `byte[]`, never decoded. Error
   bodies in ex-data are still Strings."
  [client file-id]
  (send! client "files-content"
         {:method  :get
          :path    (file-path "files-content" file-id "/content")
          :headers {"accept" "application/binary"}
          :as      :bytes}))

(def ^:private terminal-statuses #{"processed" "error" "deleted"})

(defn files-wait-for-processing
  "Poll `files-retrieve` until \"status\" is \"processed\", \"error\" or
   \"deleted\" — the analogue of `client.files.wait_for_processing(id)`
   (`lib/_files.py`). Returns the last `FileObject`, whatever its terminal
   status.

   opts:
     :poll-interval-ms  sleep between polls, default 5000
     :max-wait-ms       give-up threshold, default 1800000 (30 min)
     :sleep-fn          (fn [ms]), default Thread/sleep
     :now-fn            (fn [] epoch-ms), default System/currentTimeMillis

   As in the SDK the elapsed time is checked after each re-retrieve, so a
   non-terminal file is retrieved at least twice before giving up with
   :tools.agents.openai/wait-timeout (the SDK's RuntimeError;
   not an HTTP timeout, which is api-connection-error). Note the
   \"status\" field is deprecated in the API."
  ([client file-id] (files-wait-for-processing client file-id nil))
  ([client file-id {:keys [poll-interval-ms max-wait-ms sleep-fn now-fn]
                    :or   {poll-interval-ms 5000
                           max-wait-ms      (* 30 60 1000)
                           sleep-fn         (fn [ms] (Thread/sleep (long ms)))
                           now-fn           (fn [] (System/currentTimeMillis))}}]
   (let [start (now-fn)]
     (loop [file (files-retrieve client file-id)]
       (if (contains? terminal-statuses (get file "status"))
         file
         (do (sleep-fn poll-interval-ms)
             (let [file (files-retrieve client file-id)]
               (if (> (- (now-fn) start) max-wait-ms)
                 (throw (ex-info (str ns-prefix "files-wait-for-processing: giving up on waiting for file "
                                      file-id " to finish processing after " max-wait-ms " ms")
                                 {:type :tools.agents.openai/wait-timeout :file file}))
                 (recur file)))))))))
