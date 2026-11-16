package pl.mos.backoffice.app

import com.osucha.portpermissions.archunit.PortPermissionsRules
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import org.junit.jupiter.api.Tag

/**
 * Deny by default (ADR 0017, ADR 0066, principle 4 of docs/architecture/08-identity-and-access.md), checked by
 * port-permissions' `PortPermissionsRules.operationsAreGuarded()`: every public method of a class in an
 * `..application.service..` package of the contexts below that implements a use-case port from
 * `..application.port.in..` must either
 * - call `AccessGuard.require(...)` (or `requireSystem()`), in its body or in a lambda it creates (one level), or
 * - be annotated `@PublicOperation(reason)` or `@SystemOperation(reason)` (com.osucha.portpermissions).
 *
 * A check inside a non-inline private helper does not count. Run it alone with
 * `./gradlew :app:test --tests "pl.mos.backoffice.app.DenyByDefaultArchitectureTest"`; the failure lists every
 * offending method.
 */
@Tag("architecture")
@Tag("deny-by-default")
@AnalyzeClasses(packages = ["pl.mos.backoffice"], importOptions = [ImportOption.DoNotIncludeTests::class])
class DenyByDefaultArchitectureTest {

    @ArchTest
    val useCasesCheckPermissionsOrDeclareWhyNot: ArchRule = RULES.operationsAreGuarded()
        .because("authorisation is deny-by-default: a use case without a guard check is reachable by everyone (ADR 0017)")

    companion object {
        private val SERVICES = listOf("projects", "evaluation", "tasks", "identity", "platform", "runner", "llm.budget", "governance")
            .map { "pl.mos.backoffice.$it.application.service.." }

        private val RULES = PortPermissionsRules(
            inboundPorts = listOf("..application.port.in.."),
            services = SERVICES,
        )
    }
}
