package pl.mos.backoffice.app

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.junit.jupiter.api.Tag

/**
 * Deny by default (ADR 0017, principle 4 of docs/architecture/08-identity-and-access.md): every public method
 * of a class in `..application.service..` (contexts projects, evaluation, tasks, identity) that implements a
 * use-case port from `..application.port.in..` must either
 * - call `AuthorizationGate.require(permission)` or `require(permission, projectId)` itself, or
 * - be annotated `@PublicOperation(reason)` or `@SystemOperation(reason)`.
 *
 * The call must be in the method body (a call made only inside a non-inline lambda or a private helper does
 * not count). Run it alone with
 * `./gradlew :app:test --tests "pl.mos.backoffice.app.DenyByDefaultArchitectureTest"`; the failure lists every
 * offending method as `Class.method(params)`.
 */
@Tag("architecture")
@Tag("deny-by-default")
@AnalyzeClasses(packages = ["pl.mos.backoffice"], importOptions = [ImportOption.DoNotIncludeTests::class])
class DenyByDefaultArchitectureTest {

    @ArchTest
    val useCasesCheckPermissionsOrDeclareWhyNot: ArchRule = methods()
        .that(implementUseCasePorts)
        .should(callTheGateOrCarryAnExemption)
        .because("authorisation is deny-by-default: a use case without a gate call is reachable by everyone (ADR 0017)")

    companion object {
        private const val GATE = "pl.mos.backoffice.shared.security.AuthorizationGate"
        private const val PUBLIC = "pl.mos.backoffice.shared.security.PublicOperation"
        private const val SYSTEM = "pl.mos.backoffice.shared.security.SystemOperation"
        private val SERVICE_PACKAGE = Regex("^pl\\.mos\\.backoffice\\.(projects|evaluation|tasks|identity|platform|runner|llm\\.budget|governance)\\.application\\.service(\\..*)?$")
        private val OBJECT_METHODS = setOf("equals", "hashCode", "toString")

        private val implementUseCasePorts = object : DescribedPredicate<JavaMethod>("are public use-case methods of application.service classes") {
            override fun test(method: JavaMethod): Boolean {
                val owner = method.owner
                if (!SERVICE_PACKAGE.matches(owner.packageName)) return false
                val modifiers = method.modifiers
                if (JavaModifier.PUBLIC !in modifiers || JavaModifier.STATIC in modifiers) return false
                if (JavaModifier.SYNTHETIC in modifiers || JavaModifier.BRIDGE in modifiers) return false
                val name = method.name
                if (name in OBJECT_METHODS || name == "copy" || name.startsWith("component") || name.contains('$')) return false
                val parameters = method.rawParameterTypes.map { it.name }
                return owner.allRawInterfaces
                    .filter { it.packageName.contains(".application.port.in") }
                    .any { port -> port.methods.any { it.name == name && it.rawParameterTypes.map { p -> p.name } == parameters } }
            }
        }

        private val callTheGateOrCarryAnExemption = object : ArchCondition<JavaMethod>("call AuthorizationGate.require(..) or be annotated @PublicOperation / @SystemOperation") {
            override fun check(method: JavaMethod, events: ConditionEvents) {
                val exempt = method.isAnnotatedWith(PUBLIC) || method.isAnnotatedWith(SYSTEM)
                val callsGate = method.methodCallsFromSelf.any { it.targetOwner.fullName == GATE && it.name == "require" }
                val ok = exempt || callsGate
                val message = "${method.owner.simpleName}.${method.name}(${method.rawParameterTypes.joinToString { it.simpleName }}) " +
                    (if (ok) "is authorised" else "neither calls AuthorizationGate.require(..) nor is annotated @PublicOperation/@SystemOperation") +
                    " (${method.sourceCodeLocation})"
                events.add(SimpleConditionEvent(method, ok, message))
            }
        }
    }
}
