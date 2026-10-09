# Runs

A run is one execution of a workflow, usually for one Jira issue. This chapter covers the run list, the
run page, retrying and cancelling, sending a CI signal by hand, and the signed provenance of a run.

<a id="run-list"></a>
## The run list

**Runs** (`/runs`) lists every run of the projects you can see, live. Each project page has the same list
on its **Runs** tab.

| Column | Meaning |
|---|---|
| **Holder** | The baton chip: who holds the run now (see [The baton](getting-started.md#baton)). |
| **Run** | The issue key (or the start of the run id) and the workflow with its version. |
| **Relay** | The mini relay track: one segment per visited step, coloured by its holder. |
| **Project** | The project key (on the **Runs** page only). |
| **Started by** | A person, a CI token or the API. |
| **Cost** | Model and agent cost so far. |
| **Age** | Time since the run was created; hover for the exact time. |

### Filtering

- Search by issue key, run id or workflow name.
- Holder chips (agent, you, ci, done, blocked, idle) filter by who holds the run.
- Presets: **In flight** (agent and CI), **Failed**, **Started by me**.
- **All projects** narrows to one project; then **All workflows** narrows to one workflow.

Filters are kept in the URL, so you can bookmark or share a filtered list. `j` / `k` move through the
rows and `Enter` opens a run. Long lists scroll virtually; the rows per page come from your preferences
(see [Your account](account.md#profile)).

<a id="run-detail"></a>
## The run page

`/runs/:runId` shows one run. The header holds the baton chip, who started the run, its cost, and the full
relay track with labels, durations and costs; click a segment to select that step.

### Banners

| Banner | Meaning |
|---|---|
| **Waiting for you (approver)** / **Waiting for an approver** | The run stopped at a gate. **Review gate** (or **View gate**) opens the gate panel (see [Deciding a gate](your-turn.md#gates)). |
| **Waiting for CI signal** *name* | The run waits for your pipeline to send `backoffice-ci signal <name> --status success|fail`. |
| **Failed at** *node* | The node that failed and the error message. |

### Tabs

| Tab | Content |
|---|---|
| **Timeline** | Left: every step in order, with attempt number, the port it left through, who produced it, duration, cost and errors. Right: the selected step (or **Whole run**) with its summary, confidence and facts, and the tabs **Artefacts (N)**, **Logs** and, after a code review, **Review**. |
| **Canvas replay** | The workflow version the run started with, with the path the run took. |
| **Input** | The run input: issue key, parameters and, for CI-started runs, the CI context. |
| **Provenance** | The signed attestations of the run (see [Provenance](runs.md#provenance)). |

- **Artefacts** are the documents the steps produced: `spec`, `plan`, `tasks`, `review`, `diff` and
  others. Select one to read it; **Copy** copies the content.
- **Logs** shows **This step** or **All steps**. Turn on **Follow** to stay at the newest line; a live
  marker shows while the run is active.
- **Review** shows the verdict, score and findings of the code review, with **Open CI review** for runs
  started by CI.

### Statuses

| Status | Baton | Meaning |
|---|---|---|
| Pending | idle, queued | Created, not started yet. |
| Running | agent, or ci | A step is running. |
| Waiting | you, with approver, or ci | Stopped at a gate or a CI wait. |
| Succeeded | done (or blocked, "finished: failure") | Reached an End node. An End node with outcome Failure finishes the run with a failure outcome. |
| Failed | blocked, failed | A step failed; the run may be retried. |
| Cancelled | idle, cancelled | Cancelled by a person, or by a reject without a reject path. |

<a id="repository-results"></a>
## Results per repository

A coding step can work in several repositories of the project ([Repositories of a
step](workflows.md#step-repositories)). The run page shows what happened in each, in the selected step's
facts and, for **Whole run**, as the latest result per repository over all steps:

| Column | Meaning |
|---|---|
| **Repository** | The key, its purpose (**Source code**, **Documentation**, **Other**) and a mark for the primary repository. |
| **Access** | **Read** or **Push and merge request**. |
| **Branch** | The branch the agent pushed (or worked on, for **Read**). |
| **Commit** | The commit after the step, short form; for pushed repositories the pushed commit. |
| **Changes** | Diff stat: files changed, lines added and removed. |
| **Merge request** | Link to the merge request the push opened, if any. |
| **Pushed** | Yes only when the branch was pushed to that repository. **Read** repositories are never pushed. |
| **Error** | Why cloning, committing or pushing failed in that repository, if it did. |

The merge request in the run header (and the one decisions and gates refer to) is the primary repository's
merge request, or the first one pushed when the primary was not pushed. Steps run by older runners show one
row, for the primary repository. A failed push to one repository does not undo pushes to the others; check
the **Error** column and the step's **Logs**.

<a id="retry-cancel"></a>
## Retrying, cancelling and sending a signal by hand

### Retry a failed run

**Retry from failed step** appears on a failed run when you have `run.start`. The run continues from the
step that failed; finished steps are not repeated. When the engine marked the failure as not retryable,
the button explains that in its tooltip. Fix the cause first: a missing provider key, a sandbox limit, a
broken template, or a loop that hit **Max visits** (see [Troubleshooting](troubleshooting.md#runs)).

Failed runs from the last 7 days that you started, or may retry, are also in **Your turn**.

### Cancel a run

**Cancel run** appears on an active run when you have `run.cancel` (roles **Owner**, **Maintainer**,
**Developer**). Confirm in the dialog: running agent jobs are stopped and the run is marked cancelled.
This cannot be undone.

### Send a CI signal by hand

When a run waits for CI and you have `run.signal`, the CI banner has a small form: the signal name,
**Success** or **Fail**, an optional message, and **Send signal manually**. Use it when the pipeline
cannot report, for example after a CI outage. The run leaves through the matching port. Manual signals
are recorded in the audit log.

<a id="provenance"></a>
## Provenance

When an agent job that produced a commit or a diff finishes, and when a run ends, Back Office signs an
**attestation**: a statement that binds the commit (one per pushed repository) and diff to how they were made: model, template and
prompts (as digests), sandbox policy and image, allowed hosts, tokens and cost, the verification result
and who approved the gates on the way. It is signed with the platform key (Ed25519, DSSE envelope, in-toto
statement). Anyone with the public key can check that it was not changed. It does not prove that the
change is correct.

### Steps

1. Open the run, tab **Provenance**. Attestations appear a little after the job or run finishes.
2. Select one: **Run** or **Agent job · node**.
3. Read **Subjects** (commit and diff with their digests), **Key facts**, **Allowed hosts** and
   **Approvals**; **Statement** shows the full signed content.
4. Click **Verify** to check the signature on the server, or **Download .intoto.jsonl** to verify it
   offline with the public keys from `/.well-known/back-office/keys`.

Viewing attestations needs `project.view`. Offline verification and key handling are described in
[Storage, signing and observability](integrations-other.md#signing) and
[How agent jobs run](agent-execution.md#provenance-attestations).

<a id="ci-executions"></a>
## CI executions

A run started by your pipeline also has a CI execution: **CI** in the rail (`/ci`) lists them for all
your projects, with the gate result (passed or failed, **advisory** when it never fails the job), the
entrypoint, the project repository the pipeline ran in, the merge request or branch, the commit, cost and age. The detail page shows the review
summary, findings, the CI context (merge request, ref, commit, pipeline and job) and the changed files.
