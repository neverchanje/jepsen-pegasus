(ns pegasus.core
  (:gen-class)
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]]
            [jepsen
             [client :as client]
             [cli :as cli]
             [db :as db]
             [tests :as tests]]
            [jepsen.control.util :as cu]
            [jepsen.os.ubuntu :as ubuntu]
            [jepsen.independent :as independent]
            [jepsen.generator :as gen]
            [knossos.model :as model]
            [jepsen.checker :as checker]
            [jepsen.checker.timeline :as timeline]
            [jepsen.nemesis :as nemesis])

  (:import (com.xiaomi.infra.pegasus.client PegasusClientFactory
                                            PegasusClientInterface
                                            PException)))

(def log-dir "/pegasus/data/log")

(defn db
  "Pegasus DB"
  []
  (reify db/DB
    (setup! [db test node]
      (info node "setting up pegasus"))

    (teardown! [db test node]
      (info node "tearing down pegasus"))

    db/LogFiles
    (log-files [db test node]
      "lists and downloads all the logs under /pegasus/data/log"
      (cu/ls-full log-dir))))

(defn parse-long
  "Parses a string to a Long. Passes through `nil`."
  [s]
  (when s (Long/parseLong s)))

(defn int-bytes [i] (.getBytes (str i)))

(defn create-pegasus-client []
  (PegasusClientFactory/getSingletonClient "file://resources/pegasus.properties"))

;; get tableName="temp" hashKey="foo" sortKey=""
(defn pegasus-client-get [client, #^bytes k]
  (-> client
      (.get "temp" k (.getBytes ""))
      (#(when % (String. %)))
      (parse-long)))

;; set tableName="temp" hashKey="foo" sortKey="" value=v
(defn pegasus-client-set
  [client ^PegasusClientInterface, #^bytes k, #^bytes v]
  (-> client
      (.set "temp" k (.getBytes "") v)))

;; cas tableName="temp" hashKey="foo" sortKey="" oldValue=v newValue=v'
(defn pegasus-client-cas
  [client ^PegasusClientInterface, #^bytes k,  #^bytes v, #^bytes v']
  (-> client
      (.compareExchange "temp" k (.getBytes "") v v' 0)
      (.setSucceed)))

(defn cas-client
  "A client for a single compare-and-set register"
  [conn]
  (reify client/Client
    (open! [this test node] (cas-client (create-pegasus-client)))

    (invoke! [this test op]
      (let [[k v] (:value op)
            crash (if (= :read (:f op)) :fail :info)]
        (try+
         (case (:f op)
           :read (let [value (pegasus-client-get conn (int-bytes k))]
                   (assoc op :type :ok, :value (independent/tuple k value)))

           :write (do (pegasus-client-set conn (int-bytes k) (int-bytes v)) (assoc op :type, :ok))

           :cas (let [[value value'] v]
                  (assoc op :type (if (pegasus-client-cas conn (int-bytes k) (int-bytes value) (int-bytes value'))
                                    :ok
                                    :fail))))

         (catch PException e
           (assoc op :type crash, :error :timeout))

         (catch (and (instance? clojure.lang.ExceptionInfo %)) e
           (assoc op :type crash :error e)))))

    ; If our connection were stateful, we'd close it here.
    ; pegasus-java-client doesn't hold a connection open, so we don't need to.
    (close! [_ _])

    (setup! [_ _])
    (teardown! [_ _])))

(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn pegasus-test
  "Given an options map from the command-line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
         opts
         {:name      "pegasus"
          :os        ubuntu/os
          :db        (db)
          :client    (cas-client nil)
          :nemesis   (nemesis/partition-random-halves)
          :checker   (checker/compose
                      {:perf     (checker/perf)
                       :indep (independent/checker
                               (checker/compose
                                {:timeline (timeline/html)
                                 :linear   (checker/linearizable
                                            {:model (model/cas-register)})}))})
          :generator  (->> (independent/concurrent-generator
                            10
                            (range)
                            (fn [k]
                              (->> (gen/mix [r w cas])
                                   (gen/stagger 1/10)
                                   (gen/limit 100))))
                           (gen/nemesis
                            (gen/seq (cycle [(gen/sleep 10)
                                             {:type :info, :f :start}
                                             (gen/sleep 10)
                                             {:type :info, :f :stop}])))
                           (gen/time-limit (:time-limit opts)))}))

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn pegasus-test})
            args))
