(ns com.hapgood.refreshable-test
  (:require [com.hapgood.refreshable :as uat :refer [create close! closed? refresh!] :include-macros true]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [com.hapgood.test-utilities :refer [go-test closing] :include-macros true])
  (:import #?(:clj (java.util Date))))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn logger [{error ::uat/error :as event}] (when error (prn event)))
#_(add-tap logger)

(defn- make-supplier
  ([n] (make-supplier n -1))
  ([n initial-value]
   (let [state (atom initial-value)]
     (fn [c]
       (let [f (fn [] (async/put! c (swap! state inc)))]
         #?(:clj (async/thread (Thread/sleep n) (f))
            :cljs (js/setTimeout f n)))))))

(deftest refreshable-satisfies-channel-protocols
  (closing [eph (create identity 0)]
           (is (satisfies? impl/ReadPort eph))
           (is (satisfies? impl/Channel eph))))

(deftest can-create-and-close
  (go-test (let [refreshable (create identity 0)]
             (close! refreshable)
             ;; eventually and asynchronously, the refreshable closes
             (while (not (closed? refreshable))
                         ;; Pure busy-wait crushes Clojurescript and the close never completes.  Chill for a bit...
               (async/<! (async/timeout 100)))
             (is (closed? refreshable)))))

(deftest acquire-function-can-supply-fresh-values
  (go-test (closing [refreshable (create #(async/put! % true) 0)]
                    (is (true? (async/<! refreshable))))))

(deftest error-handler-called-correctly-and-controls-backoff
  (let [store (atom nil)]
    (testing "acquire closes source channel"
      (go-test (closing [e (create async/close! 0
                                   :backoffs (list 2)
                                   :error-handler (fn [& args] (reset! store args) nil))]
                        (is (nil? (async/<! e)))
                        (is (= e (-> store deref first)))
                        (let [error (-> store deref last)]
                          (is (map? error))
                          (is (= ::uat/source-closed (:error-type error)))
                          (is (= 2 (:retry error))))))))
  (let [store (atom nil)]
    (testing "acquire synchronously throws exception"
      (go-test (closing [e (create #(throw (ex-info "Boom" {})) 0
                                   :backoffs (list 2)
                                   :error-handler (fn [& args] (reset! store args) nil))]
                        (is (nil? (async/<! e)))
                        (is (= e (-> store deref first)))
                        (let [error (-> store deref last)]
                          (is (map? error))
                          (is (= ::uat/exception (:error-type error)))
                          (is (= 2 (:retry error))))))))
  (let [store (atom nil)]
    (testing "acquire times out"
      (go-test (closing [e (create identity 0
                                   :error-handler (fn [& args] (reset! store args) nil) :failsafe-timeout 1)]
                        (is (nil? (async/<! e)))
                        (is (= e (-> store deref first)))
                        (let [error (-> store deref last)]
                          (is (map? error))
                          (is (= ::uat/failsafe (:error-type error)))
                          (is (nil? (:retry error)))))))))

(deftest backoffs-are-consumed
  (let [store (atom [])]
    (go-test (closing [e (create async/close! 0
                                 :backoffs (list 1 2 3)
                                 :error-handler (fn [_ {retry :retry}] (swap! store conj retry) retry))]
                      (is (nil? (async/<! e)))
                      (is (= [1 2 3 nil] @store))))))

(deftest refreshable-supports-metadata
  (closing [r (create identity 0)]
           (is (#?@(:clj (instance? clojure.lang.IObj) :cljs (satisfies? IWithMeta)) r))
           (is (#?@(:clj (instance? clojure.lang.IMeta) :cljs (satisfies? IMeta)) r))))

(deftest unavailable-refreshable-cannot-be-captured
  (go-test (closing [r (create identity 0)]
                    (let [timeout (async/timeout 10)]
                      (is (= timeout (second (async/alts! [r timeout])))))))) ; timeout while waiting to read

(deftest refreshable-can-be-captured-once-supplied-with-value
  (go-test (closing [e (create (make-supplier 10) 0)]
                    (is (zero? (async/<! e))))))  ; rendez-vous

#?(:clj (deftest refreshable-supports-reference-interfaces
          (closing [e (create (make-supplier 1) 0)]
                   (is (zero? (deref e))))
          (closing [e (create (make-supplier 500) 0)]
                   (is (= :timeout (deref e 10 :timeout)))
                   (is (zero? (deref e 1000 :timeout))))))

(deftest acquire-can-refresh
  (go-test (closing [r (create (let [state (atom -2)]
                                 (fn [c] (when ((complement pos?) (swap! state inc)) (async/put! c @state))))
                               0)]
                    (async/<! (async/timeout 100))
                    (is (zero? (async/<! r)))
                    (is (= 2 (-> r meta ::uat/version))))))

(deftest metadata-records-acquisition
  (go-test (closing [e (create (make-supplier 1) 0)]
                    (async/<! e)
                    (let [m (meta e)]
                      (is (inst? (m ::uat/acquired-at)))
                      (is (pos-int? (m ::uat/latency)))
                      (is (pos-int? (m ::uat/version)))))))

(deftest exceptions-supplying-value-are-caught-and-retried-by-default
  (go-test (closing [e (create (let [state (atom -3)] ; fail twice and then supply a value
                                 (fn [c]
                                   (if (neg? (swap! state inc))
                                     (throw (ex-info "Boom" {}))
                                     (async/put! c @state))))
                               100)]
                    (is (zero? (async/<! e))))))

(deftest pending-async-captures-are-released-when-source-closes
  (go-test (let [e (create (constantly true) 1000)
                 closer (fn [] (close! e))] ; NB: Failure to close promise channel will deadlock this test
             #?(:clj (async/thread (Thread/sleep 500) (closer))
                :cljs (js/setTimeout closer 500))
             (is (nil? (async/<! e))))))

(deftest acquire-fn-transient-failure-are-retried-by-default
  (go-test (closing [e (create (let [state (atom -5)]
                                 (fn [c] (if (zero? (swap! state inc))
                                           (async/put! c @state)
                                           (async/close! c))))
                               1000)]
                    (is (zero? (async/<! e))))))

(deftest acquire-failure-recorded-in-metadata-by-default
  (go-test (closing [e (create async/close! 1)]
                    (async/<! (async/timeout 100))
                    (let [error (-> e meta ::uat/error)]
                      (is (integer? (error :retry)))
                      (is (= ::uat/source-closed (error :error-type)))))))

(deftest metadata-reflects-closing
  (go-test (let [r (create (make-supplier 0) 0)]
             (async/<! r)
             (close! r)
             ;; eventually and asynchronously, the refreshable closes
             (while (not (closed? r))
                         ;; Pure busy-wait crushes Clojurescript and the close never completes.  Chill for a bit...
               (async/<! (async/timeout 100)))
             (is (= {::uat/closed? true} (meta r))))))

(deftest failsafe-option
  (go-test (let [store (atom [])
                 failsafe-timeout 50
                 r (create (constantly true)
                           0
                           :error-handler (fn [r e] (swap! store conj e) nil)
                           :failsafe-timeout failsafe-timeout)]
             (is (nil? (async/<! r))) ; block waiting for the failsafe close
             (is (= ::uat/failsafe (-> store deref first :error-type))))))

(deftest backoffs-option
  (testing "custom backoffs"
    (go-test
     (let [store (atom [])]
       (closing [r (create async/close!
                           0
                           :error-handler (fn [r e] (swap! store conj (meta r)) (:retry e))
                           :backoffs (repeat 9999999))]
                (async/<! (async/timeout 1000))
                (is (= 1 (-> store deref count)))))))
  (testing "finite backoff sequence"
    (go-test
     (let [store (atom [])]
       (closing [r (create async/close!
                           0
                           :error-handler (fn [r e] (swap! store conj (meta r)) (:retry e))
                           :backoffs (list 1))]
                (is (nil? (async/<! r)))
                (is (= 2 (-> store deref count))))))))

(deftest string-representation
  (closing [e (create (make-supplier 0) 0)]
           ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
           ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
           (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Refreshable references to be readable data.
  #?(:clj (closing [e (create (make-supplier 0) 0)]
                   (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

(deftest refresh-fetches-updated-value-before-next-interval
  (go-test
   (closing [r (create (make-supplier 0) 100000)]
            (let [prev (async/<! r)]
              (refresh! r)
              (async/<! (async/timeout 20))
              (is (< prev (async/<! r)))))))

(deftest rejected-validations-do-not-update-the-value
  (go-test
   (let [validator-call-count (atom 0)]
     (closing [r (create (make-supplier 0)
                         100000
                         :backoffs (list 1)
                         :validator (fn [v]
                                      (swap! validator-call-count inc)
                                      (even? v)))]

              (is (= 0 (async/<! r)))
              (is (= 1 @validator-call-count))

              ;; skips the odd value that is not valid
              (refresh! r)
              (async/<! (async/timeout 20))
              (is (= 2 (async/<! r)))
              (is (= 3 @validator-call-count))))))

(deftest rejected-validations-trigger-error-handler
  (go-test
   (let [validator-call-count (atom 0)
         validation-error-occurred (atom nil)]
     (closing [r (create (make-supplier 0)
                         100000
                         :backoffs [1]
                         :error-handler (fn [_r e]
                                          (when (= ::uat/validation-error (:error-type e))
                                            (reset! validation-error-occurred true)
                                            nil))
                         :validator (fn [_v]
                                      (swap! validator-call-count inc)
                                      false))]
              (async/<! (async/timeout 20))
              (is (= 1 @validator-call-count))
              (is @validation-error-occurred)
              (is (closed? r))))))

(deftest can-set-a-nil-validator
  (go-test
   (closing [r (create (fn [c] (async/put! c 2))
                       0
                       :backoffs (repeat 30)
                       :validator (fn [_v]
                                    false))]
            (set-validator! r nil)
            (async/<! (async/timeout 20))
            (is (= 2 (async/<! r))))))

(deftest watches-can-be-added-and-get-called-when-value-updates
  (go-test
   (let [watch (atom nil)
         watch-key (keyword (gensym 'watch-key))]
     (closing [r (create (make-supplier 0) 100000)]
              (async/<! (async/timeout 20))
              (add-watch r watch-key (fn [k r o n]
                                       (reset! watch {:key k :ref r :old o
                                                      :new n})))
              (let [prev (async/<! r)]
                (refresh! r)
                (async/<! (async/timeout 20))
                (let [current (async/<! r)]
                  (is (= {:key watch-key :ref r :old prev :new current}
                         @watch))))))))

(deftest multiple-watches-can-be-added
  (go-test
   (let [watch1 (atom nil)
         watch2 (atom nil)
         watch-key1 (keyword (gensym 'watch-key))
         watch-key2 (keyword (gensym 'watch-key))
         watch-fn (fn [watch]
                    (fn [k r o n]
                      (reset! watch {:key k :ref r :old o :new n})))]
     (closing [r (create (make-supplier 0) 100000)]
              (async/<! (async/timeout 20))
              (add-watch r watch-key1 (watch-fn watch1))
              (add-watch r watch-key2 (watch-fn watch2))

              (let [prev (async/<! r)]
                (refresh! r)
                (async/<! (async/timeout 20))
                (let [current (async/<! r)]
                  (is (= {:key watch-key1 :ref r :old prev :new current}
                         @watch1))
                  (is (= {:key watch-key2 :ref r :old prev :new current}
                         @watch2))))))))

(deftest watches-can-be-removed
  (go-test
   (let [watch-calls (atom 0)
         watch-key1 (keyword (gensym 'watch-key))
         watch-key2 (keyword (gensym 'watch-key))
         watch-fn (fn [_k _r _o _n]
                    (swap! watch-calls inc))]
     (closing [r (create (make-supplier 0) 100000)]
              (add-watch r watch-key1 watch-fn)
              (add-watch r watch-key2 watch-fn)

              (async/<! (async/timeout 20))

              (remove-watch r watch-key1)
              (reset! watch-calls 0)
              (refresh! r)
              (async/<! (async/timeout 20))
              (async/<! r)
              (is (= 1 @watch-calls))))))
