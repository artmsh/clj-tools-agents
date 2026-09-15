(ns tools.agents.http-test
  "tools.agents.http: pure encoders plus request! against the shared mock
   server, identical on both runtimes.

   Port range 19100-19139 — clear of the provider suites' ranges
   (anthropic 18930-18975, openai 18950-18971, gemini 18980-18997,
   openai.agents 19000-19079)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.http :as http]
            [tools.agents.test-support :refer [start-server!]]))

(defn- url [port path] (str "http://127.0.0.1:" port path))

(defn- utf8-str [^bytes bs] (String. bs "UTF-8"))

(defn- concat-bytes ^bytes [chunks]
  (let [out (java.io.ByteArrayOutputStream.)]
    (doseq [c chunks]
      (let [^bytes b (if (instance? java.io.File c)
                       (java.nio.file.Files/readAllBytes (.toPath ^java.io.File c))
                       c)]
        (.write out b 0 (alength b))))
    (.toByteArray out)))

(defn- all-bytes ^bytes []
  (byte-array (map unchecked-byte (range 256))))

;; ---------------------------------------------------------------------------
;; Pure: params
;; ---------------------------------------------------------------------------

(deftest flatten-params-uses-bracket-nesting
  (is (= [["metadata[k]" "v"] ["metadata[n][x]" "1"]]
         (http/flatten-params {"metadata" (array-map "k" "v" "n" {"x" 1})})))
  (is (= [["image[]" "a.png"] ["image[]" "b.png"]]
         (http/flatten-params {"image" ["a.png" "b.png"]})))
  (is (= [["order" "desc"] ["stream" "false"]]
         (http/flatten-params (array-map :order :desc "stream" false))))
  (testing "nil and empty values are dropped, as in openai-python"
    (is (= [] (http/flatten-params {"after" nil "q" ""})))))

(deftest encode-params-url-encodes-names-and-values
  (is (= "metadata%5Bk%5D=a+b&image%5B%5D=x%2Fy"
         (http/encode-params (array-map "metadata" {"k" "a b"} "image" ["x/y"]))))
  (is (nil? (http/encode-params {})))
  (is (nil? (http/encode-params {"after" nil}))))

;; ---------------------------------------------------------------------------
;; Pure: multipart
;; ---------------------------------------------------------------------------

(deftest multipart-chunks-golden-bytes
  (let [chunks (http/multipart-chunks
                [{:name "purpose" :content "fine-tune"}
                 {:name "expires_after[anchor]" :content "created_at"}
                 {:name "file" :content (.getBytes "{}\n" "UTF-8") :filename "ä \"q\".jsonl"
                  :content-type "application/jsonl"}]
                "BOUND")]
    (is (= 1 (count chunks)) "adjacent in-memory parts merge into one chunk")
    (is (= (str "--BOUND\r\n"
                "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                "fine-tune\r\n"
                "--BOUND\r\n"
                "Content-Disposition: form-data; name=\"expires_after[anchor]\"\r\n\r\n"
                "created_at\r\n"
                "--BOUND\r\n"
                "Content-Disposition: form-data; name=\"file\"; filename=\"ä %22q%22.jsonl\"\r\n"
                "Content-Type: application/jsonl\r\n\r\n"
                "{}\n\r\n"
                "--BOUND--\r\n")
           (utf8-str (first chunks))))))

(deftest multipart-chunks-keep-files-as-file-objects
  (let [f      (doto (java.io.File/createTempFile "tools-agents-http" ".bin") (.deleteOnExit))
        chunks (http/multipart-chunks [{:name "file" :content f}] "B")]
    (is (= 3 (count chunks)))
    (is (identical? f (second chunks)) "the file is streamed, not read into memory")
    (is (str/includes? (utf8-str (first chunks))
                       (str "filename=\"" (.getName f) "\"\r\nContent-Type: application/octet-stream")))
    (is (= "\r\n--B--\r\n" (utf8-str (nth chunks 2))))))

