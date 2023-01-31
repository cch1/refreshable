(ns com.hapgood.refreshable
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- delta-t [t0 t1] (- (inst-ms t1) (inst-ms t0)))

;; A channel-like type that coordinates the supply of fresh values.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Refreshable [out-ref control m]
  impl/ReadPort
  (take! [this fn-handler] (impl/take! @out-ref fn-handler))
  impl/WritePort
  (put! [port val fn1-handler] (impl/put! control val fn1-handler))
  impl/Channel
  (close! [this] (impl/close! control))
  ;; There is a reason this is not a public fn in clojure.core.async: it doesn't track `close!` synchronously.
  (closed? [this] (impl/closed? @out-ref))
  #?@(:clj (clojure.lang.IMeta
            (meta [this] @m)
            clojure.lang.IObj
            (withMeta [this m'] (reset! m m')))
      :cljs (IMeta
             (-meta [this] @m)
             IWithMeta
             (-with-meta [this m'] (reset! m m'))))
  ;; Inspired by https://clojure.atlassian.net/browse/ASYNC-102
  #?@(:clj (clojure.lang.IDeref ; This interface is semantically inappropriate for ClojureScript, right?
            (deref [this]
                   (let [p (promise)]
                     (async/take! this (fn [x] (deliver p x)))
                     (deref p)))
            clojure.lang.IBlockingDeref
            (deref [this timeout fallback]
                   (let [t (async/timeout timeout)
                         p (promise)
                         [val port] (async/alts!! [t this])]
                     (if (= this port) val fallback)))))
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Refreshable " (pr-str v) ">")
                     "#<Refreshable >")))

(defn create
  "Create an instance of a reference type whose value is refreshed every `interval` milliseconds
   by the `acquire` function.

  The `acquire` function is passed a channel onto which it must place a non-nil fresh value. The
  `acquire` function can fail by either synchronously throwing an exception or asynchronously
  closing the channel.  Because the `acquire` function is called from within a go block, it
  should not block.

  The following `options` are available:

  `backoffs`: a sequence of delays (in milliseconds) to backoff when the `acquire` function fails.
              When the sequence is consumed the refreshable is closed.  The default is an infinite
              exponential backoff capped at 30s.

  `error-handler`: a function that is called with the refreshable and the error map when the `acquire`
                   function throws an exception, closes the source channel or triggers the failsafe
                   timer (see `failsafe-timeout` below).  The error handler should either return nil
                   or false (to signal that the refreshable should shut down) or a positive integer
                   number of milliseconds to backoff before retrying the acquire function.

                   Note that the current backoff sequence head is available in the error map at the
                   `:retry` key except when the failsafe timer has been triggered. Simply returning
                   the associated value will generally \"do the right thing\".

                   The default `error-handler` taps (via `tap>`) the error map and assoc's it onto
                   the refreshable's metadata before returning the value at the `:retry` key.  The
                   `error-handler` should not block.

  `failsafe-timeout`: a positive integer number of milliseconds after which the `acquire` function is
                    presumed to have died and the error handler is called.  The default is 60000ms (one
                    minute).  A nil value will never timeout the `acquire` function.

  If the refreshable itself is closed all resources are freed and no further updates will be attempted."
  [acquire interval & {:keys [error-handler backoffs failsafe-timeout]
                       :or {backoffs (concat (take 15 (iterate (partial * 2) 1)) (repeat 30000))
                            error-handler (fn [r e]
                                            (vary-meta r assoc ::error e)
                                            (tap> e)
                                            (:retry e))
                            failsafe-timeout (* 1000 60)}
                       :as options}]
  {:pre [(fn? acquire) (int? interval) (seqable? backoffs) (fn? error-handler) (or (nil? failsafe-timeout) (pos-int? failsafe-timeout))]}
  (let [out-ref (atom (async/promise-chan))
        control (async/chan 1)
        refreshable (->Refreshable out-ref control (atom {::version 0}))]
    ;; coordinate the out-ref promise-channel from value arriving on the in channel
    (async/go-loop [alarm (async/timeout 0) source nil failsafe nil called-at nil backoffs' backoffs]
      (let [[event port] (async/alts! (filter identity [control alarm source failsafe]))
            now (now)]
        (if-let [[a s f c bs] (condp = port
                                alarm (let [source (async/chan 1)]
                                        (try (acquire source)
                                             [nil source (when failsafe-timeout (async/timeout failsafe-timeout)) now backoffs']
                                             (catch #?(:clj java.lang.Exception :cljs js/Error) e
                                               (when-let [backoff (error-handler refreshable {:error-type ::exception :exception e :retry (first backoffs')})]
                                                 [(async/timeout backoff) nil nil nil (rest backoffs')]))))
                                source (if (not (nil? event))
                                         (let [latency (delta-t called-at now)
                                               refresh-after (max 0 (- interval latency))]
                                           (vary-meta refreshable #(-> %
                                                                       (merge {::acquired-at now ::latency latency})
                                                                       (dissoc ::error)
                                                                       (update ::version inc)))
                                           (let [[pc pc'] (reset-vals! out-ref (async/promise-chan))]
                                             (async/offer! pc event) ; release any previously blocked takes
                                             (async/offer! pc' event))
                                           [(async/timeout refresh-after) nil nil nil backoffs])
                                         (when-let [backoff (error-handler refreshable {:error-type ::source-closed :retry (first backoffs')})]
                                           [(async/timeout backoff) nil nil nil (rest backoffs')]))
                                failsafe (when-let [backoff (error-handler refreshable {:error-type ::failsafe})]
                                           [(async/timeout backoff) nil nil nil backoffs'])
                                control (when (not (nil? event))
                                          (if alarm
                                            [(async/timeout 0) source failsafe called-at backoffs']
                                            [alarm source failsafe called-at backoffs'])))]
          (recur a s f c bs)
          (let [[pc pc'] (reset-vals! out-ref (async/promise-chan))] ; can't close a delivered pc, so create a new one to close immediately
            (vary-meta refreshable #(-> %
                                        (dissoc ::acquired-at ::latency ::version)
                                        (assoc ::closed? true)))
            (async/close! pc)
            (async/close! pc')))))
    refreshable))

(def close! async/close!)

#?(:clj
   (do (defmethod clojure.core/print-method Refreshable
         [refreshable ^java.io.Writer writer]
         (.write writer (.toString refreshable)))
       (defmethod clojure.pprint/simple-dispatch Refreshable
         [refreshable]
         (print-method refreshable *out*)))
   :cljs
   (extend-protocol IPrintWithWriter
     Refreshable
     (-pr-writer [this writer opts]
       (-write writer (.toString this)))))
