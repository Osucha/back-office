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
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval.AssignCommand
import pl.mos.backoffice.projects.application.port.out.ApprovalRequestRepository
import pl.mos.backoffice.projects.domain.event.ApprovalAssigned
import pl.mos.backoffice.projects.domain.model.ApprovalRequest
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.projects.domain.model.Decision
import pl.mos.backoffice.projects.domain.model.GateApprovers
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.WorkflowRunId
import pl.mos.backoffice.projects.domain.service.GateApprovalPolicy
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainEvent
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.InsufficientPermissionException
import pl.mos.backoffice.shared.InvalidStateException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.PrincipalKind
import pl.mos.backoffice.shared.security.ProjectAccessDirectory
import pl.mos.backoffice.shared.security.UserId
import pl.mos.backoffice.shared.testing.FakeAuthorizationGate
import pl.mos.backoffice.shared.testing.RecordingAuditPort
import java.util.UUID

/** ADR 0028: owners and maintainers (`run.assign`) decide who decides an open gate. */
class ApprovalAssignmentServiceTest {

    private val project = Fixtures.project()
    private val projectId = project.id.value
    private val approvals = mockk<ApprovalRequestRepository>()
    private val events = mutableListOf<DomainEvent>()
    private val audit = RecordingAuditPort()
    private val people = mutableMapOf<UUID, CurrentUser>()
    private val directory = object : ProjectAccessDirectory {
        override fun members(projectId: UUID) = people.values.filter { projectId in it.projectPermissions }.sortedBy { it.displayName }
        override fun user(userId: UUID) = people[userId]
        override fun displayNames(userIds: Collection<UUID>) = userIds.mapNotNull { id -> people[id]?.let { id to it.displayName } }.toMap()
    }

    private val ann = person("Ann", setOf(ProjectsPermissions.RUN_APPROVE), setOf("approver"))
    private val dev = person("Dev", setOf(ProjectsPermissions.RUN_APPROVE), setOf("developer"))
    private val viewer = person("Vic", setOf(ProjectsPermissions.PROJECT_VIEW), setOf("approver"))
    private val starter = person("Bob", setOf(ProjectsPermissions.RUN_APPROVE), setOf("approver"), bob.userId!!)

    init {
        every { approvals.save(any()) } answers { firstArg() }
    }

    private fun person(name: String, permissions: Set<String>, roles: Set<String>, id: UUID = UUID.randomUUID()) =
        CurrentUser(PrincipalKind.USER, UserId(id), "$name@example.com", name, emptySet(), mapOf(projectId to permissions), projectRoles = mapOf(projectId to roles))
            .also { people[id] = it }

    private fun service(gate: FakeAuthorizationGate) = ApprovalAssignmentService(approvals, directory, EventPublisher { events += it }, gate, audit, Clock.fixed(now))

