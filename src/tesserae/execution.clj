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

(ns tesserae.execution
  (:require [tesserae.protocols :as p]
            [tesserae.watch :as watch]
            [tesserae.utils :refer [vcas! cancex-info]])
  (:import [clojure.lang IFn]
           [java.util.concurrent ExecutorService]
           [java.lang.ref WeakReference]))

;;;;;;;;;;;;;;;;;;;;;;;;
;; COORDINATION UTILS ;;
;;;;;;;;;;;;;;;;;;;;;;;;

;; There are two gates we care about:
;;   1. A thread claiming execution, respected by all operations but
;;     `revoke`
;;   2. Actually fulfilling the tessera with a value (whether via
;;      fulfil or fumble)
;;
;; Direct calls to fulfil and fumble do not `claim execution`, but they do
;; relinquish execution in case it's necessary.

(defn claim-execution
  [tessera]
  (compare-and-set! (.-executor-thread tessera)
                    nil
                    (WeakReference. (Thread/currentThread))))

(defn forcibly-claim-execution
  [tessera]
  (reset! (.-executor-thread tessera)
          (WeakReference. (Thread/currentThread))))

(defn relinquish-execution
  [tessera]
  (when-let [reference @(.-executor-thread tessera)]
    (when (identical? (Thread/currentThread)
                      (.get ^WeakReference reference))
      (locking (.-executor-lock tessera) ;; see revoke-impl
        (compare-and-set! (.-executor-thread tessera)
                          reference
                          nil)))))

;;;;;;;;;;;;;;;;;;;;;;
;; NORMAL EXECUTION ;;
;;;;;;;;;;;;;;;;;;;;;;

(defn- execute
  [tessera]
  (let [{:keys [status predecessors]} @(.-state tessera)
        inputs (eduction (map (comp deref #(.-tessera %))) predecessors)]
    (apply (.-f tessera) inputs)))

(defn attempt-fulfilment
  [tessera]
  (when (claim-execution tessera)
    (let [result (try (execute tessera) ;; if this fails just fumble
                      (catch Throwable t
                        (p/fumble tessera t) ;; if this fails throw
                        ::failed))] ;; Fauxnadic Error Handling
      (when-not (identical? result ::failed)
        (p/fulfil tessera result))))) ;; on failure throw

;;;;;;;;;;;;;
;; FUMBLE! ;;
;;;;;;;;;;;;;

(defn attempt-fumble
  [tessera e]
  (when (claim-execution tessera)
    (p/fumble tessera e)))

;;;;;;;;;;;;;;;;
;; REVOCATION ;;
;;;;;;;;;;;;;;;;

(declare revoke-impl)
(defn- revoke-impl*
  "Captures the common terminal parts of revoke-impl's branching logic.
  The devil's in the details; consult source."
  [tessera may-interrupt? chain?]
  (let [state (swap! (.-state tessera) assoc :status :revoked)]
    (.countDown (.-redemption-latch tessera))
    (when chain?
      (doseq [pred (:predecessors state)]
        (when (.-chained? pred)
          (revoke-impl (.-tessera pred) may-interrupt? chain? nil @(.-value tessera)))))
    (doseq [succ (:successors state)]
      (revoke-impl succ may-interrupt? false nil @(.-value tessera)))
    (watch/execute-watches! tessera (:watches state))
    ;; GC-friendly:
    (swap! (.-state tessera) dissoc :successors :predecessors :watches)
    ;; relinquish any claim to execution by any thread:
    (relinquish-execution tessera)))

(defn revoke-impl
  "Attempts to revoke the tessera; if the tessera has already been
  fulfilled, this returns nil. For more information, consult source:
  the devil is inherently in the details."
  ([tessera may-interrupt? chain?]
   (revoke-impl tessera may-interrupt? chain? nil nil))
  ([tessera may-interrupt? chain? data]
   (revoke-impl tessera may-interrupt? chain? data nil))
  ;; pass in data OR error, not both:
  ([tessera may-interrupt? chain? data error]
   ;; We don't want two revoke calls to clobber one another;
   ;; also, a non-::init value means revoke is too late.
   (when (vcas! (.-value tessera) ::init (or error (cancex-info "This tessera has been revoked." data)))
     (if (claim-execution tessera)
       ;; all other threads are now locked out
       (revoke-impl* tessera may-interrupt? chain?)
       ;; another thread already claimed execution.
       (do ;; I can try to interrupt the other thread -- it's already
         ;; prevented from setting the tessera's value.
         (when may-interrupt?
           (locking (.-executor-lock tessera)
             ;; ^^ Don't interrupt a thread that has already moved on!
             (when-let [thread (.get ^WeakReference @(.-executor-thread tessera))]
               (try (.interrupt ^Thread thread)
                    (catch SecurityException e)))))
         (forcibly-claim-execution tessera)
         (revoke-impl* tessera may-interrupt? chain?))))))
