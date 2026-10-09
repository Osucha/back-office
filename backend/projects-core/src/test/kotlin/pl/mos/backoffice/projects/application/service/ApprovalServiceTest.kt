package pl.mos.backoffice.projects.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import pl.mos.backoffice.projects.Fixtures
import pl.mos.backoffice.projects.Fixtures.alice
import pl.mos.backoffice.projects.Fixtures.bob
import pl.mos.backoffice.projects.Fixtures.now
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval.DecisionCommand
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowEngine
import pl.mos.backoffice.projects.application.port.out.WorkflowRunRepository
import pl.mos.backoffice.projects.domain.event.ApprovalDecided
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.ChecklistItem
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowRun
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditResult
import pl.mos.backoffice.shared.security.ProjectFilter
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

class ApprovalServiceTest {

    private val project = Fixtures.project()
    private val definition = Fixtures.issueWorkflow(project)
    private val approvals = mockk<ApprovalRequestRepository>()
    private val runs = mockk<WorkflowRunRepository>()
    private val engine = mockk<WorkflowEngine>(relaxed = true)
    private val events = mutableListOf<DomainEvent>()
    private val audit = RecordingAuditPort()
    private val approve = setOf(ProjectsPermissions.RUN_APPROVE)
    private val checklist = listOf(ChecklistItem("tests-green", "Tests green"), ChecklistItem("docs", "Docs updated", required = false))

    init {
        every { approvals.save(any()) } answers { firstArg() }
        every { runs.save(any()) } answers { firstArg() }
    }

    private fun service(gate: FakeAuthorizationGate) = ApprovalService(approvals, runs, engine, EventPublisher { events += it }, gate, audit, Clock.fixed(now))

    /** A run of [startedBy] waiting at the merge gate with an approval carrying [approvers] and the checklist. */
    private fun waitingApproval(approvers: GateApprovers, startedBy: pl.mos.backoffice.shared.security.Actor? = bob): Pair<WorkflowRun, ApprovalRequest> {
        val id = ApprovalRequestId.new()
        val run = Fixtures.runningRun(project, definition, startedBy = startedBy).waitingForHuman(definition.requireNode("merge-gate"), id, now)
        val approval = ApprovalRequest(
            id = id, runId = run.id, nodeId = "merge-gate", projectId = project.id, projectKey = project.key, workflowName = definition.name,
            issueKey = "SHOP-1", question = "Merge?", createdAt = now, approvers = approvers, checklist = checklist, startedBy = startedBy,
        )
        every { approvals.findById(id) } returns approval
        every { runs.findById(run.id) } returns run
        return run to approval
    }

    private fun gateFor(roles: Set<String> = emptySet(), userId: UserId = FakeAuthorizationGate.TEST_USER_ID, permissions: Set<String> = approve) =
        FakeAuthorizationGate(Fixtures.userWithRoles(project.id, permissions, roles, userId))

    @Test
    fun `an approver with run approve decides, the decision is stamped, audited and resumes the run`() {
        val (run, approval) = waitingApproval(GateApprovers.ANYONE)
        val gate = gateFor()
        val decided = service(gate).decide(approval.id, DecisionCommand("APPROVE", "ship it", checkedItems = listOf("tests-green")))

        assertThat(gate.checked.first()).isEqualTo("run.approve@${project.id}")
        assertThat(decided.status).isEqualTo(ApprovalStatus.DECIDED)
        assertThat(decided.decision!!.option).isEqualTo("approve")
        assertThat(decided.decision!!.decidedBy).isEqualTo(alice)
        assertThat(decided.decision!!.checkedItems).containsExactly("tests-green")
        verify { engine.resumeWithDecision(match { it.id == run.id }, match { it.isApprove }) }
        assertThat(events.filterIsInstance<ApprovalDecided>().single().projectId).isEqualTo(project.id)
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("RUN_GATE_DECIDED")
        assertThat(entry.result).isEqualTo(AuditResult.SUCCESS)
        assertThat(entry.projectId).isEqualTo(project.id.value)
        assertThat(entry.details["option"]).isEqualTo("approve")
        assertThat(entry.details["checkedItems"]).isEqualTo(listOf("tests-green"))
        assertThat(entry.details["commentLength"]).isEqualTo(7)
    }

    @Test
    fun `without run approve on the project the decision is refused`() {
        val (_, approval) = waitingApproval(GateApprovers.ANYONE)
        assertThatThrownBy { service(gateFor(permissions = setOf(ProjectsPermissions.PROJECT_VIEW))).decide(approval.id, DecisionCommand("reject")) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) {
                assertThat(it.permission).isEqualTo("run.approve")
                assertThat(it.projectId).isEqualTo(project.id.value)
            }
        verify(exactly = 0) { engine.resumeWithDecision(any(), any()) }
    }

