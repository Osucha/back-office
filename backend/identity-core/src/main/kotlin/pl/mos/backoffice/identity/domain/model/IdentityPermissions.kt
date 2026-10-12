package pl.mos.backoffice.identity.domain.model

import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope

/** Permissions owned by the identity context (catalogue table in 08-identity-and-access.md). */
object IdentityPermissions {
    const val USERS_VIEW = "admin.users.view"
    const val USERS_MANAGE = "admin.users.manage"
    const val ROLES_MANAGE = "admin.roles.manage"
    const val AUDIT_VIEW = "admin.audit.view"
    const val PROJECTS_ALL = "admin.projects.all"
    const val SETTINGS_MANAGE = "admin.settings.manage"
    const val TOKENS_PERSONAL = "tokens.personal"
    const val PROJECT_MEMBERS_MANAGE = "project.members.manage"

    const val MODULE = "identity"

    val definitions: List<PermissionDefinition> = listOf(
        PermissionDefinition(USERS_VIEW, PermissionScope.PLATFORM, MODULE, "List users, groups and memberships"),
        PermissionDefinition(USERS_MANAGE, PermissionScope.PLATFORM, MODULE, "Invite, edit and deactivate users; manage groups"),
        PermissionDefinition(ROLES_MANAGE, PermissionScope.PLATFORM, MODULE, "Edit roles and platform grants"),
        PermissionDefinition(AUDIT_VIEW, PermissionScope.PLATFORM, MODULE, "Read the audit log"),
        PermissionDefinition(PROJECTS_ALL, PermissionScope.PLATFORM, MODULE, "Every project permission on every project"),
        PermissionDefinition(SETTINGS_MANAGE, PermissionScope.PLATFORM, MODULE, "Platform settings (registration mode, domains)"),
        PermissionDefinition(TOKENS_PERSONAL, PermissionScope.PLATFORM, MODULE, "Create own personal access tokens"),
        PermissionDefinition(PROJECT_MEMBERS_MANAGE, PermissionScope.PROJECT, MODULE, "Add/remove members and change their project roles"),
    )
}

/**
 * Built-in roles seeded on startup (editable, not deletable). Keys of other modules are spelled out here
 * because identity-core must not depend on them; they are the catalogue keys from 08-identity-and-access.md.
 */
object BuiltInRoles {
    const val PLATFORM_ADMIN = "platform-admin"
    const val MEMBER = "member"
    const val PROJECT_MANAGER = "project-manager"
    const val EVALUATOR = "evaluator"
    const val LIBRARY_CURATOR = "library-curator"
    const val AUDITOR = "auditor"
    const val OWNER = "owner"
    const val MAINTAINER = "maintainer"
    const val DEVELOPER = "developer"
    const val APPROVER = "approver"
    const val VIEWER = "viewer"

    // Catalogue keys of other modules used by more than one role.
    private const val LIBRARY_VIEW = "library.view"
    private const val MODELS_VIEW = "models.view"
    private const val EVALUATION_VIEW = "evaluation.view"
    private const val USAGE_VIEW = "usage.view"
    private const val PROJECT_VIEW = "project.view"
    private const val PROJECT_USAGE_VIEW = "project.usage.view"

    /** Own personal connections (ADR 0035): Jira as yourself, used only with your own approval or run start. */
    private const val CONNECTIONS_PERSONAL = "connections.personal"

    /** Private projects of your own (ADR 0038). */
    private const val PROJECTS_CREATE_PERSONAL = "projects.create.personal"

    /** Your own computer as a personal runner for your private projects (ADR 0040). */
    private const val FLEET_PERSONAL = "fleet.personal"

    /** Credentials of the project (ADR 0030): owners only, not maintainers. */
    private const val PROJECT_CONNECTIONS_MANAGE = "project.connections.manage"

