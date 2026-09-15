(ns tools.agents.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.retry :as retry]))

(defn- scripted
  "An :attempt fn that plays `steps` in order: a map is returned as the
   response, an exception is thrown. Records each prepared value it got."
  [steps calls]
  (let [q (atom steps)]
    (fn [prepared]
      (swap! calls conj prepared)
      (let [step (first @q)]
        (swap! q rest)
        (if (instance? Exception step) (throw step) step)))))

(defn- status-policy
  "A with-retries opts map over response maps {:status n}: 5xx and transport
   errors are retryable, the delay is retries-taken, sleeps are recorded."
  [steps calls slept & {:as extra}]
  (merge {:max-retries 2
          :attempt     (scripted steps calls)
          :retryable?  (fn [{:keys [error response]}] (or (some? error) (>= (:status response) 500)))
          :delay       :retries-taken
          :sleep!      #(swap! slept conj %)}
         extra))

(deftest returns-first-success-without-sleeping
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 200} (retry/with-retries (status-policy [{:status 200}] calls slept))))
    (is (= 1 (count @calls)))
    (is (= [] @slept))))

(deftest retries-within-budget-with-delay-per-retries-taken
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 200}
           (retry/with-retries (status-policy [{:status 500} {:status 503} {:status 200}] calls slept))))
    (is (= 3 (count @calls)))
    (is (= [0 1] @slept))))

(deftest budget-spent-hands-last-outcome-to-finish
  (let [calls (atom []) slept (atom []) seen (atom nil)]
    (is (= :gave-up
           (retry/with-retries
            (status-policy (repeat {:status 500}) calls slept
                           :finish (fn [o] (reset! seen o) :gave-up)))))
    (is (= 3 (count @calls)) "max-retries 2 -> 3 attempts")
    (is (= [0 1] @slept))
    (is (= {:response {:status 500} :retries-taken 2 :auth-retried? false :prepared nil}
           @seen))))

(deftest max-retries-zero-is-one-attempt
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 500}
           (retry/with-retries (status-policy [{:status 500}] calls slept :max-retries 0))))
    (is (= 1 (count @calls)))
    (is (= [] @slept))))

(deftest non-retryable-outcome-finishes-at-once
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 400} (retry/with-retries (status-policy [{:status 400} {:status 200}] calls slept))))
    (is (= 1 (count @calls)))
    (is (= [] @slept))))

(deftest caught-exception-becomes-error-outcome
  (testing "retried, then rethrown by the default finish"
    (let [calls (atom []) slept (atom []) boom (ex-info "down" {})
          e (try (retry/with-retries (status-policy [boom boom boom] calls slept)) nil
                 (catch Exception e e))]
      (is (identical? boom e))
      (is (= 3 (count @calls)))
      (is (= [0 1] @slept))))
  (testing "rethrow? propagates at once, no retry, no finish"
    (let [calls (atom []) slept (atom []) boom (ex-info "permanent" {:permanent true})
          e (try (retry/with-retries
                  (status-policy [boom {:status 200}] calls slept
                                 :rethrow? #(:permanent (ex-data %))
                                 :finish (fn [_] (throw (ex-info "finish must not run" {})))))
                 nil (catch Exception e e))]
      (is (identical? boom e))
      (is (= 1 (count @calls)))
      (is (= [] @slept)))))

(deftest prepare-runs-before-every-attempt-outside-the-try
  (testing "its value reaches attempt and the outcome"
    (let [calls (atom []) slept (atom [])]
      (retry/with-retries (status-policy [{:status 500} {:status 200}] calls slept
                                         :prepare (fn [n] (str "token-" n))))
      (is (= ["token-0" "token-1"] @calls))))
  (testing "it re-runs on the auth retry too, with retries-taken unchanged"
    (let [calls (atom []) slept (atom [])]
      (retry/with-retries (status-policy [{:status 401} {:status 200}] calls slept
                                         :prepare (fn [n] (str "token-" n))
                                         :unauthorized? #(= 401 (:status (:response %)))
                                         :on-unauthorized (constantly true)))
      (is (= ["token-0" "token-0"] @calls))
      (is (= [] @slept))))
  (testing "its exception propagates and is never retried"
    (let [calls (atom []) slept (atom []) boom (ex-info "fetch failed" {})
          e (try (retry/with-retries (status-policy [{:status 200}] calls slept
                                                    :prepare (fn [_] (throw boom))))
                 nil (catch Exception e e))]
      (is (identical? boom e))
      (is (= [] @calls))
      (is (= [] @slept)))))

(defn- auth-policy [steps calls slept invalidations & {:as extra}]
  (apply status-policy steps calls slept
         (mapcat identity
                 (merge {:unauthorized?   #(= 401 (:status (:response %)))
                         :on-unauthorized (fn [o] (swap! invalidations conj (:retries-taken o)) true)}
                        extra))))

(deftest first-401-retries-once-outside-budget-without-sleep
  (let [calls (atom []) slept (atom []) inv (atom [])]
    (is (= {:status 200}
           (retry/with-retries (auth-policy [{:status 401} {:status 200}] calls slept inv :max-retries 0))))
    (is (= 2 (count @calls)))
    (is (= [] @slept))
    (is (= [0] @inv))))

(deftest second-401-finishes-and-is-invalidated-only-on-request
  (testing "default: on-unauthorized runs on the first 401 only"
    (let [calls (atom []) slept (atom []) inv (atom [])]
      (is (= {:status 401}
             (retry/with-retries (auth-policy (repeat {:status 401}) calls slept inv))))
      (is (= 2 (count @calls)))
      (is (= [0] @inv))))
  (testing ":invalidate-every-401? runs it on the second 401 too, still no third attempt"
    (let [calls (atom []) slept (atom []) inv (atom [])]
      (is (= {:status 401}
             (retry/with-retries (auth-policy (repeat {:status 401}) calls slept inv
                                              :invalidate-every-401? true))))
      (is (= 2 (count @calls)))
      (is (= [0 0] @inv)))))

(deftest declined-401-falls-through-to-retryable
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 200}
           (retry/with-retries
            (status-policy [{:status 401} {:status 200}] calls slept
                           :unauthorized?   #(= 401 (:status (:response %)))
                           :on-unauthorized (constantly false)
                           :retryable?      #(= 401 (:status (:response %)))))))
    (is (= 2 (count @calls)))
    (is (= [0] @slept) "a budgeted retry, not the auth one")))

(deftest no-on-unauthorized-means-no-401-retry
  (let [calls (atom []) slept (atom [])]
    (is (= {:status 401}
           (retry/with-retries (status-policy [{:status 401} {:status 200}] calls slept
                                              :unauthorized? (constantly true)))))
    (is (= 1 (count @calls)))))

(deftest auth-retry-keeps-the-full-budget
  (let [calls (atom []) slept (atom []) inv (atom [])
        seen (atom nil)]
    (is (= {:status 200}
           (retry/with-retries
            (auth-policy [{:status 401} {:status 500} {:status 500} {:status 200}] calls slept inv
                         :finish (fn [o] (reset! seen o) (:response o))))))
    (is (= 4 (count @calls)))
    (is (= [0 1] @slept))
    (is (= {:retries-taken 2 :auth-retried? true} (select-keys @seen [:retries-taken :auth-retried?])))))

(deftest delay-value-is-passed-to-sleep-uninterpreted
  (let [calls (atom []) slept (atom [])]
    (retry/with-retries (status-policy [{:status 500} {:status 200}] calls slept
                                       :delay (constantly 7.5)))
    (is (= [7.5] @slept))))