(deftest multipart-and-body-are-exclusive
  (let [e (try (http/request! {:method :post :url "http://127.0.0.1:1/" :body "x"
                               :multipart [{:name "a" :content "b"}]})
               nil
               (catch Exception e e))]
    (is (= :tools.agents.http/invalid-request (:type (ex-data e))))))

(deftest normalize-headers-lower-cases-and-vectors-repeats
  (is (= {"retry-after" "3" "x-dup" ["a" "b" "c"]}
         (http/normalize-headers {"Retry-After" ["3"] "X-Dup" ["a" "b"] "x-dup" ["c"]}))))

;; ---------------------------------------------------------------------------
;; request! against the mock server
;; ---------------------------------------------------------------------------

(deftest request-sends-every-method-with-body-query-and-headers
  (let [seen (atom [])
        {:keys [port stop!]} (start-server! 19100 "/echo"
                               (fn [req] (swap! seen conj req) {:status 200 :body (:method req)}))]
    (try
      (doseq [m [:get :post :put :patch :delete]]
        (let [body (when (#{:post :put :patch} m) (str "{\"m\":\"" (name m) "\"}"))
              resp (http/request! {:method m :url (url port "/echo") :query {"limit" 2}
                                   :headers {"x-test" "yes" :x-kw "k"} :body body})
              req  (peek @seen)]
          (is (= 200 (:status resp)))
          (is (= (str/upper-case (name m)) (:body resp) (:method req)))
          (is (= "/echo" (:path req)))
          (is (= "limit=2" (:query req)))
          (is (= "yes" (get-in req [:headers "x-test"])))
          (is (= "k" (get-in req [:headers "x-kw"])))
          (is (= (or body "") (:body req)))))
      (finally (stop!)))))

(deftest request-query-appends-to-an-existing-query-string
  (let [seen (atom nil)
        {:keys [port stop!]} (start-server! 19101 "/q"
                               (fn [req] (reset! seen req) {:status 200 :body ""}))]
    (try
      (http/request! {:method :get :url (url port "/q?a=1") :query {"b" ["x"]}})
      (is (= #{"a=1" "b[]=x"}
             (set (map #(java.net.URLDecoder/decode ^String % "UTF-8") (str/split (:query @seen) #"&")))))
      (finally (stop!)))))

(deftest response-headers-are-lower-cased-and-repeats-are-vectors
  (let [{:keys [port stop!]} (start-server! 19102 "/h"
                               (fn [_] {:status 429 :headers {"Retry-After" "30" "X-Dup" ["30" "60"]} :body "no"}))]
    (try
      (let [resp (http/request! {:method :post :url (url port "/h") :body "{}"})]
        (is (= 429 (:status resp)) "a non-2xx status is returned, not thrown")
        (is (= "no" (:body resp)))
        (is (= "30" (get-in resp [:headers "retry-after"])))
        (is (= ["30" "60"] (get-in resp [:headers "x-dup"])))
        (is (every? #(= % (str/lower-case %)) (keys (:headers resp)))))
      (finally (stop!)))))

(deftest bytes-round-trip-untouched
  (let [seen (atom nil)
        {:keys [port stop!]} (start-server! 19103 "/bin"
                               (fn [req] (reset! seen req) {:status 200 :body (all-bytes)}))]
    (try
      (let [resp (http/request! {:method :post :url (url port "/bin") :body (all-bytes) :as :bytes})]
        (is (bytes? (:body resp)))
        (is (= (seq (all-bytes)) (seq (:body resp))))
        (is (= (seq (all-bytes)) (seq (:body-bytes @seen)))))
      (finally (stop!)))))

(deftest multipart-request-sends-the-encoder-bytes-with-content-length
  (let [seen (atom nil)
        f    (doto (java.io.File/createTempFile "tools-agents-http" ".bin") (.deleteOnExit))
        _    (java.nio.file.Files/write (.toPath f) (all-bytes)
                                        ^"[Ljava.nio.file.OpenOption;" (into-array java.nio.file.OpenOption []))
        parts [{:name "purpose" :content "batch"}
               {:name "image[]" :content "one"}
               {:name "image[]" :content "two"}
               {:name "file" :content f :filename "données.bin"}]
        {:keys [port stop!]} (start-server! 19104 "/upload"
                               (fn [req] (reset! seen req) {:status 200 :body "ok"}))]
    (try
      (binding [http/*boundary-fn* (constantly "fixed-boundary")]
        (let [resp (http/request! {:method :post :url (url port "/upload") :multipart parts})]
          (is (= "ok" (:body resp)))
          (is (= "multipart/form-data; boundary=fixed-boundary" (get-in @seen [:headers "content-type"])))
          (is (= (str (alength (concat-bytes (http/multipart-chunks parts "fixed-boundary"))))
                 (get-in @seen [:headers "content-length"]))
              "a File part keeps a known Content-Length (BodyPublishers/concat, not chunked)")
          (is (= (seq (concat-bytes (http/multipart-chunks parts "fixed-boundary")))
                 (seq (:body-bytes @seen))))
          (is (str/includes? (:body @seen) "filename=\"données.bin\""))))
      (testing "a caller-supplied content-type wins"
        (http/request! {:method :post :url (url port "/upload") :multipart parts
                        :headers {"Content-Type" "multipart/mixed; boundary=mine"}})
        (is (= "multipart/mixed; boundary=mine" (get-in @seen [:headers "content-type"]))))
      (finally (stop!)))))

(deftest stream-delivers-chunks-before-the-response-ends
  (let [release (promise)
        {:keys [port stop!]} (start-server! 19105 "/sse"
                               (fn [_] {:status 200 :headers {"content-type" "text/event-stream"}
                                        :body (fn [send!]
                                                (send! "data: 1\n\n")
                                                (deref release 5000 nil)
                                                (send! "data: 2\n\n"))}))]
    (try
      (let [resp (http/request! {:method :get :url (url port "/sse") :as :stream})]
        (is (= 200 (:status resp)))
        (is (= "text/event-stream" (get-in resp [:headers "content-type"])))
        (with-open [rdr (java.io.BufferedReader.
                         (java.io.InputStreamReader. ^java.io.InputStream (:body resp) "UTF-8"))]
          (is (= "data: 1" (.readLine rdr))
              "first event is readable while the server is still blocked before the second")
          (is (= "" (.readLine rdr)))
          (deliver release true)
          (is (= "data: 2" (.readLine rdr)))
          (is (= "" (.readLine rdr)))
          (is (nil? (.readLine rdr)))))
      (finally (deliver release true) (stop!)))))

(deftest timeout-ms-throws-http-timeout-exception
  (let [{:keys [port stop!]} (start-server! 19106 "/slow"
                               (fn [_] (Thread/sleep 1500) {:status 200 :body "late"}))]
    (try
      (let [e (try (http/request! {:method :get :url (url port "/slow") :timeout-ms 200}) nil
                   (catch Exception e e))]
        (is (instance? java.net.http.HttpTimeoutException e)))
      (finally (stop!)))))

(deftest transport-failure-throws-unwrapped
  (let [e (try (http/request! {:method :post :url "http://127.0.0.1:1/" :body "{}"}) nil
               (catch Exception e e))]
    (is (instance? java.io.IOException e))
    (is (nil? (ex-data e)))))

(deftest explicit-client-is-used
  (let [{:keys [port stop!]} (start-server! 19107 "/c" (fn [_] {:status 200 :body "ok"}))]
    (try
      (is (= "ok" (:body (http/request! {:method :get :url (url port "/c")
                                         :client (http/client {:connect-timeout-ms 1000})}))))
      (finally (stop!)))))
