package pl.mos.backoffice.identity.application.port.`in`

import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserPreferences
import pl.mos.backoffice.identity.domain.service.ProjectAccess
import pl.mos.backoffice.shared.security.CurrentUser

data class MeView(
    val user: User,
    val current: CurrentUser,
    val groups: List<Group>,
    val projects: List<ProjectAccess>,
)

interface GetMe {
    fun me(): MeView
}

/** The signed-in caller ends their session: audited as `LOGOUT` (the authentication events are system ports). */
interface RecordLogout {
    fun loggedOut()
}

interface UpdateMyPreferences {
    fun update(preferences: UserPreferences): UserPreferences
}

/** Public login page data. */
interface GetRegistrationMode {
    fun registrationMode(): RegistrationMode
}
