FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/edi-converter-*.jar app.jar
EXPOSE 8095
ENTRYPOINT ["java", "-jar", "app.jar"]
