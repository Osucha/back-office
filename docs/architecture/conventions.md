# Conventions

## Backend (Kotlin)

- Package root: `pl.mos.backoffice`. Context packages: `pl.mos.backoffice.projects`,
  `pl.mos.backoffice.evaluation`, `pl.mos.backoffice.tasks`, `pl.mos.backoffice.identity`, `pl.mos.backoffice.shared`, `pl.mos.backoffice.llm`, `pl.mos.backoffice.app`.
- Layering inside a core module:
  - `<ctx>.domain.model` — aggregates, entities, value objects (immutable `data class`es, behaviour as methods returning new instances or applying validated mutations)
  - `<ctx>.domain.event` — domain events
  - `<ctx>.domain.service` — pure domain services (e.g. `PipelineDefinitionValidator`)
  - `<ctx>.application.port.in` — use-case interfaces, one interface per use case, command/query objects as nested data classes
  - `<ctx>.application.port.out` — driven ports (repositories, gateways)
  - `<ctx>.application.service` — use-case implementations (`*Service`), plain Kotlin classes, no annotations
- Adapters module:
  - `<ctx>.adapter.in.rest` — controllers + DTOs + mappers (`*Controller`, `*Dto`, `*Mapper`); controllers call ports only
  - `<ctx>.adapter.in.sse`, `<ctx>.adapter.in.webhook`, `<ctx>.adapter.in.runner` (callbacks)
  - `<ctx>.adapter.out.persistence` — JPA entities (`*Entity`), Spring Data repositories (`*JpaRepository`), port implementations (`*PersistenceAdapter`) and mappers
  - `<ctx>.adapter.out.<system>` — `langgraph`, `k8s`, `gitlab`, `jira`
  - `<ctx>.adapter.config` — `@Configuration` classes that instantiate use-case services from ports
- Core modules have **no** Spring, JPA, Jackson or LangGraph dependencies. ArchUnit tests in `app` fail the build otherwise.
- Errors: domain throws `DomainException` subclasses from `shared-kernel` (`NotFound`, `InvalidState`, `Validation`); the REST layer maps them to RFC 9457 problem details (404/409/400).
- IDs: `@JvmInline value class XId(val value: UUID)` with `companion fun new()`.
- JSON: Jackson 3 (`tools.jackson`) with the Kotlin module; DTOs are Kotlin data classes.
- Persistence: Spring Data JPA, Hibernate 7, JSONB via `@JdbcTypeCode(SqlTypes.JSON)`; Flyway migrations live in each adapters module under `src/main/resources/db/migration`:
  - `V1__*` shared (usage ledger, LangGraph checkpoint tables are created by LangGraph4j itself)
  - `V100__*`…`V199__*` projects context
  - `V200__*`…`V299__*` evaluation context
  - `V300__*`…`V399__*` task library context
  - `V400__*`…`V499__*` identity & access context (`V400__identity.sql`, `V401__spring_session.sql` = official Spring Session JDBC schema)
  - `V500__*`…`V549__*` platform runtime (`platform-runtime`: `V500__platform_runtime.sql` = work_item, event_log, shedlock, platform_instance; ADR 0023)
  - `V550__*`…`V599__*` governance context (`V550__attestations.sql`, ADR 0025)
  - Flyway runs with `out-of-order: true` because a new migration in one context is numerically lower than migrations already applied by another; never edit an applied migration, always add a new one in your range.
- Tests: JUnit 5 + MockK for core (fast, no Spring); `@SpringBootTest` + Testcontainers PostgreSQL for adapters; ArchUnit for layering. Test names read as sentences: `` fun `run waits for human at a gated step`() ``.
- Logging: SLF4J, structured key=value in messages; never log prompt contents at INFO.
- Time: inject `Clock` port; never call `Instant.now()` in core.

## Frontend (Angular)

- Standalone components, signals, `inject()`; no NgModules.
- Feature folders under `src/app/features/<feature>/` with `pages/`, `components/`, `data/` (API services generated from OpenAPI types).
- API types are generated from `docs/api/openapi.yaml` with `openapi-typescript` into `src/app/core/api/schema.d.ts`; hand-written thin services wrap `HttpClient`.
- NG-ZORRO for all UI; no other component libraries. Layout: left sider navigation, top header with pending-approvals badge.
- State: signals in feature-level services; SSE stream via `EventSource` in `core/events`.

## Helm / infrastructure

- Chart `deploy/helm/back-office`, apiVersion v2, semver `version`, `appVersion` equals the image tag.
- All secrets come from a pre-existing Secret (`existingSecret`) or from `secrets.*` values for non-production.
- Images: `back-office/api`, `back-office/web`, `back-office/runner`; tags are git SHAs, `latest` is never deployed.

## Documentation

- ADRs follow the MADR template, numbered `NNNN-title.md`, status `accepted|superseded|rejected`.
- Every guide starts with "When to use this guide" and ends with "Where to look when it fails".
- Diagrams are ASCII or Mermaid so they render in GitLab and in the terminal.
