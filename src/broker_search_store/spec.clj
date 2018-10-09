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

(s/def :event-stream/type
  string?)

(s/def :event-stream/topic
  (s/coll-of string?))

(s/def :event-stream/messages
  (s/coll-of :event-stream/message))

(defmulti event-stream-message-type :type)

(defmethod event-stream-message-type "subscribe" [_]
  (s/keys :req-un [:event-stream/topic]))

(defmethod event-stream-message-type "subscribed" [_]
  (s/keys :req-un [:event-stream/topic]))

(defmethod event-stream-message-type "unsubscribe" [_]
  (s/keys :req-un [:event-stream/topic]))

(defmethod event-stream-message-type "unsubscribed" [_]
  (s/keys :req-un [:event-stream/topic]))

(defmethod event-stream-message-type "ping" [_]
  (s/keys :req-un [:event-stream/type]))

(defmethod event-stream-message-type "pong" [_]
  (s/keys :req-un [:event-stream/type]))

(s/def :event-stream/message
  (s/multi-spec event-stream-message-type :type))
