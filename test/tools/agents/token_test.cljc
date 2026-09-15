(ns tools.agents.token-test
  "tools.agents.token/token-cache: expiry, skew, single-flight, failure
   propagation and recovery, invalidation. A fake clock drives expiry; the
   concurrency tests block fetch! on a latch so every caller is waiting on
   the same in-flight fetch."
  (:require [clojure.test :refer [deftest is testing]]
            [tools.agents.token :as token]))

(defn- counting-fetch
  "fetch! returning \"t1\", \"t2\", ... with expires-at (f n) and a call counter."
  [calls expires-at-fn]
  (fn []
    (let [n (swap! calls inc)]
      {:token (str "t" n) :expires-at (expires-at-fn n)})))

(deftest token-source-predicate
  (is (token/token-source? (token/token-cache {:fetch! (fn [] {:token "x"})})))
  (is (not (token/token-source? "static-key")))
  (is (not (token/token-source? {:token "x"}))))

(deftest caches-until-refresh-point
  (let [now   (atom 0)
        calls (atom 0)
        c     (token/token-cache {:fetch! (counting-fetch calls (fn [_] (+ @now 100000)))
                                  :refresh-skew-ms 10000
                                  :now-ms #(deref now)})]
    (is (= "t1" (token/token! c)))
    (is (= "t1" (token/token! c)))
    (is (= 1 @calls) "no fetch at construction, one on first use, none while fresh")
    (testing "still cached just before expires-at - skew"
      (reset! now 89999)
      (is (= "t1" (token/token! c)))
      (is (= 1 @calls)))
    (testing "refreshed inside the skew window, before expiry"
      (reset! now 90000)
      (is (= "t2" (token/token! c)))
      (is (= 2 @calls)))))

(deftest refreshes-after-expiry-with-zero-skew
  (let [now   (atom 0)
        calls (atom 0)
        c     (token/token-cache {:fetch! (counting-fetch calls (fn [_] (+ @now 1000)))
                                  :refresh-skew-ms 0
                                  :now-ms #(deref now)})]
    (is (= "t1" (token/token! c)))
    (reset! now 999)
    (is (= "t1" (token/token! c)))
    (reset! now 1000)
    (is (= "t2" (token/token! c)))
    (is (= 2 @calls))))

(deftest skew-capped-at-half-the-lifetime
  (let [now   (atom 0)
        calls (atom 0)
        ;; 10 s token, 60 s skew: without the cap it would refetch every call.
        c     (token/token-cache {:fetch! (counting-fetch calls (fn [_] (+ @now 10000)))
                                  :refresh-skew-ms 60000
                                  :now-ms #(deref now)})]
    (is (= "t1" (token/token! c)))
    (reset! now 4999)
    (is (= "t1" (token/token! c)))
    (reset! now 5000)
    (is (= "t2" (token/token! c)))))

(deftest nil-expiry-caches-until-invalidated
  (let [now   (atom 0)
        calls (atom 0)
        c     (token/token-cache {:fetch! (counting-fetch calls (constantly nil)) :now-ms #(deref now)})]
    (is (= "t1" (token/token! c)))
    (reset! now Long/MAX_VALUE)
    (is (= "t1" (token/token! c)))
    (is (true? (token/invalidate! c)))
    (is (= "t2" (token/token! c)))
    (is (= 2 @calls))))

(deftest invalidate-compare-and-clear
  (let [calls (atom 0)
        c     (token/token-cache {:fetch! (counting-fetch calls (constantly nil))})]
    (is (= "t1" (token/token! c)))
    (testing "a stale used token does not clear a newer cached one"
      (is (true? (token/invalidate! c "t1")))
      (is (= "t2" (token/token! c)))
      (is (true? (token/invalidate! c "t1")) "still true: a retry gets the newer token")
      (is (= "t2" (token/token! c)))
      (is (= 2 @calls)))
    (testing "the matching used token clears"
      (token/invalidate! c "t2")
      (is (= "t3" (token/token! c))))))

(deftest single-flight-under-concurrency
  (let [n       16
        calls   (atom 0)
        release (promise)
        c       (token/token-cache {:fetch! (fn []
                                              (swap! calls inc)
                                              @release
                                              {:token "shared" :expires-at nil})})
        futs    (doall (repeatedly n #(future (token/token! c))))]
    ;; Let every future reach token! and block on the one in-flight fetch.
    (Thread/sleep 200)
    (deliver release true)
    (is (= (repeat n "shared") (map #(deref % 5000 :timeout) futs)))
    (is (= 1 @calls))))

(deftest failed-refresh-propagates-to-all-waiters-then-recovers
  (let [n        8
        calls    (atom 0)
        release  (promise)
        fail?    (atom true)
        c        (token/token-cache {:fetch! (fn []
                                               (swap! calls inc)
                                               @release
                                               (if @fail?
                                                 (throw (ex-info "exchange failed" {:type ::boom}))
                                                 {:token "ok" :expires-at nil}))})
        futs     (doall (repeatedly n #(future (try (token/token! c)
                                                    (catch Exception e (:type (ex-data e)))))))]
    (Thread/sleep 200)
    (deliver release true)
    (is (= (repeat n ::boom) (map #(deref % 5000 :timeout) futs)))
    (is (= 1 @calls) "one failed fetch shared by every waiter")
    (testing "the failure does not poison later calls"
      (reset! fail? false)
      (is (= "ok" (token/token! c)))
      (is (= 2 @calls)))))

(deftest failed-fetch-single-caller-rethrows-original
  (let [c (token/token-cache {:fetch! (fn [] (throw (java.io.IOException. "down")))})]
    (is (thrown-with-msg? java.io.IOException #"down" (token/token! c)))
    (is (thrown-with-msg? java.io.IOException #"down" (token/token! c)))))

(deftest malformed-fetch-result-is-typed-and-redacted
  (doseq [bad [nil "tok" {:token ""} {:token 42} {:token "secret-tok" :expires-at "soon"}]]
    (let [c (token/token-cache {:fetch! (constantly bad)})
          e (try (token/token! c) nil (catch Exception e e))]
      (is (= :tools.agents.token/invalid-fetch-result (:type (ex-data e))) (pr-str bad))
      (is (not (re-find #"secret-tok" (str (ex-message e) (pr-str (ex-data e)))))))))

(deftest options-validated
  (is (= :tools.agents.token/invalid-options
         (:type (ex-data (try (token/token-cache {}) (catch Exception e e))))))
  (is (= :tools.agents.token/invalid-options
         (:type (ex-data (try (token/token-cache {:fetch! (fn []) :refresh-skew-ms -1})
                              (catch Exception e e)))))))
