FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/screening-service-*.jar app.jar

USER appuser
EXPOSE 8092
ENTRYPOINT ["java", "-jar", "app.jar"]
