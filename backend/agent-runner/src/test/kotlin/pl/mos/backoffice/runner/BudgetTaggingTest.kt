package pl.mos.backoffice.runner

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.runner.application.service.RunnerProtocolService.Companion.budgetTagged
import pl.mos.backoffice.runner.domain.AgentJobStatus

class BudgetTaggingTest {

    @Test
    fun `a failed job the gateway refused for budget is tagged budget-exceeded`() {
        assertThat(budgetTagged(AgentJobStatus.FAILED, "agent reported a failure", "project budget of 50 USD for 2026-10 would be exceeded"))
            .isEqualTo("budget-exceeded: project budget of 50 USD for 2026-10 would be exceeded")
        assertThat(budgetTagged(AgentJobStatus.TIMED_OUT, null, "job token cap of 1000 would be exceeded"))
            .startsWith("budget-exceeded: ")
    }

    @Test
    fun `successful, cancelled and never-refused jobs keep their reason`() {
        assertThat(budgetTagged(AgentJobStatus.SUCCEEDED, null, "refused once")).isNull()
        assertThat(budgetTagged(AgentJobStatus.CANCELLED, "cancelled", "refused once")).isEqualTo("cancelled")
        assertThat(budgetTagged(AgentJobStatus.FAILED, "agent reported a failure", null)).isEqualTo("agent reported a failure")
    }
}
