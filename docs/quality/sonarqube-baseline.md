# SonarQube baseline (GR-Q3)

The state of the code when the quality gate "Back Office way" was introduced (ADR 0027). The gate judges
**new code only**; the numbers below are the baseline that existing code is measured against, to be
improved whenever a file is touched. Re-measure with `deploy/scripts/sonar-local.sh`
(`docs/guides/code-quality.md`).

Run: 2026-10-07, SonarQube Community Build 26.9.0.129388 (compose profile `quality`), SonarScanner for
Gradle 7.5.0.8588, JaCoCo 0.8.15, Vitest 4.1 v8 coverage, coverage.py 7.16. Commit: master at 8902c24 plus
the GR-Q3 build changes. All backend tests green (blob-storage and the Keycloak login tests of
identity-adapters needed a rerun: their Testcontainers timed out while the machine was loaded), 417
frontend tests and 77 runner tests green.

## Quality gate

| Analysis | Gate | Note |
|---|---|---|
| first analysis | **PASSED** | no new code yet (new code = since the previous version); all 5 evaluated conditions OK |
| negative test (temporary condition overall `coverage < 90`) | **FAILED** | `QUALITY GATE STATUS: FAILED`, task `:sonar` failed, build red |
| after `sonar-setup.sh` restored the gate | **PASSED** | |

Judged on the whole code instead of new code, the gate would fail: security rating E (blocker issues
below), reliability rating D, 10 blocker and 97 critical (high) issues.

## Size and coverage

| Area | Files | Lines of code | Line coverage (SonarQube) |
|---|---|---|---|
| Backend, Kotlin (17 Gradle modules) | 340 | 33 205 | **91.2 %** |
| Frontend, TypeScript | 277 | 60 516 | **42.2 %** (Vitest reports 77.9 % of the lines of the files the tests load; files no test imports count as uncovered in SonarQube) |
| Python (`runner/entrypoint.py`, `ci/check-trivy-ignore.py`) | 2 | 1 637 | 60.1 % (`check-trivy-ignore.py` has no unit tests: 0 %) |
| Styles (LESS/CSS), HTML, JS | 7 | 988 | - |
| **Whole project** | 625 | 96 346 | coverage 70.7 % (line 74.1 %, branch 64.4 %) |

Duplicated lines: 0.1 %. Security hotspots: 0. Ratings (MQR mode): maintainability A, reliability D,
security E.

## Issues

SonarQube's own rules: **446** open issues.

| Severity | Count |
|---|---|
| Blocker | 10 |
| Critical | 97 |
| Major | 232 |
| Minor | 103 |
| Info | 4 |

By language: TypeScript 232, Kotlin 142, CSS 37, Python 33, JS 2. By software quality: maintainability
362, reliability 93, security 9.

Top 10 rules:

| Rule | Count | Name | Severity |
|---|---|---|---|
| typescript:S3358 | 60 | Ternary operators should not be nested | major |
| kotlin:S1192 | 39 | String literals should not be duplicated | critical |
| css:S8778 | 37 | "@import" rules should precede all other statements | major |
| kotlin:S6517 | 30 | Single function interfaces should be functional interfaces | major |
| typescript:S3776 | 21 | Cognitive Complexity of functions should not be too high | critical |
| kotlin:S107 | 21 | Functions should not have too many parameters | major |
| typescript:S8786 | 16 | Regular expressions should not cause non-linear backtracking | major |
| typescript:S7755 | 15 | Complex index access patterns should be replaced with ".at()" | minor |
| kotlin:S6619 | 15 | Null checks should be useful | major |
| kotlin:S3776 | 12 | Cognitive Complexity of functions should not be too high | critical |

The 10 blockers: 3 x `typescript:S6268` (Angular sanitisation bypassed in the help panel, help page and
markdown viewer - review that the HTML is sanitised before `bypassSecurityTrust*`), 5 x `typescript:S7649`
(aliased `input()` bindings), 2 x `typescript:S7651` (outputs named like DOM events). Security issues
also include `kotlin:S1313` (hard-coded IP ranges in `runner-controller` `ControllerProperties`, intended:
egress deny-list), `kotlin:S4790` (SHA-1 in `Totp.kt`, required by RFC 6238), `python:S5443` and
`python:S2068` in `runner/entrypoint.py` - candidates for "Accept" with a justification rather than code
changes.

detekt (imported as external issues from `backend:detekt`): **2 174** findings, led by `MaxLineLength`
1608, `UseOrEmpty` 105, `ForbiddenMethodCall` 78 (GR-Q1), `UnsafeCallOnNullableType` 69,
`TooGenericExceptionCaught` 47. They count towards "new issues" on new code like SonarQube's own issues.

## Accepted findings

