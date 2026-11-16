rootProject.name = "back-office"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // port-permissions (ADR 0066) is published on JitPack only; limited to its group.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.Osucha.port-permissions") }
        }
    }
}

include(
    "shared-kernel",
    "projects-core",
    "projects-adapters",
    "evaluation-core",
    "evaluation-adapters",
    "identity-core",
    "identity-adapters",
    "tasks-core",
    "tasks-adapters",
    "governance-core",
    "governance-adapters",
    "llm-gateway",
    "agent-runner",
    "platform-runtime",
    "runner-controller",
    "blob-storage",
    "app",
)
