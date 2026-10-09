package pl.mos.backoffice.identity.adapter.out.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.instant
import pl.mos.backoffice.identity.adapter.out.persistence.JdbcSupport.uuid
import pl.mos.backoffice.identity.application.port.out.LocalCredentialRepository
import pl.mos.backoffice.identity.application.port.out.MfaRepository
import pl.mos.backoffice.identity.application.port.out.PasswordResetTokenRepository
import pl.mos.backoffice.identity.domain.model.LocalCredential
import pl.mos.backoffice.identity.domain.model.MfaTotp
import pl.mos.backoffice.identity.domain.model.PasswordResetToken
import pl.mos.backoffice.identity.domain.model.RecoveryCode
import pl.mos.backoffice.identity.domain.model.ResetPurpose
import pl.mos.backoffice.shared.security.UserId
import java.sql.ResultSet
import java.time.Instant

// Local accounts (ADR 0021, V402__local_accounts.sql).

class JdbcLocalCredentialRepository(private val jdbc: JdbcClient) : LocalCredentialRepository {

    override fun find(userId: UserId): LocalCredential? =
        jdbc.sql("SELECT * FROM identity_local_credential WHERE user_id = :id").param("id", userId.value).query { rs, _ -> row(rs) }.optional().orElse(null)

    override fun findAll(userIds: Collection<UserId>): Map<UserId, LocalCredential> =
        if (userIds.isEmpty()) emptyMap()
        else jdbc.sql("SELECT * FROM identity_local_credential WHERE user_id IN (:ids)").param("ids", userIds.map { it.value })
            .query { rs, _ -> row(rs) }.list().associateBy { it.userId }

    override fun save(credential: LocalCredential): LocalCredential {
        jdbc.sql(
            """
            INSERT INTO identity_local_credential (user_id, password_hash, password_changed_at, must_change_password, failed_attempts, locked_until, lockout_count)
            VALUES (:id, :hash, :changedAt, :mustChange, :failed, :lockedUntil, :lockoutCount)
            ON CONFLICT (user_id) DO UPDATE SET password_hash = EXCLUDED.password_hash, password_changed_at = EXCLUDED.password_changed_at,
                must_change_password = EXCLUDED.must_change_password, failed_attempts = EXCLUDED.failed_attempts,
                locked_until = EXCLUDED.locked_until, lockout_count = EXCLUDED.lockout_count
            """.trimIndent(),
        )
            .param("id", credential.userId.value).param("hash", credential.passwordHash).param("changedAt", JdbcSupport.ts(credential.passwordChangedAt))
            .param("mustChange", credential.mustChangePassword).param("failed", credential.failedAttempts)
            .param("lockedUntil", JdbcSupport.ts(credential.lockedUntil)).param("lockoutCount", credential.lockoutCount)
            .update()
        return credential
    }

    override fun delete(userId: UserId) {
        jdbc.sql("DELETE FROM identity_local_credential WHERE user_id = :id").param("id", userId.value).update()
    }

    private fun row(rs: ResultSet) = LocalCredential(
        userId = UserId(rs.uuid("user_id")!!),
        passwordHash = rs.getString("password_hash"),
        passwordChangedAt = rs.instant("password_changed_at"),
        mustChangePassword = rs.getBoolean("must_change_password"),
        failedAttempts = rs.getInt("failed_attempts"),
        lockedUntil = rs.instant("locked_until"),
        lockoutCount = rs.getInt("lockout_count"),
    )
}

class JdbcMfaRepository(private val jdbc: JdbcClient) : MfaRepository {

    override fun findTotp(userId: UserId): MfaTotp? =
        jdbc.sql("SELECT * FROM identity_mfa_totp WHERE user_id = :id").param("id", userId.value).query { rs, _ ->
            MfaTotp(
                userId = UserId(rs.uuid("user_id")!!),
                encryptedSecret = rs.getString("secret_encrypted"),
                createdAt = rs.instant("created_at")!!,
                confirmedAt = rs.instant("confirmed_at"),
                lastUsedStep = rs.getLong("last_used_step").takeUnless { rs.wasNull() },
            )
        }.optional().orElse(null)

    override fun saveTotp(totp: MfaTotp): MfaTotp {
        jdbc.sql(
            """
            INSERT INTO identity_mfa_totp (user_id, secret_encrypted, created_at, confirmed_at, last_used_step)
            VALUES (:id, :secret, :createdAt, :confirmedAt, :step)
            ON CONFLICT (user_id) DO UPDATE SET secret_encrypted = EXCLUDED.secret_encrypted, created_at = EXCLUDED.created_at,
                confirmed_at = EXCLUDED.confirmed_at, last_used_step = EXCLUDED.last_used_step
            """.trimIndent(),
        )
            .param("id", totp.userId.value).param("secret", totp.encryptedSecret).param("createdAt", JdbcSupport.ts(totp.createdAt))
            .param("confirmedAt", JdbcSupport.ts(totp.confirmedAt)).param("step", totp.lastUsedStep)
            .update()
        return totp
    }

