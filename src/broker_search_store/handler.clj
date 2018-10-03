(ns broker-search-store.handler
  (:require
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
    [manifold.deferred :as m]
    [ring.util.response :as ring]
    [taoensso.timbre :as log]))

;; ---- Specs -----------------------------------------------------------------

(s/def :handler/command fn?)

(s/def :handler/search fn?)

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
  (fn [req]
    (let [{::anom/keys [category] :as command} (create-command req)]
      (if category
        (m/success-deferred (error-response command))
        (let [{::anom/keys [category] :as result} (perform-command command)]
          (if category
            (m/success-deferred (error-response result))
            (let [{:keys [tx-data extract-created-id]} result]
              (-> (db/transact conn tx-data)
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
                                   :message (.getMessage e)})))))))))))))

;; ---- Search ----------------------------------------------------------------

(s/fdef search-handler
  :args (s/cat)
  :ret :handler/search)

(defn search-handler []
  (fn [{:keys [db] {:keys [id]} :params}]
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
      (ring/response))))

;; ---- App Handler -----------------------------------------------------------

(def ^:private routes
  ["/"
   {["searches/" :id] {:get :handler/search}
    ["command/" :namespace "/" :name] {:post :handler/command}}])

(defn- wrap-not-found [handler]
  (fn [req]
    (if-let [resp (handler req)]
      resp
      (-> (ring/not-found "Not Found")
          (ring/content-type "text/plain")))))

(defn- wrap-json [handler]
  (fn [req]
    (m/let-flow [resp (handler req)]
      (-> (update resp :body cheshire/generate-string {:key-fn name})
          (ring/content-type "application/json")))))

(defn- wrap-db [conn handler]
  (fn [req]
    (handler (assoc req :db (d/db conn)))))

(s/def ::handlers
  (s/keys :req [:handler/command :handler/search]))

(s/fdef app-handler
  :args (s/cat :conn ::ds/conn :handlers ::handlers))

(defn app-handler
  "Whole app Ring handler."
  [conn handlers]
  (->> (bidi-ring/make-handler routes handlers)
       (wrap-not-found)
       (wrap-db conn)
       (wrap-json)))
