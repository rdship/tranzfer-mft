FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data/as2 && \
    groupadd -r appuser && useradd -r -g appuser -d /app appuser && \
    chown -R appuser:appuser /app /data/as2
COPY --chown=appuser:appuser target/*.jar app.jar
USER appuser
EXPOSE 8094
HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
    CMD curl -sf http://localhost:8094/internal/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
