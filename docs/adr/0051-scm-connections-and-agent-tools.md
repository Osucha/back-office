# 0051 — SCM connections on project repositories, sealed credential delivery and agent tools

- Status: accepted (steps 1 to 3 implemented; 4 and 5 planned)
- Date: 2026-10-22
- Deciders: owner
- Related: ADR 0013 (no SCM API), 0024 (sandboxed runner fleet), 0029 (prompt-injection defences), 0030
  (least-privilege connections), 0031 (least-privilege git access), 0033 (approval before write), 0034
  (multi-repository projects), 0040 (personal runners); docs/architecture/10-agent-execution.md,
  docs/manual/projects.md, docs/manual/integrations-git.md

## Context

An agent that analyses a failing pipeline cannot see it: git tokens live in the runner cluster (ADR 0031) and never
reach the agent, and Back Office holds no SCM credential (ADR 0013). Handing the agent "some token" on the runner
would tie a project's credential to a machine or a person, although a GitLab project or group access token belongs
to the project. Every runner would have to be configured with every project's tokens, and nothing would say which
step may use which token.

Writes need the opposite treatment. GitLab cannot narrow tokens finely: pushing needs `write_repository`, but a
merge request comment, a commit status or a pipeline retry needs the full `api` scope, which with role Developer
can also create issues, push to unprotected branches and change merge requests. Such a token must never be in reach
of a prompt-injected agent.

## Decision

1. **SCM connections belong to the project's repositories.** A project repository (ADR 0034) has zero or more
   connections; each holds one token, its provider (GitLab first, GitHub later) and the capabilities it grants:
   `GIT_READ`, `CI_READ`, `PUSH_BRANCH`, `MR_WRITE`, `MR_COMMENT`, `PIPELINE_RUN`. Several connections per
   repository are expected: one per permission level. Tokens are encrypted at rest like Jira and LLM provider
   connections; only the project's Owner (`project.connections.manage`) adds, replaces, tests and deletes them;
   nobody reads them back. Audited, with expiry reminders as for Jira.
2. **Tested when added.** "Test" asks the SCM for the token itself (GitLab `GET /personal_access_tokens/self`:
   scopes, expiry), the token's role in the project and whether the default branch is protected, and reports
   missing and excess rights against the declared capabilities (ADR 0030 style). This read-only call is the only
   SCM API call Back Office makes outside the write operations of point 6; ADR 0013 is amended accordingly.
3. **Least privilege selection.** A step asks for capabilities, not tokens; Back Office picks, per repository, the
   least-privileged connection that has them (fewest capabilities, then name). A step whose repository has no
   such connection gets a designer warning and does not start.
4. **Sealed delivery to the runner.** Assignments stay free of secrets: the signed spec names only
   `credentials: [{ref, repository, capabilities}]`. The runner-controller generates an X25519 key pair at
   registration and keeps the private key (Kubernetes Secret or a 0600 file); Back Office stores the public key.
   When the sandbox starts, the controller calls `POST /runner/v2/jobs/{id}/credentials`; Back Office releases a
   credential only to the runner holding the job's lease, only if the signed spec names it, only once per job and
   only to runners or pools the connection allows. The answer is sealed to the runner's public key with HPKE
   (RFC 9180, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM) with `jobId|ref|expiresAt` as associated data,
   so TLS-inspecting proxies on the way (corporate networks) see ciphertext only, and a captured answer cannot be
   replayed for another job or later. Every release is audited (connection, runner, job); disabling a runner stops
   further releases at once.
5. **Agents get read-only tools only.** A step may request agent tools (`tools: ["gitlab-read"]`). A tool defines
   what the agent receives: environment (`GITLAB_TOKEN`, `GITLAB_HOST`), the CLI in the runner image (`glab`),
   command rules, egress hosts and a prompt hint. A tool binds only to a connection whose capabilities are all
   read capabilities (`GIT_READ`, `CI_READ`); a connection with any write capability can never reach the agent.
   The token value is added to the runner's redaction. The job shows the tools it was given.
6. **Writes are platform operations.** The push of the agent's branch stays in the runner entrypoint, after the
   agent, with a `PUSH_BRANCH` connection the agent never sees. Every other write (merge request create/update,
   review comments, commit status, pipeline retry, branch cleanup, issues) is a typed workflow step executed by
   Back Office with a whitelisted operation and the selected connection, optionally previewed and approved like
   Jira writes (ADR 0033). Agents only propose writes as structured artefacts.
7. **No merge by Back Office.** Merging stays a human action in the SCM: no capability grants it, and Test warns
   when a connection's role could merge into the default branch.
8. **Runner-side git tokens are deprecated.** `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` and chart `repoSecrets`
   keep working for one release as a fallback when a repository has no connection (logged as a warning), then
   they are removed.

Implementation steps (each usable on its own):

1. Connections on project repositories: model, encryption, API, Test, UI (Sources tab). *Implemented.*
2. Sealed credential channel: runner key pair, credential release endpoint, HPKE in controller and Back Office.
   *Implemented together with 3* (Bouncy Castle's RFC 9180 implementation on both sides; conformance vector
   docs/api/fixtures/sealed-credential-vector.json).
3. Clone and push with project connections (gitAccess resolved to connections), fallback to runner tokens.
   *Implemented*; the personal runner's command no longer sets git tokens.
4. Agent tool `gitlab-read` (`glab` in the runner image), step `tools`, designer and start checks.
5. Write operations: merge request review comments and commit status first.

## Alternatives considered

- **Tokens on the runner, the agent allowed to use them.** Rejected: ties a project credential to a machine,
  every runner needs every project's tokens and no step-level consent exists.
- **A credential-injecting proxy (the agent never sees the token).** Stronger, but the egress proxy only tunnels
  TLS (CONNECT) and cannot add headers without terminating TLS; a later option for high-isolation pools.
- **Plain TLS for delivery.** Rejected: TLS-inspecting proxies on corporate networks would see every token.
- **Merge by Back Office after the human gate.** Rejected: needs Maintainer tokens (ADR 0031) and removes the
  independent approval in the SCM.

## Consequences

- Back Office now holds SCM tokens: a database or backup leak exposes them encrypted; the master key must be
  protected like the Jira and LLM connection keys.
- Runners need a newer controller for sealed delivery; older controllers keep using their own tokens (fallback).
- Personal SCM tokens (a user's own PAT) are out of scope for now.
