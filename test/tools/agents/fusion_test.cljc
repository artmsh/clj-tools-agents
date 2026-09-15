(ns tools.agents.fusion-test
  (:require [clojure.test :refer [deftest is]]
            [tools.agents.fusion :as fusion]))

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
