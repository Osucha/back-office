package pl.mos.backoffice.projects.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import pl.mos.backoffice.shared.runner.AgentAccess
import pl.mos.backoffice.shared.runner.AgentHarness
import pl.mos.backoffice.shared.tasks.AgentProfile
import pl.mos.backoffice.shared.tasks.AgentSnapshot
import pl.mos.backoffice.shared.tasks.RequiredSkill
import pl.mos.backoffice.shared.tasks.RuleSnapshot
import pl.mos.backoffice.shared.tasks.SkillInfo
import pl.mos.backoffice.shared.tasks.StepNeeds
import java.util.UUID

/** ADR 0061 decision 3: the deterministic agent audit of a workflow draft. */
class AgentAuditorTest {

    private val project = UUID.randomUUID()
    private val reviewerId = UUID.randomUUID()

    private val reviewer = AgentProfile(
        id = reviewerId, agent = AgentSnapshot("reviewer", "Reviewer", rules = listOf("ground"), skills = listOf("security")),
        projectId = null, builtIn = true,
        rules = listOf(RuleSnapshot("house", "House style", "x", automatic = true), RuleSnapshot("ground", "Ground rules", "y")),
        skills = listOf(SkillInfo("security", "Security checklist", "OWASP")),
    )
    private val coder = AgentProfile(
        id = UUID.randomUUID(), agent = AgentSnapshot("coder", "Coder", AgentHarness.CLAUDE_CODE), projectId = project, builtIn = false,
        rules = emptyList(), skills = listOf(SkillInfo("fmt", "Formatting", ""), SkillInfo("fmt-2", " formatting ", "")),
        unknownRules = listOf("gone-rule"), unknownSkills = listOf("gone-skill"),
    )
    private val reader = AgentProfile(
        id = UUID.randomUUID(), agent = AgentSnapshot("reader", "Reader", AgentHarness.OPENCODE, access = AgentAccess.READ_ONLY), projectId = null,
        builtIn = false, rules = emptyList(), skills = emptyList(),
    )

    private fun step(id: String, agent: String, vararg skills: RequiredSkill, coding: Boolean = false) =
        AuditedStep(id, StepNeeds(agent, skills.toList(), coding))

    @Test
    fun `an agent's entry lists its environment, its steps, the required skills by step and the missing ones`() {
        val report = AgentAuditor.audit(
            listOf(
                step("review", "reviewer", RequiredSkill("security", "Security checklist"), RequiredSkill("spec", "Spec review")),
                step("review-2", "reviewer", RequiredSkill("spec", null), RequiredSkill("ghost", null)),
            ),
            mapOf("reviewer" to reviewer),
        )

        assertThat(report.findings).isEmpty()
        val entry = report.agents.single()
        assertThat(entry.agent.key).isEqualTo("reviewer")
        assertThat(entry.agentId).isEqualTo(reviewerId)
        assertThat(entry.projectId).isNull()
        assertThat(entry.nodeIds).containsExactly("review", "review-2")
        assertThat(entry.rules).containsExactly(AuditRule("house", "House style", true), AuditRule("ground", "Ground rules", false))
        assertThat(entry.skills).containsExactly(AuditSkill("security", "Security checklist"))
        assertThat(entry.requiredSkills).containsExactly(
            AuditRequiredSkill("security", "Security checklist", listOf("review")),
            AuditRequiredSkill("spec", "Spec review", listOf("review", "review-2")),
            AuditRequiredSkill("ghost", null, listOf("review-2")),
        )
        assertThat(entry.missingSkills).containsExactly("spec", "ghost")
        assertThat(entry.findings.map { Triple(it.kind, it.severity, it.key) }).containsExactly(
            Triple(AgentAuditFindingKind.MISSING_SKILL, AgentAuditSeverity.WARNING, "spec"),
            Triple(AgentAuditFindingKind.MISSING_SKILL, AgentAuditSeverity.WARNING, "ghost"),
        )
        assertThat(entry.findings.first().message).contains("Reviewer lacks skill Spec review (required by 2 steps)")
        assertThat(entry.findings.first().nodeIds).containsExactly("review", "review-2")
    }

