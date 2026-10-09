package pl.mos.backoffice.projects.application.service

import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.ProposalView
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.RuleCommand
import pl.mos.backoffice.projects.application.port.`in`.ManagePersonalIntake.RuleView
import pl.mos.backoffice.projects.application.port.`in`.PollPersonalIntake
import pl.mos.backoffice.projects.application.port.`in`.StartRun
import pl.mos.backoffice.projects.application.port.out.ConnectionRepository
import pl.mos.backoffice.projects.application.port.out.IssueTrackerUnavailableException
import pl.mos.backoffice.projects.application.port.out.PersonalIntakeRepository
import pl.mos.backoffice.projects.application.port.out.ProjectRepository
import pl.mos.backoffice.projects.application.port.out.WorkflowDefinitionRepository
import pl.mos.backoffice.projects.application.service.tasks.JiraConnectionAccess
import pl.mos.backoffice.projects.domain.event.IntakeProposalsFound
import pl.mos.backoffice.projects.domain.model.ConnectionCapability
import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProjectsPermissions
import pl.mos.backoffice.projects.domain.model.ProposalState
import pl.mos.backoffice.projects.domain.model.RunInput
import pl.mos.backoffice.projects.domain.model.TriggerSource
import pl.mos.backoffice.projects.domain.model.WorkflowDefinitionId
import pl.mos.backoffice.shared.Clock
import pl.mos.backoffice.shared.DomainException
import pl.mos.backoffice.shared.EventPublisher
import pl.mos.backoffice.shared.NotFoundException
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.audit.AuditEntry
import pl.mos.backoffice.shared.audit.AuditPort
import pl.mos.backoffice.shared.audit.AuditableChanges
import pl.mos.backoffice.shared.platform.WorkKinds
import pl.mos.backoffice.shared.platform.WorkQueue
import pl.mos.backoffice.shared.platform.WorkRequest
import pl.mos.backoffice.shared.security.AuthorizationGate
import pl.mos.backoffice.shared.security.CurrentUser
import pl.mos.backoffice.shared.security.SystemOperation
import java.util.UUID

/**
 * Implements [ManagePersonalIntake] and [PollPersonalIntake] (ADR 0038). A user sees and changes only their own rules
 * and proposals (a foreign id answers 404). A poll reads the rule's JQL through the owner's own personal connection
 * (narrowed to ISSUE_READ) and creates one proposal per issue key the rule has not seen; the first poll proposes the
 * issues already matching only with `includeExisting`. Starting a proposal is an ordinary run start by its owner.
 */
