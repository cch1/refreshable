# Refreshable
An refreshable is a channel-like Clojure(Script) type that coordinates the acquisition and use of fresh values.

Unlimited takes of a fresh value may be performed without blocking.  The refreshable is refreshed based on the timely supply of values by a user-supplied `acquire` function.

### The acquire function
The user-supplied `acquire` function is responsible for periodically supplying the refreshable with fresh values.  It is called with a channel as its sole parameter.  The function should acquire a (non-nil) fresh value and put it on the channel.  The refreshable coordinates the calling of acquire based on observed latency and a milliseconds `interval` 
provided at creation.  If the acquire function throws synchronously it will be retried after a suitable backoff delay.  The acquire function can also asynchronously signal a failure to obtain a fresh value by closing its channel argument.  The function will again be retried after a suitable backoff delay.

### Backoff
If the acquire function fails, the refreshable will backoff and try again.  An exponential backoff capped at 30s is used by default, but the caller can supply a custom strategy in the form of a (possibly infinite) sequence of integer millisecond delays, e.g. `(constantly 10000)`.  If the sequence is entirely consumed, the refreshable shuts down.

### TOCTOU
Avoid time-of-check-time-of-use (TOCTOU) race conditions by never holding the refreshable's captured or dereferenced value.  Instead, take a value at the moment of use.

### Example usage
The refreshable pattern is well-suited for managing the fresh supply of dynamic application parameters.

``` clojure
(require '[com.hapgood.refreshable :as refreshable])

(defn acquire
  [r]
  (http/get parameter-server-url
            {:on-success (fn [response]
                           (let [param (extract-parameter response)
                                 lifespan (* 1000 60)]
			     (async/put! r [param lifespan])))
	     :on-failure (fn [] (async/put! r :failed))}))

(def r (refreshable/create acquire (* 1000 60 5)))

...

(let [param (async/<!! e)] ; blocks only before parameter is first made available
  (do-something param))
```

### Metadata
Acquisition metrics and failure status are tracked in the metadata of the refreshable.

### Serialization
Refreshables are not values and are not suitable for serialization.

### Shutdown
To free up the resources used by the refreshable, close it as you would close a core.async channel.

### Troubleshooting
The acquire function is automatically rescheduled if it synchronously throws an exception or asynchronously reports a failure.  If it neither throws an exception synchronously nor places a value on the refreshable channel a failsafe timer expires and an error handler can either request acquisition be retried or request the refreshable be closed.  Specify the failsafe timer (in milliseconds).

### TODO
1. Consider making the metadata read-only.  Pros: metrics can't be overwritten.  Cons: reduced utility.
2. Consider using mutable fields instead of atoms for internal state.
3. Enhance printing to leverage metatdata.
4. Support watches and validators.
