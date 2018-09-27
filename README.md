# Tesserae

*tessera*, Latin noun: A tally, token, ticket, or watchword used to assist identification and to signify promised payment or friendship.

```
[com.workiva/tesserae "1.0.0"]
```

**Tesserae** is a library providing an abstraction over futures, promises, and delays. Its premise is that these are all examples of a single thing -- a token representing some promised value. The differences between them lie in their execution models (i.e., "who does the work?").

For convenience of interoperability, the tessera type implements `clojure.lang.IDeref`, `clojure.lang.IBlockingDeref`, `clojure.lang.IPending`, `clojure.lang.IFn` (purely for compatibility with `clojure.core/deliver`), and `java.util.concurent.Future`.

Conceptually, this library is similar to [Guava's ListenableFuture](https://github.com/google/guava/wiki/ListenableFutureExplained) or [Manifold's deferred](https://github.com/ztellman/manifold/blob/master/docs/deferred.md). Tessera provides the equivalent of Clojure's promises, futures, and delays; to these it adds an analogous immediate-execution model (via `now` and `now-call`). There is first-class support for **pipelining**, **callbacks**, and **backward-propagating cancellations**.

```clojure
(require '[tesserae.core :as t])

;; A promise:
(t/promise)

;; A future:
(t/future-call (constantly :something-expensive))
(t/future :something-expensive)

;; A delay:
(t/delay-call (constantly :unpleasant-work))
(t/delay :unpleasant-work)

```

These all return a `Tessera`. They should work just as you would expect from Clojure.core's `promise`, `future`, and `delay`, except that `clojure.core/force` [won't](https://github.com/clojure/clojure/blob/master/src/jvm/clojure/lang/Delay.java#L26) [work](https://github.com/clojure/clojure/blob/clojure-1.9.0-alpha14/src/clj/clojure/core.clj#L753) on tesserae.

In addition, each Tessera implements [`java.util.concurrent.Future`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Future.html), and when `Future/get` is called, it throws the exceptions you'd expect from a Future ([`CancellationException`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CancellationException.html), [`TimeoutException`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/TimeoutException.html), [`ExecutionException`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutionException.html), and [`InterruptedException`](https://docs.oracle.com/javase/8/docs/api/java/lang/InterruptedException.html)).

## Pipelining Tesserae:

Pipelining with tesserae comes in two flavors, `pipeline` and `chain`. The only difference between these two is that cancellations are allowed (when explictly requested) to propagate backward in the case of `chain`, and are not so allowed in the case of `pipeline`. They are identical otherwise:

```clojure
(pipeline [execution-model tessera f]
    "Returns a new tessera, pipelined off of this one. The supplied
    function should take a single argument, the eventual value of this
    tessera. When execution occurs is determined by the passed
    execution-model.")
```

### ExecutionModel

There are generally three possible answers to "Who will do the work?"

 1. the thread that creates the tessera (guarantor/creator)
 2. the thread that consumes the tessera (redeemer)
 3. a third party


Option 2 is usually called a "delay," option 3 usually a "future." Option 1 (a `now`? An `imminent`?) often occurs as an implementation detail where a method is contracted to return an IDeref, but asynchrony is undesirable for some reason specific to implementation or context. If we choose option 3, the thread that performs the execution is generally part of a default "future" executor service, or a thread owned by an explicit ExecutorService.

Both `pipeline` and `chain` accept the following keywords: `#{:future, :delay, :now}` as aliases to implementions of `tesserae.execution.ExecutionModel` corresponding to the desired intuitive behaviors. Additionally, you can pass in an `ExecutorService`.

When pipelining tesserae, there is a more complex execution model that may be desirable, which this library calls *annexing*: we may wish to have the execution performed by the same thread that fulfils the previous tessera in the pipeline. However, this cannot be guaranteed (the previous tessera may already be fulfilled, its fulfilling thread long gone). So there must always be a fallback in reserve: one of the three options given above.

To that end, when pipelining, you can use the following keywords: `#{:annex-future, :annex-delay, :annex-now}` as aliases to execution models that will use a future, delay, or now model -- but only if the tessera being pipelined from has already been fulfilled. You can also call `tesserae.execution.models/annexing-model` on any other `ExecutionModel` (including your own `ExecutorService`) to produce an annexing model with the specified fallback.

For more information about `ExecutionModel`, see the documentation on the protocol, as well as the built-in implementations. 

### Examples

#### pipeline :future

Pipelining with the `:future` model returns a tessera that will be fulfilled asynchronously, by Clojure's future pool.

```clojure
(def p (t/promise))
;; p: #tessera[{:status :pending :val nil} 0x425c91a6]

(def f (t/pipeline :future p inc))
;; f: #tessera[{:status :pending :val nil} 0x5274be2a]

(deliver p 0)
;; p: #tessera[{:status :ready :val 0} 0x425c91a6]

;; As soon as `p` is fulfilled, a separate thread is allocated to execute `f`.
(deref f)
;; 1
```

#### pipeline :delay

In this execution model, the redeemer will also be the fulfiller (i.e., this represents a continuation like `clojure.core/delay`, with the work being done only on dereference).

```clojure
(def d1 (t/delay 0))
(def d2 (t/pipeline :delay d1 inc))
(def d3 (t/pipeline :delay d2 inc))
;; d1: #tessera[{:status :pending :val nil} 0x3c4db7af]

(deliver d1 0)
(println d3)
;; #tessera[{:status :pending :val nil} 0x3c4db7af]

(deref d2)
;; 1

(deref d3)
;; 2
```

Futures chained on top of delays will evaluate the delays:

```clojure
(def d (t/delay 0))
(println d)
;; #tessera[{:status :pending :val nil} 0x53932fcd]

(def f (t/pipeline :future d inc))
(deref f)
;; 1

(println d)
;; #tessera[{:status :ready :val 0} 0x53932fcd]
```

#### pipeline :annex-now

The :annex-now model is an odd one at first glance: whatever thread fulfills the previous tessera in the chain (the notifier, in our terminology above), will be forced to fulfill this tessera; however, if the previous tessera in the chain has *already* been fulfilled, then the instigator will (complain bitterly and) immediately complete the work, fulfilling the tessera.

```clojure
(def fulfiller (t/promise))
(def p (t/promise))
(def f (t/pipeline :future p inc))
(def final-tessera (t/pipeline :annex-now f #(do (deliver fulfiller (Thread/currentThread))
                                                 (inc %))))
(deliver p 0)
;; A future thread fulfills `f`, and then, because of the annexing model, fulfills `final-tessera` next.
;; As a side-effect, `fulfiller` is given a value.

(not= @fulfiller (Thread/currentThread))
;; true

;; Now let's test the fallback option of :annex-now
(def fulfiller (t/promise))
(def p (t/promise))
(def f (t/pipeline :future p inc))
(deliver p 0)
;; Now a future thread fulfills `f`, and moves on with its life.

(deref f) ;; just to ensure that it has been fulfilled.
(def final-tessera (t/pipeline :annex-now f #(do (deliver fulfiller (Thread/currentThread))
                                                 (inc %))))
                                                 
;; the current thread should have done the work, because annexing is not possible.
(= @fulfiller (Thread/currentThread))
;; true
```

## Exceptional Behavior:

Any exceptions encountered while fulfilling any tessera (promise/future/delay/freeload variety, doesn't matter) will place the tessera into a "fumbled" state:

```clojure
(def f (t/future (inc "zero")))
(println f)
;; #tessera[{:status :fumbled :val #error} 0x606350d6]

(t/fumbled? f)
;; true

(deref f)
;; throws ClassCastException java.lang.String cannot be cast to java.lang.Number

(t/get-error f)
;; Returns the ClassCastException without throwing.
```

Promises can be fulfilled with exceptions, which are treated as values like any other. If you want the exception to be thrown on redemption, you should use `fumble`:

```clojure
(def p (t/promise))
(t/fumble p (Exception. "I'm an exception haha"))
;; #tessera[{:status :fumbled :val #error} 0x6ca66c2e]
```

Exceptions are propagated down chains, skipping execution, and ExecutionModels, entirely:

```clojure
(def p (t/promise))
(def f1 (t/pipeline :future p #(do (println "f1") (inc %))))
(def f2 (t/pipeline :future f1 #(do (println "f2") (inc %))))
(t/fulfil p "zero")
;; "f1" gets printed
;; "f2" does not

(deref f2)
;; throws ClassCastException java.lang.String cannot be cast to java.lang.Number
```

## Revoking Tesserae

Tessera implements java.util.concurent.Future, including the [`cancel` method](https://docs.oracle.com/javase/7/docs/api/java/util/concurrent/Future.html#cancel(boolean)). 

```clojure
(def p (t/promise))
(.cancel ^java.util.concurrent.Future p false)
(.isCancelled ^java.util.concurrent.Future p)
;; true

(println p)
;; #tessera[{:status :revoked :val nil} 0xd3a3114]
```

If you pass `true`, an *attempt* will be made to interrupt any thread that may be currently executing the Tessera:

```clojure
(let [f (t/future (Thread/sleep 100) (println "I just woke up!") (inc 0))]
  (.cancel ^java.util.concurrent.Future f true))
;; true
;; "I just woke up!" never prints.
```

This library provides a method, `(revoke [tessera] [tessera may-interrupt?] [tessera may-interrupt? data])` which behaves similarly, returning the revoked tessera on a success and `nil` on a failure. As with exceptions, revocations propagate downward through any pipelines, short-circuiting computation when possible and -- if `may-interrupt?` is true -- sending interrupts to fulfilling threads where applicable.

```clojure
(def p (t/promise))
(def f (t/pipeline :future p inc))
(t/revoke p)
(t/revoked? f)
;; true

(println f)
;; #tessera[{:status :revoked :val nil} 0x8345731]
```

The optional argument `data` allows data to be attached to the `CancellationExceptionInfo` that is thrown by any tessera impacted by this call to `revoke`.

### Backwards-chaining revocations

Sometimes you may be provided with a tessera which occurs at the end of a long pipeline that you don't have control over, and you wish to revoke not only your tessera (and any downstream of it), but all tesserae upstream in this pipeline. This library provides `revoke-chain` for this purpose:

```clojure
(let [upstream-future (t/future (println "Processing has begun upstream"))
      upstream-future-2 (t/chain :future upstream-future
                                         #(do (println "Still...")
                                              (Thread/sleep 100)
                                              (println "Continuing...") (inc %)))
      my-tessera (t/chain :future upstream-future-2 inc)]
  (deref upstream-future) ;; <== ensures that upstream-future-2 has been submitted for execution
  (Thread/sleep 50)
  (t/revoke-chain my-tessera true))
;; You should see "Processing has begun upstream" and "Still..."
;; You should not see "Continuing..."
```

This can be very useful when dealing with multiple service layers that rely on pipelining. `pipeline` does not propagate revocations backwards; `chain` does. It is therefore possible to create a hard breakpoint to stop the flow of backward-chaining revocations;

```clojure
(def src (t/promise))
(def left (t/chain :future src inc))
(def right (t/pipeline :future src inc))
(def subchain-left (t/chain :future right inc))
(def subchain-right (t/chain :future right dec))

;;                 src
;;                  |
;;                 / \
;;        <chain> /   \ <pipeline>
;;               /     \
;;            left    right
;;                      |
;;                     / \
;;            <chain> /   \ <chain>
;;                   /     \
;;       subchain-left    subchain-right

(t/revoke-chain subchain-right)
(every? t/revoked? [right subchain-left subchain-right])
;; true

(every? (complement t/revoked?) [src left])
;; true

(deref left)
;; 1

(deref subchain-left)
;; throws CancellationException: This tessera has been revoked.
```

## Callbacks

You can register a callback/watch function with any tessera *that is not yet fulfilled*. In general, these should be lightweight functions, as they will by default be executed by whatever thread fulfills the tessera. Whenever a tessera's state changes from pending to fulfilled, fumbled, or revoked, each registered watch function will be called with the tessera (*not its value*) as the single argument. It is up to each of these functions to handle successful, fumbled, and revoked states.

Register a function by calling `watch`. For cases in which a callback is potentially expensive, you can optionally pass an ExecutorService which will handle execution. If successfully registered, `watch` returns a unique token which can be used to unregister the function by calling `unwatch` on the tessera.

```clojure
(def p (t/promise))
(def token-1 (t/watch p (fn [tessera] (println "watch function 1 fired"))))
(def token-2 (t/watch p (fn [tessera]
                     	   (println (format "watch function 2 fired. Fumbled? %s"
			                    (t/fumbled? tessera))))))
(t/unwatch p token-1)
(deliver p 0)
;; "watch function 2 fired. Fumbled? false"
```

## Contributing

1. Branch and PR to master
2. Maintainers will review.

Guidelines:

 * [generally good style](https://github.com/bbatsov/clojure-style-guide)
 * [clear commit messages](https://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html)
 * tests where appropriate

#### Contributors
Timothy Dean <[timothy.dean@workiva.com](mailto:timothy.dean@workiva.com)>  
