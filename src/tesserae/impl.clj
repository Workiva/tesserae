;; Copyright 2017-2018 Workiva Inc.
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

(ns tesserae.impl
  (:require [tesserae.protocols :refer :all :exclude [FormaTesserae ExecutionModel]]
            [tesserae.watch :as watch]
            [tesserae.execution :as ex]
            [tesserae.execution.models :as ex-m]
            [tesserae.utils :refer [vcas!]]
            [clojure.tools.logging :as log])
  (:import [tesserae.protocols FormaTesserae ExecutionModel]
           [tesserae CancellationExceptionInfo]
           [clojure.lang IFn]
           [java.util.concurrent CompletionStage ExecutorService CountDownLatch TimeUnit ExecutionException TimeoutException]
           [java.lang Runnable]
           [java.lang.ref WeakReference]
           [java.io Writer])
  (:refer-clojure :exclude [await]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TESSERA COORDINATED STATE ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Basically, the coordinated state consists of tessera status:
;;    - :pending / :fumbled / :fulfilled / :revoked / :ready
;; plus any observer state tightly coordinated with that status:
;;    - registered successors/predecessors (chaining)
;;    - registered watch-fns

(comment tessera-state
         {:status ...
          :successors ...
          :predecessors ...
          :watches ...})

;; predecessors are ordered by arg idx in the function
;; this tessera represents.

;; We store predecessors to get their values, and we note whether
;; a revoke-chain should impact them.
(deftype Predecessor [chained? tessera])

;;;;;;;;;;;;;;;;;;;;;;
;; EXECUTION MODELS ;;
;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-model
  "Provides certain keywords to be aliases for reified ExecutionModels,
  to serve the API."
  [kw-or-model]
  (if (instance? ExecutionModel kw-or-model)
    kw-or-model
    (case kw-or-model
      :future ex-m/future-model
      :delay ex-m/delay-model
      :now ex-m/now-model
      :annex-future ex-m/annex-or-future
      :annex-delay ex-m/annex-or-delay
      :annex-now ex-m/annex-or-now)))

;;;;;;;;;;;;;;
;; CHAINING ;;
;;;;;;;;;;;;;;

(defn- handle-exceptional-status
  [tessera status successor]
  (case status
    :fumbled
    (ex/attempt-fumble successor @(.-value tessera))
    :revoked
    (revoke successor)))

(defn- coerce-status
  [tessera status]
  (if (and (identical? status :pending)
           (redeemable? tessera))
    :fulfilled
    status))

(defn- link-to-successor!
  "Attempts to note successor as such in the internal state of
  tessera. Will return the status of the tessera (:pending, :ready, :fumbled).
  Returns :fulfilled in the place of :pending when the parent tessera's
  execution model follows a delay pattern"
  [tessera successor]
  (loop [snapshot @(.-state tessera)]
    (if (identical? :pending (:status snapshot))
      (let [new-state (update snapshot :successors (fnil conj []) successor)]
        (if (compare-and-set! (.-state tessera) snapshot new-state)
          (coerce-status tessera (:status new-state))
          (recur @(.-state tessera))))
      (:status snapshot))))

(defn- link-successor
  [executor tessera successor]
  (let [status (link-to-successor! tessera successor)]
    (if (#{:fumbled :revoked} status)
      (handle-exceptional-status tessera status successor)
      (when-let [execute (execution executor successor :chain status)]
        (execute successor)))))

(declare successor)
(defn- successor-impl
  [tessera f executor chained?]
  (let [successor (successor tessera f executor chained?)]
    (link-successor executor tessera successor)
    successor))

;;;;;;;;;;;;;;;;;;;;;;;;
;; TESSERA HELPER FNS ;;
;;;;;;;;;;;;;;;;;;;;;;;;

(defn- inform-successors!
  [tessera successors]
  (doseq [successor successors]
    (try (inform successor)
         (catch Throwable t
           (log/error "The tessera library encountered an error while pipelining.")))))

(defn- fumble-successors!
  [successors e]
  (doseq [successor successors]
    (ex/attempt-fumble successor e)))

(defn- assert-execution-model!
  [tessera]
  (assert (or (nil? (.-f tessera))
              (identical? (.get ^WeakReference @(.-executor-thread tessera))
                          (Thread/currentThread)))
          "Cannot call `fulfil` or `fumble` on non-promise tesserae."))

(defn- pending? [tessera] (identical? ::ex/init @(.-value tessera)))

;;;;;;;;;;;;;;;;;;;;
;; IMPLEMENTATION ;;
;;;;;;;;;;;;;;;;;;;;

(deftype Tessera
    [^CountDownLatch redemption-latch ;; only used to block derefs
     pending-cnt ;; atom
     value ;; volatile
     state ;; atom: {:keys [status successors predecessors watches]}
     f ;; IFn / Runnable TODO: make it a Runnable and use invoke everywhere
     model ;; execution-model
     executor-lock
     executor-thread]
  clojure.lang.IFn
  (invoke [this v]
    ;; for compatibility with clojure.core/deliver
    (when (nil? f)
      (fulfil this v)))
  clojure.lang.IPending
  (isRealized [this]
    (not (identical? :pending (-> state deref :status))))
  clojure.lang.IBlockingDeref
  (deref [this timeout-ms timeout-val]
    (redeem this timeout-ms timeout-val))
  clojure.lang.IDeref
  (deref [this] (redeem this))
  java.util.concurrent.Future
  (cancel [this may-interrupt?]
    (boolean
     (ex/revoke-impl this may-interrupt? false)))
  (get [this]
    (when-not (realized? this)
      (when-let [execute (execution model this :redeem)]
        (execute this)))
    (.await redemption-latch)
    (cond (fumbled? this)
          (throw (ExecutionException. "Exception encountered during execution of tessera."
                                      @value))
          (revoked? this)
          (throw @value)
          :else
          @value))
  (get [this timeout timeunit]
    (when-not (realized? this)
      (when-let [execute (execution model this :redeem)]
        (future (execute this)))) ;; enables timeouts under delay model
    (if (.await redemption-latch timeout ^TimeUnit timeunit)
      (cond (fumbled? this)
            (throw (ExecutionException. "Exception encountered during execution of tessera."
                                        @value))
            (revoked? this)
            (throw @value)
            :else
            @value)
      (throw (TimeoutException. ".get() on tessera timed out."))))
  (isCancelled [this] (revoked? this))
  (isDone [this] (not (pending? this)))
  FormaTesserae
  (fulfilled? [_] (identical? :fulfilled (:status @state)))
  (revoked? [_] (identical? :revoked (:status @state)))
  (fumbled? [_] (identical? :fumbled (:status @state)))
  (inform [this]
    (when (zero? (swap! pending-cnt dec)) ;; informing of last dependency?
      (when-let [execute (execution model this :inform)]
        (execute this)))
    this)
  (fulfil [this v]
    (assert-execution-model! this)
    (when (pending? this)
      (when (vcas! value ::ex/init v)
        (let [{:keys [successors watches]} (swap! state assoc :status :fulfilled)]
          (.countDown redemption-latch) ;; let folks in.
          (watch/execute-watches! this watches) ;; watches first
          (inform-successors! this successors) ;; then propagate the chain.
          (swap! state dissoc :successors :predecessors :watches) ;; GC-friendly
          (ex/relinquish-execution this))))
    this)
  (fumble [this e]
    (assert-execution-model! this)
    (when (pending? this)
      (when (vcas! value ::ex/init e)
        (let [{:keys [successors watches]} (swap! state assoc :status :fumbled)]
          (.countDown redemption-latch) ;; let folks in
          (watch/execute-watches! this watches)
          (fumble-successors! successors e)
          (ex/relinquish-execution this))))
    this)
  (redeem [this timeout-ms timeout-val]
    (when-not (realized? this)
      (when-let [execute (execution model this :redeem)]
        (future (execute this)))) ;; TODO: good idea? enables timeouts under delay-model
    (if (.await redemption-latch timeout-ms TimeUnit/MILLISECONDS)
      (cond (fumbled? this)
          (throw @value)
          (revoked? this)
          (throw @value)
          :else
          @value)
      timeout-val))
  (get-error [this]
    (when (or (fumbled? this)
              (revoked? this))
      @value))
  (await [this]
    (when (and (pending? this)
               (not (execution model this :redeem)))
      (.await redemption-latch))
    this)
  (await [this timeout-ms timeout-val]
    (if (and (pending? this)
             (not (execution model this :redeem)))
      (or (and (.await redemption-latch timeout-ms TimeUnit/MILLISECONDS)
               this)
          timeout-val)
      this))
  (redeem [this]
    (when-not (realized? this)
      (when-let [execute (execution model this :redeem)]
        (execute this)))
    (.await redemption-latch)
    (cond (fumbled? this)
          (throw @value)
          (revoked? this)
          (throw @value)
          :else
          @value))
  (redeemable? [this]
    (or (realized? this)
        (boolean (execution model this :redeem))))
  (revoke [this] (ex/revoke-impl this false false))
  (revoke [this may-interrupt?] (ex/revoke-impl this may-interrupt? false))
  (revoke [this may-interrupt? data] (ex/revoke-impl this may-interrupt? false data))
  (revoke-chain [this] (ex/revoke-impl this false true))
  (revoke-chain [this may-interrupt?] (ex/revoke-impl this may-interrupt? true))
  (revoke-chain [this may-interrupt? data] (ex/revoke-impl this may-interrupt? true data))
  (chain [this execution-model f]
    (let [model (resolve-model execution-model)]
      (successor-impl this f model true)))
  (pipeline [this execution-model f]
    (let [model (resolve-model execution-model)]
      (successor-impl this f model false)))
  (watch [this f] (watch/register-watch-fn this f nil))
  (watch [this f executor] (watch/register-watch-fn this f executor))
  (unwatch [this token] (watch/unregister-watch-fn this token)))

(defmethod print-method Tessera
  [^Tessera t ^Writer w]
  (let [state @(.-state t)
        status (:status state)]
    (.write w (format "#tessera[{:status %s :val %s} 0x%x]"
                      status
                      (if (= :fumbled status)
                        "#error"
                        (pr-str
                         (let [v @(.-value t)]
                           (when-not (identical? v ::ex/init)
                             v))))
                      (System/identityHashCode t)))))

(defn- successor
  ([predecessor f executor chained?]
   (successor predecessor f executor chained? :pending))
  ([predecessor f executor chained? status]
   (Tessera. (CountDownLatch. 1)
             (atom 1)
             (volatile! ::ex/init)
             (atom {:status status
                    :predecessors [(Predecessor. chained? predecessor)]})
             f
             executor
             (Object.)
             (atom nil))))

(defn promise-tessera
  []
  (Tessera. (CountDownLatch. 1)
            nil
            (volatile! ::ex/init)
            (atom {:status :pending})
            nil
            ex-m/promise-model
            (Object.)
            (atom nil)))

(defn future-tessera
  [f]
  (-> (Tessera. (CountDownLatch. 1)
                (atom 1)
                (volatile! ::ex/init)
                (atom {:status :pending})
                f
                ex-m/future-model
                (Object.)
                (atom nil))
      (inform)))

(defn delay-tessera
  [f]
  (Tessera. (CountDownLatch. 1)
            nil
            (volatile! ::ex/init)
            (atom {:status :ready})
            f
            ex-m/delay-model
            (Object.)
            (atom nil)))

(defn now-tessera
  [f]
  (let [promise (promise-tessera)
        result (try (f)
                    (catch Throwable t
                      (fumble promise t)
                      ::failed))]
    (when-not (identical? ::failed result)
      (fulfil promise result))
    promise))
