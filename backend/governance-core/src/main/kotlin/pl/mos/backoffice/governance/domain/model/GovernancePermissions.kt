package pl.mos.backoffice.governance.domain.model

/**
 * Permissions the Governance context checks. It defines no new keys (docs/architecture/08-identity-and-access.md):
 * attestations and delivery insights are project data, visible with `project.view` of the project.
 */
object GovernancePermissions {
    const val PROJECT_VIEW = "project.view"
}