    /** Every platform permission of the catalogue table (the seeder adds contributed platform keys too). */
    val ALL_PLATFORM = setOf(
        IdentityPermissions.USERS_VIEW, IdentityPermissions.USERS_MANAGE, IdentityPermissions.ROLES_MANAGE,
        IdentityPermissions.AUDIT_VIEW, IdentityPermissions.PROJECTS_ALL, IdentityPermissions.SETTINGS_MANAGE,
        IdentityPermissions.TOKENS_PERSONAL, CONNECTIONS_PERSONAL, PROJECTS_CREATE_PERSONAL, FLEET_PERSONAL,
        "projects.create", "integrations.view", "admin.connections.manage", LIBRARY_VIEW, "library.manage", MODELS_VIEW, "models.manage",
        EVALUATION_VIEW, "evaluation.run", USAGE_VIEW,
    )

    /** Every user: reads the shared catalogues, own tokens. No `projects.create`: managers create projects (ADR 0028). */
    private val MEMBER_PERMISSIONS = setOf(
        LIBRARY_VIEW, MODELS_VIEW, EVALUATION_VIEW, IdentityPermissions.TOKENS_PERSONAL, "integrations.view", CONNECTIONS_PERSONAL,
        PROJECTS_CREATE_PERSONAL, FLEET_PERSONAL,
    )

    /** Creates projects (the creator becomes their owner) and sees usage across projects (ADR 0028). */
    private val PROJECT_MANAGER_PERMISSIONS = MEMBER_PERMISSIONS + setOf("projects.create", USAGE_VIEW)

    private val OWNER_PERMISSIONS = setOf(
        PROJECT_VIEW, "project.manage", IdentityPermissions.PROJECT_MEMBERS_MANAGE, "project.ci.manage", "workflow.edit",
        "run.start", "run.cancel", "run.approve", "run.assign", "run.signal", "library.project.manage", PROJECT_USAGE_VIEW,
        PROJECT_CONNECTIONS_MANAGE,
    )

    val all: List<Role> = listOf(
        platform(PLATFORM_ADMIN, "Platform administrator", "All platform permissions including every project", ALL_PLATFORM),
        platform(MEMBER, "Member", "Default for every authenticated user (SYSTEM level)", MEMBER_PERMISSIONS),
        platform(
            PROJECT_MANAGER, "Project manager", "Creates projects, becomes their owner and decides who does which step",
            PROJECT_MANAGER_PERMISSIONS,
        ),
        platform(EVALUATOR, "Evaluator", "Member who runs model evaluations", MEMBER_PERMISSIONS + "evaluation.run"),
        platform(LIBRARY_CURATOR, "Library curator", "Member who maintains the global task library", MEMBER_PERMISSIONS + "library.manage"),
        platform(
            AUDITOR, "Auditor", "Read-only access to users, audit log and usage",
            setOf(IdentityPermissions.USERS_VIEW, IdentityPermissions.AUDIT_VIEW, USAGE_VIEW, LIBRARY_VIEW, MODELS_VIEW, EVALUATION_VIEW),
        ),
        project(OWNER, "Owner", "Every project permission except CI-token-only ones", OWNER_PERMISSIONS),
        project(
            MAINTAINER, "Maintainer", "Owner without member and connection management (project.manage still allows settings)",
            OWNER_PERMISSIONS - IdentityPermissions.PROJECT_MEMBERS_MANAGE - PROJECT_CONNECTIONS_MANAGE,
        ),
        project(
            DEVELOPER, "Developer", "Edits workflows and runs them",
            setOf(PROJECT_VIEW, "workflow.edit", "run.start", "run.cancel", "run.signal", PROJECT_USAGE_VIEW),
        ),
        project(APPROVER, "Approver", "Decides human gates", setOf(PROJECT_VIEW, "run.approve", PROJECT_USAGE_VIEW)),
        project(VIEWER, "Viewer", "Read-only project access", setOf(PROJECT_VIEW)),
    )

    val keys: Set<String> = all.map { it.key }.toSet()

    /** Role assigned at SYSTEM level on first start. */
    const val SYSTEM_DEFAULT = MEMBER

    private fun platform(key: String, name: String, description: String, permissions: Set<String>) =
        Role(key, name, description, PermissionScope.PLATFORM, permissions, builtIn = true)

