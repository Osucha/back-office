package pl.mos.backoffice.app

import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields

/**
 * Hexagonal architecture rules (docs/architecture/01-overview.md). The build fails when a core module
 * reaches for a framework or when adapters of one context depend on adapters of another.
 */
@AnalyzeClasses(packages = ["pl.mos.backoffice"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {

    private val frameworkPackages = arrayOf(
        "org.springframework..", "jakarta.persistence..", "org.hibernate..", "tools.jackson..",
        "com.fasterxml.jackson..", "org.bsc.langgraph4j..", "io.fabric8..", "org.gitlab4j..",
        "org.springframework.ai..", "org.springframework.expression..",
    )

    @ArchTest
    val sharedKernelIsFrameworkFree: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.shared..")
        .should().dependOnClassesThat().resideInAnyPackage(*frameworkPackages)
        .because("shared-kernel is pure Kotlin")

    @ArchTest
    val coreModulesAreFrameworkFree: ArchRule = noClasses()
        .that().resideInAnyPackage(
            "pl.mos.backoffice.projects.domain..", "pl.mos.backoffice.projects.application..",
            "pl.mos.backoffice.evaluation.domain..", "pl.mos.backoffice.evaluation.application..",
            "pl.mos.backoffice.tasks.domain..", "pl.mos.backoffice.tasks.application..",
        )
        .should().dependOnClassesThat().resideInAnyPackage(*frameworkPackages)
        .because("domain and application layers must not depend on frameworks")

    @ArchTest
    val domainDoesNotDependOnApplication: ArchRule = noClasses()
        .that().resideInAnyPackage("pl.mos.backoffice.projects.domain..", "pl.mos.backoffice.evaluation.domain..", "pl.mos.backoffice.tasks.domain..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.projects.application..", "pl.mos.backoffice.evaluation.application..", "pl.mos.backoffice.tasks.application..",
            "pl.mos.backoffice..adapter..",
        )

    @ArchTest
    val applicationDoesNotDependOnAdapters: ArchRule = noClasses()
        .that().resideInAnyPackage("pl.mos.backoffice.projects.application..", "pl.mos.backoffice.evaluation.application..", "pl.mos.backoffice.tasks.application..")
        .should().dependOnClassesThat().resideInAPackage("pl.mos.backoffice..adapter..")

    @ArchTest
    val coreModulesOnlyUseOwnContextAndSharedKernel: ArchRule = classes()
        .that().resideInAnyPackage("pl.mos.backoffice.projects.domain..", "pl.mos.backoffice.projects.application..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.projects.domain..", "pl.mos.backoffice.projects.application..",
            "pl.mos.backoffice.shared..", "java..", "kotlin..", "kotlinx..", "org.jetbrains.annotations..",
        )

    @ArchTest
    val evaluationCoreOnlyUsesOwnContextAndSharedKernel: ArchRule = classes()
        .that().resideInAnyPackage("pl.mos.backoffice.evaluation.domain..", "pl.mos.backoffice.evaluation.application..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.evaluation.domain..", "pl.mos.backoffice.evaluation.application..",
            "pl.mos.backoffice.shared..", "java..", "kotlin..", "kotlinx..", "org.jetbrains.annotations..",
        )

    @ArchTest
    val tasksCoreOnlyUsesOwnContextAndSharedKernel: ArchRule = classes()
        .that().resideInAnyPackage("pl.mos.backoffice.tasks.domain..", "pl.mos.backoffice.tasks.application..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.tasks.domain..", "pl.mos.backoffice.tasks.application..",
            "pl.mos.backoffice.shared..", "java..", "kotlin..", "kotlinx..", "org.jetbrains.annotations..",
        )

    @ArchTest
    val projectsAdaptersDoNotDependOnOtherContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.projects.adapter..")
        .should().dependOnClassesThat().resideInAnyPackage("pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..")

    @ArchTest
    val evaluationAdaptersDoNotDependOnOtherContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.evaluation.adapter..")
        .should().dependOnClassesThat().resideInAnyPackage("pl.mos.backoffice.projects..", "pl.mos.backoffice.tasks..")

    @ArchTest
    val tasksAdaptersDoNotDependOnOtherContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.tasks.adapter..")
        .should().dependOnClassesThat().resideInAnyPackage("pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..")

    // ---------------------------------------------------------------- platform runtime (ADR 0023)

    @ArchTest
    val platformDoesNotDependOnContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.platform..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..",
            "pl.mos.backoffice.identity..", "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..", "pl.mos.backoffice.app..",
        )
        .because("the backbone serves every context through shared-kernel ports (WorkQueue, WorkHandler, EventStream, ClusterLock, ClusterSignals)")

    @ArchTest
    val platformApplicationIsFrameworkFree: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.platform.application..")
        .should().dependOnClassesThat().resideInAnyPackage(*frameworkPackages)

    @ArchTest
    val contextsDoNotDependOnPlatformRuntime: ArchRule = noClasses()
        .that().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..",
            "pl.mos.backoffice.identity..", "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..",
        )
        .should().dependOnClassesThat().resideInAPackage("pl.mos.backoffice.platform..")
        .because("contexts use the shared-kernel ports; platform-runtime is an implementation detail")

    // ---------------------------------------------------------------- identity & access (ADR 0016-0018)

    @ArchTest
    val identityCoreIsFrameworkFree: ArchRule = noClasses()
        .that().resideInAnyPackage("pl.mos.backoffice.identity.domain..", "pl.mos.backoffice.identity.application..")
        .should().dependOnClassesThat().resideInAnyPackage(*frameworkPackages)
        .because("identity-core is pure Kotlin like every other core module")

    @ArchTest
    val identityCoreOnlyUsesOwnContextAndSharedKernel: ArchRule = classes()
        .that().resideInAnyPackage("pl.mos.backoffice.identity.domain..", "pl.mos.backoffice.identity.application..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.identity.domain..", "pl.mos.backoffice.identity.application..",
            "pl.mos.backoffice.shared..", "java..", "kotlin..", "kotlinx..", "org.jetbrains.annotations..",
            // JDK crypto (HMAC-SHA1 of RFC 6238 TOTP in the domain, ADR 0021) - part of the JDK, not a framework
            "javax.crypto..",
        )

    @ArchTest
    val identityDomainDoesNotDependOnApplication: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.identity.domain..")
        .should().dependOnClassesThat().resideInAnyPackage("pl.mos.backoffice.identity.application..", "pl.mos.backoffice..adapter..")

    @ArchTest
    val identityAdaptersDoNotDependOnOtherContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.identity.adapter..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..",
            "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..",
        )
        .because("other contexts are reached through shared-kernel ports (CiTokenVerifier, ProjectAccessProvisioner, AuditPort)")

    @ArchTest
    val otherContextsDoNotDependOnIdentity: ArchRule = noClasses()
        .that().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..",
            "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..", "pl.mos.backoffice.governance..",
        )
        .should().dependOnClassesThat().resideInAPackage("pl.mos.backoffice.identity..")
        .because("use AuthorizationGate, AuditPort and the other shared.security ports instead")

    @ArchTest
    val onlyIdentityAdaptersAndAppUseSpringSecurity: ArchRule = noClasses()
        .that().resideOutsideOfPackages("pl.mos.backoffice.identity.adapter..", "pl.mos.backoffice.app..")
        .should().dependOnClassesThat().resideInAPackage("org.springframework.security..")
        .because("authentication is the identity context's job; everyone else calls AuthorizationGate (ADR 0017)")

    // ---------------------------------------------------------------- governance (ADR 0025)

    @ArchTest
    val governanceCoreOnlyUsesOwnContextAndSharedKernel: ArchRule = classes()
        .that().resideInAnyPackage("pl.mos.backoffice.governance.domain..", "pl.mos.backoffice.governance.application..")
        .should().onlyDependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.governance.domain..", "pl.mos.backoffice.governance.application..",
            "pl.mos.backoffice.shared..", "java..", "kotlin..", "kotlinx..", "org.jetbrains.annotations..",
        )
        .because("governance-core is pure Kotlin (JDK crypto only) and reaches other contexts through shared-kernel ports")

    @ArchTest
    val governanceDomainDoesNotDependOnApplication: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.governance.domain..")
        .should().dependOnClassesThat().resideInAnyPackage("pl.mos.backoffice.governance.application..", "pl.mos.backoffice..adapter..")

    /**
     * Governance adapters reach other contexts only through shared-kernel ports (RunProvenanceSource, UsageLedger,
     * TaskCatalog) and SQL read models - except the MCP server (`adapter.in.mcp`) and its wiring
     * (`adapter.config`), a driving adapter that composes use cases of several contexts like `app` does.
     */
    @ArchTest
    val governanceAdaptersDoNotDependOnOtherContexts: ArchRule = noClasses()
        .that().resideInAPackage("pl.mos.backoffice.governance.adapter..")
        .and().resideOutsideOfPackages("pl.mos.backoffice.governance.adapter.in.mcp..", "pl.mos.backoffice.governance.adapter.config..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..",
            "pl.mos.backoffice.identity..", "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..",
        )

    /** The MCP exception: only the use-case ports (`application.port.in`), the domain types they return and `RunFilter`. */
    @ArchTest
    val mcpServerUsesOnlyUseCasePortsOfOtherContexts: ArchRule = noClasses()
        .that().resideInAnyPackage("pl.mos.backoffice.governance.adapter.in.mcp..", "pl.mos.backoffice.governance.adapter.config..")
        .should().dependOnClassesThat(
            com.tngtech.archunit.base.DescribedPredicate.describe("are outside the port.in / domain packages of projects and tasks") { c ->
                val name = c.packageName
                val otherContext = name.startsWith("pl.mos.backoffice.projects") || name.startsWith("pl.mos.backoffice.tasks")
                val allowed = name.contains(".application.port.in") || name.contains(".domain") || c.name == "pl.mos.backoffice.projects.application.port.out.RunFilter"
                (otherContext && !allowed) || name.startsWith("pl.mos.backoffice.evaluation") || name.startsWith("pl.mos.backoffice.identity") ||
                    name.startsWith("pl.mos.backoffice.llm") || name.startsWith("pl.mos.backoffice.runner")
            },
        )
        .because("the MCP server must go through the same use cases (and AuthorizationGate) as REST")

    @ArchTest
    val otherContextsDoNotDependOnGovernance: ArchRule = noClasses()
        .that().resideInAnyPackage(
            "pl.mos.backoffice.projects..", "pl.mos.backoffice.evaluation..", "pl.mos.backoffice.tasks..", "pl.mos.backoffice.identity..",
            "pl.mos.backoffice.llm..", "pl.mos.backoffice.runner..",
        )
        .should().dependOnClassesThat().resideInAPackage("pl.mos.backoffice.governance..")
        .because("governance is reached through shared-kernel ports (EnvelopeSigner) and domain events")

    @ArchTest
    val controllersLiveInInboundAdapters: ArchRule = classes()
        .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
        .should().resideInAnyPackage("pl.mos.backoffice..adapter.in..", "pl.mos.backoffice.runner..", "pl.mos.backoffice.llm..", "pl.mos.backoffice.app..")

    @ArchTest
    val jpaEntitiesLiveInPersistenceAdapters: ArchRule = classes()
        .that().areAnnotatedWith("jakarta.persistence.Entity")
        .should().resideInAnyPackage("pl.mos.backoffice..adapter.out.persistence..", "pl.mos.backoffice.runner..", "pl.mos.backoffice.llm..")

    /** ADR 0030: core code seals secrets through the `SecretSealer` port; only outbound adapters may decrypt them. */
    @ArchTest
    val onlyOutboundAdaptersOpenConnectionSecrets: ArchRule = noClasses()
        .that().resideOutsideOfPackages("pl.mos.backoffice.projects.adapter.out..", "pl.mos.backoffice.projects.adapter.config..")
        .should().dependOnClassesThat().haveFullyQualifiedName("pl.mos.backoffice.projects.adapter.out.secrets.AesGcmSecretCipher")
        .because("decrypted connection secrets must never reach controllers, use cases or other contexts (ADR 0030)")

    /** ADR 0030: the REST representation of a connection has no place for the secret. */
    @ArchTest
    val connectionResponsesCarryNoSecret: ArchRule = noFields()
        .that().areDeclaredIn("pl.mos.backoffice.projects.adapter.in.rest.ConnectionDto")
        .should().haveName("secret")
        .orShould().haveName("apiToken")
        .orShould().haveName("token")
}
