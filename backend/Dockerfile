# syntax=docker/dockerfile:1
# Build context is the repository root (see docker-compose.yml).

# Stage 1: build the Preact UI; Vite writes the bundle into backend/src/main/resources/static.
FROM node:20-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# Stage 2: compile and package the Spring Boot fat jar with the UI bundled.
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /build
COPY backend/pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY backend/config config
COPY backend/src src
COPY --from=web /src/backend/src/main/resources/static src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# Stage 3: JRE only, non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /build/target/vacancy-tracker-*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
