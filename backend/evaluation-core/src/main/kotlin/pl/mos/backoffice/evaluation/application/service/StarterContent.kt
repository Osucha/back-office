package pl.mos.backoffice.evaluation.application.service

import pl.mos.backoffice.evaluation.domain.model.Capability
import pl.mos.backoffice.evaluation.domain.model.CodingScoreWeights
import pl.mos.backoffice.evaluation.domain.model.Difficulty
import pl.mos.backoffice.evaluation.domain.model.EvalTask
import pl.mos.backoffice.evaluation.domain.model.EvalTaskId
import pl.mos.backoffice.evaluation.domain.model.ModelProfile
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
import java.time.Duration
import java.time.Instant

/**
 * Starter catalogue and suite. Prices are USD per million tokens as published in 2026-06; the owner
 * maintains them afterwards. Coding tasks point at a placeholder repository that must be replaced.
 */
object StarterContent {

    const val PLACEHOLDER_REPO_URL = "https://gitlab.example.com/samples/kotlin-sample.git"
    const val STARTER_SUITE_NAME = "Starter: SDLC tasks"

    private const val PRICE_NOTE = "Prices in USD per million tokens, exact list prices as of 2026-06. Verify against the provider price list before relying on cost figures."
    private const val PLACEHOLDER_NOTE = "Pricing is a placeholder (0/0): verify current pricing on the provider price list and update this profile."
    private const val REPO_NOTE = "This task runs against the placeholder repository $PLACEHOLDER_REPO_URL, which does not exist. Point `repository.url` at a real Kotlin/Gradle sample repository the runner can clone (a small Spring Boot service with a green `./gradlew test` is ideal) before starting a benchmark."

    fun modelProfiles(now: Instant): List<ModelProfile> = listOf(
        profile(
            "Claude Fable 5.1", Provider.ANTHROPIC, "claude-fable-5-1", Reasoning.HIGH,
            Pricing(BigDecimal("10"), BigDecimal("50"), cacheReadPerMTok = BigDecimal("0.25")),
            setOf(Capability.CODING, Capability.REASONING, Capability.LONG_CONTEXT, Capability.TOOLS, Capability.VISION),
            200_000, "Frontier model, default judge candidate. $PRICE_NOTE", now,
        ),
        profile(
            "Claude Opus 5", Provider.ANTHROPIC, "claude-opus-5", Reasoning.HIGH,
            Pricing(BigDecimal("5"), BigDecimal("25"), cacheReadPerMTok = BigDecimal("0.5")),
            setOf(Capability.CODING, Capability.REASONING, Capability.LONG_CONTEXT, Capability.TOOLS, Capability.VISION),
            200_000, PRICE_NOTE, now,
        ),
        profile(
            "Claude Sonnet 5", Provider.ANTHROPIC, "claude-sonnet-5", Reasoning.MEDIUM,
            Pricing(BigDecimal("2"), BigDecimal("10")),
            setOf(Capability.CODING, Capability.REASONING, Capability.TOOLS, Capability.VISION),
            200_000, PRICE_NOTE, now,
        ),
        profile(
            "Claude Haiku 4.5", Provider.ANTHROPIC, "claude-haiku-4-5", Reasoning.MEDIUM,
            Pricing(BigDecimal("1"), BigDecimal("5")),
            setOf(Capability.CODING, Capability.TOOLS, Capability.CHEAP),
            200_000, PRICE_NOTE, now,
        ),
        profile(
            "GPT-5", Provider.OPENAI, "gpt-5", Reasoning.MEDIUM,
            Pricing.FREE,
            setOf(Capability.CODING, Capability.REASONING, Capability.TOOLS, Capability.VISION),
            400_000, PLACEHOLDER_NOTE, now,
        ),
        profile(
            "Gemini 2.5 Pro", Provider.GOOGLE, "gemini-2.5-pro", Reasoning.MEDIUM,
            Pricing.FREE,
            setOf(Capability.CODING, Capability.REASONING, Capability.LONG_CONTEXT, Capability.TOOLS, Capability.VISION),
            1_000_000, PLACEHOLDER_NOTE, now,
        ),
        profile(
            "Mistral Large", Provider.MISTRAL, "mistral-large-latest", Reasoning.MEDIUM,
            Pricing.FREE,
            setOf(Capability.CODING, Capability.TOOLS),
            128_000, PLACEHOLDER_NOTE, now,
        ),
        profile(
            "Qwen3 32B (Ollama, local)", Provider.OLLAMA, "qwen3:32b", Reasoning.MEDIUM,
            Pricing.FREE,
            setOf(Capability.CODING, Capability.CHEAP),
            32_000, "Runs on the local Ollama server (OLLAMA_BASE_URL). No token cost; hardware time is not accounted.", now,
        ),
    )

