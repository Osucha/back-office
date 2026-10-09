# Runbook: a workflow run is stuck

## When to use this runbook

A run shows `RUNNING` (or `WAITING`) for much longer than its nodes should take, the current node does
not change, no new log lines arrive and there is nothing in your inbox for it, or your CI already
reported a signal and the run still waits.

## How a run is supposed to move

`RunService.start` saves the run as `RUNNING` and hands it to `LangGraphWorkflowEngine`. The engine
runs the compiled graph on a virtual thread; every node reports to `RecordRunProgress` (`NodeStarted`,
`NodeCompleted`, `NodeFailed` events, `workflow_run.nodes` JSON with the `port` taken) and LangGraph
writes a checkpoint to the `lg4jcheckpoint` table after each node. Before a `HUMAN_GATE` the graph
stops, the engine creates an `approval_request` row and the run becomes `WAITING` with
`waiting_kind = HUMAN_GATE`; `DecideApproval` resumes it. Before a `WAIT_FOR_CI` the graph stops and
the run becomes `WAITING` with `waiting_kind = WAIT_FOR_CI` and `waiting_signal = <name>`;
`POST /api/v1/ci/signals` (from `backoffice-ci signal`) or `POST /api/v1/runs/{id}/signals` resumes it.
A scheduled sweep (`WaitTimeoutSweep`, every minute) resumes waits whose `timeoutSeconds` elapsed
through the node's `timeout` port. On restart `RunRecovery` resumes every `RUNNING` run from its
checkpoint; `WAITING` runs need nothing.

## Symptoms, checks, fixes

### 1. `RUNNING`, current node is a runner task (`spec-kit-*`, `implement-*`)

Check:

- `GET /api/v1/runs/{id}` -> `nodes[last].runnerJobId`, `startedAt`.
- `SELECT status, started_at, external_ref FROM runner_job WHERE id = '<runnerJobId>'`.
- `SELECT at, message FROM runner_job_event WHERE job_id = '<runnerJobId>' ORDER BY at DESC LIMIT 20`.

Fix:

- Job `RUNNING` and events still arriving: the agent is working; runner tasks can take an hour.
  The node waits until `timeoutSeconds` (node) or `backoffice.runner.default-timeout`, plus two minutes.
- Job `RUNNING`, no events for a long time: follow `runner-job-failed.md`. Deleting the Kubernetes
  Job (or `docker rm -f <label>`) makes `await` observe the failure within 5 s and the node fails.
- Job row missing or `CREATED` forever: the launch failed silently; look for
  `could not start runner job` in the API log; cancel the run and retry after fixing the runner.

### 2. `RUNNING`, current node is an in-app node (`LLM` task, `DECISION`, `fetch-issue`, ...)

Check the API log for the run id:

```
grep '<runId>' api.log | grep -E 'engine error|crashed|integration failure'
```

Fix:

- `engine error` / `workflow task crashed`: the run should already be `FAILED`; if it is still
  `RUNNING` the failure happened while persisting. `POST /api/v1/runs/{id}/cancel`, then start a new run.
- Provider timeouts: `backoffice.llm.default-timeout` bounds every call; a hung call fails the node
  when it expires and an unconnected `failure` port marks the run `FAILED` for retry.

### 3. `RUNNING` after an API restart

Check the startup log for `recovering in-flight workflow runs count=N` and, per run, `recovering run id=`.

Fix:

- `backoffice.pipelines.recover-on-startup=false`: enable it, or cancel and rerun.
- `Resume request without a valid checkpoint`: the run crashed before its first checkpoint.
  Cancel it and start again.
- Recovery re-executes the interrupted node. Runner tasks re-attach to the existing job by label
  (`run-<runId8>-<node>-<attempt>`); in-app nodes run again.

### 4. `WAITING` for CI but the signal was sent

Check:

```sql
SELECT status, waiting_kind, waiting_signal, current_node_id, merge_request FROM workflow_run WHERE id = '<runId>';
SELECT occurred_at, payload FROM ... -- or: grep 'CiSignalReceived' api.log
```

