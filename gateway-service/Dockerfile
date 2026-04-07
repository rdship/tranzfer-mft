FROM eclipse-temurin:21-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    chown -R appuser:appgroup /app && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

# Gateway binds to 2220/2121/8085 — all non-privileged
USER appuser
EXPOSE 2220 2121 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
