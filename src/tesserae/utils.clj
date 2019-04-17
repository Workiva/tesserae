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

(ns tesserae.utils
  (:import [tesserae CancellationExceptionInfo]))

(defn cancex-info
  "Create an instance of CancellationExceptionInfo, a CancellationException
  subclass that carries a map of additional data."
  ([] (CancellationExceptionInfo.))
  ([msg] (CancellationExceptionInfo. msg))
  ([msg map] (CancellationExceptionInfo. msg map)))

(defn vcas!
  "Locks the volatile and performs a cas.
  TODO: consider whether this is a good idea."
  [vol prev new]
  (locking vol
    (boolean
     (when (= prev @vol)
       (vreset! vol new)
       true))))
