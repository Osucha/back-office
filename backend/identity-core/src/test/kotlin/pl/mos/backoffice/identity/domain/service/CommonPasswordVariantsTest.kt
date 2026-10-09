package pl.mos.backoffice.identity.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import pl.mos.backoffice.identity.domain.model.PasswordPolicy

class CommonPasswordVariantsTest {

    private val common = setOf("password", "dragon", "letmein", "qwerty")
    private val policy = PasswordPolicy()

    @ParameterizedTest
    @ValueSource(strings = ["Password2026!", "p4ssw0rd123456", "123dragon!!!!", "dragondragon", "LetMeIn!!2026", "!!!QWERTY2026"])
    fun `decorated common passwords are rejected even when long enough`(password: String) {
        assertThat(policy.violations(password, "alice@example.com") { it in common }).contains("is too common")
    }

    @ParameterizedTest
    @ValueSource(strings = ["correct horse battery", "Relay-kx81Qp2vZ", "dragonfly-mountain-9"])
    fun `passwords that only contain a common word among other material are accepted`(password: String) {
        assertThat(policy.violations(password, "alice@example.com") { it in common }).doesNotContain("is too common")
    }
}
