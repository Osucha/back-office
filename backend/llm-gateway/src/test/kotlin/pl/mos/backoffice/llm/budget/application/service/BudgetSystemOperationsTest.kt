package pl.mos.backoffice.llm.budget.application.service

import com.osucha.portpermissions.test.assertSystemOnly
import io.mockk.confirmVerified
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.mos.backoffice.llm.budget.application.port.out.BudgetStore
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.security.CurrentPrincipal
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import java.math.BigDecimal
import java.util.UUID

/** ADR 0067: budget bookkeeping is a system operation of the LLM gateway; outside `asSystem` it books nothing. */
class BudgetSystemOperationsTest {

    private val store = mockk<BudgetStore>(relaxed = true)
    private val principal = mockk<CurrentPrincipal>(relaxed = true)

    @Test
    fun `booking spend refuses a user holding every permission`() {
        assertSystemOnly(touched = { runCatching { confirmVerified(store) }.isFailure }) { guard ->
            BudgetService(store, guard, principal, AuditPort.NOOP, { }, Clock.SYSTEM)
                .recordSpend(UsageContext(UsageKind.PIPELINE_STEP, projectId = UUID.randomUUID()), BigDecimal.ONE)
        }
    }
}
