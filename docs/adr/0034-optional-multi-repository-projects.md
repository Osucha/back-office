# 0034 — Optional multi-repository projects: zero or more repositories per project, per-step repository access

- Status: accepted
- Date: 2026-10-08
- Deciders: owner
- Related: ADR 0013 (no SCM API, CI-initiated integration), 0014 (graph workflows), 0024 (sandboxed runner
  fleet), 0029 (prompt-injection defences), 0031 (least-privilege git access); docs/architecture/02-domain-model.md,
  docs/architecture/03-pipelines-and-agents.md, docs/architecture/10-agent-execution.md,
  docs/manual/projects.md#repositories, docs/manual/workflows.md#step-repositories

## Context

Until now a project was "a Git repository with its workflows": `Project.repository` was mandatory and every
runner job cloned exactly that repository. Two kinds of work did not fit:

- **Work without code.** The platform is SDLC-first, but the owner wants it to serve any LLM work: writing,
  analysis, translation, reviews of documents, triage. Such a project has no repository, yet the form required
  a Git URL and the seeded workflows assumed one (spec, implement, CI review).
- **Work across repositories.** Real changes often touch more than one repository: the documentation lives
  next to the source, a client library next to its service, an API contract in its own repository. A step that
  updates the code and the documentation, or that reads a contract repository while it implements a service,
  was impossible; the workaround was one project per repository and copying artefacts between runs.

ADR 0013 (Back Office never calls an SCM API and holds no git credential) and ADR 0031 (a job gets the least
git access it needs, never merge) must keep holding for every repository a job touches.

## Decision

1. **Model.** A project has zero or more repositories: `Project.repositories: List<GitRepository>` (domain
   class `GitRepository`; persistence port `ProjectRepository`; OpenAPI schema `ProjectRepository`). Each entry:
   `key` (slug `^[a-z][a-z0-9-]{0,30}$`, unique in the project, also the workspace directory), `url`,
   `defaultBranch` (default `main`), `purpose` `DOCS | SOURCE | OTHER` (a label, also told to the agent),
   optional `description`, optional `primary` flag (at most one). The primary repository is the default of
   steps that name none. Primary is optional: without one, runner steps must name their repositories. Flyway
   `V109` (projects range) moves the existing single repository into an entry `main`, purpose `SOURCE`,
   primary; the columns `repo_url` / `repo_default_branch` stay, nullable, as a mirror of the primary for one
   release. `ci_execution` gains `repository_key`.
2. **API.** `ProjectInput` / `Project.repositories` (list). `repository` is deprecated for one release: in
   responses it is the primary repository (or `null`); in requests it is used only when `repositories` is
   absent and becomes the single repository `main` (SOURCE, primary). `CiEntrypoint.repository` (optional key
   restriction), `CiContext.repositoryUrl`, `CiExecution.repositoryKey`, `NodeExecution.repositories` and
   `WorkflowRun.repositories` (`RepositoryResult`: key, url, purpose, access, primary, branch, commitSha,
   diffStat, mergeRequestUrl, pushed, error). `WorkflowRun.mergeRequest` is the primary repository's merge
   request, or the first one pushed.
