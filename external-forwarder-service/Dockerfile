FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

USER appuser
EXPOSE 8087
ENTRYPOINT ["java", "-jar", "app.jar"]
