package pl.mos.backoffice.identity.adapter.`in`.rest

import io.nayuki.qrcodegen.QrCode
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.identity.adapter.config.IdentityWebValues
import pl.mos.backoffice.identity.adapter.`in`.web.LoginSessions
import pl.mos.backoffice.identity.adapter.`in`.web.SafeRedirect
import pl.mos.backoffice.identity.application.port.`in`.AdminLocalAccounts
import pl.mos.backoffice.identity.application.port.`in`.ChangePassword
import pl.mos.backoffice.identity.application.port.`in`.CompleteMfaLogin
import pl.mos.backoffice.identity.application.port.`in`.GetMySecurity
import pl.mos.backoffice.identity.application.port.`in`.LocalLogin
import pl.mos.backoffice.identity.application.port.`in`.LoginOutcome
import pl.mos.backoffice.identity.application.port.`in`.ManageMyMfa
import pl.mos.backoffice.identity.application.port.`in`.PasswordResetLinks
import pl.mos.backoffice.identity.domain.model.LoginMethod
import pl.mos.backoffice.identity.domain.model.SessionRestriction
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.UserId
import java.util.UUID

/**
 * Local e-mail + password login, the MFA step, password change and one-time set-password links (ADR 0021,
 * openapi `/auth/login`, `/auth/login/mfa`, `/auth/password/...`). A successful step creates the same session
 * as the OIDC login ([LoginSessions]); every failure is `401 invalid-credentials`.
 */
@RestController
@RequestMapping("/api/v1/auth")
class LocalAuthController(
    private val login: LocalLogin,
    private val mfa: CompleteMfaLogin,
    private val changePassword: ChangePassword,
    private val resets: PasswordResetLinks,
    private val sessions: LoginSessions,
) {
    @PostMapping("/login")
    fun login(@RequestBody body: LocalLoginRequestDto, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> =
        step(login.login(body.email.orEmpty(), body.password.orEmpty()), body.returnTo, request, response)

    @PostMapping("/login/mfa")
    fun loginMfa(@RequestBody body: MfaLoginRequestDto, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        if (body.mfaToken.isNullOrBlank()) throw ValidationException("mfaToken is required", "mfaToken")
        if (body.code.isNullOrBlank() == body.recoveryCode.isNullOrBlank()) throw ValidationException("send either code or recoveryCode", "code")
        return step(mfa.complete(body.mfaToken, body.code, body.recoveryCode), null, request, response)
    }

    @PostMapping("/password/change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun change(@RequestBody body: PasswordChangeRequestDto, request: HttpServletRequest) {
        val newPassword = body.newPassword ?: throw ValidationException("newPassword is required", "newPassword")
        val restriction = sessions.restrictionOf(request)
        val result = changePassword.change(
            ChangePassword.Command(
                currentPassword = body.currentPassword,
                newPassword = newPassword,
                forcedChange = restriction == SessionRestriction.PASSWORD_CHANGE,
                keepSessionId = request.getSession(false)?.id,
            ),
        )
        if (restriction == SessionRestriction.PASSWORD_CHANGE) {
            sessions.restrict(request, if (result.mfaEnrolmentRequired) SessionRestriction.MFA_ENROLMENT else null)
        }
    }

    @GetMapping("/password/reset/{token}")
    fun checkReset(@PathVariable token: String): PasswordResetInfoDto {
        val info = resets.check(token)
        return PasswordResetInfoDto(info.email, info.expiresAt, info.purpose, PasswordPolicyDto.of(info.policy))
    }

    @PostMapping("/password/reset/{token}")
    fun completeReset(@PathVariable token: String, @RequestBody body: NewPasswordDto, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val newPassword = body.newPassword ?: throw ValidationException("newPassword is required", "newPassword")
        return step(resets.complete(token, newPassword), null, request, response)
    }

    private fun step(outcome: LoginOutcome, returnTo: String?, request: HttpServletRequest, response: HttpServletResponse): ResponseEntity<Any> {
        val safeReturn = returnTo?.takeIf(SafeRedirect::isSafe)
        return when (outcome) {
            LoginOutcome.Failed -> invalidCredentials()
            is LoginOutcome.MfaRequired -> ResponseEntity.ok(LoginStepResultDto(LoginStepStatus.MFA_REQUIRED, mfaToken = outcome.mfaToken, returnTo = safeReturn))
            is LoginOutcome.SignedIn -> {
                sessions.establish(request, response, outcome.user, LoginMethod.LOCAL, outcome.restriction)
                val status = when (outcome.restriction) {
                    null -> LoginStepStatus.AUTHENTICATED
                    SessionRestriction.PASSWORD_CHANGE -> LoginStepStatus.PASSWORD_CHANGE_REQUIRED
                    SessionRestriction.MFA_ENROLMENT -> LoginStepStatus.MFA_ENROLMENT_REQUIRED
                }
                ResponseEntity.ok(LoginStepResultDto(status, returnTo = safeReturn))
            }
        }
    }

    companion object {
        /** The one answer for every failed step: nothing tells whether the e-mail exists or why it failed. */
        fun invalidCredentials(): ResponseEntity<Any> = ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "The e-mail address, password or code is not valid").apply {
                    title = "Unauthorized"
                    setProperty("code", "invalid-credentials")
                },
            )
    }
}

