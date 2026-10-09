# GitLab CI and other CI

Your repository's own pipeline calls Back Office: it asks for an AI code review of a merge request, starts
any workflow mapped to an entrypoint, and reports test results to a run that waits for CI. Back Office
never calls GitLab, GitHub or any SCM API; every interaction starts in your CI with a project CI token.

**Who needs this:** project owner or maintainer (token, entrypoints), whoever owns the repository's CI
configuration (variables, `.gitlab-ci.yml`).

<a id="ci-overview"></a>
## How the CI integration works

```
your CI job                                Back Office
backoffice-ci.sh review  ── POST /api/v1/ci/executions ──▶ entrypoint "code-review" → workflow run
     │  (CI context + merge request diff)                   │ review task, decision, END (PASS/FAIL)
     │◀── poll GET /api/v1/ci/executions/{id} ──────────────┘
     └── exit 0 / 1 / 2 / 3

your test job ── backoffice-ci.sh signal tests ── POST /api/v1/ci/signals ──▶ resumes the "Wait for CI" node
```

- The client is one POSIX shell script served by Back Office itself at `<public URL>/ci/backoffice-ci.sh`
  (needs only `sh`, `curl`, `git`, `awk`). **Settings** → **CI client script** shows the URL and the version.
- An **entrypoint** maps a key used by CI (for example `code-review`) to a workflow of the project, with a
  mode: **BLOCKING** (a failed gate fails the CI job) or **ADVISORY** (the job only reports).
- The gate result is the outcome of the workflow's `END` node, so any workflow can act as a gate.
- Executions are idempotent per project, entrypoint, commit and merge request for 24 h: retrying the job
  returns the same execution (`--var force=true` forces a new one).
- Results appear in **CI** (`/ci`) and in the project's **CI** tab under **CI executions**.

**Limits**

- Nothing is posted to the merge request by Back Office (it has no SCM credentials). The report is in the
  job log and on the run page linked as `Run:`.
- The uploaded diff is capped at 2 MB (`BACKOFFICE_DIFF_MAX_BYTES`); larger diffs are truncated.

<a id="ci-token"></a>
## Create a project CI token

A CI token (`bo_ci_` + 40 random characters) is a bearer token that holds exactly two permissions on one
project: `ci.execute` (start executions of the project's entrypoints, poll them) and `ci.signal` (send
signals). It cannot read the project, its runs or anything else, and people cannot use these endpoints with
their session.

1. Open the project and select the **CI** tab. You need `project.ci.manage` (project roles **owner** and
   **maintainer**).
2. In the **CI tokens** card press **Create token**.
3. Enter a **Token name** that says where it will live, for example `gitlab-ci-shop` or
   `github-actions-shop`, and press **Create**.
4. Copy the token from **Token created** now: it is shown once and stored only as a SHA-256 hash. Press
   **Done**.
5. Map the entrypoints in the **CI entrypoints** card: **Add**, key `code-review`, the review workflow,
   mode **BLOCKING** or **ADVISORY**, then **Save entrypoints** (needs `project.manage`).

Creation and revocation are audited (`CI_TOKEN_CREATE`, `CI_TOKEN_REVOKE`). Create one token per repository
or per CI system so you can revoke one without breaking the others; the **Last used** column tells you
whether a token is still in use.

**Rotate** (CI tokens do not expire, so rotate on a schedule, for example yearly, and whenever someone with
access to the CI variables leaves): create a second token, replace the CI variable, run a pipeline, check
that **Last used** moves to the new token, then press **Revoke** on the old one. Revocation is immediate.

<a id="ci-variables"></a>
## Set the CI/CD variables

| Variable | Required | Value |
|---|---|---|
| `BACKOFFICE_URL` | yes | Public URL of Back Office as reachable from your CI runners, for example `https://backoffice.example.com` |
| `BACKOFFICE_CI_TOKEN` | yes | The `bo_ci_...` token |
| `BACKOFFICE_REVIEW_TIMEOUT` | no | Seconds to wait for the run (default `1800`) |
| `BACKOFFICE_POLL_INTERVAL` | no | Seconds between polls (default `10`) |
| `BACKOFFICE_ADVISORY` | no | `true`: never fail the job, whatever the entrypoint mode or error |
| `BACKOFFICE_DIFF_MAX_BYTES` | no | Cap of the uploaded diff (default `2097152`) |
| `BACKOFFICE_IMAGE` | no | Job image with `sh`, `curl`, `git` (default `alpine:3.20`, which installs them) |
| `BACKOFFICE_SCRIPT_URL` | no | Where to fetch the client (default `$BACKOFFICE_URL/ci/backoffice-ci.sh`) |
| `BACKOFFICE_TESTS_JOB` | no | Name of your test job, shown in the signal message |

