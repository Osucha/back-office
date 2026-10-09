package pl.mos.backoffice.governance.domain.model

/**
 * Permissions the Governance context checks. It defines no new keys (docs/architecture/08-identity-and-access.md):
 * attestations and delivery insights are project data, visible with `project.view` of the project; the metrics of a
 * library workflow (ADR 0037) also need `library.view` (Task Library context).
 */
object GovernancePermissions {
    const val PROJECT_VIEW = "project.view"
    const val LIBRARY_VIEW = "library.view"
}
