(ns tools.agents.openai.images-test
  "tools.agents.openai.images: pure part building plus mock-server round trips
   (shared tools.agents.test-support server; base-url carries /v1)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.images :as images]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- client [port] (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0}))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(defn- all-bytes
  "Every byte value 0x00..0xff, then an invalid UTF-8 sequence."
  ^bytes []
  (byte-array (concat (map unchecked-byte (range 256)) [(unchecked-byte 0xc3) (unchecked-byte 0x28)])))

(defn- latin1 [^bytes bs] (String. bs "ISO-8859-1"))

(defn- parse-multipart
  "Parse a multipart body (raw bytes) into [{:headers {lc-name value} :body byte[]}]
   in wire order. ISO-8859-1 maps bytes 1:1 to chars, so binary parts survive."
  [content-type ^bytes body]
  (let [boundary (second (re-find #"boundary=(\S+)" content-type))
        s        (latin1 body)
        delim    (str "--" boundary)]
    (is (str/ends-with? s (str delim "--\r\n")) "closing delimiter")
    (->> (str/split s (re-pattern (java.util.regex.Pattern/quote delim)))
         rest
         (remove #(str/starts-with? % "--"))
         (mapv (fn [chunk]
                 (let [chunk        (subs chunk 2 (- (count chunk) 2))
                       idx          (str/index-of chunk "\r\n\r\n")
                       header-lines (str/split-lines (subs chunk 0 idx))]
                   {:headers (into {} (map (fn [l] (let [[k v] (str/split l #":\s*" 2)] [(str/lower-case k) v]))
                                           header-lines))
                    :body    (.getBytes ^String (subs chunk (+ idx 4)) "ISO-8859-1")}))))))

(def ^:private images-response
  "{\"created\":1,\"data\":[{\"b64_json\":\"AAE=\"}]}")

(defn- b64 [^bytes bs] (.encodeToString (java.util.Base64/getEncoder) bs))

;; ---------------------------------------------------------------------------
;; Pure
;; ---------------------------------------------------------------------------

(deftest edit-parts-single-image-vs-sequence
  (let [a (.getBytes "a" "UTF-8")
        b (.getBytes "b" "UTF-8")]
    (testing "a single file is one `image` part; fields first; content-type guessed"
      (is (= [{:name "prompt" :content "p"}
              {:name "n" :content "2"}
              {:name "image" :content a :filename "a.png" :content-type "image/png"}]
             (images/edit-parts {"image" {:content a :filename "a.png"} "prompt" "p" "n" 2}))))
    (testing "a sequence is `image[]` per entry (even of one), then mask"
      (is (= [{:name "prompt" :content "p"}
              {:name "image[]" :content a :filename "a.JPG" :content-type "image/jpeg"}]
             (images/edit-parts {:image [{:content a :filename "a.JPG"}] :prompt "p"})))
      (is (= [{:name "prompt" :content "p"}
              {:name "image[]" :content a :filename "a.webp" :content-type "image/webp"}
              {:name "image[]" :content b :filename "b.bin" :content-type "application/octet-stream"}
              {:name "mask" :content b :filename "m.png" :content-type "image/x-custom"}]
             (images/edit-parts {"image" [{:content a :filename "a.webp"} {:content b :filename "b.bin"}]
                                 "mask" {:content b :filename "m.png" :content-type "image/x-custom"}
                                 "prompt" "p"
                                 "user" nil}))))
    (testing "a File part keeps its object and guesses from its own name"
      (let [f (java.io.File. "/tmp/x/cat.png")]
        (is (= {:name "image" :content f :content-type "image/png"}
               (last (images/edit-parts {"image" f "prompt" "p"}))))))))

(deftest parts-reject-bad-requests-before-io
  (let [c (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1" :max-retries 0})
        png {:content (.getBytes "x") :filename "a.png"}]
    (doseq [[fname f req msg]
            [["images-edit" images/images-edit {"prompt" "p"} "request needs \"image\""]
             ["images-edit" images/images-edit {"image" png} "request needs \"prompt\""]
             ["images-edit" images/images-edit {"image" [] "prompt" "p"} "empty sequence"]
             ["images-edit" images/images-edit {"image" (.getBytes "x") "prompt" "p"} "image: a byte[] file needs a filename"]
             ["images-edit" images/images-edit {"image" png "mask" "/tmp/m.png" "prompt" "p"} "mask: a String file is ambiguous"]
             ["images-create-variation" images/images-create-variation {} "request needs \"image\""]
             ["images-create-variation" images/images-create-variation {"image" [png png]} "single file"]
             ["images-generate" images/images-generate {"model" "gpt-image-1"} "request needs \"prompt\""]]]
      (let [e (thrown #(f c req))]
        (is (= :tools.agents.openai/invalid-request (:type (ex-data e))) msg)
        (is (str/starts-with? (ex-message e) (str "tools.agents.openai.images/" fname ": ")) (ex-message e))
        (is (str/includes? (ex-message e) msg) (ex-message e))))))

(deftest stream-refused-before-io
  (let [c   (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1" :max-retries 0})
        png {:content (.getBytes "x") :filename "a.png"}]
    (doseq [[fname f req] [["images-generate" images/images-generate {"prompt" "p" "stream" true}]
                           ["images-generate" images/images-generate {:prompt "p" :stream true}]
                           ["images-edit" images/images-edit {"image" png "prompt" "p" "stream" true}]
                           ["images-edit" images/images-edit {"image" png "prompt" "p" "stream" "true"}]]]
      (let [e (thrown #(f c req))]
        (is (= :tools.agents.openai/streaming-unsupported (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) (str "tools.agents.openai.images/" fname ": ")))))))

(deftest image-bytes-decodes-byte-exact
  (let [payload (all-bytes)]
    (is (= (vec payload) (vec (images/image-bytes {"b64_json" (b64 payload)}))))
    (is (nil? (images/image-bytes {"url" "https://example.com/img.png"})))
    (is (= [[0 1] [(unchecked-byte 0xff)]]
           (mapv vec (keep images/image-bytes [{"b64_json" "AAE="} {"url" "u"} {"b64_json" "/w=="}]))))))

;; ---------------------------------------------------------------------------
;; Mock server
;; ---------------------------------------------------------------------------

(deftest images-generate-json-body
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 0 "/v1/images"
                                (fn [req] (reset! captured req) {:status 200 :body images-response}))]
    (try
      (let [resp (images/images-generate (client port)
                                         {"model" "gpt-image-1" "prompt" "a cat" "n" 1 "size" "1024x1024"
                                          "quality" "low" "stream" false})
            {:keys [method path headers body]} @captured]
        (is (= ["POST" "/v1/images/generations"] [method path]))
        (is (= "application/json" (get headers "content-type")))
        (is (= "Bearer k" (get headers "authorization")))
        (is (= {"model" "gpt-image-1" "prompt" "a cat" "n" 1 "size" "1024x1024" "quality" "low" "stream" false}
               (oai/read-json body)))
        (is (= [0 1] (vec (images/image-bytes (first (get resp "data")))))))
      (finally (stop!)))))

(deftest images-edit-multipart-wire-format
  (let [captured (atom nil)
        img-a    (all-bytes)
        img-b    (.getBytes "second" "UTF-8")
        mask     (byte-array [(unchecked-byte 0x89) 0x50 0x4e 0x47 0 (unchecked-byte 0xff)])
        tmp      (java.io.File/createTempFile "tools-agents-images" ".webp")
        {:keys [port stop!]} (start-server! 0 "/v1/images"
                                (fn [req] (reset! captured req) {:status 200 :body images-response}))]
    (try
      (with-open [os (java.io.FileOutputStream. tmp)] (.write os img-b))
      (let [resp  (images/images-edit (client port)
                                      {"image"   [{:content img-a :filename "a.png"} tmp]
                                       "mask"    {:content mask :filename "mask.png"}
                                       "prompt"  "combine"
                                       "model"   "gpt-image-1"
                                       "n"       2
                                       "stream"  false
                                       "user"    nil})
            {:keys [method path headers body-bytes]} @captured
            ct    (get headers "content-type")
            parts (parse-multipart ct body-bytes)]
        (is (= 1 (count (get resp "data"))))
        (is (= ["POST" "/v1/images/edits"] [method path]))
        (is (str/starts-with? ct "multipart/form-data; boundary="))
        (is (= (str (alength ^bytes body-bytes)) (get headers "content-length")))
        (is (= ["form-data; name=\"prompt\""
                "form-data; name=\"model\""
                "form-data; name=\"n\""
                "form-data; name=\"stream\""
                "form-data; name=\"image[]\"; filename=\"a.png\""
                (str "form-data; name=\"image[]\"; filename=\"" (.getName tmp) "\"")
                "form-data; name=\"mask\"; filename=\"mask.png\""]
               (mapv #(get-in % [:headers "content-disposition"]) parts)))
        (is (= [nil nil nil nil "image/png" "image/webp" "image/png"]
               (mapv #(get-in % [:headers "content-type"]) parts)))
        (is (= ["combine" "gpt-image-1" "2" "false"] (mapv (comp latin1 :body) (take 4 parts))))
        (is (= (vec img-a) (vec (:body (nth parts 4)))))
        (is (= (vec img-b) (vec (:body (nth parts 5)))))
        (is (= (vec mask) (vec (:body (nth parts 6))))))
      (finally (stop!) (.delete tmp)))))

(deftest images-create-variation-multipart
  (let [captured (atom nil)
        img      (all-bytes)
        {:keys [port stop!]} (start-server! 0 "/v1/images"
                                (fn [req] (reset! captured req)
                                  {:status 200 :body "{\"created\":1,\"data\":[{\"url\":\"https://x/y.png\"}]}"}))]
    (try
      (let [resp  (images/images-create-variation (client port)
                                                  {"image" {:content img :filename "in.png"}
                                                   "model" "dall-e-2" "n" 1 "size" "256x256"
                                                   "response_format" "url"})
            {:keys [method path headers body-bytes]} @captured
            parts (parse-multipart (get headers "content-type") body-bytes)]
        (is (= ["POST" "/v1/images/variations"] [method path]))
        (is (= ["form-data; name=\"model\""
                "form-data; name=\"n\""
                "form-data; name=\"size\""
                "form-data; name=\"response_format\""
                "form-data; name=\"image\"; filename=\"in.png\""]
               (mapv #(get-in % [:headers "content-disposition"]) parts)))
        (is (= "image/png" (get-in (last parts) [:headers "content-type"])))
        (is (= (vec img) (vec (:body (last parts)))))
        (is (nil? (images/image-bytes (first (get resp "data")))))
        (is (= "https://x/y.png" (get-in resp ["data" 0 "url"]))))
      (finally (stop!)))))

(deftest images-4xx-typing
  (let [hits (atom 0)
        {:keys [port stop!]} (start-server! 0 "/v1/images"
                                (fn [req]
                                  (swap! hits inc)
                                  (if (str/ends-with? (:path req) "/generations")
                                    {:status 400 :body "{\"error\":{\"message\":\"Your request was rejected by the safety system.\",\"type\":\"image_generation_user_error\",\"code\":\"moderation_blocked\"}}"}
                                    {:status 401 :body "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}"})))]
    (try
      (let [c (oai/client {:api-key "k" :base-url (base-url port) :max-retries 2})
            e (thrown #(images/images-generate c {"prompt" "p"}))]
        (is (= :tools.agents.openai/bad-request-error (:type (ex-data e))))
        (is (= 400 (:status (ex-data e))))
        (is (string? (:body (ex-data e))))
        (is (= "tools.agents.openai.images/images-generate: HTTP 400 Your request was rejected by the safety system."
               (ex-message e)))
        (is (= 1 @hits) "4xx not retried")
        (let [e (thrown #(images/images-create-variation c {"image" {:content (.getBytes "x") :filename "a.png"}}))]
          (is (= :tools.agents.openai/authentication-error (:type (ex-data e))))
          (is (= 401 (:status (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.openai.images/images-create-variation: HTTP 401"))))
      (finally (stop!)))))
