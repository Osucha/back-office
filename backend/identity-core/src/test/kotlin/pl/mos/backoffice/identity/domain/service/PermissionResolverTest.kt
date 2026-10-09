package pl.mos.backoffice.identity.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.identity.domain.model.Grant
import pl.mos.backoffice.identity.domain.model.GrantEffect.ALLOW
import pl.mos.backoffice.identity.domain.model.GrantEffect.DENY
import pl.mos.backoffice.identity.domain.model.GrantLevel
import pl.mos.backoffice.identity.domain.model.GrantSet
import pl.mos.backoffice.identity.domain.model.Group
import pl.mos.backoffice.identity.domain.model.GroupId
import pl.mos.backoffice.identity.domain.model.MembershipId
import pl.mos.backoffice.identity.domain.model.PrincipalType
import pl.mos.backoffice.identity.domain.model.ProjectMembership
import pl.mos.backoffice.identity.domain.model.Role
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope
import java.time.Instant
import java.util.UUID

class PermissionResolverTest {

    private val resolver = PermissionResolver()
    private val catalogue = listOf("a.view", "a.manage", "b.view")
    private val userId = UUID.randomUUID()
    private val g1 = Group(GroupId.new(), "Architects", null, emptySet(), null, Instant.EPOCH)
    private val g2 = Group(GroupId.new(), "Contractors", null, emptySet(), null, Instant.EPOCH)
    private val viewer = Role("viewer-x", "Viewer", null, PermissionScope.PLATFORM, setOf("a.view", "b.view"))
    private val projectRole = Role("proj-role", "Project role", null, PermissionScope.PROJECT, setOf("a.manage"))
    private val roles = listOf(viewer, projectRole).associateBy { it.key }

    private fun system(vararg grants: Grant, roles: Set<String> = emptySet()) = GrantSet(GrantLevel.SYSTEM, null, roles, grants.toList())
    private fun group(g: Group, vararg grants: Grant, roles: Set<String> = emptySet()) = g to GrantSet(GrantLevel.GROUP, g.id.value, roles, grants.toList())
    private fun user(vararg grants: Grant, roles: Set<String> = emptySet()) = GrantSet(GrantLevel.USER, userId, roles, grants.toList())

    @Test
    fun `no grant anywhere means denied`() {
        val result = resolver.resolve(catalogue, null, emptyList(), null, roles)
        assertThat(result.effective).isEmpty()
        assertThat(result.rows.map { it.source }).containsOnlyNulls()
    }

    @Test
    fun `a role at SYSTEM level allows its permissions for everyone`() {
        val result = resolver.resolve(catalogue, system(roles = setOf("viewer-x")), emptyList(), null, roles)
        assertThat(result.effective).containsExactlyInAnyOrder("a.view", "b.view")
        assertThat(result.rows.first { it.permission == "a.view" }.source).isEqualTo("SYSTEM")
    }

    @Test
    fun `GROUP overrides SYSTEM and USER overrides GROUP`() {
        val result = resolver.resolve(
            catalogue,
            system(Grant("a.view", ALLOW), Grant("a.manage", ALLOW)),
            listOf(group(g1, Grant("a.view", DENY), Grant("a.manage", DENY))),
            user(Grant("a.manage", ALLOW)),
            roles,
        )
        assertThat(result.effective).containsExactly("a.manage")
        val view = result.rows.first { it.permission == "a.view" }
        assertThat(view.system).isEqualTo(ALLOW)
        assertThat(view.groups.single().effect).isEqualTo(DENY)
        assertThat(view.user).isNull()
        assertThat(view.source).isEqualTo("GROUP:Architects")
        assertThat(result.rows.first { it.permission == "a.manage" }.source).isEqualTo("USER")
    }

    @Test
    fun `a USER level DENY beats a GROUP level ALLOW`() {
        val result = resolver.resolve(catalogue, null, listOf(group(g1, Grant("b.view", ALLOW))), user(Grant("b.view", DENY)), roles)
        assertThat(result.effective).doesNotContain("b.view")
    }

