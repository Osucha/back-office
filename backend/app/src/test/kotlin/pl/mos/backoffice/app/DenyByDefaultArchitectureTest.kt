package pl.mos.backoffice.app

import com.osucha.portpermissions.archunit.PortPermissionsRules
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Tag

/**
 * Deny by default (ADR 0017, ADR 0066, ADR 0067, principle 4 of docs/architecture/08-identity-and-access.md), checked
 * by port-permissions' `PortPermissionsRules`:
 * - every public method of a class in an `..application.service..` package of the contexts below that implements a
 *   use-case port from `..application.port.in..` calls `AccessGuard.require(...)` or `requireSystem()` (in its body
 *   or in a lambda it creates, one level), or is annotated `@PublicOperation(reason)` / `@SystemOperation(reason)`;
 * - every `@SystemOperation` calls `requireSystem()`, so it refuses to run outside `asSystem`;
 * - `@SystemOperation` exactly on implementations of system ports (`..application.port.in.system..`);
 * - user-facing adapters (web, REST, MCP, SSE controllers) do not depend on system ports.
 *
 * A check inside a non-inline private helper does not count. Run it alone with
 * `./gradlew :app:test --tests "pl.mos.backoffice.app.DenyByDefaultArchitectureTest"`; the failure lists every
 * offending method or class.
 */
@Tag("architecture")
@Tag("deny-by-default")
@AnalyzeClasses(packages = ["pl.mos.backoffice"], importOptions = [ImportOption.DoNotIncludeTests::class])
class DenyByDefaultArchitectureTest {

    @ArchTest
    val useCasesCheckPermissionsOrDeclareWhyNot: ArchRule = RULES.operationsAreGuarded()
        .because("authorisation is deny-by-default: a use case without a guard check is reachable by everyone (ADR 0017)")

    @ArchTest
    val systemOperationsRequireTheSystem: ArchRule = RULES.systemOperationsRequireSystem()
        .because("the system holds no permissions; a system operation refuses to run outside asSystem (ADR 0067)")

    @ArchTest
    val systemOperationsLiveInSystemPorts: ArchRule = RULES.systemOperationsLiveInSystemPorts()
        .because("a system entry point is a separate port, not an annotation on a user one (ADR 0067)")

    @ArchTest
    val userFacingAdaptersDoNotReachSystemPorts: ArchRule = RULES.systemPortsAreNotReachableFromUserFacingAdapters()
        .because("people reach use cases through web, REST, MCP and SSE adapters, which cannot even see system ports (ADR 0067)")

    companion object {
        private val SERVICES = listOf("projects", "evaluation", "tasks", "identity", "platform", "runner", "llm.budget", "governance")
            .map { "pl.mos.backoffice.$it.application.service.." }

        /** Internal and machine-facing adapters (schedulers, work-queue handlers, filters, `adapter.in.runner`) are not listed. */
        val USER_FACING_ADAPTERS = listOf(
            "..adapter.in.web..", "..adapter.in.rest..", "..adapter.in.mcp..", "..adapter.in.sse..",
            "pl.mos.backoffice.app.web..", "pl.mos.backoffice.llm.web..",
        )

        private val RULES = PortPermissionsRules(
            inboundPorts = listOf("..application.port.in.."),
            services = SERVICES,
            systemPorts = listOf("..application.port.in.system.."),
            userFacingAdapters = USER_FACING_ADAPTERS,
        )
    }
}
