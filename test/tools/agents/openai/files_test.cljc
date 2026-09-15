(ns tools.agents.openai.files-test
  "tools.agents.openai.files: pure part building plus mock-server round trips
   (shared tools.agents.test-support server; base-url carries /v1). Ports
   19280-19289."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.openai :as oai]
            [tools.agents.openai.files :as files]
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
                 (let [chunk        (subs chunk 2 (- (count chunk) 2)) ;; leading and trailing CRLF
                       idx          (str/index-of chunk "\r\n\r\n")
                       header-lines (str/split-lines (subs chunk 0 idx))]
                   {:headers (into {} (map (fn [l] (let [[k v] (str/split l #":\s*" 2)] [(str/lower-case k) v]))
                                           header-lines))
                    :body    (.getBytes ^String (subs chunk (+ idx 4)) "ISO-8859-1")}))))))

(defn- part-named [parts n]
  (first (filter #(str/includes? (get-in % [:headers "content-disposition"]) (str "name=\"" n "\"")) parts)))

;; ---------------------------------------------------------------------------
;; Pure
;; ---------------------------------------------------------------------------

(deftest create-parts-flattens-fields-first-then-file
  (let [bs    (.getBytes "{}" "UTF-8")
        parts (files/create-parts {"file" {:content bs :filename "in.jsonl"}
                                   "purpose" "batch"
                                   "expires_after" {"anchor" "created_at" "seconds" 3600}})]
    (is (= [{:name "purpose" :content "batch"}
            {:name "expires_after[anchor]" :content "created_at"}
            {:name "expires_after[seconds]" :content "3600"}]
           (butlast parts)))
    (is (= {:name "file" :content bs :filename "in.jsonl" :content-type "application/octet-stream"}
           (last parts))))
  (testing "keyword keys and values, explicit content-type"
    (let [bs (.getBytes "x" "UTF-8")]
      (is (= [{:name "purpose" :content "vision"}
              {:name "file" :content bs :filename "a.png" :content-type "image/png"}]
             (files/create-parts {:file {:content bs :filename "a.png" :content-type "image/png"}
                                  :purpose :vision})))))
  (testing "a File keeps its object (streamed) and its own name"
    (let [f (java.io.File. "/tmp/x/data.jsonl")]
      (is (= {:name "file" :content f :content-type "application/octet-stream"}
             (files/file-part f)))
      (is (some #(identical? f %) (http/multipart-chunks [(files/file-part f)] "b")))))
  (testing "a Path is accepted"
    (let [p (.toPath (java.io.File. "/tmp/x/img.png"))]
      (is (identical? p (:content (files/file-part p)))))))

(deftest create-parts-rejects-bad-files-before-io
  (doseq [[req msg] [[{"purpose" "batch"} "request needs \"file\""]
                     [{"file" (.getBytes "x") "purpose" "batch"} "needs a filename"]
                     [{"file" "/tmp/a.jsonl" "purpose" "batch"} "ambiguous"]
                     [{"file" {:content "text" :filename "a.txt"} "purpose" "batch"} "unsupported file :content"]
                     [{"file" {:content (java.io.ByteArrayInputStream. (.getBytes "x")) :filename "a.txt"}
                       "purpose" "batch"} "unsupported file :content"]
                     [{"file" {:content (.getBytes "x")} "purpose" "batch"} ":filename is required"]
                     [{"file" 42 "purpose" "batch"} "unsupported file"]]]
    (let [e (thrown #(files/files-create (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1"}) req))]
      (is (= :tools.agents.openai/invalid-request (:type (ex-data e))) msg)
      (is (str/starts-with? (ex-message e) "tools.agents.openai.files/files-create: "))
      (is (str/includes? (ex-message e) msg)))))

(deftest empty-file-id-is-invalid-request-before-io
  (let [c (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1" :max-retries 0})]
    (doseq [[fname f] [["files-retrieve" files/files-retrieve]
                       ["files-delete" files/files-delete]
                       ["files-content" files/files-content]]
            id [nil ""]]
      (let [e (thrown #(f c id))]
        (is (= :tools.agents.openai/invalid-request (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) (str "tools.agents.openai.files/" fname ": ")))))))

;; ---------------------------------------------------------------------------
;; Mock server
;; ---------------------------------------------------------------------------

(def ^:private file-object
  "{\"id\":\"file-abc\",\"object\":\"file\",\"bytes\":2,\"created_at\":1,\"filename\":\"in.jsonl\",\"purpose\":\"batch\",\"status\":\"processed\"}")

(deftest files-create-multipart-wire-format
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19280 "/v1/files"
                                (fn [req] (reset! captured req) {:status 200 :body file-object}))]
    (try
      (let [resp (files/files-create (client port)
                                     {"file" {:content (.getBytes "{\"a\":1}\n" "UTF-8") :filename "in.jsonl"}
                                      "purpose" "batch"
                                      "expires_after" {"anchor" "created_at" "seconds" 3600}})
            {:keys [headers body-bytes]} @captured
            ct    (get headers "content-type")
            parts (parse-multipart ct body-bytes)]
        (is (= "file-abc" (get resp "id")))
        (is (= "POST" (:method @captured)))
        (is (= "/v1/files" (:path @captured)))
        (is (nil? (:query @captured)))
        (is (= "Bearer k" (get headers "authorization")))
        (is (str/starts-with? ct "multipart/form-data; boundary="))
        (is (= ["form-data; name=\"purpose\""
                "form-data; name=\"expires_after[anchor]\""
                "form-data; name=\"expires_after[seconds]\""
                "form-data; name=\"file\"; filename=\"in.jsonl\""]
               (mapv #(get-in % [:headers "content-disposition"]) parts)))
        (testing "data fields carry no filename and no content-type"
          (doseq [p (butlast parts)]
            (is (= #{"content-disposition"} (set (keys (:headers p)))))))
        (is (= "batch" (latin1 (:body (part-named parts "purpose")))))
        (is (= "created_at" (latin1 (:body (part-named parts "expires_after[anchor]")))))
        (is (= "3600" (latin1 (:body (part-named parts "expires_after[seconds]")))))
        (is (= "application/octet-stream" (get-in (last parts) [:headers "content-type"])))
        (is (= "{\"a\":1}\n" (latin1 (:body (last parts))))))
      (finally (stop!)))))

(deftest files-create-streams-file-from-disk-byte-exact
  (let [captured (atom nil)
        tmp      (java.io.File/createTempFile "tools-agents-files" ".bin")
        payload  (all-bytes)
        {:keys [port stop!]} (start-server! 19281 "/v1/files"
                                (fn [req] (reset! captured req) {:status 200 :body file-object}))]
    (try
      (with-open [os (java.io.FileOutputStream. tmp)] (.write os payload))
      (files/files-create (client port) {"file" tmp "purpose" "vision"})
      (let [{:keys [headers body-bytes]} @captured
            parts (parse-multipart (get headers "content-type") body-bytes)
            fpart (part-named parts "file")]
        (testing "File part keeps a known Content-Length (ofFile publisher, not chunked)"
          (is (= (str (alength ^bytes body-bytes)) (get headers "content-length")))
          (is (nil? (get headers "transfer-encoding"))))
        (is (str/includes? (get-in fpart [:headers "content-disposition"])
                           (str "filename=\"" (.getName tmp) "\"")))
        (is (= "vision" (latin1 (:body (part-named parts "purpose")))))
        (is (= (vec payload) (vec (:body fpart)))))
      (finally (stop!) (.delete tmp)))))

(deftest files-list-query-params
  (let [captured (atom [])
        {:keys [port stop!]} (start-server! 19282 "/v1/files"
                                (fn [req] (swap! captured conj req)
                                  {:status 200
                                   :body "{\"object\":\"list\",\"data\":[],\"first_id\":null,\"last_id\":null,\"has_more\":false}"}))]
    (try
      (let [c (client port)]
        (is (= false (get (files/files-list c) "has_more")))
        (files/files-list c {"purpose" "fine-tune" "limit" 50 "order" :asc "after" "file-x" "unset" nil})
        (let [[a b] @captured]
          (is (= ["GET" "/v1/files" nil] ((juxt :method :path :query) a)))
          (is (= "GET" (:method b)))
          (is (= "/v1/files" (:path b)))
          (is (= #{"purpose=fine-tune" "limit=50" "order=asc" "after=file-x"}
                 (set (str/split (:query b) #"&"))))))
      (finally (stop!)))))

(deftest files-retrieve-delete-and-404-typing
  (let [captured (atom [])
        {:keys [port stop!]} (start-server! 19283 "/v1/files"
                                (fn [req]
                                  (swap! captured conj req)
                                  (cond
                                    (str/includes? (:path req) "missing")
                                    {:status 404 :body "{\"error\":{\"message\":\"No such File object: missing\",\"type\":\"invalid_request_error\"}}"}
                                    (= "DELETE" (:method req))
                                    {:status 200 :body "{\"id\":\"file-abc\",\"object\":\"file\",\"deleted\":true}"}
                                    :else {:status 200 :body file-object})))]
    (try
      (let [c (client port)]
        (is (= "processed" (get (files/files-retrieve c "file-abc") "status")))
        (is (= true (get (files/files-delete c "file-abc") "deleted")))
        (is (= [["GET" "/v1/files/file-abc"] ["DELETE" "/v1/files/file-abc"]]
               (mapv (juxt :method :path) @captured)))
        (testing "404 → not-found-error on retrieve, delete and content"
          (doseq [[fname f] [["files-retrieve" files/files-retrieve]
                             ["files-delete" files/files-delete]
                             ["files-content" files/files-content]]]
            (let [e (thrown #(f c "missing"))]
              (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
              (is (= 404 (:status (ex-data e))))
              (is (string? (:body (ex-data e))))
              (is (= (str "tools.agents.openai.files/" fname ": HTTP 404 No such File object: missing")
                     (ex-message e))))))
        (testing "ids are path-encoded"
          (reset! captured [])
          (files/files-retrieve c "a/b?c")
          ;; httpkit reports the raw path, the JDK server the decoded one;
          ;; either way `?` did not start a query string.
          (is (contains? #{"/v1/files/a%2Fb%3Fc" "/v1/files/a/b?c"} (:path (first @captured))))
          (is (nil? (:query (first @captured))))))
      (finally (stop!)))))

(deftest files-content-binary-round-trip
  (let [captured (atom nil)
        payload  (all-bytes)
        {:keys [port stop!]} (start-server! 19284 "/v1/files"
                                (fn [req] (reset! captured req)
                                  {:status 200 :headers {"content-type" "application/octet-stream"} :body payload}))]
    (try
      (let [out (files/files-content (client port) "file-abc")]
        (is (bytes? out))
        (is (= (vec payload) (vec out)))
        (is (= "/v1/files/file-abc/content" (:path @captured)))
        (is (= "GET" (:method @captured)))
        (is (= "application/binary" (get-in @captured [:headers "accept"]))))
      (finally (stop!)))))

(deftest files-wait-for-processing-polls-until-terminal
  (let [statuses (atom ["uploaded" "uploaded" "processed"])
        hits     (atom 0)
        {:keys [port stop!]} (start-server! 19285 "/v1/files"
                                (fn [_]
                                  (let [s (nth @statuses (min @hits (dec (count @statuses))))]
                                    (swap! hits inc)
                                    {:status 200 :body (str "{\"id\":\"file-abc\",\"status\":\"" s "\"}")})))]
    (try
      (let [c      (client port)
            sleeps (atom [])]
        (is (= "processed" (get (files/files-wait-for-processing
                                 c "file-abc" {:poll-interval-ms 7 :sleep-fn #(swap! sleeps conj %)})
                                "status")))
        (is (= 3 @hits))
        (is (= [7 7] @sleeps))
        (testing "gives up after max-wait with wait-timeout, retrieving at least twice"
          (reset! hits 0)
          (reset! statuses ["uploaded"])
          (let [clock (atom 0)
                e     (thrown #(files/files-wait-for-processing
                                c "file-abc" {:max-wait-ms 10 :sleep-fn (fn [_] (swap! clock + 11))
                                              :now-fn (fn [] @clock)}))]
            (is (= :tools.agents.openai/wait-timeout (:type (ex-data e))))
            (is (= 2 @hits))))
        (testing "error and deleted are terminal"
          (doseq [s ["error" "deleted"]]
            (reset! hits 0)
            (reset! statuses [s])
            (is (= s (get (files/files-wait-for-processing c "file-abc" {:sleep-fn (fn [_] (throw (Exception. "no sleep")))})
                          "status")))
            (is (= 1 @hits)))))
      (finally (stop!)))))
