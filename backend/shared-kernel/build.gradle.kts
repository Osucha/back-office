// Pure Kotlin. No Spring, no JPA, no Jackson. Enforced by ArchUnit in :app.
// testFixtures: FakeAuthorizationGate and RecordingAuditPort for every module's unit tests.
plugins {
    `java-test-fixtures`
}
description = "Shared kernel: identifiers, money, clock, domain events, LLM gateway and usage ledger ports"

dependencies {
    // intentionally nothing beyond the Kotlin standard library
}
