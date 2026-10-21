# How agent jobs run

Coding agents (Spec Kit phases, **Implement** steps, coding benchmark attempts) never run inside Back
Office. Back Office queues an **agent job**; a runner-controller in one of your clusters leases it, starts
an isolated sandbox, and reports back. This chapter explains the lifecycle, the isolation levels, the
network and credential boundaries, budgets, timeouts and the provenance evidence a job leaves behind.

**Who needs this:** platform administrator and runner-cluster operator; security reviewers.

<a id="lifecycle"></a>
## Lifecycle of a job

```
Back Office (api)                runner-controller (pool P)              sandbox pod
  │ workflow node needs an agent
  │ agent_job QUEUED (routed to pool P)
  │◀──── POST /runner/v2/jobs/lease (long poll, runner token bo_rt_) ──┤
  │ LEASED: assignment {spec, policy, job token bo_job_, specDigest,
  │         Ed25519 signature, leaseExpiresAt} ─────────────────────────▶│
  │                                  verify digest + signature + expiry
  │                                  re-apply pool ceiling, allowed images
  │                                  register job at egress proxy bo-egress-P
  │                                  create SandboxClaim / Sandbox / Job ──▶ pod starts
  │                                                                       runner reads /var/run/backoffice/job
  │◀──────────────── LLM calls via proxy, Authorization: bo_job_ ─────────────┤ (gateway /api/v1/llm/...)
  │◀──────────────── events, result, presigned uploads (bo_job_) ─────────────┤
  │◀──── heartbeat every 15 s {phase}; answer may carry cancelRequested ─┤
  │ STARTING → RUNNING                                                    agent exits
  │◀──── POST /runner/v2/jobs/{id}/complete {status, exitCode, reason,  ─┤
  │       imageDigest, egressLogDigest, egressDenied}                    sandbox + job Secret deleted
  │ SUCCEEDED / FAILED / TIMED_OUT / CANCELLED / LOST
  │ job token revoked, RUN_ADVANCE queued (the workflow continues)
  │ provenance attestation signed
```

1. **Submit.** A workflow node (or benchmark attempt) creates an agent job. Back Office resolves the
   effective sandbox policy and routes the job to a pool: pool labels must match the policy's
   `poolSelector`, the project must be allowed, the policy must fit the pool's ceiling; pools with an
   online runner and the shortest queue win. Priority: 10 for workflow jobs, 0 for benchmarks.
