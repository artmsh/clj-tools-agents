(ns tools.agents.openai.images
  "OpenAI Images API — port of openai-python's `client.images.*`
   (`resources/images.py`, `types/image_generate_params.py`,
   `types/image_edit_params.py`, `types/image_create_variation_params.py`,
   `types/images_response.py`).

   Takes the `OpenAIClient` record built by `tools.agents.openai/client`;
   every call goes through `tools.agents.openai/request!`, so retries, error
   typing and ex-data are identical to `responses-create`. Messages are
   prefixed \"tools.agents.openai.images/<fn-name>: \"; `:type`s stay
   `:tools.agents.openai/...`.

   RESPONSES: every call returns the `ImagesResponse` map: \"created\",
   \"data\" [Image ...], and for GPT image models \"background\",
   \"output_format\", \"quality\", \"size\", \"usage\". Each Image carries
   \"b64_json\" (always for GPT image models; for dall-e models when
   \"response_format\" is \"b64_json\") or \"url\" (dall-e models' default,
   valid for 60 minutes), plus \"revised_prompt\" (dall-e-3). `image-bytes`
   decodes a \"b64_json\" item; a \"url\" item must be fetched by the caller.

   STREAMING: `images-generate` and `images-edit` accept `stream` in the SDK
   (SSE `ImageGenStreamEvent` / `ImageEditStreamEvent`). Not implemented here:
   `\"stream\" true` throws :tools.agents.openai/streaming-unsupported before
   any I/O. `partial_images` is only meaningful with streaming."
  (:require [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.openai :as oai]
            [tools.agents.openai.files :as files]))

;; ---------------------------------------------------------------------------
;; Plumbing
;; ---------------------------------------------------------------------------

(def ^:private ns-prefix "tools.agents.openai.images/")

(defn- invalid! [fn-name msg]
  (throw (ex-info (str ns-prefix fn-name ": " msg)
                  {:type :tools.agents.openai/invalid-request})))

(defn- send! [client fn-name req]
  (oai/request! client (str ns-prefix fn-name) req))

(defn- field [m k]
  (let [v (get m k)]
    (if (some? v) v (get m (keyword k)))))

(defn- require-field! [fn-name request k]
  (when-not (map? request)
    (invalid! fn-name (str "request must be a map, got " (pr-str (type request)))))
  (when (nil? (field request k))
    (invalid! fn-name (str "request needs \"" k "\""))))

