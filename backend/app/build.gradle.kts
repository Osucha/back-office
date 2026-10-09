plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}
description = "Spring Boot application: wiring, security, Flyway, observability, architecture tests"

dependencies {
    implementation(project(":shared-kernel"))
    implementation(project(":projects-core"))
    implementation(project(":projects-adapters"))
    implementation(project(":evaluation-core"))
    implementation(project(":evaluation-adapters"))
    implementation(project(":identity-core"))
    implementation(project(":identity-adapters"))
    implementation(project(":tasks-core"))
    implementation(project(":tasks-adapters"))
    implementation(project(":llm-gateway"))
    implementation(project(":agent-runner"))
    implementation(project(":platform-runtime"))
    implementation(project(":governance-core"))
    implementation(project(":governance-adapters"))
    implementation(project(":blob-storage"))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.springdoc)
    implementation(libs.micrometer.tracing.otel)
    implementation(libs.otel.exporter.otlp)
    // /actuator/prometheus (docs/architecture/09-scale-and-operations.md, Observability)
    runtimeOnly(libs.micrometer.prometheus)
    runtimeOnly(libs.postgresql)
    developmentOnly(libs.spring.boot.docker.compose)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.starter.session.jdbc)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.archunit)
    testImplementation(libs.spring.ai.mcp.client)
}

springBoot {
    mainClass.set("pl.mos.backoffice.app.BackOfficeApplicationKt")
    buildInfo()
}

// Query-plan review on a seeded dataset (docs/architecture/performance.md); not part of `check`.
tasks.test {
    useJUnitPlatform { excludeTags("perf") }
}

val perfTest by tasks.registering(Test::class) {
    description = "Seeds a large dataset and writes build/perf/query-plans.md (EXPLAIN ANALYZE of the hot queries)"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("perf") }
    maxHeapSize = "1g"
    outputs.upToDateWhen { false }
    listOf("perf.projects", "perf.runs", "perf.usage", "perf.audit", "perf.jdbcUrl", "perf.user", "perf.password", "perf.seedOnly").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    testLogging { showStandardStreams = true }
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

// The local profile resolves `spring.docker.compose.file` (../deploy/compose/...) against the working
// directory: run from backend/, as `cd backend && ./gradlew :app:bootRun` suggests.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}