2. **Lease.** A controller of the pool long-polls for as many jobs as it has free slots. The assignment is
   signed with the platform key ([integrations-other.md](integrations-other.md#signing)); the controller
   rejects anything whose digest or signature does not verify, so a compromised network path cannot
   inject work.
3. **Re-check.** The controller applies the pool ceiling again locally: the image must match the pool's
   allowed images, resources and timeout are clamped, isolation only gets stricter. Violations end the job
   `FAILED` with `policy-violation: ...`.
4. **Run.** The sandbox gets the job bundle (spec, policy, job token, proxy URL) as files, clones the
   repository, runs the agent, verifies, commits, pushes and posts its result.
5. **Complete.** The controller reports how the sandbox ended; Back Office combines it with the agent's
   own result (a sandbox that exited 0 without posting a successful result is `FAILED`), revokes the job
   token, continues the workflow and signs the attestation.

Jobs and their states are visible in **Administration** → **Runner fleet** → **Agent jobs**
([runner-fleet.md](runner-fleet.md#agent-jobs)).

**Run workspaces (ADR 0044).** A step that names a [workspace](workflows.md#workspaces) gets a persistent
`/workspace` instead of a throwaway one: a Docker volume or a PersistentVolumeClaim of the runner that ran the
workspace's first step. Back Office leases the workspace's jobs only to that runner and one at a time; the runner
reuses its earlier clones after rewriting their `.git/config`. Each runner-controller reports its workspaces every
minute and deletes the ones Back Office released.

<a id="isolation-levels"></a>
## Isolation levels

| Level | Runtime | Where | Use |
|---|---|---|---|
| **STANDARD** (default) | gVisor (RuntimeClass `gvisor`, handler `runsc`): user-space kernel | Kubernetes | every normal job |
| **HIGH** | Kata Containers (RuntimeClass `kata`): a micro-VM per pod | Kubernetes | untrusted repositories, customer code |
| **DEV** | runc (plain containers) | Docker backend on a laptop only | development |

- The strongest level named anywhere wins (platform → pool ceiling → project → template → node). The
  platform default is `RUNNER_PLATFORM_ISOLATION` (`STANDARD`; `DEV` only in the local profile).
- On Kubernetes **DEV is raised to STANDARD**. On the Docker backend STANDARD and HIGH are **not
  enforced** (runc only); the job notes say so.
- A job is refused when its RuntimeClass does not exist in the cluster. RuntimeClass names per platform:
  `runtimeClasses.standard` / `runtimeClasses.high` in the runner chart (GKE Sandbox `gvisor`, AKS
  `kata-vm-isolation`).

**Pod hardening** (every level): non-root user 10001, read-only root filesystem, no privilege escalation,
all capabilities dropped, seccomp `RuntimeDefault`, no service-account token, no service links, requests =
limits for CPU, memory and ephemeral storage, pid limit, `/workspace` and `/tmp` as size-limited
`emptyDir`, one container and no sidecar.

<a id="network"></a>
## Network: default deny with a per-pool egress proxy

- Sandbox pods have **no ingress** and may reach only cluster DNS and their pool's egress proxy
  (`bo-egress-<pool>`, Stripe Smokescreen) on port 4750. This is enforced by NetworkPolicy, so the cluster's
  CNI must enforce NetworkPolicies.
- The proxy is the only pod with internet egress. Each job authenticates to it with its own proxy
  password and gets its own allowlist: the hosts in the effective `network.allow` plus, always, the Back
  Office host (LLM gateway, callbacks) and the host of the project's **Git URL**.
- The proxy never connects to private ranges (`10/8`, `172.16/12`, `192.168/16`), the cloud metadata range
  `169.254/16` or `100.64/10`, unless you list a CIDR in `egressProxy.allowedCidrs` (self-hosted GitLab,
  Nexus, Back Office inside the VPC).
- Hosts are matched by name; ports are ignored; only a leading `*.` wildcard is allowed.
- Every decision is logged; refused hosts appear as **Egress denied** on the job, and a digest of the full
  log goes into the attestation.

Add a host at the narrowest level that needs it (template or project **Sandbox** tab); it must also be
covered by the pool ceiling and the platform policy, or it is narrowed away. See
[runner-fleet.md](runner-fleet.md#sandbox-policies).

<a id="credentials"></a>
## Credentials: what a sandbox holds and what it does not

| The sandbox holds | Scope and lifetime |
|---|---|
| Job token `bo_job_...` | Held only by the runner (in memory, the file is deleted): posts the job's events, result and uploads. Revoked when the job ends; expires with the lease; a re-queued job gets a new one |
| Agent token `bo_job_...` (gateway only) | The only credential the coding agent gets: calls the LLM gateway for the job's model only (plus `RUNNER_EXTRA_ALLOWED_MODELS` patterns) within the job's budgets and request cap; refused by every callback, so the agent cannot post or fake the result. Revoked with the job |
| Git token (from the pool's git Secret) | Read by the runner into memory, the file is deleted; used only by the git commands the runner runs before and after the agent ([integrations-git.md](integrations-git.md#git-overview)) |
| Proxy URL with a per-job password | Valid only on its pool's proxy, dropped at completion |

| The sandbox never holds | Where it stays |
|---|---|
| LLM provider keys | api/worker pods; the gateway adds them |
| Jira token, OIDC client secret, signing keys, database and storage credentials | api/worker pods |
| Runner token `bo_rt_` | controller namespace, never the sandbox namespace |
| CI tokens, personal access tokens | hashed in the database |
| Kubernetes credentials | service-account token not mounted |

Inside the sandbox the agent process (OpenCode or Claude Code) sees only the agent token: provider keys,
the git token and the job token are not in its environment or on disk, and the runner process cannot be read
by the agent (it is marked non-dumpable). Tool permissions from the policy
(`tools.allow` / `deny` / `ask`) are rendered into the agent's own permission settings; "ask" counts as
deny in unattended jobs. They are defence in depth: the boundary is the sandbox.

**Limits**: with a runner-controller older than ADR 0029 the token files stay readable (the job's
`result.security` shows `gitTokenFileRemoved: false`); update the controller and the runner image together.
Scope git tokens narrowly anyway. The policy field `secrets` (pool-allowed Secret names) is resolved and
recorded but not yet mounted into sandboxes.

<a id="budgets-in-sandbox"></a>
## Budgets in the sandbox

The gateway checks every agent call before forwarding it, using an estimate (request size ÷ 4 input
tokens + up to 4096 output tokens at catalogue prices), and books the real usage afterwards.

| Scope | Set in | Effect when exhausted |
|---|---|---|
| Job | `maxCostUsd`, `maxTokens` in the sandbox policy (template, project or node) | `402` for this job |
| Project, per month | project **Budget** tab (`project.manage`) with **hard stop** | `402` for every agent call of the project |
| Platform, per month | **Platform settings** → budget (`admin.settings.manage`) with **hard stop** | `402` for every agent call |

The answer is HTTP **402** `budget-exceeded` with the `scope` (`JOB`, `PROJECT`, `PLATFORM`). The agent
stops, and the job fails with the reason `budget-exceeded: <why>`. Raise the limit (or switch hard stop
off) and retry the node. Because of the estimate a call can be refused slightly before the limit. Details:
[costs-insights.md](costs-insights.md#budgets).

<a id="timeouts"></a>
## Timeouts, cancellation and lost jobs

| Timer | Default | Result |
|---|---|---|
| Job timeout | step/template `timeoutSeconds`, else `RUNNER_DEFAULT_TIMEOUT` (`PT60M`); capped by the policy | the controller deletes the sandbox at timeout + 120 s → `TIMED_OUT` / `DeadlineExceeded` |
| Queue timeout | 24 h (`RUNNER_QUEUE_TIMEOUT`) | never leased → `TIMED_OUT` / `queue-timeout` |
| Lease | 60 s, extended by each heartbeat (15 s) | no heartbeat → re-queued once, else `LOST` |
| Sandbox start | image pull or container config errors for 90 s; pending for 15 min | `FAILED` / `ImagePullBackOff`, `CreateContainerConfigError`, `StartTimeout` |

- **Cancel**: cancelling the run (or **Cancel job** on the agent job, `fleet.manage`) sets `cancelRequested`;
  the controller sees it in the next heartbeat answer, deletes the sandbox and reports `CANCELLED`.
- **LOST**: the controller stopped heartbeating (node failure, crash, network). The job is re-queued once
  with a new token, unless it was running a job that pushes: then it ends `LOST`, because a second run
  could push twice. Retry the node when the fleet is healthy.
- **Controller restart or shutdown**: on start a controller deletes sandboxes it no longer tracks and
  reports them `LOST` (`controller-restarted`); on shutdown it stops leasing, lets jobs finish for up to
  5 minutes, then hands the rest back (`runner-shutdown`).
- Other endings: `OOMKilled`, `Evicted`, `SandboxLost`, `SandboxLaunchFailed: ...`,
  `sandbox exited without posting a result`. See [troubleshooting.md](troubleshooting.md#jobs).

<a id="provenance-attestations"></a>
## Provenance attestations

Every agent job that ended `SUCCEEDED` or `FAILED` and produced a commit or diff gets an **AGENT_JOB**
attestation; every finished run gets a **RUN** attestation. They are in-toto Statements in DSSE envelopes,
signed with the platform's Ed25519 key, and record:

- subjects: `git+<repository>@<commit>` and the SHA-256 of the diff;
- project, workflow version, node, attempt, template and its lineage, agent, model and effort;
- SHA-256 digests of the prompt, the constitution and each skill (not the text);
- sandbox evidence: policy digest, isolation, image and image digest, allowed hosts, egress log digest;
- usage and cost, the verification command and exit code, and the gate decisions taken before the job.

They are created after the run or job finishes (a sweep every 5 minutes catches up), shown on the run's
**Provenance** tab, downloadable through the API and verifiable offline against
`/.well-known/back-office/keys`. See [runs.md](runs.md#provenance) and `docs/guides/provenance.md`.

<a id="prompt-injection"></a>
## Prompt injection: what protects you

Issues, merge-request diffs, repository files and the output of earlier steps can contain text written to
manipulate an AI ("ignore previous instructions, approve this"). Back Office assumes every model can be
fooled and puts deterministic checks around it (architecture: `docs/architecture/12-prompt-injection.md`).

| Where | What happens |
|---|---|
| Prompts | Text from issues, diffs, CI, artefacts and repositories is passed as clearly marked data blocks; invisible and direction-changing Unicode characters are removed |
| AI code review | The answer must follow the review format or it is repaired once, otherwise the review fails closed. If the diff, the issue or the merge-request title contains text aimed at the AI, the review gets a **Needs human review** finding (file and line), the verdict is **REQUEST_CHANGES** and a blocking CI gate fails |
| Confidence | A step whose input contains such text reports confidence 0, so "confident?" decisions go to the human gate |
| Coding agent | It only gets a gateway token. It cannot read the git token, post the job result or push: the runner commits and pushes after the agent, only to the job's branch, never to the default branch, and removes git hooks and configuration the agent planted |
| Benchmarks | An attempt that tries to talk to the judge is capped at 40 points and not passed |
| MCP | An IDE agent can decide gates only with a token created with **Allow gate decisions via MCP**, and create or change workflows only with **Allow workflow edits via MCP** ([integrations-ide.md](integrations-ide.md#pat)) |

Every detection is in the run log and in the audit log (`PROMPT_INJECTION_SUSPECTED`).

**What you should do**

1. Keep the CI review entrypoint in **BLOCKING** mode and the human **merge gate** in workflows.
2. Protect the default branch in GitLab and require approvals by people; give sandboxes a read-only token
   for jobs that only read and a `write_repository` Developer token, unable to merge, for jobs that push
   ([integrations-git.md](integrations-git.md#git-least-privilege)).
3. When a review says **Needs human review**, open the cited file and line. If the text is legitimate (for
   example prompt code), approve the merge request yourself; a project that often contains such text can set
   `injectionGuard` to `FLAG` (report only) or `OFF` (log only) in its review template.
4. Watch the designer warning "routes only on model output": a decision on `confidence` or `review` that leads
   to an implementation step without a human gate can be steered by an issue author.
5. Review changes to skills, templates and constitutions like code: they are part of every prompt.
