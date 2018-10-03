(ns broker-search-store.system
  "Application System

  Call `init!` to initialize the system and `shutdown!` to bring it down.
  The specs at the beginning of the namespace describe the config which has to
  be given to `init!``. The server port has a default of `8080`."
  (:require
    [clojure.spec.alpha :as s]
    [datomic.api :as d]
    [datomic-tools.schema :refer [schema]]
    [broker-search-store.handler :as handler]
    [broker-search-store.server :as server]
    [integrant.core :as ig]))

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

   :command-handler
   {:database/conn (ig/ref :database-conn)}

   :search-handler
   {}

   :app-handler
   {:database/conn (ig/ref :database-conn)
    :handlers
    {:handler/command (ig/ref :command-handler)
     :handler/search (ig/ref :search-handler)}}

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

(defmethod ig/init-key :search-handler
  [_ _]
  (handler/search-handler))

(defmethod ig/init-key :app-handler
  [_ {:database/keys [conn] :keys [handlers]}]
  (handler/app-handler conn handlers))

(defmethod ig/init-key :server
  [_ {:keys [port handler]}]
  (server/init! port handler))

(defmethod ig/init-key :default
  [_ val]
  val)

(defmethod ig/halt-key! :server
  [_ server]
  (server/shutdown! server))