    @Test
    fun `project roles restrict who decides, platform-wide access bypasses the role rule`() {
        val rule = GateApprovers(projectRoles = listOf("owner", "approver"), accountable = "owner")
        val (_, approval) = waitingApproval(rule)
        assertThatThrownBy { service(gateFor(roles = setOf("developer"))).decide(approval.id, DecisionCommand("reject")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("owner, approver")
        assertThat(audit.entries.single().result).isEqualTo(AuditResult.DENIED)
        assertThat(service(gateFor(roles = setOf("approver"))).decide(approval.id, DecisionCommand("reject")).status).isEqualTo(ApprovalStatus.DECIDED)

        val (_, other) = waitingApproval(rule)
        val admin = FakeAuthorizationGate.permitAll()
        assertThat(service(admin).decide(other.id, DecisionCommand("reject")).status).isEqualTo(ApprovalStatus.DECIDED)
    }

    @Test
    fun `explicit users restrict who decides`() {
        val (_, approval) = waitingApproval(GateApprovers(users = listOf(UUID.fromString("00000000-0000-0000-0000-00000000c0de"))))
        assertThatThrownBy { service(gateFor()).decide(approval.id, DecisionCommand("reject")) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("named approvers")
        val named = gateFor(userId = UserId.of("00000000-0000-0000-0000-00000000c0de"))
        assertThat(service(named).decide(approval.id, DecisionCommand("reject")).decision?.decidedBy?.userId).isEqualTo(UUID.fromString("00000000-0000-0000-0000-00000000c0de"))
    }

    @Test
    fun `the user who started the run cannot decide a four-eyes gate`() {
        val (_, own) = waitingApproval(GateApprovers(forbidSelfApproval = true), startedBy = alice)
        assertThatThrownBy { service(gateFor()).decide(own.id, DecisionCommand("approve", checkedItems = listOf("tests-green"))) }
            .isInstanceOf(InvalidStateException::class.java).hasMessageContaining("started the run")
        val (_, foreign) = waitingApproval(GateApprovers(forbidSelfApproval = true), startedBy = bob)
        assertThat(service(gateFor()).decide(foreign.id, DecisionCommand("approve", checkedItems = listOf("tests-green"))).status).isEqualTo(ApprovalStatus.DECIDED)
        val (_, selfAllowed) = waitingApproval(GateApprovers(forbidSelfApproval = false), startedBy = alice)
        assertThat(service(gateFor()).decide(selfAllowed.id, DecisionCommand("approve", checkedItems = listOf("tests-green"))).status).isEqualTo(ApprovalStatus.DECIDED)
    }

    @Test
    fun `approve needs every required checklist item, other options do not, unknown items are rejected`() {
        val (_, approval) = waitingApproval(GateApprovers.ANYONE)
        val service = service(gateFor())
        assertThatThrownBy { service.decide(approval.id, DecisionCommand("approve", checkedItems = listOf("docs"))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("tests-green")
        assertThatThrownBy { service.decide(approval.id, DecisionCommand("revise", checkedItems = listOf("nope"))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("unknown checklist items nope")
        assertThat(service.decide(approval.id, DecisionCommand("revise", "more tests")).decision?.option).isEqualTo("revise")
    }

    @Test
    fun `the policy explains why a caller cannot decide`() {
        val (_, approval) = waitingApproval(GateApprovers(projectRoles = listOf("owner"), forbidSelfApproval = true), startedBy = alice)
        assertThat(GateApprovalPolicy.check(approval, gateFor(roles = setOf("owner")).user).reason).contains("started the run")
        assertThat(GateApprovalPolicy.check(approval, gateFor(roles = setOf("viewer")).user).reason).contains("only project roles owner")
        assertThat(GateApprovalPolicy.check(approval, gateFor(permissions = emptySet()).user).missingPermission).isTrue()
        assertThat(GateApprovalPolicy.check(approval.copy(startedBy = bob), gateFor(roles = setOf("owner")).user).allowed).isTrue()
        assertThat(GateApprovalPolicy.check(approval.copy(status = ApprovalStatus.DECIDED, startedBy = bob), gateFor(roles = setOf("owner")).user).allowed).isFalse()
    }

    @Test
    fun `the inbox only lists approvals of visible projects and get needs project view`() {
        val (_, approval) = waitingApproval(GateApprovers.ANYONE)
        val viewer = FakeAuthorizationGate.permitting(ProjectsPermissions.PROJECT_VIEW, projects = setOf(project.id.value))
        every { approvals.findByStatus(ApprovalStatus.OPEN, ProjectFilter.Only(setOf(project.id.value)), null) } returns listOf(approval)
        assertThat(service(viewer).list(ApprovalStatus.OPEN)).containsExactly(approval)
        assertThat(service(viewer).get(approval.id)).isEqualTo(approval)
        assertThatThrownBy { service(FakeAuthorizationGate.denyAll()).get(approval.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        every { approvals.findByStatus(ApprovalStatus.OPEN, ProjectFilter.Only(emptySet()), null) } returns emptyList()
        assertThat(service(FakeAuthorizationGate.denyAll()).list(ApprovalStatus.OPEN)).isEmpty()
    }
}
