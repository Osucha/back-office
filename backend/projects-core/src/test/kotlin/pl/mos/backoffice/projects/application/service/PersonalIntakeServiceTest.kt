package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.ConnectionFakes
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.RuleCommand
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.out.IssueTrackerUnavailableException
import pl.mos.backoffice.projects.application.port.out.LegacyConnectionSource
import pl.mos.backoffice.projects.application.port.out.PersonalIntakeRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.domain.event.IntakeProposalsFound
import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.IssueHeadline
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.ConnectionSelector
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** ADR 0038: personal intake rules read with the owner's own connection, propose each new issue once, and start runs as the owner. */
class PersonalIntakeServiceTest {

    private var now: Instant = Fixtures.now
    private val clock = object : Clock {
        override fun now(): Instant = now
    }
    private val me = FakeAuthorizationGate.TEST_USER_ID.value
    private val project = Fixtures.project().copy(personal = true)
    private val workflow = Fixtures.issueWorkflow(project)
    private val projects = mockk<ProjectRepository> { every { findById(project.id) } returns project }
    private val workflows = mockk<WorkflowDefinitionRepository> { every { findById(workflow.id) } returns workflow; every { findById(neq(workflow.id)) } returns null }
    private val connections = ConnectionFakes.InMemoryConnections()
    private val mine = connections.save(ConnectionFakes.personal("My Jira", ConnectionFakes.READ, me))
    private val someoneElses = connections.save(ConnectionFakes.personal("Their Jira", ConnectionFakes.READ, UUID.randomUUID()))
    private val shared = connections.save(ConnectionFakes.connection("Team reader", ConnectionFakes.READ))
    private val trackers = ConnectionFakes.FakeTrackers()
    private val access = JiraConnectionAccess(connections, LegacyConnectionSource.NONE, ConnectionSelector(), trackers)
    private val starts = mutableListOf<RunInput>()
    private val startRun = mockk<StartRun> {
        every { start(any(), any(), TriggerSource.MANUAL) } answers {
            starts += secondArg<RunInput>()
            Fixtures.runningRun(project, workflow).copy(id = WorkflowRunId.new())
        }
    }
    private val repo = InMemoryIntake()
    private val queued = mutableListOf<WorkRequest>()
    private val events = mutableListOf<DomainEvent>()
    private val audit = RecordingAuditPort()
    private val gate = FakeAuthorizationGate(
        FakeAuthorizationGate.user(
            platform = setOf(ProjectsPermissions.CONNECTIONS_PERSONAL),
            projects = mapOf(project.id.value to setOf(ProjectsPermissions.RUN_START, ProjectsPermissions.PROJECT_VIEW)),
        ),
    )
    private val service = PersonalIntakeService(repo, connections, workflows, projects, startRun, access, { queued += it }, { events += it }, gate, audit, clock)

    private val command = RuleCommand("My issues", mine.id, "assignee = currentUser() AND statusCategory != Done", 5, workflow.id)

    private fun issue(key: String) = IssueHeadline(key, "Summary of $key", "Story", "To Do", "Medium")

