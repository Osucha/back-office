package pl.mos.backoffice.evaluation.domain.model

import pl.mos.backoffice.shared.ModelProfileId
import pl.mos.backoffice.shared.llm.StepKind
import pl.mos.backoffice.shared.security.Actor
import java.time.Instant
import java.util.UUID

/**
 * ADR 0050 step 2: the default model of a kind of step, used when neither the step, its template nor the project
 * names one: a user's own default ([ownerUserId]) or the platform's ([ownerUserId] null).
 */
data class ModelDefault(
    val ownerUserId: UUID?,
    val stepKind: StepKind,
    val profileId: ModelProfileId,
    val updatedAt: Instant,
    val updatedBy: Actor? = null,
)
