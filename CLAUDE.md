# Back Office — instructions for coding agents

Read `docs/README.md` first. Architecture is hexagonal and enforced by ArchUnit; decisions are in `docs/adr`.

## Layout

- `backend/` Kotlin 2.3, Spring Boot 4.1, Gradle multi-module. Modules: `shared-kernel`, `projects-core`,
  `projects-adapters`, `evaluation-core`, `evaluation-adapters`, `tasks-core`, `tasks-adapters`, `identity-core`,
  `identity-adapters`, `governance-core`, `governance-adapters` (signing, attestations, insights, MCP server;
  docs/architecture/11-governance.md), `llm-gateway`, `agent-runner`, `platform-runtime` (work queue, ShedLock,
  event outbox, roles; ADR 0023), `blob-storage` (S3-compatible / filesystem BlobStore), `app`,
  plus the separately deployed `runner-controller` (execution plane, ADR 0024; talks to Back Office only over HTTP).
- `frontend/` Angular 21 + NG-ZORRO, standalone components, signals. API types generated from `docs/api/openapi.yaml`.
- `runner/` container image and `entrypoint.py` for coding agents (OpenCode, Claude Code, Spec Kit).
- `deploy/helm/back-office` Helm chart; `deploy/compose` local docker-compose; `ci-templates/` GitLab CI code-review gate.

## Commands

```bash
# backend (JDK 21 required; set JAVA_HOME to it if `java` on PATH is older)
cd backend && ./gradlew test --no-daemon              # all modules incl. ArchUnit + Testcontainers (Docker needed)
cd backend && ./gradlew :app:bootRun --args="--spring.profiles.active=local"
# frontend
cd frontend && npm ci && npm run api:types && npm run build && npm test -- --watch=false
# runner
python3 -m unittest discover -s runner/tests
# code quality (GR-Q3, docs/guides/code-quality.md): SonarQube on :9100, coverage + scan + gate
docker compose -f deploy/compose/docker-compose.yml --profile quality up -d sonarqube
SONAR_TOKEN=... sh deploy/scripts/sonar-local.sh      # gate as code: deploy/scripts/sonar-setup.sh
# chart
cd deploy/helm/back-office && helm dependency build && helm lint . && helm template x . -f values-dev.yaml
```

## Rules that the build enforces

1. `*-core` and `shared-kernel` modules contain no Spring, JPA, Jackson, LangGraph, fabric8 or gitlab4j imports.
2. `domain` never depends on `application`; `application` never depends on `adapter`.
3. Adapters of one bounded context never import the other context. Cross-context access goes through ports in
   `shared-kernel` (`LlmGateway`, `UsageLedger`, `ModelCatalog`, `TaskCatalog`, `AgentRunner`, `Clock`, `EventPublisher`).
4. Every public use-case method calls `AuthorizationGate.require(...)` first or is annotated `@PublicOperation` /
   `@SystemOperation` (`DenyByDefaultArchitectureTest`); mutations record an `AuditEntry`.
5. Controllers live in `..adapter.in..`; JPA entities in `..adapter.out.persistence..` (or in `runner`/`llm` modules).
6. SonarQube gate "Back Office way" on NEW code (CI `quality:sonar`, ADR 0027): coverage >= 70 %, duplication <= 3 %,
   ratings A, no new blocker/critical issue, hotspots reviewed. Write tests with new code; suppress a rule only
   with a justification (`@Suppress("kotlin:Sxxxx")` + comment).

## Conventions (short form; full text in `docs/architecture/conventions.md`)

- Use cases: one interface per use case in `application.port.in`, implementation `*Service` in
  `application.service`, instantiated in `<ctx>.adapter.config`. No Spring annotations in core.
- Errors: throw `NotFoundException` / `InvalidStateException` / `ValidationException` / `IntegrationException`
  from `shared-kernel`; the app maps them to RFC 9457.
- Flyway version ranges: V1–49 llm-gateway, V50–99 agent-runner, V100–199 projects, V200–299 evaluation,
  V300–399 tasks, V400–499 identity, V500–549 platform, V550–599 governance (Flyway runs out-of-order; only the
  `migrate` role and the `local` profile migrate).
- ArchUnit exception: `governance.adapter.in.mcp` (+ its wiring in `governance.adapter.config`) is a driving adapter
  that may import the `application.port.in` packages (and returned domain types) of projects and tasks, like `app`.
- `/mcp` (MCP server) accepts personal access tokens only; it is in the authenticated paths and CSRF exemptions of
  `IdentitySecurityConfiguration`.
- The OpenAPI file in `docs/api/openapi.yaml` is the contract. Change it first, then backend and frontend.
- Every ADR-worthy change gets a new record in `docs/adr` (MADR style, numbered).

## Environment quirks on the owner's Windows machine

- `java` on PATH is 1.8; always export `JAVA_HOME` to the JDK 21 above before Gradle.
- The Claude Code Bash tool fails on any single quote in a command (even inside quoted heredocs). Create files
  with the Write/Edit tools, not heredocs.
- Do not run two Gradle builds concurrently in `backend/` (Kotlin daemon lock errors: "Daemon compilation failed").
