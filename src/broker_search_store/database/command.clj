(ns broker-search-store.database.command
  (:require
    [clojure.core.specs.alpha :as core-specs]
    [clojure.spec.alpha :as s]
    [cognitect.anomalies :as anom]
    [datomic.api :as d]
    [datomic-spec.core :as ds]
    [datomic-tools.schema :refer [defattr]]
    [broker-search-store.spec]
    [phrase.alpha :refer [defphraser phrase-first]]
    [taoensso.timbre :as log]))

(defmulti perform-command*
  "Called directly by perform-command because there is a problem defining
  function specs for multi-methods."
  {:arglists '([command])}
  (fn [{:gba.command/keys [name]}] name))

(s/def ::extract-created-id
  fn?)

(s/def :perform-command/result
  (s/keys :req-un [::ds/tx-data] :opt-un [::extract-created-id]))

(s/fdef perform-command
  :args (s/cat :command :gba/command)
  :ret (s/or :anomaly ::anom/anomaly :result :perform-command/result))

(defn perform-command
  "Performs a command.

  Returns either an anomaly or a map with :tx-data and an optional
  :extract-created-id function."
  [command]
  (perform-command* command))

(defmethod perform-command* :default
  [{:gba.command/keys [name]}]
  {::anom/category ::anom/incorrect
   ::anom/message (format "Unknown command with name `%s`." name)})

(defattr :command/id
  :db/valueType :db.type/uuid
  :db/unique :db.unique/value)

(defattr :command/name
  "The name of the executed command bound to the transaction.

  The value type is keyword, because Datomic doesn't support symbols."
  :db/valueType :db.type/keyword)

(s/fdef defcommand
  :args
  (s/cat
    :name symbol?
    :doc-string (s/? string?)
    :attr-map (s/? map?)
    :bindings (s/tuple ::core-specs/binding-form)
    :body (s/* any?)))

(defmacro defcommand
  "Defines a command.

  `attr-map` can contain the following keys:

  * :param-spec - a spec params have to conform to

  Bindings have to contain one arg, the params. Has to return tx-data or a
  map of :tx-data and :tid for transactions which create an entity."
  {:arglists '([name doc-string? attr-map? [params] & body])}
  [name & args]
  (let [m (if (string? (first args))
            {:doc (first args)}
            {})
        args (if (string? (first args))
               (next args)
               args)
        m (if (map? (first args))
            (conj m (first args))
            m)
        args (if (map? (first args))
               (next args)
               args)
        bindings (first args)
        body (rest args)
        {:keys [id-attr param-spec] :or {param-spec `any?}} m
        name-kw (keyword name)
        extract-created-id
        `(fn [tid# {db-after# :db-after tempids# :tempids}]
           (-> (d/entity db-after# (d/resolve-tempid db-after# tempids# tid#))
               (get ~id-attr)))
        coerce-res
        `(fn [~'tx-data-or-res]
           (if (map? ~'tx-data-or-res)
             ~(if id-attr
                `(cond-> ~'tx-data-or-res
                   (contains? ~'tx-data-or-res :tid)
                   (assoc
                     :extract-created-id
                     (partial ~extract-created-id (:tid ~'tx-data-or-res))))
                'tx-data-or-res)
             {:tx-data ~'tx-data-or-res}))]
    `(defmethod perform-command* '~name
       [{params# :gba.command/params
         :or {params# {}}}]
       (cond
         (not (s/valid? ~param-spec params#))
         {::anom/category ::anom/incorrect
          ::anom/message (phrase-first {:name '~name} ~param-spec params#)}

         :else
         (let [res# (~coerce-res ((fn ~bindings ~@body) params#))]
           (update
             res# :tx-data conj
             {:db/id (d/tempid :db.part/tx)
              :command/id (d/squuid)
              :command/name ~name-kw}))))))

(defphraser #(contains? % key)
  [{cmd-name :name} _ key]
  (format "Missing `%s` param in command `%s`." (name key) cmd-name))

(defphraser :default
  [_ p]
  (log/warn "Unhandled problem:" (pr-str p)))