    override fun deleteAll(userId: UserId) {
        jdbc.sql("DELETE FROM identity_mfa_recovery_code WHERE user_id = :id").param("id", userId.value).update()
        jdbc.sql("DELETE FROM identity_mfa_totp WHERE user_id = :id").param("id", userId.value).update()
    }

    override fun recoveryCodes(userId: UserId): List<RecoveryCode> =
        jdbc.sql("SELECT * FROM identity_mfa_recovery_code WHERE user_id = :id ORDER BY created_at").param("id", userId.value).query { rs, _ ->
            RecoveryCode(UserId(rs.uuid("user_id")!!), rs.getString("code_hash"), rs.instant("created_at")!!, rs.instant("used_at"))
        }.list()

    override fun replaceRecoveryCodes(userId: UserId, codes: List<RecoveryCode>) {
        jdbc.sql("DELETE FROM identity_mfa_recovery_code WHERE user_id = :id").param("id", userId.value).update()
        codes.forEach {
            jdbc.sql("INSERT INTO identity_mfa_recovery_code (user_id, code_hash, created_at, used_at) VALUES (:id, :hash, :createdAt, :usedAt)")
                .param("id", userId.value).param("hash", it.hash).param("createdAt", JdbcSupport.ts(it.createdAt)).param("usedAt", JdbcSupport.ts(it.usedAt))
                .update()
        }
    }

    /** A conditional update, so two concurrent uses of the same code cannot both succeed. */
    override fun useRecoveryCode(userId: UserId, hash: String, at: Instant): Boolean =
        jdbc.sql("UPDATE identity_mfa_recovery_code SET used_at = :at WHERE user_id = :id AND code_hash = :hash AND used_at IS NULL")
            .param("at", JdbcSupport.ts(at)).param("id", userId.value).param("hash", hash).update() == 1
}

class JdbcPasswordResetTokenRepository(private val jdbc: JdbcClient) : PasswordResetTokenRepository {

    override fun findByHash(hash: String): PasswordResetToken? =
        jdbc.sql("SELECT * FROM identity_password_reset_token WHERE token_hash = :hash").param("hash", hash).query { rs, _ ->
            PasswordResetToken(
                hash = rs.getString("token_hash"),
                userId = UserId(rs.uuid("user_id")!!),
                purpose = ResetPurpose.valueOf(rs.getString("purpose")),
                expiresAt = rs.instant("expires_at")!!,
                createdAt = rs.instant("created_at")!!,
                createdBy = JdbcSupport.actor(rs, "created_by"),
                usedAt = rs.instant("used_at"),
            )
        }.optional().orElse(null)

    override fun save(token: PasswordResetToken): PasswordResetToken {
        jdbc.sql(
            """
            INSERT INTO identity_password_reset_token (token_hash, user_id, purpose, expires_at, created_at, created_by_kind, created_by_id, created_by_name, used_at)
            VALUES (:hash, :userId, :purpose, :expiresAt, :createdAt, :kind, :byId, :byName, :usedAt)
            ON CONFLICT (token_hash) DO UPDATE SET used_at = EXCLUDED.used_at
            """.trimIndent(),
        )
            .param("hash", token.hash).param("userId", token.userId.value).param("purpose", token.purpose.name)
            .param("expiresAt", JdbcSupport.ts(token.expiresAt)).param("createdAt", JdbcSupport.ts(token.createdAt))
            .param("kind", token.createdBy.kind.name).param("byId", token.createdBy.userId).param("byName", token.createdBy.displayName)
            .param("usedAt", JdbcSupport.ts(token.usedAt))
            .update()
        return token
    }

    override fun invalidateAllOf(userId: UserId, at: Instant): Int =
        jdbc.sql("UPDATE identity_password_reset_token SET used_at = :at WHERE user_id = :id AND used_at IS NULL")
            .param("at", JdbcSupport.ts(at)).param("id", userId.value).update()

    override fun markUsed(hash: String, at: Instant): Boolean =
        jdbc.sql("UPDATE identity_password_reset_token SET used_at = :at WHERE token_hash = :hash AND used_at IS NULL")
            .param("at", JdbcSupport.ts(at)).param("hash", hash).update() == 1
}
