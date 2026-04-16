FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/sftp && \
    chown -R appuser:appgroup /app /data/sftp && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

# SFTP binds to port 2222 — non-privileged, OK
USER appuser
EXPOSE 2222 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
