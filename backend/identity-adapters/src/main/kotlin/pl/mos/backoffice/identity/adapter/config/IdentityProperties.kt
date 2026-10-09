package pl.mos.backoffice.identity.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.mos.backoffice.identity.domain.model.RegistrationMode
import java.time.Duration

/**
 * `backoffice.auth.*` (docs/guides/authentication-and-users.md). OIDC login is disabled when
 * `oidc.issuer-uri` is blank (tests, scripts-only installations): only bearer tokens authenticate then.
 */
@ConfigurationProperties(prefix = "backoffice.auth")
data class IdentityProperties(
    val oidc: Oidc = Oidc(),
    /** Default until an administrator saves platform settings. */
    val registration: RegistrationMode = RegistrationMode.INVITE_ONLY,
    val allowedEmailDomains: List<String> = emptyList(),
    /** E-mails that receive `platform-admin` on their first login. */
    val bootstrapAdmins: List<String> = emptyList(),
    val session: Session = Session(),
    /** `Secure` flag of the `BO_SESSION` cookie; false only for plain-HTTP local development. */
    val cookieSecure: Boolean = true,
    /** Claim holding the user's groups (Keycloak: `groups` with the group-membership mapper). */
    val groupsClaim: String = "groups",
    /** Default of the platform setting "synchronise groups from the identity provider". */
    val syncGroups: Boolean = false,
    /** Default of the platform setting "personal token max days". */
    val personalTokenMaxDays: Int = 90,
    /** Local accounts (ADR 0021). */
    val local: Local = Local(),
) {
    /**
     * `backoffice.auth.local.*`. [enabled] is a hard switch (the platform setting `localLoginEnabled` can
     * only narrow it). [secretKey] (base64, 32 bytes) encrypts TOTP secrets and signs MFA step tokens; it
     * is required while local login is enabled.
     */
    data class Local(
        val enabled: Boolean = true,
        val secretKey: String = "",
        val bootstrapAdminEmail: String = "",
        val bootstrapAdminPassword: String = "",
        val resetLinkValidity: Duration = Duration.ofHours(24),
        val lockout: Lockout = Lockout(),
        val throttle: Throttle = Throttle(),
        /** Issuer label shown in authenticator apps. */
        val totpIssuer: String = "Back Office",
        /** Validity of the token between the password and the MFA step. */
        val mfaTokenValidity: Duration = Duration.ofMinutes(5),
    )

    /** Defaults of the platform setting `lockout` (until an administrator saves settings). */
    data class Lockout(val maxFailures: Int = 5, val lockDuration: Duration = Duration.ofMinutes(15))

    /** Per-source-IP sliding window over `/auth/login`, `/auth/login/mfa` and `POST /auth/password/reset/{token}`. */
    data class Throttle(val maxAttempts: Int = 20, val window: Duration = Duration.ofMinutes(5))

    data class Oidc(
        val issuerUri: String = "",
        val clientId: String = "",
        val clientSecret: String = "",
        val scopes: List<String> = listOf("openid", "profile", "email"),
        /** Shown on the login page. */
        val providerName: String = "Keycloak",
        /** Provider page for password / MFA changes; derived for Keycloak issuers when blank. */
        val accountUrl: String = "",
    ) {
        val enabled: Boolean get() = issuerUri.isNotBlank()
    }

    data class Session(
        val idleTimeout: Duration = Duration.ofMinutes(30),
        val absoluteTimeout: Duration = Duration.ofHours(12),
    )

    fun accountUrl(): String? = oidc.accountUrl.ifBlank {
        if (oidc.issuerUri.contains("/realms/")) oidc.issuerUri.trimEnd('/') + "/account" else ""
    }.ifBlank { null }

    companion object {
        const val REGISTRATION_ID = "oidc"
    }
}