    @Test
    fun `a rule needs the caller's own personal connection with ISSUE_READ and a workflow they may start`() {
        assertThatThrownBy { service.create(command.copy(connectionId = someoneElses.id)) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.errors.single().field).isEqualTo("connectionId") }
        assertThatThrownBy { service.create(command.copy(connectionId = shared.id)) }.isInstanceOf(ValidationException::class.java)
        val other = Fixtures.issueWorkflow(Fixtures.project("OTHER"))
        every { workflows.findById(other.id) } returns other
        assertThatThrownBy { service.create(command.copy(workflowId = other.id)) }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.errors.single().field).isEqualTo("workflowId") }

        val created = service.create(command)
        assertThat(created.rule.ownerUserId).isEqualTo(me)
        assertThat(created.connectionName).isEqualTo("My Jira")
        assertThat(created.projectKey).isEqualTo(project.key)
        assertThat(queued.single().dedupeKey).isEqualTo("PERSONAL_INTAKE_POLL:${created.rule.id}")
        assertThat(audit.operations()).containsExactly("INTAKE_RULE_CREATE")
    }

    @Test
    fun `every issue that appears in the results is proposed once, and only the owner hears about it`() {
        val rule = service.create(command).rule
        trackers.headlinesResult = listOf(issue("SHOP-1"), issue("SHOP-2"))

        assertThat(service.poll(rule.id)).isEqualTo(2)
        assertThat(trackers.opened.last().id).isEqualTo(mine.id)
        assertThat(trackers.headlinesJql.single()).isEqualTo("assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC")
        val found = events.single() as IntakeProposalsFound
        assertThat(found.ownerUserId).isEqualTo(me)
        assertThat(found.payload["ownerUserId"]).isEqualTo(me.toString())
        assertThat(found.issueKeys).containsExactly("SHOP-1", "SHOP-2")

        // SHOP-1 again (e.g. after a status change) is not proposed twice; SHOP-3 is new
        now = now.plus(Duration.ofMinutes(5))
        trackers.headlinesResult = listOf(issue("SHOP-1"), issue("SHOP-3"))
        assertThat(service.poll(rule.id)).isEqualTo(1)
        assertThat(service.proposals().map { it.proposal.issueKey }).containsExactlyInAnyOrder("SHOP-1", "SHOP-2", "SHOP-3")
        assertThat(service.proposals().first().issueUrl).startsWith("https://acme.atlassian.net/browse/")
        assertThat(service.rules().single().newProposals).isEqualTo(3)
        assertThat(repo.findRule(rule.id)!!.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(5)))
    }

    @Test
    fun `without includeExisting the first poll only remembers what already matches`() {
        val rule = service.create(command.copy(includeExisting = false)).rule
        trackers.headlinesResult = listOf(issue("SHOP-1"))
        assertThat(service.poll(rule.id)).isZero()
        assertThat(events).isEmpty()
        assertThat(service.proposals()).isEmpty()

        trackers.headlinesResult = listOf(issue("SHOP-1"), issue("SHOP-2"))
        assertThat(service.poll(rule.id)).isEqualTo(1)
        assertThat(service.proposals().single().proposal.issueKey).isEqualTo("SHOP-2")
    }

    @Test
    fun `starting a proposal is the owner's own run of the rule's workflow, dismiss and restore change only the state`() {
        val rule = service.create(command).rule
        trackers.headlinesResult = listOf(issue("SHOP-1"), issue("SHOP-2"))
        service.poll(rule.id)
        val (first, second) = service.proposals().sortedBy { it.proposal.issueKey }.map { it.proposal.id }

        val started = service.start(first)
        assertThat(started.proposal.state).isEqualTo(ProposalState.STARTED)
        assertThat(started.proposal.runId).isNotNull
        assertThat(starts.single().issueKey).isEqualTo("SHOP-1")
        assertThat(starts.single().parameters).containsEntry(PersonalIntakeService.PROPOSAL_PARAMETER, first.toString())
        assertThatThrownBy { service.start(first) }.isInstanceOf(InvalidStateException::class.java)

        assertThat(service.dismiss(second).proposal.state).isEqualTo(ProposalState.DISMISSED)
        assertThat(service.proposals(ProposalState.DISMISSED).map { it.proposal.issueKey }).containsExactly("SHOP-2")
        assertThat(service.restore(second).proposal.state).isEqualTo(ProposalState.NEW)
        assertThat(service.proposals(ProposalState.STARTED).single().workflowName).isEqualTo(workflow.name)
        assertThat(audit.operations()).contains("INTAKE_PROPOSAL_START", "INTAKE_PROPOSAL_DISMISS", "INTAKE_PROPOSAL_RESTORE")
    }

    @Test
    fun `rules and proposals of another person do not exist for the caller`() {
        val rule = service.create(command).rule
        trackers.headlinesResult = listOf(issue("SHOP-1"))
        service.poll(rule.id)
        val proposal = service.proposals().single().proposal.id
        val stranger = PersonalIntakeService(
            repo, connections, workflows, projects, startRun, access, { }, { }, FakeAuthorizationGate.permitting(ProjectsPermissions.CONNECTIONS_PERSONAL).also {
                it.user = it.user.copy(userId = pl.mos.backoffice.shared.security.UserId(UUID.randomUUID()))
            },
            audit, clock,
        )
        assertThat(stranger.rules()).isEmpty()
        assertThat(stranger.proposals()).isEmpty()
        assertThatThrownBy { stranger.start(proposal) }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy { stranger.delete(rule.id) }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `errors back off, a foreign or deleted connection stops the rule with a reason`() {
        val rule = service.create(command).rule
        trackers.headlinesFailure = IssueTrackerUnavailableException(429, Duration.ofMinutes(30), "slow down")
        assertThat(service.poll(rule.id)).isZero()
        val failed = repo.findRule(rule.id)!!
        assertThat(failed.consecutiveFailures).isEqualTo(1)
        assertThat(failed.nextPollAt).isEqualTo(now.plus(Duration.ofMinutes(30)))
        assertThat(failed.lastError).contains("429")

        repo.saveRule(failed.copy(connectionId = someoneElses.id))
        trackers.headlinesFailure = null
        service.poll(rule.id)
        assertThat(repo.findRule(rule.id)!!.lastError).contains("not one of your personal connections")
    }

    /** Rules and proposals in memory, unique per rule and issue key like the table. */
    private class InMemoryIntake : PersonalIntakeRepository {
        private val rules = linkedMapOf<IntakeRuleId, PersonalIntakeRule>()
        private val proposals = linkedMapOf<IntakeProposalId, IntakeProposal>()
        override fun saveRule(rule: PersonalIntakeRule) = rule.also { rules[it.id] = it }
        override fun findRule(id: IntakeRuleId) = rules[id]
        override fun rulesOf(ownerUserId: UUID) = rules.values.filter { it.ownerUserId == ownerUserId }
        override fun deleteRule(id: IntakeRuleId) {
            rules.remove(id)
            proposals.values.removeIf { it.ruleId == id }
        }
        override fun dueRules(now: Instant) = rules.values.filter { it.due(now) }
        override fun knownKeys(ruleId: IntakeRuleId) = proposals.values.filter { it.ruleId == ruleId }.map { it.issueKey }.toSet()
        override fun insertProposalIfAbsent(proposal: IntakeProposal): Boolean {
            if (proposals.values.any { it.ruleId == proposal.ruleId && it.issueKey == proposal.issueKey }) return false
            proposals[proposal.id] = proposal
            return true
        }
        override fun findProposal(id: IntakeProposalId) = proposals[id]
        override fun saveProposal(proposal: IntakeProposal) = proposal.also { proposals[it.id] = it }
        override fun proposalsOf(ownerUserId: UUID, state: ProposalState, limit: Int) =
            proposals.values.filter { it.ownerUserId == ownerUserId && it.state == state }.sortedByDescending { it.detectedAt }.take(limit)
        override fun newCounts(ownerUserId: UUID) =
            proposals.values.filter { it.ownerUserId == ownerUserId && it.state == ProposalState.NEW }.groupingBy { it.ruleId }.eachCount()
    }
}
