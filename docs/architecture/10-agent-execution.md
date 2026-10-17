# 10 — Agent execution plane

How coding agents run safely (ADR 0024). Goal: an agent can do real engineering work — clone, build, test,
change code, push a branch — while being unable to leak credentials, reach anything it was not allowed
to, exceed its budget, or affect other jobs, and leaving evidence of everything it did.

Implementation: `backend/runner-controller` (Spring Boot application, own image and README),
`backend/runner-controller/egress-proxy` (Smokescreen build) and `runner/` (sandbox image and `entrypoint.py`).

## Threat model

| Threat | Control |
|---|---|
| Prompt injection makes the agent exfiltrate secrets | no provider keys in the sandbox (LLM gateway); the agent gets a gateway-only agent token; the git and job tokens stay in the runner entrypoint's memory (files deleted, process non-dumpable); egress allowlist (docs/architecture/12-prompt-injection.md) |
| Model-written code escapes the container | gVisor (user-space kernel) by default, Kata microVM for `isolation: HIGH`; non-root, read-only rootfs, dropped caps, seccomp, no SA token |
| Lateral movement inside the cluster | NetworkPolicy default-deny; sandbox reaches only its pool's egress proxy and DNS; proxy reaches only allowlisted hosts and never private ranges; separate namespace (or cluster) per pool |
| Runaway cost | per-job, per-run and per-project budgets enforced at the gateway; `max_tokens` caps; deadline |
| Resource abuse / crypto mining | CPU/memory/ephemeral-storage limits, pids limit, deadline, egress allowlist |
| Compromised control plane runs arbitrary workloads | Back Office has no cluster credentials; runner-controllers pull only jobs of their pool, verify the Ed25519 signature, re-apply the pool ceiling and the allowed image patterns locally |
| Tampering with results | results and logs posted with the full job token, which the agent never sees (the agent token is refused by the callbacks); verification run by the runner, not reported by the agent; attestation signed by the control plane over digests supplied by the controller (egress log) and the runner (diff, log) |
| Stale or reused credentials | job tokens expire with the lease; proxy credentials are per job and dropped at completion; git tokens short-lived where supported; registration tokens single use |
| Over-privileged git credential | least privilege per job (ADR 0031): `gitAccess` READ jobs get a read-only token and never push, only PUSH_MR jobs get a push token (Developer, `write_repository`), push options cannot merge, the default branch is protected (see [Git access](#git-access-adr-0031)) |

## Components

| Component | Where | Responsibility |
|---|---|---|
| Runner API v2 | Back Office api | registration, leasing, heartbeats, cancellation, completion, upload URLs |
| Agent job queue | PostgreSQL (`agent_job`) | queued jobs per pool with priority, labels, deadline |
| LLM gateway proxy | Back Office api | Anthropic- and OpenAI-compatible endpoints for sandboxes; job-token auth; model allowlist; budgets; usage ledger |
| runner-controller | execution cluster | lease → verify → render sandbox → watch → heartbeat → report; warm pool management; enforce policy; egress ACL source |
| Sandbox pod | execution cluster | one `agent` container (runner image: OpenCode, Claude Code, Spec Kit, toolchains); no sidecar |
| Egress proxy | execution cluster, one Deployment per pool (`bo-egress-<pool>`) | Stripe Smokescreen with per-job roles: CONNECT/HTTP allowlist per job, private-range blocking, decisions reported to the controller |

## Job lifecycle

```
Back Office                          runner-controller (pool P)                  sandbox
  node needs agent ──▶ agent_job QUEUED
                       ◀── POST /runner/v2/jobs/lease (long poll, freeSlots = maxConcurrent - running)
  LEASED (lease 60 s) ──▶ assignment {spec, policy, jobToken, specDigest, signature}
                            verify digest + signature + expiry, resolve policy against the pool ceiling
                            register job in the egress ACL, render SandboxClaim / Sandbox / Job ──▶ pod starts
                                                                            runner reads /var/run/backoffice/job
                                                                            agent calls LLM gateway (jobToken) via proxy
                                                                            events/result/uploads ──▶ api (jobToken)
                       ◀── heartbeat every 15 s {phase, sandboxName, node}; ◀── cancelRequested
  RUNNING
                            sandbox exits ──▶ POST /complete {status, exitCode, reason, imageDigest,
                                                              egressLogDigest, egressDenied, durationMs}
  SUCCEEDED/FAILED ──▶ RUN_ADVANCE enqueued, attestation signed          sandbox + Secret deleted
```

States: `QUEUED → LEASED → STARTING → RUNNING → SUCCEEDED | FAILED | TIMED_OUT | CANCELLED`, plus
`LOST` when heartbeats stop (re-queued once if nothing was pushed, otherwise failed with evidence). The
controller reports `LOST` itself in two cases: orphaned sandboxes found at start (`controller-restarted`,
deleted before leasing) and jobs still running when the shutdown grace period ends (`runner-shutdown`).

### Controller loop details

- **Registration**: on first start the controller calls `POST /runner/v2/register` with the single-use
  registration token (`runner-controller.back-office.registration-token`, env `RUNNER_REGISTRATION_TOKEN`) and
  its capabilities (backends offered, RuntimeClasses found, `maxConcurrent`), then stores the runner token in
  the Kubernetes Secret `back-office-runner-credentials` (or a 0600 file for Docker). Later starts reuse it.
- **Config**: `GET /runner/v2/config` at start and every 5 minutes (and immediately when an assignment names
  an unknown `keyId`): pool ceiling, allowed image patterns, warm pool size, gateway URLs, JWK set.
- **Leasing**: one long-poll thread asks for `min(freeSlots, maxJobsPerLease)` jobs with `waitSeconds` 25 and
  blocks while no slot is free.
- **Monitoring**: one thread every 2 s observes each sandbox, sends heartbeats every `heartbeatSeconds`,
  enforces the deadline (policy timeout + 120 s) itself, maps terminal states and reports completions
  (retried until Back Office accepts; 4xx other than 408/429 is final, e.g. 409 = already closed).
- **Heartbeat answers**: `cancelRequested` → sandbox deleted, `CANCELLED`; 409/404 → lease lost, sandbox
  deleted, nothing reported.
- **Graceful shutdown** (SIGTERM): stop leasing, let running jobs finish for `shutdownGraceSeconds`
  (default 300), then delete the rest and hand them back as `LOST`/`runner-shutdown`.
- **Metrics** (`/actuator/prometheus`): `backoffice_sandbox_start_seconds` (histogram, launch → agent
  container running with its bundle), `backoffice_runner_active_jobs`, `backoffice_runner_free_slots`,
  `backoffice_runner_jobs_completed_total{status}`, `backoffice_runner_assignments_rejected_total{reason}`.

## Assignment verification

The controller rejects an assignment (not run, not reported, counted as `reason=verification`) unless:

1. `specDigest` (lowercase hex SHA-256, optionally prefixed `sha256:`) equals the digest the controller
   computes over the **canonical JSON** of the object `{"policy": <policy>, "spec": <spec>}` exactly as
   received (`policy` omitted when absent). Canonical JSON = RFC 8785 (JCS) with one addition:
   - object members whose value is `null` are removed (so absent and null hash the same); `null` array
     elements are kept;
   - object keys sorted by UTF-16 code units; no insignificant whitespace;
   - strings escaped minimally (`\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t`, other code points below U+0020 as
     `\u00xx` lowercase), everything else literal;
   - integers as plain digits; other numbers in ECMAScript `Number.prototype.toString` form;
   - encoded as UTF-8.
2. `signature` is base64 (standard or URL-safe) Ed25519 over the UTF-8 bytes of
   `jobId + "|" + specDigest + "|" + leaseExpiresAt`, each string exactly as received (the digest including
   its `sha256:` prefix if Back Office sent one), verified with the JWK (`kty: OKP`, `crv: Ed25519`, `x`)
   whose `kid` equals `keyId` (all keys are tried when `keyId` is absent).
3. `leaseExpiresAt` lies in the future (5 s clock-skew tolerance).

Reference implementation: `runnercontroller/security/CanonicalJson.kt` and `AssignmentVerifier.kt`
(`CanonicalJsonTest` pins the byte form). The control plane signs with its own `runner/adapter/in/web/CanonicalJson.kt`;
both modules run the shared vectors in `docs/api/fixtures/spec-digest-vectors.json` (`SpecDigestVectorsTest`),
which is also the conformance suite for any third-party runner. `runner-controller.verification.require-signature=false`
exists for local development only.

Afterwards the controller **re-applies the pool policy** (`PolicyResolver`): image must match
`allowedImages` (`*` wildcard), resources and timeout are clamped to the ceiling, isolation only gets
stricter (DEV is raised to STANDARD outside Docker), `network.allow` entries outside the ceiling are dropped,
tool deny lists are united. Violations are reported as `FAILED` with reason `policy-violation: ...`.

## Sandbox policy

Resolved per job from: platform defaults → runner pool defaults → project settings → task template →
workflow node overrides (narrowing only: a lower level can restrict but never widen the platform ceiling).

```yaml
sandboxPolicy:
  isolation: STANDARD          # STANDARD = gVisor, HIGH = Kata, DEV = runc (docker backend only)
  resources: { cpu: "2", memory: 4Gi, ephemeralStorage: 10Gi, pids: 2048 }
  timeoutSeconds: 3600
  network:
    allow:                     # host[:port] patterns; the LLM gateway and git host are always added
      - registry.npmjs.org
      - repo.maven.apache.org
      - "*.gradle.org"
  tools:                       # rendered into OpenCode `permission` and Claude Code `permissions`
    allow: ["git *", "./gradlew *", "npm *", "rg *"]
    deny:  ["curl *", "wget *", "ssh *", "docker *", "sudo *"]
    ask:   ["rm -rf *"]        # unattended: treated as deny and reported
  secrets: ["npm-read-token"]  # names in the runner cluster's Secret store, never sent by Back Office (planned, see below)
  environment:
    image: ghcr.io/org/back-office-runner:2.0-jdk21-node22   # must match the pool's allowed image patterns
    setupCommand: "./gradlew --offline help || true"
    caches: [gradle, npm]      # mounted from per-pool cache volumes (read-write, per-project key)
```

Hosts are normalised to lowercase host names (scheme, credentials, path and **port are dropped**:
Smokescreen ACLs are host based); only a leading `*.` wildcard is valid. Always added: the hosts of the
gateway URLs (`anthropicBaseUrl`, `openaiBaseUrl`, `apiBaseUrl`), the host of `spec.repository.url` and of every `spec.repositories[].url` (ADR 0034) and
`runner-controller.egress.always-allow` (e.g. the object storage host of presigned upload URLs).

`secrets` is **resolved and validated, not yet delivered into sandboxes (planned)**: Back Office merges it
(requested names ∩ pool `ceiling.secrets`) and the controller's `PolicyResolver` filters it again, but
neither `PodSpecRenderer` (Agent Sandbox / Job backends) nor `DockerBackend` mounts the named Secrets yet.

## Egress

**Decision: one egress proxy Deployment per pool plus a pod-level NetworkPolicy; no sidecar.**

A NetworkPolicy selects pods, not containers. With a Smokescreen *sidecar*, the pod needs an egress rule to
the internet for the sidecar, and the agent container in the same network namespace gets the same route: it
can ignore `HTTPS_PROXY` and connect directly, so the allowlist would be advisory. Making a sidecar
enforceable needs iptables owner matching (an init container with `NET_ADMIN`, as service meshes do), which
contradicts "drop all capabilities" and does not work reliably under gVisor/Kata.

So each pool gets:

| Object | Content |
|---|---|
| `Deployment bo-egress-<pool>` (2 replicas) | `back-office/egress-proxy` (Smokescreen compiled with `egress-proxy/main.go`), `BO_EGRESS_MODE=dynamic`, non-root, read-only rootfs, no SA token |
| `Service bo-egress-<pool>` | TCP 4750 |
| `Secret bo-egress-<pool>` | proxy ↔ controller token (generated once) |
| `NetworkPolicy bo-sandbox-<pool>` | selects `backoffice.io/sandbox=true, backoffice.io/pool=<pool>`; **no ingress**; egress only to the proxy pods on TCP 4750 and to the cluster DNS pods (`kube-system`, `k8s-app=kube-dns`) on 53 |
| `NetworkPolicy bo-egress-<pool>` | selects the proxy pods; ingress only from the pool's sandbox pods on 4750; egress to `0.0.0.0/0` except private ranges (`10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `100.64/10`), to `runner-controller.egress.allow-ranges`, to the controller pods (ACL pull) and DNS |

Per-job identity: the controller registers every job with a random proxy password and hands the sandbox
`HTTPS_PROXY=http://<jobId>:<password>@bo-egress-<pool>.<ns>.svc:4750` (file `proxy-url` of the job bundle).
The proxy's `RoleFromRequest` takes the role from `Proxy-Authorization: Basic`, and only if
`sha256(password)` equals what the controller registered; otherwise the request falls to the ACL default
rule, `enforce` with no domains. The ACL (Smokescreen `version: v1`, one `enforce` service per job with
the job's hosts as `allowed_domains`) and the credential hashes are pulled from the controller
(`GET /egress/v1/acl`, bearer = pool token, ETag, every 2 s and immediately for unknown roles). Smokescreen
additionally resolves each host and refuses private addresses unless allowed by `--allow-range` /
`--allow-address` (use them when Back Office itself runs in the same cluster). Every decision
(`CANONICAL-PROXY-DECISION`) is posted to `POST /egress/v1/decisions` and printed as `BO-EGRESS {json}`.

At completion the controller reports `egressDenied` (distinct refused hosts) and `egressLogDigest` =
`sha256:` + hex SHA-256 over the job's decisions, one line each, `at|host|ALLOW or DENY|reason`, in arrival
order, joined with `\n`.

The Agent Sandbox controller creates a shared NetworkPolicy per `SandboxTemplate`; with
`networkPolicyManagement: Managed` and no explicit `networkPolicy` it would allow the public internet. Every
template therefore carries an explicit `networkPolicy` with the same egress rules (proxy + DNS) and no
ingress. NetworkPolicies are additive, so the pool policy and the template policy together still allow only
proxy and DNS.

Optional hardening (not required): with Cilium, add a `CiliumNetworkPolicy` with `toFQDNs` on the proxy pods
so that even the proxy can reach only the union of allowlisted names; Smokescreen remains the per-job
authority and the evidence source.

Docker backend: the same design with Docker primitives — a per-job `--internal` network (no route out), a
per-job proxy container (`BO_EGRESS_MODE=static`, the job's rule as ACL default) attached to both the
bridge and the job network under the alias `egress-proxy`, and the agent container only on the job network.

## Sandbox pod

One container `agent` (runner image), no sidecar; cold pods have one init container `bundle` that copies the
job and git Secrets into memory volumes and exits:

- pod: `restartPolicy: Never`, `automountServiceAccountToken: false`, `enableServiceLinks: false`,
  `runtimeClassName` from the isolation (`STANDARD → gvisor`, `HIGH → kata`, configurable
  `runner-controller.kubernetes.runtime-classes`; jobs are refused when the class is not installed and
  `require-runtime-class=true`), `runAsNonRoot` 10001:10001, `fsGroup` 10001, seccomp `RuntimeDefault`,
  `activeDeadlineSeconds` = timeout + 120 s (cold starts), `terminationGracePeriodSeconds: 30`;
- container: `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `privileged: false`,
  capabilities `drop: [ALL]`, requests = limits for `cpu`, `memory`, `ephemeral-storage`;
- volumes: `/workspace` emptyDir (size limit = ephemeral storage), `/tmp` emptyDir, the job bundle at
  `/var/run/backoffice/job` and the git token(s) at `/var/run/backoffice/git` (`token` for a single-repository
  spec, `<key>/token` per repository of a multi-repository spec), both **writable memory
  emptyDirs** so the runner can delete the secret files before the agent starts (ADR 0029). Cold pods fill them
  with the init container `bundle` (same image and lock-down), which alone mounts the Secret `bo-job-<jobId>`
  (mode 0444, at `/var/run/backoffice/job-secret`) and the git Secret(s) selected for the job's repositories and
  their levels (legacy: at `/var/run/backoffice/git-secret`; multi-repository: volume `git-repo-<n>` at
  `/var/run/backoffice/git-repo-secrets/<key>`; none for `NONE`, ADR 0031); warm pods are filled by the
  controller. `RUNNER_GIT_TOKEN_FILE` / `RUNNER_GIT_TOKEN_DIR` tell the runner where to look. Cache PVCs at `/caches/<name>` with
  `GRADLE_USER_HOME`, `npm_config_cache`, ...;
- pids: Kubernetes has no per-pod pids field; the kubelet `podPidsLimit` of the node pool is the hard limit
  and the runner additionally sets `RLIMIT_NPROC` to `resources.pids` (`RUNNER_MAX_PROCS`);
- labels `backoffice.io/sandbox`, `backoffice.io/pool`, `backoffice.io/job-id`, `backoffice.io/project-id`,
  `backoffice.io/run-id`, `backoffice.io/isolation`, `app.kubernetes.io/managed-by=back-office-runner-controller`;
  annotations with job id, pool, deadline, policy narrowing notes, `backoffice.io/git-access` (highest level) and,
  for multi-repository jobs, `backoffice.io/git-repositories` (`docs=READ,src=PUSH_MR,...`).

Job bundle (`/var/run/backoffice/job`): `spec.json` (the `RunnerJobSpec` with `gateway`, timeout clamped),
`policy.json` (resolved `SandboxPolicy`), `job-id`, `job-token`, `proxy-url`, `ready` (written last). No
per-job data is passed in environment variables, so cold and warm sandboxes look the same to the runner.

## Git access (ADR 0031)

Back Office never holds a git credential (ADR 0013); it decides only **how much** git a job needs and signs
that level with the spec (`RunnerJobSpec.gitAccess`):

| Level | Derived for | Credential the controller mounts | Runner behaviour |
|---|---|---|---|
| `NONE` | no repository | none | no clone |
| `READ` | any job with a repository that does not push: `push = false`, Spec Kit analysis without push, `EVAL_CODING` benchmarks | `read` Secret of the matching `repoSecrets` entry | clone/fetch only; `repository.push` and the merge request are switched off, `remote.origin.pushurl = disabled://read-only-job`, `Git.push` refuses |
| `PUSH_MR` | IMPLEMENT or SPEC_KIT with `push = true` | `push` Secret of the matching entry | push `HEAD:refs/heads/<job branch>` to the spec URL with whitelisted push options |

Multi-repository jobs (ADR 0034) carry the level per `repositories[]` entry; `gitAccess` is then the highest
level among the entries (see [Repositories and workspace](#repositories-and-workspace-adr-0034)).

Selection in the runner-controller (`GitCredentialSelector`): `git.repo-secrets` entries
(`{repository, read, push}`; `repository` = `host/group/project`, `host/group/*`, `host`, `*.domain` or `*`,
most specific first), then the legacy `git.secrets` (chart `existingSecret` / `hostSecrets`) as **push-level**
fallback (warning at start). A READ job whose only match is a push credential and a PUSH_MR job whose match has
no push credential fail with `policy-violation: git credential: ...` before a sandbox exists;
`git.allow-push-credential-for-read=true` lifts the first refusal deliberately. No match = anonymous clone.
The selected Secret is the only git Secret in the pod (annotation `backoffice.io/git-access`); the Docker
backend writes `BO_GIT_TOKEN_READ` or `BO_GIT_TOKEN_PUSH` (legacy `BO_GIT_TOKEN` = push) into the token file.

Push options (`ALLOWED_PUSH_OPTIONS`): `merge_request.create`, `merge_request.target`, `merge_request.title`,
`merge_request.description`, `merge_request.draft`. Anything else (`merge_when_pipeline_succeeds`,
`auto_merge`, `remove_source_branch`, `ci.skip`, `ci.variable`, ...) is refused before git runs, and
`-c push.pushOption=` resets options from any git config. No `--force` exists in the runner. Merging is
prevented by the token itself: Developer role against a default branch protected with "Allowed to merge:
Maintainers" (docs/manual/integrations-git.md#gitlab-protect-branches).

## Repositories and workspace (ADR 0034)

A job can work in several repositories of its project. Back Office builds `RunnerJobSpec.repositories`, one
`JobRepository` per repository the step uses (rules in
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#step-repositories)):

| Field | Meaning |
|---|---|
| `key` | project repository key (`^[a-z][a-z0-9-]{0,30}$`) |
| `url` | git URL to clone (and to push to for `PUSH_MR`) |
| `baseBranch` | checked out before the agent starts; the diff is taken against it; target of the merge request; never pushed to |
| `access` | `NONE`, `READ` or `PUSH_MR` (Back Office sends READ or PUSH_MR) |
| `path` | workspace subdirectory, equal to `key` |
| `pushBranch` | `PUSH_MR` only: the job branch, never the base branch |
| `mergeRequest` | `PUSH_MR` only: `{create, title, description, draft}`, target = `baseBranch` |
| `primary` | exactly one entry: the agent's working directory and the legacy mirror |

**Workspace layout.**

```
/workspace/                 workspace root (agent working directory when the list is empty)
  service/                  primary entry  -> the agent starts here
  docs/                     PUSH_MR entry  -> same job branch pushed, own merge request
  api/                      READ entry     -> pushurl disabled, never pushed
```

Every entry is cloned into `/workspace/<path>` with the credential selected for it, its base branch pinned at
clone time and `.git/config` snapshotted; after the agent the runner restores the config, empties the hooks,
diffs and commits **per repository**. The same job branch is pushed to every `PUSH_MR` entry with the
whitelisted push options of ADR 0031; Back Office refuses a job branch that equals the default branch of any
`PUSH_MR` repository (it falls back to `run/<run id>`). `READ` entries get `remote.origin.pushurl =
disabled://read-only-job` and are never pushed. The runner never merges.

**Credentials.** The runner-controller selects a credential for each entry by (url, access) exactly as for the
single repository: the `read` Secret of the matching `repoSecrets` entry for `READ`, the `push` Secret for
`PUSH_MR`, refusals (`policy-violation: git credential: ...`) per entry. Every entry's host is added to the
job's egress allowlist.

**Result.** `RunnerResult.repositories[]` has one item per spec entry with the same `key`: `branch`,
`commitSha`, `diffStat`, `mergeRequestUrl`, `pushed` (true only when the job branch was pushed to that
repository) and `error` (clone, commit or push failure of that repository). The legacy top-level `branch`,
`commitSha`, `diff`/`diffStat` and `mergeRequestUrl` mirror the primary entry.

**Compatibility.** The legacy `repository` stays filled from the primary entry and `gitAccess` is the highest
access among the entries. `repositories` is omitted for jobs built the legacy way (coding benchmarks), so their
spec digests do not change; when it is present the canonical-JSON digest covers it
(`docs/api/fixtures/spec-digest-vectors.json` has a two-repository vector). A runner without
multi-repository support clones only the legacy repository (the primary); a result without `repositories` is
read as the primary's result. An older runner-controller selects one credential for the primary URL at the
top-level `gitAccess` (the highest level), which can mount a push credential for the primary although only
another repository is `PUSH_MR` - a residual risk until controllers are upgraded (ADR 0034).

### In the runner-controller and the runner

A job may work with several repositories, or none. The signed spec then carries
`repositories: [{key, url, baseBranch, access: NONE|READ|PUSH_MR, path, pushBranch?, mergeRequest?: {create, title,
description, draft}, primary, purpose?}]` and the levels above apply **per repository**:

| | Controller (`GitCredentialSelector.plan`) | Runner (`entrypoint.py`) |
|---|---|---|
| parsing | keys `^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$`, unique; unknown `access` refused; absent `access` = `READ` | the same, plus `path` relative without `.`-components, unique and not nested; `PUSH_MR` needs `pushBranch`; without an explicit primary the first `PUSH_MR` (else the first) repository is primary |
| credential | selected per repository by its URL and its level from `repoSecrets` (most specific pattern); `NONE` and anonymous repositories get nothing | reads `<RUNNER_GIT_TOKEN_DIR>/<key>/token` of each spec repository once into memory and deletes it |
| refusal | `policy-violation: repository '<key>' (<host/path>): git credential: ...` (READ with only a push Secret unless `allowPushCredentialForRead`; PUSH_MR without a push Secret) | - |
| delivery | Kubernetes: one Secret volume per repository with a credential, init container only, copied to `git/<key>/token`; warm pods: written by the controller; Docker: `BO_GIT_TOKEN_READ` / `BO_GIT_TOKEN_PUSH` by each repository's level | one `Git` per repository with its own token; the inline credential helper is bound to the repository host **and path** (`credential.useHttpPath=true`, `BO_GIT_PATH`), so a token is never answered for a sibling repository on the same host |
| egress | every repository host is a mandatory egress host (like the gateway) | - |

Workspace: `/workspace/<path>` per repository (cloned and checked out at `baseBranch` exactly like the single
repository). The agent, `setupCommand`,
`verifyCommand`, Spec Kit (`.specify`; only when the spec does not say `specKit: false`, ADR 0036), skills and `collectArtifacts` run in the **primary** repository; with an
empty list in `/workspace`. The agent may reach the other repositories (OpenCode `external_directory` allows exactly
their paths, Claude Code gets `--add-dir`), and its prompt ends with a short `## Repositories` section (key, path,
access, `purpose`). READ / NONE clones get `remote.origin.pushurl = disabled://read-only-job`; PUSH_MR clones check
out their `pushBranch`.

After the agent, per repository: `.git/config` restored and `.git/hooks` emptied (a repository whose `.git` was
replaced is skipped with an error), commit with the `Back-Office-Run` trailer, diff against the pinned base
(`--no-ext-diff --no-textconv`), and only for `PUSH_MR` a push of `HEAD:refs/heads/<pushBranch>` to the spec URL
with the whitelisted push options (target = `baseBranch`). One repository failing does not stop the others.
`result.repositories[] = {key, branch, commitSha, diffStat, mergeRequestUrl, pushed, error}`; the job is `FAILED`
when any `PUSH_MR` repository has an error (`error` names it). The legacy top-level `branch`, `commitSha`,
`diffStat`, `diff` and `mergeRequestUrl` come from the primary repository; other repositories' diffs are artefacts
`diff:<key>`. `result.security.gitAccessByRepository` records the levels.

Compatibility: a spec without `repositories` (or with an empty list next to the legacy `repository`) is a
single-repository spec and runs exactly as before (`/workspace/repo`, `spec.gitAccess`, `git/token`, no
`result.repositories`). The digest algorithm is unchanged (`docs/api/fixtures/spec-digest-vectors.json` has a
two-repository vector).

## Run workspaces and agent sessions (ADR 0044)

- Step config `workspace` / `sessionRole` / `prepareOnly` (`StepWorkspace`, validated with the step repositories)
  becomes `RunnerJobSpec.workspace {name, key, sessionRole, prepareOnly}`; `name = bo-ws-<run id hex>-<key>`.
- Control plane: `runner_workspace` (V54: pool, runner, active job, release time) and `agent_job.workspace_name`.
  `JobLifecycle.routeJob` binds the workspace and prefers its pool; `lockQueued(pool, runner)` skips jobs whose
  workspace is busy or on another runner; the lease claims it with one conditional `UPDATE` (`active_job_id`), and
  `finish` / `requeue` free it. `RunProgressService.finished` calls `AgentRunner.releaseWorkspaces` (now; 24 h after
  a failure). `POST /runner/v2/workspaces/sync` (`WorkspaceSync.decide`) tells a controller what to delete.
- runner-controller: DOCKER mounts a named volume (`docker volume create`, labels managed-by / pool /
  `backoffice.io/workspace`); K8S_JOB and AGENT_SANDBOX a ReadWriteOnce PVC sized by `ephemeralStorage`
  (`workspace-storage-class`), never a warm pod. A sync every `workspace-sync-seconds` (60) deletes released volumes
  not used by a running sandbox.
- runner: a clone is reused when `/workspace/.bo/repos/<path>.json` names the same URL: `.git` checked, hooks and
  grafts removed, `.git/config` rewritten (`trusted_git_config`), fetch, access of the job; the working tree stays.
  READ repositories are not committed in a workspace. Sessions: Claude Code `--session-id` / `--resume` with
  `uuid5(workspace, role)` in `CLAUDE_CONFIG_DIR=/workspace/.claude-config`; OpenCode `XDG_DATA_HOME=/workspace/.bo/opencode/<role>`
  and `--continue`. `prepareOnly`: clone + setupCommand + `.git` restore, no agent, no commit.

## Runner protocol v2 (summary; contract in `docs/api/openapi.yaml`, tag `runner-v2`)

| Call | Who | Purpose |
|---|---|---|
| `POST /runner/v2/register` | controller, with a registration token | returns runner id and a runner token (`bo_rt_`) |
| `POST /runner/v2/jobs/lease` | controller | long poll up to 30 s; returns up to N assignments for its pool labels |
| `POST /runner/v2/jobs/{id}/heartbeat` | controller | extends the lease, reports phase; response carries `cancelRequested` |
| `POST /runner/v2/jobs/{id}/uploads` | sandbox (job token) | presigned object-storage URL for large artefacts and logs |
| `POST /internal/runner/jobs/{id}/events` / `result` | sandbox (job token) | streamed events and the agent's result (unchanged shapes, plus `digests` and `policy`) |
| `POST /runner/v2/jobs/{id}/complete` | controller | terminal status from the platform's point of view (exit code, OOM, deadline, egress log digest) |
| `GET /runner/v2/config` | controller | pool policy ceiling, allowed images, gateway URLs, signing public key |

Assignments are signed (Ed25519, same key as attestations) over `jobId|specDigest|leaseExpiresAt`; see
"Assignment verification".

Completion mapping (controller view; the work's own verdict is the `RunnerResult`):

| Observed | `status` / `reason` |
|---|---|
| agent container exit 0 | `SUCCEEDED` (the runner exits 0 whenever it delivered its result, even for a failed task) |
| exit 3 | `FAILED` / `ResultNotDelivered` |
| terminated `OOMKilled` | `FAILED` / `OOMKilled` |
| pod or Job `DeadlineExceeded`, claim `ClaimExpired`, sandbox `SandboxExpired`, controller deadline | `TIMED_OUT` / `DeadlineExceeded` |
| `ImagePullBackOff`, `ErrImagePull`, `InvalidImageName` for longer than 90 s | `FAILED` / `ImagePullBackOff` |
| `CreateContainerConfigError` / `CreateContainerError` for longer than 90 s | `FAILED` / that reason |
| pending longer than 15 min | `FAILED` / `StartTimeout` |
| pod `Evicted` | `FAILED` / `Evicted` |
| sandbox disappeared | `FAILED` / `SandboxLost` |
| image not allowed, RuntimeClass missing, ceiling violated | `FAILED` / `policy-violation: ...` |
| backend could not create the sandbox | `FAILED` / `SandboxLaunchFailed: ...` |
| cancel requested | `CANCELLED` / `cancel-requested` |

## Control plane implementation (agent-runner, llm-gateway)

What the Back Office side does exactly; operator guide in `docs/guides/runner-fleet.md`, runbook in
`docs/runbooks/agent-jobs.md`.

- **Queue.** `agent_job` (Flyway `V51__runner_fleet.sql`, which also moved the v1 `runner_job` rows over and
  dropped the v1 tables). `AgentRunner.submit` routes the job to a pool at submit time (pool labels ⊇ the
  merged `poolSelector`, project allowed by `projectIds`, policy resolvable against the pool - preferring
  pools with an online runner, then the shortest queue) and stores the effective policy and its `sources`.
  A job no pool can take stays `QUEUED` without a pool and with the reason; the lease scan routes it again
  when pools change. `submit` is idempotent on the (DNS-normalised) `jobLabel`. Priority: the continuation's
  priority, else 10 for workflow jobs and 0 for benchmark jobs.
- **Lease.** `SELECT … FOR UPDATE SKIP LOCKED` by `priority DESC, queued_at`, capped by `maxJobs`, `freeSlots`,
  the pool's `maxConcurrentJobs` and the runner's `maxConcurrent`. The policy is resolved again against the
  current pool ceiling (a violation fails the job with `policy-violation: …`). The long poll waits on an
  in-process signal (woken by submits on the same replica) and re-checks the database every
  `lease-poll-interval` (1 s) for other replicas.
- **States.** `LEASED` until the first heartbeat, `STARTING`/`RUNNING` from the heartbeat phase. A lease
  lives `lease-duration` (60 s) and every heartbeat extends it. The lease scan (every 10 s, one replica via
  `pg_try_advisory_xact_lock`): an expired `LEASED` job is re-queued (up to `max-unacknowledged-leases`, then
  `FAILED`); an expired `STARTING`/`RUNNING` job is re-queued once unless it was `RUNNING` with
  `repository.push=true` or a `PUSH_MR` entry in `repositories` (it may have pushed) - otherwise it ends `LOST` (terminal; reported to the engine
  as `FAILED`). `QUEUED` past `queue-timeout` (24 h) ends `TIMED_OUT` with `queue-timeout`.
- **Completion.** The controller's status wins, except that `SUCCEEDED` needs a successful agent result
  (no result → `FAILED` "sandbox exited without posting a result"; a failed result → `FAILED`). The
  terminal transition revokes the job token and enqueues the continuation (shared `WorkQueue`, same
  transaction) with `jobId`, `status` (`RunnerJobStatus`) and `agentJobStatus` added to its payload.
- **Results.** Stored inline in `agent_job.result`; above 64 kB the full JSON goes to the `BlobStore`
  (`agent-jobs/<jobId>/result.json`) and a trimmed copy stays inline. Usage is not booked from the result
  (the proxy metered every call); the reported cost is kept on the job for the admin view.
- **Tokens.** 256 random bits base64url with a prefix, stored as SHA-256 hex. The job token is issued per
  lease (a re-queued job gets a new one) and is valid while the job is active and the lease has not
  expired. `JobCredential.modelAllowlist` = the spec's model plus `backoffice.runner.extra-allowed-models`
  patterns; `maxCostUsd`/`maxTokens` come from the effective policy.
- **Assignment signature.** `specDigest` = SHA-256 hex of the canonical JSON `{"policy":…,"spec":…}` (RFC 8785
  with null members dropped, see "Assignment verification"); signature = `EnvelopeSigner.sign("jobId|specDigest|leaseExpiresAt")`
  with `leaseExpiresAt` exactly as sent. Without a signer bean assignments are unsigned (development).
- **Policy merge.** Implemented in `SandboxPolicyResolver`: isolation strongest wins (DEV < STANDARD <
  HIGH, so a platform default of STANDARD forbids DEV pools - the local profile sets DEV); resources,
  timeout and caps minimum; `network.allow` pattern intersection (`*` = one DNS label, no port = any port)
  plus the API host and the git hosts (every repository of the job); `tools.deny`/`ask` union, `tools.allow` intersection; secrets = the
  requested names ∩ the pool's `ceiling.secrets` (the pool list is an allowlist, not a default); image = most
  specific, must match `allowedImages` (empty list = only the pool's or platform's own image); `poolSelector`
  union, conflicts are errors. Empty lists inherit. The spec's `timeout` caps `timeoutSeconds`.
- **LLM proxy.** `/api/v1/llm/anthropic/v1/messages` (+ `count_tokens`, not metered),
  `/api/v1/llm/openai/v1/chat/completions`, `/api/v1/llm/openai/v1/models`. Job token as `x-api-key` or
  bearer. The OpenAI endpoint serves every allowlisted OpenAI-protocol provider (OpenAI, OpenAI-compatible,
  Google, Mistral, Ollama) in that order of preference for the model id. Streaming responses are forwarded
  line by line (flushed per SSE event); usage is read from `message_start`/`message_delta` (Anthropic) and
  from the final `usage` chunk (OpenAI, `stream_options.include_usage=true` is added). Rate limiter:
  `BATCH` for benchmark jobs, `INTERACTIVE` otherwise; 429 `rate-limited` when the wait would exceed `max-wait`.
- **Budgets.** Pre-check before the call with an estimate (request bytes / 4 input tokens + `min(max_tokens,
  4096)` output tokens at catalogue pricing) against the job caps and the platform/project month; 402
  `budget-exceeded` (with `scope` = `JOB`, `PROJECT` or `PLATFORM`). The ledger is decorated so every
  booked cost (proxy, workflow LLM steps, reviews, benchmarks) moves `budget_state`; `BudgetAlert` /
  `BudgetExceeded` events fire once per scope and month. In-process `LlmGateway` calls (workflow LLM steps,
  reviews, benchmarks) run the same pre-check (`LlmGatewayConfiguration.budgetPrecheck`, as the system) and
  fail with `IntegrationException` system `budget-exceeded` (not retryable). A proxy refusal is remembered on
  the job (`budget_job_spend.refused_reason`/`refused_at`, llm Flyway `V4__budget_job_refusals.sql`, read
  through the `BudgetRefusals` port); when such a job ends without succeeding (and was not cancelled),
  `RunnerProtocolService.budgetTagged` sets its reason to `budget-exceeded: <why>`. Exceeded budgets also
  appear as a "raise the limit" card in the turn queue of users who can manage the budget.

## Backends in runner-controller

Chosen by `runner-controller.backend` (`AUTO` default) and capability detection at start: `AUTO` picks
`AGENT_SANDBOX` when the four Agent Sandbox CRDs exist, else `K8S_JOB` when the Kubernetes API is reachable,
else `DOCKER` when `docker version` works.

| Backend | When | Notes |
|---|---|---|
| `AGENT_SANDBOX` | Agent Sandbox CRDs present | kubernetes-sigs/agent-sandbox **v1beta1**: `SandboxTemplate` (`extensions.agents.x-k8s.io/v1beta1`) per (image, isolation, RuntimeClass, resources, caches) hash; `SandboxWarmPool` (`extensions.agents.x-k8s.io/v1beta1`, `replicas` = pool warm size, `sandboxTemplateRef`, `updateStrategy: Recreate`); `SandboxClaim` (`extensions.agents.x-k8s.io/v1beta1`, `warmPoolRef`, `lifecycle.shutdownTime` = deadline, `shutdownPolicy: Delete`, job annotations via `additionalPodMetadata`) per job; with warm size 0 a cold `Sandbox` (`agents.x-k8s.io/v1beta1`, `podTemplate`, `shutdownTime`, `shutdownPolicy: Delete`) per job |
| `K8S_JOB` | no CRDs | `batch/v1 Job` (`backoffLimit: 0`, `activeDeadlineSeconds`, `ttlSecondsAfterFinished`) with the same pod spec and RuntimeClass, no warm pool |
| `DOCKER` | local development | `docker run` with a per-job internal network and proxy container, read-only rootfs, tmpfs workspace, `--cap-drop ALL`, `no-new-privileges`, `--pids-limit`, `--memory`, `--cpus`; runc (no gVisor) |

Bundle delivery: a cold sandbox mounts the per-job Secret. A warm pod exists before the job, so it cannot
mount a per-job Secret, and a `SandboxClaim` with `env` forces a cold start. The warm pod therefore runs the
runner with `RUNNER_WAIT_FOR_JOB=1`; once the claim is bound (`status.sandbox.name`; the pod has the
Sandbox's name) the controller writes the bundle (from the same per-job Secret, which is owned by the claim)
into the pod's memory-backed volumes with `pods/exec`, `ready` last. The controller's Role therefore needs
`pods/exec` in the sandbox namespace.

Controller RBAC in the sandbox namespace: `secrets`, `pods` (get/list/watch), `pods/exec` (create; warm
pools only), `jobs.batch`, `deployments.apps`, `services`, `networkpolicies.networking.k8s.io`, and the four
Agent Sandbox resources (all verbs). Cluster scope (read-only, optional): `customresourcedefinitions` and
`runtimeclasses.node.k8s.io` for capability detection (without it detection falls back to a namespaced probe
and RuntimeClass checks are skipped).

Orphans: at start the controller lists its sandboxes (`app.kubernetes.io/managed-by`, `backoffice.io/pool`),
deletes those it does not run and reports them `LOST` / `controller-restarted`.

## Agent configuration inside the sandbox

- OpenCode: generated `opencode.json` with `provider.anthropic.options.baseURL = <anthropicBaseUrl>/v1`,
  `provider.openai.options.baseURL = <openaiBaseUrl>` (with `/v1`); other providers (Google, Mistral, Ollama,
  OpenAI-compatible) use provider `backoffice` (`@ai-sdk/openai-compatible`) on the gateway's OpenAI
  endpoint; `apiKey = "{env:BACKOFFICE_JOB_TOKEN}"` (the token is not written to disk); `permission` from the
  tool policy: `bash` = `"*": deny` when an allowlist exists (else allow), allow patterns, then deny and ask
  patterns last (OpenCode applies the last matching rule); `webfetch`, `websearch`, `external_directory`,
  `question` denied.
- Claude Code: `ANTHROPIC_BASE_URL=<anthropicBaseUrl without /v1>`, `ANTHROPIC_AUTH_TOKEN=<job token>`,
  settings rendered from the tool policy (`permissions.allow` = file tools + `Bash(<pattern>)` or `Bash`
  without allowlist, `permissions.deny` = deny + ask as `Bash(<pattern>)`, `defaultMode: dontAsk`) passed with
  `--settings` and written to `.claude/settings.local.json` (git-excluded); `--permission-mode dontAsk`. A
  repository's own `.claude/settings.json` allow rules still add to this (logged as a warning): the tool policy
  is defence in depth, the boundary is the sandbox.
- Ask means deny in unattended jobs: reported as a PROGRESS event and in `RunnerResult.policy.askTreatedAsDeny`;
  tool calls the agent reported as refused (Claude Code `permission_denials`) are in `RunnerResult.policy.denials`.
- `HTTPS_PROXY`/`HTTP_PROXY`/`https_proxy`/`http_proxy`/`ALL_PROXY` = the `proxy-url` of the bundle; `NO_PROXY`
  removed. The runner's own callbacks use the same proxy.
- The **agent token** is the only credential in the agent's environment (ADR 0029). The entrypoint reads the
  job token and the git token into memory and deletes both files, marks itself non-dumpable
  (`prctl(PR_SET_DUMPABLE, 0)`), exchanges the job token for a gateway-only agent token
  (`POST /api/v1/internal/runner/jobs/{id}/agent-token`) and starts the agent with that token only; provider
  keys, `GIT_TOKEN`, `RUNNER_CALLBACK_TOKEN`, `RUNNER_GIT_TOKEN_FILE` are removed. Git credentials reach only
  the git processes that need them (clone/fetch before the agent, push after it) through an inline helper that
  answers only for the repository host (per repository for multi-repository jobs, ADR 0034). After the agent and after `verifyCommand` the entrypoint kills stray
  processes of the sandbox user, restores `.git/config`, empties `.git/hooks`, diffs with
  `--no-ext-diff --no-textconv` against the base pinned at clone time and pushes `HEAD:refs/heads/<branch>` to
  the spec's repository URL (never the default branch, never forced), only for `gitAccess: PUSH_MR` and only
  with whitelisted push options (never a merge, ADR 0031). `RunnerResult.security` records what was enforced
  (including `gitAccess`) (older controllers with read-only Secret mounts leave the files readable: reported there and logged).
- Large artefacts (over 2 MB), the full diff when it exceeds 2 MB, and the complete runner log are uploaded
  through `POST /api/v1/runner/v2/jobs/{id}/uploads` (job token) and a presigned `PUT`, and referenced as
  artefacts with media type `application/vnd.backoffice.blob-ref+json` (`{blobKey, sha256, sizeBytes,
  mediaType}`). `RunnerResult.digests` carries `diffSha256` (full diff) and `logSha256`.

## Personal runners (ADR 0040)

A user may have one **personal pool** (`runner_pool.owner_user_id`, `/api/v1/me/runner`, `fleet.personal`). Steps of
a private project (ADR 0038) whose run a person started carry `RunnerJobSpec.personalRunnerOf` = that person; routing
sends them only to that person's pool when it exists (waiting while it is offline) and never sends anything else
there. The pool is served by a runner-controller with the DOCKER backend on the user's computer, started with the
`docker run` command generated at registration.

`llm_credentials = LOCAL_LOGIN` (personal pools only): the assignment carries `spec.llmCredentials = LOCAL_LOGIN`
(signed) and `api.anthropic.com` in the mandatory egress. The controller (DOCKER only, `CLAUDE_CODE_OAUTH_TOKEN` set,
else `local-login: ...` failure) writes the login as bundle file `claude-login-token`; the runner consumes and
deletes it, starts Claude Code with `CLAUDE_CODE_OAUTH_TOKEN` and without gateway URL/token, and reports
`usage.billing = SUBSCRIPTION`. Such a step costs 0 in runs and budgets; the estimate stays on the agent job.
OpenCode jobs of the pool use the gateway as usual.

## Agent Sandbox prerequisites

- Agent Sandbox controller and CRDs (`kubernetes-sigs/agent-sandbox`, API **v1beta1**: `sandboxes.agents.x-k8s.io`,
  `sandboxtemplates.extensions.agents.x-k8s.io`, `sandboxwarmpools.extensions.agents.x-k8s.io`,
  `sandboxclaims.extensions.agents.x-k8s.io`; the extensions controller must be enabled for templates,
  warm pools and claims), installable by the runner chart.
- RuntimeClass `gvisor` (GKE Sandbox, or `runsc` installed on nodes) and optionally `kata` (AKS Kata
  node pools, EKS with Kata on metal nodes); the chart only references them.
- A CNI that enforces NetworkPolicy (Calico, Cilium, cloud defaults) — without it the egress allowlist is not
  enforced.
- Kubelet `podPidsLimit` on the sandbox node pool.