    private fun profile(
        name: String,
        provider: Provider,
        modelId: String,
        reasoning: Reasoning,
        pricing: Pricing,
        capabilities: Set<Capability>,
        contextWindow: Int,
        notes: String,
        now: Instant,
    ) = ModelProfile.create(
        displayName = name,
        ref = ModelRef(provider, modelId, ModelOptions(reasoning = reasoning, maxOutputTokens = 8_192)),
        pricing = pricing,
        capabilities = capabilities,
        contextWindow = contextWindow,
        notes = notes,
        now = now,
    )

    fun starterSuite(now: Instant): TaskSuite = TaskSuite.create(
        name = STARTER_SUITE_NAME,
        description = "Twelve example tasks across analysis, specification, review, architecture and coding. " +
            "Replace them with your own tickets and repositories; the five CODING_JOB tasks reference a placeholder repository that must be changed before use.",
        codingScoreWeights = CodingScoreWeights.DEFAULT,
        tasks = listOf(
            rootCauseFailingTest(),
            compareIntegrationApproaches(),
            specFromTicket(),
            paymentRetryAcceptanceCriteria(),
            reviewKotlinDiff(),
            monolithModuleBoundaries(),
            writeAdr(),
            codingAddEndpoint(),
            codingFixConcurrencyBug(),
            codingRefactorHexagonal(),
            codingFlywayAndJpa(),
            codingRateLimiter(),
        ),
        now = now,
    )

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
            OrderTotalsTest > total applies percentage discount once() FAILED
                org.opentest4j.AssertionFailedError:
                expected: 90.00
                 but was: 81.00
                    at OrderTotalsTest.total applies percentage discount once(OrderTotalsTest.kt:27)
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
            - Root cause (0-10): identifies that the GOLD discount is applied twice (per line and again on the sum), not a rounding or scale problem.
            - Fix (0-10): removes one of the two applications (preferably the per-line one or the final one consistently) and keeps HALF_UP scaling; compiles as Kotlin.
            - Additional test (0-10): a test with two or more lines, or a non-GOLD customer, that distinguishes single from double application.
            - Discipline (0-10): no invented code, assumptions labelled, concise.
        """.trimIndent(),
        referenceAnswer = "The 10 % GOLD discount is applied on every line and then again on the order sum, giving 100 * 0.9 * 0.9 = 81. Apply the policy once (on the sum, or per line) and add a test with several lines and a mixed-tier scenario.",
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
            Keep it under 700 words.
        """.trimIndent(),
        context = """
            Team of one architect-developer, Kotlin/Spring Boot services on Kubernetes, PostgreSQL per service,
            no broker in production today, RabbitMQ available in the platform catalogue. Order volume ~2 000/day,
            inventory service owned by another team with 99.5 % availability. Business tolerates a reservation
            appearing up to 30 seconds after the order but not double reservations or lost reservations.
        """.trimIndent(),
        rubric = """
            - Completeness (0-10): all requested comparison rows present and technically correct.
            - Reasoning (0-10): recommendation follows from the constraints in the context (tolerated delay, no double or lost reservations, small team, broker not yet in production) and names the trade-offs of the choice.
            - Safety mechanisms (0-10): names the transactional outbox (or equivalent) for B, or timeouts/retries/compensation and idempotency keys for A; explains why each is needed.
            - Clarity (0-10): table renders in Markdown, within the word limit, no filler.
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
            Turn the ticket in the context into a Spec Kit feature specification (spec.md). Use these sections:
            Overview, User scenarios (Given/When/Then), Functional requirements (numbered FR-1..), Key entities,
            Non-functional requirements, Out of scope, Open questions (mark each with [NEEDS CLARIFICATION]).
            Requirements must be testable and implementation-agnostic: no framework, database or endpoint
            names. Aim for 5-10 functional requirements and at least 3 open questions that a product owner
            would need to answer.
        """.trimIndent(),
        context = """
            Ticket SHOP-482: "Customers should be able to save a shopping cart for later and get it back on another
            device. Carts saved for more than 30 days can be dropped."
            Product: B2C web shop, customers log in with email, carts today live in the browser session only.
        """.trimIndent(),
        rubric = """
            - Structure (0-10): every requested section present and used correctly; scenarios in Given/When/Then.
            - Requirement quality (0-10): FRs are testable, unambiguous, implementation-agnostic; covers save, restore on another device, expiry after 30 days, conflict when the current device also has a cart.
            - Open questions (0-10): at least three genuine product decisions (e.g. multiple saved carts, what happens to prices and out-of-stock items on restore, anonymous users) each tagged [NEEDS CLARIFICATION].
            - Scope discipline (0-10): out-of-scope section keeps the feature small; no invented features presented as requirements.
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
               state changes, limits, operator visibility). At least 12 scenarios.
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
            - Coverage (0-10): schedule (+1/+3/+7), both emails, suspension, no double charge, manual retry and operator view are all covered by scenarios.
            - Edge cases (0-10): at least ten realistic edge cases with a stated expected behaviour; includes duplicate/out-of-order webhooks and card update during the window.
            - Precision (0-10): scenarios are unambiguous and testable; idempotency key or equivalent named; time zone / cut-off handling stated.
            - State diagram (0-10): valid Mermaid, states and transitions consistent with the scenarios.
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
            - Defect detection (0-10): finds the three planted defects: (1) BigDecimal compared with == against ZERO (scale-sensitive; 0.00 is not equal to 0), (2) pagination subList end index not clamped to list size (IndexOutOfBoundsException on the last page), (3) unsynchronized mutable HashMap cache in a singleton service used concurrently. Each missed defect costs 3 points.
            - Severity and reasoning (0-10): severities are proportionate (the cache and the pagination crash are at least MAJOR), explanations are correct and specific to the code.
            - Suggested fixes (0-10): fixes are correct Kotlin (compareTo/signum, coerceAtMost, ConcurrentHashMap or a cache abstraction).
            - Signal to noise (0-10): no invented issues, verdict consistent with findings (REQUEST_CHANGES expected).
        """.trimIndent(),
        referenceAnswer = "Three defects: `total == BigDecimal.ZERO` should use `signum() == 0` or `compareTo`; `subList(from, from + size)` must clamp the end to `items.size`; `cache` is a plain HashMap mutated from a singleton bean and needs ConcurrentHashMap (or Caffeine) plus computeIfAbsent. Verdict: REQUEST_CHANGES.",
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
            data ownership conflicts they create. Under 1 000 words plus the diagram.
        """.trimIndent(),
        context = """
            A ten-year-old Kotlin/Spring monolith for a field-service company: work orders, technician scheduling,
            customer contracts and SLAs, parts inventory, invoicing, and a mobile API used by technicians.
            One PostgreSQL schema with ~180 tables; the `work_order` table is joined by almost everything;
            invoicing reads scheduling and parts tables directly; the mobile API has its own controllers but
            reuses the same JPA entities. Team: 6 developers, one release per two weeks, no message broker.
        """.trimIndent(),
        rubric = """
            - Boundaries (0-10): modules follow business capabilities (e.g. work orders, scheduling, contracts/SLA, inventory, invoicing, mobile as a thin adapter), each with clear ownership of aggregates and data; no cyclic dependencies in the graph.
            - Ports and dependencies (0-10): inbound/outbound ports named per module; cross-module reads go through ports or published read models, not shared tables.
            - Migration plan (0-10): a realistic order (typically invoicing or mobile adapter first as low-risk seams, work orders last), shippable after each step, with measurable done criteria (e.g. no direct table access across modules enforced by ArchUnit).
            - Hard cuts (0-10): correctly identifies the work_order coupling and invoicing reading scheduling/parts as hardest; describes the data ownership conflict and a resolution (read model, event, or explicit shared kernel).
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
            for this team, and state the decision clearly. 400-700 words.
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
            - MADR structure (0-10): all sections present and used as intended; status set; options clearly separated from the decision.
            - Analysis (0-10): decision drivers reflect the context (volume, retention, single operator, backups, access control); pros and cons are accurate for PostgreSQL, object storage and git.
            - Decision quality (0-10): a clear decision with consequences (including negative ones) and a sensible threshold or hybrid if chosen (e.g. small artefacts in PostgreSQL, large in object storage) justified from the drivers.
            - Writing (0-10): concise, no marketing language, within the length limit.
        """.trimIndent(),
        timeout = Duration.ofMinutes(10),
    )

    // ----- CODING (runner jobs) -----------------------------------------------------------------

    private fun codingRepository() = TaskRepository(
        url = PLACEHOLDER_REPO_URL,
        baseRef = "main",
        verifyCommand = "./gradlew test",
    )

    private fun codingAddEndpoint() = EvalTask(
        id = EvalTaskId.new(),
        name = "Add a validated REST endpoint",
        category = TaskCategory.CODING,
        difficulty = Difficulty.EASY,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Add `POST /api/customers` to this Spring Boot service. Request body: `{ "email": string, "displayName": string }`.
            Rules: email must be a syntactically valid address and unique (409 on duplicate), displayName 2-80
            characters after trimming. Response 201 with `{ "id": uuid, "email", "displayName", "createdAt" }` and a
            `Location` header. Validation errors return RFC 9457 problem details with a per-field `errors` array.
            Follow the existing package layout and naming. Add unit tests for validation and a web-layer test
            for the 201, 400 and 409 cases. All existing tests must stay green.
        """.trimIndent(),
        context = REPO_NOTE,
        rubric = """
            - Correctness (0-10): endpoint behaves as specified (201 + Location, 400 with field errors, 409 on duplicate email).
            - Tests (0-10): validation unit tests and web-layer tests for all three status codes exist and are meaningful.
            - Fit (0-10): follows the repository conventions (packages, DTO naming, error handling) instead of introducing a parallel style.
            - Hygiene (0-10): small focused diff, no unrelated changes, no dead code or debug output.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(20),
    )

    private fun codingFixConcurrencyBug() = EvalTask(
        id = EvalTaskId.new(),
        name = "Fix a concurrency bug with a failing test",
        category = TaskCategory.CODING,
        difficulty = Difficulty.HARD,
        kind = TaskKind.CODING_JOB,
        instructions = """
            The `StockReservationService` in this repository loses reservations under concurrent load: two
            parallel reservations for the last unit can both succeed. First write a test that reproduces the
            problem deterministically (for example with a CountDownLatch and two threads, or a repeatable
            multi-threaded loop that asserts the invariant `reserved <= available`). Confirm it fails. Then fix the
            service without a global lock: use optimistic locking with a version column or a database-level
            constraint plus retry, whichever fits the existing persistence code. Explain the chosen strategy in
            a short comment. All tests must pass.
        """.trimIndent(),
        context = REPO_NOTE,
        rubric = """
            - Reproduction (0-10): a test exists that fails on the original code and passes after the fix; it exercises real concurrency, not a sequential simulation.
            - Fix quality (0-10): the fix guarantees the invariant (optimistic locking with retry, atomic conditional update, or unique constraint); no `synchronized` on a singleton as the sole fix in a multi-instance deployment.
            - Explanation (0-10): the strategy and its limits (e.g. retry count, behaviour under contention) are documented briefly and accurately.
            - Hygiene (0-10): focused diff, no test disabled, no flaky sleeps as the synchronization mechanism.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(30),
    )

    private fun codingRefactorHexagonal() = EvalTask(
        id = EvalTaskId.new(),
        name = "Refactor to hexagonal ports with tests green",
        category = TaskCategory.CODING,
        difficulty = Difficulty.EXPERT,
        kind = TaskKind.CODING_JOB,
        instructions = """
            The `order` package mixes domain logic, JPA entities and REST controllers. Refactor it into a
            hexagonal structure: `domain` (pure Kotlin aggregates and value objects), `application` (use-case
            interfaces as inbound ports, repository/gateway interfaces as outbound ports, services implementing
            the use cases), `adapter.in.rest` and `adapter.out.persistence` (JPA entities and mappers). The
            domain must not import Spring, JPA or Jackson. Preserve the public REST contract byte for byte
            (existing web tests must pass unchanged). Add an ArchUnit test that enforces the layering. Keep
            behaviour identical; this is a pure refactoring.
        """.trimIndent(),
        context = REPO_NOTE,
        rubric = """
            - Structure (0-10): the four layers exist with the right dependency direction; domain free of framework imports; ports are interfaces owned by the application layer.
            - Behaviour preservation (0-10): existing tests unchanged and green; REST contract identical; no logic silently altered.
            - ArchUnit (0-10): a layering test exists, is meaningful (not trivially passing) and would fail on the original structure.
            - Craft (0-10): mappers are explicit and small, no leaked JPA entities into domain or DTOs, no god-classes; the diff is reviewable.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(45),
    )

    private fun codingFlywayAndJpa() = EvalTask(
        id = EvalTaskId.new(),
        name = "Add a Flyway migration and JPA mapping",
        category = TaskCategory.CODING,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Add persistence for `AuditEvent(id: UUID, occurredAt: Instant, actor: String, action: String,
            subjectType: String, subjectId: String, details: JSON object)` to this service. Deliverables: a Flyway
            migration following the repository numbering convention (table `audit_event`, `timestamptz`,
            `jsonb` details, index on `(subject_type, subject_id)` and on `occurred_at`), a JPA entity using the
            project's JSON mapping approach (or Hibernate `@JdbcTypeCode(SqlTypes.JSON)`), a Spring Data
            repository with `findBySubject(subjectType, subjectId, pageable)` ordered newest first, and a
            repository test against PostgreSQL (Testcontainers, as the existing tests do) that round-trips an
            event with nested JSON details. Hibernate schema validation must pass.
        """.trimIndent(),
        context = REPO_NOTE,
        rubric = """
            - Migration (0-10): correct types (uuid PK, timestamptz, jsonb), both indexes, follows the version numbering and naming used in the repository.
            - Mapping (0-10): entity matches the table so `ddl-auto=validate` passes; JSON mapped with the project approach; timestamps are UTC Instants.
            - Repository and test (0-10): query returns newest first with paging; Testcontainers test round-trips nested JSON and asserts on it.
            - Hygiene (0-10): no generated schema edits by hand outside Flyway, no unrelated changes.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(25),
    )

    private fun codingRateLimiter() = EvalTask(
        id = EvalTaskId.new(),
        name = "Implement a rate limiter with tests",
        category = TaskCategory.CODING,
        difficulty = Difficulty.HARD,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Implement a per-API-key token-bucket rate limiter for this service: `RateLimiter.tryAcquire(key: String): Decision`
            where `Decision` carries `allowed`, `remaining` and `retryAfter`. Configuration: capacity and refill
            rate per second from application properties, sensible defaults (60 requests, 1 per second refill).
            The implementation must be thread-safe, must not leak memory for keys that stop calling (evict idle
            buckets), and must use an injectable time source so tests are deterministic. Wire it as a servlet
            filter on `/api/**` that returns 429 with `Retry-After` and `X-RateLimit-Remaining` headers. Tests:
            unit tests for refill math and burst behaviour with a fake clock, a concurrency test proving no
            over-admission, and a web test for the 429 path.
        """.trimIndent(),
        context = REPO_NOTE,
        rubric = """
            - Algorithm (0-10): token bucket implemented correctly (capacity cap, fractional refill, no negative tokens), deterministic through an injected clock.
            - Concurrency and memory (0-10): thread-safe per-key state (atomic update or per-bucket lock), idle eviction present and tested or clearly bounded.
            - Integration (0-10): filter applied to /api/** only, 429 with correct headers, configuration properties bound with defaults.
            - Tests (0-10): refill, burst, concurrency and web tests present and meaningful; no sleeps for timing.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(30),
    )

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
