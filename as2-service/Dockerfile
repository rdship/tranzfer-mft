FROM eclipse-temurin:21-jre
WORKDIR /app
RUN mkdir -p /data/as2
COPY target/*.jar app.jar
EXPOSE 8094
ENTRYPOINT ["java", "-jar", "app.jar"]
