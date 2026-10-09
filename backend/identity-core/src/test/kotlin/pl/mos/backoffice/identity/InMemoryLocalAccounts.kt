package pl.mos.backoffice.identity

import pl.mos.backoffice.identity.application.port.out.AuditLogReader
import pl.mos.backoffice.identity.application.port.out.AuditRecordPage
import pl.mos.backoffice.identity.application.port.out.CodeHasher
import pl.mos.backoffice.identity.application.port.out.CommonPasswords
import pl.mos.backoffice.identity.application.port.out.LocalCredentialRepository
import pl.mos.backoffice.identity.application.port.out.MfaChallenges
import pl.mos.backoffice.identity.application.port.out.MfaRepository
import pl.mos.backoffice.identity.application.port.out.PasswordHasher
import pl.mos.backoffice.identity.application.port.out.PasswordResetTokenRepository
import pl.mos.backoffice.identity.application.port.out.SecretCipher
import pl.mos.backoffice.identity.application.service.IdentityOptions
import pl.mos.backoffice.identity.application.service.LocalAccountSupport
import pl.mos.backoffice.identity.application.service.LocalAuthOptions
import pl.mos.backoffice.identity.domain.model.AuthMode
import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.MfaTotp
import pl.mos.backoffice.identity.domain.model.PasswordResetToken
import pl.mos.backoffice.identity.domain.model.RecoveryCode
import pl.mos.backoffice.identity.domain.model.User
import pl.mos.backoffice.identity.domain.model.UserStatus
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.Actor
import pl.mos.backoffice.shared.security.UserId
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** In-memory local-account ports on top of [InMemoryIdentity], with a movable clock and a counting hasher. */
class InMemoryLocalAccounts(val db: InMemoryIdentity = InMemoryIdentity().apply { seedBuiltInRoles() }) {

    var time: Instant = db.now
    val clock = Clock { time }
    fun advance(duration: Duration) {
        time = time.plus(duration)
    }

    val credentialMap = linkedMapOf<UserId, LocalCredential>()
    val totpMap = linkedMapOf<UserId, MfaTotp>()
    val recoveryMap = linkedMapOf<UserId, MutableList<RecoveryCode>>()
    val resetMap = linkedMapOf<String, PasswordResetToken>()
    val commonList = mutableSetOf("password1234", "qwertyuiop123")

    /** Every `matches` call: (raw, hash). */
    val hashChecks = mutableListOf<Pair<String, String>>()

    val credentials = object : LocalCredentialRepository {
        override fun find(userId: UserId) = credentialMap[userId]
        override fun save(credential: LocalCredential) = credential.also { credentialMap[it.userId] = it }
        override fun delete(userId: UserId) {
            credentialMap.remove(userId)
        }
    }

    val mfa = object : MfaRepository {
        override fun findTotp(userId: UserId) = totpMap[userId]
        override fun saveTotp(totp: MfaTotp) = totp.also { totpMap[it.userId] = it }
        override fun deleteAll(userId: UserId) {
            totpMap.remove(userId)
            recoveryMap.remove(userId)
        }
        override fun recoveryCodes(userId: UserId) = recoveryMap[userId]?.toList() ?: emptyList()
        override fun replaceRecoveryCodes(userId: UserId, codes: List<RecoveryCode>) {
            recoveryMap[userId] = codes.toMutableList()
        }
        override fun useRecoveryCode(userId: UserId, hash: String, at: Instant): Boolean {
            val list = recoveryMap[userId] ?: return false
            val index = list.indexOfFirst { it.hash == hash && it.usedAt == null }
            if (index < 0) return false
            list[index] = list[index].copy(usedAt = at)
            return true
        }
    }

    val resetTokens = object : PasswordResetTokenRepository {
        override fun findByHash(hash: String) = resetMap[hash]
        override fun save(token: PasswordResetToken) = token.also { resetMap[it.hash] = it }
        override fun invalidateAllOf(userId: UserId, at: Instant): Int {
            val open = resetMap.values.filter { it.userId == userId && it.usedAt == null }
            open.forEach { resetMap[it.hash] = it.copy(usedAt = at) }
            return open.size
        }
        override fun markUsed(hash: String, at: Instant): Boolean {
            val token = resetMap[hash]?.takeIf { it.usedAt == null } ?: return false
            resetMap[hash] = token.copy(usedAt = at)
            return true
        }
    }

    val hasher = object : PasswordHasher {
        override fun hash(raw: String) = "{v2}$raw"
        override fun matches(raw: String, hash: String): Boolean {
            hashChecks += raw to hash
            return hash.substringAfter('}') == raw && hash.startsWith("{")
        }
        override fun needsRehash(hash: String) = hash.startsWith("{v1}")
        override val dummyHash = "{v2}dummy-${UUID.randomUUID()}"
    }

    val cipher = object : SecretCipher {
        override fun encrypt(plaintext: ByteArray): String = "enc:" + Base64.getEncoder().encodeToString(plaintext)
        override fun decrypt(ciphertext: String): ByteArray = Base64.getDecoder().decode(ciphertext.removePrefix("enc:"))
    }

    val codeHasher = CodeHasher { "hmac:$it" }

    val challengeMap = linkedMapOf<String, UserId>()
    val challenges = object : MfaChallenges {
        override fun issue(userId: UserId): String = "mfa-${UUID.randomUUID()}".also { challengeMap[it] = userId }
        override fun consume(token: String): UserId? = challengeMap.remove(token)
    }

    val common = CommonPasswords { it.lowercase() in commonList }

    val auditReader = AuditLogReader { AuditRecordPage(emptyList(), null) }

    fun options(oidc: Boolean = false, enabled: Boolean = true, bootstrapEmail: String? = null, bootstrapPassword: String? = null) = IdentityOptions(
        local = LocalAuthOptions(enabled = enabled, oidcConfigured = oidc, bootstrapAdminEmail = bootstrapEmail, bootstrapAdminPassword = bootstrapPassword),
    )

    fun support(audit: AuditPort, options: IdentityOptions = options()) = LocalAccountSupport(
        db.users, credentials, mfa, resetTokens, hasher, cipher, codeHasher, challenges, common, db.settingsProvider(options), options, audit, clock,
    )

    /** An ACTIVE local user with [password] (or without a password when `null`). */
    fun localUser(email: String, password: String? = "correct-horse-battery", mode: AuthMode = AuthMode.LOCAL, status: UserStatus = UserStatus.ACTIVE): User {
        val user = db.users.save(
            User(UserId.new(), null, null, email, email.substringBefore('@'), status, createdAt = db.now, createdBy = Actor.SYSTEM, authMode = mode),
        )
        credentials.save(if (password == null) LocalCredential.empty(user.id) else LocalCredential.empty(user.id).withPassword(hasher.hash(password), db.now))
        return user
    }
}
