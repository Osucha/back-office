# 0040 — Personal runners: your own computer runs your private projects' agent steps, optionally with your own Claude login

- Status: accepted
- Date: 2026-10-12
- Deciders: owner
- Related: ADR 0024 (runner fleet; extended here), 0029 (prompt-injection defences), 0038 (personal work mode),
  0025 (budgets); docs/manual/personal-runner.md, docs/architecture/10-agent-execution.md

## Context

ADR 0038 lets one person automate their own work with private projects, next to the team mode. Their agent steps
still run on the shared runner fleet and reach models only through the Back Office LLM gateway with the platform's
provider keys (ADR 0024). A person who has only a Claude subscription licence (no API key, no Bedrock/Vertex) cannot
run Claude Code steps at all, and a laptop owner may simply prefer their own machine to a shared cluster.

Claude Code can sign in with a long-lived token of the user's subscription (`claude setup-token`,
`CLAUDE_CODE_OAUTH_TOKEN`). It reports `total_cost_usd`, which is a client-side estimate, not a bill. Subscription
use is meant for the individual's own use; a shared server must not hold one person's login.

## Decision

1. **Personal runner pools.** `runner_pool` gets `owner_user_id` (at most one pool per user) and `llm_credentials`
   (`GATEWAY` | `LOCAL_LOGIN`; `LOCAL_LOGIN` only with an owner) — migration V53. Each user manages their own pool
   under **My account → My computer as a runner** (`/api/v1/me/runner`, new permission `fleet.personal`, granted to
   the member-level built-in roles by identity migration V411). Pool id `personal-<24 hex of the user id>`; ids with
   that prefix are reserved. Administrators see personal pools (with owner) and can revoke them.
2. **Routing.** `RunnerJobSpec.personalRunnerOf` is set by pipeline steps of a **private project** whose run was
   started by a person (also via **Start** in My work) to that person. `JobLifecycle.route`: such a job goes only to
   that user's personal pool when one exists — also while it is offline (the job waits, queue timeout applies) — else
   to the shared pools; personal pools never receive any other job; the label selector of the shared fleet does not
   apply to personal pools. The field is routing data: stored with the job, removed from the assignment.
3. **Registration.** Same protocol as ADR 0024: a single-use `bo_rrt_` token, but issued by the owner, with a
   generated `docker run` of the runner-controller (DOCKER backend, Docker socket, `/var/lib/back-office-runner`
   mounted at the same path so the daemon can bind-mount job directories, `--user 0:0`). Secrets other than the
   registration token are passed by name only (`-e CLAUDE_CODE_OAUTH_TOKEN`, `-e BO_GIT_TOKEN_READ/PUSH`), so they
   never appear in Back Office or the UI. For a Back Office on the same computer the command uses
   `host.docker.internal` and `RUNNER_EGRESS_ALLOW_RANGES`. The controller image now contains the docker CLI.
4. **`LOCAL_LOGIN` assignments.** The lease adds `spec.llmCredentials = LOCAL_LOGIN` (signed, in the digest; absent
   for gateway jobs, so their digests are unchanged) and `api.anthropic.com` to the mandatory egress hosts. The
   controller accepts such a job only on the DOCKER backend and only when `CLAUDE_CODE_OAUTH_TOKEN` is set (else the
   job fails with `local-login: ...`), writes the login as bundle file `claude-login-token`, and allows the Anthropic
   host. The runner reads and deletes the file before any untrusted code runs, gives it to the Claude Code process
   only (`CLAUDE_CODE_OAUTH_TOKEN`, no gateway URL or token), and reports `usage.billing = SUBSCRIPTION`. OpenCode
   keeps using the gateway.
5. **Cost.** A `SUBSCRIPTION` result has node cost 0 (runs, budgets, insights); Claude Code's estimate stays on the
   agent job (admin view) and in the run log. Gateway budgets and the model allow-list do not apply to these calls,
   because they never reach the gateway.

## Alternatives considered

- **The user's login stored in Back Office** and sent with each job: one person's credential on a shared server,
  outside subscription terms, and a high-value secret in the database.
- **"You run it yourself" through MCP** (Back Office hands the step to the user's interactive Claude Code): no
  sandbox, no unattended runs, and the step contract differs from runner steps; kept as a possible later addition.
- **Personal runner for team projects too**: would mix one person's machine and licence into a team process and
  break the shared budget and audit model; team projects stay on the shared fleet.
- **Reusing pool labels** (`poolSelector`) instead of an owner: anyone editing a project policy could route jobs to
  someone's laptop; ownership must be a property the owner controls.

## Consequences

- One person can automate their work with only their Claude subscription and a computer with Docker; nothing about
  the shared fleet changes.
- Weaker isolation than the shared fleet (runc, the user's Docker daemon) and the user's login is readable by the
  agent inside the sandbox; egress still limited to Back Office, git, package registries and `api.anthropic.com`.
  The manual tells the user to check their licence terms and to use the login only with trusted inputs.
- Spend of `LOCAL_LOGIN` steps is invisible to Back Office budgets by design.
- Private-project steps wait while the owner's computer is off; removing the personal runner returns them to the
  shared fleet.