    private fun manager() = FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_ASSIGN, projects = setOf(projectId))

    private fun openApproval(approvers: GateApprovers = GateApprovers(projectRoles = listOf("approver"), forbidSelfApproval = true), status: ApprovalStatus = ApprovalStatus.OPEN): ApprovalRequest {
        val approval = ApprovalRequest(
            id = ApprovalRequestId.new(), runId = WorkflowRunId.new(), nodeId = "merge-gate", projectId = project.id, projectKey = project.key,
            workflowName = "wf", issueKey = "SHOP-1", question = "Merge?", createdAt = now, approvers = approvers, startedBy = bob, status = status,
        )
        every { approvals.findById(approval.id) } returns approval
        return approval
    }

    private fun id(user: CurrentUser) = user.userId!!.value

    @Test
    fun `a manager assigns an open approval, the gate is checked first, the change is audited and published`() {
        val approval = openApproval(GateApprovers(projectRoles = listOf("approver"), users = listOf(id(starter))))
        val gate = manager()

        val assigned = service(gate).assign(approval.id, AssignCommand(listOf(id(ann), id(ann)), "Ann takes it"))

        assertThat(gate.checked.first()).isEqualTo("run.assign@$projectId")
        assertThat(assigned.assignees).containsExactly(id(ann))
        assertThat(assigned.assignment!!.assignedBy).isEqualTo(alice)
        assertThat(assigned.assignment!!.comment).isEqualTo("Ann takes it")
        verify { approvals.save(match { it.assignment?.userIds == listOf(id(ann)) }) }
        val entry = audit.entries.single()
        assertThat(entry.operation).isEqualTo("APPROVAL_ASSIGN")
        assertThat(entry.projectId).isEqualTo(projectId)
        assertThat(entry.details["old"]).isEqualTo(listOf(id(starter).toString()))
        assertThat(entry.details["new"]).isEqualTo(listOf(id(ann).toString()))
        assertThat(entry.details["overridden"]).isEqualTo(false)
        val event = events.filterIsInstance<ApprovalAssigned>().single()
        assertThat(event.payload["assignees"]).isEqualTo(listOf(id(ann).toString()))
        assertThat(event.projectId).isEqualTo(project.id)
    }

    @Test
    fun `without run assign the assignment is refused before anything else`() {
        val approval = openApproval()
        val approver = FakeAuthorizationGate.permitting(ProjectsPermissions.RUN_APPROVE, ProjectsPermissions.PROJECT_VIEW, projects = setOf(projectId))
        assertThatThrownBy { service(approver).assign(approval.id, AssignCommand(listOf(id(ann)))) }
            .isInstanceOfSatisfying(InsufficientPermissionException::class.java) { assertThat(it.permission).isEqualTo("run.assign") }
        assertThatThrownBy { service(approver).candidates(approval.id) }.isInstanceOf(InsufficientPermissionException::class.java)
        verify(exactly = 0) { approvals.save(any()) }
        assertThat(audit.entries).isEmpty()
        assertThat(events).isEmpty()
    }

    @Test
    fun `assignees must hold run approve, satisfy the roles rule and the four-eyes rule`() {
        val approval = openApproval()
        val service = service(manager())
        assertThatThrownBy { service.assign(approval.id, AssignCommand(listOf(id(viewer)))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("run.approve")
        assertThatThrownBy { service.assign(approval.id, AssignCommand(listOf(id(dev)))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("only project roles approver")
        assertThatThrownBy { service.assign(approval.id, AssignCommand(listOf(id(ann), id(starter)))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("four-eyes")
        assertThatThrownBy { service.assign(approval.id, AssignCommand(listOf(UUID.randomUUID()))) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("not active")
        assertThatThrownBy { service.assign(approval.id, AssignCommand((1..21).map { id(person("P$it", setOf(ProjectsPermissions.RUN_APPROVE), setOf("approver"))) })) }
            .isInstanceOf(ValidationException::class.java).hasMessageContaining("at most 20")
        verify(exactly = 0) { approvals.save(any()) }
    }

    @Test
    fun `only open approvals can be assigned, an empty list clears the assignment`() {
        val decided = openApproval(status = ApprovalStatus.DECIDED)
        assertThatThrownBy { service(manager()).assign(decided.id, AssignCommand(listOf(id(ann)))) }.isInstanceOf(InvalidStateException::class.java)

        val approval = openApproval().assign(listOf(id(ann)), alice, now)
        every { approvals.findById(approval.id) } returns approval
        val cleared = service(manager()).assign(approval.id, AssignCommand(emptyList()))
        assertThat(cleared.assignment).isNull()
        assertThat(cleared.assignees).isEmpty()
        assertThat(audit.entries.single().details["cleared"]).isEqualTo(true)
        assertThat(audit.entries.single().details["overridden"]).isEqualTo(true)
        assertThat(events.filterIsInstance<ApprovalAssigned>().single().assignees).isEmpty()
    }

    @Test
    fun `candidates are the project members who could decide, marked when already assigned`() {
        val approval = openApproval(GateApprovers(projectRoles = listOf("approver"), users = listOf(id(ann)), forbidSelfApproval = true))
        val candidates = service(manager()).candidates(approval.id)
        assertThat(candidates.map { it.displayName }).containsExactly("Ann")
        assertThat(candidates.single().assigned).isTrue()
        assertThat(candidates.single().projectRoles).containsExactly("approver")
    }

    @Test
    fun `the policy lets an assignee decide instead of the definition's named users, roles and four-eyes still apply`() {
        val approval = openApproval(GateApprovers(projectRoles = listOf("approver"), users = listOf(id(dev)), forbidSelfApproval = true))
        assertThat(GateApprovalPolicy.check(approval, ann).reason).contains("named approvers")
        val assigned = approval.assign(listOf(id(ann)), alice, now)
        assertThat(GateApprovalPolicy.check(assigned, ann).allowed).isTrue()
        val other = person("Otto", setOf(ProjectsPermissions.RUN_APPROVE), setOf("approver"))
        assertThat(GateApprovalPolicy.check(assigned, other).reason).contains("assigned to other users")
        assertThat(GateApprovalPolicy.check(approval.assign(listOf(id(starter)), alice, now), starter).reason).contains("four-eyes")
        assertThat(GateApprovalPolicy.canBeAssigned(approval, ann).allowed).isTrue()
        assertThat(GateApprovalPolicy.canBeAssigned(approval, dev).allowed).isFalse()
        assertThat(GateApprovalPolicy.canBeAssigned(approval, viewer).missingPermission).isTrue()
        assertThatThrownBy { approval.decide(Decision("approve", decidedAt = now)).assign(listOf(id(ann)), alice, now) }
            .isInstanceOf(InvalidStateException::class.java)
    }
}
