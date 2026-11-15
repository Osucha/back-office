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
job that pushes (a `DELIVER` job of **Deliver changes**, ADR 0063) - then it ends `LOST` with the runner, sandbox and node in the reason, because a retry could
push twice. Check the controller logs and the sandbox pod on that node; retry the run node once the fleet is
healthy. Jobs that were leased but never heartbeated are re-queued up to 5 times, then `FAILED`
(`lease-not-acknowledged`) - usually a controller that cannot start sandboxes (RuntimeClass missing, image pull).

To take a runner out of service: *Runner fleet → Runners → Revoke* - its jobs return to the queue.

## budget-exceeded (HTTP 402 from the LLM proxy)

The agent's harness reports `budget-exceeded` and the job fails. The problem body names the `scope`:

- `PROJECT` / `PLATFORM`: the month's hard limit is reached. `GET /api/v1/budgets` shows spend and limit.
  Raise the limit (`PUT /api/v1/projects/{id}/budget`, `project.manage`; platform: `PUT /api/v1/admin/budget`,
  `admin.settings.manage`) or set `hardStop: false`, then retry the node.
- `JOB`: the job's own `maxCostUsd` / `maxTokens` (the project sandbox policy; ADR 0062: templates have none) is used up.
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

## Delivering changes failed (git push, merge request)

Only a **Deliver changes** step pushes (a `DELIVER` job, ADR 0063); agent jobs clone read-only and log
`[git] access READ: clone only`. To get an agent's work onto a branch, add a **Deliver changes** node after the
step. The job's log (*Agent jobs*, or `GET /api/v1/runs/{id}/logs?stepId=<step>`) shows the git lines.

### Push rejected or refused

- Push rejected by the server: the project's repository token with PUSH_BRANCH is used first
  ([integrations-git](../manual/integrations-git.md#git-project-tokens)); otherwise the git tokens live on the
  runner side, not in the control plane - in Kubernetes the runner chart's `gitCredentials.repoSecrets[].push`
  Secret of the repository (legacy: `existingSecret` / `hostSecrets`; sandbox namespace, key `token`), in compose
  `BO_GIT_TOKEN_PUSH` (`GIT_TOKEN_PUSH` in `.env`). It needs scope `write_repository` and role Developer; the
  job branch must not be protected.
- `policy-violation: git credential: READ job ... has only a push credential` / `PUSH_MR job ... has no push
  credential` (ADR 0031): the pool has no read (or push) Secret for that repository. Add it to
  `gitCredentials.repoSecrets` ([integrations-git](../manual/integrations-git.md#git-install));
  `allowPushCredentialForRead` is only a temporary exception.
- `push refused: ...` before cloning: the branch renders to the default branch, `HEAD` or `refs/...`; change
  **Branch** on the **Deliver changes** node.
- "nothing to deliver": the work tree had no changes (or the agents worked in a **Separate directory**, whose
  changes are thrown away); check the implementing step's `diff`.
- A spec with `mode: SPEC_KIT` (an older Back Office) fails on a current runner; upgrade Back Office.

### Push options rejected, or the merge request was not created

With **Open a merge request** on, the runner pushes with `git push -o merge_request.create
-o merge_request.target=<branch> -o merge_request.title=<title> [-o merge_request.description=...]
[-o merge_request.draft]` (ADR 0013, 0063).

- `... the receiving end does not support push options`: the remote is not GitLab (or a git server without
  `receive.advertisePushOptions`). Only GitLab creates merge requests this way; on other hosts turn **Open a merge
  request** off (the branch is still pushed) and open the pull request from your CI (`gh pr create`).
- `push options must not have new line characters`: cannot happen with a current runner (it collapses
  title/description to one line and caps them at 255/1000 characters); the runner image is stale, rebuild it.
- Push succeeded but no merge request URL (`WARN push options sent but no merge request URL found`): GitLab prints
  the MR URL as `remote:   https://host/group/repo/-/merge_requests/N` and the runner parses that line. It is missing
  when the MR already existed for the branch (some GitLab versions print nothing then), when the token's user cannot
  create MRs on the target project (Developer role needed), or when the target branch does not exist. Check the
  branch on GitLab and the job's `remote:` log lines; the run state `mr` stays empty, and downstream `WAIT_FOR_CI`
  nodes still work because the CI matches the run by the commit trailer, not by the MR.
- MR opened against the wrong target: the target is the delivered repository's default branch (`baseBranch`);
  check the repository's **Default branch** in the project.
- Commits without the `Back-Office-Run` trailer (so `backoffice-ci signal` cannot find the run): someone amended or
  squashed the delivery's commit; the CI falls back to matching by branch, or pass `--run <id>` explicitly.
