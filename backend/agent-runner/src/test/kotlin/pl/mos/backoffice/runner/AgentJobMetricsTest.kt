package pl.mos.backoffice.runner

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import pl.mos.backoffice.runner.adapter.out.metrics.AgentJobMetrics
import java.sql.ResultSet

class AgentJobMetricsTest {

    private val meters = SimpleMeterRegistry()
    private val db = mockk<NamedParameterJdbcTemplate>()
    private var rows: List<Triple<String?, String, Long>> = emptyList()

    init {
        every { db.query(any<String>(), any<RowMapper<Any>>()) } answers {
            val mapper = secondArg<RowMapper<Any>>()
            rows.mapIndexed { i, (pool, status, n) ->
                val rs = mockk<ResultSet>()
                every { rs.getString("pool_id") } returns pool
                every { rs.getString("status") } returns status
                every { rs.getLong("n") } returns n
                mapper.mapRow(rs, i)
            }
        }
    }

    private fun gauge(pool: String, status: String) = meters.find(AgentJobMetrics.NAME).tags("pool", pool, "status", status).gauge()?.value()

    @Test
    fun `reports unfinished jobs per pool and status and drops series that went to zero`() {
        val metrics = AgentJobMetrics(meters, db)
        rows = listOf(Triple("shared", "QUEUED", 3L), Triple("shared", "RUNNING", 2L), Triple(null, "QUEUED", 1L))

        metrics.refresh()

        assertThat(gauge("shared", "QUEUED")).isEqualTo(3.0)
        assertThat(gauge("shared", "RUNNING")).isEqualTo(2.0)
        assertThat(gauge(AgentJobMetrics.NO_POOL, "QUEUED")).isEqualTo(1.0)

        rows = listOf(Triple("shared", "RUNNING", 1L))
        metrics.refresh()

        assertThat(gauge("shared", "QUEUED")).isNull()
        assertThat(gauge("shared", "RUNNING")).isEqualTo(1.0)
    }

    @Test
    fun `a failing query keeps the last values`() {
        val metrics = AgentJobMetrics(meters, db)
        rows = listOf(Triple("shared", "QUEUED", 4L))
        metrics.refresh()
        every { db.query(any<String>(), any<RowMapper<Any>>()) } throws IllegalStateException("database down")

        metrics.refresh()

        assertThat(gauge("shared", "QUEUED")).isEqualTo(4.0)
    }
}