    @Test
    fun `a model answer on coding work is an error, a read-only coding agent is fine (ADR 0063 - agents never push)`() {
        val report = AgentAuditor.audit(
            listOf(
                step("plan", "reviewer", coding = true),
                step("answer", "reviewer"),
                step("look", "reader", coding = true),
                step("look-only", "reader", coding = true),
            ),
            mapOf("reviewer" to reviewer, "reader" to reader),
        )

        val byAgent = report.agents.associateBy { it.agent.key }
        val coding = byAgent.getValue("reviewer").findings.single()
        assertThat(coding.kind).isEqualTo(AgentAuditFindingKind.NEEDS_CODING_AGENT)
        assertThat(coding.severity).isEqualTo(AgentAuditSeverity.ERROR)
        assertThat(coding.nodeIds).containsExactly("plan")
        assertThat(coding.message).contains("a coding agent is needed by 1 step")
        assertThat(byAgent.getValue("reader").findings).isEmpty()
    }

    @Test
    fun `rules and skills the library lost and two skills with one name are reported, unknown agents outside the entries`() {
        val report = AgentAuditor.audit(
            listOf(step("code", "coder", coding = true), step("x", "ghost"), step("y", "ghost")),
            mapOf("coder" to coder, "ghost" to null),
        )

        val entry = report.agents.single()
        assertThat(entry.projectId).isEqualTo(project)
        assertThat(entry.findings.map { it.kind to it.key }).containsExactly(
            AgentAuditFindingKind.UNKNOWN_RULE to "gone-rule",
            AgentAuditFindingKind.UNKNOWN_SKILL to "gone-skill",
            AgentAuditFindingKind.DUPLICATE_SKILL_NAME to "fmt",
        )
        assertThat(entry.findings.last().message).contains("2 skills named \"Formatting\" (fmt, fmt-2)")
        assertThat(entry.findings.last().severity).isEqualTo(AgentAuditSeverity.WARNING)
        val unknown = report.findings.single()
        assertThat(unknown.kind).isEqualTo(AgentAuditFindingKind.UNKNOWN_AGENT)
        assertThat(unknown.key).isEqualTo("ghost")
        assertThat(unknown.nodeIds).containsExactly("x", "y")
        assertThat(unknown.message).contains("for 2 steps")
    }

    @Test
    fun `no steps, no entries`() {
        val report = AgentAuditor.audit(emptyList(), emptyMap())
        assertThat(report.agents).isEmpty()
        assertThat(report.findings).isEmpty()
    }

    @Test
    fun `ADR 0064 - a coding agent's connector found neither in the project nor on the platform is a warning, model answers have no tools`() {
        val tooled = coder.copy(agent = coder.agent.copy(connectors = listOf("gitlab-read", "jira", "docs")), unknownRules = emptyList(), unknownSkills = emptyList(), skills = emptyList())
        val answering = reviewer.copy(agent = reviewer.agent.copy(connectors = listOf("jira")))
        val report = AgentAuditor.audit(
            listOf(step("code", "coder", coding = true), step("review", "reviewer")),
            mapOf("coder" to tooled, "reviewer" to answering),
            knownConnectors = setOf("gitlab-read"),
        )
        val byAgent = report.agents.associateBy { it.agent.key }
        val findings = byAgent.getValue("coder").findings
        assertThat(findings.map { it.kind to it.key }).containsExactly(
            AgentAuditFindingKind.UNKNOWN_CONNECTOR to "jira",
            AgentAuditFindingKind.UNKNOWN_CONNECTOR to "docs",
        )
        assertThat(findings.first().severity).isEqualTo(AgentAuditSeverity.WARNING)
        assertThat(findings.first().nodeIds).containsExactly("code")
        assertThat(findings.first().message).contains("jira").contains("Coder")
        assertThat(byAgent.getValue("reviewer").findings).isEmpty()
        assertThat(AgentAuditor.audit(listOf(step("code", "coder", coding = true)), mapOf("coder" to tooled)).agents.single().findings)
            .describedAs("not checked without the known connectors").isEmpty()
    }
}
