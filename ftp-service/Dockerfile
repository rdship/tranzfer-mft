FROM eclipse-temurin:25-jre
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && \
    addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/ftp && \
    chown -R appuser:appgroup /app /data/ftp && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --destination /app/extracted && rm app.jar && chown -R appuser:appgroup /app/extracted

# FTP binds to port 2121 (non-privileged) internally; Docker/K8s maps 21->2121 externally
USER appuser
EXPOSE 2121 8082
ENTRYPOINT ["java", "-jar", "app.jar", "--ftp.port=2121"]
