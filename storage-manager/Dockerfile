FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/storage/hot /data/storage/warm /data/storage/cold && \
    chown -R appuser:appgroup /app /data/storage && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/storage-manager-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted && rm app.jar && chown -R appuser:appgroup /app/extracted

USER appuser
EXPOSE 8096
ENTRYPOINT ["java", "-cp", "/app/extracted/lib/*:/app/extracted/app.jar", "org.springframework.boot.loader.launch.JarLauncher"]