Reviewed findings that stay in the code on purpose. They are suppressed in **repository configuration**
(`sonar.issue.ignore.multicriteria` in `backend/build.gradle.kts`, one rule in one file per entry, each
with a comment), so every SonarQube server - the local one and the one of CI - honours the same
decision. Adding a line here and there is a reviewed change; nothing is accepted by clicking in the UI.

| Rule | File | Reason | Reviewer | Date |
|---|---|---|---|---|
| `kotlin:S4790` (weak hash) | `backend/identity-core/.../domain/service/Totp.kt` | TOTP per RFC 6238 / HOTP RFC 4226: `Mac.getInstance("HmacSHA1")` with the user's 20-byte secret, dynamic truncation to 6 digits. Authenticator apps only implement HMAC-SHA1. SHA-1 is used as a keyed MAC (HMAC security does not rest on collision resistance), never as a hash of data. | owner decision | 2026-10-07 |
| `kotlin:S1313` (hard-coded IP) | `backend/runner-controller/.../config/ControllerProperties.kt` | `kubernetes.privateCidrs` (10/8, 172.16/12, 192.168/16, 169.254/16 incl. the cloud metadata endpoint 169.254.169.254, 100.64/10 CGNAT) is the egress **deny**-list: `EgressInfrastructure` renders it as the `except` of the proxy pods' NetworkPolicy `0.0.0.0/0` rule. Secure default, overridable by configuration; no address is an allow. | owner decision | 2026-10-07 |
| `python:S2068` (hard-coded password) | `runner/entrypoint.py` (`GitClient._cred_args`) | The string is the git credential-helper protocol (`username=oauth2` / `password=...`); the value comes at run time from the token file (`$RUNNER_GIT_TOKEN_FILE`) or `$GIT_TOKEN`. No secret is in the code. | owner decision | 2026-10-07 |
| `typescript:S6268` (Angular sanitisation bypassed) | `frontend/src/app/core/ui/safe-html.ts` | The single `bypassSecurityTrustHtml` of the app (`sanitizeToTrustedHtml`), used by `app-markdown`, the help panel and the manual page. It only receives DOMPurify output: explicit tag/attribute allow-lists, no `style`/`class`/`on*`/`data-*`, no script/iframe/object/embed/form/svg/math, URLs limited to http(s), mailto and scheme-less (relative, `/path`, `#anchor`), `target=_blank` forced to `rel="noopener noreferrer"`, `SANITIZE_NAMED_PROPS` against DOM clobbering (manual anchors become `user-content-<id>`). Content is treated as hostile (agent/LLM output, benchmark attempts, CI summaries). XSS payload tests in `safe-html.spec.ts`. | owner decision | 2026-10-07 |

Fixed instead of accepted (2026-10-07): the other two `S6268` (help panel, manual page and markdown
viewer now call the helper above), `typescript:S5852` in `core/help/manual-markdown.ts` (anchor regex
replaced by a linear scanner, adversarial-input test), `typescript:S7649` x5 (route inputs renamed to
their binding name, alias dropped), `typescript:S7651` x2 (outputs `cancelJob`, `dismissed`) and
`python:S5443` (the runner log lives in a fresh `tempfile.mkdtemp` directory with mode 0700 instead of a
predictable name in `/tmp`).

## After the review (2026-10-08)

Full analysis (backend tests with JaCoCo, frontend 446 tests with coverage, runner tests with
coverage.py) after the fixes and accepted findings above:

| Measure | Before | After |
|---|---|---|
| Quality gate (new code) | passed | **passed** (coverage on new code 84.6%, 0 new blocker/high issues) |
| Blocker issues | 10 | **0** |
| Open security issues | 12 | **0** (4 accepted in configuration, the rest fixed) |
| Security rating (overall) | E | **A** |
| Reliability rating (overall) | D | D (existing code; judged on new code only) |
| Coverage (overall) | 70.7% | 70.9% |
| Open issues (all severities) | 446 | 437 |

The gate failed once in between, as intended: the first version of the linear anchor scanner had a
cognitive complexity of 22 (`typescript:S3776`, high); it was split into `nameEnd`/`readValue` before
it could be merged.

### Frontend high-severity issues (2026-10-08)

The 37 high issues of the frontend were fixed in code, behaviour-preserving (454 frontend tests green,
i18n ids and texts unchanged, initial bundle 273.3 kB gzip):

