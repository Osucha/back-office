# Runner fleet: pools, registration, sandbox policies, budgets

Coding agents run on **runner-controllers** in your clusters, never inside Back Office (ADR 0024,
[architecture](../architecture/10-agent-execution.md)). Back Office queues agent jobs; a controller leases
them over HTTPS, runs each in a sandbox and reports back. This guide is for platform operators
(`fleet.manage`) and project owners (`project.manage`).

## 1. Create a pool

A pool is a group of controllers that run the same kind of jobs (a cluster, a region, GPU nodes, one
customer). *Administration → Runner fleet → New pool*, or:

```bash
curl -X POST "$BO/api/v1/admin/runner-pools" -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" -d '{
  "id": "eu-default", "name": "EU default",
  "labels": { "region": "eu" },
  "allowedImages": [ "ghcr.io/org/back-office-runner:*" ],
  "maxConcurrentJobs": 10, "warmPoolSize": 2,
  "projectIds": [],
  "ceiling": {
    "isolation": "STANDARD",
    "resources": { "cpu": "4", "memory": "8Gi", "ephemeralStorage": "20Gi" },
    "network": { "allow": [ "registry.npmjs.org", "repo.maven.apache.org", "*.gradle.org" ] },
    "secrets": [ "npm-read-token" ],
    "environment": { "image": "ghcr.io/org/back-office-runner:2.0-jdk21-node22" }
  }
}'
```

- `labels` are matched by `poolSelector` in project, template or node policies.
- `projectIds` empty = every project may use the pool.
- `allowedImages` are glob patterns; empty = only the ceiling's (or platform's) own image.
- `ceiling.secrets` is an **allowlist** of Secret names in the runner cluster; a job is entitled to a secret
  only when its policy asks for it. Back Office never sees the values. **Planned, not yet delivered:** the
  list is resolved and validated (Back Office and runner-controller), but the controller does not yet mount
  the named Secrets into sandboxes (neither the Kubernetes backends nor the Docker backend), so the agent
  does not receive them today.
- A pool cannot be deleted while it has queued or running jobs.

## 2. Register a controller

*Administration → Runner fleet → Pools → Add runner* (or
`POST /admin/runner-pools/{poolId}/registration-tokens`) returns a **single-use** `bo_rrt_` token valid
24 h and a `helmValues` snippet with the keys of the `back-office-runner` chart:

```yaml
backOffice:
  url: "https://backoffice.example.com"
  poolId: "eu-default"
registration:
  token: "bo_rrt_..."      # or registration.existingSecret (key RUNNER_REGISTRATION_TOKEN)
backend: auto
```

Install the runner chart with it (`helm upgrade --install runners deploy/helm/back-office-runner -f
values.yaml`, see [runner-fleet-install.md](runner-fleet-install.md)). The controller calls
`POST /api/v1/runner/v2/register` once, receives its `bo_rt_` runner token (store it in the cluster; it is
shown only once) and from then on long-polls `/api/v1/runner/v2/jobs/lease`. *Administration → Runner
fleet → Runners* shows each controller with `lastSeenAt` and `online` (seen in the last 90 s). **Revoke** a runner to invalidate its token; the jobs it held go back to
the queue.

Autoscaling: `GET /api/v1/runner/v2/pools/{poolId}/queue` (runner token) returns `{queued, running}` for a
KEDA metrics-api scaler.

### Run workspaces (ADR 0044)

Steps that name a run workspace keep `/workspace` between the steps of one run. On Docker (also personal runners)
that is a named volume `bo-ws-...`; on Kubernetes a ReadWriteOnce PVC in the sandbox namespace (chart value
`workspaces.storageClass`, empty = the cluster default; the controller needs `create` / `delete` on
persistentvolumeclaims, which the chart grants). The controller reports its workspaces every minute and deletes the
released ones. Docker volumes have no size limit: watch the disk of a personal runner.

## 3. Sandbox policies

The policy of a job is resolved **narrowing only**: platform (`backoffice.runner.platform-policy.*`) →
pool ceiling → project → task template / workflow node. A lower level can restrict but never widen:

