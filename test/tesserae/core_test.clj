(ns tesserae.core-test
  (:require [clojure.test :refer :all]
            [tesserae.core :as tess])
  (:import [tesserae.impl Tessera]
           [java.util.concurrent CancellationException]))

(deftest test:fulfilment-methods
  (let [p-1 (promise)
        p-2 (promise) 
        p-3 (tess/promise)
        p-4 (tess/promise)
        p-5 (tess/promise)]
    (deliver p-1 :value)
    (p-2 :value)
    (deliver p-3 :value)
    (tess/fulfil p-4 :value)
    (p-5 :value)
    (is (= :value @p-1 @p-2 @p-3 @p-4 @p-5))))

(deftest test:only-once-fulfilment
  (let [a (atom 0)
        p-1 (tess/promise)
        p-2 (tess/chain :future
                        p-1
                        (fn [v-1]
                          (swap! a inc)
                          (inc v-1)))]
    (tess/fulfil p-1 0)
    (is (= 0 @p-1))
    (is (= 1 @p-2 @a))
    (tess/fulfil p-1 1)
    (is (= 0 @p-1))
    (is (= 1 @p-2 @a))
    (is (thrown? Throwable (tess/fulfil p-2 4))) ;; <== wrong execution model
    (is (= 1 @p-2 @a))))