In GitLab (as of 2026; docs: https://docs.gitlab.com/ci/variables/):

1. Open the repository's project → **Settings** → **CI/CD** → **Variables** → **Add variable**. You need
   the Maintainer role on the GitLab project. A group-level variable (**Group** → **Settings** → **CI/CD**)
   works too when several repositories share one Back Office project.
2. **Key** `BACKOFFICE_CI_TOKEN`, **Value** the token.
3. **Visibility**: **Masked and hidden** (the value can never be revealed in the UI again) or **Masked**.
   `bo_ci_` tokens satisfy GitLab's masking rules.
4. **Flags**: leave **Protect variable** **unticked**. GitLab passes protected variables only to pipelines
   of protected branches and tags, but the review job runs in merge request pipelines of feature branches
   and the signal jobs run on the branches the agents push. A protected token is simply absent there and the
   job stops with `BACKOFFICE_CI_TOKEN is required`. Leave **Expand variable reference** unticked.
5. Add `BACKOFFICE_URL` the same way (visibility **Visible**).

Protect the token by other means: limit who has Maintainer on the GitLab project, and remember that a CI
token can only start this project's entrypoints and send signals.

<a id="ci-template"></a>
## Add the GitLab template

The repository `ci-templates/backoffice.gitlab-ci.yml` holds a ready template. Include it from the project
that hosts Back Office, or copy it:

```yaml
include:
  - project: 'mos/back-office'
    ref: main
    file: '/ci-templates/backoffice.gitlab-ci.yml'

# optional
backoffice:review:
  variables:
    BACKOFFICE_ADVISORY: "true"          # report only
  # allow_failure: { exit_codes: [1] }   # tolerate a failed gate, still fail on errors (2) and timeouts (3)
```

The project's **CI** tab also renders a **GitLab CI snippet** with one job per entrypoint and one signal
job per `Wait for CI` signal used by the project's workflows; **Copy** it into `.gitlab-ci.yml`.

| Job | Runs when | Does |
|---|---|---|
| `backoffice:review` | merge request pipelines (`CI_PIPELINE_SOURCE == "merge_request_event"`), stage `test` | `backoffice-ci.sh review` |
| `backoffice:signal-tests` | branch pipelines whose HEAD commit has the `Back-Office-Run:` trailer, stage `.post`, on success | `signal tests --status success` |
| `backoffice:signal-tests-failed` | same, on failure | `signal tests --status fail` |

The template sets `GIT_DEPTH: "0"` so the merge base with the target branch exists for the diff. Signal
jobs exit 0 without calling anything on branches people push (no trailer).

**Exit codes of the script**

| Code | Meaning |
|---|---|
| `0` | gate **PASS**, or mode **ADVISORY**, or `BACKOFFICE_ADVISORY=true`; for `signal`: delivered (or nothing was waiting) |
| `1` | gate **FAIL** (or the execution failed without a gate) in **BLOCKING** mode |
| `2` | transport error, `401`/`403`, unknown entrypoint (`404`) or usage error |
| `3` | `BACKOFFICE_REVIEW_TIMEOUT` elapsed before the run finished |

**Test it**

1. Open a merge request; the `backoffice:review` job prints the report with `Gate: PASS` or `FAIL` and a
   `Run:` link.
2. In Back Office the execution appears under **CI** and the token's **Last used** changes.
3. From a laptop: `export BACKOFFICE_URL=... BACKOFFICE_CI_TOKEN=bo_ci_...`, download the script and run
   `sh backoffice-ci.sh run code-review --wait` inside a checkout of a feature branch.

<a id="ci-other"></a>
## GitHub Actions and other CI systems

The same script works unchanged on any CI that can run `sh`. It detects GitLab (`CI_*` variables) and
GitHub Actions (`GITHUB_*` plus the event payload); everywhere else it reports provider `OTHER` and reads
commit and branch from git.

**GitHub Actions**

1. Repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**: name
   `BACKOFFICE_CI_TOKEN`, value the token. Under the **Variables** tab add `BACKOFFICE_URL`. Environment
   secrets work too if the job declares the environment.
   Docs: https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions
2. Add a workflow:

```yaml
on:
  pull_request:
jobs:
  review:
    runs-on: ubuntu-latest
    env:
      BACKOFFICE_URL: ${{ vars.BACKOFFICE_URL }}
      BACKOFFICE_CI_TOKEN: ${{ secrets.BACKOFFICE_CI_TOKEN }}
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - run: curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
      - run: sh backoffice-ci.sh review
```

Secrets are not passed to workflows triggered from forks; reviews of fork pull requests need a different
trigger, which you should weigh carefully because the token would be exposed to forked code.

**Other CI (Jenkins, Bitbucket Pipelines, Azure Pipelines, ...)**: store the token in the CI's secret store,
export it as `BACKOFFICE_CI_TOKEN` for the step, check out with full history, then run
`sh backoffice-ci.sh run <entrypoint> --wait --diff auto`. Treat exit codes as above.

**Signals from your own test job** (any CI):

```sh
if git log -1 --format=%B | grep -q "^Back-Office-Run:"; then
  curl -fsSL "$BACKOFFICE_URL/ci/backoffice-ci.sh" -o backoffice-ci.sh
  sh backoffice-ci.sh signal tests --status success --message "$JOB_URL"
fi
```

Use `--run <runId>` when the commit lost the trailer (squash merge, rebased commit).

Agents open merge requests through GitLab push options; GitHub ignores them, see
[integrations-git.md](integrations-git.md#github-token). More symptoms: [troubleshooting.md](troubleshooting.md#ci).
The full reference is `docs/guides/ci-integration.md`.
