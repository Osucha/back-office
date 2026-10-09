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
