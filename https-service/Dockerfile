FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp && \
    mkdir -p /data/https-keystores && chown -R appuser:appgroup /data

COPY --chown=appuser:appgroup target/*.jar app.jar

USER appuser
EXPOSE 8099 443 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
