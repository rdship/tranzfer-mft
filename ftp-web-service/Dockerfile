FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/ftpweb && \
    chown -R appuser:appgroup /app /data/ftpweb && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

USER appuser
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