and the CI job output of `backoffice-ci signal` (`resumed: false` and a message).

Fix:

- `no run is waiting for signal x`: the signal name differs from the node's `ci.signal` (case and
  spelling), or the run is waiting at a different node. Send the right name.
- `N runs are waiting for signal x; pass --run <id> or a matching branch`: the run could not be matched.
  The runner writes the `Back-Office-Run: <runId>` trailer into its commits and the script passes it as
  `--run`; when your pipeline runs on a commit without the trailer (a squash, a manual commit), match by
  branch: `merge_request.branch` on the run must equal the CI branch, or resend with `--run <id>`.
  As the owner you can always unblock with `POST /api/v1/runs/{id}/signals {"signal":"tests","status":"SUCCESS"}`.
- The job used a revoked or foreign token: the signal endpoint answered `401`; issue a new token.
- `timeoutSeconds` elapsed meanwhile: the run left through `timeout` (see `nodes[].port`); unconnected,
  the run is `FAILED` and can be retried after the CI is fixed.

### 5. `WAITING` for a human but nothing in the inbox

Check:

```sql
SELECT id, status, node_id FROM approval_request WHERE run_id = '<runId>' ORDER BY created_at DESC;
SELECT open_approval_id, waiting_kind FROM workflow_run WHERE id = '<runId>';
```

Fix:

- `OPEN` row exists: the UI filter hides it; `GET /api/v1/approvals?status=OPEN` lists it, decide through
  `POST /api/v1/approvals/{id}/decide`.
- `DECIDED` but run still waiting: the resume thread died (log `workflow task crashed`). Restarting
  the API does **not** resume waiting runs (they wait by design). Deciding again is rejected (409); use
  `POST /api/v1/runs/{id}/cancel` and rerun, or resume manually: re-open the approval
  (`UPDATE approval_request SET status='OPEN', decision=NULL ...`), set
  `UPDATE workflow_run SET status='WAITING', waiting_kind='HUMAN_GATE', open_approval_id=...` and decide again.
- `EXPIRED`: the gate's `timeoutSeconds` elapsed and the run left through `timeout`.

### 6. A CI job keeps polling (`backoffice-ci run --wait` never returns)

`GET /api/v1/ci/executions/{id}` stays `RUNNING` because the run behind it (`runId`) is stuck: apply
sections 1-5 to that run. When the run finishes (or is cancelled) the execution becomes
`SUCCEEDED`/`FAILED` with a `gateResult` and the job exits; `BACKOFFICE_REVIEW_TIMEOUT` bounds the wait
on the CI side (exit code 3).

### 7. Run finished in the database but the UI still shows it active

The SSE stream (`/api/v1/events`) reconnects with `Last-Event-ID` (or `?lastEventId=`); the server
replays the last 500 events only. Reload the page; check the browser console for `EventSource` errors
and the ingress timeout for `text/event-stream` (heartbeat comments are sent every 20 s).

## Last resort

```sql
UPDATE workflow_run SET status = 'CANCELLED', finished_at = now(), waiting_kind = NULL, waiting_signal = NULL, open_approval_id = NULL WHERE id = '<runId>';
UPDATE approval_request SET status = 'EXPIRED' WHERE run_id = '<runId>' AND status = 'OPEN';
UPDATE ci_execution SET status = 'FAILED', gate_result = 'FAIL', error = 'cancelled by operator', finished_at = now() WHERE run_id = '<runId>' AND status IN ('QUEUED', 'RUNNING');
```

Checkpoints in `lg4jthread` / `lg4jcheckpoint` can stay; they are keyed by run id and never reused.

## Where to look when it fails

- API log filtered by the run id (`runId=` appears in every engine message).
- `GET /api/v1/runs/{id}/logs` for node-level log lines (template warnings, branch evaluations, runner messages).
- `docs/architecture/03-pipelines-and-agents.md` for the compilation, routing and recovery rules.
- `docs/guides/ci-integration.md` for the signal matching rules seen from the CI side.
- `docs/runbooks/runner-job-failed.md` when the stuck node is runner-backed.
