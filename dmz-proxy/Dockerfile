FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

# DMZ proxy uses Netty on high ports (8088, 32222, 32121, 4443) — no privileged ports
USER appuser
EXPOSE 8088 32222 32121 4443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
