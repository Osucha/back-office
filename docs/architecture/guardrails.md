# Build guardrails

A rule the build does not enforce does not exist in practice. This page is the registry of the rules
Back Office enforces automatically, where each one is checked and - for every rule - how to prove that
the check actually fails when the rule is broken (the negative test). A guardrail whose negative test
has never been run is not trusted. Decision: ADR 0020 (and ADR 0001/0017 for the architecture rules).

IDs: `GR-<group><n>` with A = architecture, S = security / supply chain, Q = code quality, D = contract.
IDs are stable: a retired rule keeps its ID with status *retired*, new rules get the next number.

## Registry

| ID | Rule | Enforced by | Negative test |
|---|---|---|---|
| GR-A1 | Hexagon layering: `domain` never depends on `application`, `application` never on `adapter`; controllers only in `..adapter.in..`, JPA entities only in `..adapter.out.persistence..` | ArchUnit `backend/app/src/test/kotlin/pl/mos/backoffice/app/ArchitectureTest.kt` (`domainDoesNotDependOnApplication`, `applicationDoesNotDependOnAdapters`, `controllersLiveInInboundAdapters`, `jpaEntitiesLiveInPersistenceAdapters`); `./gradlew :app:test`, CI `backend:test` | In any `*-core` module add to a `domain.model` class a property of a type from `application.port.in`; `:app:test --tests *ArchitectureTest` fails on `domainDoesNotDependOnApplication` |
| GR-A2 | No framework in core: `*-core` and `shared-kernel` import no Spring, JPA, Jackson, LangGraph, fabric8, gitlab4j | ArchUnit `sharedKernelIsFrameworkFree`, `coreModulesAreFrameworkFree` (same test class, CI `backend:test`) | Annotate a service in `projects-core` with `@org.springframework.stereotype.Service` (add `compileOnly` Spring if needed to compile); the ArchUnit rule fails |
| GR-A3 | No cross-context adapter imports: adapters of one bounded context never import another context; cores use only their own context + `shared-kernel` | ArchUnit `projectsAdaptersDoNotDependOnOtherContexts`, `evaluationAdaptersDoNotDependOnOtherContexts`, `tasksAdaptersDoNotDependOnOtherContexts`, `*CoreOnlyUsesOwnContextAndSharedKernel` | Import `pl.mos.backoffice.evaluation.domain.model.ModelProfile` from a class in `projects-adapters`; the corresponding rule fails |
| GR-A4 | Deny-by-default authorisation: every public use-case method calls `AuthorizationGate.require(...)` or is marked `@PublicOperation` / `@SystemOperation` | ArchUnit `DenyByDefault…` rule (identity work of iteration 3, ADR 0017; lives next to `ArchitectureTest`), CI `backend:test` | Add a new use-case interface + `*Service` without a `gate.require` call and without annotation; the DenyByDefault rule reports the method |
| GR-S1 | No secrets in the repository tree | TruffleHog `filesystem` scan, CI `security:secrets` (`--fail`, exclusions `ci/trufflehog-exclude.txt`, detectors JDBC/GitLab-v1 off, optional `TRUFFLEHOG_ONLY_VERIFIED=true`) | Plant a fake AWS key in a scratch directory and scan it with the CI flags: exit code 183 and `Detector Type: AWS` (performed, see below) |
| GR-S2 | No HIGH/CRITICAL CVE with an available fix in dependencies and images; every suppression has a statement and an expiry date | Trivy: CI `security:dependencies` (backend runtime classpath lockfiles, `frontend/package-lock.json`, `runner/`; CycloneDX SBOMs as artefacts), `security:images` (api/web/runner images on the default branch), `security:suppressions` (`ci/check-trivy-ignore.py`) | (a) Pin a known-vulnerable version, e.g. `"lodash": "4.17.15"` in a scratch `package-lock.json` / Gradle `implementation("org.yaml:snakeyaml:1.33")`, run the gate command: exit 1. (b) Add a suppression with `expired_at` in the past to a copy of `ci/trivy-ignore.yaml` and run `python3 ci/check-trivy-ignore.py <copy>`: exit 1 (performed, see below) |
| GR-S3 | CSRF protection on cookie sessions: unsafe methods with `BO_SESSION` need `X-XSRF-TOKEN`; bearer-token requests are exempt | Security integration tests in `identity-adapters` (`IdentityRestIntegrationTest` - "unsafe requests with a session need the CSRF header, bearer requests do not"), CI `backend:test` | Disable CSRF in the security filter chain (`csrf { disable() }`); the test expecting `403` with `code=csrf` fails |
| GR-S4 | Prompt-injection defences hold: untrusted text reaches models only in data blocks with a random boundary, an injection marker in a BLOCKING review never auto-passes, the coding agent cannot read the git token or post the job result, `decide_gate` over MCP needs the token opt-in (ADR 0029, docs/architecture/12-prompt-injection.md) | Injection suite: `PromptInjectionPrimitivesTest` (shared-kernel), `PromptInjectionSuiteTest` + `WorkflowValidatorTest` (projects-core), `SpelExpressionEvaluatorTest` + `AdfOutputTest` (projects-adapters), `JudgeInjectionTest` (evaluation-core), `McpInjectionDefencesTest` (governance-adapters), `McpServerIntegrationTest` (app), `RunnerProtocolIntegrationTest` (agent-runner), `LlmProxyIntegrationTest` (llm-gateway), `PodSpecRendererTest` (runner-controller), `runner/tests` injection classes; CI `backend:test`, runner tests | Remove the `findingsFor` guard findings in `LlmTaskExecutor` (or set the default `InjectionGuardMode` to OFF): `PromptInjectionSuiteTest` "malicious diff comment" fails because the complying model APPROVE passes (performed, see below); accept `JobTokenScope.GATEWAY` in `SandboxCallbackService.requireOwnJob` and `RunnerTokenFilter`: the agent-token test in `RunnerProtocolIntegrationTest` fails on the forged result |
| GR-Q1 | Time only through the injected `Clock`; no `println`/`System.out`, no `Thread.sleep` | detekt `ForbiddenMethodCall` in `backend/config/detekt/detekt.yml` (needs type resolution: `./gradlew detektMain detektTest`), CI `backend:detekt` (allow_failure until the backlog is fixed) | Put a file with `println`, `Thread.sleep(1)`, `Instant.now()` into a test source set and run `:<module>:detektTest`: it fails with one `ForbiddenMethodCall` per call, `Instant.now(clock)` passes (performed, see below) |
| GR-Q2 | Frontend layer rules: `features` do not import each other's internals, `core`/`shared` never import `features`, only `core/api` talks HTTP | dependency-cruiser config of the frontend, `npm run lint:deps`, CI `frontend:lint-deps` (allow_failure until the script exists) | Import a component of one feature from another feature (`features/runs/...` from `features/projects/...`); `npm run lint:deps` reports the violated rule and exits non-zero |
| GR-Q3 | New code passes the SonarQube quality gate "Back Office way": coverage >= 70 %, duplicated lines <= 3 %, maintainability/reliability/security rating A, no new blocker/high (critical) issue, 100 % security hotspots reviewed (thresholds in `deploy/scripts/sonar-setup.sh`) | SonarScanner for Gradle (`backend/build.gradle.kts`, one project `back-office`: Kotlin modules + `frontend/src` + Python of `runner/`, `ci/`), coverage from JaCoCo (`jacocoAggregatedReport`), Vitest v8 lcov (`npm run test:ci`), coverage.py; CI `quality:sonar` with `sonar.qualitygate.wait=true` (only when `SONAR_HOST_URL` is set; merge requests only with `SONAR_BRANCH_ANALYSIS=true`, ADR 0027, `docs/guides/code-quality.md`) | Add a condition the code cannot meet (e.g. overall `coverage LT 90`) to the gate and rerun `./gradlew sonar -Dsonar.qualitygate.wait=true`: `QUALITY GATE STATUS: FAILED`, task `:sonar` fails; rerun `sonar-setup.sh` to restore (performed, see below) |
| GR-D1 | The OpenAPI file `docs/api/openapi.yaml` is the source of the API contract; frontend types are generated from it, never hand-written | `npm run api:types` (openapi-typescript) in the web image build; CI `frontend:api-drift` regenerates and fails on a diff of `src/app/core/api/schema.d.ts` (allow_failure while iteration 3 changes the contract); frontend compile fails on use of fields the contract does not have | Remove a property from a schema in `openapi.yaml` that the UI uses, run `npm run api:types && npm run build`: TypeScript compilation fails; without regenerating, `frontend:api-drift` fails on the diff |
| GR-D2 | No source file is hidden by `.gitignore` (a `backend/**/out/` pattern once hid every `adapter/out` package, so clean clones did not build) | CI `repo:ignored-sources`: `git ls-files --others --ignored --exclude-standard` over `*/src/*`, `ci/`, `runner/`, `docs/` must be empty | Restore `backend/**/out/` in `.gitignore`: 46 source files become ignored and the job fails (performed 2026-09-27) |

