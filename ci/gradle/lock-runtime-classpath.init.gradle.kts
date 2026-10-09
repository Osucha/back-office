// Gradle init script for the Trivy dependency scan (job security:dependencies, guardrail GR-S2).
//
// Trivy's filesystem scanner reads Gradle dependencies only from `gradle.lockfile`s, and the backend
// does not commit lockfiles. This script turns on dependency locking for the `runtimeClasspath` of every
// module - only in the CI job that uses it, the build itself is unchanged - so that
//
//   ./gradlew -I ../ci/gradle/lock-runtime-classpath.init.gradle.kts writeRuntimeLocks --write-locks
//
// writes <module>/gradle.lockfile with exactly the libraries that end up in the application, and
// `trivy fs backend/` can scan them. Test-only libraries are deliberately left out.
allprojects {
    configurations.matching { it.name == "runtimeClasspath" }.configureEach {
        resolutionStrategy.activateDependencyLocking()
    }
    tasks.register("writeRuntimeLocks") {
        group = "verification"
        description = "Resolves runtimeClasspath so that --write-locks writes gradle.lockfile (Trivy input)."
        notCompatibleWithConfigurationCache("resolves a configuration at execution time")
        val runtime = configurations.findByName("runtimeClasspath")
        doLast {
            runtime?.resolve()
        }
    }
}
