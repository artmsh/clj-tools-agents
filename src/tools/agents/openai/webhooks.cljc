(ns tools.agents.openai.webhooks
  "OpenAI webhook signature verification — port of openai-python's
   `client.webhooks.verify_signature` / `client.webhooks.unwrap`
   (`resources/webhooks/webhooks.py`, `lib/_webhooks.py`), which implement the
   Standard Webhooks scheme (https://www.standardwebhooks.com).

   Pure: no HTTP; a client is optional (only its :webhook-secret is read).
   Only the clock (overridable via :now-s) and the OPENAI_WEBHOOK_SECRET env
   var touch the process.

   PAYLOAD MUST BE THE RAW REQUEST BODY (String or byte[]), exactly as
   received. Re-serialized JSON (parsed then written back) changes bytes and
   will not verify.

   Scheme: HMAC-SHA256 over the UTF-8 bytes of \"{webhook-id}.{webhook-timestamp}.{body}\"
   (timestamp header text used verbatim), base64 (standard, padded), compared
   in constant time against each space-separated part of `webhook-signature`
   (`v1,<b64>` or bare `<b64>`; any match accepts). A `whsec_`-prefixed secret
   is base64-decoded; any other secret is used as its raw UTF-8 bytes.

   Errors (ex-info, :type in ex-data):
     :tools.agents.openai/missing-webhook-secret           no :secret, no client secret, no env var (SDK ValueError)
     :tools.agents.openai/missing-webhook-header           header absent, :header in ex-data (SDK ValueError)
     :tools.agents.openai/invalid-webhook-secret           `whsec_` remainder is not base64 (SDK binascii.Error)
     :tools.agents.openai/invalid-webhook-signature-error  bad timestamp format, too old, too new,
                                                           or no signature matches (SDK InvalidWebhookSignatureError)"
  (:require [clojure.string :as str]
            [tools.agents.openai :as oai]))

(def default-tolerance-s
  "Replay window in seconds, both directions — the SDK's `tolerance=300`."
  300)

(defn- fail! [type msg & [data]]
  (throw (ex-info (str "tools.agents.openai.webhooks/verify-signature: " msg) (merge {:type type} data))))

(defn resolve-webhook-secret
  "Explicit :secret (an explicit \"\" is kept, as in the SDK) >
   (:webhook-secret (:client opts)) > OPENAI_WEBHOOK_SECRET > throw
   :tools.agents.openai/missing-webhook-secret. :client is an optional
   tools.agents.openai/client record (the SDK's client-level
   `webhook_secret`). getenv-fn is injected for tests."
  [opts getenv-fn]
  (let [s  (:secret opts)
        cs (:webhook-secret (:client opts))]
    (cond
      (some? s) s
      (some? cs) cs
      (some? (getenv-fn "OPENAI_WEBHOOK_SECRET")) (getenv-fn "OPENAI_WEBHOOK_SECRET")
      :else (fail! :tools.agents.openai/missing-webhook-secret
                   (str "The webhook secret must either be set using the env var, "
                        "OPENAI_WEBHOOK_SECRET, or passed as :secret")))))

(defn- required-header [headers k]
  (let [v (oai/header-value headers k)]
    (if (string? v)
      v
      (fail! :tools.agents.openai/missing-webhook-header (str "Could not find " k " header") {:header k}))))

(defn- parse-timestamp
  "Python int(): surrounding whitespace, optional sign, digits with single
   underscores between groups. Anything else → nil."
  [s]
  (when-let [[_ t] (re-matches #"\s*([+-]?\d+(?:_\d+)*)\s*" s)]
    (parse-long (str/replace (str/replace t "_" "") #"^\+" ""))))

(defn- secret-key-bytes ^bytes [^String secret]
  (let [raw (if (str/starts-with? secret "whsec_")
              (try
                (.decode (java.util.Base64/getMimeDecoder) (subs secret 6))
                (catch IllegalArgumentException _
                  (fail! :tools.agents.openai/invalid-webhook-secret
                         "webhook secret after whsec_ is not valid base64")))
              (.getBytes secret "UTF-8"))]
    ;; SecretKeySpec rejects an empty key; Python's hmac accepts b"". HMAC
    ;; zero-pads keys to the 64-byte block, so 64 zero bytes is the same key.
    (if (zero? (alength ^bytes raw)) (byte-array 64) raw)))

(defn- payload-bytes ^bytes [payload]
  (if (string? payload) (.getBytes ^String payload "UTF-8") payload))

(defn compute-signature
  "Base64 HMAC-SHA256 of \"{webhook-id}.{timestamp}.{payload}\" under secret
   (same secret decoding as `verify-signature`). The unprefixed value that
   follows `v1,` in a `webhook-signature` header."
  [payload webhook-id timestamp secret]
  (let [mac    (javax.crypto.Mac/getInstance "HmacSHA256")
        _      (.init mac (javax.crypto.spec.SecretKeySpec. (secret-key-bytes secret) "HmacSHA256"))
        prefix (.getBytes (str webhook-id "." timestamp ".") "UTF-8")]
    (.update mac prefix)
    (.encodeToString (java.util.Base64/getEncoder) (.doFinal mac (payload-bytes payload)))))

(defn verify-signature
  "Validate that payload was sent by OpenAI — the analogue of
   `client.webhooks.verify_signature(payload, headers, secret=, tolerance=)`.
   Returns nil or throws (see ns docstring for error types).

   headers: map, keys matched case-insensitively (strings or keywords).
   opts:
     :secret     webhook secret; falls back to :client's :webhook-secret,
                 then OPENAI_WEBHOOK_SECRET
     :client     optional tools.agents.openai/client record
     :tolerance  seconds, default 300, applied to both too-old and too-new
     :now-s      current epoch seconds (default: system clock) — for tests"
  ([payload headers] (verify-signature payload headers {}))
  ([payload headers opts]
   (let [secret    (resolve-webhook-secret opts #(System/getenv %))
         sig-hdr   (required-header headers "webhook-signature")
         ts-hdr    (required-header headers "webhook-timestamp")
         id-hdr    (required-header headers "webhook-id")
         ts        (or (parse-timestamp ts-hdr)
                       (fail! :tools.agents.openai/invalid-webhook-signature-error
                              "Invalid webhook timestamp format"))
         now       (long (or (:now-s opts) (quot (System/currentTimeMillis) 1000)))
         tolerance (long (or (:tolerance opts) default-tolerance-s))]
     (when (> (- now ts) tolerance)
       (fail! :tools.agents.openai/invalid-webhook-signature-error "Webhook timestamp is too old"))
     (when (> ts (+ now tolerance))
       (fail! :tools.agents.openai/invalid-webhook-signature-error "Webhook timestamp is too new"))
     (let [expected (.getBytes ^String (compute-signature payload id-hdr ts-hdr secret) "UTF-8")
           sigs     (->> (str/split sig-hdr #"\s+")
                         (remove str/blank?)
                         (map #(if (str/starts-with? % "v1,") (subs % 3) %)))]
       (when-not (some #(java.security.MessageDigest/isEqual expected (.getBytes ^String % "UTF-8")) sigs)
         (fail! :tools.agents.openai/invalid-webhook-signature-error
                "The given webhook signature does not match the expected signature"))
       nil))))

(defn unwrap
  "Verify then parse — the analogue of `client.webhooks.unwrap(payload,
   headers, secret=)`. Same opts and errors as `verify-signature`; returns the
   event decoded by `tools.agents.openai/read-json` (string keys)."
  ([payload headers] (unwrap payload headers {}))
  ([payload headers opts]
   (verify-signature payload headers opts)
   (oai/read-json (if (string? payload) payload (String. ^bytes payload "UTF-8")))))
