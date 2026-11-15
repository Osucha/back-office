# Runbook: a workflow run is stuck

## When to use this runbook

A run shows `RUNNING` (or `WAITING`) for much longer than its nodes should take, the current node does
not change, no new log lines arrive and there is nothing in your inbox for it, or your CI already
reported a signal and the run still waits.

## How a run is supposed to move

`RunService.start` saves the run as `RUNNING` and hands it to `LangGraphWorkflowEngine`. The engine never
executes anything itself: start, resume, retry and recovery each enqueue a `RUN_ADVANCE` work item (dedupe key
`RUN_ADVANCE:<runId>`, [work-queue.md](work-queue.md)), and the worker holding that item's lease invokes the
compiled graph on its own thread until the graph interrupts or ends. Every node reports to `RecordRunProgress` (`NodeStarted`,
`NodeCompleted`, `NodeFailed` events, `workflow_run.nodes` JSON with the `port` taken) and LangGraph
writes a checkpoint to the `lg4jcheckpoint` table after each node. Before a `HUMAN_GATE` the graph
stops, the engine creates an `approval_request` row and the run becomes `WAITING` with
`waiting_kind = HUMAN_GATE`; `DecideApproval` resumes it. A step whose agent stopped with questions (ADR 0060)
waits the same way with `waiting_kind = AGENT_QUESTIONS` and an `approval_request` of kind `AGENT_QUESTIONS`
(options `answer` / `cancel`); it shows in **Your turn** as "The agent has questions". Before a `WAIT_FOR_CI` the graph stops and
the run becomes `WAITING` with `waiting_kind = WAIT_FOR_CI` and `waiting_signal = <name>`;
`POST /api/v1/ci/signals` (from `backoffice-ci signal`) or `POST /api/v1/runs/{id}/signals` resumes it.
A scheduled sweep (`WaitTimeoutSweep`, every minute) resumes waits whose `timeoutSeconds` elapsed
through the node's `timeout` port. A runner-backed step (an agent step, **Deliver changes**) submits an agent
job and stops at its `<node>::await` node; the run stays `RUNNING` but holds no work item, and the job's end
enqueues the `RUN_ADVANCE` that continues it. `RunRecovery` (worker role, ShedLock `workflow-recovery`) scans at
startup and every 10 minutes (`backoffice.pipelines.recovery-scan-delay`) and enqueues a `RECOVER` advance for
every `RUNNING` run; it merges into a live item, so it only matters for runs left without one. `WAITING` runs
need nothing.

## Symptoms, checks, fixes

### 1. `RUNNING`, current node is a runner task (an agent step, a **Deliver changes** node)

Check:

- `GET /api/v1/runs/{id}` -> `nodes[last].runnerJobId`, `startedAt`.
- *Administration → Runner fleet → Agent jobs* (or `GET /api/v1/admin/agent-jobs`): status, pool, runner and reason
  of that job.
- `SELECT status, reason, queued_at, started_at, finished_at FROM agent_job WHERE id = '<runnerJobId>'`.
- `SELECT at, message FROM agent_job_event WHERE job_id = '<runnerJobId>' ORDER BY at DESC LIMIT 20`.

Fix:

- Job `QUEUED`: no runner leased it; see "A job stays QUEUED" in [agent-jobs.md](agent-jobs.md).
- Job `RUNNING` and events still arriving: the agent is working; runner tasks can take an hour. The sandbox is
  bounded by the effective policy's `timeoutSeconds` (platform default 3600 s).
- Job `RUNNING`, no events for a long time, or `LOST`: follow [agent-jobs.md](agent-jobs.md). Cancelling the run
  cancels its agent job too.
- Job finished (`SUCCEEDED`, `FAILED`, `TIMED_OUT`, `CANCELLED`, `LOST`) but the run did not move: the job's end
  enqueues a `RUN_ADVANCE` item; look for it (`dedupe_key = 'RUN_ADVANCE:<runId>'`) and for a `DEAD` one in
  [work-queue.md](work-queue.md). The recovery scan re-drives the run within 10 minutes.

### 2. `RUNNING`, current node is an in-app node (`LLM` task, `DECISION`, `fetch-issue`, ...)

Check the API log for the run id:

```
grep '<runId>' api.log | grep -E 'engine error|integration failure'
```

Fix:

- `engine error`: the run should already be `FAILED`; if it is still
  `RUNNING` the failure happened while persisting. `POST /api/v1/runs/{id}/cancel`, then start a new run.
- Provider timeouts: `backoffice.llm.default-timeout` bounds every call; a hung call fails the node
  when it expires and an unconnected `failure` port marks the run `FAILED` for retry.

### 3. `RUNNING` after a restart or a crashed worker

When a worker dies, its lease on the `RUN_ADVANCE` item expires (60 s) and another worker continues the run
from the last LangGraph checkpoint; the node that was running in-app runs again. A runner-backed node is not
re-run: its agent job keeps running in the fleet and its end continues the run.

Check the work item of the run (`SELECT status, attempts, last_error FROM work_item WHERE dedupe_key =
'RUN_ADVANCE:<runId>' ORDER BY created_at DESC`) and the log line `recovery scan: re-driving RUNNING workflow
runs count=N`.

Fix:

- No live item and no recovery log: `backoffice.pipelines.recover-on-startup` (`PIPELINE_RECOVER_ON_STARTUP`) is
  `false`, which turns off the startup and the periodic scan; enable it, or cancel and rerun.
- Item `DEAD`: see [work-queue.md](work-queue.md) section 2; fix the cause and retry the item.
- No worker online: see [work-queue.md](work-queue.md) section 1.

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
- `DECIDED` but run still waiting: the resume (`RUN_ADVANCE` item with the decision) did not apply; check
  the item as in section 3. Restarting the API does **not** resume waiting runs (they wait by design). Deciding again is rejected (409); use
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
- `docs/runbooks/agent-jobs.md` when the stuck node is runner-backed.
