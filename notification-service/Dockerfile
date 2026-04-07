FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/notification-service-*.jar app.jar

USER appuser
EXPOSE 8097
ENTRYPOINT ["java", "-jar", "app.jar"]
