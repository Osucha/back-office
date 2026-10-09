# 0033 — Jira intake watcher and approval before external writes

- Status: accepted
- Date: 2026-10-08
- Deciders: owner
- Related: ADR 0023 (PostgreSQL work queue, ShedLock), 0028 (step assignment, `run.assign`), 0029
  (prompt-injection defences), 0030 (least-privilege connections), 0032 (desktop notifications);
  docs/architecture/05-integrations.md, docs/architecture/03-pipelines-and-agents.md,
  docs/manual/integrations-jira.md#jira-intake, docs/manual/integrations-jira.md#jira-write-approval

## Context

ADR 0030 introduced Jira connections with capabilities and left two items "designed for, not built yet":

- **Starting work from Jira.** A run started only when a person pressed **Start run** with an issue key, a
  CI pipeline called an entrypoint, or the MCP tool `start_workflow` was used. The workflow trigger
  `JIRA_TRANSITION` and the project field **JQL filter** (`issueTracker.jql`) were stored but nothing
  used them. Teams want "move the issue to *Ready for AI* and the agent picks it up".
- **Writes to Jira happened without a person seeing them.** **Notify Jira** posts a comment rendered from
  run state that can contain model output and issue text; **Transition issue** moves the issue. Both are
  external side effects that cannot be undone from Back Office. Issue text is untrusted (ADR 0029) and
  can steer model output, so a prompt-injected comment would be posted as the bot account, visible to
  everyone on the issue.

Constraints: Back Office must keep least privilege (ADR 0030: a reader account for reading, never a writer
for reading), must work behind firewalls without inbound traffic, and must run on any number of replicas
without starting the same issue twice.

## Decision

### Jira intake watcher

1. **One watcher per project**, configured on the project page (Settings tab, card "Jira intake
   watcher"): `enabled`, poll interval in minutes (1–1440, default 5) and the workflow to start. The JQL
   filter is the existing project field `issueTracker.jql`, now used. Stored in table `jira_intake`
   (projects Flyway `V108__jira_intake_and_write_approval.sql`). API: `GET`/`PUT
   /api/v1/projects/{projectId}/jira-intake` (schemas `JiraIntake`, `JiraIntakeInput`, `JiraIntakeStatus`),
   `POST /api/v1/projects/{projectId}/jira-intake/poll` ("Poll now", `202`). Reading needs `project.view`;
   configuring and polling need `project.manage` (owners and maintainers). Audited `JIRA_INTAKE_UPDATE` and
   `JIRA_INTAKE_POLL_NOW`.
2. **The workflow's `JIRA_TRANSITION` trigger decides the mode.** With a status (for example
   `Ready for AI`): an issue that *enters* that status starts one run with its `issueKey` (transition
   mode). Without a status: a *new* issue matching the JQL, created after the watcher started, starts a run
   (new-issue mode). A workflow without a `JIRA_TRANSITION` trigger cannot be chosen.
3. **Polling with JQL and relative dates.** Back Office sends
   `project = "<Jira key>" AND (<project JQL without ORDER BY>) AND status CHANGED TO "<status>" AFTER -<n>m ORDER BY updated ASC`
   (transition mode) or `... AND created >= -<n>m ORDER BY updated ASC` (new-issue mode) to
   `GET /rest/api/3/search/jql` with `fields=status,updated,created&expand=changelog`; at most 100 issues per
   poll, in pages of 50 via `nextPageToken`. `n` is the minutes since the last successful poll plus a
   two-minute overlap (at least 2 minutes, at most 7 days). Relative dates avoid the time zone of the Jira
   user, which absolute JQL dates would use. The connection's scoped client adds its allow-list on top:
   `project in (KEYS) AND (...)` (ADR 0030).
4. **Reads metadata only.** The watcher reads key, status, `updated`, `created` and the status changes of the
   changelog. It never reads summary, description or comments: issue text is untrusted (ADR 0029) and
   reaches prompts only later, through the run's **Fetch issue** step and the existing data-block helpers.
