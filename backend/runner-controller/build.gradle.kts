plugins {
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}
description = "runner-controller: execution plane of the runner fleet (ADR 0024). Leases agent jobs from Back Office " +
    "and runs each in a sandbox (Agent Sandbox CRDs, Kubernetes Job, or Docker for development)."

dependencies {
    // Deliberately no dependency on the Back Office modules: the controller talks to Back Office only over the
    // runner v2 HTTP protocol (docs/api/openapi.yaml, tag runner-v2) and can be released independently.
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.fabric8.client)
    // ADR 0051: HPKE (RFC 9180) to open the project repository tokens Back Office seals to this controller
    implementation(libs.bouncycastle.prov)
    implementation("io.micrometer:micrometer-registry-prometheus")
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.fabric8.server.mock)
}

springBoot {
    mainClass.set("pl.mos.backoffice.runnercontroller.RunnerControllerApplicationKt")
    buildInfo()
}

tasks.bootJar {
    archiveFileName.set("runner-controller.jar")
}

tasks.jar {
    enabled = false
}

tasks.withType<Test>().configureEach {
    // The Docker end-to-end test needs the runner and egress-proxy images; it skips itself when Docker or the
    // images are missing. Pass -Pe2e to fail instead of skipping.
    systemProperty("runnerController.e2e.required", project.hasProperty("e2e").toString())
    project.findProperty("runnerImage")?.let { systemProperty("runnerController.e2e.runnerImage", it.toString()) }
    project.findProperty("egressProxyImage")?.let { systemProperty("runnerController.e2e.egressProxyImage", it.toString()) }
}
