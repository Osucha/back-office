package pl.mos.backoffice.identity.adapter.`in`.rest

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import pl.mos.backoffice.identity.PostgresTestSupport
import pl.mos.backoffice.identity.TestIdentity

/**
 * An installation without an identity provider: the configured local admin is created on an empty
 * database, must change the password at the first login and then holds `platform-admin`.
 */
@SpringBootTest(
    properties = [
        "backoffice.auth.local.bootstrap-admin-email=Owner@Example.com",
        "backoffice.auth.local.bootstrap-admin-password=ChangeMe-Local-123",
    ],
)
@AutoConfigureMockMvc
@Import(PostgresTestSupport::class)
class BootstrapLocalAdminIntegrationTest {

    @Autowired lateinit var mvc: MockMvcTester

    private fun post(uri: String, body: String, vararg cookies: jakarta.servlet.http.Cookie) = mvc.post().uri(uri)
        .with { it.remoteAddr = "10.9.9.9"; it }
        .cookie(*(cookies.toList() + TestIdentity.XSRF).toTypedArray())
        .header(TestIdentity.XSRF_HEADER, TestIdentity.XSRF.value)
        .contentType(MediaType.APPLICATION_JSON).content(body)

    @Test
    fun `the bootstrap admin signs in, changes the password and is a platform admin`() {
        val first = post("/api/v1/auth/login", """{"email":"owner@example.com","password":"ChangeMe-Local-123"}""").exchange()
        assertThat(first).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("PASSWORD_CHANGE_REQUIRED")
        val session = first.response.getCookie("BO_SESSION")!!
        assertThat(mvc.get().uri("/api/v1/admin/users").cookie(session)).hasStatus(HttpStatus.FORBIDDEN)
            .bodyJson().extractingPath("$.code").isEqualTo("password-change-required")

        assertThat(post("/api/v1/auth/password/change", """{"newPassword":"the-real-admin-secret"}""", session)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(mvc.get().uri("/api/v1/me").cookie(session)).hasStatusOk().bodyJson().extractingPath("$.platformPermissions")
            .asArray().contains("admin.users.manage")
        assertThat(mvc.get().uri("/api/v1/admin/users").cookie(session)).hasStatusOk().bodyJson().extractingPath("$.totalElements").isEqualTo(1)
    }
}
