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

(ns tesserae.core
  (:require [tesserae.protocols :as p]
            [tesserae.impl :as impl])
  (:refer-clojure :exclude [future delay future-call promise await]))

(defmacro ^:private expose-var
  "Primarily serves to keep protocol and API documentation
  in sync. Note that these are not *identical* vars -- won't
  mess up tooling the way potemkin does."
  [local remote]
  `(do (def ~local ~remote)
       (alter-meta! (var ~local)
                    merge
                    (-> (var ~remote)
                        meta
                        (select-keys [:doc :arglists])))))

;; Protocol methods
(expose-var fumbled? p/fumbled?)
(expose-var revoked? p/revoked?)
(expose-var fulfilled? p/fulfilled?)
(expose-var fumble p/fumble)
(expose-var fulfil p/fulfil)
(expose-var revoke p/revoke)
(expose-var revoke-chain p/revoke-chain)
(expose-var redeem p/redeem)
(expose-var get-error p/get-error)
(expose-var await p/await)
(expose-var watch p/watch)
(expose-var unwatch p/unwatch)

(defn chain
  [execution-model tessera f]
  (p/chain tessera execution-model f))
(alter-meta! #'chain
             assoc
             :doc (-> #'p/chain meta :doc))

(defn pipeline
  [execution-model tessera f]
  (p/pipeline tessera execution-model f))
(alter-meta! #'pipeline
             assoc
             :doc (-> #'p/pipeline meta :doc))


(defn promise
  "Returns a tessera corresponding to a regular promise in Clojure: its
  value can be set by any thread, once only, with `fulfil` or
  `clojure.core/deliver`. Its value can subsequently be read with
  `redeem` or `clojure.core/deref`."
  []
  (impl/promise-tessera))

(defn future-call
  "Corresponds to `clojure.core/future-call`, returning instead a
  tessera whose behavior is identical."
  [f]
  (impl/future-tessera f))

(defmacro future
  "Corresponds to `clojure.core/future`, returning instead a tessera
  whose behavior is identical."
  [& body]
  `(impl/future-tessera (^:once fn [] ~@body)))

(defn delay-call
  "As `future-call` is to `future`, so `delay-call` is to `delay`. Takes
  a function of no arguments and returns a tessera whose value will be
  computed by the first thread that redeems it (via `redeem` or
  `clojure.core/deref`)."
  [f]
  (impl/delay-tessera f))

(defmacro delay
  "Corresponds to `clojure.core/delay`, returning instead a tessera
  whose behavior is identical."
  [& body]
  `(impl/delay-tessera (^:once fn [] ~@body)))

(defn now-call
  "As with `future-call`, accepts a function of no arguments, and
  returns a tessera representing the result of invoking that function.
  Unlike `future-call`, the current thread performs the execution
  immediately. If an error is encountered, the tessera is placed into
  a fumbled state, but `now-call` will not throw."
  [f]
  (impl/now-tessera f))

(defmacro now
  "As with `future`, this takes a body of expressions and returns a
  tessera representing the result of executing the expressions. Unlike
  a `future`, the current thread performs the execution
  immediately. If an error is encountered, the tessera is placed into
  a fumbled state, but `now` itself will not throw."
  [& body]
  `(impl/now-tessera (^:once fn [] ~@body)))

(defn tessera?
  "Returns true if x is a tessera."
  [x] (satisfies? p/FormaTesserae x))