| Rule | Count | Fix |
|---|---|---|
| `typescript:S3776` cognitive complexity | 21 | Extracted named pure helpers, early returns and lookup tables. New unit-tested modules: `features/runs/data/step-facts.ts` (step facts of the run page), `features/workflows/data/issue-groups.ts` (validation sheet groups). `validateLocally` (complexity 104) is now one check function per concern writing to an `IssueSink`; `narrowingIssues` / `narrow` / `compactPolicy` one helper per policy section; the palette actions a table of `when(permission, item)` entries. |
| `typescript:S2871` sort without comparator | 9 | `core/i18n/compare.ts`: `compareKeys` (code-unit order, for permission/role keys that go into payloads and equality checks) and `compareText` (collation in the UI language, for text shown to people, e.g. provider names). |
| `typescript:S7761` dataset | 3 | `element.dataset.*` instead of `get/setAttribute('data-*')`. |
| `typescript:S3735` void | 2 | Effects read their dependency signals as plain statements. |
| `typescript:S2004` nesting | 1 | `startRunItems()` in `layout/palette-providers.ts`. |
| `typescript:S7059` async in constructor | 1 | The help page loads the manual manifest in `ngOnInit`. |

Rule of thumb for new code: never call `sort()` without a comparator; keep functions under complexity
15 by moving decisions into `data/*.ts` helpers with tests.

### Backend and Python high-severity issues (2026-10-08)

The remaining high issues of the backend (Kotlin), the runner (`runner/entrypoint.py`) and the CI script
(`ci/check-trivy-ignore.py`) were fixed in code, behaviour-preserving (public API, JSON shapes, SQL, audit
codes and log formats unchanged):

| Rule | Count | Fix |
|---|---|---|
| `kotlin:S1192` duplicated literals | 40 | `const val` in the owning object/companion or a private file-level constant: role permission keys in `BuiltInRoles`, node ids/labels and one shared `REVIEW_APPROVED` branch in `DefaultWorkflows`, artifact/tag names in `BuiltInLibrary`, `SLUG_PATTERN` in `WorkflowDefinition.kt`, validation messages and field names (`MUST_NOT_BE_BLANK`, `MUST_BE_POSITIVE`, ...), media types, CI snippet lines, `--filter`, the cache `{path}` placeholder, `.upload-`, `I/O error`. |
| `kotlin:S3776` cognitive complexity | 12 (+2 from the prompt-injection commit) | Extracted private functions: `WorkflowValidator` one check per node kind (`validateTask`/`validateDecision`/`validateGate`/`validateWaitForCi`, `validateEdgeSource`, `modelRoutedPush`); `ResolveLoginService.matchUser`/`bindInvited`/`grantBootstrapAdmin`; `LlmProxy.admit`/`upstreamRequest`/`relay`; `BudgetService.precheckJob`/`precheckMonthly`; `PgNotificationListener.subscribe`/`dispatchUntilStopped`/`pauseBeforeReconnect`; `LangGraphWorkflowEngine.graphInput`/`failOnEngineError`; `JobSupervisor.leaseOnce`/`pauseAfterLeaseFailure`; `AdfFlattener` list/table/date renderers; `JsonSyntax.escape`/`integerPart`/`digits`; `TemplateValidator.resolveParent`; `LlmTaskExecutor.answerOf`/`reviewOf`/`guardedConfidence`/`producedArtifacts`; `PromptInjectionGuard.findingProblems`. New tests: `AdfFlattenerTest` (lists, tables, inline and block nodes), more `JsonSyntaxTest` cases. |
| `python:S3776` cognitive complexity | 8 | `main()` (159), the spec loader, the OpenCode/Claude Code event parsers, the stray-process sweep, artifact collection and `check-trivy-ignore.py` split into named helpers (`_parse_*`, `_load_*`, `_opencode_*`, `_claude_*`, `_sweep_once`, `_result_status`, ...); security hardening (secret files, non-dumpable, push refspec) unchanged. New `ci/tests/test_check_trivy_ignore.py`, more runner tests. |
| `python:S1192` duplicated literals | 4 | Module constants (`.claude`, `application/json`, `https://`, `.specify`). |

Fixed on the way so the gate stays green on new code: `python:S8786` (two `check-trivy-ignore.py`
regexes made linear), `typescript:S8786` (`format.ts` trailing-zero regex replaced by a scan), two
`typescript:S3776` (`groupIssues`, `buildTurnQueue`) and `text:S6389` (literal bidi/zero-width characters in
injection tests written as `\u` escapes).

Tests: backend 733 (all modules, ArchUnit included; the Testcontainers context test of `platform-runtime`
needed a rerun under load), frontend 454, runner 128 (1 skipped), ci 37.

| Measure | Before (2026-10-08 morning) | After |
|---|---|---|
| Open high issues | 101 | **0** (frontend, backend, runner, ci) |
| Blocker issues | 0 | 0 |
| Open security issues | 0 | 0 |
| Quality gate (new code) | passed | **passed** (coverage on new code 81.1%) |
| Coverage (overall) | 70.9% | 72.8% (line 76.2%, branch 66.4%; `entrypoint.py` 84%) |
| Open issues (all severities) | 437 | 323 |
| Reliability rating (overall) | D | C |
