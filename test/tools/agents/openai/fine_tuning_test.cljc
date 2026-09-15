(ns tools.agents.openai.fine-tuning-test
  "tools.agents.openai.fine-tuning: id validation plus mock-server round trips
   for every endpoint (shared tools.agents.test-support server; base-url
   carries /v1). Ports 19340-19349."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.fine-tuning :as ft]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(defn- client
  ([port] (client port "k"))
  ([port api-key] (oai/client {:api-key api-key :base-url (base-url port) :max-retries 0})))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(defn- query-set
  "The query as a set of decoded `name=value` pairs: httpkit reports the raw
   query, the JDK server a decoded one."
  [q]
  (when q (set (map #(java.net.URLDecoder/decode ^String % "UTF-8") (str/split q #"&")))))

(def ^:private job-json
  "{\"id\":\"ftjob-1\",\"object\":\"fine_tuning.job\",\"model\":\"gpt-4o-mini-2024-07-18\",\"status\":\"running\"}")

(def ^:private checkpoint "ft:gpt-4o-mini-2024-07-18:org:custom:ckpt-step-100")

;; ---------------------------------------------------------------------------
;; Pure: id validation
;; ---------------------------------------------------------------------------

(deftest empty-ids-are-invalid-request-before-io
  (let [c (oai/client {:api-key "k" :base-url "http://127.0.0.1:1/v1" :max-retries 0})]
    (doseq [[fname arg f] [["jobs-retrieve" "fine-tuning-job-id" #(ft/jobs-retrieve c %)]
                           ["jobs-cancel" "fine-tuning-job-id" #(ft/jobs-cancel c %)]
                           ["jobs-pause" "fine-tuning-job-id" #(ft/jobs-pause c %)]
                           ["jobs-resume" "fine-tuning-job-id" #(ft/jobs-resume c %)]
                           ["jobs-list-events" "fine-tuning-job-id" #(ft/jobs-list-events c %)]
                           ["jobs-checkpoints-list" "fine-tuning-job-id" #(ft/jobs-checkpoints-list c % {"limit" 1})]
                           ["checkpoints-permissions-create" "fine-tuned-model-checkpoint"
                            #(ft/checkpoints-permissions-create c % {"project_ids" ["p"]})]
                           ["checkpoints-permissions-list" "fine-tuned-model-checkpoint" #(ft/checkpoints-permissions-list c %)]
                           ["checkpoints-permissions-delete" "fine-tuned-model-checkpoint"
                            #(ft/checkpoints-permissions-delete c % "cp_1")]
                           ["checkpoints-permissions-delete" "permission-id"
                            #(ft/checkpoints-permissions-delete c checkpoint %)]]
            id [nil ""]]
      (let [e (thrown #(f id))]
        (is (= :tools.agents.openai/invalid-request (:type (ex-data e))) fname)
        (is (str/starts-with? (ex-message e) (str "tools.agents.openai.fine-tuning/" fname ": expected a non-empty " arg)))))
    (testing "checkpoint is validated before permission id, as in the SDK"
      (is (str/includes? (ex-message (thrown #(ft/checkpoints-permissions-delete c "" ""))) "fine-tuned-model-checkpoint")))))

;; ---------------------------------------------------------------------------
;; Mock server
;; ---------------------------------------------------------------------------

(defn- recording-server [port handler]
  (let [captured (atom [])
        srv      (start-server! port "/v1/fine_tuning"
                   (fn [req] (swap! captured conj req) (handler req)))]
    (assoc srv :captured captured)))

(deftest every-endpoint-method-path-query-body
  (let [{:keys [port stop! captured]} (recording-server 19340 (fn [_] {:status 200 :body job-json}))]
    (try
      (let [c      (client port)
            create {"model" "gpt-4o-mini-2024-07-18" "training_file" "file-train" "validation_file" "file-val"
                    "method" {"type" "supervised" "supervised" {"hyperparameters" {"n_epochs" 3}}}
                    "integrations" [{"type" "wandb" "wandb" {"project" "p"}}]
                    "metadata" {"team" "ml"} "seed" 42 "suffix" "custom"}
            cases  [[#(ft/jobs-create c create) "POST" "/v1/fine_tuning/jobs" nil create]
                    [#(ft/jobs-retrieve c "ftjob-1") "GET" "/v1/fine_tuning/jobs/ftjob-1" nil nil]
                    [#(ft/jobs-list c) "GET" "/v1/fine_tuning/jobs" nil nil]
                    [#(ft/jobs-list c {"after" "ftjob-0" "limit" 5 "metadata" {"team" "ml" "env" "prod"} "unset" nil})
                     "GET" "/v1/fine_tuning/jobs" #{"after=ftjob-0" "limit=5" "metadata[team]=ml" "metadata[env]=prod"} nil]
                    [#(ft/jobs-list c {"metadata" "null"}) "GET" "/v1/fine_tuning/jobs" #{"metadata=null"} nil]
                    [#(ft/jobs-cancel c "ftjob-1") "POST" "/v1/fine_tuning/jobs/ftjob-1/cancel" nil nil]
                    [#(ft/jobs-pause c "ftjob-1") "POST" "/v1/fine_tuning/jobs/ftjob-1/pause" nil nil]
                    [#(ft/jobs-resume c "ftjob-1") "POST" "/v1/fine_tuning/jobs/ftjob-1/resume" nil nil]
                    [#(ft/jobs-list-events c "ftjob-1") "GET" "/v1/fine_tuning/jobs/ftjob-1/events" nil nil]
                    [#(ft/jobs-list-events c "ftjob-1" {"after" "ftevent-9" "limit" 2})
                     "GET" "/v1/fine_tuning/jobs/ftjob-1/events" #{"after=ftevent-9" "limit=2"} nil]
                    [#(ft/jobs-checkpoints-list c "ftjob-1" {"limit" 10})
                     "GET" "/v1/fine_tuning/jobs/ftjob-1/checkpoints" #{"limit=10"} nil]
                    [#(ft/checkpoints-permissions-create c checkpoint {"project_ids" ["proj_a" "proj_b"]})
                     "POST" (str "/v1/fine_tuning/checkpoints/" checkpoint "/permissions") nil {"project_ids" ["proj_a" "proj_b"]}]
                    [#(ft/checkpoints-permissions-list c checkpoint {"order" "descending" "project_id" "proj_a" "limit" 3 "after" "cp_0"})
                     "GET" (str "/v1/fine_tuning/checkpoints/" checkpoint "/permissions")
                     #{"order=descending" "project_id=proj_a" "limit=3" "after=cp_0"} nil]
                    [#(ft/checkpoints-permissions-delete c checkpoint "cp_1")
                     "DELETE" (str "/v1/fine_tuning/checkpoints/" checkpoint "/permissions/cp_1") nil nil]]]
        (doseq [[f method path query body] cases]
          (reset! captured [])
          (is (= "running" (get (f) "status")))
          (let [req (first @captured)]
            (is (= 1 (count @captured)))
            (is (= [method path query] [(:method req) (:path req) (query-set (:query req))]) path)
            (is (= "Bearer k" (get-in req [:headers "authorization"])))
            (if body
              (is (= body (oai/read-json (:body req))) path)
              (is (= "" (:body req)) path)))))
      (finally (stop!)))))

(deftest checkpoint-ids-keep-colons-and-encode-unsafe-chars
  (let [{:keys [port stop! captured]} (recording-server 19341 (fn [_] {:status 200 :body "{}"}))]
    (try
      (let [c (client port)]
        (ft/checkpoints-permissions-list c checkpoint)
        (testing "the SDK's path_template leaves ':' literal (both servers report it verbatim)"
          (is (= (str "/v1/fine_tuning/checkpoints/" checkpoint "/permissions") (:path (first @captured)))))
        (reset! captured [])
        (ft/jobs-retrieve c "a/b?c d")
        (is (contains? #{"/v1/fine_tuning/jobs/a%2Fb%3Fc%20d" "/v1/fine_tuning/jobs/a/b?c d"} (:path (first @captured))))
        (is (nil? (:query (first @captured)))))
      (finally (stop!)))))

(deftest admin-key-is-passed-as-api-key
  (let [{:keys [port stop! captured]} (recording-server 19342
                                        (fn [req]
                                          (if (= "Bearer sk-admin" (get-in req [:headers "authorization"]))
                                            {:status 200 :body "{\"id\":\"cp_1\",\"object\":\"checkpoint.permission\",\"deleted\":true}"}
                                            {:status 403 :body "{\"error\":{\"message\":\"You have insufficient permissions for this operation.\"}}"})))]
    (try
      (is (= true (get (ft/checkpoints-permissions-delete (client port "sk-admin") checkpoint "cp_1") "deleted")))
      (let [e (thrown #(ft/checkpoints-permissions-delete (client port "sk-proj") checkpoint "cp_1"))]
        (is (= :tools.agents.openai/permission-denied-error (:type (ex-data e))))
        (is (= "tools.agents.openai.fine-tuning/checkpoints-permissions-delete: HTTP 403 You have insufficient permissions for this operation."
               (ex-message e))))
      (is (= ["Bearer sk-admin" "Bearer sk-proj"] (mapv #(get-in % [:headers "authorization"]) @captured)))
      (finally (stop!)))))

(deftest jobs-404-typing
  (let [{:keys [port stop!]} (recording-server 19343
                               (fn [_] {:status 404 :body "{\"error\":{\"message\":\"Could not find fine-tune job: ftjob-x\"}}"}))]
    (try
      (let [c (client port)]
        (doseq [[fname f] [["jobs-retrieve" ft/jobs-retrieve]
                           ["jobs-cancel" ft/jobs-cancel]
                           ["jobs-pause" ft/jobs-pause]
                           ["jobs-resume" ft/jobs-resume]
                           ["jobs-list-events" ft/jobs-list-events]
                           ["jobs-checkpoints-list" ft/jobs-checkpoints-list]]]
          (let [e (thrown #(f c "ftjob-x"))]
            (is (= :tools.agents.openai/not-found-error (:type (ex-data e))) fname)
            (is (= 404 (:status (ex-data e))))
            (is (= (str "tools.agents.openai.fine-tuning/" fname ": HTTP 404 Could not find fine-tune job: ftjob-x")
                   (ex-message e))))))
      (finally (stop!)))))

(deftest jobs-list-events-cursor-paging
  (let [pages {nil        "{\"object\":\"list\",\"data\":[{\"id\":\"ev3\"},{\"id\":\"ev2\"}],\"has_more\":true}"
               "ev2"      "{\"object\":\"list\",\"data\":[{\"id\":\"ev1\"}],\"has_more\":false}"}
        {:keys [port stop! captured]} (recording-server 19344
                                        (fn [req]
                                          (let [after (some #(second (re-find #"^after=(.*)$" %)) (query-set (:query req)))]
                                            {:status 200 :body (get pages after)})))]
    (try
      (let [c   (client port)
            ids (loop [params {"limit" 2} acc []]
                  (let [page (ft/jobs-list-events c "ftjob-1" params)
                        acc  (into acc (map #(get % "id")) (get page "data"))]
                    (if (get page "has_more")
                      (recur (assoc params "after" (get (peek (get page "data")) "id")) acc)
                      acc)))]
        (is (= ["ev3" "ev2" "ev1"] ids))
        (is (= [#{"limit=2"} #{"limit=2" "after=ev2"}] (mapv (comp query-set :query) @captured))))
      (finally (stop!)))))
