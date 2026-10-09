# Runbook: a runner job failed or never reported back

## When to use this runbook

A `SPEC_KIT` or `IMPLEMENT` step (or a `CODING_JOB` benchmark attempt) ended as `FAILED` or
`TIMED_OUT`, or the Kubernetes Job / docker container is gone while the API still waits for it.

## How the runner protocol works

1. The API creates a row in `runner_job` (status `CREATED`) and launches a Kubernetes `batch/v1 Job`
   named after the label `run-<runId8>-<step>-<attempt>` (or `docker run --name <label>`).
2. The container receives `RUNNER_JOB_ID`, `RUNNER_CALLBACK_URL`, `RUNNER_CALLBACK_TOKEN`,
   `RUNNER_SPEC` (base64 JSON: mode, agent, model, repository incl. `mergeRequest`, skills, `runId`,
   `collectDiff`), provider keys and git credentials from two Secrets.
3. It posts log batches to `POST /api/v1/internal/runner/jobs/{id}/events` and the final
   `POST .../result`. Both carry `Authorization: Bearer <token>` (HMAC of job id + expiry, 24 h).
4. The API stores the result, books usage, and wakes the waiting step. `await` also polls the row
   every 5 s, so a result posted while the API was restarting is still picked up. On timeout the API
   marks the job `TIMED_OUT` and deletes the Job.

## Symptoms, checks, fixes

### Step fails immediately with `runner: agent runner is disabled`

`backoffice.runner.mode` is `DISABLED`. Set `RUNNER_MODE=KUBERNETES` (cluster) or `RUNNER_MODE=DOCKER`
(laptop) and restart. `GET /api/v1/settings/integrations` shows the active mode;
`POST /api/v1/settings/integrations/kubernetes/test` checks connectivity.

### `could not start runner job ...` (Kubernetes)

Check:

```
kubectl -n <ns> auth can-i create jobs --as=system:serviceaccount:<ns>:<api-sa>
kubectl -n <ns> get sa back-office-runner
```

Fix: the API service account needs create/get/list/delete on `jobs` and get/list on `pods`
(`pods/log`); the runner service account (`backoffice.runner.kubernetes.service-account`) must exist.
Wrong `backoffice.runner.kubernetes.namespace` gives 404/403 here.

### Job created, pod never runs

```
kubectl -n <ns> describe job <label>
kubectl -n <ns> get pods -l job-name=<label>
```

- `ImagePullBackOff`: `backoffice.runner.image` is wrong or the registry secret is missing.
- `Pending` with insufficient CPU/memory: lower `backoffice.runner.kubernetes.*-request` or add nodes.
- `CreateContainerConfigError`: the Secrets are referenced with `optional: true`, so this is a
  security-context problem (image must run as UID 1000, non-root, read-only root fs with `/workspace`).

### Pod ran but no events and no result

```
kubectl -n <ns> logs -l job-name=<label> --tail=200
```

- `401` from the callback: `RUNNER_TOKEN_SECRET` differs between API replicas or was rotated while
  the job ran. Tokens are valid 24 h; a job longer than that needs a smaller `timeoutSeconds`.
- Connection refused / DNS: `backoffice.runner.callback-url` must be reachable from the pod
  (`http://<api-service>:8080` in-cluster, `http://host.docker.internal:8080` for docker on a laptop).
  With the NetworkPolicy enabled the pod label `app.kubernetes.io/component: runner` must match the
  policy selector; check `kubectl -n <ns> get networkpolicy -o yaml`.
- `409` on `/result`: the result was already stored (retry after a network hiccup). The runner treats
  409 as delivered; nothing to do.
- Provider `401`/`403`: the provider key is missing from the `back-office-provider-keys` Secret for
  the model profile used by the step.
- Git push rejected: the git tokens live on the runner side, not in the control plane - in Kubernetes the
  runner chart's `gitCredentials.repoSecrets[].push` Secret of the repository (legacy: `existingSecret` /
  `hostSecrets`; sandbox namespace, key `token`), in compose `BO_GIT_TOKEN_PUSH` (`GIT_TOKEN_PUSH` in `.env`).
  It needs scope `write_repository` and role Developer; the job branch must not be protected.
