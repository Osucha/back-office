# 0011 - Merge-request gate implemented as a CI job polling the API

Status: partially superseded by [0013](0013-ci-initiated-integration-without-scm-api.md) - the CI job remains the gate, but the diff is uploaded by the job and the platform no longer calls GitLab.

## Context
The owner wants code review as a job that connects to this application to decide whether a merge
request may pass.

## Decision
A GitLab CI template (`ci-templates/code-review.gitlab-ci.yml`) posts a review request and polls until
completion; the job exit code is the gate. Review logic runs in the API where models, the constitution
and spec artefacts live. GitLab webhooks may additionally trigger reviews early so results are ready
when the job asks.

## Alternatives
- Running the review model inside the CI job: keys and prompts leak into every repository CI config;
  no access to specs or the model catalogue.
- Webhook-only with an external status API: works, but the explicit job is visible in the MR pipeline
  and trivially debuggable.

## Consequences
- Reviews are idempotent per `(project, MR, sha)`.
- The CI job needs `BACKOFFICE_URL` and `BACKOFFICE_API_KEY` as protected CI variables.
