(ns broker-search-store.database
  (:require
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [manifold.bus :as bus]
    [manifold.deferred :as m]
    [prometheus.alpha :as prom :refer [defcounter]]
    [taoensso.timbre :as log])
  (:import
    [java.util.concurrent BlockingQueue ExecutionException ExecutorService]))


(defcounter successful-transactions-total
  "Total number of successful transactions by command."
  {:subsystem "database"}
  "namespace" "name")

(defcounter transactions-errors-total
  "Total number of transaction errors by command."
  {:subsystem "database"}
  "namespace" "name")

(defcounter published-events-total
  "Total number of published events."
  {:subsystem "database"}
  "namespace" "name")


(defn- datom->clj [db-after {:keys [added e a v]}]
  [(if added :db/add :db/retract) e (d/ident db-after a) v])


(s/fdef transact
  :args (s/cat :conn ::ds/conn :command-name :gba.command/name
               :tx-data ::ds/tx-data)
  :ret m/deferred?)

(defn transact
  [conn command-name tx-data]
  (log/debug "Transact:" tx-data)
  (-> (d/transact-async conn tx-data)
      (m/catch ExecutionException
        (fn [^ExecutionException e]
          (let [cause (.getCause e)
                ex-data (ex-data cause)]
            (log/error "Error while transacting:" (.getMessage cause))
            (prom/inc! transactions-errors-total (namespace command-name)
                       (name command-name))
            (case (:db/error ex-data)
              :db.error/not-an-entity
              (m/error-deferred
                (ex-info
                  (format "`%s` is not an entity." (:entity ex-data))
                  {::anom/category ::anom/incorrect
                   ::anom/message "Missing entity."}))

              (case (:gba/error ex-data)
                (:gba.error/duplicate-criterion
                  :gba.error/criterion-not-found
                  :gba.error/wrong-criterion-type)
                (m/error-deferred
                  (ex-info
                    (.getMessage cause)
                    {::anom/category ::anom/incorrect
                     ::anom/message (.getMessage cause)}))

                (m/error-deferred cause))))))
      (m/chain
        (fn [{:keys [db-after] :as res}]
          (update res :tx-data (partial mapv (partial datom->clj db-after)))))
      (m/chain
        (fn [{:keys [tx-data] :as res}]
          (log/trace "New datoms:" tx-data)
          (prom/inc! successful-transactions-total (namespace command-name)
                     (name command-name))
          res))))


(defn- take! [^BlockingQueue queue]
  (try
    (.take queue)
    (catch InterruptedException _
      {::anom/category ::anom/interrupted})))


(defmulti topic (fn [command-name _] command-name))

(defmethod topic :default [command-name _]
  (log/error "Missing topic creation for command" command-name))


(defn- topic* [{:keys [db-after] :as tx}]
  (let [tx-eid (d/t->tx (d/basis-t db-after))]
    (when-let [{:command/keys [name]} (d/pull db-after [:command/name] tx-eid)]
      (topic name tx))))


(defmulti event (fn [[namespace name] _] (keyword namespace name)))

(defmethod event :default [[namespace name] _]
  (log/error "Missing event creation for event" (keyword namespace name)))


(defn- transaction-handler [^BlockingQueue queue bus]
  (fn []
    (log/info "Start transaction looping...")
    (try
      (loop []
        (let [{::anom/keys [category] :as tx} (take! queue)]
          (when (not= ::anom/interrupted category)
            (when-let [[namespace name :as topic] (topic* tx)]
              (when (bus/active? bus topic)
                (when-let [event (event topic tx)]
                  (prom/inc! published-events-total namespace name)
                  @(bus/publish! bus topic event))))
            (recur))))
      (catch Throwable t
        (log/error t)))
    (log/info "Stop transaction looping.")))

(defn event-bus [conn ^ExecutorService thread-pool]
  (let [queue (d/tx-report-queue conn)
        bus (bus/event-bus)]
    (.submit thread-pool ^Runnable (transaction-handler queue bus))
    bus))