(defn- reject-stream!
  "Refuse `stream` before building parts or touching the network. `request!`
   refuses a JSON `true` and a multipart \"true\" part too; this also names
   the Images API in the message."
  [fn-name request]
  (when (contains? #{true "true"} (field request "stream"))
    (throw (ex-info (str ns-prefix fn-name ": \"stream\" true is not supported — "
                         "image streaming (SSE) is not implemented by this client")
                    {:type :tools.agents.openai/streaming-unsupported}))))

;; ---------------------------------------------------------------------------
;; Multipart parts
;; ---------------------------------------------------------------------------

(def ^:private mime-by-extension
  {"png" "image/png" "jpg" "image/jpeg" "jpeg" "image/jpeg" "webp" "image/webp" "gif" "image/gif"})

(defn- guess-content-type
  "httpx guesses a file part's Content-Type from its filename
   (`mimetypes.guess_type`); edits are reported to fail with \"unsupported
   mimetype ('application/octet-stream')\" otherwise (unverified live), unlike
   `files-create`'s octet-stream default. Same table for the formats the
   endpoints accept; anything else is application/octet-stream."
  [filename]
  (let [ext (some-> filename (str/split #"\.") next last str/lower-case)]
    (get mime-by-extension ext "application/octet-stream")))

(defn- content-name [content]
  (cond
    (instance? java.io.File content)       (.getName ^java.io.File content)
    (instance? java.nio.file.Path content) (str (.getFileName ^java.nio.file.Path content))
    :else                                  nil))

(defn image-part
  "One multipart file part named `part-name` (\"image\", \"image[]\" or
   \"mask\"). `file` is what `tools.agents.openai.files/file-part` accepts:
   java.io.File / java.nio.file.Path (streamed from disk) or
   {:content byte[]|File|Path :filename s :content-type s}. Without an
   explicit :content-type it is guessed from the filename extension (png,
   jpg/jpeg, webp, gif), as httpx does. Rejections are
   :tools.agents.openai/invalid-request, with `file-part`'s wording."
  [fn-name part-name file]
  (let [p (try (files/file-part fn-name file)
               ;; file-part prefixes its own namespace; re-prefix with ours.
               (catch clojure.lang.ExceptionInfo e
                 (if (= :tools.agents.openai/invalid-request (:type (ex-data e)))
                   (invalid! fn-name (str part-name ": " (second (str/split (ex-message e) #": " 2))))
                   (throw e))))]
    (cond-> (assoc p :name part-name)
      (not (and (map? file) (:content-type file)))
      (assoc :content-type (guess-content-type (or (:filename p) (content-name (:content p))))))))

(defn- image-parts
  "openai-python `extract_files(paths=[[\"image\"], [\"image\", \"<array>\"]])`
   with array_format brackets (`_utils/_utils.py` `_extract_items`): a single
   file is one `image` part; a sequential value is one `image[]` part per
   entry, even when it holds one file."
  [fn-name image]
  (if (sequential? image)
    (do (when (empty? image) (invalid! fn-name "\"image\" is an empty sequence"))
        (mapv #(image-part fn-name "image[]" %) image))
    [(image-part fn-name "image" image)]))

(defn- scalar-parts
  "Every non-file field, bracket-flattened as `_serialize_multipartform`
   does; nil and \"\" dropped, booleans \"true\"/\"false\"."
  [request file-keys]
  (mapv (fn [[n v]] {:name n :content v})
        (http/flatten-params (apply dissoc request (mapcat (juxt identity keyword) file-keys)))))

(defn edit-parts
  "The multipart parts `images-edit` sends for `request`, in httpx's order:
   data fields first (`scalar-parts`), then the image part(s), then `mask`.
   Throws :tools.agents.openai/invalid-request for a missing \"image\" or
   \"prompt\", an empty image sequence, or an unsupported file value."
  ([request] (edit-parts "images-edit" request))
  ([fn-name request]
   (require-field! fn-name request "image")
   (require-field! fn-name request "prompt")
   (let [mask (field request "mask")]
     (cond-> (into (scalar-parts request ["image" "mask"])
                   (image-parts fn-name (field request "image")))
       (some? mask) (conj (image-part fn-name "mask" mask))))))

(defn variation-parts
  "The multipart parts `images-create-variation` sends: data fields, then a
   single `image` part (`extract_files(paths=[[\"image\"]])`). A sequence of
   images is rejected with :tools.agents.openai/invalid-request."
  ([request] (variation-parts "images-create-variation" request))
  ([fn-name request]
   (require-field! fn-name request "image")
   (let [image (field request "image")]
     (when (sequential? image)
       (invalid! fn-name "\"image\" must be a single file — variations take one image"))
     (conj (scalar-parts request ["image"]) (image-part fn-name "image" image)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn images-generate
  "POST {base-url}/images/generations (JSON) — the analogue of
   `client.images.generate(...)`. `request` is sent verbatim:

     \"prompt\"              required
     \"model\"               \"gpt-image-2\", \"gpt-image-1\", \"dall-e-3\", ...
     \"n\" \"size\" \"quality\" \"background\" \"moderation\"
     \"output_format\" \"output_compression\"   GPT image models
     \"response_format\" (\"url\"|\"b64_json\") \"style\"   dall-e models
     \"user\"

   Returns the `ImagesResponse`. Throws :tools.agents.openai/invalid-request
   without \"prompt\" and :tools.agents.openai/streaming-unsupported for
   \"stream\" true, both before any I/O."
  [client request]
  (require-field! "images-generate" request "prompt")
  (reject-stream! "images-generate" request)
  (send! client "images-generate" {:method :post :path "/images/generations" :body request}))

(defn images-edit
  "POST {base-url}/images/edits (multipart/form-data) — the analogue of
   `client.images.edit(image=, prompt=, mask=, ...)`. `request`:

     \"image\"   required; one file (part `image`) or a sequence of files
               (parts `image[]`, up to 16 for GPT image models). A file is
               java.io.File, java.nio.file.Path or
               {:content byte[]|File|Path :filename s :content-type s}.
     \"prompt\"  required
     \"mask\"    optional file (part `mask`), a PNG with alpha
     other     scalar fields as form fields: \"model\" \"n\" \"size\"
               \"quality\" \"background\" \"input_fidelity\" \"output_format\"
               \"output_compression\" \"response_format\" \"user\" ...

   Returns the `ImagesResponse`. \"stream\" true throws
   :tools.agents.openai/streaming-unsupported and a missing/unsupported file
   :tools.agents.openai/invalid-request, both before any I/O."
  [client request]
  (reject-stream! "images-edit" request)
  (send! client "images-edit"
         {:method :post :path "/images/edits" :multipart (edit-parts "images-edit" request)}))

(defn images-create-variation
  "POST {base-url}/images/variations (multipart/form-data) — the analogue of
   `client.images.create_variation(image=, ...)`. Only `dall-e-2` supports
   variations (`resources/images.py`); \"model\" is sent verbatim, not
   validated. `request`: \"image\" (required, one square PNG under 4 MB, same
   file forms as `images-edit`), \"model\", \"n\", \"response_format\",
   \"size\", \"user\". Returns the `ImagesResponse`."
  [client request]
  (send! client "images-create-variation"
         {:method    :post
          :path      "/images/variations"
          :multipart (variation-parts "images-create-variation" request)}))

(defn image-bytes
  "Decode one `ImagesResponse` \"data\" item's \"b64_json\" to a byte[]
   (standard Base64). Returns nil for an item without \"b64_json\", i.e. a
   \"url\" item (dall-e models' default `response_format`), which the caller
   fetches itself before the URL expires. All images of a response:
   `(keep image-bytes (get response \"data\"))`."
  ^bytes [item]
  (when-let [s (get item "b64_json")]
    (.decode (java.util.Base64/getDecoder) ^String s)))
