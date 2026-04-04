FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/keystore-manager-*.jar app.jar
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]
