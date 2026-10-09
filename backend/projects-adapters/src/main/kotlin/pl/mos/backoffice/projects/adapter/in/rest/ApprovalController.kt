package pl.mos.backoffice.projects.adapter.`in`.rest

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.projects.application.port.`in`.ApprovalQueries
import pl.mos.backoffice.projects.application.port.`in`.AssignApproval
import pl.mos.backoffice.projects.application.port.`in`.DecideApproval
import pl.mos.backoffice.projects.application.port.`in`.WriteIdentityQuery
import pl.mos.backoffice.projects.domain.model.ApprovalRequestId
import pl.mos.backoffice.projects.domain.model.ApprovalStatus
import pl.mos.backoffice.shared.ValidationException
import java.util.UUID

/**
 * `approvals` endpoints: the inbox (only approvals of projects the caller can view, each marked with
 * `canDecide` / `cannotDecideReason` from the gate's approver rule, `canAssign` and the assignees), the
 * decide action and the run-time assignment of an open approval (ADR 0028).
 */
@RestController
@RequestMapping("/api/v1/approvals")
class ApprovalController(
    private val queries: ApprovalQueries,
    private val decide: DecideApproval,
    private val assign: AssignApproval,
    private val views: ApprovalViews,
    private val mapper: RestMapper,
    private val identities: WriteIdentityQuery,
) {
    /** ADR 0035: which identities the caller could approve a waiting Jira write as. */
    @GetMapping("/{approvalId}/write-identity")
    fun writeIdentity(@PathVariable approvalId: UUID): WriteIdentityDto = mapper.toDto(identities.writeIdentity(ApprovalRequestId(approvalId)))

    @GetMapping
    fun list(@RequestParam(defaultValue = "OPEN") status: String): List<ApprovalRequestDto> {
        val parsed = ApprovalStatus.entries.firstOrNull { it.name.equals(status, true) } ?: throw ValidationException("unknown status $status", "status")
        return views.toDtos(queries.list(parsed))
    }

    @GetMapping("/{approvalId}")
    fun get(@PathVariable approvalId: UUID): ApprovalRequestDto = views.toDto(queries.get(ApprovalRequestId(approvalId)))

    @PostMapping("/{approvalId}/decide")
    fun decide(@PathVariable approvalId: UUID, @RequestBody @Valid body: DecisionDto): ApprovalRequestDto =
        views.toDto(decide.decide(ApprovalRequestId(approvalId), mapper.toCommand(body)))

    @PutMapping("/{approvalId}/assignees")
    fun assign(@PathVariable approvalId: UUID, @RequestBody @Valid body: AssignApprovalDto): ApprovalRequestDto =
        views.toDto(assign.assign(ApprovalRequestId(approvalId), mapper.toCommand(body)))

    @GetMapping("/{approvalId}/assignee-candidates")
    fun candidates(@PathVariable approvalId: UUID): List<AssigneeCandidateDto> =
        assign.candidates(ApprovalRequestId(approvalId)).map(mapper::toDto)
}