    @Test
    fun `within the GROUP level DENY wins over ALLOW from another group`() {
        val result = resolver.resolve(
            catalogue,
            system(Grant("a.view", ALLOW)),
            listOf(group(g1, roles = setOf("viewer-x")), group(g2, Grant("a.view", DENY))),
            null,
            roles,
        )
        assertThat(result.effective).containsExactly("b.view")
        val row = result.rows.first { it.permission == "a.view" }
        assertThat(row.groups.map { it.effect }).containsExactly(ALLOW, DENY)
        assertThat(row.source).isEqualTo("GROUP:Contractors")
    }

    @Test
    fun `an explicit DENY beats a role ALLOW at the same level`() {
        val result = resolver.resolve(catalogue, null, emptyList(), user(Grant("a.view", DENY), roles = setOf("viewer-x")), roles)
        assertThat(result.effective).containsExactly("b.view")
    }

    @Test
    fun `project roles assigned at a platform level are ignored`() {
        val result = resolver.resolve(catalogue, system(roles = setOf("proj-role")), emptyList(), null, roles)
        assertThat(result.effective).isEmpty()
    }

    @Test
    fun `project permissions are the union of direct and group memberships and admin projects all sets allProjects`() {
        val projectA = UUID.randomUUID()
        val developer = Role("developer", "Dev", null, PermissionScope.PROJECT, setOf("project.view", "run.start"))
        val approver = Role("approver", "Approver", null, PermissionScope.PROJECT, setOf("project.view", "run.approve"))
        val all = mapOf("developer" to developer, "approver" to approver)
        fun m(type: PrincipalType, role: String) = ProjectMembership(MembershipId.new(), projectA, "A", type, UUID.randomUUID(), role, Instant.EPOCH, Actor.SYSTEM)

        val result = ProjectPermissionResolver().resolve(listOf(m(PrincipalType.USER, "developer"), m(PrincipalType.GROUP, "approver")), all, setOf("x"))
        assertThat(result.projects.getValue(projectA).permissions).containsExactlyInAnyOrder("project.view", "run.start", "run.approve")
        assertThat(result.projects.getValue(projectA).roles).containsExactlyInAnyOrder("developer", "approver")
        assertThat(result.allProjects).isFalse()
        assertThat(ProjectPermissionResolver().resolve(emptyList(), all, setOf("admin.projects.all")).allProjects).isTrue()
    }

    @Test
    fun `catalogue validator rejects unknown keys and wrong scopes`() {
        val validator = CatalogValidator(
            listOf(PermissionDefinition("a.view", PermissionScope.PLATFORM, "t", "d"), PermissionDefinition("p.view", PermissionScope.PROJECT, "t", "d")),
        )
        assertThatThrownBy { validator.validateGrantSet(system(Grant("nope.nope", ALLOW)), roles) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { validator.validateGrantSet(system(Grant("p.view", ALLOW)), roles) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { validator.validateGrantSet(system(roles = setOf("proj-role")), roles) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { validator.validateRole(Role("r-x", "R", null, PermissionScope.PLATFORM, setOf("p.view"))) }
            .isInstanceOf(ValidationException::class.java)
        validator.validateRole(Role("r-x", "R", null, PermissionScope.PROJECT, setOf("p.view")))
        // keys already stored on a role are tolerated even when nobody contributes them any more
        validator.validateRole(Role("r-x", "R", null, PermissionScope.PROJECT, setOf("p.view", "legacy.key")), previous = Role("r-x", "R", null, PermissionScope.PROJECT, setOf("legacy.key")))
    }

    @Test
    fun `token codec generates prefixed url-safe tokens and hashes them`() {
        val token = TokenCodec.generate()
        assertThat(token.plaintext).startsWith("bo_pat_").hasSize(47)
        assertThat(token.prefix).isEqualTo(token.plaintext.take(12))
        assertThat(token.hash).isEqualTo(TokenCodec.hash(token.plaintext)).hasSize(64)
        assertThat(TokenCodec.looksLikePersonalToken(token.plaintext)).isTrue()
        assertThat(TokenCodec.looksLikePersonalToken("bo_pat_short")).isFalse()
        assertThat(TokenCodec.generate().plaintext).isNotEqualTo(token.plaintext)
    }
}
