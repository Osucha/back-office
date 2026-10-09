// Pure Kotlin. Domain + application layers of the Governance context (ADR 0025): provenance attestations
// (in-toto Statement v1 in DSSE envelopes), delivery insights. JDK crypto only, no frameworks.
description = "Governance: provenance attestations, delivery insights - domain model, use cases and ports"

dependencies {
    api(project(":shared-kernel"))
}
