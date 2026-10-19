FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 10001 timeline
COPY --from=build /workspace/target/proj-timeline-backend-1.0.0.jar app.jar
USER timeline
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
