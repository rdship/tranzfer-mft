FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p outbox inbox sent failed && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/mft-client-*.jar mft-client.jar
COPY --chown=appuser:appgroup src/main/resources/mft-client-example.yml mft-client.yml
VOLUME ["/app/outbox", "/app/inbox", "/app/sent", "/app/failed"]

USER appuser
ENTRYPOINT ["java", "-jar", "mft-client.jar"]
