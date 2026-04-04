FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 2220 2121 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
