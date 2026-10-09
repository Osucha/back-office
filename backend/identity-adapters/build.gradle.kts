plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}
description = "Identity & Access adapters: OIDC BFF login, local accounts, sessions, token filters, AuthorizationGate, audit log, admin REST"

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":identity-core"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.spring.boot.starter.cache)
    implementation(libs.caffeine)
    implementation(libs.bouncycastle.prov)
    implementation(libs.qrcodegen)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc)

    runtimeOnly(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.spring.boot.starter.flyway)
    testImplementation(libs.flyway.postgresql)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.keycloak)
}
