FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/storage/hot /data/storage/warm /data/storage/cold && \
    chown -R appuser:appgroup /app /data/storage && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/storage-manager-*.jar app.jar

USER appuser
EXPOSE 8096
ENTRYPOINT ["java", "-jar", "app.jar"]
