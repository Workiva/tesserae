;; Copyright 2017-2019 Workiva Inc.
;;
;; Licensed under the Eclipse Public License 1.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://opensource.org/licenses/eclipse-1.0.php
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns tesserae.execution.models
  (:require [tesserae.execution :refer [attempt-fulfilment]]
            [tesserae.protocols :as p])
  (:import [clojure.lang IFn]
           [java.util.concurrent ExecutorService]))

;;;;;;;;;;;;;;;;;;;;;
;; EXECUTION MODEL ;;
;;;;;;;;;;;;;;;;;;;;;

;; There are generally three possible answers to "Who will do the
;; work?"
;;
;; (a) the thread that creates the tessera (guarantor/creator)
;; (b) the thread that consumes the tessera (redeemer)
;; (c) a third party
;;
;; Option (b) is usually called a "delay," option (c) usually called a
;; "future.". Option (a) would then be, I suppose, a "present" or
;; "imminent" by analogy. (?) Whatever.
;;
;; If we choose (c), it can be generally one of two things:
;; (1) A default "future" executor service.
;; (2) Some other explicit executor service.
;;
;; But when chaining, there is a third:
;; (3) whatever thread happens to fulfil the previous tessera in the
;; chain.
;;
;; But this third only works if the previous tessera has not yet been
;; fulfilled. So using this third option requires a fallback, either
;; (a), (b), or (c).

;; Stage: #{:redeem :inform :chain}

(def promise-model
  (reify p/ExecutionModel
    (execution [_] identity)
    (execution [_ _ _] nil)
    (execution [_ _ _ _] nil)))

(def future-model
  (reify
    p/ExecutionModel
    (execution [_]
      (fn [tessera]
        (future (attempt-fulfilment tessera))))
    (execution [model tessera stage]
      (p/execution model tessera stage nil))
    (execution [model tessera stage predecessor-status]
      (case stage
        :chain (case predecessor-status
                 :fulfilled (recur tessera :inform predecessor-status)
                 false)
        :inform (p/execution model)
        false))))

(def delay-model
  (reify
    p/ExecutionModel
    (execution [_]
      (fn [tessera]
        (attempt-fulfilment tessera)))
    (execution [model tessera stage]
      (p/execution model tessera stage nil))
    (execution [model tessera stage _]
      (case stage
        :redeem (p/execution model)
        false))))

(def now-model
  (reify
    p/ExecutionModel
    (execution [model]
      (fn [tessera]
        (attempt-fulfilment tessera)))
    (execution [model tessera stage]
      (p/execution model tessera stage nil))
    (execution [model tessera stage _]
      (case stage
        :chain (p/execution model)
        false))))

(extend-protocol p/ExecutionModel
  IFn
  (execution [f] (f))
  (execution [f tessera stage]
    (p/execution f tessera stage nil))
  (execution [f tessera stage predecessor-status]
    (f tessera stage predecessor-status))
  ExecutorService
  (execution [es]
    (fn [tessera]
      (.submit ^ExecutorService es
               ^Runnable #(attempt-fulfilment tessera))))
  (execution [es tessera stage]
    (p/execution model tessera stage nil))
  (execution [es tessera stage predecessor-status]
    (case stage
      :chain (case predecessor-status
               :fulfilled (recur es tessera :inform predecessor-status))
      :inform (p/execution es)
      false)))

(defn annexing-model
  [fallback]
  (reify
    p/ExecutionModel
    (execution [model]
      (fn [tessera]
        (attempt-fulfilment tessera)))
    (execution [model tessera stage]
      (p/execution model tessera stage nil))
    (execution [model tessera stage predecessor-status]
      (case stage
        ;; make the previous thread do it if possible:
        :inform (p/execution model)
        ;; otherwise use the fallback:
        (p/execution fallback tessera stage predecessor-status)))))

(def annex-or-future (annexing-model future-model))
(def annex-or-delay (annexing-model delay-model))
(def annex-or-now (annexing-model now-model))
