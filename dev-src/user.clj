(ns user
  (:require [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [com.hapgood.refreshable :as refreshable]
            [datomic.ion :as ion]))

(defn- path->keyword
  "Convert the given `/`-separated path string into a keyword who namespace consists of all but the last segment
  and whose name is the last segment."
  [path]
  (let [segments (sequence (map last) (re-seq #"/([^/]+)" path))
        kw (last segments)
        ns (butlast segments)]
    (if (seq ns)
      (keyword (str/join "." ns) kw)
      (keyword kw))))

(defn fetch
  "Fetch parameters (via Datomic Ions) promoting the path keys to keywords and the string valuess to EDN-compatible data"
  [env app]
  {:pre [(string? env) (string? app)] :post [(map? %)]}
  (let [path (str "/datomic-shared/" (name env) "/" app "/edn")]
    (reduce-kv (fn [acc k v] (assoc acc (path->keyword k) (edn/read-string v)))
               {}
               (ion/get-params {:path path}))))

(defn params
  []
  (let [env (-> (ion/get-env) :env name)
        app-name (-> (ion/get-app-info) :app-name)
        acquire (fn [c] (tap> "acquire in") (async/>!! c (try [(fetch env app-name) 5000]
                                                              (catch Exception e
                                                                {:msg "Failed to acquire parameters" :ex e ::anomaly :failed}))))]
    (refreshable/create acquire)))
