# Code quality: the SonarQube gate (GR-Q3)

## When to use this guide

You maintain the Back Office repository and want to connect the pipeline to a SonarQube server, run the
same analysis on your laptop, or understand why the `quality:sonar` job is red. Decision: ADR 0027;
guardrail GR-Q3 in `docs/architecture/guardrails.md`; baseline numbers in
`docs/quality/sonarqube-baseline.md`.

## How it fits together

```
backend:test   ./gradlew test jacocoAggregatedReport   -> backend/build/reports/jacoco/aggregate/*.xml
frontend:test  npm run test:ci (ng test --coverage)    -> frontend/coverage/back-office-web/lcov.info
runner:test    coverage run -m unittest (.coveragerc)  -> build/coverage/python-coverage.xml
backend:detekt (optional)                              -> backend/*/build/reports/detekt/*.xml
        \__________________________ quality:sonar (stage quality) __________________________/
              ./gradlew sonar -Dsonar.qualitygate.wait=true   -> project `back-office`, gate "Back Office way"
```

One SonarQube project `back-office` holds everything: each Gradle module is a module of the project
(Kotlin), the root module spans the repository root and adds `frontend/src` (TypeScript) and the Python of
`runner/` and `ci/`. All settings live in `backend/build.gradle.kts` (block `sonar { }`); there is no
`sonar-project.properties`.

Excluded from analysis: build output, `node_modules`, `.angular`, the generated API types
`frontend/src/app/core/api/schema.d.ts` (GR-D1), translations `frontend/src/locale/**`, `frontend/public`,
`frontend/e2e` (screenshots, browser scripts). Test files: `*.spec.ts`, `runner/tests`, `ci/tests` and the
Gradle `test` source sets.

## The gate "Back Office way"

Created by `deploy/scripts/sonar-setup.sh` (idempotent; rerun after changing a value). Every condition
judges **new code** only - "Clean as you code": the existing code is the baseline, whatever you touch must
meet the bar.

| Condition (new code) | Default | Variable of `sonar-setup.sh` |
|---|---|---|
| Coverage | >= 70 % | `SONAR_GATE_NEW_COVERAGE` |
| Duplicated lines | <= 3 % | `SONAR_GATE_NEW_DUPLICATION` |
| Maintainability, reliability, security rating | A | `SONAR_GATE_NEW_RATING` |
| Security hotspots reviewed | 100 % | `SONAR_GATE_HOTSPOTS_REVIEWED` |
| New blocker / high (critical) issues | 0 | always; `SONAR_GATE_ZERO_NEW_ISSUES=true` fails on any new issue (strict "Sonar way") |

New code is "since the previous version" (`SONAR_NEW_CODE=PREVIOUS_VERSION`). `quality:sonar` sets the
project version to the last release tag (`git describe --tags`), so new code is everything since the
previous release; before the first tag it is everything since the first analysis. Alternatives: `NUMBER_OF_DAYS:30`, `REFERENCE_BRANCH:main`.
The script detects the metric set of the server (Multi-Quality Rule mode, default since 2025.1, or Standard
Experience) and uses the matching rating and severity metrics.

## Connect GitLab CI

1. On the SonarQube server, as an administrator (once):

   ```sh
   SONAR_HOST_URL=https://sonarqube.example.com SONAR_ADMIN_TOKEN=<admin user token> \
   SONAR_CREATE_TOKEN=true sh deploy/scripts/sonar-setup.sh
   ```

   The last line printed is a project analysis token; it is shown only once.
2. In GitLab, **Settings -> CI/CD -> Variables**:

   | Variable | Value | Flags |
   |---|---|---|
   | `SONAR_HOST_URL` | `https://sonarqube.example.com` | - |
   | `SONAR_TOKEN` | the analysis token | **Masked** (and *Protected* only if merge requests come exclusively from protected branches - unprotected MR pipelines do not see protected variables) |
   | `SONAR_BRANCH_ANALYSIS` | `true` with Developer Edition+ or SonarQube Cloud, otherwise leave unset | - |
   | `SONAR_ORGANIZATION` | SonarQube Cloud only: the organization key | - |

3. The next pipeline on the default branch contains `quality:sonar`.

Behaviour by mode:

- **`SONAR_HOST_URL` not set** - the job is not created; the pipeline is unchanged (forks, mirrors,
  installations without SonarQube). If it is set but `SONAR_TOKEN` is missing, the job fails with a
  message naming the missing variable.