| Field | Rule |
|---|---|
| `isolation` | strongest wins: DEV < STANDARD (gVisor) < HIGH (Kata) |
| `resources.*`, `timeoutSeconds`, `maxCostUsd`, `maxTokens` | the minimum |
| `network.allow` | intersection with every level above (`*` = one DNS label, no port = any port); the API host and the git host are always added |
| `tools.allow` | intersection; `tools.deny` and `tools.ask` are unions (unattended jobs treat ask as deny) |
| `secrets` | what the job asks for, limited to the pool's allowed names (others are dropped with a warning); resolved and validated only, not yet delivered into sandboxes (planned) |
| `environment.image` | most specific level wins; must match the pool's `allowedImages` |
| `poolSelector` | union; contradicting values are an error |

Project owners set the project level in the project tab *Sandbox* or
`PUT /api/v1/projects/{projectId}/sandbox-policy` (`project.manage`); `GET` (`project.view`) returns the
project policy, the ceiling, the effective policy and `sources` (which level set each field).

Every agent job shows its effective policy and sources in *Administration → Runner fleet → Agent jobs*.

## 4. Budgets

| Scope | Where | Permission |
|---|---|---|
| Platform month | `PUT /api/v1/admin/budget` | `admin.settings.manage` |
| Project month | `PUT /api/v1/projects/{projectId}/budget` | `project.manage` (read: `project.usage.view`) |
| Per job | `maxCostUsd`, `maxTokens` in the sandbox policy | template / project |

`{ "monthlyLimitUsd": 500, "alertAtPercent": 80, "hardStop": true }`. `GET /api/v1/budgets` lists the
budgets you may see with the month's spend, a linear forecast and the state (`OK`, `ALERT`, `EXCEEDED`,
`NO_LIMIT`). Crossing the alert threshold emits `BudgetAlert`, reaching the limit `BudgetExceeded`
(both on the event stream, once per month). With `hardStop` the LLM proxy answers **402
`budget-exceeded`** to further agent calls; the harness stops and the job fails. Spend counts every ledger
entry (agent calls through the proxy, workflow LLM steps, reviews, benchmarks), priced from the model
catalogue.

## 5. What a job token can do

The sandbox receives exactly one credential, the `bo_job_` token of its job. It:

- calls the LLM proxy: `ANTHROPIC_BASE_URL=<api>/api/v1/llm/anthropic` (Claude Code),
  `<api>/api/v1/llm/anthropic/v1` and `<api>/api/v1/llm/openai/v1` (OpenCode), as `x-api-key` or bearer -
  only for the job's model (plus `backoffice.runner.extra-allowed-models` patterns), within its budgets;
- posts events and the result to `/api/v1/internal/runner/jobs/{itsJobId}/events|result`;
- asks for presigned upload URLs at `/api/v1/runner/v2/jobs/{itsJobId}/uploads`.

It cannot lease or complete jobs, read other jobs, call any other API, or be used after the job ended or its
lease expired (a re-queued job gets a new token). Provider keys never leave Back Office.

## Configuration reference (`backoffice.runner.*`)

| Key | Default | Meaning |
|---|---|---|
| `api-url` | `backoffice.public-url` | base URL controllers and sandboxes use; gateway URLs derive from it |
| `lease-duration` | 60 s | lease without heartbeat; the job token expires with it |
| `online-window` | 90 s | runner counts as online |
| `queue-timeout` | 24 h | unleased jobs end `TIMED_OUT` |
| `max-unacknowledged-leases` | 5 | leases without a first heartbeat before the job fails |
| `extra-allowed-models` | - | `provider/model` patterns every job may call (e.g. `anthropic/claude-haiku-*` for Claude Code's background model) |
| `platform-policy.*` | STANDARD, 2 CPU, 4Gi, 10Gi, 2048 pids, 3600 s, common registries | top layer of every policy |
| `lease-scan.enabled`, `lease-scan.interval` | true, 10 s | expiry / LOST / queue timeout scan |

LLM proxy (`backoffice.llm.proxy.*`): `base-urls.<provider>` (upstream override), `request-timeout` (15 min),
`estimate-output-tokens-cap` (4096, used by the budget pre-check).
