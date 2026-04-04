FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/storage-manager-*.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "app.jar"]
