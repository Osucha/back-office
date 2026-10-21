# 11 — Governance: budgets, insights, provenance, MCP

Capabilities for engineering leaders (ADR 0025). All read paths go through `AuthorizationGate`; project
data is filtered by `project.view`.

Code: modules `governance-core` (package `pl.mos.backoffice.governance.domain` / `.application`, pure Kotlin
plus JDK crypto) and `governance-adapters` (`.adapter.*`). Flyway range `V550–V599` (`V550__attestations.sql`).
The Projects context provides run data through the shared-kernel port `RunProvenanceSource`
(`projects.adapter.out.provenance.ProjectsRunProvenanceSource`); usage comes from the `UsageLedger` port;
insights and the attestation catch-up query the tables of other contexts read-only with SQL (read models).

## Budgets

| Scope | Set by | Fields |
|---|---|---|
| Platform (monthly) | `admin.settings.manage` | `monthlyLimitUsd`, `alertAtPercent` (default 80), `hardStop` |
| Project (monthly) | `project.manage` | same |
| Run | task template / workflow | `maxCostUsd` per run |
| Job | task template `sandboxPolicy` / LLM step | `maxCostUsd`, `maxTokens` |

Enforcement at the LLM gateway before every provider call (estimate) and after (actual); state in
`budget_state(scope, period, spent_usd, version)`. Crossing the alert threshold emits `BudgetAlert` and a
turn card for project owners; hitting a hard limit returns HTTP 402 `budget-exceeded` to the agent (the
harness stops), fails the job with reason `budget-exceeded` and opens a `you` turn: "Raise the limit or
stop". New permission: none (existing `project.manage`, `admin.settings.manage`).

## Delivery insights (`GET /insights/delivery`)

`projectId` (optional; `project.view` on it), `from`/`to` (default: the last 30 days, at most 366 days). Without a
project the metrics cover every project the caller holds `project.view` on (`gate.projectFilter`).

**Scope:** every metric starts from the runs **created** in `[from, to)` of the visible projects (`runs` CTE in
`JdbcDeliveryInsightsReadModel`). A node execution is one element of `workflow_run.nodes` (JSONB); an *agent job*
is a `TASK` execution that ended `SUCCEEDED` or `FAILED` (runner-backed and in-process LLM tasks alike). Durations
are `finishedAt − startedAt` of the execution; open waits are not counted until they end. Weeks are ISO weeks
(Monday) in UTC.

| Metric | Definition | Reads |
|---|---|---|
| Lead time | per `(project, issue key)` with a run in scope that recorded a merge-request URL: from the first start (`coalesce(started_at, created_at)`) of **any** run for that issue key (also before the window) to the moment the MR was recorded = the earliest successful runner-backed `TASK` execution of that run (fallback: run finish); p50/p90/total/count | `workflow_run` (`issue_key`, `merge_request->>'url'`, `nodes`) |
| Human wait (headline) | durations of `HUMAN_GATE` executions; p50/p90/total/count | `workflow_run.nodes` |
| CI wait | durations of `WAIT_FOR_CI` executions | `workflow_run.nodes` |
| Runs | total / succeeded / failed / cancelled by `status` | `workflow_run` |
| Agent success rate per template | agent jobs grouped by `templateKey` (fallback node id): jobs, succeeded, rate, sum of execution cost | `workflow_run.nodes` |
| Agent success rate per model | agent jobs grouped by the dominant model of the step (the `usage_record.model_label` with the highest cost for the same `run_id` + `step_id`; `unknown` without ledger rows) | `workflow_run.nodes`, `usage_record` |
| Rework loops per run | (decided gates with option `revise` + executions whose previous attempt of the same node `FAILED`) ÷ runs | `approval_request`, `workflow_run.nodes` |
| Cost per successful run | ledger cost of runs `SUCCEEDED` with outcome `SUCCESS` ÷ their count | `usage_record` (by `run_id`), `workflow_run` |
| Cost per merge request | ledger cost of runs with a merge-request URL ÷ their count | `usage_record`, `workflow_run` |
| Total cost | ledger cost of the runs in scope | `usage_record` |
| Throughput (`weekly`) | runs succeeded / failed by week of `finished_at`; human wait hours by week of the gate's end; ledger cost by week of `occurred_at` | `workflow_run`, `usage_record` |
| Model mix (`modelMix`) | calls, input/output tokens and cost per `model_label` of the runs in scope | `usage_record` |
| Gate decisions | decided approvals of the runs in scope per option (lower-cased) | `approval_request.decision->>'option'` |
| Bottleneck | the gate (`workflow_name`, node id) with the longest p90 wait | `workflow_run.nodes` |

Not yet: gate decisions per approver role (the decision stores the actor, not the role held at the time) and
materialised daily tables for large installs (the queries use the existing indexes on `workflow_run.created_at`
and `usage_record.run_id`). Tested with a seeded dataset in `DeliveryInsightsIntegrationTest`.

