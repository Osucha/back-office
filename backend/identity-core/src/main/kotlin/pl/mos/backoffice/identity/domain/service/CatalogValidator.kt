package pl.mos.backoffice.identity.domain.service

import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope

/**
 * Validates grants and roles against the permission catalogue contributed by the modules: unknown keys and
 * keys of the wrong scope are rejected (`ValidationException`, 400). Keys already stored on a role are
 * tolerated even when no module contributes them any more, so an edit never fails on legacy content.
 */
class CatalogValidator(catalogue: List<PermissionDefinition>) {

    private val byKey: Map<String, PermissionDefinition> = catalogue.associateBy { it.key }

    fun validateGrantSet(set: GrantSet, roles: Map<String, Role>) {
        val errors = mutableListOf<ValidationException.FieldError>()
        set.grants.forEach { grant ->
            val definition = byKey[grant.permission]
            when {
                definition == null -> errors += ValidationException.FieldError("grants", "unknown permission ${grant.permission}")
                definition.scope != PermissionScope.PLATFORM ->
                    errors += ValidationException.FieldError("grants", "${grant.permission} is project-scoped; grant it through a project role")
            }
        }
        set.roles.forEach { key ->
            val role = roles[key]
            when {
                role == null -> errors += ValidationException.FieldError("roles", "unknown role $key")
                role.scope != PermissionScope.PLATFORM -> errors += ValidationException.FieldError("roles", "$key is a project role")
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    fun validateRole(role: Role, previous: Role? = null) {
        val tolerated = previous?.permissions ?: emptySet()
        val errors = role.permissions.filter { it !in tolerated }.mapNotNull { key ->
            val definition = byKey[key]
            when {
                definition == null -> ValidationException.FieldError("permissions", "unknown permission $key")
                definition.scope != role.scope -> ValidationException.FieldError("permissions", "$key is ${definition.scope}-scoped, the role is ${role.scope}")
                else -> null
            }
        }
        if (errors.isNotEmpty()) throw ValidationException(errors)
    }

    /** Checks that [keys] exist in the catalogue (e.g. a personal token's permission subset). */
    fun validateKeys(keys: Collection<String>, field: String) {
        val unknown = keys.filter { it !in byKey }
        if (unknown.isNotEmpty()) throw ValidationException("unknown permissions $unknown", field)
    }

    fun definition(key: String): PermissionDefinition? = byKey[key]
}
