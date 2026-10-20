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

    /**
     * Spring PetClinic, the reference Spring Boot application, pinned to one commit so every benchmark works on the same
     * code: Java 17 (built on the runner's JDK 21), Maven wrapper, H2 in tests, about 90 green tests in 100 seconds.
     */
    private fun codingRepository() = TaskRepository(
        url = PETCLINIC_REPO_URL,
        baseRef = PETCLINIC_COMMIT,
        verifyCommand = "./mvnw -B -q test",
    )

    private val PETCLINIC_CONTEXT = """
        Repository: Spring PetClinic (https://github.com/spring-projects/spring-petclinic, commit ${PETCLINIC_COMMIT.take(12)}),
        Spring Boot with Spring MVC + Thymeleaf, Spring Data JPA, Bean Validation and caching. Packages under
        `org.springframework.samples.petclinic`: `owner` (Owner, Pet, PetType, Visit and their controllers, OwnerRepository),
        `vet` (Vet, Specialty, VetController, VetRepository), `model` (BaseEntity, NamedEntity, Person), `system`.
        The schema is plain SQL, one file per database: `src/main/resources/db/{h2,mysql,postgres}/schema.sql` and
        `data.sql` (no Flyway; Hibernate does not create tables). Tests run on H2; MySQL and PostgreSQL tests are skipped
        without Docker. Build with the Maven wrapper: `./mvnw -B -q test`. The build checks formatting
        (spring-javaformat, tabs) and `I18nPropertiesSyncTest` requires every new message key in all
        `messages*.properties` files: run `./mvnw -B -q spring-javaformat:apply` before you finish.
    """.trimIndent()

    private fun codingAddEndpoint() = EvalTask(
        id = EvalTaskId.new(),
        name = "Add a validated JSON endpoint for owners",
        category = TaskCategory.CODING,
        difficulty = Difficulty.EASY,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Add a small JSON API next to the existing HTML pages: `POST /api/owners` creates an owner from
            `{ "firstName", "lastName", "address", "city", "telephone" }` with the same validation rules as the
            `Owner` entity (telephone exactly 10 digits, required fields). Answer 201 with the created owner as JSON
            (id, the five fields, no pets) and a `Location: /api/owners/{id}` header; `GET /api/owners/{id}` returns it,
            404 when it does not exist. Validation errors answer 400 as RFC 9457 problem details listing each invalid
            field. Use a request DTO instead of binding the JPA entity, keep the existing HTML controllers unchanged,
            and add MockMvc tests for 201, 400 and 404. All existing tests must stay green.
        """.trimIndent(),
        context = PETCLINIC_CONTEXT,
        rubric = """
            - Correctness (0-10): POST answers 201 with Location and body, GET by id works, 404 for unknown ids, 400 problem details name each invalid field.
            - Tests (0-10): MockMvc tests cover 201, 400 (at least the telephone rule) and 404 with meaningful assertions on status, headers and body.
            - Fit (0-10): a separate REST controller and DTOs in the owner package, reuses OwnerRepository, no entity exposed as the request body, HTML flow untouched.
            - Hygiene (0-10): small focused diff, formatting applied, no unrelated changes or debug output.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(20),
    )

    private fun codingVisitVet() = EvalTask(
        id = EvalTaskId.new(),
        name = "Record which vet handled a visit",
        category = TaskCategory.CODING,
        difficulty = Difficulty.MEDIUM,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Visits do not say which vet handled them. Add an optional vet to a visit: a nullable `vet_id` column with a
            foreign key to `vets` in every schema.sql (H2, MySQL, PostgreSQL), a `@ManyToOne` `vet` on `Visit`, a vet
            select in the new-visit form (`pets/createOrUpdateVisitForm.html`, empty choice allowed) and the vet's
            name in the visits table of the owner details page (blank when none). Give a few existing visits a vet in
            the H2 `data.sql`. Tests: the form posts a visit with a vet and the page shows the name; a visit without a
            vet still works.
        """.trimIndent(),
        context = PETCLINIC_CONTEXT,
        rubric = """
            - Schema (0-10): nullable vet_id with a foreign key in all three schema files, consistent naming, sample data updated without breaking existing rows.
            - Mapping and UI (0-10): Visit maps the vet lazily or eagerly on purpose, the form offers the vets with an empty choice, owner details show the name.
            - Tests (0-10): controller tests cover a visit with and without a vet and assert what the page renders.
            - Hygiene (0-10): message keys added to every messages file, formatting applied, no unrelated changes.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(25),
    )

    private fun codingOptimisticLocking() = EvalTask(
        id = EvalTaskId.new(),
        name = "Prevent lost updates on owner edits",
        category = TaskCategory.CODING,
        difficulty = Difficulty.HARD,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Two receptionists editing the same owner silently overwrite each other: the last save wins. Add optimistic
            locking: a `version` column (every schema.sql, existing rows start at 0) and `@Version` on the entity, carried
            through the edit form as a hidden field. When the version is stale, re-show the form with a clear error
            ("changed by someone else, reload") instead of saving or answering 500. First write a test that
            reproduces the lost update on the current code and fails, then fix it; the test must not rely on sleeps.
            Explain the chosen approach and its limits in a short comment.
        """.trimIndent(),
        context = PETCLINIC_CONTEXT,
        rubric = """
            - Reproduction (0-10): a test shows the lost update on the original code (it would fail there) and passes after the fix, deterministically.
            - Fix (0-10): @Version on the entity, the version travels through the form, a stale save is rejected and reported on the form, never a 500.
            - Schema and data (0-10): version column added consistently to all schema files with a default, existing data keeps loading.
            - Explanation and hygiene (0-10): short accurate comment on the strategy and its limits, message keys in every messages file, focused diff.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(30),
    )

    private fun codingRefactorHexagonal() = EvalTask(
        id = EvalTaskId.new(),
        name = "Refactor the vet module to ports and adapters",
        category = TaskCategory.CODING,
        difficulty = Difficulty.EXPERT,
        kind = TaskKind.CODING_JOB,
        instructions = """
            The `vet` package mixes JPA entities, the Spring Data repository and the controller. Refactor it into
            ports and adapters: `vet.domain` (plain Java records or classes for Vet and Specialty, no Spring, JPA or
            Jackson imports), `vet.application` (a use case such as `ListVets` as an inbound port, `VetCatalog` as an
            outbound port, a service implementing the use case), `vet.adapter.in.web` (the existing controller) and
            `vet.adapter.out.persistence` (the JPA entities, the repository and a mapper). Keep the HTML page, the
            `/vets` JSON and XML resources and the caching behaviour identical: existing tests must pass unchanged.
            Add ArchUnit (`com.tngtech.archunit:archunit-junit5`, test scope) with a test that enforces the layering.
        """.trimIndent(),
        context = PETCLINIC_CONTEXT,
        rubric = """
            - Structure (0-10): the four layers exist with the right dependency direction, the domain is free of framework imports, ports are interfaces owned by the application layer.
            - Behaviour preservation (0-10): existing tests unchanged and green, /vets HTML, JSON and XML identical, caching still applied.
            - ArchUnit (0-10): a meaningful layering test that would fail on the original structure.
            - Craft (0-10): small explicit mappers, no JPA entity leaking past the persistence adapter, a reviewable diff.
        """.trimIndent(),
        repository = codingRepository(),
        timeout = Duration.ofMinutes(45),
    )

    private fun codingRateLimiter() = EvalTask(
        id = EvalTaskId.new(),
        name = "Rate-limit form submissions with tests",
        category = TaskCategory.CODING,
        difficulty = Difficulty.HARD,
        kind = TaskKind.CODING_JOB,
        instructions = """
            Protect the application from form spam: a token-bucket rate limiter per client IP for state-changing
            requests (POST to `/owners/**`). `RateLimiter.tryAcquire(key): Decision` returns `allowed`, `remaining` and
            `retryAfter`; capacity and refill rate come from application properties with defaults (20 requests, 1 per
            3 seconds). It must be thread-safe, evict buckets of clients that stopped calling and take an injectable
            clock so tests are deterministic. Wire it as a servlet filter answering 429 with `Retry-After`; GET requests
            and static resources are never limited. Tests: refill and burst with a fake clock, a concurrency test
            proving no over-admission, and a MockMvc test of the 429 path.
        """.trimIndent(),
        context = PETCLINIC_CONTEXT,
        rubric = """
            - Algorithm (0-10): correct token bucket (capacity cap, fractional refill, never negative), deterministic through the injected clock.
            - Concurrency and memory (0-10): thread-safe per-key state, idle buckets evicted and that eviction tested or clearly bounded.
            - Integration (0-10): only POST /owners/** limited, 429 with Retry-After, properties bound with defaults.
            - Tests (0-10): refill, burst, concurrency and web tests present and meaningful, no sleeps for timing.
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
