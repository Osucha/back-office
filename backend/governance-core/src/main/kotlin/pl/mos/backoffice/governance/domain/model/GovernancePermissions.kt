package pl.mos.backoffice.governance.domain.model

import pl.mos.backoffice.shared.security.LibraryAccessPermissions
import pl.mos.backoffice.shared.security.ProjectAccessPermissions

/**
 * Permissions the Governance context checks. It defines no new keys (docs/architecture/08-identity-and-access.md):
 * attestations and delivery insights are project data, visible with `project.view` of the project; the metrics of a
 * library workflow (ADR 0037) also need `library.view` (Task Library context).
 */
object GovernancePermissions {
    val PROJECT_VIEW = ProjectAccessPermissions.VIEW
    val LIBRARY_VIEW = LibraryAccessPermissions.VIEW
}
