FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/sftp && \
    chown -R appuser:appgroup /app /data/sftp && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

# SFTP binds to port 2222 — non-privileged, OK
USER appuser
EXPOSE 2222 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
