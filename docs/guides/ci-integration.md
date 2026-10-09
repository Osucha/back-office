# CI integration: `backoffice-ci.sh`

## When to use this guide

You want your repository's CI (GitLab CI, GitHub Actions or anything that can run `sh`, `curl` and
`git`) to call Back Office: gate merge requests with an AI code review, start any configured workflow
from a pipeline, or report test results to a workflow that is waiting for them. Back Office never
calls your SCM (ADR 0013); every interaction starts from your CI with a token issued by Back Office.
The client is one POSIX shell script served by the platform itself. This guide replaces the
iteration-1 "GitLab code-review job" guide.

## How it fits together

```
your repository CI                                   Back Office
─────────────────────────────                        ─────────────────────────────────────────
backoffice-ci review  ──POST /api/v1/ci/executions──▶ entrypoint `code-review` → workflow run
      │  (CI context + MR diff)                        │ code-review task, DECISION, END(outcome)
      │◀─ poll GET /api/v1/ci/executions/{id} ─────────┘ gateResult PASS | FAIL
      └─ exit 0 / 1

agent runner ──git push -o merge_request.create──▶ GitLab opens the MR; commits carry
                                                    the trailer `Back-Office-Run: <runId>`
your test job ──backoffice-ci signal tests──POST /api/v1/ci/signals──▶ resumes the WAIT_FOR_CI node
```

## 1. Issue a project CI token

In Back Office open the project, go to **CI** (entrypoints and tokens) and create a token. Creating,
listing and revoking tokens needs the project permission `project.ci.manage` (project roles `owner` and
`maintainer`); each token records who created it (`createdBy`) and creation and revocation are audited
(`CI_TOKEN_CREATE`, `CI_TOKEN_REVOKE`). The plaintext `bo_ci_...` is shown once; the platform stores only
its SHA-256. A token holds exactly `ci.execute` and `ci.signal` on that one project: it can start
executions of the project's entrypoints, send signals and poll its own executions, and nothing else (it
cannot read the project, its runs or other projects). People cannot call the CI start and signal
endpoints with their session (403): `ci.*` permissions are granted only to tokens. Revoke a token from the
same page; issue one token per repository or per CI system so revocation is targeted.

On the same page map **entrypoints** to workflows: `code-review -> "CI code review"` with mode
`BLOCKING` (the job fails on a failed gate) or `ADVISORY` (the job only reports). The gate result is
the outcome of the workflow's `END` node, so any workflow can be a gate.

## 2. Set the CI variables

| Variable | Required | Value |
|---|---|---|
| `BACKOFFICE_URL` | yes | base URL of Back Office as reachable from your runners, e.g. `https://back-office.example.com` |
| `BACKOFFICE_CI_TOKEN` | yes | the `bo_ci_...` token (Masked, not Protected - see below) |
| `BACKOFFICE_REVIEW_TIMEOUT` | no | seconds to wait for a run (default `1800`) |
| `BACKOFFICE_POLL_INTERVAL` | no | seconds between polls (default `10`) |
| `BACKOFFICE_ADVISORY` | no | `true` never fails the job, whatever the entrypoint mode or error |
| `BACKOFFICE_DIFF_MAX_BYTES` | no | cap for the uploaded diff (default `2097152`; larger diffs are truncated with a marker line) |

GitLab: *Settings > CI/CD > Variables*, visibility **Masked** (or **Masked and hidden**) for the token and
leave **Protect variable** unticked: GitLab passes protected variables only to pipelines of protected
branches and tags, and the review job runs in merge request pipelines of feature branches, so it would
get no token. GitHub: repository or environment secrets.

## 3. GitLab: include the template

```yaml
include:
  - project: 'mos/back-office'        # the project hosting this repository
    ref: main
    file: '/ci-templates/backoffice.gitlab-ci.yml'
  # or: - remote: 'https://gitlab.example.com/mos/back-office/-/raw/main/ci-templates/backoffice.gitlab-ci.yml'

variables:
  BACKOFFICE_TESTS_JOB: "test"        # informational; see the signal jobs below

# optional overrides
backoffice:review:
  variables:
    BACKOFFICE_ADVISORY: "true"       # report only
  # allow_failure: { exit_codes: [1] }  # tolerate FAIL but still fail on transport errors (2) / timeouts (3)
```