## Status at the introduction of this registry (iteration 3)

| ID | Blocking in CI | Baseline |
|---|---|---|
| GR-A1..A3 | yes | green |
| GR-A4 | yes, once the identity module lands the rule | - |
| GR-S1 | yes | 0 findings on the tree (11 false positives resolved: 2 test fixtures marked `trufflehog:ignore`, build output `backend/**/bin`, downloaded subcharts excluded, JDBC/GitLab-v1 detectors off) |
| GR-S2 | yes | frontend `package-lock.json`: 0 fixable HIGH/CRITICAL; runner: no manifest (covered by the image scan); backend: lockfiles are written in CI only, first result there |
| GR-S3 | yes | green |
| GR-S4 | yes (introduced 2026-10-08, ADR 0029) | green |
| GR-Q1 | no (`allow_failure`, not in `check`) | 1252 detekt findings in 23 source sets: 955 `MaxLineLength` (140), 84 `UseOrEmpty`, 33 `UnsafeCallOnNullableType`, 27 `TooGenericExceptionCaught`, **23 `ForbiddenMethodCall` (15 `Instant.now()`, 8 `Thread.sleep`)**, rest < 20 each |
| GR-Q2 | no (`allow_failure`) until `lint:deps` exists | - |
| GR-Q3 | yes where `SONAR_HOST_URL` is set (introduced 2026-10-07, iteration 5) | gate on new code only; overall numbers in `docs/quality/sonarqube-baseline.md` |
| GR-D1 | no (`allow_failure`) | drift present at introduction (contract changed, types not regenerated) |
| GR-D2 | yes | clean after narrowing the rule to `backend/*/out/` |