(deftest test:only-once-execution
  (testing "simple only-once-execution"
    (let [a (atom 0)
          p-1 (tess/promise)
          d (tess/chain :delay
                        p-1
                        (fn [v-1]
                          (swap! a inc)
                          (inc v-1)))
          results (vec (repeatedly 100 #(future (deref d))))]
      (is (= ::timed-out (deref (first results) 100 ::timed-out)))
      (tess/fulfil p-1 0)
      (is (= 0 @p-1))
      (is (apply = 1 @d @a (map deref results)))))
  (testing "chained only-once-execution"
    (let [a (atom 0)
          p-1 (tess/promise)
          d (tess/chain :delay
                        p-1
                        (fn [v-1]
                          (swap! a inc)
                          (inc v-1)))
          results (vec (repeatedly 100 #(tess/chain :future d inc)))]
      (is (= ::timed-out (deref (first results) 100 ::timed-out)))
      (tess/fulfil p-1 0)
      (is (= 0 @p-1))
      (is (every? #(= 2 %) (map deref results)))
      (is (= 1 @d @a)))))

(deftest test:chaining
  (let [p (tess/promise)
        chain (->> p
                   (iterate #(tess/chain :future % inc))
                   (take 10))]
    (is (every? false? (map tess/fulfilled? chain)))
    (tess/fulfil p 0)
    (tess/fulfil p 1) ;; should be noop
    (deliver p 2) ;; should be noop
    (p 3) ;; should be noop
    (is (= 9 @(last chain)))
    (is (every? true? (map tess/fulfilled? chain)))
    (is (= (range 10) (map tess/redeem chain)))))

(deftest test:fumbling
  (let [p (tess/promise)
        p-2 (tess/chain :future p inc)
        p-3 (tess/chain :future p-2 inc)]
    (tess/fulfil p "three")
    (identical? "three" @p)
    (is (thrown? Exception @p-2))
    (is (thrown? Exception @p-3))
    (is (identical? (:val (.state ^Tessera p-2))
                    (:val  (.state ^Tessera p-3))))))

(deftest test:watching-and-inspecting
  (let [watch-fn (fn [watch-val]
                   (fn [p]
                     (cond (tess/fumbled? p) (watch-val :fumbled)
                           (tess/fulfilled? p) (watch-val :realized)
                           (tess/revoked? p) (watch-val :revoked))))
        watch-realized (promise)
        watch-fumbled (promise)
        watch-revoked (promise)
        realized-p (tess/promise)
        fumbled-p (tess/future (Thread/sleep 500) (inc "hi there"))
        revoked-p (tess/promise)]
    (testing "watching a realized promise"
      (tess/watch realized-p (watch-fn watch-realized))
      (tess/fulfil realized-p :my-new-value)
      (is (= :realized @watch-realized)))
    (testing "watching a revoked promise"
      (tess/watch revoked-p (watch-fn watch-revoked))
      (tess/revoke revoked-p)
      (is (= :revoked @watch-revoked)))
    (testing "watching a fumbled promise"
      (tess/watch fumbled-p (watch-fn watch-fumbled))
      (is (= :fumbled @watch-fumbled)))))

(deftest test:revoking
  (testing "non-interrupting-revocations"
    (let [a (atom 0)
          p (tess/promise)
          p-2 (tess/chain :future p #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-3 (tess/chain :future p-2 #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-4a (tess/chain :future p-3 #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-4b (tess/chain :future p-3 #(do (Thread/sleep 100) (swap! a inc) (inc %)))]
      (is (= 0 @a))
      (tess/fulfil p 0)
      (is (= 0 @a))
      (Thread/sleep 110) ;; p-2 is fulfilled. p-3 starts executing.
      (is (= 1 @a))
      (tess/revoke-chain p-4a) ;; p-4a and p-3 and therefore p-4b are revoked
      (Thread/sleep 110) ;; p-3 finishes executing.
      (is (= 2 @a))
      (Thread/sleep 110) ;; p-4a and p-4b never begin executing.
      (is (= 2 @a))
      (are [x] (true? x)
        (not (tess/revoked? p))
        (not (tess/revoked? p-2))
        (tess/revoked? p-3)
        (tess/revoked? p-4a)
        (tess/revoked? p-4b))))
  (testing "interrupting-revocations"
    (let [a (atom 0)
          p (tess/promise)
          p-2 (tess/chain :future p #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-3 (tess/chain :future p-2 #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-4a (tess/chain :future p-3 #(do (Thread/sleep 100) (swap! a inc) (inc %)))
          p-4b (tess/chain :future p-3 #(do (Thread/sleep 100) (swap! a inc) (inc %)))]
      (is (= 0 @a))
      (tess/fulfil p 0)
      (is (= 0 @a))
      (Thread/sleep 110) ;; p-2 is fulfilled. p-3 starts executing.
      (is (= 1 @a))
      (tess/revoke-chain p-4a true) ;; p-4a and p-3 and therefore p-4b are revoked with may-interrupt? = true
      (Thread/sleep 110) ;; p-3 should be interrupted and not swap a's value.
      (is (= 1 @a))
      (Thread/sleep 110) ;; p-4a and p-4b should never begin executing.
      (is (= 1 @a))
      (are [x] (true? x)
        (not (tess/revoked? p))
        (not (tess/revoked? p-2))
        (tess/revoked? p-3)
        (tess/revoked? p-4a)
        (tess/revoked? p-4b))))
  (testing "branch vs. chain revocation behavior"
    (let [a (atom 0)
          p (tess/promise)
          c-1 (tess/chain :future p inc)
          c-2 (tess/chain :future c-1 inc)
          b-1 (tess/pipeline :future p #(do (swap! a inc) (inc %)))
          b-2 (tess/pipeline :future b-1 #(do (swap! a inc) (inc %)))
          b-3 (tess/chain :future b-1 #(do (swap! a inc) (inc %)))]
      (tess/revoke-chain b-2)
      (is (tess/revoked? b-2))
      (is (every? (complement tess/revoked?) [p c-1 c-2 b-1 b-3]))
      (tess/revoke-chain b-3)
      (is (tess/revoked? b-3))
      (is (tess/revoked? b-1))
      (is (every? (complement tess/revoked?) [p c-1 c-2]))
      (tess/fulfil p 0)
      (is (and (= 0 @p) (= 1 @c-1) (= 2 @c-2)))
      (is (every? tess/revoked? [b-1 b-2 b-3]))
      (is (= 0 @a)))))

(deftest test:chain
  (testing "simple chain execution model"
    (let [p (tess/promise)
          p-f (tess/chain :future p inc)
          a-1 (atom nil)
          p-f-c (tess/chain :annex-delay p-f #(do (reset! a-1 (Thread/currentThread)) (inc %)))
          a-2 (atom nil)
          p-c (tess/chain :annex-delay p #(do (reset! a-2 (Thread/currentThread)) (inc %)))]
      (tess/fulfil p 0)
      (is (identical? @a-2 (Thread/currentThread))) ;; this thread fulfilled p, so this thread fulfilled p-c.
      (is (and (= @p-f @p-c 1)
               (= @p-f-c 2)))
      (is (not (identical? @a-1 (Thread/currentThread)))) ;; a future fulfilled p-f, so it fulfilled p-f-c.
      (let [a-3 (atom nil)
            p-f-c-2 (tess/chain :annex-delay p-f #(do (reset! a-3 (Thread/currentThread)) (inc %)))]
        (is (= @p-f-c-2 2))
        ;; p-f was already fulfilled, so the thread that created p-f-c-2 (this one) also fulfilled it:
        (is (identical? @a-3 (Thread/currentThread)))))))

(deftest test:exceptions
  (testing "Throws CancellationException"
    (let [p (tess/promise)]
      (tess/revoke p)
      (is (thrown? CancellationException @p))))
  (testing "Throws InterruptedException"
    (let [thread (promise)
          f (tess/future (deliver thread (Thread/currentThread)) (Thread/sleep 100) 3)]
      (.interrupt ^Thread @thread)
      (is (thrown? InterruptedException @f)))))

;;; THE FOLLOWING FOUR TESTS COPIED FROM CLOJURE.CORE

(def stub-me :original)
(deftest copied-test-with-redefs-fn
  (let [p (tess/promise)]
    (with-redefs-fn {#'stub-me :temp}
      (fn []
        (.start (Thread. #(deliver p stub-me)))
        @p))
    (is (= :temp @p))
    (is (= :original stub-me))))

(deftest copied-test-with-redefs
  (let [p (tess/promise)]
    (with-redefs [stub-me :temp]
      (.start (Thread. #(deliver p stub-me)))
      @p)
    (is (= :temp @p))
    (is (= :original stub-me))))

(deftest copied-test-with-redefs-throw
  (let [p (tess/promise)]
    (is (thrown? Exception
                 (with-redefs [stub-me :temp]
                   (deliver p stub-me)
                   (throw (Exception. "simulated failure in with-redefs")))))
    (is (= :temp @p))
        (is (= :original stub-me))))

(def ^:dynamic *test-value* 1)
(deftest copied-future-fn-properly-retains-conveyed-bindings
  (let [a (atom [])]
    (binding [*test-value* 2]
      @(tess/future (try (dotimes [_ 3]
                           ;; we need some binding to trigger binding pop
                           (binding [*print-dup* false]
                             (swap! a conj *test-value*)))
                         (catch Throwable t (println t))))
      (is (= [2 2 2] @a)))))
