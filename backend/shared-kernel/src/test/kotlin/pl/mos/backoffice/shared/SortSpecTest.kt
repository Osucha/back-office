package pl.mos.backoffice.shared

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.usage.SortSpec

class SortSpecTest {

    private val fields = listOf("cost", "created.at")

    @Test
    fun `parses field and direction, blank means the natural order`() {
        assertThat(SortSpec.parse("cost.desc", fields)).isEqualTo(SortSpec("cost", true))
        assertThat(SortSpec.parse(" cost.ASC ", fields)).isEqualTo(SortSpec("cost", false))
        assertThat(SortSpec.parse("cost", fields)).isEqualTo(SortSpec("cost", false))
        assertThat(SortSpec.parse("created.at.desc", fields)).isEqualTo(SortSpec("created.at", true))
        assertThat(SortSpec.parse(null, fields)).isNull()
        assertThat(SortSpec.parse("  ", fields)).isNull()
    }

    @Test
    fun `unknown fields and directions are refused`() {
        assertThatThrownBy { SortSpec.parse("name.asc", fields) }.isInstanceOf(ValidationException::class.java).hasMessageContaining("cost.asc|cost.desc")
        assertThatThrownBy { SortSpec.parse("cost.up", fields) }.isInstanceOf(ValidationException::class.java)
    }
}
