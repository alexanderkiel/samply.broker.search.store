(ns broker-search-store.database
  (:require
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [manifold.deferred :as m]
    [taoensso.timbre :as log])
  (:import
    [java.util.concurrent ExecutionException]))

(defn- datom->clj [db-after {:keys [added e a v]}]
  [(if added :db/add :db/retract) e (d/ident db-after a) v])

(s/fdef transact
  :args (s/cat :conn ::ds/conn :tx-data ::ds/tx-data)
  :ret m/deferred?)

(defn transact
  [conn tx-data]
  (log/debug "Transact:" tx-data)
  (-> (d/transact-async conn tx-data)
      (m/catch ExecutionException
        (fn [^ExecutionException e]
          (let [cause (.getCause e)
                ex-data (ex-data cause)]
            (log/error "Error while transacting:" (.getMessage cause))
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
          res))))