/** `/me/security`, `/me/mfa/...`: the signed-in user's own sign-in methods. */
@RestController
@RequestMapping("/api/v1/me")
class MySecurityController(
    private val security: GetMySecurity,
    private val mfa: ManageMyMfa,
    private val sessions: LoginSessions,
) {
    @GetMapping("/security")
    fun security(): MySecurityDto = MySecurityDto.of(security.mySecurity())

    @PostMapping("/mfa/totp")
    fun startTotp(): TotpEnrolmentDto {
        val enrolment = mfa.startTotp()
        return TotpEnrolmentDto(enrolment.secretBase32, enrolment.otpauthUri, QrSvg.of(enrolment.otpauthUri))
    }

    @PutMapping("/mfa/totp")
    fun confirmTotp(@RequestBody body: TotpCodeDto, request: HttpServletRequest): RecoveryCodesDto {
        val codes = mfa.confirmTotp(body.code ?: throw ValidationException("code is required", "code"))
        if (sessions.restrictionOf(request) == SessionRestriction.MFA_ENROLMENT) sessions.restrict(request, null)
        return RecoveryCodesDto(codes)
    }

    @DeleteMapping("/mfa/totp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disableTotp(@RequestBody body: CurrentPasswordDto) = mfa.disableTotp(body.currentPassword.orEmpty())

    @PostMapping("/mfa/recovery-codes")
    fun regenerate(@RequestBody body: CurrentPasswordDto): RecoveryCodesDto = RecoveryCodesDto(mfa.regenerateRecoveryCodes(body.currentPassword.orEmpty()))
}

/** Administrators: sign-in method, reset links, lost authenticators and lockouts of a user. */
@RestController
@RequestMapping("/api/v1/admin/users/{userId}")
class AdminLocalAccountController(
    private val accounts: AdminLocalAccounts,
    private val resets: PasswordResetLinks,
    private val web: IdentityWebValues,
) {
    @PutMapping("/auth")
    fun setAuthMode(@PathVariable userId: UUID, @RequestBody body: AuthModeDto): UserDetailDto =
        UserDetailDto.of(accounts.setAuthMode(UserId(userId), body.authMode ?: throw ValidationException("authMode is required", "authMode")), web.publicUrl)

    @PostMapping("/password-reset")
    fun issueReset(@PathVariable userId: UUID): PasswordResetLinkDto {
        val link = resets.issue(UserId(userId))
        return PasswordResetLinkDto(UserDetailDto.setPasswordUrl(web.publicUrl, link.token), link.expiresAt)
    }

    @DeleteMapping("/mfa")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun resetMfa(@PathVariable userId: UUID) = accounts.resetMfa(UserId(userId))

    @PostMapping("/unlock")
    fun unlock(@PathVariable userId: UUID): UserDetailDto = UserDetailDto.of(accounts.unlock(UserId(userId)), web.publicUrl)
}

/** Renders a QR code as a small inline SVG (one path, dark modules on a white background, 4-module quiet zone). */
object QrSvg {
    private const val BORDER = 4

    fun of(text: String): String {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val size = qr.size + BORDER * 2
        val path = StringBuilder()
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr.getModule(x, y)) path.append("M${x + BORDER},${y + BORDER}h1v1h-1z")
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges" role="img" aria-label="QR code">""" +
            """<rect width="100%" height="100%" fill="#FFFFFF"/><path d="$path" fill="#000000"/></svg>"""
    }
}
