plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}
description = "Spring AI implementation of the LlmGateway port for all providers, usage ledger (JPA) and /usage API"

dependencies {
    implementation(project(":shared-kernel"))
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.ai.anthropic)
    implementation(libs.spring.ai.openai)
    implementation(libs.spring.ai.google.genai)
    implementation(libs.spring.ai.mistral)
    implementation(libs.spring.ai.ollama)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.micrometer.tracing.otel)
    implementation(libs.springdoc)
    // distributed provider rate limits (ADR 0023)
    implementation(libs.bucket4j.core)
    implementation(libs.bucket4j.postgresql)
    runtimeOnly(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.wiremock)
}
