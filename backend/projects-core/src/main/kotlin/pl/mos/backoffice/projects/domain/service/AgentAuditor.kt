package pl.mos.backoffice.projects.domain.service

import pl.mos.backoffice.shared.runner.AgentAccessMode
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.StepNeeds
import java.util.UUID

/** ADR 0061: what the agent audit found. */
enum class AgentAuditFindingKind { MISSING_SKILL, NEEDS_CODING_AGENT, READ_ONLY_PUSH, UNKNOWN_RULE, UNKNOWN_SKILL, DUPLICATE_SKILL_NAME, UNKNOWN_AGENT }

/** ADR 0061: an ERROR stops a run (the resolver refuses the step); a WARNING does not. */
enum class AgentAuditSeverity { ERROR, WARNING }

/** One deterministic finding; [nodeIds] are the steps concerned, [key] the skill, rule or agent key concerned. */
data class AgentAuditFinding(
    val kind: AgentAuditFindingKind,
    val severity: AgentAuditSeverity,
    val message: String,
    val nodeIds: List<String> = emptyList(),
    val key: String? = null,
)

/** A task step of the draft with what its template and overrides need (from the `TaskCatalog`). */
data class AuditedStep(val nodeId: String, val needs: StepNeeds)

/** A skill the steps of one agent require; [name] null when the library has no skill with the key. */
data class AuditRequiredSkill(val key: String, val name: String?, val nodeIds: List<String>)

/** One agent the workflow uses: its environment, the steps it runs, what they require and what is missing. */
data class AgentAuditEntry(
    val agent: AgentSnapshot,
    val agentId: UUID?,
    /** The project of a project agent; null for a global one. */
    val projectId: UUID?,
    val nodeIds: List<String>,
    val rules: List<AuditRule>,
    val skills: List<AuditSkill>,
    val requiredSkills: List<AuditRequiredSkill>,
    val missingSkills: List<String>,
    val findings: List<AgentAuditFinding>,
)

data class AuditRule(val key: String, val name: String, val automatic: Boolean)

data class AuditSkill(val key: String, val name: String)

/** The audit: one entry per agent in the order the steps first use them, and the findings tied to no agent. */
data class AgentAuditReport(val agents: List<AgentAuditEntry>, val findings: List<AgentAuditFinding>)

/**
 * ADR 0061 decision 3: the agent audit of a workflow draft. Pure: the caller resolves every step ([AuditedStep]) and every
 * agent key the steps use ([profiles]; null = the agent does not exist) through the `TaskCatalog`. Findings:
 *
 * - `MISSING_SKILL` (warning): a step requires a skill its agent lacks;
 * - `NEEDS_CODING_AGENT` (error): a step needs a coding agent but its agent gives a model answer;
 * - `READ_ONLY_PUSH` (error): a read-only coding agent on a step that pushes;
 * - `UNKNOWN_RULE` / `UNKNOWN_SKILL` (error): the agent names a rule or skill the library no longer has;
 * - `DUPLICATE_SKILL_NAME` (warning): two skills of one agent have the same name (the harness cannot tell them apart);
 * - `UNKNOWN_AGENT` (error, not tied to an agent entry): a step names an agent that does not exist.
 */
object AgentAuditor {

    fun audit(steps: List<AuditedStep>, profiles: Map<String, AgentProfile?>): AgentAuditReport {
        val byAgent = steps.groupBy { it.needs.agentKey }
        val unknown = byAgent.filterKeys { profiles[it] == null }.map { (key, agentSteps) ->
            AgentAuditFinding(
                AgentAuditFindingKind.UNKNOWN_AGENT, AgentAuditSeverity.ERROR,
                "agent $key does not exist: choose another agent for ${agentSteps.size.steps()}",
                agentSteps.map { it.nodeId }, key,
            )
        }
        val entries = byAgent.mapNotNull { (key, agentSteps) -> profiles[key]?.let { entry(it, agentSteps) } }
        return AgentAuditReport(entries, unknown)
    }

    private fun entry(profile: AgentProfile, steps: List<AuditedStep>): AgentAuditEntry {
        val agent = profile.agent
        val skillKeys = profile.skills.map { it.key }.toSet()
        val required = steps.flatMap { step -> step.needs.requiredSkills.map { it to step.nodeId } }
            .groupBy({ it.first.key }, { it })
            .map { (key, uses) -> AuditRequiredSkill(key, uses.firstNotNullOfOrNull { it.first.name }, uses.map { it.second }.distinct()) }
        val missing = required.filter { it.key !in skillKeys }
        val findings = buildList {
            missing.forEach { skill ->
                add(AgentAuditFinding(
                    AgentAuditFindingKind.MISSING_SKILL, AgentAuditSeverity.WARNING,
                    "agent ${agent.name} lacks skill ${skill.name ?: skill.key} (required by ${skill.nodeIds.size.steps()}): add it to the agent or choose another agent",
                    skill.nodeIds, skill.key,
                ))
            }
            if (agent.modelAnswer) {
                steps.filter { it.needs.needsCodingAgent }.map { it.nodeId }.takeIf { it.isNotEmpty() }?.let { nodeIds ->
                    add(AgentAuditFinding(
                        AgentAuditFindingKind.NEEDS_CODING_AGENT, AgentAuditSeverity.ERROR,
                        "agent ${agent.name} gives a model answer; a coding agent is needed by ${nodeIds.size.steps()}",
                        nodeIds, agent.key,
                    ))
                }
            } else if (agent.access.mode == AgentAccessMode.READ_ONLY) {
                steps.filter { it.needs.pushes }.map { it.nodeId }.takeIf { it.isNotEmpty() }?.let { nodeIds ->
                    add(AgentAuditFinding(
                        AgentAuditFindingKind.READ_ONLY_PUSH, AgentAuditSeverity.ERROR,
                        "agent ${agent.name} may only read but pushes in ${nodeIds.size.steps()}: set push to false or choose another agent",
                        nodeIds, agent.key,
                    ))
                }
            }
            val nodeIds = steps.map { it.nodeId }
            profile.unknownRules.forEach {
                add(AgentAuditFinding(AgentAuditFindingKind.UNKNOWN_RULE, AgentAuditSeverity.ERROR, "rule $it of agent ${agent.name} no longer exists", nodeIds, it))
            }
            profile.unknownSkills.forEach {
                add(AgentAuditFinding(AgentAuditFindingKind.UNKNOWN_SKILL, AgentAuditSeverity.ERROR, "skill $it of agent ${agent.name} no longer exists", nodeIds, it))
            }
            profile.skills.groupBy { it.name.trim().lowercase() }.values.filter { it.size > 1 }.forEach { same ->
                add(AgentAuditFinding(
                    AgentAuditFindingKind.DUPLICATE_SKILL_NAME, AgentAuditSeverity.WARNING,
                    "agent ${agent.name} has ${same.size} skills named \"${same.first().name}\" (${same.joinToString { it.key }}): rename one",
                    emptyList(), same.first().key,
                ))
            }
        }
        return AgentAuditEntry(
            agent = agent,
            agentId = profile.id,
            projectId = profile.projectId,
            nodeIds = steps.map { it.nodeId },
            rules = profile.rules.map { AuditRule(it.key, it.name, it.automatic) },
            skills = profile.skills.map { AuditSkill(it.key, it.name) },
            requiredSkills = required,
            missingSkills = missing.map { it.key },
            findings = findings,
        )
    }

    private fun Int.steps(): String = if (this == 1) "1 step" else "$this steps"
}
