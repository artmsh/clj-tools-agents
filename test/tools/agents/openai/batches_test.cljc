(ns tools.agents.openai.batches-test
  "tools.agents.openai.batches: pure JSONL building plus mock-server round
   trips (shared tools.agents.test-support server; base-url carries /v1).
   Ports 19320-19329."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.batches :as batches]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- client [port] (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0}))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(defn- query-set [q] (when q (set (str/split q #"&"))))

;; ---------------------------------------------------------------------------
;; Pure
;; ---------------------------------------------------------------------------

(deftest batch-input-jsonl-builds-one-line-per-request
  (testing "requests are encoded verbatim, newline-separated, trailing newline"
    (let [s (batches/batch-input-jsonl
             [{"custom_id" "a" "method" "POST" "url" "/v1/responses" "body" {"model" "m" "input" "line1\nline2"}}
              {:custom_id "b" :method "POST" :url "/v1/responses" :body {:model "m"}}])]
      (is (str/ends-with? s "\n"))
      (is (= 2 (count (str/split-lines s))) "an embedded newline is escaped, never raw")
      (is (= [{"custom_id" "a" "method" "POST" "url" "/v1/responses" "body" {"model" "m" "input" "line1\nline2"}}
              {"custom_id" "b" "method" "POST" "url" "/v1/responses" "body" {"model" "m"}}]
             (vec (oai/read-jsonl s))))))
  (testing "the url arity fills method and url, keeping explicit ones"
    (is (= [{"custom_id" "a" "body" {} "method" "POST" "url" "/v1/embeddings"}
            {"custom_id" "b" "method" "GET" "url" "/v1/other"}]
           (vec (oai/read-jsonl (batches/batch-input-jsonl
                                 "/v1/embeddings"
                                 [{"custom_id" "a" "body" {}}
                                  {"custom_id" "b" "method" "GET" "url" "/v1/other"}]))))))
  (testing "no requests → empty string"
    (is (= "" (batches/batch-input-jsonl []))))
  (testing "custom_id must be a unique non-empty string"
    (doseq [[reqs msg] [[[{"body" {}}] "needs a non-empty string \"custom_id\""]
                        [[{"custom_id" ""}] "needs a non-empty string \"custom_id\""]
                        [[{"custom_id" 1}] "needs a non-empty string \"custom_id\""]
                        [[{"custom_id" "x"} {:custom_id "x"}] "duplicate \"custom_id\" \"x\""]]]
      (let [e (thrown #(batches/batch-input-jsonl "/v1/responses" reqs))]
        (is (= :tools.agents.openai/invalid-request (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) "tools.agents.openai.batches/batch-input-jsonl: "))
        (is (str/includes? (ex-message e) msg))))))

(deftest endpoints-lists-the-sdk-literal
  (is (= 8 (count batches/endpoints)))
  (is (contains? batches/endpoints "/v1/videos")))

(deftest empty-batch-id-is-invalid-request-before-io
  (let [c (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1" :max-retries 0})]
    (doseq [[fname f] [["batches-retrieve" batches/batches-retrieve]
                       ["batches-cancel" batches/batches-cancel]
                       ["batches-results" batches/batches-results]]
            id [nil ""]]
      (let [e (thrown #(f c id))]
        (is (= :tools.agents.openai/invalid-request (:type (ex-data e))))
        (is (str/starts-with? (ex-message e) (str "tools.agents.openai.batches/" fname ": ")))))))

;; ---------------------------------------------------------------------------
;; Mock server: endpoints
;; ---------------------------------------------------------------------------

(def ^:private batch-json
  "{\"id\":\"batch_1\",\"object\":\"batch\",\"endpoint\":\"/v1/responses\",\"status\":\"validating\",\"input_file_id\":\"file-in\",\"completion_window\":\"24h\"}")

(deftest batches-create-sends-json-body
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 19320 "/v1/batches"
                                (fn [req] (reset! captured req) {:status 200 :body batch-json}))]
    (try
      (let [req  {"input_file_id" "file-in" "endpoint" "/v1/responses" "completion_window" "24h"
                  "metadata" {"job" "nightly"}
                  "output_expires_after" {"anchor" "created_at" "seconds" 86400}}
            resp (batches/batches-create (client port) req)
            {:keys [method path query headers body]} @captured]
        (is (= "batch_1" (get resp "id")))
        (is (= ["POST" "/v1/batches" nil] [method path query]))
        (is (= "Bearer k" (get headers "authorization")))
        (is (= "application/json" (get headers "content-type")))
        (is (= req (oai/read-json body)) "output_expires_after stays a nested JSON object"))
      (finally (stop!)))))

(deftest batches-retrieve-cancel-and-404-typing
  (let [captured (atom [])
        {:keys [port stop!]} (start-server! 19321 "/v1/batches"
                                (fn [req]
                                  (swap! captured conj req)
                                  (if (str/includes? (:path req) "missing")
                                    {:status 404 :body "{\"error\":{\"message\":\"No batch found with id 'missing'.\",\"type\":\"invalid_request_error\"}}"}
                                    {:status 200 :body (if (str/ends-with? (:path req) "/cancel")
                                                         (str/replace batch-json "validating" "cancelling")
                                                         batch-json)})))]
    (try
      (let [c (client port)]
        (is (= "validating" (get (batches/batches-retrieve c "batch_1") "status")))
        (is (= "cancelling" (get (batches/batches-cancel c "batch_1") "status")))
        (is (= [["GET" "/v1/batches/batch_1" ""] ["POST" "/v1/batches/batch_1/cancel" ""]]
               (mapv (juxt :method :path :body) @captured)))
        (testing "404 → not-found-error"
          (doseq [[fname f] [["batches-retrieve" batches/batches-retrieve]
                             ["batches-cancel" batches/batches-cancel]]]
            (let [e (thrown #(f c "missing"))]
              (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
              (is (= 404 (:status (ex-data e))))
              (is (= (str "tools.agents.openai.batches/" fname ": HTTP 404 No batch found with id 'missing'.")
                     (ex-message e))))))
        (testing "ids are path-encoded"
          (reset! captured [])
          (batches/batches-retrieve c "a/b?c")
          (is (contains? #{"/v1/batches/a%2Fb%3Fc" "/v1/batches/a/b?c"} (:path (first @captured))))
          (is (nil? (:query (first @captured))))))
      (finally (stop!)))))

(deftest batches-list-query-and-cursor-paging
  (let [captured (atom [])
        pages    {nil      "{\"object\":\"list\",\"data\":[{\"id\":\"batch_1\"},{\"id\":\"batch_2\"}],\"first_id\":\"batch_1\",\"last_id\":\"batch_2\",\"has_more\":true}"
                  "batch_2" "{\"object\":\"list\",\"data\":[{\"id\":\"batch_3\"}],\"first_id\":\"batch_3\",\"last_id\":\"batch_3\",\"has_more\":false}"}
        {:keys [port stop!]} (start-server! 19322 "/v1/batches"
                                (fn [req]
                                  (swap! captured conj req)
                                  (let [after (some #(second (re-find #"^after=(.*)$" %)) (query-set (:query req)))]
                                    {:status 200 :body (get pages after)})))]
    (try
      (let [c   (client port)
            ids (loop [params {"limit" 2 "unset" nil} acc []]
                  (let [page (batches/batches-list c params)
                        acc  (into acc (map #(get % "id")) (get page "data"))]
                    (if (get page "has_more")
                      (recur (assoc params "after" (get page "last_id")) acc)
                      acc)))]
        (is (= ["batch_1" "batch_2" "batch_3"] ids))
        (is (= [["GET" "/v1/batches" #{"limit=2"}]
                ["GET" "/v1/batches" #{"limit=2" "after=batch_2"}]]
               (mapv (juxt :method :path (comp query-set :query)) @captured)))
        (reset! captured [])
        (batches/batches-list c)
        (is (nil? (:query (first @captured)))))
      (finally (stop!)))))

;; ---------------------------------------------------------------------------
;; Mock server: batches-results
;; ---------------------------------------------------------------------------

(defn- ok-line [cid n]
  (str "{\"id\":\"batch_req_" cid "\",\"custom_id\":\"" cid "\",\"response\":{\"status_code\":200,\"request_id\":\"r" n
       "\",\"body\":{\"n\":" n "}},\"error\":null}"))

(defn- err-line [cid]
  (str "{\"id\":\"batch_req_" cid "\",\"custom_id\":\"" cid "\",\"response\":{\"status_code\":400,\"request_id\":\"rx\","
       "\"body\":{\"error\":{\"message\":\"bad\"}}},\"error\":null}"))

(defn- results-server
  "Serves GET /v1/batches/{id} and GET /v1/files/{id}/content from `files`
   ({file-id body}); records every request."
  [port batch files]
  (let [captured (atom [])
        srv      (start-server! port "/v1"
                   (fn [req]
                     (swap! captured conj req)
                     (let [path (:path req)]
                       (cond
                         (str/starts-with? path "/v1/batches/")
                         {:status 200 :body (oai/write-json batch)}

                         :else
                         (let [fid (second (re-find #"^/v1/files/([^/]+)/content$" path))]
                           (if-let [body (get files fid)]
                             {:status 200 :headers {"content-type" "application/octet-stream"}
                              :body (.getBytes ^String body "UTF-8")}
                             {:status 404 :body "{\"error\":{\"message\":\"No such File object\"}}"}))))))]
    (assoc srv :captured captured)))

(deftest batches-results-keys-shuffled-lines-by-custom-id
  (let [cids     (mapv #(str "req-" %) (range 20))
        shuffled (shuffle (map-indexed (fn [n cid] [cid n]) cids))
        out-body (str (str/join "\r\n" (map (fn [[cid n]] (ok-line cid n)) shuffled)) "\r\n\r\n")
        batch    {"id" "batch_1" "status" "completed" "output_file_id" "file-out" "error_file_id" "file-err"}
        {:keys [port stop! captured]} (results-server 19323 batch {"file-out" out-body
                                                                   "file-err" (str (err-line "req-bad") "\n")})]
    (try
      (let [c   (client port)
            res (batches/batches-results c batch)]
        (is (= (set cids) (set (keys res))))
        (doseq [[n cid] (map-indexed vector cids)]
          (is (= n (get-in res [cid "response" "body" "n"])) (str cid " matched by custom_id, not position")))
        (is (= [["GET" "/v1/files/file-out/content"]] (mapv (juxt :method :path) @captured)))
        (is (= "application/binary" (get-in (first @captured) [:headers "accept"])))
        (testing ":errors? merges the error file"
          (reset! captured [])
          (let [res (batches/batches-results c batch {:errors? true})]
            (is (= 21 (count res)))
            (is (= 400 (get-in res ["req-bad" "response" "status_code"])))
            (is (= ["/v1/files/file-out/content" "/v1/files/file-err/content"] (mapv :path @captured)))))
        (testing "a batch id is retrieved first"
          (reset! captured [])
          (is (= 20 (count (batches/batches-results c "batch_1"))))
          (is (= ["/v1/batches/batch_1" "/v1/files/file-out/content"] (mapv :path @captured)))))
      (finally (stop!)))))

(deftest batches-results-error-cases
  (testing "no output_file_id → invalid-request before any download"
    (let [{:keys [port stop! captured]} (results-server 19324 nil {"file-err" (str (err-line "a") "\n")})]
      (try
        (let [c (client port)
              e (thrown #(batches/batches-results c {"id" "batch_1" "status" "in_progress" "output_file_id" nil}))]
          (is (= :tools.agents.openai/invalid-request (:type (ex-data e))))
          (is (= "tools.agents.openai.batches/batches-results: batch \"batch_1\" (status \"in_progress\") has no \"output_file_id\""
                 (ex-message e)))
          (is (empty? @captured))
          (testing "every request failed: only the error file, with :errors?"
            (is (= ["a"] (keys (batches/batches-results c {"id" "b" "error_file_id" "file-err"} {:errors? true})))))
          (testing "neither file id with :errors?"
            (is (= :tools.agents.openai/invalid-request
                   (:type (ex-data (thrown #(batches/batches-results c {"id" "b"} {:errors? true})))))))
          (testing "a missing file → not-found-error from files-content"
            (let [e (thrown #(batches/batches-results c {"id" "b" "output_file_id" "gone"}))]
              (is (= :tools.agents.openai/not-found-error (:type (ex-data e))))
              (is (str/starts-with? (ex-message e) "tools.agents.openai.files/files-content: ")))))
        (finally (stop!)))))
  (testing "malformed line, missing custom_id, duplicate custom_id"
    (let [{:keys [port stop!]} (results-server 19325 nil
                                               {"bad-json" (str (ok-line "a" 1) "\n\n{not json}\n")
                                                "no-cid"   "{\"id\":\"x\",\"response\":null}\n"
                                                "dup-out"  (str (ok-line "a" 1) "\n")
                                                "dup-err"  (str (err-line "a") "\n")})]
      (try
        (let [c (client port)
              e (thrown #(batches/batches-results c {"id" "b" "output_file_id" "bad-json"}))]
          (is (= :tools.agents.openai/json-parse-error (:type (ex-data e))))
          (is (= 3 (:line (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.openai/read-jsonl: line 3: "))
          (let [e (thrown #(batches/batches-results c {"id" "b" "output_file_id" "no-cid"}))]
            (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))
            (is (str/includes? (ex-message e) "without a string \"custom_id\"")))
          (let [e (thrown #(batches/batches-results c {"id" "b" "output_file_id" "dup-out" "error_file_id" "dup-err"}
                                                    {:errors? true}))]
            (is (= :tools.agents.openai/invalid-response (:type (ex-data e))))
            (is (str/includes? (ex-message e) "duplicate \"custom_id\" \"a\""))))
        (finally (stop!))))))
