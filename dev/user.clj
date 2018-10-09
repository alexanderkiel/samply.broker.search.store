(ns user
  (:require
    [clojure.repl :refer :all]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.tools.namespace.repl :refer [refresh]]
    [datomic-spec.test :as dt]
    [env-tools.alpha :as env-tools]
    [manifold.bus :as bus]
    [broker-search-store.server :as server]
    [broker-search-store.system :as system]
    [spec-coerce.alpha :refer [coerce]]))

(st/instrument)
;(dt/instrument)


(defonce system nil)


(defn init []
  (let [config (coerce :system/config (env-tools/build-config :system/config))]
    (alter-var-root #'system (constantly (system/init! config)))
    (println "Server running at port" (server/port (:server system)))))


(defn reset []
  (some-> system system/shutdown!)
  (refresh :after 'user/init))


(defn connect []
  (:database-conn system))


;; Init Development
(comment
  (init)
  (pst)
  )


;; Reset after making changes
(comment
  (reset)
  )

;; Search Ids
(comment
  (require 'shortid.core)
  (dotimes [_ 100]
    (prn (shortid.core/generate 5))))

(comment
  (bus/topic->subscribers (:event-bus system))
  )
