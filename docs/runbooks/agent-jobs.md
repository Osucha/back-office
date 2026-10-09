# Runbook: agent jobs (runner fleet v2)

Where to look: *Administration → Runner fleet → Agent jobs* (`GET /api/v1/admin/agent-jobs?status=…`, `fleet.view`) shows
status, pool, runner, reason, effective policy and denied egress for each job;
*Administration → Runner fleet* shows pools with `onlineRunners`, `queued`, `running`. Background:
[10-agent-execution.md](../architecture/10-agent-execution.md), [runner-fleet guide](../guides/runner-fleet.md).

## A job stays QUEUED

| Reason shown | Cause | Fix |
|---|---|---|
| `no online runner in pool <id>` | no controller of the pool called in the last 90 s | check the controller deployment (`kubectl -n <ns> logs deploy/runner-controller`), its network path to the API and that its runner token was not revoked; register a new controller with a fresh registration token |
| `no runner pool can run this job (<pool>: …)` | no pool matches: `poolSelector` labels, `projectIds`, or the policy is invalid for every pool (image not in `allowedImages`, contradicting selectors) | fix the pool labels/projects/allowed images or the template/project policy; the lease scan re-routes unassigned jobs every 10 s |
| `no runner pool exists` | fresh installation | create a pool and register a controller |
| (none, pool has runners) | pool or runner at capacity (`maxConcurrentJobs`, runner `maxConcurrent`) | raise the limits or add controllers |

After `queue-timeout` (24 h) the job ends `TIMED_OUT` with `queue-timeout`; the waiting run then fails its node
and can be retried.

## A job is LOST

The controller stopped heartbeating (node failure, controller crash, network partition) and the lease
expired (60 s). The first loss re-queues the job (new lease, new job token) unless it was already running a
job that pushes - then it ends `LOST` with the runner, sandbox and node in the reason, because a retry could
push twice. Check the controller logs and the sandbox pod on that node; retry the run node once the fleet is
healthy. Jobs that were leased but never heartbeated are re-queued up to 5 times, then `FAILED`
(`lease-not-acknowledged`) - usually a controller that cannot start sandboxes (RuntimeClass missing, image pull).

To take a runner out of service: *Runner fleet → Runners → Revoke* - its jobs return to the queue.

## budget-exceeded (HTTP 402 from the LLM proxy)

The agent's harness reports `budget-exceeded` and the job fails. The problem body names the `scope`:

- `PROJECT` / `PLATFORM`: the month's hard limit is reached. `GET /api/v1/budgets` shows spend and limit.
  Raise the limit (`PUT /api/v1/projects/{id}/budget`, `project.manage`; platform: `PUT /api/v1/admin/budget`,
  `admin.settings.manage`) or set `hardStop: false`, then retry the node.
- `JOB`: the job's own `maxCostUsd` / `maxTokens` (sandbox policy of the template or project) is used up.
  Raise it in the policy if the task legitimately needs more.

The pre-check uses an estimate (prompt size + up to 4096 output tokens), so a call can be refused slightly
before the limit is actually reached.

## model-not-allowed (HTTP 403 from the LLM proxy)

The agent asked for a model that is not the job's model. Typical: Claude Code's background calls to a small
model. Allow it platform-wide with `backoffice.runner.extra-allowed-models` (e.g. `anthropic/claude-haiku-*`)
or configure the harness to use the job's model. `invalid-token` (401) instead means the job is no longer
active or its lease expired (see LOST).

## Egress denied

The sandbox's proxy refused a host that is not in the effective `network.allow`; the controller reports the
hosts in `egressDenied` on completion (shown on the job). Add the host at the narrowest level that needs it
(project or template policy) - it must also be covered by the pool ceiling and the platform policy, otherwise
it is narrowed away (check `sources` in `GET /api/v1/projects/{id}/sandbox-policy`). The API host and the git
host are always allowed.

## Other failures

- `policy-violation: …` at lease time: the pool ceiling changed after submit and the job no longer fits.
- `sandbox exited without posting a result`: the agent crashed or was killed (OOM, deadline); see the
  controller's `reason` (`OOMKilled`, `DeadlineExceeded`, `ImagePullBackOff`) and the job's log events.
- `heartbeat → 409`: the job is no longer leased by that runner (re-queued or finished); the controller must
  stop the sandbox. This is expected after a LOST re-queue.
