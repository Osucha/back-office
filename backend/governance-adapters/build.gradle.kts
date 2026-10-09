plugins {
    alias(libs.plugins.kotlin.spring)
}
description = "Governance adapters: Ed25519 signer, attestation store (JDBC), delivery insights read model, REST, MCP server"

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":governance-core"))
    // The MCP server is a driving adapter composing the use cases of several contexts (like `app`):
    // it may import their `application.port.in` packages (and the domain types those return) only.
    implementation(project(":projects-core"))
    implementation(project(":tasks-core"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc)
    implementation(libs.spring.ai.mcp.server.webmvc)

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

// @McpTool parameters are discovered through java.lang.reflect.Parameter names
kotlin {
    compilerOptions {
        javaParameters.set(true)
    }
}
