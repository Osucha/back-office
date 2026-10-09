package pl.mos.backoffice.projects.application.port.out

import pl.mos.backoffice.projects.domain.model.IntakeProposal
import pl.mos.backoffice.projects.domain.model.IntakeProposalId
import pl.mos.backoffice.projects.domain.model.IntakeRuleId
import pl.mos.backoffice.projects.domain.model.PersonalIntakeRule
import pl.mos.backoffice.projects.domain.model.ProposalState
import java.time.Instant
import java.util.UUID

/** Personal intake rules and their proposals (ADR 0038). */
interface PersonalIntakeRepository {
    fun saveRule(rule: PersonalIntakeRule): PersonalIntakeRule
    fun findRule(id: IntakeRuleId): PersonalIntakeRule?
    fun rulesOf(ownerUserId: UUID): List<PersonalIntakeRule>

    /** Deletes the rule and its proposals (runs started from them stay). */
    fun deleteRule(id: IntakeRuleId)

    /** Enabled rules whose next poll is due at [now]. */
    fun dueRules(now: Instant): List<PersonalIntakeRule>

    /** Issue keys the rule has proposed (or remembered as baseline) so far. */
    fun knownKeys(ruleId: IntakeRuleId): Set<String>

    /** Inserts unless the rule already has a proposal for the issue (unique per rule and key); true when inserted. */
    fun insertProposalIfAbsent(proposal: IntakeProposal): Boolean

    fun findProposal(id: IntakeProposalId): IntakeProposal?
    fun saveProposal(proposal: IntakeProposal): IntakeProposal

    /** The owner's proposals in [state], newest first, at most [limit]. */
    fun proposalsOf(ownerUserId: UUID, state: ProposalState, limit: Int = 200): List<IntakeProposal>

    /** NEW proposals per rule of the owner. */
    fun newCounts(ownerUserId: UUID): Map<IntakeRuleId, Int>
}
