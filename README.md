[![Docker Automated build](https://img.shields.io/docker/automated/akiel/samply.broker.search.store.svg)](https://hub.docker.com/r/akiel/samply.broker.search.store/)

# Samply Broker Search Store

This project contains a service which persists searches created by the 
[Samply Broker UI Material Prototype][1]

## Environment

* PORT - the port under which the service should listen
* DATABASE_URI - the URI of the Datomic database to connect to

## Usage

```bash
docker run -e PORT="8080" -e DATABASE_URI="datomic:mem://store" -p 8080:8080 akiel/samply.broker.search.store:latest
```

## License

Copyright © 2018 Alexander Kiel

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.

[1]: <https://github.com/alexanderkiel/samply.broker.ui.material>