3. **Steps choose repositories.** Node or template config `repositories: [{key, access: READ | PUSH_MR}]`.
   When absent: the primary repository with the access derived as before (IMPLEMENT / SPEC_KIT with
   `push != false` -> `PUSH_MR`, else `READ`). Only runner steps (IMPLEMENT, SPEC_KIT) use repositories; LLM
   steps, Jira steps, gates and CI waits need none and work in projects without a repository. A step may read
   several repositories and push to several. Validator errors: a runner step in a project without
   repositories; no primary and no explicit list; unknown key (the message lists the project's keys);
   duplicate key; access other than `READ` / `PUSH_MR`; `PUSH_MR` on a step whose `push` is off;
   `repositories` on a non-runner step. Warning: one step pushes to a `DOCS` and a `SOURCE` repository
   (allowed, made visible). Starting a run is refused when its steps have repository errors (the project
   changed after the workflow was saved).
4. **Job spec contract** (shared with the execution plane). `RunnerJobSpec.repositories: List<JobRepository>`
   with `JobRepository(key, url, baseBranch, access NONE|READ|PUSH_MR, path, pushBranch?, mergeRequest?,
   primary)`; `path` = `key`; `pushBranch` and `mergeRequest {create, title, description, draft}` (target =
   `baseBranch`) only for `PUSH_MR`. Workspace root `/workspace`, each repository cloned into
   `/workspace/<path>`, the agent works in the primary entry's path (`/workspace` when there is none). The job's
   primary is the project primary if the step uses it, else the step's first repository. The same job branch
   is pushed to every `PUSH_MR` repository; a branch equal to the default branch of any `PUSH_MR` repository is
   refused (fallback `run/<run id>`). For old runners the legacy `repository` is filled from the primary entry
   and `gitAccess` is the highest access among the entries. `repositories` is omitted for jobs built the
   legacy way (coding benchmarks), so their spec digests do not change. `RunnerResult.repositories: [{key,
   branch?, commitSha?, diffStat?, mergeRequestUrl?, pushed, error?}]`; the legacy top-level branch, commit,
   diff and merge request fields mirror the primary entry. Each side tolerates the other being old: a missing
   list means one legacy repository. The signed spec digest (canonical JSON) covers the new fields;
   `docs/api/fixtures/spec-digest-vectors.json` has a vector with two repositories.
5. **Credentials per entry.** The runner-controller selects a credential for every entry by (url, access)
   exactly as ADR 0031 does for the single repository: the `read` Secret for `READ`, the `push` Secret for
   `PUSH_MR`. `READ` entries get `pushurl` disabled; the push-option whitelist is unchanged; the runner never
   merges. The coding agent's prompt lists the repositories (key, path, access, purpose, base branch);
   descriptions are written inside ADR 0029 data blocks.
6. **Templates.** The template model gains `project.repositories`, `project.repository` (primary, may be
   null), `repos` (this step's repositories: key, url, path, access, purpose, baseBranch, primary) and
   `repositoryResults` (key -> latest result: branch, commitSha, mergeRequestUrl, pushed);
   `project.defaultBranch` / `project.repositoryUrl` stay as the primary's values (may be empty).
7. **Seeding.** A project with at least one repository gets the three SDLC workflows and the `code-review`
   CI entrypoint as before. A project without a repository gets one starter workflow **LLM task with review**
   (Start -> LLM task with the new built-in template `llm-task` -> human gate "Result ok?": approve -> End
   success, revise -> back to the task, reject -> End failure) and no CI entrypoint. When such a project later
   gets its first repository, the SDLC workflows and the `code-review` entrypoint are seeded once (skipped when
   workflows with those names exist). The Task Library ships twenty global templates.
8. **CI.** `backoffice-ci.sh` 2.1.0 sends `context.repositoryUrl` (GitLab `CI_PROJECT_URL`; GitHub
   `$GITHUB_SERVER_URL/$GITHUB_REPOSITORY`; otherwise the `origin` remote without credentials). Back Office maps
   the execution to a project repository by normalised URL (scheme, credentials, `.git` suffix and trailing
   slash ignored, host case-insensitive, scp-style `git@host:group/proj` equals `https://host/group/proj`). An
   unknown URL is refused with HTTP 400 listing the project's repositories (`key: url`). Older scripts that send
   only `projectPath` are matched by path suffix; no match means no repository. An entrypoint can be
   restricted to one repository key; executions from another or an unknown repository are refused (400). The
   run gets the parameter `repository` = key.
9. **Provenance.** Agent-job attestations get one subject per pushed repository (`git+<url>@<sha>`), the run
   attestation collects them, and the job predicate lists the repositories.

## Alternatives considered

- **One project per repository.** What users did so far. Rejected: members, budgets, connections and
  workflows had to be duplicated, a change spanning two repositories became two runs that could not share
  artefacts or one gate, and a project without code was still impossible.
- **Git submodules or a monorepo only.** Rejected as the only answer: it forces a repository layout on the
  team, submodule pinning makes agent pushes awkward (the parent repository needs another commit), and it does
  not help work without code. Teams that already use a monorepo simply keep one repository.
- **Repositories on the workflow instead of the project.** Rejected: CI tokens, entrypoints, sandbox policy
  and runner credentials are per project; the same repository would be declared in every workflow, and the
  CI mapping (which repository a pipeline runs in) would be ambiguous.
- **Mandatory primary repository.** Rejected: it would make repository-less projects impossible again and
  hide, for multi-repository projects that have no natural "main" repository, which repository a step uses.
  An optional primary keeps the simple single-repository case unchanged and makes the explicit choice
  visible where it matters.
- **One access level per job (keep `gitAccess` only).** Rejected: a step that reads a contract repository and
  pushes to the service repository would get push credentials for both, contradicting ADR 0031.

## Consequences

- **Upgrade.** `V109` converts every project in place (`main`, SOURCE, primary); existing workflows keep
  working without change because steps without `repositories` use the primary with the access they derived
  before. API clients that send `repository` keep working for one release; the legacy columns and the
  `repository` field are removed in the next release.
- **Old runners** (images without `repositories` support) ignore the list and clone only the legacy
  `repository`, i.e. the primary entry; steps that name other repositories then see only the primary. Upgrade
  runner images before using multi-repository steps.
- **Old runner-controllers** ignore the list and select the credential for the legacy (primary) URL with the
  top-level `gitAccess`, which is the highest access among the entries. A step that only reads the primary but
  pushes to another repository therefore gets the primary's **push** credential mounted (and no credential for
  the other repository, so its push fails). This is a residual risk until all controllers are upgraded; the
  old runner still pushes only where `repository.push` is set, and the protected default branch still prevents
  a merge.
- Spec digests of legacy-built jobs (coding benchmarks) are unchanged; the shared vectors pin the new form.
- A project without repositories cannot use Spec Kit or Implement steps; the validator says so, and adding a
  repository later seeds the SDLC workflows.
- Pushing to a documentation and a source repository in one step is allowed but flagged, because a single
  agent credential set then spans two trust domains.
- Run pages, attestations and the template model have to deal with several merge requests per run;
  `run.mergeRequest` and `mr` stay single-valued (primary first) for decisions written before.
