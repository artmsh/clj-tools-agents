(ns tools.agents.fusion-test
  (:require [clojure.test :refer [deftest is]]
            [tools.agents.fusion :as fusion]
            [tools.agents.json]))

(defn- index-of [xs target]
  (loop [i 0 xs xs]
    (if (seq xs)
      (if (= (first xs) target)
        i
        (recur (inc i) (rest xs)))
      -1)))

(deftest provider-call-normalizes-success
  (let [result (fusion/provider-call {:provider :openai
                                      :id :a
                                      :call (fn [_spec prompt]
                                              {:text (str "ok " prompt)
                                               :response {"id" "r1"}})}
                                     "hello")]
    (is (= :ok (:status result)))
    (is (= :a (:id result)))
    (is (= :openai (:provider result)))
    (is (= "ok hello" (:text result)))
    (is (= {"id" "r1"} (:response result)))))

(deftest provider-call-captures-errors
  (let [result (fusion/provider-call {:provider :anthropic
                                      :id :b
                                      :call (fn [_spec _prompt] (throw (ex-info "boom" {})))}
                                     "hello")]
    (is (= :error (:status result)))
    (is (= :b (:id result)))
    (is (= :anthropic (:provider result)))
    (is (some? (:error result)))))

(deftest provider-call-rejects-unsupported-provider
  (let [result (fusion/provider-call {:provider :not-a-real-provider :id :c} "hello")]
    (is (= :error (:status result)))
    (is (= :tools.agents.fusion/unsupported-provider (:type (ex-data (:error result)))))))

(deftest parallel-preserves-provider-order
  (let [seen (atom [])
        providers [{:provider :openai
                    :id :slow
                    :call (fn [_spec _prompt]
                            (swap! seen conj :slow-start)
                            (Thread/sleep 80)
                            (swap! seen conj :slow-end)
                            {:text "slow" :response {"id" "slow"}})}
                   {:provider :anthropic
                    :id :fast
                    :call (fn [_spec _prompt]
                            (swap! seen conj :fast-start)
                            {:text "fast" :response {"id" "fast"}})}]
        results (fusion/parallel providers "prompt")]
    (is (= [:slow :fast] (mapv :id results)))
    (is (= ["slow" "fast"] (mapv :text results)))
    (is (= #{:slow-start :fast-start :slow-end} (set (deref seen))))
    (is (= true
           (< (index-of (deref seen) :fast-start)
              (index-of (deref seen) :slow-end))))))

(deftest fuse-joins-successful-texts
  (let [providers [{:provider :openai
                    :id :a
                    :call (fn [_spec _prompt] {:text "first" :response {"id" "a"}})}
                   {:provider :anthropic
                    :id :b
                    :call (fn [_spec _prompt] (throw (ex-info "bad provider" {})))}
                   {:provider :openai
                    :id :c
                    :call (fn [_spec _prompt] {:text "third" :response {"id" "c"}})}]
        result (fusion/fuse providers "prompt")]
    (is (= :ok (:status result)))
    (is (= "first\nthird" (:answer result)))
    (is (= ["first" "third"] (:texts result)))
    (is (= [:a :b :c] (mapv :id (:results result))))))

(deftest provider-options-forwards-credential-source
  (let [src (Object.)]
    (is (identical? src (:credential-source (#'fusion/provider-options {:provider :openai :credential-source src}))))
    (is (not (contains? (#'fusion/provider-options {:provider :openai :api-key "k"}) :credential-source)))))

(deftest provider-http-is-forwarded-to-the-client
  (let [calls (atom [])
        http  (fn [req]
                (swap! calls conj (:url req))
                {:status 200 :headers {}
                 :body (if (clojure.string/includes? (:url req) "anthropic")
                         "{\"content\":[{\"type\":\"text\",\"text\":\"from-a\"}]}"
                         "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"from-o\"}]}]}")})
        result (fusion/fuse [{:provider :openai :id :o :model "m" :api-key "k"
                              :base-url "https://openai.fake/v1" :http http}
                             {:provider :anthropic :id :a :model "m" :api-key "k"
                              :base-url "https://anthropic.fake" :http http}]
                            "prompt")]
    (is (= :ok (:status result)))
    (is (= #{"from-o" "from-a"} (set (:texts result))))
    (is (= #{"https://openai.fake/v1/responses" "https://anthropic.fake/v1/messages"} (set @calls)))))

(deftest provider-json-is-forwarded-to-the-client
  (let [writes (atom 0)
        codec  {:read  tools.agents.json/read-json
                :write (fn [v] (swap! writes inc) (tools.agents.json/write-json v))}
        http   (fn [_] {:status 200 :headers {}
                        :body "{\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}"})
        result (fusion/provider-call {:provider :anthropic :model "m" :api-key "k" :http http :json codec} "p")]
    (is (= "a" (:text result)))
    (is (= 1 @writes))))

(deftest provider-timeouts-are-forwarded-to-the-client
  (let [seen (atom [])
        http (fn [req] (swap! seen conj ((juxt :timeout-ms :connect-timeout-ms) req))
               {:status 200 :headers {} :body "{\"content\":[{\"type\":\"text\",\"text\":\"a\"}]}"})]
    (fusion/provider-call {:provider :anthropic :model "m" :api-key "k" :http http} "p")
    (fusion/provider-call {:provider :anthropic :model "m" :api-key "k" :http http
                           :timeout-ms 1234 :connect-timeout-ms nil} "p")
    (is (= [[600000 5000] [1234 nil]] @seen))))
