import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.sonarqube)
    jacoco
}

// Aggregated coverage of all modules (GR-Q3); produced by `jacocoAggregatedReport` below.
val jacocoAggregateXml: Provider<RegularFile> =
    layout.buildDirectory.file("reports/jacoco/aggregate/jacocoAggregatedReport.xml")

allprojects {
    group = "pl.mos.backoffice"
    version = System.getenv("APP_VERSION") ?: "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
        }
    }

    // Spring Boot and Spring AI BOMs for every module so versions stay consistent,
    // including pure-Kotlin core modules that only use them for test libraries.
    extensions.configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:${rootProject.libs.versions.springBoot.get()}")
            mavenBom("org.springframework.ai:spring-ai-bom:${rootProject.libs.versions.springAi.get()}")
        }
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
        "testImplementation"(rootProject.libs.kotlin.test.junit5)
        "testImplementation"(rootProject.libs.mockk)
        "testImplementation"(rootProject.libs.assertj)
        if (project.name != "shared-kernel") {
            "testImplementation"(testFixtures(project(":shared-kernel")))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    // Static analysis (docs/architecture/guardrails.md, GR-Q1 and friends). The bans in
    // config/detekt/detekt.yml (println, System.out, Thread.sleep, argument-less now()) need type
    // resolution, i.e. the `detektMain` / `detektTest` tasks; the plain `detekt` task skips them.
    // NOT wired into `check` yet: the existing code has violations to clean up first. Run explicitly:
    //   ./gradlew detektMain detektTest --continue
    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig.set(true)
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel.set(true)
        // Report paths relative to the module, as the SonarQube detekt import (GR-Q3) resolves them.
        basePath.set(layout.projectDirectory)
    }
    tasks.named("check") {
        setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name.startsWith("detekt") })
    }

    // Coverage (GR-Q3, ADR 0027): every Test task runs with the JaCoCo agent; the XML that SonarQube
    // reads is the aggregate below (integration tests in `app` cover classes of the other modules).
    apply(plugin = "jacoco")
    extensions.configure<JacocoPluginExtension> {
        toolVersion = rootProject.libs.versions.jacoco.get()
    }

    // Per-module SonarQube settings. Sources/tests/binaries/libraries come from the Gradle source sets.
    // The aggregated report is imported by every module (SonarQube matches it by file path).
    sonar {
        properties {
            property("sonar.coverage.jacoco.xmlReportPaths", jacocoAggregateXml.get().asFile.absolutePath)
            // Do not inherit the frontend/Python test filter of the root module.
            property("sonar.test.inclusions", "**/*")
            // detekt findings as external issues when `detektMain detektTest` ran before (CI backend:detekt).
            property(
                "sonar.kotlin.detekt.reportPaths",
                listOf("main", "test").map { layout.buildDirectory.file("reports/detekt/$it.xml").get().asFile }
                    .filter { it.exists() }.joinToString(","),
            )
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Code quality gate (GR-Q3, ADR 0027, docs/guides/code-quality.md)
//
//   ./gradlew test jacocoAggregatedReport --no-daemon --continue     # coverage of all modules
//   SONAR_TOKEN=... ./gradlew sonar -Dsonar.host.url=...            # scan (or deploy/scripts/sonar-local.sh)
//
// ONE SonarQube project `back-office` covers the whole repository: the Gradle modules (Kotlin) as
// modules, plus the frontend (TypeScript) and the Python of runner/ and ci/ in the root module, whose
// base directory is the repository root. Frontend and Python coverage reports must exist before the scan
// (`npm run test:ci` in frontend/, coverage.py XML, see .coveragerc); missing reports only mean 0 % coverage for those files.
// ------------------------------------------------------------------------------------------------
val repoRoot: File = rootDir.parentFile

val jacocoAggregatedReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Aggregated JaCoCo XML/HTML report of all modules (input of the SonarQube scan)"
    subprojects.forEach { p -> mustRunAfter(p.tasks.withType<Test>()) }
    executionData.from(
        files(subprojects.map { it.layout.buildDirectory.dir("jacoco") })
            .asFileTree.matching { include("*.exec") },
    )
    sourceDirectories.from(subprojects.map { it.file("src/main/kotlin") })
    classDirectories.from(subprojects.map { it.layout.buildDirectory.dir("classes/kotlin/main") })
    reports {
        xml.required.set(true)
        xml.outputLocation.set(jacocoAggregateXml)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate/html"))
        csv.required.set(false)
    }
}

sonar {
    properties {
        property("sonar.projectKey", System.getenv("SONAR_PROJECT_KEY") ?: "back-office")
        property("sonar.projectName", "Back Office")
        // SonarQube Cloud only (ADR 0027): the organization key
        System.getenv("SONAR_ORGANIZATION")?.takeIf { it.isNotBlank() }?.let { property("sonar.organization", it) }
        // The root module spans the repository so frontend and Python land in the same project.
        property("sonar.projectBaseDir", repoRoot.absolutePath)
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.scm.provider", "git")
        property(
            "sonar.sources",
            listOf("frontend/src", "runner/entrypoint.py", "ci/check-trivy-ignore.py").joinToString(","),
        )
        property("sonar.tests", listOf("frontend/src", "runner/tests", "ci/tests").joinToString(","))
        property(
            "sonar.test.inclusions",
            listOf("frontend/src/**/*.spec.ts", "runner/tests/**/*.py", "ci/tests/**/*.py").joinToString(","),
        )
        // Generated or non-code content. Patterns are relative to each module's base directory, so the
        // frontend ones never match inside a Gradle module.
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**",
                "**/node_modules/**",
                "**/.angular/**",
                "frontend/src/**/*.spec.ts",
                "frontend/src/test-setup.ts",
                // openapi-typescript output (GR-D1): generated from docs/api/openapi.yaml
                "frontend/src/app/core/api/schema.d.ts",
                "frontend/src/locale/**",
                "frontend/public/**",
                "frontend/e2e/**",
                "**/__pycache__/**",
            ).joinToString(","),
        )
        property(
            "sonar.coverage.exclusions",
            listOf("frontend/src/main.ts", "frontend/src/bootstrap.ts", "**/*.d.ts").joinToString(","),
        )
        property("sonar.cpd.exclusions", "frontend/src/locale/**")
        property("sonar.javascript.lcov.reportPaths", "frontend/coverage/back-office-web/lcov.info")
        property("sonar.python.version", "3.12")
        property("sonar.python.coverage.reportPaths", "build/coverage/python-coverage.xml")
        // Accepted findings: reviewed decisions of the owner, listed with reasons in
        // docs/quality/sonarqube-baseline.md ("Accepted findings"). Each entry is ONE rule in ONE file;
        // `**/` makes the pattern match whether the scanner sees the path relative to the module or to
        // the repository root. Adding an entry here is a reviewed change like any other code.
        val acceptedFindings = listOf(
            // RFC 6238/4226 TOTP requires HMAC-SHA1 (authenticator apps only speak it); SHA-1 is used as a
            // keyed MAC, not as a collision-resistant hash, where its weakness does not apply.
            "kotlin:S4790" to "**/identity/domain/service/Totp.kt",
            // The hard-coded CIDRs are the egress DENY-list (RFC 1918, link-local incl. cloud metadata
            // 169.254.169.254, CGNAT) put into the NetworkPolicy `except`; overridable in configuration.
            "kotlin:S1313" to "**/runnercontroller/config/ControllerProperties.kt",
            // `password=` is the git credential-helper protocol line; the value is read at run time from
            // the token file / $GIT_TOKEN, no secret is in the code.
            "python:S2068" to "**/runner/entrypoint.py",
            // The single reviewed bypass of Angular sanitisation: it only ever receives DOMPurify output
            // with an explicit allow-list (XSS payload tests in safe-html.spec.ts).
            "typescript:S6268" to "**/core/ui/safe-html.ts",
        )
        property("sonar.issue.ignore.multicriteria", acceptedFindings.indices.joinToString(",") { "a$it" })
        acceptedFindings.forEachIndexed { i, (rule, file) ->
            property("sonar.issue.ignore.multicriteria.a$i.ruleKey", rule)
            property("sonar.issue.ignore.multicriteria.a$i.resourceKey", file)
        }
    }
}
