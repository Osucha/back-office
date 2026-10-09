plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}
description = "Projects & Pipelines adapters: REST, JPA, LangGraph4j engine, Kubernetes/Docker runner, GitLab, Jira, SSE"

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":projects-core"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc)

    implementation(libs.langgraph4j.core)
    implementation(libs.langgraph4j.postgres)
    implementation("org.springframework:spring-expression")
    implementation(libs.fabric8.client)
    implementation(libs.micrometer.core)
    implementation(libs.caffeine)

    runtimeOnly(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.fabric8.server.mock)
    testImplementation(libs.wiremock)
    testImplementation(project(":platform-runtime"))
}

// The CI client script lives at the repository root (ci/backoffice-ci.sh, owned by the infra part of the
// repo) and is served by the API at GET /ci/backoffice-ci.sh; bundle it as a classpath resource.
tasks.processResources {
    from(rootProject.projectDir.resolve("../ci")) {
        include("backoffice-ci.sh")
        into("ci")
    }
}
