(defproject samply.broker.search.store "latest"
  :description "Service storing Searches"
  :url "https://github.com/alexanderkiel/samply.broker.search.store"

  :min-lein-version "2.0.0"
  :pedantic? :abort

  :dependencies
  [[aleph "0.4.6"]
   [bidi "2.1.4" :exclusions [prismatic/schema ring/ring-core]]
   [cheshire "5.8.0"]
   [com.cognitect/anomalies "0.1.12"]
   [com.datomic/datomic-free "0.9.5697"
    :exclusions [org.slf4j/slf4j-nop]]
   [integrant "0.6.3"]
   [org.clojars.akiel/datomic-tools "0.4"]
   [org.clojars.akiel/env-tools "0.2"]
   [org.clojars.akiel/shortid "0.1.2"]
   [org.clojars.akiel/spec-coerce "0.2"]
   [org.clojure/clojure "1.9.0"]
   [org.clojure/spec.alpha "0.2.176"]
   [phrase "0.3-alpha3"]
   [ring/ring-core "1.7.0"
    :exclusions [clj-time commons-codec commons-fileupload
                 commons-io crypto-equality crypto-random]]
   [com.taoensso/timbre "4.10.0"]]

  :profiles
  {:dev
   {:source-paths ["dev"]
    :dependencies
    [[org.clojure/tools.namespace "0.2.11"]]}

   :uberjar
   {:aot [broker-search-store.core]}}

  :main ^:skip-aot broker-search-store.core)
