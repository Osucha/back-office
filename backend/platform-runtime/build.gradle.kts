plugins {
    alias(libs.plugins.kotlin.spring)
}
description = "Scale-out backbone (ADR 0023): JDBC work queue and worker loop, ShedLock singletons, event_log outbox with LISTEN/NOTIFY bridge, cache channel, roles, instance registry, operations API, migrate role"

dependencies {
    implementation(project(":shared-kernel"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.micrometer.core)
    implementation(libs.shedlock.core)
    implementation(libs.shedlock.jdbc)
    // PGConnection#getNotifications for the LISTEN bridge
    implementation(libs.postgresql)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
