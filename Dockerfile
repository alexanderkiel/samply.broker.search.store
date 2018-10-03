FROM clojure:lein-2.8.1 as build

COPY . /build/

WORKDIR /build
RUN lein uberjar

FROM openjdk:8u171-jre-alpine3.8

COPY --from=build /build/target/samply.broker.search.store-latest-standalone.jar /app/

WORKDIR /app

EXPOSE 80 5555

CMD ["/bin/sh", "-c", "java $JVM_OPTS -Dclojure.server.repl=\"{:address \\\"0.0.0.0\\\" :port 5555 :accept clojure.core.server/repl}\" -jar samply.broker.search.store-latest-standalone.jar"]
