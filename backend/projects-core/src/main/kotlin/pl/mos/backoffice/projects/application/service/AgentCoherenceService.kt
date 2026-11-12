package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.CheckAgentCoherence
import pl.mos.backoffice.projects.application.port.`in`.CoherenceFinding
import pl.mos.backoffice.projects.application.port.`in`.CoherenceQuote
import pl.mos.backoffice.projects.application.port.`in`.CoherenceReport
import pl.mos.backoffice.projects.application.port.`in`.CoherenceSeverity
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.domain.model.Project
import pl.mos.backoffice.projects.domain.model.ProjectId
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.llm.LlmGateway
import pl.mos.backoffice.shared.llm.LlmRequest
import pl.mos.backoffice.shared.llm.ModelCatalog
import pl.mos.backoffice.shared.llm.ModelDefaults
import pl.mos.backoffice.shared.llm.ModelProfileSnapshot
import pl.mos.backoffice.shared.llm.ModelRuntime
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.tasks.AgentEnvironmentText
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.TaskCatalog
import pl.mos.backoffice.shared.usage.UsageContext
import pl.mos.backoffice.shared.usage.UsageKind
import java.util.UUID

/**
 * ADR 0061 decision 4: the coherence check of an agent's environment. The document is the one a step of the agent gets
 * ([AgentEnvironmentText]: preamble, instructions, the project constitution with a project, the rules) plus the names and
 * descriptions of its skills. Back Office has no port that reads files of a repository, so the repository's
 * `AGENTS.md` / `CLAUDE.md` are not read: with a project that has repositories the sources say `repo files not read`.
 *
 * The model is the platform default for model answers ([ModelDefaults] without a user); the call is booked through the
 * gateway as `MODEL_TEST` usage (an on-demand check outside runs; the contract has no dedicated kind) with the project
 * and the caller. Nothing is stored except the audit entry `AGENT_COHERENCE_CHECK`.
 */
