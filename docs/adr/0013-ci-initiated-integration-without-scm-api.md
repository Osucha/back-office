# 0013 - CI-initiated integration: the platform never calls the SCM API

Status: accepted - 2026-09-26. Supersedes the GitLab parts of [0011](0011-code-review-as-ci-job-calling-api.md).

## Context
The owner does not want Back Office to connect to GitLab. Everything that touches a repository must be
initiated from the project's own CI (GitLab CI today, any CI tomorrow) with a secret issued by Back
Office, while configuration and execution stay inside Back Office. A CI job must pass or fail depending
on the outcome. Some steps of a delivery process therefore run in CI, some in Back Office, and the
workflow designer must be able to express both.

## Decision
1. **No SCM API client.** gitlab4j, the GitLab webhook and the `OPEN_MR` step are removed. Merge requests
   are opened by the agent runner through git push options (`git push -o merge_request.create ...`),
   which GitLab honours and other hosts ignore. Diffs are supplied by whoever has the repository: the
   CI job (for merge-request gates) or the runner (after an implementation step).
2. **Project CI tokens.** Back Office issues per-project bearer tokens (`bo_ci_...`, shown once, stored
   hashed). The owner puts one into the project's CI variables. Tokens authorise only the CI endpoints and
   only for their project.
3. **One client script, served by the platform.** `backoffice-ci.sh` (POSIX sh + curl + git) is served at
   `GET /ci/backoffice-ci.sh` and published as a release asset. Commands: `run <entrypoint>` (start a
   configured workflow with the CI context and diff, wait, exit 0/1 by gate result), `signal <name>`
   (report a CI job result to a waiting workflow run) and `review` (alias of `run code-review`).
4. **CI entrypoints.** A project maps entrypoint keys to workflows (`code-review -> "CI code review"`),
   with `BLOCKING` or `ADVISORY` mode. Code review is therefore an ordinary workflow whose `END` node
   outcome decides the gate; nothing is hard-coded.
5. **CI gates inside workflows.** A `WAIT_FOR_CI` node pauses a run until the project's CI sends a signal
   for it (matched by run id from a git trailer written by the runner, or by branch). Back Office never
   triggers CI; pushing the branch does.

## Alternatives
- Keep gitlab4j only for reading diffs: rejected by the owner; also keeps a privileged token in the platform.
- GitHub-style App/webhook model: needs inbound SCM connectivity and provider-specific code.
- Publishing a container image as the CI client: heavier than a script and needs a registry; the script
  can still be wrapped in an image by a project if desired.

## Consequences
- Jira access remains (issue tracker, not SCM); it is optional.
- MR notes are no longer posted by the platform; the CI job prints the review and the UI shows it. A
  project may post notes itself from CI with its own token (`BACKOFFICE_POST_MR_NOTE=true`).
- The CI script is part of the API contract and versioned with the application.
