# syntax=docker/dockerfile:1.7
# Build stage: compile all modules and produce the Boot jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
# CR stripped: a Windows checkout may give gradlew CRLF line endings ("./gradlew: not found")
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --version --no-daemon
# All modules are copied because settings.gradle.kts includes them (build output is excluded by
# .dockerignore). One image serves the api, worker and migrate roles (BACKOFFICE_ROLE, ADR 0023).
COPY . .
ARG APP_VERSION=0.1.0
ENV APP_VERSION=${APP_VERSION}
RUN --mount=type=cache,target=/root/.gradle sed -i 's/\r$//' gradlew && ./gradlew :app:bootJar -x test --no-daemon -Pkotlin.compiler.execution.strategy=in-process

# Runtime stage: minimal JRE, non-root, layered jar for fast rebuilds.
FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
