FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/license-service-*.jar app.jar

USER appuser
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