## Workflow metrics

ADR 0037. `GET /insights/workflows?libraryWorkflowId=` (one library workflow: `library.view`, runs of every project
the caller holds `project.view` on) or `?workflowId=` (one project workflow: `project.view` on its project), and
`GET /insights/library-workflows` (headline metrics per library workflow, `library.view`). Same window rules as
delivery insights (default the last 30 days, at most 366). Read model `JdbcWorkflowInsightsReadModel`.

**Scope:** the runs **created** in `[from, to)` of the visible projects with `workflow_run.library_workflow_id`
(library scope) or `workflow_id` (workflow scope). Per run (`facts` CTE): *successful* = `SUCCEEDED` with outcome
`SUCCESS`; *failed* = `FAILED`, or `SUCCEEDED` through a `FAILURE` end; *finished* = successful + failed +
`CANCELLED`; *active* = the rest. Node executions, durations and weeks are read as in delivery insights.

| Metric | Definition |
|---|---|
| Success rate | successful ÷ finished |
| First-pass rate | successful runs with no rework loop ÷ finished |
| Rework loops per run | (`HUMAN_GATE` executions that left through `revise` + executions whose previous attempt of the same node `FAILED`) ÷ runs |
| Duration | `finished_at − coalesce(started_at, created_at)` of finished, not cancelled runs; p50/p90/total/count |
| Active time | duration minus the summed durations of the run's `HUMAN_GATE` and `WAIT_FOR_CI` executions (floor 0) |
| Human wait | per run with at least one finished gate: the summed gate durations |
| Total cost, cost per run, cost per successful run | ledger cost (`usage_record` by `run_id`) of the runs, ÷ runs, of the successful runs ÷ their number |
| Cost at API prices: total, per run, per successful run (ADR 0042) | the ledger cost plus what subscriptions paid for (`workflow_run.api_cost_amount - cost_amount`, at least 0), divided the same way; per node `apiCost` of the node executions (their cost when absent) |
| Tokens | ledger input and output tokens of the runs |
| Merge requests | runs that recorded a merge-request URL |
| Modified runs | runs whose `library_modified` is true (the linked copy differed from its library version at the start) |