5. **Least privilege.** The watcher uses only a connection with `ISSUE_READ`, chosen by the ADR 0030
   selector (amended: least privilege across project and platform). Without such a connection it refuses to
   poll and shows "refused: ..." on the watcher. If the chosen connection holds more capabilities (a writer),
   it polls and shows a least-privilege warning; the client it receives is narrowed to reading in any case.
6. **Exactly one run per transition, across replicas.** Before a run starts, the watcher claims a key in
   table `jira_intake_claim` (primary key project + key, an insert that only one replica wins). Transition
   mode: `T:<issue>:<changelog history id>` of the latest change into the status (fallback
   `T:<issue>:<status>:<updated millis>` when Jira returned no changelog). New-issue mode: `N:<issue>`. If
   the run cannot start (for example its Jira steps have no connection), the claim is released and the error
   is shown; the next poll retries. Claims older than 30 days are purged.
7. **Cursor and baseline.** `cursor` is the start time of the last successful poll. The first poll after
   enabling, re-enabling or changing the workflow only sets the baseline: the existing backlog never starts
   runs. At most 20 runs start per poll; when more events exist the cursor does not advance, so the next poll
   continues where this one stopped.
8. **Scheduling on the work queue.** A cluster singleton (ShedLock `jira-intake-scan`, every
   `backoffice.jira-intake.scan-delay` ms, default 30000) on worker replicas enqueues a `JIRA_INTAKE_POLL`
   work item per due project (dedupe key `JIRA_INTAKE_POLL:<projectId>`, one live item per project); any
   worker performs the poll (ADR 0023).
9. **Errors and rate limits.** HTTP `429`, `5xx` and other Jira errors record `lastError` and back off: the
   poll interval doubles per consecutive failure, up to one hour; a longer `Retry-After` (seconds) wins.
10. **Runs started by the watcher** have the system actor "Jira intake watcher" and trigger `JIRA`; the
    start is audited `RUN_START` with `trigger=JIRA` and `intakeDedupeKey`. They are ordinary runs.

### Approval before external writes

11. **A node setting, default on.** Jira steps whose required capabilities have the `write` flag
    (**Notify Jira** `NOTIFY` → `ISSUE_COMMENT`, **Transition issue** `TRANSITION_ISSUE` →
    `ISSUE_TRANSITION`) have node configuration `approval: REQUIRED | NOT_REQUIRED`, default `REQUIRED`. A
    missing value (every workflow saved before this change) means `REQUIRED`; an unknown value is a validator
    error. The palette marks these tasks `externalWrite: true`.
12. **Render once, pause before writing.** With `REQUIRED` the step renders its write once (the comment
    after templates and sanitising, or the target status), checks that a usable connection exists (no
    approval is asked for a write that cannot happen), stores the write in the run state (`pendingWrite`)
    and routes to a synthetic interrupting LangGraph node `<nodeId>::confirm` (like `<nodeId>::await` for
    runner jobs). The run waits with wait kind `WRITE_CONFIRMATION`; the node execution stays open
    (`WAITING`, then `RUNNING`) and finishes with the write.
13. **A write confirmation in the inbox.** An approval of kind `WRITE_CONFIRMATION` opens: "Agent wants to
    comment on SHOP-42" with the exact comment text, or "Agent wants to move SHOP-42 to In Review".
    Options are approve and reject only; nothing can be edited. The preview (action, issue key, comment or
    target status, connection name, SHA-256 `contentSha256`) is stored on the approval
    (`approval_request.kind`, `approval_request.write_preview`).
14. **Approve writes exactly the preview; reject writes nothing.** Approve performs the previewed write
    without rendering again. Reject leaves the step through its `failure` port (unconnected: the run fails,
    as for any failed step) with "write rejected by <name>, nothing was written (...) - <comment>".
15. **Who approves** is configured on the node like a human gate (`approvers`: project roles, named users,
    accountable role, forbid self-approval). Default: holders of `run.approve` with project role owner or
    maintainer (accountable: owner). Owners and maintainers can reassign a waiting confirmation with
    `run.assign` (ADR 0028). Write confirmations never time out; the node timeout limits execution, not the
    wait.