- **Community Build** (`SONAR_BRANCH_ANALYSIS` unset): only the default branch is analysed. A merge request
  is judged after it is merged - the gate turns the main pipeline red and the fix is a follow-up MR. This is
  the price of the free edition.
- **Developer Edition+ / SonarQube Cloud** (`SONAR_BRANCH_ANALYSIS=true`): merge-request pipelines run the
  analysis with `sonar.pullrequest.key/branch/base`, the gate judges the MR's changes and SonarQube
  decorates the MR (configure the GitLab ALM integration in SonarQube for the comments).

## Run it locally

```sh
docker compose -f deploy/compose/docker-compose.yml --profile quality up -d sonarqube   # http://localhost:9100
```

Port 9100 on the host because MinIO already publishes 9000 (`SONAR_PORT` changes it). First start takes
about a minute; `docker ps` shows `healthy` when ready. If the container exits with
"vm.max_map_count is too low": `wsl -d docker-desktop sysctl -w vm.max_map_count=524288` (Windows) or
`sudo sysctl -w vm.max_map_count=524288` (Linux).

First login is `admin` / `admin` and SonarQube demands a new password. Do it in the browser, or:

```sh
curl -u admin:admin -X POST http://localhost:9100/api/users/change_password \
  --data-urlencode login=admin --data-urlencode previousPassword=admin --data-urlencode "password=<new>"
SONAR_ADMIN_USER=admin SONAR_ADMIN_PASSWORD=<new> SONAR_CREATE_TOKEN=true sh deploy/scripts/sonar-setup.sh
```

Then the analysis (Git Bash on Windows, with `JAVA_HOME` set to a JDK 21):

```sh
SONAR_TOKEN=<token> sh deploy/scripts/sonar-local.sh
# reuse earlier results: SKIP_BACKEND=true SKIP_FRONTEND=true SKIP_PYTHON=true
```

The script runs the backend tests with JaCoCo (about 15 minutes; failed tests only make coverage partial),
`npm run test:ci` with coverage, the Python tests with coverage.py when the `coverage` package is installed,
then `./gradlew sonar` and waits for the gate. PowerShell equivalent of the steps:

```powershell
$env:JAVA_HOME = "C:\Users\<you>\.jdks\ms-21.0.7"; $env:SONAR_TOKEN = "<token>"
cd backend; .\gradlew.bat test jacocoAggregatedReport --no-daemon --no-parallel --continue; cd ..
cd frontend; npm run test:ci; cd ..
python -m coverage run -m unittest discover -s runner/tests; python -m coverage xml -o build/coverage/python-coverage.xml
cd backend; .\gradlew.bat sonar --no-daemon "-Dsonar.host.url=http://localhost:9100" "-Dsonar.qualitygate.wait=true"
```

## Reading a red gate

- The job log ends with `QUALITY GATE STATUS: FAILED - View details on <url>`. Open the link: the project
  overview lists the failed conditions on *New Code*.
- **Coverage on new code** below the threshold: the *Coverage* drill-down, filtered to new code, shows the
  uncovered lines you added or changed. Add tests; untestable wiring belongs in
  `sonar.coverage.exclusions` only with a reason in the review.
- **New issues**: *Issues* tab, filter "New code". Fix them, or - for a false positive - mark the issue
  *Accept* / *False positive* in SonarQube with a comment (a reviewed decision, visible in the history), or
  suppress it in code with `@Suppress("kotlin:Sxxxx")` / `// NOSONAR` plus a justification. Findings
  that must stay accepted on every server (local and CI) go into `sonar.issue.ignore.multicriteria` in
  `backend/build.gradle.kts` (one rule, one file, a comment with the reason) and into "Accepted findings"
  of `docs/quality/sonarqube-baseline.md`.
- **detekt findings** (rules `external_detekt:*`) are imported when `backend:detekt` ran; a new
  `ForbiddenMethodCall` (GR-Q1) and other high-severity detekt findings in your change fail the gate even
  though `backend:detekt` itself is still `allow_failure`.
- **Security hotspots**: *Security Hotspots* tab; each one must be reviewed (Safe / Fixed / Acknowledged).
- **Duplication**: *Measures -> Duplications* on new code; extract the shared code.
- Coverage of 0 % for a whole area usually means a missing report: check that `backend:test`,
  `frontend:test` and `runner:test` produced their artefacts (paths above) and that the scanner log has no
  "Could not resolve N file paths in ..." warnings for your files.

Changing a threshold is a change to `deploy/scripts/sonar-setup.sh` (reviewed like code), followed by a
rerun of the script against the server.
