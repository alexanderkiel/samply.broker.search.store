(ns broker-search-store.system
  "Application System

  Call `init!` to initialize the system and `shutdown!` to bring it down.
  The specs at the beginning of the namespace describe the config which has to
  be given to `init!``. The server port has a default of `8080`."
  (:require
    [clojure.spec.alpha :as s]
    [datomic.api :as d]
    [datomic-tools.schema :refer [schema]]
    [broker-search-store.database :as database]
    [broker-search-store.handler :as handler]
    [broker-search-store.server :as server]
    [integrant.core :as ig]
    [prometheus.alpha :as prom]
    [taoensso.timbre :as log])
  (:import
    [java.util.concurrent Executors ExecutorService]
    [io.prometheus.client CollectorRegistry]
    [io.prometheus.client.hotspot ClassLoadingExports GarbageCollectorExports
                                  MemoryPoolsExports StandardExports
                                  ThreadExports VersionInfoExports]))

;; ---- Specs -------------------------------------------------------------

(s/def ::version string?)
(s/def :database/uri string?)
(s/def :config/database-conn (s/keys :req [:database/uri]))
(s/def :config/server (s/keys :opt-un [::server/port]))

(s/def :system/config
  (s/keys
    :req-un [:config/database-conn]
    :opt-un [::version :config/server]))

;; ---- Functions -------------------------------------------------------------

(def ^:private default-config
  {:database-conn {}

   :thread-pool {}

   :event-bus
   {:database/conn (ig/ref :database-conn)
    :thread-pool (ig/ref :thread-pool)}

   :command-handler
   {:database/conn (ig/ref :database-conn)}

   :event-stream-handler
   {:event-bus (ig/ref :event-bus)}

   :search-handler
   {:database/conn (ig/ref :database-conn)}

   :health-handler
   {}

   :collector-registry
   {}

   :metrics-handler
   {:collector-registry (ig/ref :collector-registry)}

   :app-handler
   {:handlers
    {:handler/command (ig/ref :command-handler)
     :handler/event-stream (ig/ref :event-stream-handler)
     :handler/search (ig/ref :search-handler)
     :handler/health (ig/ref :health-handler)
     :handler/metrics (ig/ref :metrics-handler)}}

   :server {:port 8080 :handler (ig/ref :app-handler)}})

(s/fdef init!
  :args (s/cat :config :system/config :keys (s/? (s/coll-of keyword?))))

(defn init!
  ([config]
   (ig/init (merge-with merge default-config config)))
  ([config keys]
   (ig/init (merge-with merge default-config config) keys)))

(defn shutdown! [system]
  (ig/halt! system))



;; ---- Integrant Hooks -------------------------------------------------------


(defmethod ig/init-key :database-conn
  [_ {:database/keys [uri]}]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    @(d/transact conn (schema))
    conn))


(defmethod ig/init-key :command-handler
  [_ {:database/keys [conn]}]
  (handler/command-handler conn))


(defmethod ig/init-key :thread-pool
  [_ _]
  (Executors/newFixedThreadPool 1))


(defmethod ig/init-key :event-bus
  [_ {:database/keys [conn] :keys [thread-pool]}]
  (database/event-bus conn thread-pool))


(defmethod ig/init-key :event-stream-handler
  [_ {:keys [event-bus]}]
  (handler/event-stream-handler event-bus))


(defmethod ig/init-key :search-handler
  [_ {:database/keys [conn]}]
  (handler/search-handler conn))


(defmethod ig/init-key :health-handler
  [_ _]
  (handler/health-handler))


(defmethod ig/init-key :collector-registry
  [_ _]
  (doto (CollectorRegistry. true)
    (.register (StandardExports.))
    (.register (MemoryPoolsExports.))
    (.register (GarbageCollectorExports.))
    (.register (ThreadExports.))
    (.register (ClassLoadingExports.))
    (.register (VersionInfoExports.))
    (.register database/successful-transactions-total)
    (.register database/transactions-errors-total)
    (.register database/published-events-total)
    (.register handler/connected-clients)
    (.register handler/subscribed-topics)))

(defmethod ig/init-key :metrics-handler
  [_ {:keys [collector-registry]}]
  (fn [_]
    (prom/dump-metrics collector-registry)))


(defmethod ig/init-key :app-handler
  [_ {:keys [handlers]}]
  (handler/app-handler handlers))


(defmethod ig/init-key :server
  [_ {:keys [port handler]}]
  (server/init! port handler))


(defmethod ig/init-key :default
  [_ val]
  val)


(defmethod ig/halt-key! :thread-pool
  [_ ^ExecutorService thread-pool]
  (log/info "Shutdown thread pool")
  (.shutdownNow thread-pool))


(defmethod ig/halt-key! :server
  [_ server]
  (server/shutdown! server))

(defmethod ig/halt-key! :collector-registry
  [_ registry]
  (.clear registry))
