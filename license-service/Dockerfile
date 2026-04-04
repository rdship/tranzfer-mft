FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/license-service-*.jar app.jar
EXPOSE 8089
ENTRYPOINT ["java", "-jar", "app.jar"]
