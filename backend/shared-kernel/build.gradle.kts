// Pure Kotlin. No Spring, no JPA, no Jackson. Enforced by ArchUnit in :app.
// testFixtures: FakeAccessGuard and RecordingAuditPort for every module's unit tests.
plugins {
    `java-test-fixtures`
}
description = "Shared kernel: identifiers, money, clock, domain events, LLM gateway and usage ledger ports"

dependencies {
    // Nothing beyond the Kotlin standard library, except port-permissions-core (Kotlin stdlib only, ADR 0066):
    // typed permissions, the AccessGuard port and the catalogue every module contributes to.
    api(libs.port.permissions.core)
    testFixturesApi(libs.port.permissions.test)
}
