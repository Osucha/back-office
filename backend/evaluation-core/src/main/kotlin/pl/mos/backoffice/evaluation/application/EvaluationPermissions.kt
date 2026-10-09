package pl.mos.backoffice.evaluation.application

import pl.mos.backoffice.shared.security.PermissionDefinition
import pl.mos.backoffice.shared.security.PermissionScope

/** Permissions of the Model Evaluation context (docs/architecture/08-identity-and-access.md, ADR 0017). All platform-scoped. */
object EvaluationPermissions {
    const val MODULE = "evaluation"

    /** Read model profiles and the provider list. */
    const val MODELS_VIEW = "models.view"

    /** Create, edit and delete model profiles and their pricing; smoke-test a profile. */
    const val MODELS_MANAGE = "models.manage"

    /** Read task suites, benchmarks, attempts and leaderboards. */
    const val EVALUATION_VIEW = "evaluation.view"

    /** Create and edit suites and tasks; create, start, cancel and delete benchmarks. */
    const val EVALUATION_RUN = "evaluation.run"

    val definitions: List<PermissionDefinition> = listOf(
        PermissionDefinition(MODELS_VIEW, PermissionScope.PLATFORM, MODULE, "Read model profiles"),
        PermissionDefinition(MODELS_MANAGE, PermissionScope.PLATFORM, MODULE, "Create and edit model profiles and pricing, test a profile"),
        PermissionDefinition(EVALUATION_VIEW, PermissionScope.PLATFORM, MODULE, "Read suites, benchmarks and leaderboards"),
        PermissionDefinition(EVALUATION_RUN, PermissionScope.PLATFORM, MODULE, "Create suites and tasks; create, start and cancel benchmarks"),
    )
}
