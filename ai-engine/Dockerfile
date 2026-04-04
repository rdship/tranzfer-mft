FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/ai-engine-*.jar app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "app.jar"]
