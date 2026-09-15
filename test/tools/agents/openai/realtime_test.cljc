(ns tools.agents.openai.realtime-test
  "Mock-server coverage for tools.agents.openai.realtime (shared
   tools.agents.test-support server; base-url carries /v1)."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [tools.agents.openai :as oai]
            [tools.agents.openai.realtime :as rt]
            [tools.agents.test-support :refer [start-server!]]))

(defn- base-url [port] (str "http://127.0.0.1:" port "/v1"))

(deftest client-secrets-create-posts-json-passthrough
  (let [captured (atom nil)
        {:keys [port stop!]} (start-server! 0 "/v1/realtime/client_secrets"
                                (fn [req]
                                  (reset! captured req)
                                  {:status 200
                                   :body (str "{\"value\":\"ek_123\",\"expires_at\":1756310470,"
                                              "\"session\":{\"type\":\"realtime\",\"model\":\"gpt-realtime\"}}")}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            resp   (rt/realtime-client-secrets-create
                     client {"expires_after" {"anchor" "created_at" "seconds" 600}
                             "session" {"type" "realtime" "model" "gpt-realtime"}})]
        (is (= "ek_123" (get resp "value")))
        (is (= "gpt-realtime" (get-in resp ["session" "model"])))
        (is (= "POST" (:method @captured)))
        (is (= "/v1/realtime/client_secrets" (:path @captured)))
        (is (= "Bearer k" (get (:headers @captured) "authorization")))
        (is (= {"expires_after" {"anchor" "created_at" "seconds" 600}
                "session" {"type" "realtime" "model" "gpt-realtime"}}
               (oai/read-json (:body @captured))))
        (rt/realtime-client-secrets-create client)
        (is (= "{}" (:body @captured))))
      (finally (stop!)))))

(deftest client-secrets-create-typed-http-error
  (let [{:keys [port stop!]} (start-server! 0 "/v1/realtime/client_secrets"
                                (fn [_] {:status 401 :body "{\"error\":{\"message\":\"bad key\"}}"}))]
    (try
      (let [client (oai/client {:api-key "k" :base-url (base-url port) :max-retries 0})
            e      (try (rt/realtime-client-secrets-create client {}) nil (catch Exception e e))]
        (is (= :tools.agents.openai/authentication-error (:type (ex-data e))))
        (is (= 401 (:status (ex-data e))))
        (is (str/starts-with? (ex-message e) "tools.agents.openai/realtime-client-secrets-create: HTTP 401 bad key")))
      (finally (stop!)))))
