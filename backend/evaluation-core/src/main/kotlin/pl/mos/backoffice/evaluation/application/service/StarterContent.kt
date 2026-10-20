package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
import pl.mos.backoffice.evaluation.domain.model.TaskAcceptance
import pl.mos.backoffice.evaluation.domain.model.TaskCategory
import pl.mos.backoffice.evaluation.domain.model.TaskKind
import pl.mos.backoffice.evaluation.domain.model.TaskRepository
import pl.mos.backoffice.evaluation.domain.model.TaskSuite
import pl.mos.backoffice.shared.llm.ModelOptions
import pl.mos.backoffice.shared.llm.ModelRef
import pl.mos.backoffice.shared.llm.Provider
import pl.mos.backoffice.shared.llm.Reasoning
import pl.mos.backoffice.shared.usage.Pricing
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Starter task suite. Coding tasks run against Spring PetClinic pinned to one commit, so they work out of the box and
 * every benchmark sees the same code. Model profiles are not seeded (ADR 0045): they are created from the models of
 * the connected providers.
 */
object StarterContent {

    /** The address of the first starter coding tasks, which never existed; [upgradeCodingTasks] replaces them. */
    const val PLACEHOLDER_REPO_URL = "https://gitlab.example.com/samples/kotlin-sample.git"
    const val PETCLINIC_REPO_URL = "https://github.com/spring-projects/spring-petclinic.git"
    const val PETCLINIC_COMMIT = "500158f732419217507c7656904b8e6aa1bcc0d6"
    const val STARTER_SUITE_NAME = "Starter: SDLC tasks"

    fun starterSuite(now: Instant): TaskSuite = TaskSuite.create(
        name = STARTER_SUITE_NAME,
        description = "Twelve example tasks across analysis, specification, review, architecture and coding. " +
            "The five CODING_JOB tasks run against Spring PetClinic pinned to one commit; replace them with your own tickets and repositories.",
        codingScoreWeights = CodingScoreWeights.DEFAULT,
        tasks = listOf(
            rootCauseFailingTest(),
            compareIntegrationApproaches(),
            specFromTicket(),
            paymentRetryAcceptanceCriteria(),
            reviewKotlinDiff(),
            monolithModuleBoundaries(),
            writeAdr(),
        ) + codingTasks(),
        now = now,
    )

    /** The five CODING_JOB tasks (Spring PetClinic). */
    fun codingTasks(): List<EvalTask> =
        listOf(codingAddEndpoint(), codingVisitVet(), codingOptimisticLocking(), codingRefactorHexagonal(), codingRateLimiter())

    /**
     * ADR 0046: a starter suite seeded with the placeholder repository gets the PetClinic tasks instead of the coding tasks
     * that still point there; tasks the owner changed to another repository stay. Null when nothing is to change.
     */
    fun upgradeCodingTasks(suite: TaskSuite, now: Instant): TaskSuite? {
        val stale = suite.tasks.filter { it.repository?.url == PLACEHOLDER_REPO_URL }
        if (stale.isEmpty()) return null
        val kept = suite.tasks - stale.toSet()
        val added = codingTasks().filter { new -> kept.none { it.name == new.name } }
        return suite.copy(
            tasks = kept + added,
            description = if (suite.name == STARTER_SUITE_NAME) starterSuite(now).description else suite.description,
            updatedAt = now,
        )
    }

    /**
     * ADR 0047: content hashes ([contentHash]) of the earlier starter tasks, each mapped to the name of the task that
     * replaces it. A task with one of these hashes was never edited by the owner.
     */
    internal val EARLIER_VERSIONS: Map<String, String> = mapOf(
        "f5beb699a9c219293ba60cec3995d678" to "Root-cause a failing test from a stack trace",
        "b48a7b1b7d4ff30ca795a74efcf034ea" to "Compare two integration approaches",
        "ccba165d2ac9c0e15161552d01d52b6d" to "Write a Spec Kit spec from a two-line ticket",
        "50e43de64d863d97643a6f64031431bf" to "Acceptance criteria and edge cases for a payment retry",
        "d1220d0daa47f43bbefc86b8d241155f" to "Review a Kotlin diff with planted bugs",
        "6addb35e3978797ef3154b17ac5a47c7" to "Propose module boundaries for a monolith",
        "61b77fd40757c62f3fa1830a141bc34d" to "Write an ADR",
        "b54b204af897690c615523d93bf7495a" to "Add a validated JSON endpoint for owners",
        "33656e142ca7724ffc1b858e41a91632" to "Record which vet handled a visit",
        "e5fa04d06eca687aaf3bb6799ae58182" to "Prevent lost updates on owner edits",
        "e8f3bfbd49ef7cf652db87ed98f98334" to "Refactor the vet module to ports and adapters",
        "d09900a2c38dcf49ce8008b6b109666a" to "Rate-limit owner form submissions",
    )

