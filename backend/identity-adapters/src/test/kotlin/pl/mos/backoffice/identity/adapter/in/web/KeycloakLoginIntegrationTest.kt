package pl.mos.backoffice.identity.adapter.`in`.web

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.mos.backoffice.identity.PostgresTestSupport
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The real authorization-code flow (with PKCE) against Keycloak 26.7: the API redirects to the provider,
 * a Java HTTP client submits the login form like a browser, the callback provisions the user (bootstrap
 * admin) and `/api/v1/me` answers 200 with the session cookie. A non-invited user ends on `/login?error=not-invited`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestSupport::class)
class KeycloakLoginIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    private fun browser(): HttpClient = HttpClient.newBuilder()
        .cookieHandler(PlainHttpCookies())
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build()

    private fun get(client: HttpClient, url: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** Starts the login at the API, fills the Keycloak form and returns the final response after all redirects. */
    private fun login(client: HttpClient, username: String, password: String): HttpResponse<String> {
        val loginPage = get(client, "http://localhost:$port/oauth2/authorization/oidc?returnTo=/projects")
        assertThat(loginPage.uri().toString()).startsWith(keycloak.authServerUrl)
        val action = Regex("""<form[^>]+id="kc-form-login"[^>]+action="([^"]+)"""").find(loginPage.body())?.groupValues?.get(1)
            ?: Regex("""action="([^"]+login-actions/authenticate[^"]*)"""").find(loginPage.body())!!.groupValues[1]
        val form = mapOf("username" to username, "password" to password, "credentialId" to "")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        return client.send(
            HttpRequest.newBuilder(URI.create(action.replace("&amp;", "&")))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Test
    fun `a real OIDC login provisions the bootstrap admin and opens a session`() {
        val client = browser()
        val landed = login(client, "ada", "ada-password")
        assertThat(landed.uri().path).isEqualTo("/projects")

        val me = get(client, "http://localhost:$port/api/v1/me")
        assertThat(me.statusCode()).isEqualTo(200)
        assertThat(me.body()).contains("ada@example.com", "admin.users.manage", "Ada Admin")

        val session = get(client, "http://localhost:$port/api/v1/auth/session")
        assertThat(session.statusCode()).isEqualTo(200)
        assertThat(session.body()).contains("idleExpiresAt")
    }

    @Test
    fun `a user who is not invited is sent to the login page with not-invited`() {
        val landed = login(browser(), "stranger", "stranger-password")
        assertThat(landed.uri().path).isEqualTo("/login")
        assertThat(landed.uri().query).isEqualTo("error=not-invited")
    }

    companion object {
        @JvmStatic
        val keycloak: KeycloakContainer = KeycloakContainer("quay.io/keycloak/keycloak:26.7")
            .withRealmImportFile("/keycloak/bo-test-realm.json")
            .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("backoffice.auth.oidc.issuer-uri") { keycloak.authServerUrl.trimEnd('/') + "/realms/bo-test" }
            registry.add("backoffice.auth.oidc.client-id") { "back-office" }
            registry.add("backoffice.auth.oidc.client-secret") { "test-secret" }
            registry.add("backoffice.auth.bootstrap-admins") { "ada@example.com" }
            registry.add("backoffice.auth.registration") { "INVITE_ONLY" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = keycloak.stop()
    }
}

/**
 * A browser treats `http://localhost` as a secure context and sends `Secure` cookies to it; the JDK cookie
 * manager does not. Keycloak marks its login cookies `Secure`, so the flag is dropped for this test.
 */
private class PlainHttpCookies : java.net.CookieHandler() {
    private val delegate = CookieManager(null, CookiePolicy.ACCEPT_ALL)
    private val secure = Regex(";\\s*Secure", RegexOption.IGNORE_CASE)

    override fun get(uri: URI, requestHeaders: Map<String, List<String>>): Map<String, List<String>> = delegate.get(uri, requestHeaders)

    override fun put(uri: URI, responseHeaders: Map<String, List<String>>) = delegate.put(
        uri,
        responseHeaders.mapValues { (name, values) -> if (name.equals("Set-Cookie", ignoreCase = true)) values.map { it.replace(secure, "") } else values },
    )
}
