(ns broker-search-store.server
  "HTTP Server

  Call `init!` to initialize an HTTP server and `shutdown!` to release its port
  again."
  (:require
    [aleph.http :as http]
    [aleph.netty :refer [AlephServer]]
    [clojure.spec.alpha :as s])
  (:import
    [java.io Closeable]))

;; ---- Specs -----------------------------------------------------------------

(s/def ::port
  (s/and nat-int? #(<= % 65535)))

(s/def ::server
  (s/and #(instance? Closeable %)
         #(satisfies? AlephServer %)))

;; ---- Functions -------------------------------------------------------------

(s/fdef init!
  :args (s/cat :port ::port :handler fn?)
  :ret ::server)

(defn init!
  "Creates a new HTTP server listening on `port` serving from `handler`.

  Call `shutdown!` on the returned server to stop listening and releasing its
  port."
  [port handler]
  (http/start-server handler {:port port}))

(s/fdef shutdown!
  :args (s/cat :server ::server))

(defn shutdown!
  "Shuts the server down releasing its port."
  [server]
  (.close ^Closeable server))

(s/fdef wait-for-close
  :args (s/cat :server ::server))

(defn wait-for-close [server]
  (aleph.netty/wait-for-close server))

(s/fdef port
  :args (s/cat :server ::server))

(defn port [server]
  (aleph.netty/port server))
