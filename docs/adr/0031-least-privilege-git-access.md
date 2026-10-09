# 0031 — Least-privilege git access: READ and PUSH_MR credentials per job, never merge

- Status: accepted
- Date: 2026-10-08
- Deciders: owner
- Related: ADR 0013 (no SCM API, CI-initiated integration), 0024 (sandboxed runner fleet), 0029 (prompt-injection
  defences); docs/architecture/10-agent-execution.md, docs/architecture/12-prompt-injection.md,
  docs/manual/integrations-git.md

## Context

Since ADR 0024 a runner pool holds one git token per repository host (`gitCredentials.existingSecret` /
`hostSecrets`, Docker `BO_GIT_TOKEN`) and mounts it into **every** sandbox that has a repository. The token
must be able to push (Implement and Spec Kit steps push a branch and open a merge request through GitLab push
options), so every job got push rights, including jobs that only read: Spec Kit analysis without `push`,
implementation dry runs with `push = false` and coding benchmarks. ADR 0029 keeps the token in the
entrypoint's memory, but a prompt-injected agent that defeats the sandbox, or a bug in the runner, would then
hold a credential that can write to the repository. Nothing in the platform stopped a push token from being
able to merge either: a Maintainer token or an unprotected default branch would let a push option such as
`merge_request.merge_when_pipeline_succeeds` merge the agent's change without a human.

The owner's rule: a task that only reads gets a read-only token; a task that implements gets a token that can
create a branch, push it and open a merge request, but never merge. ADR 0013 stays: Back Office never calls
an SCM API and holds no SCM credential.

## Decision

1. **Access level on the job spec.** `RunnerJobSpec.gitAccess` is `NONE | READ | PUSH_MR`, derived by Back
   Office from the task: an IMPLEMENT or SPEC_KIT step with `push = true` -> `PUSH_MR`; any other job with a
   repository (`push = false`, analyses, coding benchmarks) -> `READ`; a job without a repository -> `NONE`.
   The level is part of the signed assignment, shown on the agent job (`AgentJob.gitAccess`) and in the
   designer's `push` field description. Back Office still sends only the level, never a secret.
2. **Credentials chosen in the runner cluster by repository and level.** Chart `gitCredentials.repoSecrets`
   (`runner-controller.git.repo-secrets`): a list of `{repository, read, push}` where `repository` is
   `host/group/project`, `host/group/*`, `host`, `*.domain` or `*` (most specific wins) and `read` / `push` name
   Secrets (key `token`) in the sandbox namespace. The controller mounts the `read` Secret for READ jobs, the
   `push` Secret only for PUSH_MR jobs and nothing for NONE. Refusals fail the job with
   `policy-violation: git credential: ...`: a READ job whose only match is a push credential (unless
   `allowPushCredentialForRead: true`, explicitly), a PUSH_MR job whose match has no push credential. No match
   at all keeps the old behaviour: anonymous clone (public repositories). `existingSecret` / `hostSecrets`
   stay as **push-level** fallbacks used only when no `repoSecrets` entry matches; the controller logs a
   warning at start that they grant push. Docker: `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH`, `BO_GIT_TOKEN`
   as push fallback.
3. **The runner never merges and READ jobs never push.** `spec.gitAccess` below PUSH_MR switches
   `repository.push` and the merge request off, sets `remote.origin.pushurl` to `disabled://read-only-job`
   right after the clone (part of the `.git/config` snapshot the runner restores after the agent) and
   `Git.push` refuses. Push options are a whitelist: `merge_request.create`, `.target`, `.title`,
   `.description`, `.draft`. Never `merge_when_pipeline_succeeds`, `auto_merge`, `remove_source_branch`,
   `ci.skip` or `ci.variable`; `push.pushOption` from any git config is reset on the push command line. Pushes
   stay what they were: explicit spec URL, `HEAD:refs/heads/<job branch>`, never the default or target branch,
   never `--force` (there is no option to enable it).
4. **Merging is made impossible on the SCM side**, documented step by step (manual, `git-least-privilege`):
   READ token = GitLab project/group access token with role Reporter and scope `read_repository` only; PUSH
   token = role Developer and scope `write_repository` only (no `api`, no `read_api`), expiry at most 90 days;
   default branch protected with "Allowed to merge: Maintainers", "Allowed to push and merge: No one",
   force push off. Developer + protected default branch cannot merge, whatever push option is sent. GitHub:
   fine-grained tokens with Contents read (READ) or read and write (PUSH), branch protection requiring pull
   request reviews; pull requests are not created by push options there.

## Alternatives considered

- **One token per pool, trust the runner not to push READ jobs.** Rejected: the runner is defence in depth;
  the credential itself must not be able to do what the job must not do.
- **Back Office issues short-lived tokens through the GitLab API.** Rejected: violates ADR 0013 (no SCM API,
  no SCM credential in the control plane) and would make Back Office a high-value target.
- **A credential proxy that filters `git-receive-pack` for READ jobs.** Stronger than a pushurl, but another
  moving part in every pool; a read-only token gives the same guarantee with what GitLab already enforces.
- **Allow `merge_when_pipeline_succeeds` behind a flag.** Rejected: the human merge gate is the decision
  (ADR 0029); auto-merge belongs to GitLab's own approval rules, not to the agent's credential.

## Consequences

- Upgrading pools with only `existingSecret` / `hostSecrets`: PUSH_MR jobs keep working; READ jobs (benchmarks,
  `push = false`, analyses) fail with a clear reason until a read Secret is added to `repoSecrets` (or, as a
  conscious stop-gap, `allowPushCredentialForRead: true`). The controller log says so at start.
- Operators manage two tokens per repository (or group) and rotate both; the manual gives the exact commands.
- Older runner images ignore `gitAccess` and push whenever `repository.push` is set; Back Office only sets
  `push` for PUSH_MR jobs and the controller hands READ jobs a read-only token, so the token still prevents
  the push. Older controllers ignore the level and mount the per-host Secret as before.
- Residual risk: the PUSH token can still push arbitrary content to the job branch and open (not merge) a
  merge request; review, CI and the protected default branch remain the control.

## Amendment (ADR 0034, 2026-10-08)

Projects can have several repositories and a step can use several of them
([ADR 0034](0034-optional-multi-repository-projects.md)). The access level is now **per repository entry**:
`RunnerJobSpec.repositories[].access` is `READ` or `PUSH_MR`, and the runner-controller selects the credential
for every entry by its URL and access with the same `repoSecrets` rules as above (read Secret for READ, push
Secret for PUSH_MR, refusals as in decision 2). READ entries get their `pushurl` disabled; the push-option
whitelist and "never merge" apply to every PUSH_MR entry. The top-level `gitAccess` stays in the spec as the
**highest** access among the entries, for older controllers and runners, and the legacy `repository` mirrors the
primary entry. An older controller therefore selects one credential for the primary URL at the highest level,
which may mount a push credential for the primary although only another repository is PUSH_MR; ADR 0034 lists
this as a residual risk until controllers are upgraded.
