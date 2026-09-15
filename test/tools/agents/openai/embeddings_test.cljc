(ns tools.agents.openai.embeddings-test
  "tools.agents.openai.embeddings: pure encoding-format logic plus a
   mock-server round trip (shared tools.agents.test-support server; base-url
   carries /v1 as in tools.agents.openai.live-test)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.embeddings :as emb]
            [tools.agents.test-support :refer [start-server!]]))

(defn- encode-f32le [xs]
  (let [buf (.order (java.nio.ByteBuffer/allocate (* 4 (count xs))) java.nio.ByteOrder/LITTLE_ENDIAN)]
    (doseq [x xs] (.putFloat buf (float x)))
    (.encodeToString (java.util.Base64/getEncoder) (.array buf))))

;; ---------------------------------------------------------------------------
;; Pure
;; ---------------------------------------------------------------------------

(deftest decode-embedding-base64-round-trips-little-endian-float32
  (is (= "AACAPwAAIMAAAAA+" (encode-f32le [1.0 -2.5 0.125])))
  (is (= [1.0 -2.5 0.125] (emb/decode-embedding-base64 "AACAPwAAIMAAAAA+")))
  (is (every? double? (emb/decode-embedding-base64 "AACAPwAAIMAAAAA+")))
  (is (= [] (emb/decode-embedding-base64 ""))))

(deftest prepare-request-forces-base64-only-when-format-omitted
  (is (= "base64" (get (emb/prepare-request {"model" "m" "input" "x"}) "encoding_format")))
  (is (= {"encoding_format" "float"} (emb/prepare-request {"encoding_format" "float"})))
  (is (= {:encoding_format "base64"} (emb/prepare-request {:encoding_format "base64"})))
  (is (= {:encoding_format "float"} (emb/prepare-request {:encoding_format "float"}))))

(deftest parse-response-decodes-only-for-implicit-format
  (let [b64  (encode-f32le [1.0 -2.5 0.125])
        resp {"object" "list" "data" [{"object" "embedding" "index" 0 "embedding" b64}]}]
    (testing "implicit → decoded"
      (is (= [1.0 -2.5 0.125] (get-in (emb/parse-response {"input" "x"} resp) ["data" 0 "embedding"]))))
    (testing "explicit base64 → untouched strings"
      (is (= resp (emb/parse-response {"encoding_format" "base64"} resp))))
    (testing "explicit float → untouched"
      (let [fresp {"data" [{"embedding" [0.5]}]}]
        (is (= fresp (emb/parse-response {:encoding_format "float"} fresp)))))
    (testing "mixed string and array entries: arrays pass through"
      (let [mixed {"data" [{"embedding" b64} {"embedding" [0.25 0.5]}]}]
        (is (= [[1.0 -2.5 0.125] [0.25 0.5]]
               (mapv #(get % "embedding") (get (emb/parse-response {} mixed) "data"))))))))

(deftest parse-response-empty-data-is-invalid-response
  (doseq [resp [{"data" []} {}]]
    (let [e (try (emb/parse-response {} resp) nil (catch Exception e e))]
      (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))
      (is (str/includes? (ex-message e) "No embedding data received"))))
  ;; explicit format: SDK returns the object untouched, even when empty
  (is (= {"data" []} (emb/parse-response {"encoding_format" "float"} {"data" []}))))

;; ---------------------------------------------------------------------------
;; Mock server
;; ---------------------------------------------------------------------------

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(deftest embeddings-create-posts-base64-and-decodes
  (let [captured (atom nil)
        body     (str "{\"object\":\"list\",\"model\":\"text-embedding-3-small\","
                      "\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":\""
                      (encode-f32le [1.0 -2.5 0.125]) "\"}],"
                      "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}")
        {:keys [port stop!]} (start-server! 19260 "/v1/embeddings"
                                (fn [req] (reset! captured req) {:status 200 :body body}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            resp   (emb/embeddings-create client {"model" "text-embedding-3-small" "input" "hi"})]
        (is (= [1.0 -2.5 0.125] (get-in resp ["data" 0 "embedding"])))
        (is (= "POST" (:method @captured)))
        (is (= "/v1/embeddings" (:path @captured)))
        (is (= "Bearer k" (get (:headers @captured) "authorization")))
        (is (str/includes? (:body @captured) "\"encoding_format\":\"base64\""))
        (is (str/includes? (:body @captured) "\"input\":\"hi\"")))
      (finally (stop!)))))

(deftest embeddings-create-explicit-float-passthrough-and-typed-errors
  (let [captured (atom nil)
        status   (atom 200)
        {:keys [port stop!]} (start-server! 19261 "/v1/embeddings"
                                (fn [req]
                                  (reset! captured req)
                                  (if (= 200 @status)
                                    {:status 200 :body "{\"data\":[{\"embedding\":[0.5,1.5]}]}"}
                                    {:status @status :body "{\"error\":{\"message\":\"bad model\"}}"})))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})]
        (is (= [0.5 1.5] (get-in (emb/embeddings-create client {"model" "m" "input" "x" "encoding_format" "float"})
                                 ["data" 0 "embedding"])))
        (is (str/includes? (:body @captured) "\"encoding_format\":\"float\""))
        (is (not (str/includes? (:body @captured) "base64")))
        (reset! status 400)
        (let [e (try (emb/embeddings-create client {"model" "m" "input" "x"}) nil (catch Exception e e))]
          (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.openai/embeddings-create: HTTP 400 bad model"))))
      (finally (stop!)))))
