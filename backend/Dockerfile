# Build stage: compile and package the Spring Boot fat jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY config config
COPY src src
RUN mvn -q -B -DskipTests package

# Runtime stage: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /build/target/vacancy-tracker-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
