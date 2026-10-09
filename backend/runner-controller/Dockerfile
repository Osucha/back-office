# syntax=docker/dockerfile:1.7
# runner-controller image (ADR 0024, docs/architecture/10-agent-execution.md).
#
# Build from backend/:  docker build -f runner-controller/Dockerfile -t back-office/runner-controller:dev .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY config ./config
RUN chmod +x gradlew && ./gradlew --version --no-daemon
# All module directories are copied because settings.gradle.kts includes them; only :runner-controller is built.
COPY . .
ARG APP_VERSION=0.1.0
ENV APP_VERSION=${APP_VERSION}
RUN --mount=type=cache,target=/root/.gradle ./gradlew :runner-controller:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre AS runtime
RUN useradd --system --uid 10001 --create-home controller
WORKDIR /app
COPY --from=build /workspace/runner-controller/build/libs/runner-controller.jar /app/runner-controller.jar
USER 10001
EXPOSE 8090
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC" \
    RUNNER_CONTROLLER_BACK_OFFICE_CREDENTIAL_FILE=/tmp/runner-credentials.json
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD wget -qO- http://localhost:8090/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-jar", "/app/runner-controller.jar"]
