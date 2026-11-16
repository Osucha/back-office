package pl.mos.backoffice.evaluation.application

import com.osucha.portpermissions.PermissionSet
import pl.mos.backoffice.shared.security.ModelAccessPermissions
import pl.mos.backoffice.shared.security.ModulePermissions

/** Permissions of the Model Evaluation context (docs/architecture/08-identity-and-access.md, ADR 0017, ADR 0066). All platform-scoped. */
object EvaluationPermissions {
    const val MODULE = "evaluation"

    object Models : PermissionSet("models") {
        val MANAGE = permission("manage", "Create and edit model profiles and pricing, test a profile")
    }

    object Evaluation : PermissionSet("evaluation") {
        val VIEW = permission("view", "Read suites, benchmarks and leaderboards")
        val RUN = permission("run", "Create suites and tasks; create, start and cancel benchmarks")
    }

    /** Read model profiles and the provider list. */
    val MODELS_VIEW = ModelAccessPermissions.VIEW

    /** Create, edit and delete model profiles and their pricing; smoke-test a profile. */
    val MODELS_MANAGE = Models.MANAGE

    /** Read task suites, benchmarks, attempts and leaderboards. */
    val EVALUATION_VIEW = Evaluation.VIEW

    /** Create and edit suites and tasks; create, start, cancel and delete benchmarks. */
    val EVALUATION_RUN = Evaluation.RUN

    /** What this context contributes to the catalogue. */
    val contribution = ModulePermissions(MODULE, listOf(ModelAccessPermissions, Models, Evaluation))
}
