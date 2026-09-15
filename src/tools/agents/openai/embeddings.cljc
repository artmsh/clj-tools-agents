(ns tools.agents.openai.embeddings
  "OpenAI Embeddings API — port of openai-python's `client.embeddings.create`
   (`resources/embeddings.py`, `lib/_parsing/_embeddings.py`).

   Takes the `OpenAIClient` record built by `tools.agents.openai/client` and
   POSTs through `tools.agents.openai/post-json!`, so retries, error typing
   and `:stream` refusal are identical to `responses-create`.

   ENCODING FORMAT, exactly as the SDK:
     - caller omits `encoding_format` → the request is sent with
       `\"encoding_format\" \"base64\"` (a ~4x smaller wire payload) and every
       string `data[].embedding` is decoded as little-endian float32 into a
       vector of doubles, so the caller sees float vectors either way;
     - caller passes `encoding_format` (\"float\" or \"base64\") → the response
       is returned untouched; an explicit \"base64\" yields base64 strings."
  (:require [tools.agents.openai :as oai]))

(defn decode-embedding-base64
  "Decode a base64 string of little-endian IEEE-754 float32 values into a
   vector of doubles — numpy `frombuffer(b64decode(s), dtype=\"float32\")`.
   Trailing bytes that do not form a whole float32 are ignored."
  [s]
  (let [bytes (.decode (java.util.Base64/getDecoder) ^String s)
        buf   (.order (java.nio.ByteBuffer/wrap bytes) java.nio.ByteOrder/LITTLE_ENDIAN)
        n     (quot (alength bytes) 4)]
    (loop [i 0 acc (transient [])]
      (if (< i n)
        (recur (inc i) (conj! acc (double (.getFloat buf (int (* 4 i))))))
        (persistent! acc)))))

(defn- format-given? [request]
  (or (contains? request "encoding_format") (contains? request :encoding_format)))

(defn prepare-request
  "The request map actually sent: `request` with `\"encoding_format\"
   \"base64\"` added when the caller gave none (string or keyword key)."
  [request]
  (if (format-given? request)
    request
    (assoc request "encoding_format" "base64")))

(defn parse-response
  "openai-python's `parse_embedding_response`. `request` is the caller's
   ORIGINAL request (before `prepare-request`). With an explicit
   `encoding_format` the response is returned as-is. Otherwise empty or
   missing \"data\" throws `:tools.agents.openai/invalid-response` (the SDK's
   `ValueError(\"No embedding data received\")`), and each string
   \"embedding\" is decoded via `decode-embedding-base64`; non-string entries
   pass through (gateways that ignore `encoding_format`)."
  [request response]
  (if (format-given? request)
    response
    (let [data (get response "data")]
      (when (or (not (sequential? data)) (empty? data))
        (throw (ex-info "tools.agents.openai/embeddings-create: No embedding data received"
                        {:type :tools.agents.openai/invalid-response :status nil :body nil})))
      (assoc response "data"
             (mapv (fn [item]
                     (let [e (when (map? item) (get item "embedding"))]
                       (if (string? e)
                         (assoc item "embedding" (decode-embedding-base64 e))
                         item)))
                   data)))))

(defn embeddings-create
  "POST {base-url}/embeddings — the analogue of
   `client.embeddings.create(**params)`. `request` passes through to JSON:
   \"input\", \"model\", \"dimensions\", \"encoding_format\", \"user\". Returns
   the decoded response map (string keys); see the ns docstring for the
   implicit base64 request and float32 decoding.

   Throws ex-info with the same `:type`/`:status`/`:body` contract as
   `tools.agents.openai/responses-create`, message prefix
   \"tools.agents.openai/embeddings-create: \"."
  [client request]
  (parse-response request
                  (oai/post-json! client "embeddings-create" "/embeddings" (prepare-request request))))