- `policy-violation: git credential: READ job ... has only a push credential` / `PUSH_MR job ... has no push
  credential` (ADR 0031): the pool has no read (or push) Secret for that repository. Add it to
  `gitCredentials.repoSecrets` (docs/manual/integrations-git.md#git-install); `allowPushCredentialForRead`
  is only a temporary exception.
- READ jobs never push (`[git] access READ: clone only`): turn `push` on in the step if it should push.

### Push rejected because of push options, or the merge request was not created

The runner pushes with `git push -o merge_request.create -o merge_request.target=<branch>
-o merge_request.title=<title> [-o merge_request.description=...] [-o merge_request.draft]` when the
task sets `repository.mergeRequest` (ADR 0013). Symptoms and fixes:

- `push failed: ... the receiving end does not support push options` - the remote is not GitLab (or
  a very old GitLab / a git server without `receive.advertisePushOptions`). Only GitLab creates merge
  requests this way; on other hosts remove `mergeRequest` from the task (the branch is still pushed)
  and open the pull request from your CI (`gh pr create`).
- `push options must not have new line characters` - cannot happen with the runner (it collapses
  title/description to one line and caps them at 255/1000 characters); if you see it the image is
  stale, rebuild it.
- Push succeeded but `result.mergeRequestUrl` is empty (`WARN push options sent but no merge request
  URL found`): GitLab prints the MR URL as `remote:   https://host/group/repo/-/merge_requests/N`;
  the runner parses that line. It is missing when the MR already existed for the branch (GitLab then
  prints `View merge request for <branch>` with the URL, which is parsed, or nothing at all on some
  versions), when the token's user cannot create MRs on the target project (`Developer` role needed),
  or when the target branch does not exist. Check `git log` of the branch on GitLab and the pod log
  lines starting with `[git] remote:`; the run state `mr` stays empty and downstream `WAIT_FOR_CI`
  nodes still work because the CI matches the run by the commit trailer, not by the MR.
- The MR opened but under the wrong target: `mergeRequest.targetBranch` in the task defaults to the
  repository `baseRef`.
- Commits without the `Back-Office-Run` trailer (so `backoffice-ci signal` cannot find the run): the
  spec had no `runId` (older API) or the agent amended/squashed the runner's commit; the CI falls
  back to matching by branch, or pass `--run <id>` explicitly.

`GET /api/v1/runs/{id}/logs?stepId=<step>` shows what did arrive; when no events were posted the API
falls back to the pod log (`AgentRunner.logs`).

### Result posted but the step is `FAILED`

- `verification failed (exit N)`: the `verifyCommand` failed after the agent finished; the tail is in
  the `verification-<step>` artefact. Fix the prompt or the command and `POST /runs/{id}/retry`.
- `runner job FAILED: <error>`: the harness reported an error (`error` field of the result); the
  agent output is in `summary`.

### `TIMED_OUT`

The step waited `timeoutSeconds` (+ 2 min grace) without a result; the Job was deleted
(`activeDeadlineSeconds = timeout + 120` also kills it cluster-side). Raise the step `timeoutSeconds`
or `backoffice.runner.default-timeout`, or split the task.

### Job exists from a previous attempt (`re-attaching`)

Job names are deterministic per attempt, so a retried step re-uses a live Job. If that Job is
stale (`kubectl -n <ns> delete job <label>`), the next `await` poll marks the runner job `FAILED`
and the step fails; retry the run to get attempt `n+1` with a fresh label.

### Docker mode

- `docker binary not available`: the API container has no Docker CLI/socket; use Kubernetes mode.
- `docker run failed (125)`: usually a bad `--network` (`backoffice.runner.docker.network`) or
  `--env-file` path (`backoffice.runner.docker.env-file`).
- `docker logs <label>` shows the container output; containers are started with `--rm`, so a crashed
  container leaves nothing behind. Remove `--rm` temporarily by running the image by hand with the same
  env (`docker inspect` on a running one shows the command).

## Useful queries

```sql
SELECT id, label, status, created_at, started_at, finished_at, external_ref FROM runner_job ORDER BY created_at DESC LIMIT 20;
SELECT at, progress, message FROM runner_job_event WHERE job_id = '<id>' ORDER BY at;
SELECT result->>'status', result->>'error', result->'verification'->>'exitCode' FROM runner_job WHERE id = '<id>';
```

## Where to look when it fails

- API log: `runner job started`, `runner job re-attached`, `runner job timed out`, `runner result received`.
- `docs/architecture/03-pipelines-and-agents.md` (agent runner protocol, `RunnerJobSpec`).
- `docs/architecture/06-security.md` (sandboxing: non-root, no service account token, NetworkPolicy).
- `docs/runbooks/stuck-pipeline-run.md` when the run itself no longer moves after the job finished.
