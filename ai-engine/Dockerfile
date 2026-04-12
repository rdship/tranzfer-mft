FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/ai-engine-*.jar app.jar

USER appuser
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
