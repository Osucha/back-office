plugins {
    alias(libs.plugins.kotlin.spring)
}
description = "Agent execution control plane (ADR 0024): runner fleet protocol v2, agent job queue, job tokens, sandbox policy resolution"

dependencies {
    implementation(project(":shared-kernel"))

    implementation(libs.spring.boot.starter.webmvc)
    // JDBC + transactions (the queue needs FOR UPDATE SKIP LOCKED and advisory locks, so no JPA entities here)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc)
    // ADR 0051: HPKE (RFC 9180) to seal project repository tokens to a runner-controller's key
    implementation(libs.bouncycastle.prov)

    runtimeOnly(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
