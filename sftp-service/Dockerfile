FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data/sftp
COPY target/*.jar app.jar
EXPOSE 2222 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
