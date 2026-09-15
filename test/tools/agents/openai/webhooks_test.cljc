(ns tools.agents.openai.webhooks-test
  "Pure tests for tools.agents.openai.webhooks — reference vectors from
   openai-python (tests/lib/test_webhooks.py) and Standard Webhooks
   (libraries/python/tests/test_webhooks.py), plus the SDK's synthetic matrix
   (tests/lib/test_webhook_signature.py). Clock injected via :now-s."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai.webhooks :as wh]))

(defn- err-type [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))

(defn- err [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

;; ---------------------------------------------------------------------------
;; Reference vectors
;; ---------------------------------------------------------------------------

(def sdk-vector
  {:secret  "whsec_RdvaYFYUXuIFuEbvZHwMfYFhUf7aMYjYcmM24+Aj40c="
   :payload "{\"id\": \"evt_685c059ae3a481909bdc86819b066fb6\", \"object\": \"event\", \"created_at\": 1750861210, \"type\": \"response.completed\", \"data\": {\"id\": \"resp_123\"}}"
   :headers {"webhook-id"        "wh_685c059ae39c8190af8c71ed1022a24d"
             "webhook-timestamp" "1750861210"
             "webhook-signature" "v1,gUAg4R2hWouRZqRQG4uJypNS8YK885G838+EHb4nKBY="}
   :now     1750861210})

(def std-vector
  {:secret  "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"
   :payload "{\"test\": 2432232314}"
   :headers {"webhook-id"        "msg_p5jXN8AQM9LWM0D4loKWxJek"
             "webhook-timestamp" "1614265330"
             "webhook-signature" "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE="}
   :now     1614265330})

(deftest reference-vectors-verify
  (doseq [{:keys [secret payload headers now]} [sdk-vector std-vector]]
    (is (nil? (wh/verify-signature payload headers {:secret secret :now-s now})))
    (is (nil? (wh/verify-signature (.getBytes ^String payload "UTF-8") headers {:secret secret :now-s now})))))

(deftest standard-webhooks-negative-vector
  (let [{:keys [secret payload headers now]} std-vector
        bad (assoc headers "webhook-signature" "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OA=")]
    (is (= :tools.agents.openai/invalid-webhook-signature-error
           (err-type #(wh/verify-signature payload bad {:secret secret :now-s now}))))))

(deftest unwrap-verifies-then-parses
  (let [{:keys [secret payload headers now]} sdk-vector
        evt (wh/unwrap payload headers {:secret secret :now-s now})]
    (is (= "response.completed" (get evt "type")))
    (is (= "resp_123" (get-in evt ["data" "id"])))
    (is (= evt (wh/unwrap (.getBytes ^String payload "UTF-8") headers {:secret secret :now-s now}))))
  (let [{:keys [secret payload headers now]} sdk-vector]
    (is (= :tools.agents.openai/invalid-webhook-signature-error
           (err-type #(wh/unwrap (str payload " ") headers {:secret secret :now-s now}))))))

;; ---------------------------------------------------------------------------
;; SDK synthetic matrix
;; ---------------------------------------------------------------------------

(def now 1750000000)
(def payload "{\"synthetic\": \"café\"}")
(def raw-secret "synthetic-webhook-secret")
(def prefixed-secret
  (str "whsec_" (.encodeToString (java.util.Base64/getEncoder) (.getBytes ^String raw-secret "UTF-8"))))

(defn- signed-headers
  ([] (signed-headers {}))
  ([{:keys [timestamp secret] :or {timestamp (str now) secret raw-secret}}]
   {"webhook-id"        "evt_synthetic"
    "webhook-timestamp" timestamp
    "webhook-signature" (str "v1," (wh/compute-signature payload "evt_synthetic" timestamp secret))}))

(deftest signature-forms-and-secret-encodings
  (doseq [[secret key-secret] [[raw-secret raw-secret] [prefixed-secret raw-secret] ["" ""]]
          body [payload (.getBytes ^String payload "UTF-8")]
          form [:prefixed :bare :multiple]]
    (testing (str [secret (if (string? body) :text :bytes) form])
      (let [h   (signed-headers {:secret key-secret})
            sig (subs (get h "webhook-signature") 3)
            h   (case form
                  :prefixed h
                  :bare     (assoc h "webhook-signature" sig)
                  :multiple (assoc h "webhook-signature" (str "v1,invalid\t" sig " v1,also-invalid")))
            h   (into {} (map (fn [[k v]] [(str/upper-case k) v]) h))]
        (is (nil? (wh/verify-signature body h {:secret secret :now-s now})))))))

(defn- hmac-b64 [^bytes key ^String msg]
  (let [mac (javax.crypto.Mac/getInstance "HmacSHA256")]
    (.init mac (javax.crypto.spec.SecretKeySpec. key "HmacSHA256"))
    (.encodeToString (java.util.Base64/getEncoder) (.doFinal mac (.getBytes msg "UTF-8")))))

(deftest raw-prefixed-and-empty-secret-keys
  (is (= (wh/compute-signature payload "evt_synthetic" "1" raw-secret)
         (wh/compute-signature payload "evt_synthetic" "1" prefixed-secret)
         (hmac-b64 (.getBytes ^String raw-secret "UTF-8") (str "evt_synthetic.1." payload))))
  ;; Python's hmac accepts b""; SecretKeySpec does not. HMAC zero-pads keys
  ;; to the 64-byte block, so "" must sign like any all-zero key.
  (is (= (hmac-b64 (byte-array 1) (str "evt_synthetic.1." payload))
         (wh/compute-signature payload "evt_synthetic" "1" "")
         (wh/compute-signature payload "evt_synthetic" "1" "whsec_"))))

(deftest replay-window-boundaries
  (doseq [[delta tolerance msg] [[0 0 nil] [-300 300 nil] [300 300 nil]
                                 [-301 300 "too old"] [301 300 "too new"]
                                 [-1 0 "too old"] [1 0 "too new"] [0 -1 "too old"]]]
    (testing [delta tolerance]
      (let [h (signed-headers {:timestamp (str (+ now delta))})
            f #(wh/verify-signature payload h {:secret raw-secret :tolerance tolerance :now-s now})]
        (if msg
          (let [e (err f)]
            (is (= :tools.agents.openai/invalid-webhook-signature-error (:type (ex-data e))))
            (is (str/includes? (ex-message e) msg)))
          (is (nil? (f))))))))

(deftest default-tolerance-is-300
  (is (nil? (wh/verify-signature payload (signed-headers {:timestamp (str (- now 300))}) {:secret raw-secret :now-s now})))
  (is (some? (err #(wh/verify-signature payload (signed-headers {:timestamp (str (- now 301))}) {:secret raw-secret :now-s now})))))

(deftest signature-uses-original-timestamp-text
  (doseq [ts [(str "0" now) (str "+" now) (str " " now " ")]]
    (is (nil? (wh/verify-signature payload (signed-headers {:timestamp ts}) {:secret raw-secret :now-s now})))))

(deftest invalid-timestamp-format
  (doseq [ts ["" "not-a-timestamp" "1.5"]]
    (let [e (err #(wh/verify-signature payload (signed-headers {:timestamp ts}) {:secret raw-secret :now-s now}))]
      (is (= :tools.agents.openai/invalid-webhook-signature-error (:type (ex-data e))))
      (is (str/includes? (ex-message e) "Invalid webhook timestamp format")))))

(deftest missing-headers-in-sdk-order
  (doseq [[h missing] [[{} "webhook-signature"]
                       [{"webhook-signature" "invalid"} "webhook-timestamp"]
                       [{"webhook-signature" "invalid" "webhook-timestamp" (str now)} "webhook-id"]]]
    (let [e (err #(wh/verify-signature payload h {:secret raw-secret :now-s now}))]
      (is (= :tools.agents.openai/missing-webhook-header (:type (ex-data e))))
      (is (= missing (:header (ex-data e))))
      (is (str/includes? (ex-message e) (str "Could not find " missing " header"))))))

(deftest keyword-header-keys-accepted
  (let [h (into {} (map (fn [[k v]] [(keyword k) v]) (signed-headers)))]
    (is (nil? (wh/verify-signature payload h {:secret raw-secret :now-s now})))))

(deftest mismatch-error-leaks-nothing
  (let [h (assoc (signed-headers) "webhook-signature" "v1,synthetic-invalid-signature")
        e (err #(wh/verify-signature payload h {:secret raw-secret :now-s now}))]
    (is (= :tools.agents.openai/invalid-webhook-signature-error (:type (ex-data e))))
    (is (str/includes? (ex-message e) "does not match the expected signature"))
    (doseq [s [raw-secret payload "synthetic-invalid-signature"]]
      (is (not (str/includes? (str (ex-message e) (ex-data e)) s))))))

(deftest non-ascii-signature-is-a-mismatch
  (is (= :tools.agents.openai/invalid-webhook-signature-error
         (err-type #(wh/verify-signature payload (assoc (signed-headers) "webhook-signature" "v1,é")
                                         {:secret raw-secret :now-s now})))))

(deftest malformed-prefixed-secret-is-typed
  (is (= :tools.agents.openai/invalid-webhook-secret
         (err-type #(wh/verify-signature payload (signed-headers) {:secret "whsec_a" :now-s now})))))

(deftest secret-resolution
  (is (= "explicit" (wh/resolve-webhook-secret {:secret "explicit"} (constantly "env"))))
  (is (= "" (wh/resolve-webhook-secret {:secret ""} (constantly "env"))))
  (is (= "env" (wh/resolve-webhook-secret {} {"OPENAI_WEBHOOK_SECRET" "env"})))
  (is (= :tools.agents.openai/missing-webhook-secret
         (err-type #(wh/resolve-webhook-secret {} (constantly nil))))))
