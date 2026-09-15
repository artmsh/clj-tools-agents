(ns tools.agents.anthropic.batches-test
  "tools.agents.anthropic.batches against the shared mock server: method,
   path, query, body, headers, paging, JSONL results and error typing —
   identical assertions on both runtimes. Ends with one REAL-API round trip,
   skipped unless ANTHROPIC_LIVE=1 and ANTHROPIC_API_KEY are both set."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.anthropic :as a]
            [tools.agents.anthropic.batches :as b]
            [tools.agents.test-support :refer [start-server! rotating-token-cache]]))

(def ^:private route "/v1/messages/batches")

(defn- batch-json [id status]
  (str "{\"id\":\"" id "\",\"type\":\"message_batch\",\"processing_status\":\"" status "\","
       "\"created_at\":\"2024-08-20T18:37:24.100435Z\",\"expires_at\":\"2024-08-21T18:37:24.100435Z\","
       "\"request_counts\":{\"canceled\":0,\"errored\":0,\"expired\":0,\"processing\":1,\"succeeded\":0},"
       "\"results_url\":null}"))

(defn- with-mock
  "Start the mock on port with handler, call (f client captured-atom), stop.
   captured holds every request, in order."
  ([port handler f] (with-mock port {} handler f))
  ([port client-opts handler f]
   (let [captured (atom [])
         {:keys [stop!]} (start-server! port route (fn [req] (swap! captured conj req) (handler req)))]
     (try
       (f (a/client (merge {:api-key "test-key" :base-url (str "http://127.0.0.1:" port)} client-opts))
          captured)
       (finally (stop!))))))

(defn- ex-of [thunk] (try (thunk) nil (catch Exception e e)))

;; ---------------------------------------------------------------------------
;; Endpoints: method, path, body, headers
;; ---------------------------------------------------------------------------

(deftest create-posts-requests-body-with-standard-headers
  (with-mock 19400
    (fn [_] {:status 200 :headers {"request-id" "req_b1"} :body (batch-json "msgbatch_1" "in_progress")})
    (fn [client captured]
      (let [request {"requests" [{"custom_id" "a"
                                  "params" {"model" "claude-haiku-4-5" "max_tokens" 1
                                            "messages" [{"role" "user" "content" "hi"}]}}]}
            resp    (b/batches-create client request)
            req     (first @captured)]
        (is (= "msgbatch_1" (get resp "id")))
        (is (= "in_progress" (get resp "processing_status")))
        (is (= "req_b1" (a/request-id resp)))
        (is (= "POST" (:method req)))
        (is (= "/v1/messages/batches" (:path req)))
        (is (nil? (:query req)))
        (is (= request (a/read-json (:body req))) "body is the request map, verbatim")
        (is (= "test-key" (get-in req [:headers "x-api-key"])))
        (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
        (is (= "application/json" (get-in req [:headers "content-type"])))
        (is (nil? (get-in req [:headers "anthropic-beta"])) "batches are GA: the SDK sends no beta header")))))

(deftest retrieve-gets-batch-by-id
  (with-mock 19401
    (fn [_] {:status 200 :body (batch-json "msgbatch_2" "ended")})
    (fn [client captured]
      (is (= "ended" (get (b/batches-retrieve client "msgbatch_2") "processing_status")))
      (let [req (first @captured)]
        (is (= "GET" (:method req)))
        (is (= "/v1/messages/batches/msgbatch_2" (:path req)))
        (is (= "" (:body req)))
        (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))))))

