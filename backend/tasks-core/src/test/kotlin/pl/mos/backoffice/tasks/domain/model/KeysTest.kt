package pl.mos.backoffice.tasks.domain.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.ValidationException
import java.time.Instant

/** ADR 0055: keys derived from display names. */
class KeysTest {

    @Test
    fun `a name becomes a lower-case ASCII slug, Polish diacritics included`() {
        assertThat(Keys.fromName("Analiza zgłoszenia", "task")).isEqualTo("analiza-zgloszenia")
        assertThat(Keys.fromName("ŁĄCZNOŚĆ Żółw źdźbło", "task")).isEqualTo("lacznosc-zolw-zdzblo")
        assertThat(Keys.fromName("Ørsted & Søn: review!", "task")).isEqualTo("orsted-son-review")
        assertThat(Keys.fromName("  --Code   review--  ", "task")).isEqualTo("code-review")
        assertThat(Keys.fromName("Crème brûlée", "skill")).isEqualTo("creme-brulee")
    }

    @Test
    fun `the prefix goes first when the name starts with a digit, is too short or yields nothing`() {
        assertThat(Keys.fromName("2FA check", "task")).isEqualTo("task-2fa-check")
        assertThat(Keys.fromName("x", "rule")).isEqualTo("rule-x")
        assertThat(Keys.fromName("", "skill")).isEqualTo("skill")
        assertThat(Keys.fromName("???", "rule")).isEqualTo("rule")
        assertThat(Keys.fromName("中文", "task")).describedAs("nothing usable").isEqualTo("task")
    }

    @Test
    fun `a derived key is valid and capped so a suffix still fits`() {
        val long = Keys.fromName("Very long name ".repeat(10), "task")
        assertThat(long.length).isLessThanOrEqualTo(58)
        assertThat(long).doesNotEndWith("-")
        assertThat(Keys.isValid(long)).isTrue()
        assertThat(Keys.isValid(Keys.unique(long) { it == long })).isTrue()
        listOf("Analiza zgłoszenia", "2FA check", "", "x", "a b c d").forEach {
            assertThat(Keys.isValid(Keys.fromName(it, "task"))).describedAs(it).isTrue()
        }
    }

    @Test
    fun `unique returns the base when free, else the first free numbered suffix`() {
        assertThat(Keys.unique("review") { false }).isEqualTo("review")
        assertThat(Keys.unique("review") { it == "review" }).isEqualTo("review-2")
        assertThat(Keys.unique("review") { it in setOf("review", "review-2", "review-4") }).isEqualTo("review-3")
    }

    @Test
    fun `require rejects invalid keys with the field name`() {
        Keys.require("ok-key")
        assertThatThrownBy { Keys.require("Bad", "extendsKey") }.isInstanceOf(ValidationException::class.java).hasMessageContaining("extendsKey")
    }

    @Test
    fun `rules guard their own invariants and need a key on create`() {
        val now = Instant.parse("2026-11-01T10:00:00Z")
        assertThatThrownBy { Rule.create(Rule.Content(name = "No key", content = "x"), now) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("key")
        assertThatThrownBy { Rule.create(Rule.Content(key = "rr", name = " ", content = "x"), now) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("name")
        assertThatThrownBy { Rule.create(Rule.Content(key = "rr", name = "R", content = " "), now) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("content")
        assertThatThrownBy { Skill.create(Skill.Content(name = "No key", content = "x"), now) }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { TaskTemplate.create(TaskTemplate.Content(name = "No key"), now) }.isInstanceOf(ValidationException::class.java)

        val rule = Rule.create(Rule.Content(key = "rr", name = "R", content = "x", tags = listOf("a", "a")), now, builtIn = true)
        assertThat(rule.tags).containsExactly("a")
        val updated = rule.update(Rule.Content(name = "R2", content = "y", automatic = true), now.plusSeconds(1))
        assertThat(updated.key).isEqualTo("rr")
        assertThat(updated.automatic).isTrue()
        assertThat(updated.builtIn).isTrue()
        assertThat(updated.updatedAt).isEqualTo(now.plusSeconds(1))
        assertThat(rule.update(Rule.Content(key = "other", name = "R", content = "x"), now).key).isEqualTo("other")
    }
}