class PersonalIntakeService(
    private val intake: PersonalIntakeRepository,
    private val connections: ConnectionRepository,
    private val workflows: WorkflowDefinitionRepository,
    private val projects: ProjectRepository,
    private val startRun: StartRun,
    private val jira: JiraConnectionAccess,
    private val queue: WorkQueue,
    private val events: EventPublisher,
    private val gate: AuthorizationGate,
    private val audit: AuditPort,
    private val clock: Clock,
) : ManagePersonalIntake, PollPersonalIntake {

    override fun rules(): List<RuleView> {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val counts = intake.newCounts(userId(me))
        return intake.rulesOf(userId(me)).sortedBy { it.name.lowercase() }.map { view(it, counts[it.id] ?: 0) }
    }

    override fun create(command: RuleCommand): RuleView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val owner = userId(me)
        if (intake.rulesOf(owner).size >= PersonalIntakeRule.MAX_RULES_PER_USER) {
            throw ValidationException("at most ${PersonalIntakeRule.MAX_RULES_PER_USER} rules per person", "name")
        }
        requireUsable(me, command)
        val now = clock.now()
        val saved = intake.saveRule(
            PersonalIntakeRule(
                id = IntakeRuleId.new(), ownerUserId = owner, ownerName = me.displayName, name = command.name.trim(),
                connectionId = command.connectionId, jql = command.jql.trim(), pollIntervalMinutes = command.pollIntervalMinutes,
                workflowId = command.workflowId, enabled = command.enabled, includeExisting = command.includeExisting,
                nextPollAt = if (command.enabled) now else null, createdAt = now, updatedAt = now,
            ),
        )
        audit.record(AuditEntry("INTAKE_RULE_CREATE", RULE, saved.id.toString(), details = mapOf("name" to saved.name, "workflowId" to saved.workflowId.toString(), "enabled" to saved.enabled)))
        if (saved.enabled) enqueue(saved.id)
        return view(saved, 0)
    }

    override fun update(id: IntakeRuleId, command: RuleCommand): RuleView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val existing = ownRule(me, id)
        requireUsable(me, command)
        val saved = intake.saveRule(
            existing.configure(command.name, command.connectionId, command.jql, command.pollIntervalMinutes, command.workflowId, command.enabled, command.includeExisting, clock.now()),
        )
        val changes = AuditableChanges.changed(
            "name" to (existing.name to saved.name),
            "jql" to (existing.jql to saved.jql),
            "connectionId" to (existing.connectionId.toString() to saved.connectionId.toString()),
            "pollIntervalMinutes" to (existing.pollIntervalMinutes to saved.pollIntervalMinutes),
            "workflowId" to (existing.workflowId.toString() to saved.workflowId.toString()),
            "enabled" to (existing.enabled to saved.enabled),
            "includeExisting" to (existing.includeExisting to saved.includeExisting),
        )
        if (changes.isNotEmpty()) audit.record(AuditEntry("INTAKE_RULE_UPDATE", RULE, id.toString(), details = changes))
        if (saved.enabled && saved.due(clock.now())) enqueue(saved.id)
        return view(saved, intake.newCounts(saved.ownerUserId)[saved.id] ?: 0)
    }

    override fun delete(id: IntakeRuleId) {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val existing = ownRule(me, id)
        intake.deleteRule(id)
        audit.record(AuditEntry("INTAKE_RULE_DELETE", RULE, id.toString(), details = mapOf("name" to existing.name)))
    }

    override fun pollNow(id: IntakeRuleId): RuleView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val saved = intake.saveRule(ownRule(me, id).pollNow(clock.now()))
        audit.record(AuditEntry("INTAKE_RULE_POLL_NOW", RULE, id.toString()))
        enqueue(id)
        return view(saved, intake.newCounts(saved.ownerUserId)[saved.id] ?: 0)
    }

    override fun proposals(state: ProposalState): List<ProposalView> {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        if (state == ProposalState.BASELINE) throw ValidationException("must be NEW, STARTED or DISMISSED", "state")
        val rules = intake.rulesOf(userId(me)).associateBy { it.id }
        return intake.proposalsOf(userId(me), state).map { proposalView(it, rules[it.ruleId]) }
    }

    override fun start(id: IntakeProposalId, workflowId: WorkflowDefinitionId?): ProposalView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val proposal = ownProposal(me, id)
        val rule = intake.findRule(proposal.ruleId)
        val chosen = workflowId ?: rule?.workflowId ?: throw ValidationException("choose a workflow", "workflowId")
        if (proposal.state != ProposalState.NEW && proposal.state != ProposalState.DISMISSED) {
            throw pl.mos.backoffice.shared.InvalidStateException("the run for ${proposal.issueKey} was already started")
        }
        // an ordinary start by the owner: run.start is checked there, the run is theirs (ADR 0035 ACTING_USER steps)
        val run = startRun.start(chosen, RunInput(proposal.issueKey, mapOf(PROPOSAL_PARAMETER to proposal.id.toString())), TriggerSource.MANUAL)
        val saved = intake.saveProposal(proposal.started(chosen, run.id, clock.now()))
        audit.record(
            AuditEntry(
                "INTAKE_PROPOSAL_START", PROPOSAL, id.toString(), projectId = run.projectId.value,
                details = mapOf("issueKey" to proposal.issueKey, "workflowId" to chosen.toString(), "runId" to run.id.toString()),
            ),
        )
        return proposalView(saved, rule)
    }

    override fun dismiss(id: IntakeProposalId): ProposalView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val saved = intake.saveProposal(ownProposal(me, id).dismissed(clock.now()))
        audit.record(AuditEntry("INTAKE_PROPOSAL_DISMISS", PROPOSAL, id.toString(), details = mapOf("issueKey" to saved.issueKey)))
        return proposalView(saved, intake.findRule(saved.ruleId))
    }

    override fun restore(id: IntakeProposalId): ProposalView {
        val me = gate.require(ProjectsPermissions.CONNECTIONS_PERSONAL)
        val saved = intake.saveProposal(ownProposal(me, id).restored())
        audit.record(AuditEntry("INTAKE_PROPOSAL_RESTORE", PROPOSAL, id.toString(), details = mapOf("issueKey" to saved.issueKey)))
        return proposalView(saved, intake.findRule(saved.ruleId))
    }

    @SystemOperation("scheduler of personal intake rules (ShedLock singleton)")
    override fun due(): List<IntakeRuleId> = intake.dueRules(clock.now()).map { it.id }

    @SystemOperation("PERSONAL_INTAKE_POLL work item; reads Jira with the rule owner's own personal connection, ISSUE_READ only")
    override fun poll(id: IntakeRuleId): Int {
        val rule = intake.findRule(id)?.takeIf { it.enabled } ?: return 0
        val now = clock.now()
        return try {
            val reader = jira.openOwnReader(rule.ownerUserId, rule.connectionId)
            val issues = reader.client.headlines(ordered(rule.jql), MAX_RESULTS)
            val known = intake.knownKeys(rule.id)
            val state = if (!rule.initialized && !rule.includeExisting) ProposalState.BASELINE else ProposalState.NEW
            val created = issues.filter { it.key !in known }
                .filter { intake.insertProposalIfAbsent(IntakeProposal.found(rule, it, state, now)) }
            intake.saveRule(rule.succeeded(now))
            if (state == ProposalState.NEW && created.isNotEmpty()) {
                events.publish(IntakeProposalsFound(rule.ownerUserId, rule.id, rule.name, created.map { it.key }, created.size, now))
            }
            if (state == ProposalState.NEW) created.size else 0
        } catch (e: IssueTrackerUnavailableException) {
            intake.saveRule(rule.failed(now, "Jira answered HTTP ${e.status}: ${e.message}", e.retryAfter))
            0
        } catch (e: DomainException) {
            intake.saveRule(rule.failed(now, e.message ?: e.javaClass.simpleName))
            0
        } catch (e: RuntimeException) {
            intake.saveRule(rule.failed(now, "${e.javaClass.simpleName}: ${e.message}"))
            0
        }
    }

    /** The connection must be the caller's own personal one with ISSUE_READ, the workflow one they may start. */
    private fun requireUsable(me: CurrentUser, command: RuleCommand) {
        val connection = connections.findById(command.connectionId)
        if (connection == null || !connection.personal || connection.ownerUserId != userId(me)) {
            throw ValidationException("choose one of your personal Jira connections", "connectionId")
        }
        if (ConnectionCapability.ISSUE_READ !in connection.capabilities) {
            throw ValidationException("connection ${connection.name} cannot read issues (ISSUE_READ)", "connectionId")
        }
        val workflow = workflows.findById(command.workflowId) ?: throw ValidationException("workflow not found", "workflowId")
        if (!me.can(ProjectsPermissions.RUN_START, workflow.projectId.value)) {
            throw ValidationException("you may not start workflow ${workflow.name} (run.start in its project)", "workflowId")
        }
    }

    private fun ownRule(me: CurrentUser, id: IntakeRuleId): PersonalIntakeRule =
        intake.findRule(id)?.takeIf { it.ownerUserId == userId(me) } ?: throw NotFoundException("IntakeRule", id)

    private fun ownProposal(me: CurrentUser, id: IntakeProposalId): IntakeProposal =
        intake.findProposal(id)?.takeIf { it.ownerUserId == userId(me) && it.state != ProposalState.BASELINE } ?: throw NotFoundException("IntakeProposal", id)

    private fun userId(me: CurrentUser): UUID = me.userId?.value ?: throw ValidationException("personal intake needs a signed-in person", null)

    private fun view(rule: PersonalIntakeRule, newProposals: Int): RuleView {
        val workflow = workflows.findById(rule.workflowId)
        val project = workflow?.let { projects.findById(it.projectId) }
        return RuleView(rule, connections.findById(rule.connectionId)?.name, workflow?.name, workflow?.projectId, project?.key, newProposals)
    }

    private fun proposalView(proposal: IntakeProposal, rule: PersonalIntakeRule?): ProposalView {
        val workflowId = proposal.workflowId ?: rule?.workflowId
        val site = rule?.let { connections.findById(it.connectionId)?.siteUrl }?.trimEnd('/')
        return ProposalView(proposal, rule?.name, workflowId, workflowId?.let { workflows.findById(it)?.name }, site?.let { "$it/browse/${proposal.issueKey}" })
    }

    private fun enqueue(id: IntakeRuleId) =
        queue.enqueue(WorkRequest(WorkKinds.PERSONAL_INTAKE_POLL, mapOf(RULE_ID to id.toString()), dedupeKey = dedupeKey(id), maxAttempts = 1))

    companion object {
        const val RULE = "IntakeRule"
        const val PROPOSAL = "IntakeProposal"
        const val RULE_ID = "ruleId"
        const val PROPOSAL_PARAMETER = "intakeProposalId"
        const val MAX_RESULTS = 50
        private val ORDER_BY = Regex("(?i)\\border\\s+by\\b")

        fun dedupeKey(id: IntakeRuleId): String = "${WorkKinds.PERSONAL_INTAKE_POLL}:$id"

        /** Newest first unless the JQL orders itself, so the 50 issues read are the most recently changed ones. */
        fun ordered(jql: String): String = if (ORDER_BY.containsMatchIn(jql)) jql else "${jql.trim()} ORDER BY updated DESC"
    }
}