Breakdowns: **by version** (`library_version` in library scope, `workflow_version` in workflow scope), **by project**
(library scope), **by node** (all executions of the node id: count, `SUCCEEDED`, `FAILED`, p50/p90 of finished
executions, summed execution cost, ports taken; ordered by the node's average position in the runs) and
**weekly** (successful / failed runs and their ledger cost by ISO week of `finished_at`). The UI compares a version
with the next older one that has finished runs (better / worse when the success rate moves by 5 points or the
median duration or cost per success by 10 %) and marks versions with fewer than 5 finished runs. Tested with a
seeded dataset in `WorkflowInsightsIntegrationTest`.

## Provenance attestations

- Format: [in-toto Statement v1](https://github.com/in-toto/attestation/blob/main/spec/v1/statement.md) in a
  [DSSE](https://github.com/secure-systems-lab/dsse/blob/master/protocol.md) envelope (`payloadType`
  `application/vnd.in-toto+json`), Ed25519 signature over `PAE = "DSSEv1" SP len(type) SP type SP len(body) SP body`.
- Keys: `backoffice.signing.keys` = list of `{id, privateKeyPkcs8Base64, publicKeyX509Base64, active}`; the active key
  signs, retired keys (public key only) keep verifying. `keyid` / JWK `kid` = `id`, default the fingerprint
  `sha256:<hex SHA-256 of the raw 32-byte public key>`. Without keys an **ephemeral** key is generated with a warning
  (local development; signatures then do not survive a restart and differ per replica). The same signer
  (`EnvelopeSigner` port, bean `Ed25519EnvelopeSigner`) signs runner job assignments.
- When: after the `WorkflowRunFinished` event commits (and on the runner's `AgentJobFinished` / `RunnerJobFinished`
  with `runId` + `jobId` in the payload); a catch-up sweep every 5 minutes (`backoffice.provenance.sweep-interval`,
  one replica at a time via `pg_try_advisory_xact_lock`) attests finished runs of the last 7 days without a run
  attestation. Creation is idempotent (`attestation.subject_key` unique: `RUN:<runId>`, `JOB:<runId>:<executionId>`)
  and audited (`ATTESTATION_CREATE`).
- `AGENT_JOB` attestation: one per runner-backed `TASK` execution that ended `SUCCEEDED`/`FAILED` **and** produced a
  commit or a diff. Subjects: `git+<repo>@<commitSha>` (`sha256` of the commit id plus `gitCommit`), one per
  pushed repository of the job (ADR 0034; jobs from older runners: the single repository), and
  `diff:<runId>/<nodeId>/<attempt>` (`sha256` of the diff). Job evidence (spec and result) is read from `agent_job`
  (runner v2) or `runner_job` (v1) schema-agnostically (`JdbcJobEvidenceSource`); prompts and skills are reduced to
  SHA-256.
- `RUN` attestation: one per finished run; subjects = all job subjects (every pushed repository of every job) (or a digest of the artefact manifest when no
  job produced code); predicate with the path of nodes, the gate decisions, the job attestation ids and totals.
- Predicate `https://back-office.dev/attestations/agent-run/v1` (job): `kind`, `runId`, `project {id, key,
  repository}` (the primary repository URL, null when there is none), `workflow {id, name, version}`, `node {id, name, executionId, attempt, status, port}`, `template {key,
  version, lineage}`, `job {id, agent, status, branch, mergeRequestUrl}` (primary repository), `repositories [{key, url, purpose, access,
  primary, branch, commitSha, mergeRequestUrl, pushed}]` (ADR 0034; omitted for legacy jobs), `model {ref, effort}`, `prompts
  {promptSha256, constitutionSha256, skills{key: sha256}}`, `sandbox {policyDigest, isolation, image, imageDigest,
  allowedHosts, egressLogDigest}`, `usage {inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, cost}`,
  `verification {command, exitCode}`, `gateDecisions [{approvalId, nodeId, option, comment, checkedItems, decidedBy
  {kind, userId, displayName}, decidedAt}]` (decided before the job ended), `timestamps`.
  Known gaps: `template.version` is null (not recorded at run time yet); `imageDigest` / `egressLogDigest` are only
  filled by runner v2 completions; the system and user prompt are one digest (the runner receives them joined).
- Endpoints: `GET /api/v1/runs/{runId}/attestations` (`project.view`, summaries), `GET /api/v1/attestations/{id}`
  (`project.view`, the envelope as `application/vnd.in-toto+json`), `POST /api/v1/attestations/verify` (any
  authenticated caller), public keys at `GET /.well-known/back-office/keys` (JWK set `OKP`/`Ed25519`, `status` active or
  retired, unauthenticated).
- Verification offline: `docs/guides/provenance.md` (Node 22 script `docs/guides/verify-attestation.mjs`, no
  dependencies; tested against envelopes produced by `ProvenanceIntegrationTest`).

Example (job statement, shortened):

```json
{
  "_type": "https://in-toto.io/Statement/v1",
  "subject": [
    { "name": "git+https://git.example.com/shop.git@4f2a9c1e0b7d", "digest": { "sha256": "cc1be28a…", "gitCommit": "4f2a9c1e0b7d" } },
    { "name": "diff:8d36e132-…/impl/2", "digest": { "sha256": "d546081e…" } }
  ],
  "predicateType": "https://back-office.dev/attestations/agent-run/v1",
  "predicate": {
    "kind": "AGENT_JOB", "runId": "8d36e132-…",
    "project": { "id": "…", "key": "shop", "repository": "https://git.example.com/shop.git" },
    "workflow": { "id": "…", "name": "Issue to MR", "version": 3 },
    "node": { "id": "impl", "name": "Implement", "executionId": "…", "attempt": 2, "status": "SUCCEEDED", "port": "success" },
    "template": { "key": "implement-claude", "version": null, "lineage": ["implement-claude", "implement"] },
    "job": { "id": "…", "agent": "CLAUDE_CODE", "status": "SUCCEEDED", "branch": "feature/shop-1", "mergeRequestUrl": "…/merge_requests/7" },
    "model": { "ref": "anthropic/claude-opus-5", "effort": "HIGH" },
    "prompts": { "promptSha256": "0a39b193…", "constitutionSha256": "219a2047…", "skills": { "kotlin": "f351c0b9…" } },
    "sandbox": { "policyDigest": "a5b9341b…", "isolation": "STANDARD", "image": "ghcr.io/org/runner:2.0", "imageDigest": null,
                 "allowedHosts": ["repo.maven.apache.org"], "egressLogDigest": null },
    "usage": { "inputTokens": 1200, "outputTokens": 300, "cacheReadTokens": 0, "cacheWriteTokens": 0, "cost": { "amount": "1.750000", "currency": "USD" } },
    "verification": { "command": "./gradlew test", "exitCode": 0 },
    "gateDecisions": [],
    "timestamps": { "startedAt": "2026-09-20T10:00:00Z", "finishedAt": "2026-09-20T10:10:00Z", "attestedAt": "2026-09-28T10:19:28Z" }
  }
}
```

## MCP server

Streamable HTTP at `/mcp` (Spring AI `spring-ai-starter-mcp-server-webmvc`, protocol `STREAMABLE`, `SYNC`, tools
declared with `@McpTool` in `governance.adapter.in.mcp.BackOfficeMcpTools`). Authentication: **personal access
tokens only** (`Authorization: Bearer bo_pat_…`). `McpPersonalTokenFilter` answers anything else (no token, session
cookie, CI token) with 401 before the transport; the identity bearer filter authenticates the token (`/mcp` is added
to the authenticated paths and to the CSRF exemptions of `IdentitySecurityConfiguration`); tools re-check the
principal kind. Tools call the same `application.port.in` use cases as REST, so `AuthorizationGate` applies; a denied
or failed call is an MCP tool error (`isError: true`), not a transport error. Disable with
`BACKOFFICE_MCP_ENABLED=false`.

| Tool | Does | Use case | Permission |
|---|---|---|---|
| `list_my_turns` | open gates the caller may decide and failed runs the caller may retry | `ApprovalQueries.list`, `RunQueries.list` | `project.view`; items filtered by `run.approve` / `run.start` |
| `get_run` | run summary, nodes, artefact list | `RunQueries.get` | `project.view` |
| `get_artifact` | artefact content (spec, plan, tasks, review, diff) by id or name | `RunQueries.artifact(s)` | `project.view` |
| `start_workflow` | start a workflow run for an issue key | `StartRun.start` (trigger `API`) | `run.start` |
| `decide_gate` | approve / revise / reject with a comment (checklist ids required for approve) | `DecideApproval.decide` | `run.approve` + the gate's approver rule + token option "Allow gate decisions via MCP" |
| `search_task_library` | find templates and skills by text | `ManageTaskTemplates.list`, `ManageSkills.list` | `library.view` |
| `get_project_context` | constitution, repositories (key, url, purpose, primary), CI entrypoints, workflows, recent runs | `ManageProjects`, `ManageWorkflows`, `RunQueries` | `project.view` |
| `get_delivery_insights` | the insights of a project (or of all visible projects) | `DeliveryInsightsQuery.get` | `project.view` |
| `list_projects` | the projects the caller can see | `ManageProjects.list` | `project.view` |
| `get_workflow_palette` | control nodes, task templates (ports, config schema) and state fields to build a workflow from | `ManageWorkflows.palette` | `project.view` |
| `get_workflow` | one workflow with its definition as `WorkflowDefinitionInput` JSON | `ManageWorkflows.get` | `project.view` |
| `validate_workflow` | check a definition without saving it (errors, warnings) | `ManageWorkflows.validate` | `project.view` |
| `create_workflow` | create a workflow; validated first, nothing saved on errors | `ManageWorkflows.create` | `workflow.edit` + token option "Allow workflow edits via MCP" |
| `update_workflow` | replace a definition (new version), optional `expectedVersion` against concurrent edits | `ManageWorkflows.update` | `workflow.edit` + token option "Allow workflow edits via MCP" |

Prompt injection (ADR 0029, [12-prompt-injection.md](12-prompt-injection.md)): every tool carries MCP annotations
(read tools `readOnlyHint`; `decide_gate` `destructiveHint=true`, `idempotentHint=false`, so clients ask the
user); `decide_gate` additionally needs a personal access token created with **Allow gate decisions via MCP**
(`allowMcpGateDecisions`, default off; refusals audited as `MCP_GATE_DECISION` `DENIED`), and the gate rules of the
use case (approver roles, assignees, `forbidSelfApproval`) apply as in the UI. Text produced by agents, models or
issue authors (artefact content, node summaries/errors, gate questions/summaries, failure messages) is returned
sanitised inside `<untrusted-data>` blocks with a `notice` field.

Workflow authoring (ADR 0049): definitions travel as JSON in the shape of `WorkflowDefinitionInput`
(`WorkflowDocumentJson` / `WorkflowJson` in `projects.application.port.in`, the one encoding shared with REST and
persistence). `create_workflow` and `update_workflow` need a token created with **Allow workflow edits via MCP**
(`allowMcpWorkflowEdits`, `TokenCapabilities.MCP_WORKFLOW_EDITS`, default off; refusals audited as
`MCP_WORKFLOW_EDIT` `DENIED`): an injected agent could otherwise remove a human gate. Every write is validated
first and versioned by the use case; `update_workflow` is marked destructive.

Architecture exception (ArchitectureTest `mcpServerUsesOnlyUseCasePortsOfOtherContexts`): `governance.adapter.in.mcp`
and its wiring in `governance.adapter.config` are a driving adapter composing several contexts, like `app`; they may
import the `application.port.in` packages of Projects and Task Library, the domain types those ports return and
`RunFilter` — nothing else of those contexts. All other governance adapters reach other contexts only through
shared-kernel ports and SQL read models.

Client configuration (e.g. Claude Code): `claude mcp add --transport http back-office https://backoffice.example.com/mcp
--header "Authorization: Bearer bo_pat_…"`.
