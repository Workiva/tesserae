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

(ns tesserae.protocols
  (:refer-clojure :exclude [await]))

(defprotocol FormaTesserae
  (fumbled? [tessera]
    "A tessera is either pending, fulfilled successfully, revoked, or
    fumbled. Returns true if this tessera is in an error state.")
  (revoked? [tessera]
    "A tessera is either pending, fulfilled successfully, revoked, or
    fumbled. Returns true if this tessera has been revoked.")
  (fulfilled? [tessera]
    "A tessera is either pending, fulfilled successfully, revoked, or
    fumbled. Returns true if this tessera has been fulfilled
    successfully.")
  (fumble [tessera t]
    "Attempts to mark the tessera as fumbled, setting its value to the
    Throwable t. After being fumbled, any attempt to redeem the tessera
    will result in t being rethrown. The error may be fetched without
    rethrowing via `get-error`.")
  (fulfil [tessera v]
    "Attempts to fulfil the tessera by setting its value to v. Always
    returns the tessera itself. If the tessera is no longer pending,
    this does nothing. If the value is successfully set to v, then
    this also executes all registered watch-functions and triggers
    execution of any tesserae pipelined or chained off this one.")
  (redeem [tessera] [tessera timeout-ms timeout-val]
    "Retrieves the value represented by this tessera. Throws if the
    tessera has been fumbled or revoked.")
  (get-error [tessera]
    "Fetches the error from a fumbled or revoked tessera, without
     rethrowing. Returns nil if the tessera is not fumbled.")
  (await [tessera] [tessera timeout-ms timeout-val]
    "Blocks until the tessera is no longer pending, but makes no
    attempt to redeem. If the tessera's execution model would have the
    redeeming thread perform the execution, then this does not
    block. Returns the tessera, or timeout-val if timeout is met.")
  (redeemable? [tessera]
    "Private. Implementation aid. Can this tessera be redeemed right
    now?")
  (inform [tessera]
    "Private. Implementation aid. Notes that a dependency has been
    fulfilled.")
  (chain [tessera execution-model f]
    "Returns a new tessera, chained off of this one. The supplied
    function should take a single argument, the eventual value of this
    tessera. When execution occurs is determined by the passed
    execution-model. Tesserae that are chained enable
    backward-propagation of cancellations via `revoke-chain`.

    execution-model must be one of:
       - :future (clojure.core's future pool performs the work)
       - :delay (the thread that consumes the tessera performs the work)
       - :now (the current thread will perform the work, blocking if necessary)
       - :annex-future (see tesserae.execution.models/annexing-model)
       - :annex-delay (ibid.)
       - :annex-now (ibid.)
       - an ExecutorService (behaves like :future)
       - any other implementation of tesserae.protocols/ExecutionModel.")
  (pipeline [tessera execution-model f]
    "Returns a new tessera, pipelined off of this one. The supplied
    function should take a single argument, the eventual value of this
    tessera. When execution occurs is determined by the passed
    execution-model. Tesserae that are pipelined do NOT allow
    backward-propagation of revocations.

    execution-model must be one of:
       - :future (clojure.core's future pool performs the work)
       - :delay (the thread that consumes the tessera performs the work)
       - :now (the current thread will perform the work, blocking if necessary)
       - :annex-future (see tesserae.execution.models/annexing-model)
       - :annex-delay (ibid.)
       - :annex-now (ibid.)
       - an ExecutorService (behaves like :future)
       - any other implementation of tesserae.protocols/ExecutionModel.")
  (revoke [tessera] [tessera may-interrupt?] [tessera may-interrupt? data]
    "Revokes the tessera, possibly interrupting any thread currently
    working on fulfilling it. By default, this does not interrupt
    threads. This revocation propagates to any dependent tesserae.

    Revoked tesserae throw tesserae.CancellationExceptionInfo. If a
    data map is provided, this is attached as ex-data.")
  (revoke-chain [tessera] [tessera may-interrupt?] [tessera may-interrupt? data]
    "Revokes the tessera, possibly interrupting any thread currently
    working on fulfilling it. By default, this does not interrupt
    threads. This revocation propagates down to any dependent
    tesserae, as well as transitively back up to any tesserae this may
    have been chained off of (but not pipelined).

    Revoked tesserae throw tesserae.CancellationExceptionInfo. If a
    data map is provided, this is attached as ex-data.")
  (watch [tessera f] [tessera f executor]
    "Registers a callback function that is executed once this tessera
    is no longer pending. The watch function should take one argument,
    the tessera itself. On a success, this returns a unique token that
    can be used to unregister the watch fn. Fails, returning false, if
    the tessera is no longer pending.

    By default, the watch function is executed by whatever thread
    fulfils the tessera. If this is undesirable, an optional
    ExecutorService may be provided.")
  (unwatch [tessera token]
    "Unregisters a watch function previously registered with `watch`.
     Returns true on a success, false if the token matched no
     function."))

(defprotocol ExecutionModel
  "This protocol governs the execution model of tesserae. The
    protocol can be implemented directly, but it has also been
    extended to clojure.lang.IFn with the expectation that the
    function's 0-arity, 2-arity, and 3-arity calls satisfy the
    corresponding arities of `execution` on this protocol."
  (execution [model] [model tessera stage] [model tessera stage predecessor-status]
    "In the single arity, this is contracted to return an execution
    function: a function of one argument (the tessera) that will, one
    way or another, cause tesserae.execution/attempt-fulfilment to be
    called on the tessera by some thread.

    stage is one of #{:redeem, :inform, :chain} -- representing the
    three stages of a tessera's lifecycle, and therefore the identify
    of the calling thread:
        - :chain indicates that the calling thread is creating the
          tessera as part of a pipeline/chain;
        - :inform indicates that the calling thread has just fulfilled
          the last of this tessera's dependencies.
        - :redeem indicates that the calling thread wishes to redeem
          the tessera's value (fulfilling it if necessary).

    predecessor-status is used when the stage is :chain, i.e., when
    `chain` or `pipeline` is being executed. Gives the current status
    of the predecessor tessera in the pipeline, one of:
    #{:pending, :fulfilled, :fumbled, :revoked}

    (execution model) -- this is contracted to return an execution
    function as described above.

    (execution model tessera stage) -- Return EITHER nil OR an
    execution function, depending on whether or not this
    execution model calls for execution at this stage.

    (execution model tessera stage predecessor-status) -- Return
    EITHER nil OR an execution function, depending on whether or not
    this execution model calls for execution at this stage.

    See tesserae.execution.models for examples of use."))
