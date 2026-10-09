package pl.mos.backoffice.governance.adapter.`in`.rest

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.mos.backoffice.governance.adapter.out.json.DsseJson
import pl.mos.backoffice.governance.application.port.`in`.DeliveryInsightsQuery
import pl.mos.backoffice.governance.application.port.`in`.ProvenanceQueries
import pl.mos.backoffice.governance.domain.model.AgentSuccess
import pl.mos.backoffice.governance.domain.model.Attestation
import pl.mos.backoffice.governance.domain.model.DeliveryInsights
import pl.mos.backoffice.governance.domain.model.DistributionStat
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.ValidationException
import pl.mos.backoffice.shared.platform.DsseEnvelope
import pl.mos.backoffice.shared.platform.DsseSignature
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** `provenance` tag of openapi.yaml: attestations of a run, one DSSE envelope, verification. */
@RestController
@RequestMapping("/api/v1")
class ProvenanceController(private val provenance: ProvenanceQueries) {

    @GetMapping("/runs/{runId}/attestations")
    fun list(@PathVariable runId: UUID): List<AttestationSummaryDto> = provenance.listForRun(runId).map(AttestationSummaryDto::of)

    @GetMapping("/attestations/{attestationId}", produces = [IN_TOTO_JSON, MediaType.APPLICATION_JSON_VALUE])
    fun get(@PathVariable attestationId: UUID): ResponseEntity<Map<String, Any?>> {
        val attestation = provenance.get(attestationId)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(IN_TOTO_JSON))
            .header("Content-Disposition", "attachment; filename=\"attestation-${attestation.id}.intoto.json\"")
            .body(DsseJson.toJson(attestation.envelope))
    }

    @PostMapping("/attestations/verify", consumes = [MediaType.APPLICATION_JSON_VALUE, IN_TOTO_JSON])
    fun verify(@RequestBody envelope: DsseEnvelopeDto): VerificationDto {
        val result = provenance.verify(envelope.toDomain())
        return VerificationDto(result.valid, result.keyId, result.statement, result.reason)
    }

    companion object {
        const val IN_TOTO_JSON = "application/vnd.in-toto+json"
    }
}

/** Public JWK set of the platform signing keys (current and retired), for offline verification. */
@RestController
class SigningKeysController(private val provenance: ProvenanceQueries) {

    @GetMapping("/.well-known/back-office/keys", produces = [MediaType.APPLICATION_JSON_VALUE, "application/jwk-set+json"])
    fun keys(): ResponseEntity<Map<String, Any?>> =
        ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(mapOf("keys" to provenance.publicKeys()))
}

/** `insights` tag of openapi.yaml. */
@RestController
@RequestMapping("/api/v1/insights")
class InsightsController(private val insights: DeliveryInsightsQuery) {

    @GetMapping("/delivery")
    fun delivery(
        @RequestParam(required = false) projectId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): DeliveryInsightsDto = DeliveryInsightsDto.of(insights.get(projectId, from, to))
}

// ------------------------------------------------------------------------------------------------ DTOs

data class SubjectDto(val name: String, val digest: Map<String, String>)

data class AttestationSummaryDto(
    val id: UUID,
    val runId: UUID,
    val nodeId: String?,
    val jobId: UUID?,
    val kind: String,
    val subjects: List<SubjectDto>,
    val keyId: String,
    val createdAt: Instant,
) {
    companion object {
        fun of(a: Attestation) = AttestationSummaryDto(a.id, a.runId, a.nodeId, a.jobId, a.kind.name, a.subjects.map { SubjectDto(it.name, it.digest) }, a.keyId, a.createdAt)
    }
}

data class DsseSignatureDto(val keyid: String? = null, val sig: String? = null)

data class DsseEnvelopeDto(val payloadType: String? = null, val payload: String? = null, val signatures: List<DsseSignatureDto>? = null) {
    fun toDomain(): DsseEnvelope {
        if (payloadType.isNullOrBlank()) throw ValidationException("must not be blank", "payloadType")
        if (payload.isNullOrBlank()) throw ValidationException("must not be blank", "payload")
        return DsseEnvelope(payloadType, payload, signatures.orEmpty().map { DsseSignature(it.keyid ?: "", it.sig ?: "") })
    }
}

