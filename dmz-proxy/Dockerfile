FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
# Management API + all proxied ports
EXPOSE 8088 21 443 2222
ENTRYPOINT ["java", "-jar", "app.jar"]
