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

(ns tesserae.watch
  (:import [java.util.concurrent ExecutorService]))

;;;;;;;;;;;;;
;; WATCHES ;;
;;;;;;;;;;;;;

(deftype Watch [token f executor])

(defn- conditional-swap!
  "Kinda. Just returns true/false for success/failure -- not the
  swapped-in state."
  [a pred f]
  (boolean
   (loop [snapshot @a]
     (when (pred snapshot)
       (or (compare-and-set! a snapshot (f snapshot))
           (recur @a))))))

(defn- register-watch
  "Attempts to register the watch, so long as the tessera is still
  pending. Returns true if the watch is registered; false otherwise."
  [tessera watch]
  (conditional-swap!
   (.-state tessera)
   #(identical? :pending (:status %))
   #(update % :watches (fnil assoc {}) (.-token watch) watch)))

(defn register-watch-fn
  "Attempts to register the watch-fn, so long as the tessera is still
  pending. Returns a unique watch token if the watch is registered;
  nil otherwise."
  [tessera watch-fn executor]
  (let [token (rand-int Integer/MAX_VALUE)
        watch (Watch. token watch-fn executor)]
    (when (register-watch tessera watch)
      token)))

(defn unregister-watch-fn
  "Attempts to unregister the watch, so long as the tessera is still
  pending. Returns true if the watch is unregistered; false if called
  too late."
  [tessera token]
  (conditional-swap!
   (.-state tessera)
   #(identical? :pending (:status %))
   #(dissoc % token)))

(defn execute-watches!
  "Executes the supplied watches, either directly in this thread or by
  submitting to the optional ExecutorService supplied when the watch
  was added."
  [tessera watches]
  (doseq [[_ watch] watches]
    (try
      (if (.-executor watch)
        (.submit ^ExecutorService (.-executor watch)
                 ^Runnable (partial (.-f watch) tessera))
        ((.-f watch) tessera))
      (catch Throwable t)))) ;; watch fns should handle their own
                             ;; exceptions
