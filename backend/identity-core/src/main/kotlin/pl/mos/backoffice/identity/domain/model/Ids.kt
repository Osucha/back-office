package pl.mos.backoffice.identity.domain.model

import java.util.UUID

@JvmInline
value class GroupId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): GroupId = GroupId(UUID.randomUUID())
        fun of(value: String): GroupId = GroupId(UUID.fromString(value))
    }
}

@JvmInline
value class MembershipId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): MembershipId = MembershipId(UUID.randomUUID())
        fun of(value: String): MembershipId = MembershipId(UUID.fromString(value))
    }
}

@JvmInline
value class TokenId(val value: UUID) {
    override fun toString(): String = value.toString()
    companion object {
        fun new(): TokenId = TokenId(UUID.randomUUID())
        fun of(value: String): TokenId = TokenId(UUID.fromString(value))
    }
}
