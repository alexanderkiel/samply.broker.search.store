(ns broker-search-store.handler
  (:require
    [aleph.http :as http]
    [bidi.ring :as bidi-ring]
    [byte-streams :as bs]
    [cheshire.core :as cheshire]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [broker-search-store.database :as db]
    [broker-search-store.database.command :refer [perform-command]]
    [broker-search-store.search]
    [broker-search-store.spec]
    [manifold.bus :as bus]
    [manifold.deferred :as m]
    [manifold.stream :as stream]
    [ring.util.response :as ring]
    [prometheus.alpha :as prom :refer [defgauge]]
    [taoensso.timbre :as log]))



;; ---- Specs -----------------------------------------------------------------


(s/def :handler/command fn?)

(s/def :handler/event-stream fn?)

(s/def :handler/search fn?)

(s/def :handler/health fn?)

(s/def :handler/metrics fn?)



;; ---- Utils -----------------------------------------------------------------


(defn- error-response
  [{::anom/keys [category message]
    :or {message "Unknown"}
    :as error}]
  (case category
    ::anom/incorrect
    (-> (ring/response {:error message})
        (ring/status 400))
    ::anom/forbidden
    (-> (ring/response {:error message})
        (ring/status 403))
    ::anom/not-found
    (-> (ring/response
          {:error message})
        (ring/status 404))
    (do (log/error error)
        (-> (ring/response {:error message})
            (ring/status 500)))))


(defn- to-json [x]
  (cheshire/generate-string x {:key-fn name}))


(defn- wrap-json [handler]
  (fn [req]
    (m/let-flow [resp (handler req)]
      (-> (update resp :body to-json)
          (ring/content-type "application/json")))))


(defn- wrap-db [handler conn]
  (fn [req]
    (handler (assoc req :db (d/db conn)))))



;; ---- Command ---------------------------------------------------------------


(defn- parse-params [body]
  (try
    (cheshire/parse-string (bs/to-string body) keyword)
    (catch Exception _
      #::anom
          {:category ::anom/incorrect
           :message "Invalid body. Expect JSON Object."})))