class AgentCoherenceService(
    private val catalog: TaskCatalog,
    private val projects: ProjectRepository,
    private val llm: LlmGateway,
    private val models: ModelCatalog,
    private val defaults: ModelDefaults?,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
) : CheckAgentCoherence {

    override fun check(agentId: UUID, projectId: ProjectId?): CoherenceReport {
        val user = gate.require(ProjectsPermissions.LIBRARY_VIEW)
        projectId?.let { gate.require(ProjectsPermissions.PROJECT_VIEW, it.value) }
        val project = projectId?.let { projects.findById(it) ?: throw NotFoundException("Project", it) }
        val profile = catalog.agentProfileById(agentId, projectId?.value) ?: throw NotFoundException("Agent", agentId)
        profile.projectId?.let { own ->
            if (projectId != null && own != projectId.value) throw NotFoundException("Agent", agentId)
            gate.require(ProjectsPermissions.PROJECT_VIEW, own)
        }
        val model = model()
        val document = document(profile, project)
        val result = llm.chat(
            model.ref,
            LlmRequest.of(
                SYSTEM_PROMPT, userPrompt(profile, document.text), responseSchema = RESPONSE_SCHEMA,
                usageContext = UsageContext(UsageKind.MODEL_TEST, projectId = projectId?.value ?: profile.projectId, actingUserId = user.userId?.value),
            ),
        )
        val findings = findings(result.structured)
        val report = CoherenceReport(profile.agent.key, model.displayName, model.pricing.costOf(result.usage), document.sources, findings)
        audit.record(
            AuditEntry(
                operation = "AGENT_COHERENCE_CHECK", entityType = "Agent", entityId = agentId.toString(),
                projectId = projectId?.value ?: profile.projectId,
                details = mapOf(
                    "agentKey" to profile.agent.key, "model" to model.displayName, "sources" to document.sources.size,
                    "findings" to findings.size, "contradictions" to findings.count { it.severity == CoherenceSeverity.CONTRADICTION },
                    "cost" to report.cost.amount.toPlainString(),
                ),
            ),
        )
        return report
    }

    /** The platform default for model answers; a 409 when there is none or it cannot be called. */
    private fun model(): ModelProfileSnapshot {
        val id = defaults?.resolve(null, StepKind.LLM_CALL)?.profileId
            ?: throw InvalidStateException("no default model for model answers is configured: set the platform default model first")
        val profile = models.find(id) ?: throw InvalidStateException("the default model profile $id does not exist")
        if (!profile.enabled) throw InvalidStateException("the default model ${profile.displayName} is disabled")
        if (profile.runtime != ModelRuntime.API || !llm.isConfigured(profile.ref)) {
            throw InvalidStateException("the default model ${profile.displayName} cannot be called through the gateway")
        }
        return profile
    }

    private data class Document(val text: String, val sources: List<String>)

    private fun document(profile: AgentProfile, project: Project?): Document {
        val agent = profile.agent
        val constitution = project?.constitution?.takeIf { it.isNotBlank() }
        val environment = AgentEnvironmentText.compose(agent.modelAnswer, agent.instructions, constitution, profile.rules)
        val skills = profile.skills.takeIf { it.isNotEmpty() }?.let { list ->
            "## Skills (loaded on demand; name and description)\n" + list.joinToString("\n") { s ->
                "- ${s.name} (${s.key})" + s.description.trim().takeIf { it.isNotEmpty() }?.let { ": ${it.replace('\n', ' ')}" }.orEmpty()
            }
        }
        val sources = buildList {
            add(SOURCE_PREAMBLE)
            if (agent.instructions.isNotBlank()) add(SOURCE_INSTRUCTIONS)
            if (constitution != null) add(SOURCE_CONSTITUTION)
            profile.rules.forEach { add("rule:${it.key}") }
            profile.skills.forEach { add("skill:${it.key}") }
            if (project != null && project.repositories.isNotEmpty()) add(REPO_FILES_NOT_READ)
        }
        return Document(listOfNotNull(environment, skills).joinToString("\n\n"), sources)
    }

    private fun userPrompt(profile: AgentProfile, document: String): String =
        "Agent: ${profile.agent.name} (${profile.agent.key}).\n\n" +
            "Sources: the platform preamble (\"# Working in Back Office\"), the agent's role (\"## Your role\"), the project " +
            "constitution, each rule (\"### <name> (<key>)\" under \"## Rules\") and each skill (\"## Skills\"). Name a quote's " +
            "source as preamble, instructions, constitution, rule:<key> or skill:<key>.\n\n" +
            "<environment>\n$document\n</environment>"

    /** Defensive read of the structured answer: unknown severities read as AMBIGUITY, findings without a summary are dropped. */
    private fun findings(structured: Map<String, Any?>?): List<CoherenceFinding> {
        val list = structured?.get("findings") as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val f = item as? Map<*, *> ?: return@mapNotNull null
            val summary = (f["summary"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val severity = CoherenceSeverity.entries.firstOrNull { it.name == (f["severity"] as? String)?.uppercase() } ?: CoherenceSeverity.AMBIGUITY
            val quotes = (f["quotes"] as? List<*>).orEmpty().mapNotNull { q ->
                val m = q as? Map<*, *> ?: return@mapNotNull null
                val text = (m["text"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CoherenceQuote((m["source"] as? String).orEmpty(), text)
            }
            CoherenceFinding(severity, summary, quotes, (f["suggestion"] as? String)?.takeIf { it.isNotBlank() })
        }
    }

    companion object {
        const val SOURCE_PREAMBLE = "preamble"
        const val SOURCE_INSTRUCTIONS = "instructions"
        const val SOURCE_CONSTITUTION = "constitution"
        const val REPO_FILES_NOT_READ = "repo files not read"

        val SYSTEM_PROMPT = """
            You review the working environment of an engineering agent: the texts it always gets (platform preamble, its role,
            the project constitution, rules) and the skills it may load. Find what would make the agent unsure what to do:
            - CONTRADICTION: two sources demand incompatible things;
            - AMBIGUITY: a demand is unclear or can be read in conflicting ways;
            - REDUNDANCY: the same demand is repeated in a way that may drift apart.
            The preamble states the precedence (constitution, rules, skills, task); a conflict it resolves is still worth a
            finding when the lower source would mislead. Quote the exact words of every source involved. Suggest the
            smallest change that removes the problem. Report nothing when the environment is coherent; never invent quotes.
        """.trimIndent()

        val RESPONSE_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["findings"],"properties":{"findings":{"type":"array","items":
            {"type":"object","additionalProperties":false,"required":["severity","summary","quotes","suggestion"],"properties":{
            "severity":{"type":"string","enum":["CONTRADICTION","AMBIGUITY","REDUNDANCY"]},
            "summary":{"type":"string"},
            "quotes":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["source","text"],
            "properties":{"source":{"type":"string"},"text":{"type":"string"}}}},
            "suggestion":{"type":["string","null"]}}}}}}
        """.trimIndent()
    }
}
