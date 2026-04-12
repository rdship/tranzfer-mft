FROM eclipse-temurin:25-jre
WORKDIR /app

RUN addgroup --system appgroup && adduser --system --ingroup appgroup appuser && \
    mkdir -p /data/ftp && \
    chown -R appuser:appgroup /app /data/ftp && \
    mkdir -p /tmp && chmod 1777 /tmp

COPY --chown=appuser:appgroup target/*.jar app.jar

# FTP binds to port 2121 (non-privileged) internally; Docker/K8s maps 21->2121 externally
USER appuser
EXPOSE 2121 8082
ENTRYPOINT ["java", "-jar", "app.jar", "--ftp.port=2121"]