    /** md5 of `instructions|rubric|context|referenceAnswer`, the same as the SQL used to record [EARLIER_VERSIONS]. */
    fun contentHash(task: EvalTask): String {
        val text = listOf(task.instructions, task.rubric, task.context.orEmpty(), task.referenceAnswer.orEmpty()).joinToString("|")
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * ADR 0047: starter tasks still in an earlier version get the current content (keeping their id and weight); tasks
     * the owner edited stay. Null when nothing is to change.
     */
    fun upgradeUntouchedTasks(suite: TaskSuite, now: Instant, earlier: Map<String, String> = EARLIER_VERSIONS): TaskSuite? {
        val current = starterSuite(now).tasks.associateBy { it.name }
        var changed = false
        val tasks = suite.tasks.map { task ->
            val fresh = earlier[contentHash(task)]?.let { current[it] }
            if (fresh == null) task else fresh.copy(id = task.id, weight = task.weight).also { changed = true }
        }
        return if (changed) suite.copy(tasks = tasks, updatedAt = now) else null
    }

    // ----- ANALYSIS -----------------------------------------------------------------------------

    private fun rootCauseFailingTest() = EvalTask(
        id = EvalTaskId.new(),
        name = "Root-cause a failing test from a stack trace",
        category = TaskCategory.ANALYSIS,
        difficulty = Difficulty.EASY,
        kind = TaskKind.PROMPT,
        instructions = """
            A CI run of a Kotlin/Spring Boot service fails on one test. Using only the stack trace and the code
            excerpt in the context, explain:
            1. the root cause (not the symptom) in two or three sentences,
            2. the minimal code fix as a diff or a rewritten snippet,
            3. one additional test case that would have caught the bug earlier.
            Do not speculate about code you cannot see; say explicitly when something is an assumption.
        """.trimIndent(),
        context = """
            ```
            OrderTotalsTest > total of a single-line order() FAILED
                org.opentest4j.AssertionFailedError:
                expected: 90.00
                 but was: 81.00
                    at OrderTotalsTest.total of a single-line order(OrderTotalsTest.kt:27)
            ```

            ```kotlin
            class OrderTotals(private val discounts: DiscountPolicy) {
                fun total(order: Order): BigDecimal {
                    var sum = BigDecimal.ZERO
                    for (line in order.lines) {
                        val lineTotal = line.unitPrice.multiply(BigDecimal(line.quantity))
                        sum = sum.add(discounts.apply(lineTotal, order.customer))
                    }
                    return discounts.apply(sum, order.customer).setScale(2, RoundingMode.HALF_UP)
                }
            }

            class DiscountPolicy {
                fun apply(amount: BigDecimal, customer: Customer): BigDecimal =
                    if (customer.tier == Tier.GOLD) amount.multiply(BigDecimal("0.90")) else amount
            }
            ```

            Test: one line, unit price 100.00, quantity 1, GOLD customer; expected total 90.00.
        """.trimIndent(),
        rubric = """
            - Correctness (0-10): 10 = states that DiscountPolicy.apply runs twice for a GOLD order (once per line inside the loop, again on the sum), so 100.00 becomes 100.00 * 0.90 * 0.90 = 81.00, and backs it with that arithmetic. Wrong root cause (rounding, scale, HALF_UP, BigDecimal precision, test data): Correctness <= 2. Right cause without the arithmetic or with a wrong explanation of where the second application happens: <= 6.
            - Fix (0-10): 10 = a minimal diff that applies the policy exactly once (either drop the per-line call and keep the call on the sum, or keep the per-line call and return sum.setScale(2, RoundingMode.HALF_UP)), keeps the final HALF_UP scaling and compiles as Kotlin against the shown types. A fix that still applies the discount twice in some path, removes the discount entirely or changes the result for non-GOLD customers: <= 2. A fix that adds per-line rounding without saying it changes totals: <= 6.
            - Additional test (0-10): 10 = a concrete Kotlin test that would fail on the buggy code and pins the contract "the GOLD discount is applied exactly once to the order": a GOLD order with two or more lines and a hand-computed expected total, asserted with compareTo / isEqualByComparingTo (not equals on BigDecimal, which is scale-sensitive); a non-GOLD control is a plus. A test that only uses a non-GOLD customer (factor 1.0 squared is still 1.0, so it cannot see double application): <= 3. A test that would not compile against the shown types (invented properties or methods) without labelling the constructor shape as an assumption: <= 3. Asserting with assertEquals on BigDecimal values of different scale: <= 5.
            - Discipline (0-10): 10 = uses only what the context shows, labels assumptions (for example the constructors of Order, OrderLine and Customer), short (root cause in two or three sentences). Invented code or behaviour presented as fact: <= 4. Long speculative detours (database, concurrency, caching): <= 6.
        """.trimIndent(),
        referenceAnswer = """
            Ground truth: OrderTotals.total calls discounts.apply on every line inside the loop and then again on the
            sum. For the failing test (one line, 100.00 x 1, GOLD) that is 100.00 * 0.90 = 90.0000 per line, then
            90.0000 * 0.90 = 81.000000 on the sum, scaled to 81.00. Expected 90.00. Not a rounding or scale problem.

            A top answer states:
            - Root cause: the GOLD discount is applied twice (per line and on the order sum); the 81.00 = 100 * 0.9 * 0.9
              arithmetic proves it.
            - Fix (either is correct): remove the per-line call so the loop only sums line totals and the existing
              `discounts.apply(sum, order.customer).setScale(2, RoundingMode.HALF_UP)` stays; or keep the per-line call
              and return `sum.setScale(2, RoundingMode.HALF_UP)`. With exact BigDecimal multiplication both give the
              same totals; they only differ if someone adds per-line rounding.
            - Additional test: a GOLD order with several lines and a hand-computed total, for example 19.99 x 3 and
              5.01 x 1 (sum 64.98): expected 58.48 (64.98 * 0.90 = 58.482), while the buggy code gives 52.63
              (64.98 * 0.81 = 52.6338); asserted with `isEqualByComparingTo(BigDecimal("58.48"))` or
              `compareTo(...) == 0`. Optionally a non-GOLD control (expected 64.98) and a case that pins where rounding
              happens (three GOLD lines of 0.05: discount on the sum gives 0.135 -> 0.14, per-line rounding would give
              0.15). Constructors of Order, line and Customer are not shown and are labelled as assumptions.

            Penalise:
            - "Rounding / scale / HALF_UP / BigDecimal precision is the cause" (wrong).
            - A fix that removes the discount, applies it in two places again, or rounds every line without saying so.
            - An additional test with only a non-GOLD customer (cannot detect double application), or a test that only
              repeats the failing one-line case.
            - BigDecimal assertions with equals / assertEquals across different scales (90.00 vs 90.0000 fails).
            - Tests using properties or methods that do not exist in the shown code, presented as fact.
            - Claiming the test expectation is wrong.
        """.trimIndent(),
        timeout = Duration.ofMinutes(5),
    )

    private fun compareIntegrationApproaches() = EvalTask(
        id = EvalTaskId.new(),
        name = "Compare two integration approaches",
        category = TaskCategory.ANALYSIS,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.PROMPT,
        instructions = """
            An order service must inform an inventory service when an order is placed so stock is reserved.
            Compare two approaches: (A) a synchronous REST call from order service to inventory service inside
            the order placement transaction, and (B) publishing an OrderPlaced event to a message broker that
            the inventory service consumes.
            Produce:
            - a comparison table with at least these rows: consistency guarantees, failure handling, latency,
              coupling, operational complexity, testability, evolution when a third consumer appears;
            - a recommendation for the situation described in the context with explicit trade-offs;
            - the two or three concrete mechanisms needed to make the recommended option safe (for example
              outbox pattern, idempotent consumer, timeouts and fallbacks).
            Keep it to about 700 words; much longer answers lose Clarity points.
        """.trimIndent(),
        context = """
            Team of one architect-developer, Kotlin/Spring Boot services on Kubernetes, PostgreSQL per service,
            no broker in production today, RabbitMQ available in the platform catalogue. Order volume ~2 000/day,
            inventory service owned by another team with 99.5 % availability. Business tolerates a reservation
            appearing up to 30 seconds after the order but not double reservations or lost reservations.
        """.trimIndent(),
        rubric = """
            - Correctness (0-10): 10 = every requested row present and technically right, in particular: A cannot be atomic with the local database transaction (a remote call is not rolled back, so commit-after-reserve leaves an orphan reservation and a timeout-then-retry can reserve twice); A couples order placement to inventory availability; B with a broker is at-least-once, so duplicates are normal. Claiming A gives strong or ACID consistency across both services, or that a broker gives exactly-once delivery: Correctness <= 3. A missing requested row: -1 each.
            - Reasoning (0-10): 10 = the recommendation is derived from the context numbers: 99.5 % availability is about 3.6 hours a month (0.005 x ~730 h) in which A blocks or fails order placement, while the business tolerates 30 seconds of delay, so asynchronous delivery fits; it weighs that RabbitMQ is not in production yet and is new operational cost for a one-person team (and may offer an outbox relay without a broker as the first step). Recommending A with only "add retries" as mitigation: Reasoning <= 4. Recommending B without mentioning the cost of operating a new broker for one person: <= 7. No numbers from the context used: <= 6.
            - Safety mechanisms (0-10): 10 = transactional outbox written in the same transaction as the order, a relay that delivers at least once, and an idempotent consumer keyed by orderId (for example a unique reservation per orderId) so duplicates do not double-reserve; plus monitoring of outbox lag against the 30-second budget. If A is recommended: idempotency key per order, tight timeouts, and a compensation path for commit failures after a reservation. "Use retries" or "use a broker" without idempotency: <= 4. Outbox without idempotent consumer, or the reverse: <= 6.
            - Clarity (0-10): 10 = a Markdown table that renders, a recommendation in the first lines of its section, about 700 words or fewer, no filler. Much longer than about 700 words: <= 5. Table broken or missing: <= 4.
        """.trimIndent(),
        referenceAnswer = """
            Ground truth from the context: 2 000 orders/day (about 1.4 per minute), inventory 99.5 % available (about
            3.6 hours of downtime per month, about 300 orders affected per month at this volume if spread evenly), 30 seconds of
            tolerated delay, no double and no lost reservations, one person operates everything, no broker in
            production, RabbitMQ available in the catalogue.

            A top answer states:
            - A (synchronous REST inside the order transaction) is a dual write: the remote reservation cannot take
              part in the local transaction. If the order commit fails after the call, a reservation is orphaned; if
              the call times out after inventory reserved, a retry double-reserves unless the call carries an
              idempotency key. A also holds a database transaction and connection open across a network call and
              makes order placement fail whenever inventory is down (about 3.6 h/month).
            - B decouples availability and latency, scales to a third consumer by subscription, but delivery is
              at least once and ordering is not guaranteed; it is eventually consistent, which the 30-second
              tolerance allows (except during inventory outages, when reservations are late but not lost).
            - Recommendation: B, made safe by a transactional outbox (OrderPlaced row written in the order
              transaction, relayed afterwards) and an idempotent consumer keyed by orderId on the inventory side
              (unique reservation per orderId), which needs agreement with the inventory team. RabbitMQ is new
              operational cost for a one-person team; an acceptable lighter path is the outbox relayed by a poller
              that calls the inventory API with orderId as the idempotency key, moving to RabbitMQ when a third
              consumer appears. Monitor outbox lag against the 30-second budget and alert on stuck rows.

            Penalise:
            - "A is consistent / atomic / transactional across both services" or "two-phase commit is the easy fix".
            - "The broker guarantees exactly-once delivery", so no idempotency is needed.
            - Recommending A with retries as the only mitigation (retries without an idempotency key cause the
              double reservations the business forbids).
            - Ignoring that the broker is not in production yet and is run by one person.
            - Publishing to the broker directly after commit without an outbox (lost events on crash).
            - Generic pros/cons that do not use the numbers in the context.
        """.trimIndent(),
        timeout = Duration.ofMinutes(10),
    )

    // ----- SPECIFICATION ------------------------------------------------------------------------

    private fun specFromTicket() = EvalTask(
        id = EvalTaskId.new(),
        name = "Write a Spec Kit spec from a two-line ticket",
        category = TaskCategory.SPECIFICATION,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.PROMPT,
        instructions = """
            Turn the ticket in the context into a Spec Kit feature specification (spec.md) following the Spec Kit
            spec template:
            - a header (Feature Specification: <name>, Status: Draft, Input: the ticket);
            - User Scenarios & Testing: prioritised user stories (P1, P2, ...), each with why it has that
              priority, how it can be tested independently, and acceptance scenarios in Given/When/Then; then
              an Edge Cases list;
            - Requirements: Functional Requirements numbered FR-001, FR-002, ... phrased as "System MUST ...",
              and Key Entities (what they represent, no storage details);
            - Success Criteria: measurable, technology-agnostic outcomes numbered SC-001, SC-002, ...;
            - a short Out of Scope list.
            Mark every open product decision inline, in the requirement or scenario it blocks, as
            [NEEDS CLARIFICATION: <the question>]; at least three of them.
            Requirements must be testable and implementation-agnostic: no framework, database, storage or
            endpoint names. Aim for 5-10 functional requirements. The spec must say what happens when a customer
            restores a saved cart on a device that already has a different cart.
        """.trimIndent(),
        context = """
            Ticket SHOP-482: "Customers should be able to save a shopping cart for later and get it back on another
            device. Carts saved for more than 30 days can be dropped."
            Product: B2C web shop, customers log in with email, carts today live in the browser session only.
        """.trimIndent(),
        rubric = """
            - Structure (0-10): 10 = follows the Spec Kit template as instructed: prioritised user stories with independent test and Given/When/Then acceptance scenarios, Edge Cases, FR-001-style "System MUST" requirements, Key Entities, SC-001-style success criteria, Out of Scope. Missing Success Criteria or user stories without priorities: <= 6. Own section scheme instead of the template (for example "Overview / Non-functional requirements / Open questions" only): <= 4.
            - Requirement quality (0-10): 10 = each FR is testable and implementation-agnostic and together they cover: save the cart for a logged-in customer, restore it on another device after login, expiry after 30 days with the reference point defined (since saving or since last change), a defined behaviour when the restoring device already has a different cart (merge, replace or ask, or a NEEDS CLARIFICATION on exactly that), and prices and availability re-checked on restore. Missing the device-conflict rule: <= 5. Any framework, database, cache, cookie or endpoint name in a requirement: <= 5. Vague verbs ("should be fast", "user-friendly"): -2.
            - Open questions (0-10): 10 = at least three inline [NEEDS CLARIFICATION: question] markers on genuine product decisions placed where they block a requirement (for example: merge vs replace on conflict, one or several saved carts, whether expiry is notified, price changes and out-of-stock items on restore, guests). Fewer than three: <= 5. Markers only in a separate list, or questions an engineer could decide alone: <= 6.
            - Scope and success criteria (0-10): 10 = success criteria are measurable outcomes for users or the business (for example share of restores that show the same items, time for a customer to get the cart back) with no technical metrics, and Out of Scope keeps the feature small (sharing carts, wish lists, guest carts across devices). Invented features presented as requirements (named carts, sharing, reminders by email): <= 5. Success criteria that are technical (API latency, database size): <= 5.
        """.trimIndent(),
        referenceAnswer = """
            A top answer states:
            - P1 user story: a logged-in customer saves the current cart and gets it back after logging in on another
              device; independent test: save on device 1, log in on device 2, the same items and quantities appear.
            - P2: expiry: saved carts older than 30 days may be removed; the spec defines the reference point (since
              saving or since last change) or marks it [NEEDS CLARIFICATION]; the ticket says "can be dropped", which
              is permission, not an obligation, and a top answer notices that.
            - P2/P3: conflict: restoring on a device that already holds a different cart has a defined outcome
              (merge with summed quantities, replace, or ask the customer); the choice is a product decision, so a
              [NEEDS CLARIFICATION: merge, replace or ask?] marker on that FR is ideal.
            - FRs in "System MUST" form, for example: MUST let a logged-in customer save the cart; MUST make the saved
              cart available on any device after login; MUST show current prices and flag items no longer available
              on restore instead of dropping them silently; MUST remove saved carts after the retention period; MUST
              not expose a saved cart to anyone but its owner.
            - Key Entities: Customer, Saved Cart (owner, items, saved/updated time), Cart Item (product, quantity).
            - Success criteria as outcomes, for example SC-001: 95 % of customers who saved a cart see the same items
              after logging in on another device; SC-002: a customer gets the saved cart back in under 10 seconds
              from login.
            - Inline [NEEDS CLARIFICATION] on: conflict handling, one vs several saved carts, saving as a guest,
              notifying before expiry, price changes since saving.
            - Out of Scope: sharing carts, wish lists, guest carts across devices, cart recovery emails.

            Penalise:
            - Technology in requirements or success criteria (database, Redis, cookies, REST endpoints, JWT,
              "p95 latency").
            - No rule for the device that already has a cart.
            - Treating "can be dropped" as "MUST delete exactly at 30 days" without noticing the ambiguity, or not
              defining when the 30 days start.
            - Open questions collected only at the end instead of inline [NEEDS CLARIFICATION: ...] markers.
            - Invented features stated as requirements (multiple named carts, sharing, reminder emails).
            - Requirement numbering FR-1 or untestable wording ("easy", "seamless").
        """.trimIndent(),
        timeout = Duration.ofMinutes(10),
    )

    private fun paymentRetryAcceptanceCriteria() = EvalTask(
        id = EvalTaskId.new(),
        name = "Acceptance criteria and edge cases for a payment retry",
        category = TaskCategory.SPECIFICATION,
        difficulty = Difficulty.HARD,
        kind = TaskKind.PROMPT,
        instructions = """
            Write acceptance criteria for automatic retry of failed card payments in a subscription service,
            based on the context. Deliver:
            1. Acceptance criteria as Gherkin scenarios grouped by theme (schedule, customer communication,
               state changes, limits, operator visibility), each theme in a fenced ```gherkin code block.
               At least 12 scenarios.
            2. An edge-case list with at least 10 entries, each with the expected behaviour (for example: card
               expires between retries, customer updates card during the retry window, partial refund pending,
               currency change, duplicate webhook from the payment provider, subscription cancelled mid-cycle).
            3. The state diagram of a payment attempt as a Mermaid stateDiagram.
            Be precise about time zones, cut-offs and idempotency.
        """.trimIndent(),
        context = """
            Business rules: retry a failed renewal payment up to 3 times at +1 day, +3 days and +7 days after the
            first failure; email the customer after the first failure and before the final attempt; suspend the
            subscription after the final failure; never charge twice for one billing period; the payment
            provider sends webhooks that may arrive out of order or twice; operators need to see the retry
            history and trigger a manual retry.
        """.trimIndent(),
        rubric = """
            - Rule correctness (0-10): 10 = the scenarios encode the business rules exactly: retries at T+1d, T+3d and T+7d measured from the first failure T (not cumulative T+1/T+4/T+11); email after the first failure and before the final (T+7d) attempt; suspension only after the third retry fails (not after the third attempt counting the original charge); operators see the history and can trigger a manual retry. Cumulative schedule or suspension after the wrong attempt: Correctness <= 3. Any other rule contradicted: -3 each.
            - Idempotency and races (0-10): 10 = one idempotency key per subscription billing period, sent to the provider on every attempt (automatic and manual), so a manual retry racing an automatic one cannot charge twice; a local guard (state check or lock) so a period already paid is never retried; duplicate webhooks deduplicated by provider event id; out-of-order webhooks resolved by attempt state (a late failure never overrides a success); a success webhook arriving after suspension has defined handling (reactivate, or refund by policy). A new key per attempt, or "no double charge" asserted without a mechanism: <= 3. No handling of the late success after suspension: <= 6.
            - Edge cases (0-10): 10 = at least ten realistic edge cases each with an expected behaviour, including card updated during the window (next attempt uses the new card, same key and schedule unless stated), card expiring between retries, cancellation mid-window (stop retries), and time zone / cut-off (schedule computed in one stated zone, attempts on day boundaries defined). Edge cases without an expected behaviour count as missing. Fewer than ten: -1 per missing.
            - Form (0-10): 10 = each theme in a fenced ```gherkin block with valid Given/When/Then, at least 12 scenarios, and a valid Mermaid stateDiagram whose states and transitions match the scenarios (including suspended and recovered paths). Gherkin inside Markdown table cells or prose: <= 4. Mermaid that would not render or contradicts the scenarios: <= 5.
        """.trimIndent(),
        referenceAnswer = """
            Ground truth from the context:
            - First failure at T (the renewal charge). Retries at T+1d, T+3d and T+7d, all measured from T. Three
              retries, four charge attempts in total.
            - Emails: one after the first failure (around T), one before the final attempt (before T+7d).
            - Suspension only after the third retry (T+7d) fails.
            - Never charge twice for one billing period; webhooks may be duplicated or out of order; operators see the
              retry history and can trigger a manual retry.

            A top answer states:
            - Scenarios in fenced ```gherkin blocks per theme, with concrete times (for example "Given the renewal
              failed at 2026-03-01T10:00Z, then retries are scheduled at 03-02, 03-04 and 03-08 10:00Z").
            - One idempotency key per subscription and billing period (for example subscriptionId + periodStart),
              reused by every automatic and manual attempt and passed to the provider; a manual retry while an
              automatic attempt is in flight is rejected or joined, and the provider deduplicates by key, so at most
              one charge succeeds per period. The period is marked paid on the first success and the remaining
              schedule is cancelled.
            - Webhooks are deduplicated by provider event id; state transitions are monotonic (a failure webhook
              arriving after a success for the same attempt or period is ignored).
            - A success webhook arriving after suspension (late settlement) reactivates the subscription for that
              period (or triggers a refund, if that is the stated policy); it is never ignored.
            - Whether a manual retry counts towards the three retries and whether it resets the schedule is stated.
            - The schedule is computed in UTC (or another stated zone) and the cut-off for "day" is defined.
            - Edge cases with expected behaviour: card updated during the window, card expires before a retry,
              customer cancels mid-window, refund pending, currency change, provider timeout without a webhook
              (attempt stays pending, reconciled by status query before the next attempt), retry day falls during a
              provider outage, duplicate webhook, out-of-order webhooks, late success after suspension, operator
              retries twice quickly.
            - Mermaid stateDiagram with states such as Pending, Succeeded, Failed, RetryScheduled (1..3), Suspended,
              Reactivated, and transitions matching the scenarios.

            Penalise:
            - Cumulative schedule (T+1, T+4, T+11) or retries counted from the previous retry.
            - Suspending after the third attempt counting the original charge, or after the first retry.
            - A new idempotency key per attempt (a manual retry racing the automatic one could charge twice).
            - "No double charge" stated without a mechanism.
            - Late success after suspension ignored or not mentioned.
            - Gherkin inside Markdown table cells; prose acceptance criteria instead of Given/When/Then.
            - Mermaid that does not parse, or a diagram whose states the scenarios never mention.
        """.trimIndent(),
        timeout = Duration.ofMinutes(15),
    )

    // ----- REVIEW -------------------------------------------------------------------------------

    private fun reviewKotlinDiff() = EvalTask(
        id = EvalTaskId.new(),
        name = "Review a Kotlin diff with planted bugs",
        category = TaskCategory.REVIEW,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.PROMPT,
        instructions = """
            Review the merge request diff in the context as a senior Kotlin/Spring reviewer. Output a table of
            findings with columns severity (BLOCKER/MAJOR/MINOR/NIT), file:line, title, explanation, suggested
            fix. Then give a verdict (APPROVE / COMMENT / REQUEST_CHANGES) with one paragraph of reasoning.
            There are real defects in this diff; find them. Do not pad the review with style remarks unless
            they matter.
        """.trimIndent(),
        context = REVIEW_DIFF,
        rubric = """
            - Correctness (0-10): finding the three MUST FIND defects of the reference answer, each with a correct explanation tied to the code: all three = 9-10; two = at most 6; one = at most 3; none of the three = 0, however many other findings there are. A MUST FIND defect named with a wrong explanation (for example "== on BigDecimal compares references" or "subList copies the list") counts as half.
            - Severity and verdict (0-10): 10 = severities proportionate (the pagination crash and the unsynchronized cache at least MAJOR, the BigDecimal equality at least MINOR with its effect on the 204 response stated) and verdict REQUEST_CHANGES with reasoning that follows from the findings. APPROVE: <= 2. COMMENT with any MUST FIND defect listed as MAJOR or above: <= 5.
            - Suggested fixes (0-10): 10 = correct, compilable Kotlin for each finding: signum() == 0 / compareTo (or lines.isEmpty()) for the empty check, an end index clamped with minOf / coerceAtMost(lines.size), ConcurrentHashMap with computeIfAbsent or a bounded cache (Caffeine, Spring Cache) for the cache. A fix that does not compile or does not remove the defect: -3 each.
            - Signal to noise (0-10): 10 = every finding is true for this code. Only false or irrelevant findings lower this score: -3 per KNOWN FALSE POSITIVE of the reference, -2 per other claim that is false for the shown code, -1 per pure style remark presented as a defect. Real extra findings (the REAL, NEUTRAL OR BONUS list of the reference) never lower this score.
        """.trimIndent(),
        referenceAnswer = """
            MUST FIND (the three planted defects; verified against the diff):
            1. `Statement.isEmpty` uses `total == BigDecimal.ZERO`. Kotlin `==` calls `BigDecimal.equals`, which compares
               scale; `total` is always `setScale(2, ...)`, so `0.00 == 0` is false and `isEmpty` is never true: the
               controller never answers 204 and returns 200 with an empty page. Fix: `total.signum() == 0` /
               `compareTo(BigDecimal.ZERO) == 0`, or better `lines.isEmpty()` (a statement whose lines net to zero is
               not empty).
            2. `page()` calls `statement.lines.subList(from, from + size)` without clamping the end index. Any page
               that is not full throws IndexOutOfBoundsException (HTTP 500): the last page, and with the default
               size 50 every statement with fewer than 50 lines. Fix: `subList(from, minOf(from + size, lines.size))`.
            3. `private val cache = HashMap<UUID, Statement>()` in a singleton `@Service`, read and written by
               concurrent requests without synchronisation (and iterated in `evict`): lost entries, corrupted map,
               ConcurrentModificationException. Fix: ConcurrentHashMap with computeIfAbsent, or a real cache
               (Caffeine / Spring Cache) with size and time bounds.
            Verdict: REQUEST_CHANGES.

            REAL, NEUTRAL OR BONUS (true observations; never penalise, credit as bonus when explained correctly):
            - The cache is never invalidated or bounded: `evict` exists but nothing calls it, so new orders never
              appear in a cached statement (stale data) and the map grows without limit (memory).
            - `cacheKey` and `evict` use only `customerId.mostSignificantBits`: two customers whose IDs share the high
              64 bits get the same key, so one customer can receive another customer's statement (data leak), and
              `evict` removes other customers' entries. Unlikely with random v4 UUIDs, realistic with time-based
              v1 / v7 UUIDs created close together. Fix: a data-class key (customerId, from, to).
            - Spring Data `Between` is inclusive on both ends and `toInstant` is the next day at 00:00 UTC, so an order
              placed exactly at next-day 00:00:00 appears in this statement and in the next one. Fix: a
              `GreaterThanEqual ... LessThan` query.
            - `toEur` returns EUR amounts unscaled while converted amounts are scaled to 2, so line amounts have
              inconsistent scale.
            - One `exchangeRates.rateToEur` call per order (N remote calls per statement); rates should be fetched
              once per currency, and the rate date is not the order date.
            - No `from <= to` validation (and no limit on the range length); `page * size` can overflow for a huge
              `page`.
            - Dates are interpreted in UTC only; the repository method takes a UUID for `Customer` (cannot be
              verified from the diff; fine if labelled as an assumption).

            KNOWN FALSE POSITIVES (penalise):
            - "StatementLine is not visible / not imported in StatementController": both files are in package
              `shop.statement` and the class is public.
            - "The XOR in cacheKey collides when from and to are swapped": `from.toEpochDay() shl 20` puts `from` above
              bit 20 and current epoch days (about 20 000) fit in 20 bits, so (a, b) and (b, a) give different keys.
            - "epochDay overflows in cacheKey": an epoch day of about 2^15 shifted by 20 bits is about 2^35, far
              below the Long range.
            - Any claim that `==` on BigDecimal compares references in Kotlin (it calls equals).
        """.trimIndent(),
        timeout = Duration.ofMinutes(10),
    )

    // ----- ARCHITECTURE -------------------------------------------------------------------------

    private fun monolithModuleBoundaries() = EvalTask(
        id = EvalTaskId.new(),
        name = "Propose module boundaries for a monolith",
        category = TaskCategory.ARCHITECTURE,
        difficulty = Difficulty.HARD,
        kind = TaskKind.PROMPT,
        instructions = """
            Propose a modular structure (bounded contexts / Gradle modules) for the monolith described in the
            context. For each module give: name, responsibility, owned aggregates, inbound and outbound ports,
            which other modules it may depend on, and the data it owns. Draw the dependency graph as Mermaid.
            Then describe a migration order: which seam to cut first and why, how to keep the system shippable
            after each step, and what to measure to know a step is done. Identify the two hardest cuts and the
            data ownership conflicts they create. Keep it to about 1 000 words plus the diagram; much longer
            answers lose points.
        """.trimIndent(),
        context = """
            A ten-year-old Kotlin/Spring monolith for a field-service company: work orders, technician scheduling,
            customer contracts and SLAs, parts inventory, invoicing, and a mobile API used by technicians.
            One PostgreSQL schema with ~180 tables; the `work_order` table is joined by almost everything;
            invoicing reads scheduling and parts tables directly; the mobile API has its own controllers but
            reuses the same JPA entities. Team: 6 developers, one release per two weeks, no message broker.
        """.trimIndent(),
        rubric = """
            - Boundaries (0-10): 10 = modules follow business capabilities (work orders, scheduling, contracts/SLA, parts inventory, invoicing) with the mobile API as a thin driving adapter, not a domain module; each owns its aggregates and tables; the Mermaid graph has no cycles and dependencies point from invoicing and the mobile adapter towards the core. A cycle in the graph (for example work orders <-> scheduling): <= 4. Modules by technical layer (controllers, services, repositories) or a "shared entities" module everyone uses: <= 3.
            - Ports and dependencies (0-10): 10 = inbound and outbound ports named per module; cross-module reads go through ports or published read models, other modules reference work orders by id only; the mobile API stops reusing JPA entities and calls application ports. Cross-module table reads or shared JPA entities left in the target design: <= 4.
            - Migration plan (0-10): 10 = the first cut is low-risk and justified: the mobile API as a thin adapter over application ports, or a leaf capability with few inbound joins (parts inventory, contracts/SLA); invoicing and work orders come late; the system ships every two weeks after each step (modular monolith in one deployable, no big bang); done criteria are measurable (ArchUnit or module tests green, zero cross-module table access in queries, per-module schema or grants). Invoicing or work orders as the first cut: <= 4. Splitting into microservices or introducing a broker as a prerequisite: <= 5. No measurable done criteria: <= 6.
            - Hard cuts (0-10): 10 = names the work_order table (joined by almost everything) and invoicing reading scheduling and parts tables directly as the two hardest cuts, describes the ownership conflict of each (who owns work order state, who owns billable time and parts usage) and a concrete resolution (work orders module owns the table and exposes a port or read model; invoicing consumes billable-work facts published by scheduling and inventory, in-process events or outbox tables since there is no broker). Hard cuts that contradict the migration order (calling a module both an easy first seam and one of the hardest cuts): <= 4.
        """.trimIndent(),
        referenceAnswer = """
            A top answer states:
            - Modules: Work Orders (WorkOrder aggregate, the work_order table), Scheduling (technician assignments,
              calendars), Contracts & SLA (contracts, SLA terms), Parts Inventory (parts, stock, usage), Invoicing
              (invoices, billing lines), and the Mobile API as a driving adapter over the application ports of the
              others (it owns no data). Each module owns its tables; others hold ids, not foreign-key joins.
            - Acyclic dependencies, for example: Contracts and Inventory are leaves; Work Orders -> Contracts;
              Scheduling -> Work Orders; Invoicing -> Work Orders, Scheduling, Inventory, Contracts (through ports or
              read models); Mobile API -> application ports of Work Orders, Scheduling, Inventory. Where two modules
              need each other (work orders and scheduling), one direction is a port call and the other an event.
            - First cut: the mobile API as a thin adapter (replace its reuse of JPA entities with calls to application
              ports; no data migration, immediate protection against schema leakage), or a leaf capability such as
              parts inventory or contracts with few inbound joins. Then scheduling; invoicing and work orders last,
              because they carry the hardest data coupling.
            - Shippable after every step: one deployable (modular monolith), Gradle modules plus ArchUnit or module
              tests, views or ports as transitional adapters, release every two weeks continues.
            - Done criteria per step: the module's ArchUnit rules pass, no query of another module joins its tables
              (checked by test or SQL inspection), its tables in its own schema or behind grants.
            - Hard cut 1: work_order is joined by almost everything; Work Orders owns it, others switch to a port or a
              read model (and denormalised copies they own) before the joins are removed.
            - Hard cut 2: invoicing reads scheduling and parts tables directly; resolution: scheduling and inventory
              publish billable facts (time worked, parts used) as in-process events persisted in outbox tables or
              expose query ports; invoicing keeps its own read model. No broker is needed for this.

            Penalise:
            - Invoicing proposed as a low-risk first seam (it depends on scheduling and parts data: it is one of the
              hardest cuts), or work orders first.
            - Cycles in the Mermaid graph.
            - The mobile API as a full domain module owning data.
            - Microservices, a separate database per service or a message broker as the first step for a 6-person
              team without a broker.
            - A shared "common entities" module that keeps the JPA entity coupling.
            - Big-bang rewrite, or steps after which the system cannot ship.
            - Done criteria that are not measurable ("cleaner code", "less coupling").
        """.trimIndent(),
        timeout = Duration.ofMinutes(15),
    )

    private fun writeAdr() = EvalTask(
        id = EvalTaskId.new(),
        name = "Write an ADR",
        category = TaskCategory.ARCHITECTURE,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.PROMPT,
        instructions = """
            Write an Architecture Decision Record in MADR format (Title, Status, Context, Decision drivers,
            Considered options, Decision outcome with consequences, Pros and cons of the options) for the
            decision described in the context. Consider at least three options, be concrete about the trade-offs
            for this team, and state the decision clearly. About 400-700 words; much longer answers lose
            Writing points.
        """.trimIndent(),
        context = """
            Decision: how to store and version large pipeline artefacts (specs, plans, diffs, logs; 10 KB to 5 MB
            each, ~200 per day, kept for 12 months, rarely read after a week) in a Kotlin/Spring Boot application
            deployed on Kubernetes with PostgreSQL. Options the team has floated: PostgreSQL bytea/text columns,
            S3-compatible object storage (MinIO available in the cluster), git repository per project.
            Constraints: single developer operating the system, backups must be simple, artefacts must be
            linkable from the UI with access control.
        """.trimIndent(),
        rubric = """
            - MADR structure (0-10): 10 = all requested sections present and used as intended (drivers are forces, not solutions; options described neutrally before the outcome), status set, at least three options, decision stated in one sentence at the start of Decision outcome. Missing Consequences or Pros and cons: <= 5. Fewer than three options: <= 6.
            - Analysis (0-10): 10 = puts a number on the volume (about 200/day x an assumed average size x 365, i.e. tens of GB per year, up to a few hundred GB if most artefacts are large) and draws its effect on PostgreSQL (database size, pg_dump/restore time, WAL and backup volume, deleting old rows for the 12-month retention); pros and cons are accurate: git has no per-artefact access control, keeps everything forever (retention needs history rewrites) and suits 5 MB logs poorly; object storage needs its own backup and an access path for the UI. No volume estimate: <= 5. A factual error about a store (for example "MinIO buckets are publicly readable so links work", "git deletes old blobs"): -3 each.
            - Decision quality (0-10): 10 = a clear decision that follows from the drivers, with negative consequences named: for object storage plus metadata in PostgreSQL, a second system to back up and keep consistent with the metadata (orphans, write order), access through the application or short-lived pre-signed URLs, retention by lifecycle rule; for a hybrid threshold, two code paths and two backup systems. Git chosen as the store: <= 3. PostgreSQL-only chosen without addressing backup/restore time and retention at this volume: <= 5. No negative consequences: <= 5.
            - Writing (0-10): 10 = concise, concrete, no marketing language, about 400-700 words. Much longer than about 700 words: <= 5. Generic text that would fit any project: <= 5.
        """.trimIndent(),
        referenceAnswer = """
            Ground truth from the context: 10 KB to 5 MB per artefact, about 200 per day, kept 12 months, rarely read
            after a week; Kubernetes with PostgreSQL; MinIO available; one operator; simple backups; links from the UI
            with access control.

            A top answer states:
            - Volume: 200/day x an assumed average (for example 0.5 MB) x 365 = about 36 GB per year (about 73 GB at
              1 MB average, about 365 GB if everything were 5 MB): tens of GB that would dominate the PostgreSQL
              database, slow pg_dump/restore and point-in-time recovery, inflate WAL and backups with data that is
              rarely read, and need bulk deletes (or partition drops) for retention.
            - Options: (1) PostgreSQL bytea/text, (2) S3-compatible object storage (MinIO) with metadata in
              PostgreSQL, (3) git repository per project; a hybrid threshold may be considered as a fourth.
            - Git rejected: no access control per artefact (repository-level only), history grows forever and
              12-month retention needs history rewrites, poor fit for multi-megabyte logs, extra service to operate.
            - Decision (preferred): object storage in MinIO for the content, a PostgreSQL row per artefact for
              metadata and authorisation (project, key, size, checksum, created at); the UI links to an application
              endpoint that checks access and streams or redirects to a short-lived pre-signed URL; a bucket
              lifecycle rule expires objects after 12 months.
            - Negative consequences named: a second system to back up (MinIO replication or mirror off the cluster),
              consistency between metadata rows and objects (write the object first, then the row; reconcile
              orphans), one more dependency for a single operator. A hybrid (small in PostgreSQL, large in MinIO) also
              means two code paths and two backup systems, which works against "backups must be simple".
            - PostgreSQL-only is a defensible alternative only if it quantifies the volume and handles retention (for
              example monthly partitions dropped after 12 months) and backup/restore time.

            Penalise:
            - No number for the yearly volume, or a wrong order of magnitude (MB or TB per year).
            - Git chosen, or git praised for "free versioning" without the access-control and retention problems.
            - Object storage chosen with public buckets or links that bypass access control.
            - A hybrid presented without naming two backup systems and two code paths.
            - Ignoring backup/restore impact of large binary data in PostgreSQL.
            - Missing negative consequences, marketing language ("blazing fast", "infinitely scalable").
        """.trimIndent(),
        timeout = Duration.ofMinutes(10),
    )

    // ----- CODING (runner jobs) -----------------------------------------------------------------

    /**
     * Spring PetClinic, the reference Spring Boot application, pinned to one commit so every benchmark works on the same
     * code: Java 17 (built on the runner's JDK 21), Maven wrapper, H2 in tests, about 90 green tests in about a minute.
     * The verify command runs the whole build: the repository's tests plus the hidden acceptance tests.
     */
    private fun codingRepository(acceptance: TaskAcceptance) = TaskRepository(
        url = PETCLINIC_REPO_URL,
        baseRef = PETCLINIC_COMMIT,
        verifyCommand = "./mvnw -B test",
        acceptance = acceptance,
    )

    private val PETCLINIC_CONTEXT = """
        Repository: Spring PetClinic (https://github.com/spring-projects/spring-petclinic, commit ${PETCLINIC_COMMIT.take(12)}),
        Spring Boot with Spring MVC + Thymeleaf, Spring Data JPA, Bean Validation and caching. Packages under
        `org.springframework.samples.petclinic`: `owner` (Owner, Pet, PetType, Visit and their controllers, OwnerRepository),
        `vet` (Vet, Specialty, VetController, VetRepository), `model` (BaseEntity, NamedEntity, Person), `system`.
        The schema is plain SQL, one file per database: `src/main/resources/db/{h2,mysql,postgres}/schema.sql` and
        `data.sql` (no Flyway; Hibernate does not create tables). Tests run on H2; MySQL and PostgreSQL tests are skipped
        without Docker. The build checks formatting (spring-javaformat, tabs) and `I18nPropertiesSyncTest` requires every
        new message key in all `messages*.properties` files.
    """.trimIndent()

    /**
     * ADR 0047: a PetClinic task stored under `starter/petclinic/<slug>/` on the classpath: `instructions.md`,
     * `rubric.md`, `reference.md`, `protected.txt` (pathspecs that must stay unchanged) and `hidden.txt` listing the
     * hidden acceptance files under `hidden/`. Each was validated: the base fails the hidden tests, a reference solution
     * passes them.
     */
    private fun petclinicTask(slug: String, name: String, difficulty: Difficulty, timeout: Duration): EvalTask {
        val files = lines(slug, "hidden.txt").associateWith { resource(slug, "hidden/$it") }
        return EvalTask(
            id = EvalTaskId.new(),
            name = name,
            category = TaskCategory.CODING,
            difficulty = difficulty,
            kind = TaskKind.CODING_JOB,
            instructions = resource(slug, "instructions.md").trim(),
            context = PETCLINIC_CONTEXT,
            rubric = resource(slug, "rubric.md").trim(),
            referenceAnswer = resource(slug, "reference.md").trim(),
            repository = codingRepository(TaskAcceptance(files, lines(slug, "protected.txt"), requireChanges = true)),
            timeout = timeout,
        )
    }

    private fun resource(slug: String, path: String): String =
        (StarterContent::class.java.getResourceAsStream("/starter/petclinic/$slug/$path")
            ?: error("starter resource missing: $slug/$path"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private fun lines(slug: String, path: String): List<String> =
        resource(slug, path).lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun codingAddEndpoint() =
        petclinicTask("owner-json-api", "Add a validated JSON endpoint for owners", Difficulty.EASY, Duration.ofMinutes(20))

    private fun codingVisitVet() =
        petclinicTask("visit-vet", "Record which vet handled a visit", Difficulty.MEDIUM, Duration.ofMinutes(25))

    private fun codingOptimisticLocking() =
        petclinicTask("owner-optimistic-locking", "Prevent lost updates on owner edits", Difficulty.HARD, Duration.ofMinutes(30))

    private fun codingRefactorHexagonal() =
        petclinicTask("vet-hexagonal", "Refactor the vet module to ports and adapters", Difficulty.EXPERT, Duration.ofMinutes(45))

    private fun codingRateLimiter() =
        petclinicTask("owner-rate-limiter", "Rate-limit owner form submissions", Difficulty.HARD, Duration.ofMinutes(30))

    private val REVIEW_DIFF: String = """
        Merge request !57: "Customer statement export" (Kotlin, Spring Boot 3.x)

        ```diff
        diff --git a/src/main/kotlin/shop/statement/StatementService.kt b/src/main/kotlin/shop/statement/StatementService.kt
        new file mode 100644
        --- /dev/null
        +++ b/src/main/kotlin/shop/statement/StatementService.kt
        @@ -0,0 +1,96 @@
        +package shop.statement
        +
        +import org.springframework.stereotype.Service
        +import java.math.BigDecimal
        +import java.math.RoundingMode
        +import java.time.Instant
        +import java.time.LocalDate
        +import java.time.ZoneOffset
        +import java.util.UUID
        +
        +data class StatementLine(val orderId: UUID, val placedAt: Instant, val description: String, val amount: BigDecimal)
        +
        +data class Statement(val customerId: UUID, val from: LocalDate, val to: LocalDate, val lines: List<StatementLine>, val total: BigDecimal) {
        +    val isEmpty: Boolean get() = total == BigDecimal.ZERO
        +}
        +
        +data class Page<T>(val items: List<T>, val page: Int, val size: Int, val totalItems: Int)
        +
        +@Service
        +class StatementService(
        +    private val orders: OrderRepository,
        +    private val exchangeRates: ExchangeRateClient,
        +) {
        +    private val cache = HashMap<UUID, Statement>()
        +
        +    fun statement(customerId: UUID, from: LocalDate, to: LocalDate): Statement {
        +        val key = cacheKey(customerId, from, to)
        +        cache[key]?.let { return it }
        +        val fromInstant = from.atStartOfDay().toInstant(ZoneOffset.UTC)
        +        val toInstant = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        +        val lines = orders.findByCustomerAndPlacedAtBetween(customerId, fromInstant, toInstant)
        +            .map { order ->
        +                StatementLine(
        +                    orderId = order.id,
        +                    placedAt = order.placedAt,
        +                    description = "Order ${'$'}{order.number}",
        +                    amount = toEur(order.total, order.currency),
        +                )
        +            }
        +            .sortedBy { it.placedAt }
        +        val total = lines.fold(BigDecimal.ZERO) { acc, line -> acc.add(line.amount) }.setScale(2, RoundingMode.HALF_UP)
        +        val statement = Statement(customerId, from, to, lines, total)
        +        cache[key] = statement
        +        return statement
        +    }
        +
        +    fun page(statement: Statement, page: Int, size: Int): Page<StatementLine> {
        +        require(page >= 0) { "page must not be negative" }
        +        require(size in 1..200) { "size must be within 1..200" }
        +        val from = page * size
        +        if (from >= statement.lines.size) return Page(emptyList(), page, size, statement.lines.size)
        +        val items = statement.lines.subList(from, from + size)
        +        return Page(items, page, size, statement.lines.size)
        +    }
        +
        +    fun evict(customerId: UUID) {
        +        cache.keys.filter { it.mostSignificantBits == customerId.mostSignificantBits }.forEach { cache.remove(it) }
        +    }
        +
        +    private fun toEur(amount: BigDecimal, currency: String): BigDecimal =
        +        if (currency == "EUR") amount else amount.multiply(exchangeRates.rateToEur(currency)).setScale(2, RoundingMode.HALF_UP)
        +
        +    private fun cacheKey(customerId: UUID, from: LocalDate, to: LocalDate): UUID =
        +        UUID(customerId.mostSignificantBits, (from.toEpochDay() shl 20) xor to.toEpochDay())
        +}
        diff --git a/src/main/kotlin/shop/statement/StatementController.kt b/src/main/kotlin/shop/statement/StatementController.kt
        new file mode 100644
        --- /dev/null
        +++ b/src/main/kotlin/shop/statement/StatementController.kt
        @@ -0,0 +1,38 @@
        +package shop.statement
        +
        +import org.springframework.http.ResponseEntity
        +import org.springframework.web.bind.annotation.GetMapping
        +import org.springframework.web.bind.annotation.PathVariable
        +import org.springframework.web.bind.annotation.RequestMapping
        +import org.springframework.web.bind.annotation.RequestParam
        +import org.springframework.web.bind.annotation.RestController
        +import java.time.LocalDate
        +import java.util.UUID
        +
        +@RestController
        +@RequestMapping("/api/customers/{customerId}/statement")
        +class StatementController(private val service: StatementService) {
        +
        +    @GetMapping
        +    fun statement(
        +        @PathVariable customerId: UUID,
        +        @RequestParam from: LocalDate,
        +        @RequestParam to: LocalDate,
        +        @RequestParam(defaultValue = "0") page: Int,
        +        @RequestParam(defaultValue = "50") size: Int,
        +    ): ResponseEntity<StatementResponse> {
        +        val statement = service.statement(customerId, from, to)
        +        if (statement.isEmpty) return ResponseEntity.noContent().build()
        +        val lines = service.page(statement, page, size)
        +        return ResponseEntity.ok(StatementResponse(statement.total, lines.items, lines.page, lines.size, lines.totalItems))
        +    }
        +}
        +
        +data class StatementResponse(
        +    val total: java.math.BigDecimal,
        +    val lines: List<StatementLine>,
        +    val page: Int,
        +    val size: Int,
        +    val totalItems: Int,
        +)
        ```
    """.trimIndent()
}