data class VerificationDto(val valid: Boolean, val keyId: String?, val statement: Map<String, Any?>?, val reason: String?)

data class MoneyDto(val amount: BigDecimal, val currency: String) {
    companion object {
        fun of(m: Money) = MoneyDto(m.rounded().amount, m.currency)
    }
}

data class DistributionStatDto(val p50Seconds: Double?, val p90Seconds: Double?, val totalSeconds: Double?, val count: Int) {
    companion object {
        fun of(d: DistributionStat) = DistributionStatDto(d.p50Seconds, d.p90Seconds, d.totalSeconds, d.count)
    }
}

data class RunCountsDto(val total: Int, val succeeded: Int, val failed: Int, val cancelled: Int)
data class TemplateSuccessDto(val templateKey: String, val jobs: Int, val succeeded: Int, val rate: Double, val cost: MoneyDto)
data class ModelSuccessDto(val model: String, val jobs: Int, val succeeded: Int, val rate: Double, val cost: MoneyDto)
data class GateDecisionDto(val option: String, val count: Int)
data class WeeklyDto(val weekStart: java.time.LocalDate, val succeeded: Int, val failed: Int, val humanWaitHours: Double, val cost: MoneyDto)
data class BottleneckDto(val nodeId: String, val nodeName: String, val workflowName: String, val p90WaitSeconds: Double)
data class ModelMixDto(val model: String, val calls: Long, val inputTokens: Long, val outputTokens: Long, val cost: MoneyDto)

data class DeliveryInsightsDto(
    val from: Instant,
    val to: Instant,
    val projectId: UUID?,
    val leadTime: DistributionStatDto,
    val humanWait: DistributionStatDto,
    val ciWait: DistributionStatDto,
    val runs: RunCountsDto,
    val reworkLoopsPerRun: Double?,
    val costPerSuccessfulRun: MoneyDto,
    val costPerMergeRequest: MoneyDto,
    val totalCost: MoneyDto,
    val agentSuccessByTemplate: List<TemplateSuccessDto>,
    val agentSuccessByModel: List<ModelSuccessDto>,
    val gateDecisions: List<GateDecisionDto>,
    val weekly: List<WeeklyDto>,
    val bottleneck: BottleneckDto?,
    val modelMix: List<ModelMixDto>,
) {
    companion object {
        private fun rate(a: AgentSuccess) = BigDecimal(a.rate).setScale(4, java.math.RoundingMode.HALF_UP).toDouble()

        fun of(i: DeliveryInsights) = DeliveryInsightsDto(
            from = i.from,
            to = i.to,
            projectId = i.projectId,
            leadTime = DistributionStatDto.of(i.leadTime),
            humanWait = DistributionStatDto.of(i.humanWait),
            ciWait = DistributionStatDto.of(i.ciWait),
            runs = RunCountsDto(i.runs.total, i.runs.succeeded, i.runs.failed, i.runs.cancelled),
            reworkLoopsPerRun = i.reworkLoopsPerRun,
            costPerSuccessfulRun = MoneyDto.of(i.costPerSuccessfulRun),
            costPerMergeRequest = MoneyDto.of(i.costPerMergeRequest),
            totalCost = MoneyDto.of(i.totalCost),
            agentSuccessByTemplate = i.agentSuccessByTemplate.map { TemplateSuccessDto(it.key, it.jobs, it.succeeded, rate(it), MoneyDto.of(it.cost)) },
            agentSuccessByModel = i.agentSuccessByModel.map { ModelSuccessDto(it.key, it.jobs, it.succeeded, rate(it), MoneyDto.of(it.cost)) },
            gateDecisions = i.gateDecisions.map { GateDecisionDto(it.option, it.count) },
            weekly = i.weekly.map { WeeklyDto(it.weekStart, it.succeeded, it.failed, it.humanWaitHours, MoneyDto.of(it.cost)) },
            bottleneck = i.bottleneck?.let { BottleneckDto(it.nodeId, it.nodeName, it.workflowName, it.p90WaitSeconds) },
            modelMix = i.modelMix.map { ModelMixDto(it.model, it.calls, it.inputTokens, it.outputTokens, MoneyDto.of(it.cost)) },
        )
    }
}
