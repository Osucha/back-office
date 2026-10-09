package pl.mos.backoffice.identity.application.port.`in`

import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PersonalAccessToken
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.TokenId
import pl.mos.backoffice.identity.domain.model.User
import java.util.UUID

data class MemberView(val membership: ProjectMembership, val principalName: String, val principalEmail: String?)

interface ManageProjectMembers {
    fun list(projectId: UUID): List<MemberView>
    fun add(projectId: UUID, command: AddMember): MemberView
    fun changeRole(projectId: UUID, memberId: MembershipId, role: String): MemberView
    fun remove(projectId: UUID, memberId: MembershipId)

    data class AddMember(val principalType: PrincipalType, val principalId: UUID, val role: String)
}

/** Pickers: needs `admin.users.view` or `project.members.manage` on any project. */
interface LookupDirectory {
    fun users(q: String): List<User>
    fun groups(q: String?): List<Group>
}

data class CreatedToken(val token: PersonalAccessToken, val plaintext: String)

interface ManagePersonalTokens {
    fun list(): List<PersonalAccessToken>
    fun create(command: CreateToken): CreatedToken
    fun revoke(id: TokenId)

    data class CreateToken(
        val name: String,
        val expiresInDays: Int,
        val permissions: Set<String> = emptySet(),
        /** See `PersonalAccessToken.allowMcpGateDecisions`. */
        val allowMcpGateDecisions: Boolean = false,
    )
}