The template downloads the script from `$BACKOFFICE_URL/ci/backoffice-ci.sh` at job start (no
checksum: the script is versioned with the platform you are already trusting; a `ts=` query avoids
stale proxy copies) and defines three jobs:

| Job | Runs when | Does |
|---|---|---|
| `backoffice:review` | merge request pipelines, stage `test` | `backoffice-ci review` |
| `backoffice:signal-tests` | branch pipelines whose HEAD carries the `Back-Office-Run` trailer, stage `.post`, `when: on_success` | `signal tests --status success` |
| `backoffice:signal-tests-failed` | same, `when: on_failure` | `signal tests --status fail` |

The signal jobs exit 0 without calling anything when HEAD has no trailer, so they are harmless on
branches humans push. To signal right after your own test job instead of at the end of the pipeline,
override `needs`:

```yaml
backoffice:signal-tests:
  needs: [test]
backoffice:signal-tests-failed:
  needs: [test]
```

Use merge request pipelines (the template's `rules` rely on `CI_PIPELINE_SOURCE == "merge_request_event"`)
and keep `GIT_DEPTH` at `0` for the review job (the template sets it) so the merge base with the target
branch is available for the diff.

## 4. What `review` does

`backoffice-ci review` is `backoffice-ci run code-review --wait --diff auto`:

1. Detects the **CI context** from `CI_*` (GitLab) or `GITHUB_*` variables (plus the event payload on
   GitHub): provider, project path, repository URL, pipeline/job ids and URL, ref, commit, default
   branch, actor and, for merge requests, `iid`, title, description, source/target branch, URL and
   author. Outside a known CI it reports `OTHER` and reads the commit, the branch and the `origin`
   remote (credentials stripped) from git. See "Projects with several repositories" below.
2. Computes the **diff**: for merge requests `git fetch origin <target>` then
   `git diff --merge-base origin/<target> HEAD` (falls back to `origin/<target>...HEAD`,
   `CI_MERGE_REQUEST_DIFF_BASE_SHA`, then a two-dot diff); on a plain branch the merge-base diff
   against the default branch; on the default branch itself the last commit. The diff is capped at
   `BACKOFFICE_DIFF_MAX_BYTES` (cut on a line boundary, marker appended) and `changedFiles` is the
   `git diff --name-only` list.
3. `POST /api/v1/ci/executions` with `{entrypoint, context, diff, changedFiles, variables}`. The
   platform is idempotent per `(project, entrypoint, commitSha, mergeRequest.iid)` for 24 h: retrying
   the job or re-running the pipeline returns the same execution instead of reviewing again
   (`--var force=true` forces a new run).
4. Polls `GET /api/v1/ci/executions/{id}` every `BACKOFFICE_POLL_INTERVAL` seconds until `SUCCEEDED`
   or `FAILED` (or the timeout), then prints the report:

```
================================ BACK OFFICE ================================
Execution: 3f1c...  entrypoint: code-review  project: SHOP
Status:    SUCCEEDED
Gate:      FAIL (mode BLOCKING)
Verdict:   REQUEST_CHANGES (score 62)
Cost:      0.42 USD
Run:       https://back-office.example.com/runs/9c0e...

Summary:
  The change adds the cart endpoint but bypasses the pricing service ...

Findings:
  [BLOCKER] src/cart/CartController.kt:88 - Price computed client-side
  [MAJOR] src/cart/CartService.kt:41 - Missing transaction boundary
  [NIT] README.md:3 - Typo in heading
=============================================================================
Gate FAIL
```

The full report with details and suggestions is on the run page linked as `Run:`. Nothing is posted
to the merge request by the platform (it has no SCM access); print the job log or post a note from
your own CI if you want it on the MR.

### Projects with several repositories

A project can have several repositories (ADR 0034), for example `service` and `docs`, each with its own CI
using the same project token. Script 2.1.0 sends `context.repositoryUrl`: GitLab `CI_PROJECT_URL`, GitHub
`$GITHUB_SERVER_URL/$GITHUB_REPOSITORY`, otherwise the `origin` remote without credentials. Back Office maps
it to a project repository by normalised URL (scheme, credentials, `.git` and a trailing slash ignored, host
case-insensitive, `git@host:group/proj` = `https://host/group/proj`):

- the execution records the repository key (shown on the CI execution) and the run gets the parameter
  `repository` = key, usable as `input.parameters['repository']` in decisions and prompts;
- an unknown URL is refused with HTTP 400 that lists the project's repositories (`key: url`): add the
  repository to the project or fix its URL;
- an entrypoint can be restricted to one repository (**Repository** column on the CI tab): executions from
  another or an unknown repository are refused with 400, so the documentation repository cannot start the
  source repository's review by mistake;
- older scripts (before 2.1.0) send no URL; Back Office matches `projectPath` against the end of the
  repository URLs and otherwise runs the execution without a repository. The template downloads the script
  from the platform at job start, so pipelines using it get 2.1.0 automatically.

## 5. Commands and exit codes

| Command | Behaviour |
|---|---|
| `run <entrypoint> [--wait] [--diff auto\|none] [--var k=v]...` | start the workflow mapped to `<entrypoint>`; without `--wait` prints the execution id and exits 0 |
| `review` | `run code-review --wait --diff auto` |
| `signal <name> --status success\|fail [--run <id>] [--message "..."]` | resume the run waiting on `WAIT_FOR_CI` signal `<name>`; the run id comes from `--run`, else from the `Back-Office-Run` trailer of `HEAD`, else the run is matched by branch/commit |
| `status <executionId>` | print one execution |
| `version`, `help` | |

| Exit code | Meaning |
|---|---|
| `0` | `gateResult == PASS`, or the entrypoint mode is `ADVISORY`, or `BACKOFFICE_ADVISORY=true`; for `signal`: delivered (resumed or nothing waiting) |
| `1` | `gateResult == FAIL` (or the execution `FAILED` without a gate) in `BLOCKING` mode |
| `2` | transport, authentication (`401`/`403`), unknown entrypoint (`404`) or usage error |
| `3` | `BACKOFFICE_REVIEW_TIMEOUT` elapsed before the run finished |

Only `curl`, `git`, `sh` and `awk` are needed (no `jq`); the token is passed to `curl` through a
private config file and never printed.

## 6. Signals from a test job (workflows that wait for CI)

A workflow with a `WAIT_FOR_CI` node (signal `tests`, for example) pauses after the implementation
task pushed a branch. Every commit the runner makes carries the trailer `Back-Office-Run: <runId>`,
and the branch push triggers your pipeline as usual. At the end of your tests:

```yaml
# GitLab, by hand instead of the template jobs
test:
  script: ./gradlew test
  after_script:
    - |
      if git log -1 --format=%B | grep -q "^Back-Office-Run:"; then
        curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
        if [ "$CI_JOB_STATUS" = "success" ]; then s=success; else s=fail; fi
        sh backoffice-ci.sh signal tests --status "$s" --message "$CI_JOB_URL"
      fi
```

`signal` exits 0 both when a run was resumed and when no run was waiting (the message says which), so
it is safe on every branch. Use `--run <id>` when the trailer is not available (squashed commits,
a different checkout).

## 7. Starting other workflows from CI

Any entrypoint the project maps can be started: `backoffice-ci run nightly-audit --diff none --var
suite=full`. Variables arrive as `input.parameters` of the run; the CI context is `input.ci` and can be
used in decision expressions (`input.ci.mergeRequest.targetBranch == 'main'`). Without `--wait` the
job returns immediately and the run continues in Back Office.

## 8. GitHub Actions

The same script works unchanged; the context comes from `GITHUB_*` variables and the event payload
(`GITHUB_EVENT_PATH`, for the PR title, body and author).

```yaml
name: back-office
on:
  pull_request:
  push:
    branches: ["**"]

jobs:
  review:
    if: github.event_name == 'pull_request'
    runs-on: ubuntu-latest
    env:
      BACKOFFICE_URL: ${{ vars.BACKOFFICE_URL }}
      BACKOFFICE_CI_TOKEN: ${{ secrets.BACKOFFICE_CI_TOKEN }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - run: curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
      - run: sh backoffice-ci.sh review

  signal-tests:
    if: github.event_name == 'push'
    runs-on: ubuntu-latest
    env:
      BACKOFFICE_URL: ${{ vars.BACKOFFICE_URL }}
      BACKOFFICE_CI_TOKEN: ${{ secrets.BACKOFFICE_CI_TOKEN }}
    steps:
      - uses: actions/checkout@v4
      - id: tests
        run: ./gradlew test
        continue-on-error: true
      - run: |
          git log -1 --format=%B | grep -q "^Back-Office-Run:" || exit 0
          curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
          sh backoffice-ci.sh signal tests --status "${{ steps.tests.outcome == 'success' && 'success' || 'fail' }}"
      - if: steps.tests.outcome != 'success'
        run: exit 1
```

Merge requests opened by the runner use GitLab push options; GitHub ignores them, so on GitHub the
runner pushes the branch and `result.mergeRequestUrl` stays empty - open the pull request yourself
or with `gh pr create` in a workflow step.

## 9. Testing the script locally

```bash
export BACKOFFICE_URL=http://localhost:8080 BACKOFFICE_CI_TOKEN=bo_ci_...
curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh   # or use ci/backoffice-ci.sh from the repo
cd /path/to/some/checkout/on/a/feature/branch
sh backoffice-ci.sh run code-review --wait            # OTHER provider, branch diff against origin/main
sh backoffice-ci.sh signal tests --status success --run <runId>
```

`ci/tests/test_backoffice_ci.py` runs the script against a fake API (`python3 -m unittest discover -s ci/tests`).

## Where to look when it fails

- **`BACKOFFICE_URL is required` / `BACKOFFICE_CI_TOKEN is required`** - the variable is not visible
  to the job: marked Protected while the MR source branch is unprotected (unmark it, keep Masked), wrong
  environment scope, or not passed to the `env:` of the GitHub step.
- **HTTP 401** - the token was revoked, mistyped (masking rejects tokens with unusual characters,
  `bo_ci_` tokens are safe) or is a personal access token instead of a project CI token.
- **HTTP 403** - the token belongs to another project than the one that owns the execution you poll.
- **HTTP 400 unknown repository** - the CI job's repository URL is not one of the project's
  repositories; the message lists them (`key: url`). Add the repository on the project's **Settings** tab
  or correct its URL. **HTTP 400 for a restricted entrypoint** - the entrypoint accepts only another
  repository; change the entrypoint's **Repository** or call another entrypoint.
- **HTTP 404 unknown entrypoint** - the entrypoint key is not mapped on the project's CI tab, or
  `BACKOFFICE_URL` points at the web UI of a different deployment.
- **`Diff: empty`** - shallow clone without the target branch: set `GIT_DEPTH: "0"` (the template
  does) or `fetch-depth: 0`; the script also tries `CI_MERGE_REQUEST_DIFF_BASE_SHA`.
- **Diff truncated** - raise `BACKOFFICE_DIFF_MAX_BYTES` (the platform accepts up to 2 MB) or split
  the merge request.
- **Exit 3 (timeout)** - the run is slow or stuck; open the `Run:` link, then raise
  `BACKOFFICE_REVIEW_TIMEOUT` or configure a faster model profile for the review task.
- **Execution `FAILED` with an error** - printed as `Error:`; usually a provider problem
  (see [provider-errors](../runbooks/provider-errors.md)) or a workflow validation error on the run page.
- **Signal reports "no run is waiting"** - the run already moved on, the signal name differs from the
  `WAIT_FOR_CI` node's, or HEAD has no trailer and the branch does not match; pass `--run <id>`.
- **Merge request not created by the runner** - see [runner-job-failed](../runbooks/runner-job-failed.md)
  (push options need GitLab; the runner's push token - runner chart `gitCredentials.repoSecrets[].push`,
  compose `BO_GIT_TOKEN_PUSH` - needs `write_repository`; the step's git access must be `PUSH_MR`, i.e. `push` on).
- **`apk: not found` in the template jobs** - a custom `BACKOFFICE_IMAGE` without `apk`; use an image
  that ships `sh`, `curl` and `git`.
