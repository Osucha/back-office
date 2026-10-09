# 0027 - SonarQube quality gate on new code ("Clean as you code") for the whole repository

Status: accepted - 2026-10-07

## Context
The build guardrails (ADR 0020, `docs/architecture/guardrails.md`) cover architecture (ArchUnit), supply
chain (TruffleHog, Trivy), a few bans (detekt GR-Q1) and frontend layering (dependency-cruiser). Nothing
measured test coverage, duplication or the general maintainability/reliability/security of the code, and
nothing stopped a merge request from adding untested or duplicated code. Much of the code is written by
coding agents; a gate that judges every change the same way, independently of who wrote it, is the cheapest
safety net. The repository is polyglot (Kotlin modules, Angular/TypeScript, Python of `runner/` and `ci/`),
and the existing code has a backlog (1252 detekt findings at the introduction of GR-Q1) that cannot be
fixed before the gate is switched on.

## Decision
1. **SonarQube** (self-hosted Community Build, or a paid edition / SonarQube Cloud where available) analyses
   the repository as **one project `back-office`**: the Gradle modules as modules, the frontend and the Python
   in the root module whose base directory is the repository root. The scanner is the **SonarScanner for
   Gradle** (`org.sonarqube`, configured in `backend/build.gradle.kts`), so source sets, classpaths and
   module structure come from Gradle instead of a hand-maintained `sonar-project.properties`.
2. **Coverage from the existing test jobs**: JaCoCo agent on every backend `Test` task and one aggregated
   XML report (`jacocoAggregatedReport`; integration tests in `app` cover classes of other modules), Vitest
   v8 coverage as lcov from the Angular unit-test builder (`npm run test:ci` = `ng test --coverage`),
   coverage.py XML for Python (`.coveragerc`). detekt reports are imported as external issues when present.
3. **The quality gate "Back Office way" is code**: `deploy/scripts/sonar-setup.sh` creates the project and
   the gate through the Web API with configurable thresholds. All conditions judge **new code only**
   ("Clean as you code"): coverage >= 70 %, duplicated lines <= 3 %, maintainability, reliability and
   security rating A, no new blocker or high (critical) issue, 100 % of the security hotspots reviewed. The
   existing code is the baseline (`docs/quality/sonarqube-baseline.md`) and is improved when touched.
   New code = since the previous version (`PREVIOUS_VERSION`), configurable.
4. **CI job `quality:sonar`** (stage `quality`, after the test jobs) runs with `sonar.qualitygate.wait=true`,
   so a red gate fails the pipeline. It exists only when `SONAR_HOST_URL` is set (forks and installations
   without SonarQube stay green). Merge-request analysis (`sonar.pullrequest.*`) needs Developer Edition+ or
   SonarQube Cloud and is switched on with `SONAR_BRANCH_ANALYSIS=true`; with the Community Build only the
   default branch is analysed. Guardrail id **GR-Q3**.
5. **Local run**: a `sonarqube` service (profile `quality`) in `deploy/compose/docker-compose.yml` and
   `deploy/scripts/sonar-local.sh` reproduce the CI analysis on a laptop.

## Alternatives
- **SonarQube Cloud** (SaaS): same analysers, merge-request decoration and branch analysis included, no
  server to run. Rejected as the default because the code would leave the company network and the
  installation must also work fully on-premises; the configuration here works unchanged against SonarQube
  Cloud (`SONAR_HOST_URL=https://sonarcloud.io`, plus `sonar.organization`), so it stays an option.
- **JetBrains Qodana**: strong Kotlin inspections (IntelliJ engine) and a free community linter, quality
  gate in CI. Rejected: the Angular/TypeScript and Python coverage story is weaker in the free tier, the
  baseline/new-code model needs the paid Qodana Cloud for history, and there is no self-hosted dashboard
  comparable to SonarQube's in the free offering.
- **Only the existing linters (detekt, ESLint/Prettier, dependency-cruiser) plus coverage thresholds in
  the build**: no extra server. Rejected: no duplication detection, no security hotspots, no history and no
  new-code judgement - a global coverage threshold either fails on the existing code or is set so low that
  it never fails. The linters stay; detekt keeps its own bans (GR-Q1) and is imported into SonarQube.

## Consequences
- One more service to operate (SonarQube with its database) in environments that want the gate; without it
  the pipeline behaves as before.
- `backend:test` also writes JaCoCo data (a few percent slower), `frontend:test` measures coverage, and the
  `quality:sonar` job clones the full history (blame is needed to tell new lines from old ones).
- The Community Build judges a merge request only after it is merged into the default branch; teams that
  need the gate before merging need Developer Edition or SonarQube Cloud.
- The gate is only as strict as the thresholds in `sonar-setup.sh`; changing them is a reviewed change of
  this repository, not a click in the SonarQube UI.
