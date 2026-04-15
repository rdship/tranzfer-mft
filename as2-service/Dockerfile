FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    groupadd -r appgroup && useradd -r -g appgroup -d /app appuser && \
    mkdir -p /data/as2 && \
    chown -R appuser:appgroup /app /data/as2 && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted && rm app.jar && chown -R appuser:appgroup /app/extracted

USER appuser
EXPOSE 8094
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8094/internal/health || exit 1
ENTRYPOINT ["java", "-cp", "/app/extracted/lib/*:/app/extracted/app.jar", "org.springframework.boot.loader.launch.JarLauncher"]
