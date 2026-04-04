FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data/ftp
COPY target/*.jar app.jar
EXPOSE 21 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
