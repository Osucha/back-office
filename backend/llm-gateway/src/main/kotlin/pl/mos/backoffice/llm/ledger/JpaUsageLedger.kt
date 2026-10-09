package pl.mos.backoffice.llm.ledger

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.mos.backoffice.shared.Money
import pl.mos.backoffice.shared.llm.TokenUsage
import pl.mos.backoffice.shared.usage.PageOf
import pl.mos.backoffice.shared.usage.UsageGroup
import pl.mos.backoffice.shared.usage.UsageGroupBy
import pl.mos.backoffice.shared.usage.UsageLedger
import pl.mos.backoffice.shared.usage.UsageQuery
import pl.mos.backoffice.shared.usage.UsageRecord
import pl.mos.backoffice.shared.usage.UsageSummary
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `UsageLedger` on PostgreSQL. `record` runs in its own transaction so a ledger row is kept even when
 * the caller's work fails afterwards. Summaries are native SQL because `DAY` grouping needs `date_trunc`.
 */
@Repository
class JpaUsageLedger(private val repository: UsageRecordJpaRepository) : UsageLedger {

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    override fun record(record: UsageRecord) {
        repository.save(UsageRecordEntity.from(record))
    }

    @Transactional(readOnly = true)
    override fun summary(query: UsageQuery, groupBy: UsageGroupBy): UsageSummary {
        val groupExpr = when (groupBy) {
            UsageGroupBy.MODEL -> "model_label"
            UsageGroupBy.PROJECT -> "COALESCE(CAST(project_id AS text), '$UNATTRIBUTED')"
            UsageGroupBy.BENCHMARK -> "COALESCE(CAST(benchmark_id AS text), '$UNATTRIBUTED')"
            UsageGroupBy.DAY -> "to_char(date_trunc('day', occurred_at AT TIME ZONE 'UTC'), 'YYYY-MM-DD')"
            UsageGroupBy.CONTEXT_KIND -> "context_kind"
        }
        val (where, params) = whereClause(query)
        val order = if (groupBy == UsageGroupBy.DAY) "grp ASC" else "SUM(cost) DESC, grp ASC"
        val sql = """
            SELECT $groupExpr AS grp, SUM(cost), SUM(input_tokens), SUM(output_tokens), SUM(cache_read_tokens), SUM(cache_write_tokens), COUNT(*)
            FROM usage_record $where GROUP BY grp ORDER BY $order
        """.trimIndent()
        val nativeQuery = entityManager.createNativeQuery(sql)
        params.forEach { (name, value) -> nativeQuery.setParameter(name, value) }

        @Suppress("UNCHECKED_CAST")
        val rows = nativeQuery.resultList as List<Array<Any?>>
        val groups = rows.map { row ->
            val usage = TokenUsage(long(row[2]), long(row[3]), long(row[4]), long(row[5]))
            val key = row[0]?.toString() ?: UNATTRIBUTED
            UsageGroup(key = key, label = key, cost = Money(decimal(row[1])).rounded(), usage = usage, calls = long(row[6]))
        }
        return UsageSummary(
            from = query.from,
            to = query.to,
            groupBy = groupBy,
            total = Money.sum(groups.map { it.cost }),
            totalUsage = groups.fold(TokenUsage.ZERO) { acc, g -> acc + g.usage },
            groups = groups,
        )
    }

    @Transactional(readOnly = true)
    override fun entries(query: UsageQuery, page: Int, size: Int): PageOf<UsageRecord> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "occurredAt"))
        val result = repository.findAll(specification(query), pageable)
        return PageOf(result.content.map { it.toRecord() }, result.number, result.size, result.totalElements)
    }

    @Transactional(readOnly = true)
    override fun totalCost(query: UsageQuery): Money {
        val cb = entityManager.criteriaBuilder
        val cq = cb.createQuery(BigDecimal::class.java)
        val root = cq.from(UsageRecordEntity::class.java)
        cq.select(cb.coalesce(cb.sum(root.get("cost")), BigDecimal.ZERO))
        cq.where(*predicates(query, root, cb).toTypedArray())
        return Money(entityManager.createQuery(cq).singleResult ?: BigDecimal.ZERO).rounded()
    }

    private fun specification(query: UsageQuery) = Specification<UsageRecordEntity> { root, _, cb ->
        cb.and(*predicates(query, root, cb).toTypedArray())
    }

    private fun predicates(query: UsageQuery, root: Root<UsageRecordEntity>, cb: CriteriaBuilder): List<Predicate> = buildList {
        query.from?.let { add(cb.greaterThanOrEqualTo(root.get<Instant>("occurredAt"), it)) }
        query.to?.let { add(cb.lessThan(root.get<Instant>("occurredAt"), it)) }
        query.projectId?.let { add(cb.equal(root.get<UUID>("projectId"), it)) }
        query.runId?.let { add(cb.equal(root.get<UUID>("runId"), it)) }
        query.benchmarkId?.let { add(cb.equal(root.get<UUID>("benchmarkId"), it)) }
    }

    private fun whereClause(query: UsageQuery): Pair<String, Map<String, Any>> {
        val clauses = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        query.from?.let { clauses += "occurred_at >= :fromTs"; params["fromTs"] = it }
        query.to?.let { clauses += "occurred_at < :toTs"; params["toTs"] = it }
        query.projectId?.let { clauses += "project_id = :projectId"; params["projectId"] = it }
        query.runId?.let { clauses += "run_id = :runId"; params["runId"] = it }
        query.benchmarkId?.let { clauses += "benchmark_id = :benchmarkId"; params["benchmarkId"] = it }
        return (if (clauses.isEmpty()) "" else "WHERE " + clauses.joinToString(" AND ")) to params
    }

    private fun long(value: Any?): Long = (value as? Number)?.toLong() ?: 0L
    private fun decimal(value: Any?): BigDecimal = when (value) {
        is BigDecimal -> value
        is Number -> BigDecimal.valueOf(value.toDouble())
        else -> BigDecimal.ZERO
    }

    companion object {
        const val UNATTRIBUTED = "unattributed"
        const val MAX_PAGE_SIZE = 200
    }
}
