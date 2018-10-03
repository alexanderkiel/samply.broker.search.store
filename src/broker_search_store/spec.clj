(ns broker-search-store.spec
  (:require
    [clojure.spec.alpha :as s]))

;; A symbol like create-subject in imperative form.
(s/def :gba.command/name
  symbol?)

(s/def :gba.command/params
  map?)

;; A command is something which a subject likes to do in a system.
(s/def :gba/command
  (s/keys :req [:gba.command/name]
          :opt [:gba.command/params]))
