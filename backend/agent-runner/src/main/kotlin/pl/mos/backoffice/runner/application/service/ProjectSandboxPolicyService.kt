package pl.mos.backoffice.runner.application.service

import pl.mos.backoffice.runner.application.port.`in`.ManageProjectSandboxPolicy
import pl.mos.backoffice.runner.application.port.`in`.SandboxPolicyView
import pl.mos.backoffice.runner.application.port.out.ProjectPolicyStore
import pl.mos.backoffice.runner.application.port.out.RunnerPoolStore
import pl.mos.backoffice.runner.application.port.out.Transactions
import pl.mos.backoffice.runner.domain.RunnerPermissions
import pl.mos.backoffice.runner.domain.RunnerPool
import pl.mos.backoffice.runner.domain.policy.PolicyLayer
import pl.mos.backoffice.runner.domain.policy.PolicyLevel
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.runner.SandboxPolicy
import pl.mos.backoffice.shared.security.AuthorizationGate
import java.util.UUID

/**
 * The project level of the sandbox policy (`GET/PUT /projects/{projectId}/sandbox-policy`). The view shows
 * the stored project policy, the ceiling above it (platform + the pool the project's jobs would use by
 * default) and the effective result with the level that set each field.
 */
class ProjectSandboxPolicyService(
    private val policies: ProjectPolicyStore,
    private val pools: RunnerPoolStore,
    private val lifecycle: JobLifecycle,
    private val tx: Transactions,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : ManageProjectSandboxPolicy {

    override fun get(projectId: UUID): SandboxPolicyView {
        gate.require(RunnerPermissions.PROJECT_VIEW, projectId)
        return view(projectId, policies.findPolicy(projectId) ?: SandboxPolicy.DEFAULT)
    }

    override fun update(projectId: UUID, policy: SandboxPolicy): SandboxPolicyView {
        val user = gate.require(RunnerPermissions.PROJECT_MANAGE, projectId)
        val errors = lifecycle.resolver.validate(policy)
        if (errors.isNotEmpty()) throw ValidationException(errors.map { ValidationException.FieldError("policy", it) })
        tx.inTransaction {
            val previous = policies.findPolicy(projectId)
            policies.savePolicy(projectId, policy, lifecycle.now(), user.login)
            audit.record(
                AuditEntry(
                    "PROJECT_SANDBOX_POLICY_UPDATE", "Project", projectId.toString(), projectId = projectId,
                    details = AuditableChanges.changed("sandboxPolicy" to (previous?.toString() to policy.toString())),
                ),
            )
        }
        return view(projectId, policy)
    }

    private fun view(projectId: UUID, project: SandboxPolicy): SandboxPolicyView {
        val pool = defaultPool(projectId)
        val top = listOfNotNull(lifecycle.platformLayer(), pool?.let(lifecycle::poolLayer))
        val hosts = lifecycle.mandatoryHosts(null)
        val ceiling = lifecycle.resolver.resolve(top, mandatoryHosts = hosts)
        val effective = lifecycle.resolver.resolve(top + PolicyLayer(PolicyLevel.PROJECT, project), allowedImages = pool?.allowedImages, mandatoryHosts = hosts)
        return SandboxPolicyView(project, effective.policy, ceiling.policy, effective.sources, pool?.id, effective.warnings + effective.errors)
    }

    /** The pool a job of this project without a pool selector would most likely use: one dedicated to it, else a shared one. */
    private fun defaultPool(projectId: UUID): RunnerPool? {
        val candidates = pools.findAll().filter { it.acceptsProject(projectId) }
        return candidates.firstOrNull { projectId in it.projectIds } ?: candidates.minByOrNull { it.id }
    }
}
