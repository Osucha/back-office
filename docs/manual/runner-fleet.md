# Runner fleet

The runner fleet is where agent jobs execute: pools of runner-controllers in your Kubernetes clusters (or
Docker on a laptop) that pull jobs from Back Office over HTTPS. Back Office never receives credentials for
those clusters. This chapter covers pools, registering a runner, sandbox policies and the agent-job view.
How a single job runs is in [agent-execution.md](agent-execution.md).

**Who needs this:** platform administrator (`fleet.manage`), runner-cluster operator; project owners for
the project sandbox policy.

<a id="pools"></a>
## Pools

A pool is a group of controllers that run the same kind of job: one cluster, a region, GPU nodes, one
customer. Open **Administration** → **Runner fleet** (`/admin/fleet/pools`; viewing needs `fleet.view`,
changes `fleet.manage`). The tabs **Pools**, **Runners** and **Agent jobs** show the fleet.

**Create a pool**

1. **New pool**.
2. **Pool**: **Pool id** (used in chart values and job labels; cannot be changed), **Name**,
   **Description**, **Labels** (for example `region=eu`, `gpu=true`; jobs pick pools by these labels
   through the policy's pool selector).
3. **Capacity and images**: **Warm pool size** (pre-started sandboxes per template, Agent Sandbox backend
   only), **Max concurrent jobs** across the pool, **Allowed images** (patterns with `*`, for example
   `registry.example.com/back-office/runner:*`; empty = only the ceiling's or platform's own image),
   **Projects** that may use the pool (empty = all).
4. **Policy ceiling**: the most a job in this pool may get: isolation, resources and time, network
   allowlist, tools, environment image and allowed secret names, cost and token caps per job.
5. **Create pool**.

A pool with queued or running jobs cannot be deleted. Changing the ceiling applies to the next lease;
queued jobs that no longer fit fail with `policy-violation: ...`.

<a id="add-runner"></a>
## Add a runner to a pool

**Prerequisites in the execution cluster** (check them with
`deploy/scripts/check-runner-cluster.sh --standard gvisor --high kata --url https://backoffice.example.com`):

1. Kubernetes 1.29+ and a CNI that **enforces** NetworkPolicy (Calico, Cilium, GKE Dataplane V2, AKS
   Cilium, EKS VPC CNI with network policies on). Without it the egress allowlist is not enforced.
2. RuntimeClass `gvisor` for STANDARD isolation (GKE Sandbox, or `runsc` on the nodes), optionally Kata
   for HIGH (AKS `kata-vm-isolation`, kata-deploy elsewhere).
3. Recommended: Agent Sandbox v1.0.4 CRDs and controller, once per cluster:
   `kubectl apply -f https://github.com/kubernetes-sigs/agent-sandbox/releases/download/v1.0.4/sandbox-with-extensions.yaml`
   (or `agentSandbox.install=true` in exactly one release). Without them the `K8S_JOB` backend runs plain
   Jobs with no warm pool.
4. Outbound HTTPS from the cluster to the Back Office public URL; nothing inbound.
5. Images the cluster can pull: `runner-controller`, `egress-proxy` and the agent image(s) listed in the
   pool's **Allowed images**.
6. Optional: KEDA 2.x for queue-based autoscaling.

**Register**

1. On **Pools** press **Add runner** on the pool row.
2. In **Add a runner to** *pool* press **Create registration token**. The token (`bo_rrt_...`) is single
   use and expires after 24 hours; it is shown once. Creating another one does not revoke this one.
3. **1. Save the chart values** as `runner-<pool>.values.yaml`:

```yaml
backOffice:
  url: https://backoffice.example.com
  poolId: eu-1
registration:
  token: bo_rrt_...          # single use; or registration.existingSecret (key RUNNER_REGISTRATION_TOKEN)
backend: auto                # AGENT_SANDBOX when the CRDs exist, else K8S_JOB
```

4. **2. Install the runner chart**:

```sh
helm upgrade --install bo-runner-eu-1 deploy/helm/back-office-runner \
  --namespace bo-runner-eu-1 --create-namespace \
  -f runner-eu-1.values.yaml \
  --set controller.image.repository=registry.example.com/back-office/runner-controller \
  --set controller.image.tag=<version> \
  --set egressProxy.image=registry.example.com/back-office/egress-proxy:<version>
```

   For production keep the token out of the values file: create
   `kubectl -n bo-runner-eu-1 create secret generic bo-runner-registration --from-literal=RUNNER_REGISTRATION_TOKEN=bo_rrt_...`
   and set `registration.existingSecret=bo-runner-registration`. Chart variants: `values-gvisor.yaml`,
   `values-kata.yaml`, `values-dev.yaml`, `values-aws|gcp|azure|onprem.yaml`.
5. Add git credentials for the agents: [integrations-git.md](integrations-git.md#git-install).
6. **Verify**: the controller logs `registered as runner ...`; **Runners** lists it **online** (seen in the
   last 90 s) with its version, backends and runtime classes; a workflow with an **Implement** step creates
   a `SandboxClaim` or Job in `bo-runner-eu-1-sandboxes`.

On registration the controller exchanges the registration token for a runner token (`bo_rt_...`) and stores
it in the Secret `<fullname>-token` (key `runnerToken`, `tokenSecret.name`) in **its own** namespace, never in the sandbox
namespace. Later starts reuse it; the registration token can then be deleted.

**Revoke or replace a runner**: **Runners** → **Revoke** invalidates the runner token at once; jobs it held
go back to the queue. To re-register the same installation: create a new registration token, put it into the
registration Secret, delete the Secret `<fullname>-token`, restart the controller
(`kubectl -n bo-runner-eu-1 rollout restart deployment -l app.kubernetes.io/name=back-office-runner-controller`).

**Laptop**: the compose profile `full` runs a runner-controller with the Docker backend; put the token into
`RUNNER_REGISTRATION_TOKEN` in `deploy/compose/.env` (pool `default`).

**Scaling**: `maxConcurrent` sandboxes per controller replica (default 5 in the chart); `autoscaling.type=keda`
scales replicas on the pool's queue (`/api/v1/runner/v2/pools/<pool>/queue`). Size the sandbox node pool for
`maxReplicas × maxConcurrent` pods. More: `docs/guides/runner-fleet-install.md`, `docs/runbooks/scaling.md`.

<a id="sandbox-policies"></a>
## Sandbox policies

Every job runs under an effective policy resolved from five levels, **narrowing only**: platform defaults
→ pool ceiling → project → task template → workflow node. A lower level can restrict but never widen.

| Field | How levels combine |
|---|---|
| Isolation | strongest wins: DEV < STANDARD (gVisor) < HIGH (Kata) |
| CPU, memory, ephemeral storage, pids, timeout, `maxCostUsd`, `maxTokens` | the minimum |
| Network allowlist | intersection with every level above (`*.` = one DNS label); Back Office host and git host always added |
| Tools allow / deny / ask | allow: intersection; deny and ask: union (ask = deny when unattended) |
| Secrets | requested names limited to the pool's allowed names |
| Image | most specific level wins; must match the pool's **Allowed images** |
| Pool selector | union; contradicting values are an error |

**Project level**: open the project → **Sandbox** tab. **Project policy** edits the project's level (needs
`project.manage`; others see it read-only); **Effective for this project** shows what a job gets today and
which level set each field. Press **Save sandbox policy**. Templates and nodes narrow further in the task
library and the designer.

**Platform level**: server properties `backoffice.runner.platform-policy.*` (isolation via
`RUNNER_PLATFORM_ISOLATION`, default STANDARD) and `RUNNER_EXTRA_ALLOWED_MODELS` for models every job may
call. Details: `docs/guides/runner-fleet.md`.

<a id="agent-jobs"></a>
## Agent jobs

**Administration** → **Runner fleet** → **Agent jobs** lists every job handed to the fleet: **State**,
**Work** (project, run, node), **Model**, **Pool**, **Duration**, **Cost**, **Queued**. Filter by pool and
status. Opening a job shows its reason, the runner and sandbox, **Egress denied** hosts, and the
**Effective policy** with the source of each field. **Cancel job** (needs `fleet.manage`) stops an active job.

| State | Meaning |
|---|---|
| `QUEUED` | waiting for a runner; the reason says why when no pool fits or no runner is online |
| `LEASED` | a controller took it, no heartbeat yet |
| `STARTING`, `RUNNING` | sandbox starting / agent working |
| `SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED` | finished; failed jobs carry a reason |
| `LOST` | the controller stopped heartbeating while the job might have pushed |

The **Pools** tab shows per pool the online runners, queued and running jobs. The alert
`BackOfficeRunnerPoolWithoutRunners` fires when jobs queue for 10 minutes with no runner online.
Symptoms and fixes: [troubleshooting.md](troubleshooting.md#jobs).