(defn- create-command
  "Creates the command from the request.

  Generates the command name from `namespace` and `name` extracted from the URL
  by bidi and takes the command params from the body parsed as JSON."
  {:arglists '([req])}
  [{{:keys [namespace name]} :params :keys [body]}]
  (let [{::anom/keys [category] :as params} (parse-params body)]
    (cond
      category
      params

      (map? params)
      (cond-> #:gba.command
          {:name (symbol namespace name)}
        params (assoc :gba.command/params params))

      :else
      #::anom
          {:category ::anom/incorrect
           :message "Params have to be a JSON object."})))


(s/fdef command-handler
  :args (s/cat :conn ::ds/conn)
  :ret :handler/command)

(defn command-handler [conn]
  (-> (fn [req]
        (let [{::anom/keys [category] :as command} (create-command req)]
          (if category
            (m/success-deferred (error-response command))
            (let [{::anom/keys [category] :as result} (perform-command command)]
              (if category
                (m/success-deferred (error-response result))
                (let [{command-name :gba.command/name} command
                      {:keys [tx-data extract-created-id]} result]
                  (-> (db/transact conn command-name tx-data)
                      (m/chain'
                        (fn [{:keys [db-after tx-data] :as tx-result}]
                          (let [{:gba.command/keys [name params]} command]
                            (log/info (format "Performed command `%s` with params `%s`."
                                              name (pr-str params))))
                          (log/debug (format "Transacted datoms `%s`." tx-data))
                          (ring/response
                            (cond-> {:t (d/basis-t db-after)}
                              extract-created-id
                              (assoc :id (extract-created-id tx-result))))))
                      (m/catch'
                        (fn [^Throwable e]
                          (if-let [ex-data (ex-data e)]
                            (error-response ex-data)
                            (do (log/error e)
                                (error-response
                                  #::anom
                                      {:category ::anom/fault
                                       :message (.getMessage e)}))))))))))))
      (wrap-json)))



;; ---- Subscribe -------------------------------------------------------------


(defgauge connected-clients
  "Current number of connected clients."
  {:subsystem "event_stream"})


(defgauge subscribed-topics
  "Current number of subscribed topics."
  {:subsystem "event_stream"}
  "namespace" "name")


(s/fdef event-stream-handler
  :args (s/cat :event-bus some?)
  :ret :handler/event-stream)

(defn- socket [req]
  (-> (http/websocket-connection req)
      (m/catch'
        (fn [^Throwable e]
          #::anom
              {:category ::anom/fault
               :message (.getMessage e)}))))


(s/fdef put!
  :args (s/cat :sink stream/sink? :msg :event-stream/message))

(defn- put! [sink msg]
  (stream/put! sink (to-json msg)))


(defmulti on-message* (fn [_ _ _ {:keys [type]}] type))


(defmethod on-message* "subscribe"
  [event-bus socket subscriptions {:keys [topic]}]
  (let [success @(put! socket {:type "subscribed" :topic topic})]
    (if success
      (if (contains? subscriptions topic)
        subscriptions
        (let [stream (bus/subscribe event-bus topic)]
          (apply prom/inc! subscribed-topics (take 2 topic))
          (stream/connect
            (stream/map to-json stream)
            socket)
          (assoc subscriptions topic stream)))
      (reduced subscriptions))))


(defmethod on-message* "unsubscribe"
  [_ socket subscriptions {:keys [topic]}]
  (let [success @(put! socket {:type "unsubscribed" :topic topic})]
    (if success
      (if-let [stream (get subscriptions topic)]
        (do (apply prom/dec! subscribed-topics (take 2 topic))
            (stream/close! stream)
            (dissoc subscriptions topic))
        subscriptions)
      (reduced subscriptions))))


(defmethod on-message* "ping"
  [_ socket subscriptions _]
  (let [success @(put! socket {:type "pong"})]
    (if success
      subscriptions
      (reduced subscriptions))))


(s/fdef on-message
  :args (s/cat :event-bus some? :socket stream/sink?
               :subscriptions map? :msg :event-stream/message))


(defn- on-message
  [event-bus socket subscriptions msg]
  (try
    (on-message* event-bus socket subscriptions msg)
    (catch Throwable t
      (log/error t))))


(defn event-stream-handler [event-bus]
  (fn [req]
    (m/let-flow' [{::anom/keys [category] :as socket} (socket req)]
      (if category
        (error-response socket)
        (do (prom/inc! connected-clients)
            (-> (->> socket
                     (stream/map #(cheshire/parse-string % keyword))
                     (stream/reduce (partial on-message event-bus socket) {}))
                (m/chain'
                  (fn [subscriptions]
                    (doseq [[topic stream] subscriptions]
                      (stream/close! stream)
                      (apply prom/dec! subscribed-topics (take 2 topic)))
                    (prom/dec! connected-clients)))))))))



;; ---- Search ----------------------------------------------------------------


(s/fdef search-handler
  :args (s/cat :conn ::ds/conn)
  :ret :handler/search)

(defn search-handler [conn]
  (-> (fn [{:keys [db] {:keys [id]} :params}]
        (some->
          (d/pull
            db
            [:search/id :search/title
             {:search/criteria
              [:search.criterion/mdr-key
               :search.criterion/type
               :search.criterion/selected-values
               {:search.criterion/metric-query
                [:search.criterion.metric-query/type
                 :search.criterion.metric-query/lower-bound
                 :search.criterion.metric-query/upper-bound]}]}]
            [:search/id id])
          (ring/response)))
      (wrap-json)
      (wrap-db conn)))



;; ---- Health ----------------------------------------------------------------


(s/fdef health-handler
  :args (s/cat)
  :ret :handler/health)

(defn health-handler []
  (fn [_]
    (-> (ring/response "OK")
        (ring/content-type "text/plain"))))



;; ---- App Handler -----------------------------------------------------------


(def ^:private routes
  ["/"
   {["searches/" :id] {:get :handler/search}
    ["command/" :namespace "/" :name] {:post :handler/command}
    "event-stream" :handler/event-stream
    "health" :handler/health
    "metrics" :handler/metrics}])


(defn- wrap-not-found [handler]
  (fn [req]
    (if-let [resp (handler req)]
      resp
      (-> (ring/not-found "Not Found")
          (ring/content-type "text/plain")))))


(s/def ::handlers
  (s/keys :req [:handler/command :handler/event-stream :handler/search
                :handler/health :handler/metrics]))


(s/fdef app-handler
  :args (s/cat :handlers ::handlers))

(defn app-handler
  "Whole app Ring handler."
  [handlers]
  (-> (bidi-ring/make-handler routes handlers)
      (wrap-not-found)))
