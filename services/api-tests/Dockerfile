FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
WORKDIR /app
COPY services/${SERVICE}/target/${SERVICE}-1.0.0.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