Path to blocking detekt: fix the 23 `ForbiddenMethodCall` findings first (GR-Q1 proper), then either
reformat or raise `MaxLineLength`, then add `detektMain`/`detektTest` to `check` in
`backend/build.gradle.kts` and remove `allow_failure` from `backend:detekt`. A baseline file
(`detekt { baseline = ... }`) is the alternative if the cleanup has to wait - it freezes today's findings
and fails only on new ones.

## Negative tests performed

| ID | Date | What was done | Result |
|---|---|---|---|
| GR-S1 | 2026-09-27 | Generated a random-looking `AKIA…` access key id + 40-char secret into `settings.py` in a scratch directory outside the repository; `trufflehog filesystem . --fail --no-update --exclude-paths=ci/trufflehog-exclude.txt --exclude-detectors=JDBC,Gitlab.v1` (TruffleHog 3.97.9) | exit **183**, `Found unverified result`, `Detector Type: AWS`, `File: settings.py`, `Line: 2`. With `--only-verified` the same key passes (exit 0): the flag trades recall for precision, which is why it is off by default |
| GR-S2 (b) | 2026-09-27 | `ci/check-trivy-ignore.py` on a sample with one valid, one expired, one statement-less and one expiry-less entry | exit **1**, the three bad entries reported with file:line; the real `ci/trivy-ignore.yaml` passes (0 entries) |
| GR-Q1 | 2026-09-27 | Temporary `GuardrailNegativeProbe.kt` in `shared-kernel/src/test/kotlin` calling `println`, `System.out.println`, `Thread.sleep`, `Instant.now()`, `LocalDateTime.now()` and `Instant.now(clock)`; `./gradlew :shared-kernel:detektTest`; file deleted afterwards | task **FAILED**, "Analysis failed with 5 issues", one `ForbiddenMethodCall` per forbidden call with the GR-Q1 reason; `Instant.now(clock)` not reported |
| GR-Q3 | 2026-10-07 | Local SonarQube Community Build 26.9 (compose profile `quality`), gate "Back Office way" from `sonar-setup.sh` plus a temporary condition overall `coverage LT 90` (actual 70.7 %); `./gradlew sonar -Dsonar.qualitygate.wait=true`; condition removed by rerunning `sonar-setup.sh` | `QUALITY GATE STATUS: FAILED`, `Execution failed for task ':sonar'`, BUILD FAILED. After the restore the next analysis: `QUALITY GATE STATUS: PASSED` (baseline: `docs/quality/sonarqube-baseline.md`) |
| GR-S4 | 2026-10-08 | Temporarily replaced `guard.findingsFor(signals, guardMode)` by `emptyList()` in `LlmTaskExecutor` and ran `:projects-core:test --tests *PromptInjectionSuiteTest` | task **FAILED**, 4 of 11 tests red: "malicious diff comment" (the complying model APPROVE passed), "Trojan Source", "injected MR title", "FLAG/OFF"; file restored, suite green again |

Re-run the negative test of a rule whenever its tool, version or configuration changes (e.g. a
TruffleHog or detekt upgrade, a new exclusion) and add a row here.

## Adding a guardrail

1. Pick the next ID in the group and add a row above: rule in one sentence, the enforcing job/test, the negative test.
2. Wire it into CI; start with `allow_failure: true` only if existing code violates it, and record the baseline.
3. Run the negative test once and record it. A rule without a failing demonstration is not merged.
4. Mention the ID in the reason/message of the check (as the detekt reasons do) so a red build points here.
