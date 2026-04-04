FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/mft-client-*.jar mft-client.jar
RUN mkdir -p outbox inbox sent failed
VOLUME ["/app/outbox", "/app/inbox", "/app/sent", "/app/failed"]
COPY src/main/resources/mft-client-example.yml mft-client.yml
ENTRYPOINT ["java", "-jar", "mft-client.jar"]