    private fun project(key: String, name: String, description: String, permissions: Set<String>) =
        Role(key, name, description, PermissionScope.PROJECT, permissions, builtIn = true)
}

/** Audit operation codes recorded by the identity context (ADR 0018). */
object IdentityAudit {
    const val USER_INVITE = "USER_INVITE"
    const val USER_UPDATE = "USER_UPDATE"
    const val USER_ACTIVATE = "USER_ACTIVATE"
    const val USER_DEACTIVATE = "USER_DEACTIVATE"
    const val USER_PROVISION = "USER_PROVISION"
    const val USER_PREFERENCES_UPDATE = "USER_PREFERENCES_UPDATE"
    const val SESSIONS_TERMINATE = "SESSIONS_TERMINATE"
    const val GROUP_CREATE = "GROUP_CREATE"
    const val GROUP_UPDATE = "GROUP_UPDATE"
    const val GROUP_DELETE = "GROUP_DELETE"
    const val ROLE_CREATE = "ROLE_CREATE"
    const val ROLE_UPDATE = "ROLE_UPDATE"
    const val ROLE_DELETE = "ROLE_DELETE"
    const val ROLE_ASSIGN = "ROLE_ASSIGN"
    const val ROLE_UNASSIGN = "ROLE_UNASSIGN"
    const val GRANT_CHANGE = "GRANT_CHANGE"
    const val MEMBER_ADD = "MEMBER_ADD"
    const val MEMBER_ROLE_CHANGE = "MEMBER_ROLE_CHANGE"
    const val MEMBER_REMOVE = "MEMBER_REMOVE"
    const val PAT_CREATE = "PAT_CREATE"
    const val PAT_REVOKE = "PAT_REVOKE"
    const val SETTINGS_UPDATE = "SETTINGS_UPDATE"
    const val LOGIN_SUCCESS = "LOGIN_SUCCESS"
    const val LOGIN_FAILURE = "LOGIN_FAILURE"
    const val LOGOUT = "LOGOUT"
    const val SESSION_EXPIRED = "SESSION_EXPIRED"
    const val ACCESS_DENIED = "ACCESS_DENIED"
    const val AUDIT_VIEW = "AUDIT_VIEW"

    // local accounts (ADR 0021)
    const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
    const val ACCOUNT_UNLOCK = "ACCOUNT_UNLOCK"
    const val PASSWORD_CHANGE = "PASSWORD_CHANGE"
    const val PASSWORD_RESET_ISSUE = "PASSWORD_RESET_ISSUE"
    const val PASSWORD_RESET_COMPLETE = "PASSWORD_RESET_COMPLETE"
    const val MFA_ENABLE = "MFA_ENABLE"
    const val MFA_DISABLE = "MFA_DISABLE"
    const val MFA_RESET = "MFA_RESET"
    const val RECOVERY_CODE_USED = "RECOVERY_CODE_USED"
    const val RECOVERY_CODES_REGENERATE = "RECOVERY_CODES_REGENERATE"
    const val AUTH_MODE_CHANGE = "AUTH_MODE_CHANGE"

    /** `LOGIN_FAILURE` reasons of local login; audit only, the client always gets `invalid-credentials`. */
    object FailureReason {
        const val UNKNOWN_USER = "unknown-user"
        const val BAD_PASSWORD = "bad-password"
        const val LOCKED = "locked"
        const val METHOD_NOT_ALLOWED = "method-not-allowed"
        const val INACTIVE = "inactive"
        const val BAD_MFA = "bad-mfa"
        const val THROTTLED = "throttled"
    }

    const val ENTITY_USER = "USER"
    const val ENTITY_GROUP = "GROUP"
    const val ENTITY_ROLE = "ROLE"
    const val ENTITY_GRANT = "GRANT"
    const val ENTITY_MEMBERSHIP = "PROJECT_MEMBERSHIP"
    const val ENTITY_TOKEN = "PERSONAL_TOKEN"
    const val ENTITY_SETTINGS = "PLATFORM_SETTINGS"
    const val ENTITY_AUDIT = "AUDIT_LOG"
    const val ENTITY_SESSION = "SESSION"
}