(deftest id-is-url-encoded-into-the-path
  (with-mock 19402
    (fn [_] {:status 200 :body (batch-json "x" "ended")})
    (fn [client captured]
      (b/batches-retrieve client "a/b?c d")
      (let [req (first @captured)]
        ;; httpkit reports the raw path, the JDK server the decoded one
        (is (contains? #{"/v1/messages/batches/a%2Fb%3Fc%20d" "/v1/messages/batches/a/b?c d"} (:path req)))
        (is (nil? (:query req)) "a ? in the id never starts a query string")))))

(deftest empty-or-non-string-id-throws-before-any-request
  (with-mock 19403
    (fn [_] {:status 200 :body "{}"})
    (fn [client captured]
      (doseq [[f id] [[b/batches-retrieve ""] [b/batches-delete ""] [b/batches-cancel nil]
                      [b/batches-results ""] [b/batches-retrieve 42]]]
        (let [e (ex-of #(f client id))]
          (is (= :tools.agents.anthropic.error/invalid-argument (:type (ex-data e))))
          (is (str/starts-with? (ex-message e) "tools.agents.anthropic.batches/batches-"))))
      (is (empty? @captured)))))

(deftest cancel-posts-without-body
  (with-mock 19404
    (fn [_] {:status 200 :body (batch-json "msgbatch_3" "canceling")})
    (fn [client captured]
      (is (= "canceling" (get (b/batches-cancel client "msgbatch_3") "processing_status")))
      (let [req (first @captured)]
        (is (= "POST" (:method req)))
        (is (= "/v1/messages/batches/msgbatch_3/cancel" (:path req)))
        (is (= "" (:body req)))))))

(deftest delete-uses-http-delete
  (with-mock 19405
    (fn [_] {:status 200 :body "{\"id\":\"msgbatch_4\",\"type\":\"message_batch_deleted\"}"})
    (fn [client captured]
      (is (= {"id" "msgbatch_4" "type" "message_batch_deleted"} (b/batches-delete client "msgbatch_4")))
      (let [req (first @captured)]
        (is (= "DELETE" (:method req)))
        (is (= "/v1/messages/batches/msgbatch_4" (:path req)))))))

(deftest client-betas-and-auth-token-still-apply
  (with-mock 19406 {:api-key nil :auth-token "tok" :betas ["x-beta"]}
    (fn [_] {:status 200 :body (batch-json "b" "ended")})
    (fn [client captured]
      (b/batches-retrieve client "b")
      (let [h (:headers (first @captured))]
        (is (= "Bearer tok" (get h "authorization")))
        (is (= "oauth-2025-04-20,x-beta" (get h "anthropic-beta")))))))

(deftest credential-source-401-invalidates-and-retries-once-through-request!
  (let [{:keys [source fetches]} (rotating-token-cache)
        hits (atom 0)]
    (with-mock 19419 {:api-key nil :credential-source source :max-retries 0}
      (fn [_] (if (= 1 (swap! hits inc))
                {:status 401 :body "{\"error\":{\"message\":\"expired\"}}"}
                {:status 200 :body (batch-json "b" "ended")}))
      (fn [client captured]
        (is (= "b" (get (b/batches-retrieve client "b") "id")))
        (is (= ["Bearer tok-1" "Bearer tok-2"] (mapv #(get (:headers %) "authorization") @captured)))
        (is (= 2 @fetches))))))

;; ---------------------------------------------------------------------------
;; List + paging
;; ---------------------------------------------------------------------------

(defn- query-map [q]
  (into {} (for [kv (when q (str/split q #"&"))] (vec (str/split kv #"=" 2)))))

(deftest list-sends-query-params-and-returns-page-verbatim
  (with-mock 19407
    (fn [_] {:status 200
             :body (str "{\"data\":[" (batch-json "b1" "ended") "],\"has_more\":true,"
                        "\"first_id\":\"b1\",\"last_id\":\"b1\"}")})
    (fn [client captured]
      (let [page (b/batches-list client {"limit" 1 "after_id" "b0"})]
        (is (= ["b1"] (map #(get % "id") (get page "data"))))
        (is (true? (get page "has_more")))
        (is (= "b1" (get page "first_id")))
        (is (= "b1" (get page "last_id"))))
      (b/batches-list client)
      (let [[r1 r2] @captured]
        (is (= "GET" (:method r1)))
        (is (= "/v1/messages/batches" (:path r1)))
        (is (= {"limit" "1" "after_id" "b0"} (query-map (:query r1))))
        (is (nil? (:query r2)) "no params -> no query string")))))

(deftest next-page-params-mirrors-sdk-sync-page
  (testing "forward: after_id <- last_id, other params carried"
    (is (= {"limit" 2 "after_id" "b2"}
           (b/next-page-params {"limit" 2 "after_id" "b0"} {"has_more" true "first_id" "b1" "last_id" "b2"}))))
  (testing "backward when before_id was given: before_id <- first_id"
    (is (= {"before_id" "b1"}
           (b/next-page-params {"before_id" "b9"} {"has_more" true "first_id" "b1" "last_id" "b2"}))))
  (testing "has_more false or missing cursor ends paging; absent has_more does not"
    (is (nil? (b/next-page-params nil {"has_more" false "last_id" "b2"})))
    (is (nil? (b/next-page-params nil {"has_more" true "last_id" nil})))
    (is (= {"after_id" "b2"} (b/next-page-params nil {"last_id" "b2"})))))

(deftest list-all-pages-lazily-forward
  (let [pages {nil  ["b1" "b2"]
               "b2" ["b3" "b4"]
               "b4" ["b5"]}]
    (with-mock 19408
      (fn [req]
        (let [after (get (query-map (:query req)) "after_id")
              ids   (get pages after)]
          {:status 200
           :body (str "{\"data\":[" (str/join "," (map #(batch-json % "ended") ids)) "],"
                      "\"has_more\":" (if (= after "b4") "false" "true") ","
                      "\"first_id\":\"" (first ids) "\",\"last_id\":\"" (last ids) "\"}")}))
      (fn [client captured]
        (let [s (b/batches-list-all client {"limit" 2})]
          (is (empty? @captured) "nothing fetched until realized")
          (is (= ["b1" "b2"] (map #(get % "id") (take 2 s))))
          (is (= 1 (count @captured)) "second page not fetched for the first two items")
          (is (= ["b1" "b2" "b3" "b4" "b5"] (map #(get % "id") s)))
          (is (= [{"limit" "2"} {"limit" "2" "after_id" "b2"} {"limit" "2" "after_id" "b4"}]
                 (map (comp query-map :query) @captured))))))))

(deftest list-all-pages-backward-from-before-id
  (with-mock 19409
    (fn [req]
      (let [before (get (query-map (:query req)) "before_id")]
        {:status 200
         :body (if (= before "b9")
                 (str "{\"data\":[" (batch-json "b8" "ended") "],\"has_more\":true,\"first_id\":\"b8\",\"last_id\":\"b8\"}")
                 (str "{\"data\":[" (batch-json "b7" "ended") "],\"has_more\":false,\"first_id\":\"b7\",\"last_id\":\"b7\"}"))}))
    (fn [client captured]
      (is (= ["b8" "b7"] (map #(get % "id") (b/batches-list-all client {"before_id" "b9"}))))
      (is (= [{"before_id" "b9"} {"before_id" "b8"}] (map (comp query-map :query) @captured))))))

;; ---------------------------------------------------------------------------
;; Results (JSONL)
;; ---------------------------------------------------------------------------

(def ^:private results-jsonl
  (str "{\"custom_id\":\"a\",\"result\":{\"type\":\"succeeded\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
       "\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}}\n"
       "\n"
       "{\"custom_id\":\"b\",\"result\":{\"type\":\"errored\",\"error\":{\"type\":\"error\","
       "\"error\":{\"type\":\"invalid_request_error\",\"message\":\"max_tokens: required\"},\"request_id\":null}}}\r\n"
       "{\"custom_id\":\"c\",\"result\":{\"type\":\"canceled\"}}\n"
       "{\"custom_id\":\"d\",\"result\":{\"type\":\"expired\"}}"))

(deftest results-decodes-jsonl-lines-including-errored
  (with-mock 19410
    (fn [_] {:status 200 :headers {"content-type" "application/binary" "request-id" "req_r1"}
             :body results-jsonl})
    (fn [client captured]
      (let [results (b/batches-results client "msgbatch_5")
            by-id   (into {} (map (juxt #(get % "custom_id") identity)) results)]
        (is (= ["a" "b" "c" "d"] (map #(get % "custom_id") results)))
        (is (= "hi" (a/output-text (get-in by-id ["a" "result" "message"]))))
        (is (= "errored" (get-in by-id ["b" "result" "type"])))
        (is (= "invalid_request_error" (get-in by-id ["b" "result" "error" "error" "type"])))
        (is (= "canceled" (get-in by-id ["c" "result" "type"])))
        (is (= "expired" (get-in by-id ["d" "result" "type"])))
        (is (= "req_r1" (a/request-id results))))
      (let [req (first @captured)]
        (is (= 1 (count @captured)) "no pre-flight retrieve")
        (is (= "GET" (:method req)))
        (is (= "/v1/messages/batches/msgbatch_5/results" (:path req)))
        (is (= "application/binary" (get-in req [:headers "accept"])))))))

(deftest results-empty-body-is-empty-seq
  (with-mock 19411
    (fn [_] {:status 200 :body ""})
    (fn [client _] (is (= [] (vec (b/batches-results client "msgbatch_6")))))))

(deftest results-malformed-line-throws-typed-parse-error-when-realized
  (with-mock 19412
    (fn [_] {:status 200 :body "{\"custom_id\":\"a\",\"result\":{\"type\":\"canceled\"}}\n{not json\n"})
    (fn [client _]
      (let [results (b/batches-results client "msgbatch_7")
            e       (ex-of #(doall results))]
        (is (= "a" (get (first results) "custom_id")) "lines before the bad one decode")
        (is (= :tools.agents.anthropic.error/json-parse (:type (ex-data e))))
        (is (= 2 (:line (ex-data e))))
        (is (str/starts-with? (ex-message e) "tools.agents.anthropic/read-jsonl: line 2: "))))))

;; ---------------------------------------------------------------------------
;; Error typing and retries
;; ---------------------------------------------------------------------------

(deftest not-found-is-typed-with-api-message
  (with-mock 19413
    (fn [_] {:status 404 :body "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"batch not found\"}}"})
    (fn [client captured]
      (doseq [[fname f] [["batches-retrieve" #(b/batches-retrieve client "nope")]
                         ["batches-results" #(b/batches-results client "nope")]]]
        (let [e (ex-of f)]
          (is (= :tools.agents.anthropic.error/not-found (:type (ex-data e))))
          (is (= 404 (:status (ex-data e))))
          (is (= (str "tools.agents.anthropic.batches/" fname ": HTTP 404 batch not found") (ex-message e)))))
      (is (= 2 (count @captured)) "404 is not retried"))))

(deftest conflict-409-is-api-status-and-retried
  (with-mock 19414 {:max-retries 0}
    (fn [_] {:status 409 :body "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"batch still processing\"}}"})
    (fn [client captured]
      (let [e (ex-of #(b/batches-delete client "msgbatch_8"))]
        (is (= :tools.agents.anthropic.error/api-status (:type (ex-data e))))
        (is (= 409 (:status (ex-data e))))
        (is (str/includes? (ex-message e) "batch still processing")))
      (is (= 1 (count @captured)))))
  (let [n (atom 0)]
    (binding [a/*sleep-fn* (fn [_] nil)]
      (with-mock 19415
        (fn [_] (if (= 1 (swap! n inc))
                  {:status 409 :body "{}"}
                  {:status 200 :body (batch-json "msgbatch_9" "canceling")}))
        (fn [client captured]
          (is (= "canceling" (get (b/batches-cancel client "msgbatch_9") "processing_status")))
          (is (= ["POST" "POST"] (map :method @captured)) "retried with the same method"))))))

;; ---------------------------------------------------------------------------
;; REAL-API round trip: create -> retrieve -> list -> cancel -> poll -> delete.
;; Skipped (one passing assertion) unless ANTHROPIC_LIVE=1 AND
;; ANTHROPIC_API_KEY are set, so a key merely present in the environment never
;; makes the regular suite hit the network. COST: one max_tokens=1 request on
;; ANTHROPIC_BATCH_MODEL (default claude-haiku-4-5), billed at batch (50%)
;; rates only if it is processed before the cancel lands — normally nothing.
;; Delete needs processing to have ended; if the batch is still "canceling"
;; after the poll budget, the delete step is skipped rather than failed.
;; ---------------------------------------------------------------------------

(deftest batches-round-trip-against-the-real-api
  (if-let [api-key (and (= "1" (System/getenv "ANTHROPIC_LIVE"))
                        (not-empty (System/getenv "ANTHROPIC_API_KEY")))]
    (let [client  (a/client {:api-key api-key
                             :base-url (or (not-empty (System/getenv "ANTHROPIC_BATCH_BASE_URL"))
                                           a/default-base-url)})
          model   (or (not-empty (System/getenv "ANTHROPIC_BATCH_MODEL")) "claude-haiku-4-5")
          created (b/batches-create client {"requests" [{"custom_id" "live-1"
                                                         "params" {"model" model "max_tokens" 1
                                                                   "messages" [{"role" "user" "content" "hi"}]}}]})
          id      (get created "id")]
      (is (string? id))
      (is (= "message_batch" (get created "type")))
      (is (= id (get (b/batches-retrieve client id) "id")))
      (is (some #(= id (get % "id")) (get (b/batches-list client {"limit" 20}) "data")))
      (let [canceled (b/batches-cancel client id)]
        (is (contains? #{"canceling" "ended"} (get canceled "processing_status"))))
      (let [final (loop [i 0]
                    (let [s (b/batches-retrieve client id)]
                      (if (or (= "ended" (get s "processing_status")) (>= i 30))
                        s
                        (do (Thread/sleep 2000) (recur (inc i))))))]
        (if (= "ended" (get final "processing_status"))
          (do
            (is (every? #(contains? % "result") (b/batches-results client id)))
            (is (= {"id" id "type" "message_batch_deleted"} (b/batches-delete client id))))
          (is true (str "skipped delete: batch " id " still " (get final "processing_status"))))))
    (is true "skipped: set ANTHROPIC_LIVE=1 and ANTHROPIC_API_KEY")))
