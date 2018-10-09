# Event Stream Protocol

The search store exposes a web socket under `/event-tream`. The following document describes the protocol of this web socket. The general theme of the protocol is a publish/subscribe protocol, were client subscribe to events and the server publishes events.

## Message Encoding

Messages are strings with JSON encoded data. The top-level JSON entity is an object with at least one `type` property. That `type` property differentiates one message type from others.

## Client -> Server Messages

The following messages are send from the client to the server:

### Subscribe

Subscribe messages are used by the client to subscribe to an event topic. Event topics consist of a list of strings were the first two strings are the namespace and the name of the event to subscribe to. The remaining elements of the topic are parameters of the subscription. In the following example the topic `search/criterion-added` has one parameter, the search identifier.

```json
{"type"  : "subscribe",
 "topic" : ["search", "criterion-added", "7yyrg"]
}
```

### Unsubscribe

In the same manner as `subscribe` messages, the `unsubscribe` message is used by clients to unsubscribe from a topic.

```json
{"type"  : "unsubscribe",
 "topic" : ["search", "criterion-added", "7yyrg"]
}
```

### Ping

TODO

```json
{"type" : "ping"}
```

## Server -> Client Messages

The following messages are send from the server to the client:

### Subscribed

The server acknowledges `subscribe` messages with `subscribed` messages which also carry the topic for reference.

```json
{"type"  : "subscribed",
 "topic" : ["search", "criterion-added", "7yyrg"]
}
```

### Unsubscribed

The server acknowledges `unsubscribe` messages with `unsubscribed` messages which also carry the topic for reference.

```json
{"type"  : "unsubscribed",
 "topic" : ["search", "criterion-added", "7yyrg"]
}
```

### Pong

TODO

```json
{"type" : "pong"}
```

### Events

Events have always three properties `type`, `topic` and `data`. The type is always `event`. The topic is the topic for which a client subscribed. The topic also contains the event namespace and name as its first two elements. The `data` property contains the event data itself. The structure of the data depends on the event name. The example shows the `search/criterion-added` event.

```json
{"type"  : "event",
 "topic" : ["search", "criterion-added", "7yyrg"],
 "data"  : {
     "mdr-key"         : "urn:mdr16:dataelement:16:1",
     "type"            : "enumerated",
     "selected-values" : ["bone_marrow"]
 }
}
```

#### Search Criterion Added

```json
{"type"  : "event",
 "topic" : ["search", "criterion-added", "4kNQm"],
 "data"  : {
     "mdr-key"      : "urn:mdr16:dataelement:29:1",
     "type"         : "float",
     "metric-query" : {
         "type"        : "between",
         "lower-bound" : 1.0,
         "upper-bound" : 2.0
     }
 }
}
```

#### Search Criterion Edited

```json
{"type"  : "event",
 "topic" : ["search", "criterion-edited", "4kNQm"],
 "data"  : {
     "mdr-key"         : "urn:mdr16:dataelement:23:1",
     "type"            : "enumerated",
     "selected-values" : [
         "female",
         "male",
         "sex_other/intersexual"
     ]
 }
}
```
