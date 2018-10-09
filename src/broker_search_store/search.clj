(ns broker-search-store.search
  (:require
    [broker-search-store.database :refer [topic event]]
    [broker-search-store.database.command :refer [defcommand]]
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    [datomic.api :as d]
    [datomic-tools.schema :refer [defattr defunc]]
    [phrase.alpha :refer [defphraser phrase-first]]
    [taoensso.timbre :as log]))

;; ---- Specs -----------------------------------------------------------------

(s/def :search/id
  string?)

(s/def :search/version
  nat-int?)

(s/def :search/title
  string?)

(s/def :search.criterion/mdr-key
  string?)

(s/def :search.criterion/type
  #{:enumerated})

(s/def :search.criterion/selected-values
  (s/coll-of string? :min-count 1))

(s/def :search/criterion
  (s/keys :req [:search.criterion/mdr-key :search.criterion/type
                :search.criterion/selected-values]))

;; ---- Attributes ------------------------------------------------------------

(defattr :search/id
  :db/valueType :db.type/string
  :db/unique :db.unique/identity)

(defattr :search/version
  :db/valueType :db.type/long)

(defattr :search/title
  :db/valueType :db.type/string)

(defattr :search/criteria
  :db/valueType :db.type/ref
  :db/cardinality :db.cardinality/many
  :db/isComponent true)

(defattr :search.criterion/version
  :db/valueType :db.type/long)

(defattr :search.criterion/mdr-key
  :db/valueType :db.type/string)

(defattr :search.criterion/type
  :db/valueType :db.type/keyword)

(defattr :search.criterion/selected-values
  :db/valueType :db.type/string
  :db/cardinality :db.cardinality/many)

(defattr :search.criterion/metric-query
  :db/valueType :db.type/ref
  :db/isComponent true)

(defattr :search.criterion.metric-query/type
  :db/valueType :db.type/keyword)

(defattr :search.criterion.metric-query/type
  :db/valueType :db.type/keyword)

(defattr :search.criterion.metric-query/lower-bound
  :db/valueType :db.type/float)

(defattr :search.criterion.metric-query/upper-bound
  :db/valueType :db.type/float)

;; ---- Database Functions ----------------------------------------------------

(defunc search/create
  "Creates a search.

  Generates an unique identifier in the transactor. Sets the title to the
  filename."
  {:requires [[shortid.core]]}
  [db tid]
  (let [id (loop [id (shortid.core/generate 5)]
             (if (d/entity db [:search/id id])
               (recur (shortid.core/generate 5))
               id))]
    [#:search{:db/id tid :id id :version 0 :title "New Search"}]))

(defunc search/add-criterion
  [db search-id mdr-key criterion]
  (if
    (d/q
      '[:find ?criterion .
        :in $ ?search ?mdr-key
        :where
        [?search :search/criteria ?criterion]
        [?criterion :search.criterion/mdr-key ?mdr-key]]
      db [:search/id search-id] mdr-key)
    (throw
      (ex-info
        (format "Criterion with MDR key `%s` already exists in search `%s`" mdr-key search-id)
        {:gba/error :gba.error/duplicate-criterion}))
    (let [criterion-tid (d/tempid :db.part/user)]
      [(-> criterion
           (assoc :db/id criterion-tid)
           (assoc :search.criterion/mdr-key mdr-key))
       [:db/add [:search/id search-id] :search/criteria criterion-tid]])))

(defunc search/edit-enumerated-criterion
  {:requires [[clojure.set :as set]]}
  [db search-id mdr-key new-selected-values]
  (if-let
    [criterion-eid
     (d/q
       '[:find ?criterion .
         :in $ ?search ?mdr-key
         :where
         [?search :search/criteria ?criterion]
         [?criterion :search.criterion/mdr-key ?mdr-key]]
       db [:search/id search-id] mdr-key)]
    (let [{:search.criterion/keys [type]
           old-selected-values :search.criterion/selected-values}
          (d/entity db criterion-eid)]
      (if (= :enumerated type)
        (let [assertions (set/difference new-selected-values old-selected-values)
              retractions (set/difference old-selected-values new-selected-values)]
          (-> []
              (into (map (partial vector :db/add criterion-eid :search.criterion/selected-values)) assertions)
              (into (map (partial vector :db/retract criterion-eid :search.criterion/selected-values)) retractions)))
        (throw
          (ex-info
            (format "Criterion with MDR key `%s` in search `%s` can't edited as enumerated criterion because its type is `%s`." mdr-key search-id type)
            {:gba/error :gba.error/wrong-criterion-type}))))
    (throw
      (ex-info
        (format "Criterion with MDR key `%s` not found in search `%s`." mdr-key search-id)
        {:gba/error :gba.error/criterion-not-found}))))

(defunc search/edit-float-criterion
  [db search-id mdr-key new-metric-query]
  (if-let
    [criterion-eid
     (d/q
       '[:find ?criterion .
         :in $ ?search ?mdr-key
         :where
         [?search :search/criteria ?criterion]
         [?criterion :search.criterion/mdr-key ?mdr-key]]
       db [:search/id search-id] mdr-key)]
    (let [{:search.criterion/keys [type]
           {metric-query-id :db/id
            old-lower-bound :search.criterion.metric-query/lower-bound
            old-upper-bound :search.criterion.metric-query/upper-bound}
           :search.criterion/metric-query}
          (d/entity db criterion-eid)
          {new-type :search.criterion.metric-query/type
           new-lower-bound :search.criterion.metric-query/lower-bound
           new-upper-bound :search.criterion.metric-query/upper-bound}
          new-metric-query]
      (if (= :float type)
        (cond->
          [[:db/add metric-query-id :search.criterion.metric-query/type new-type]]
          new-lower-bound
          (conj [:db/add metric-query-id :search.criterion.metric-query/lower-bound new-lower-bound])
          (and old-lower-bound (nil? new-lower-bound))
          (conj [:db/retract metric-query-id :search.criterion.metric-query/lower-bound old-lower-bound])
          new-upper-bound
          (conj [:db/add metric-query-id :search.criterion.metric-query/upper-bound new-upper-bound])
          (and old-upper-bound (nil? new-upper-bound))
          (conj [:db/retract metric-query-id :search.criterion.metric-query/upper-bound old-upper-bound]))
        (throw
          (ex-info
            (format "Criterion with MDR key `%s` in search `%s` can't edited as float criterion because its type is `%s`." mdr-key search-id type)
            {:gba/error :gba.error/wrong-criterion-type}))))
    (throw
      (ex-info
        (format "Criterion with MDR key `%s` not found in search `%s`" mdr-key search-id)
        {:gba/error :gba.error/criterion-not-found}))))

;; ---- Commands --------------------------------------------------------------

(defcommand search/create
  {:id-attr :search/id}
  [_]
  (let [tid (d/tempid :db.part/user)]
    {:tid tid :tx-data [[:search/create tid]]}))

(s/def ::search-id
  :search/id)

(defmulti json-criterion-type :type)

(defmethod json-criterion-type "enumerated" [_]
  (s/keys :req-un [::search-id :search.criterion/mdr-key
                   :search.criterion/selected-values]))

(defmethod json-criterion-type "float" [_]
  (s/keys :req-un [::search-id :search.criterion/mdr-key
                   :search.criterion/metric-query]))

(s/def ::json-criterion
  (s/multi-spec json-criterion-type :type))

(defmulti criterion-tx-data :type)

(defmethod criterion-tx-data "enumerated"
  [{:keys [selected-values]}]
  {:tx-map
   #:search.criterion
       {:type :enumerated
        :selected-values selected-values}})

(defmethod criterion-tx-data "float"
  [{{:keys [type lower-bound upper-bound]} :metric-query}]
  (let [metric-query-tid (d/tempid :db.part/user)]
    {:tx-map
     #:search.criterion
         {:type :float
          :metric-query metric-query-tid}
     :more
     [(cond->
        #:search.criterion.metric-query
            {:db/id metric-query-tid
             :type (keyword type)}
        lower-bound
        (assoc :search.criterion.metric-query/lower-bound (float lower-bound))
        upper-bound
        (assoc :search.criterion.metric-query/upper-bound (float upper-bound)))]}))



;; ---- Command - Add-Criterion -----------------------------------------------


(defcommand search/add-criterion
  {:param-spec ::json-criterion}
  [{:keys [search-id mdr-key] :as params}]
  (let [{:keys [tx-map more] :or {more []}} (criterion-tx-data params)]
    (conj more [:search/add-criterion search-id mdr-key tx-map])))


(defn- criterion-search-id
  "Extracts the search identifier from `tx-data` if the transaction contains
  Datoms with attributes with the namespace `search.criterion` or
  `search.criterion.metric-query`."
  [db tx-data]
  (some
    (fn [{:keys [e a]}]
      (let [attr-ns (namespace (d/ident db a))
            entity (d/entity db e)]
        (cond
          (= "search.criterion" attr-ns)
          (:search/id (:search/_criteria entity))

          (= "search.criterion.metric-query" attr-ns)
          (:search/id (:search/_criteria (:search.criterion/_metric-query entity))))))
    tx-data))


(defmethod topic :search/add-criterion
  [_ {:keys [db-after tx-data]}]
  (some->> (criterion-search-id db-after tx-data)
           (conj ["search" "criterion-added"])))


(def ^:private criterion-pattern
  [:search.criterion/mdr-key
   :search.criterion/type
   :search.criterion/selected-values
   {:search.criterion/metric-query
    [:search.criterion.metric-query/type
     :search.criterion.metric-query/lower-bound
     :search.criterion.metric-query/upper-bound]}])


(defn- criterion
  "Returns the criterion entity from `tx-data` if the transaction contains
  Datoms with attributes with the namespace `search.criterion` or
  `search.criterion.metric-query`."
  [db tx-data]
  (some
    (fn [{:keys [e a]}]
      (let [attr-ns (namespace (d/ident db a))
            entity (d/entity db e)]
        (cond
          (= "search.criterion" attr-ns)
          entity

          (= "search.criterion.metric-query" attr-ns)
          (:search.criterion/_metric-query entity))))
    tx-data))


(defn- criterion-event-data
  [{:search.criterion/keys [mdr-key type selected-values metric-query]}]
  (cond->
    {:mdr-key mdr-key
     :type type}
    (= :enumerated type)
    (assoc :selected-values selected-values)
    (= :float type)
    (assoc :metric-query metric-query)))


(defmethod event :search/criterion-added
  [topic {:keys [db-after tx-data]}]
  {:type "event"
   :topic topic
   :data (criterion-event-data (criterion db-after tx-data))})



;; ---- Command - Edit-Criterion ----------------------------------------------


(defcommand search/edit-criterion
  {:param-spec ::json-criterion}
  [{:keys [search-id mdr-key] :as params}]
  (let [{{:search.criterion/keys [type selected-values]} :tx-map :keys [more]}
        (criterion-tx-data params)]
    (case type
      :enumerated
      [[:search/edit-enumerated-criterion search-id mdr-key (set selected-values)]]
      :float
      [[:search/edit-float-criterion search-id mdr-key (first more)]])))


(defmethod topic :search/edit-criterion
  [_ {:keys [db-after tx-data]}]
  (some->> (log/spy (criterion-search-id db-after tx-data))
           (conj ["search" "criterion-edited"])))


(defmethod event :search/criterion-edited
  [topic {:keys [db-after tx-data]}]
  {:type "event"
   :topic topic
   :data (criterion-event-data (criterion db-after tx-data))})


(defphraser json-criterion-type
  [_ {{:keys [type]} :val}]
  (if type
    (format "Unknown criterion with type `%s`." type)
    "Missing `type` property."))