16. **Audit with digests.** The decision is `RUN_WRITE_DECIDED` (action, issueKey, toStatus,
    contentSha256); the write is `EXTERNAL_WRITE` (action, issue, connection, contentSha256, approval
    `APPROVED` / `NOT_REQUIRED`, approvedBy, result `WRITTEN` / `FAILED`). Equal digests prove that the
    written content is the approved preview.
17. **Opting out is visible.** With `NOT_REQUIRED` the step writes immediately and logs a warning; the
    designer validator shows a warning on the node: the step writes to Jira without anybody confirming, and
    issue text or model output can reach it (prompt injection); keep `REQUIRED` unless the content is fixed.
18. **Notifications.** `RunWaitingForHuman` carries `kind` (`GATE` / `WRITE_CONFIRMATION`); the desktop
    notifier (ADR 0032) announces a new write confirmation the user may decide with the title "Confirm a
    Jira write" and the body "Agent wants to comment on SHOP-42", under the *your turn* type.
19. **Seeded workflow.** The "Move issue to In Review" transition step of **Issue to reviewed MR** now asks
    for confirmation by default.

## Alternatives considered

- **Jira webhooks instead of polling.** Rejected for now. A webhook needs inbound reachability from
  Atlassian Cloud to Back Office (a public endpoint, which many installations do not have), validation of
  the webhook secret, and registering a webhook needs Jira administrator rights, which contradicts the
  reader-only account of ADR 0030. Polling with a reader works behind firewalls and with the least
  privilege. Webhooks can be added later as a trigger that enqueues the same poll.
- **JQL history alone (`status CHANGED TO ... AFTER ...`) without the changelog.** Not enough for dedupe:
  JQL says that an issue changed into the status in the window, not which change, so an issue that left and
  re-entered the status could not be told from one seen twice through the poll overlap. The changelog
  history id identifies each transition; JQL still narrows the search.
- **Absolute timestamps in JQL.** Rejected: Jira interprets them in the time zone of the API user, so a
  misconfigured account would shift the window. Relative minutes do not depend on it.
- **A new `rejected` port on write steps.** Rejected: it would change the task templates and every existing
  graph. The existing `failure` port already means "nothing was written"; designers route it to an
  `END FAILURE` or a rework step.
- **Rendering the content again after approval.** Rejected: the approved text must be the text that is
  written. State can change while the confirmation waits (a loop, a retry), and a second rendering could
  differ from what the approver saw.
- **A separate HUMAN_GATE node before the write step.** Rejected: a gate cannot show the exact content the
  step will write (it is rendered by the step), and the content could change between the gate and the step.
  Designers would also have to remember to add the gate; a default-on node setting cannot be forgotten.
- **Approval as a platform-wide switch.** Rejected: some writes are fixed text (for example "Run started")
  and do not need a person; a per-node setting with a visible warning keeps the decision with the designer.

## Consequences

- New Flyway projects `V108__jira_intake_and_write_approval.sql`: tables `jira_intake`,
  `jira_intake_claim`; columns `approval_request.kind`, `approval_request.write_preview`,
  `connection.expiry_reminded_for` (ADR 0030 amendment).
- New audit codes: `JIRA_INTAKE_UPDATE`, `JIRA_INTAKE_POLL_NOW`, `RUN_WRITE_DECIDED`, `EXTERNAL_WRITE`.
  New work kind `JIRA_INTAKE_POLL`, ShedLock name `jira-intake-scan`, property
  `backoffice.jira-intake.scan-delay`.
- Existing workflows with **Notify Jira** or **Transition issue** start asking for confirmation after the
  upgrade (missing setting = `REQUIRED`). Runs that used to finish unattended now wait at these steps;
  designers who want the old behaviour set `NOT_REQUIRED` deliberately and see the warning.
- Approvals have a `kind` (`GATE` / `WRITE_CONFIRMATION`); **Your turn** shows write confirmations next to
  gates, and the same deny-by-default and four-eyes rules apply to both.
- An issue that leaves and re-enters the status between two polls is detected through the changelog id of
  the latest change; when Jira returns no changelog, a re-entry within one poll window may be missed.
- The watcher can start at most 20 runs per poll and per project; a large burst is spread over several
  polls.
- Polling costs one Jira search per project per interval (plus pages); a 429 backs the watcher off without
  affecting run steps.
