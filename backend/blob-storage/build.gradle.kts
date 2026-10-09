plugins {
    alias(libs.plugins.kotlin.spring)
}
description = "BlobStore port implementations: S3-compatible object storage (AWS SDK v2) and a local filesystem store with signed URLs"

dependencies {
    implementation(project(":shared-kernel"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlin.reflect)

    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3) {
        // the sync client uses the Apache HTTP client; the Netty client is only needed by S3AsyncClient
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awssdk.apache.client)

    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.testcontainers.minio)
    testImplementation(libs.testcontainers.junit)
}
