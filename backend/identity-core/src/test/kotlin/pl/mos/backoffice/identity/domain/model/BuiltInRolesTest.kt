package pl.mos.backoffice.identity.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR 0030: project credentials are managed by owners only; platform credentials by platform admins. */
class BuiltInRolesTest {

    private fun role(key: String) = BuiltInRoles.all.single { it.key == key }

    @Test
    fun `owners manage project connections, maintainers and developers do not`() {
        assertThat(role(BuiltInRoles.OWNER).permissions).contains("project.connections.manage")
        assertThat(role(BuiltInRoles.MAINTAINER).permissions).doesNotContain("project.connections.manage", IdentityPermissions.PROJECT_MEMBERS_MANAGE)
        assertThat(role(BuiltInRoles.MAINTAINER).permissions).contains("project.manage", "workflow.edit")
        assertThat(role(BuiltInRoles.DEVELOPER).permissions).doesNotContain("project.connections.manage")
    }

    @Test
    fun `platform admins manage platform connections, members only see them`() {
        assertThat(role(BuiltInRoles.PLATFORM_ADMIN).permissions).contains("admin.connections.manage", "integrations.view")
        assertThat(role(BuiltInRoles.MEMBER).permissions).contains("integrations.view").doesNotContain("admin.connections.manage")
        assertThat(role(BuiltInRoles.PROJECT_MANAGER).permissions).doesNotContain("admin.connections.manage")
    }
}
