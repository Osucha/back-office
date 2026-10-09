# Your turn and the inbox

Everything that needs a person lands in one queue: gates to decide, writes to Jira to confirm, failed runs
to retry, failed CI reviews, budgets that need attention and Jira tokens about to expire. This chapter
covers the **Home** page, the **Your turn** page, how to decide a gate and how to confirm an external
write.

<a id="home"></a>
## Home

`/home` is the page you land on after signing in. It answers "does anything need me?" first.

| Section | What it shows |
|---|---|
| **Your turn** | The first items of your queue as turn cards, most urgent first. **All N in Your turn** opens the full page when there are more. "Nothing needs you right now." is a normal, calm state. |
| **In flight** | Runs that are not finished, grouped as **Agents working**, **Waiting for CI**, **With other approvers** and **Queued**, each with its mini relay track, cost and age. **All runs in flight** opens **Runs** filtered to agent and CI. |
| **Set up Back Office** | Administrators only, until the setup steps are done (see [First steps](getting-started.md#first-steps)). |
| **Landed today** | Runs that finished successfully today, with duration and cost. |
| **Cost this week** | The last seven days of spend and this month's total, with a link to **Costs**. Shown when you may see usage. |

Keyboard: `j` / `k` move between turn cards, `Enter` opens one, `a` opens the gate under the cursor on its
checklist. A gate is never approved from Home; `a` only opens the gate panel.

<a id="inbox"></a>
## The Your turn page

`/inbox` (rail: **Your turn**) is the full queue. The switch at the top has four views:

| View | Content |
|---|---|
| **Needs you** | Your queue (below). The number is the item count. |
| **With others** | Open gates of your projects that someone else must decide. You can open them to read, not decide. |
| **Decided** | Gates that were decided, with who decided them. |
| **Expired** | Gates that timed out before anyone decided. |

### What lands in your queue

| Item | When it appears | Primary action |
|---|---|---|
| Gate | An open gate you are allowed to decide. | **Review gate** (`a`) |
| Write confirmation | A step wants to write to Jira and you may confirm it, for example "Agent wants to comment on SHOP-42" ([Confirming external writes](#write-confirmations)). | Open it, then **Approve** or **Reject** |
| Jira token expiring | A connection you can manage has a token that expires within 14 days or has expired: "The Jira token of <name> expires in 5 days" / "... has expired". | Open the connection, **Replace token** |
| Failed run | A run that failed in the last 7 days, which you started or may retry (`run.start`). | **Open run** |
| Failed CI review | A CI execution of one of your projects that failed or whose review gate failed, in the last 3 days. A CI failure that already shows as a failed run is not listed twice. | **Open review** |
| Budget | A monthly budget you manage that crossed its alert threshold or its limit. | **Review budget**, or **Raise the limit** when exceeded |

Order: gates and write confirmations first, then failed runs and exceeded budgets, then failed CI, then
budget alerts and expiring tokens; within a group, the longest waiting first. Each card shows what, where (project key · workflow or issue · node),
why you (for example "Accountable: owner · 2 required checks · agent confidence 72%") and how long it
has waited.

The amber counter in the top bar and on the rail counts only the gates you can decide.

A gate can also be linked directly: `/inbox/:approvalId` opens it in the side panel.

<a id="gates"></a>
## Deciding a gate

A gate is a point in a workflow where the run stops and waits for a person. Who may decide is set on the
gate in the workflow (see [Gate settings](workflows.md#gates-config)).

### Steps

1. Open the gate: click its card, press `a` on Home or **Your turn**, or click **Review gate** on the run
   page. The gate opens in a side panel; `Esc` closes it.
2. Read the top of the panel: the question, the agent's summary and its **Agent confidence**.
3. Check **Why you**: the **Accountable** role, the **Approvers**, **Assigned to** (the people named to
   decide, or the roles; "(you)" when it is you, and who reassigned it), the **Rule** when the person who
   started the run may not approve it, and a link to **Open run** (and **Merge request** when there is
   one).
4. Read the **Artefacts** the gate shows (for example `spec`, `plan`, `review`, `diff`), one per tab. To
   fix something yourself, click **Edit before deciding**, change the text, and use **Preview** or
   **Revert**. Edited artefacts are saved with your decision and the run continues with your version.
5. Tick the **Checklist** items you have verified. The count shows how many required items are done.
6. Write a comment if needed and click a decision button.

### Decision buttons

| Button | Shortcut | Effect | Needs |
|---|---|---|---|
| **Approve** (or **Approve and merge**) | `a` | The run continues through the approve port. | Every required checklist item ticked |
| **Send back for revision** | `r` | Your comment is added to the run's feedback and the run goes back, usually to the task that produced the artefact. | A comment |
| **Reject** | `x` | The run follows the reject path; if the workflow has none, the run is cancelled. | A comment |

A gate may offer other options than these three; each option is a button with its own name. When a
button is unavailable, the panel says why in words under the buttons, for example "Approve is
unavailable: tick all required checklist items first."

### When you cannot decide

The panel footer says **You cannot decide this gate.** with the reason, for example that you are not one
of the approvers or that you started the run and the gate forbids self-approval. Deciding needs the
project permission `run.approve` (project roles **Owner**, **Maintainer**, **Approver**) and the gate's
own approver rule. The rule is copied when the run reaches the gate, so later edits to the workflow do
not change who decides an open gate; a project owner or maintainer can reassign it
([Assigning a step](#assign-steps)).

### What you see afterwards

The gate leaves your queue, the run continues, and the decision appears on the run's timeline and in the
gate's **Decided** view with your name, the ticked items and the comment. Every decision is recorded in
the audit log. A gate with a timeout that nobody decides moves to **Expired** and the run follows the
gate's timeout path (or fails, if there is none).

### Deciding from your IDE

With a personal access token you can list your turns and decide gates from a coding agent through the
MCP server (`list_my_turns`, `decide_gate`). See [IDE and MCP](integrations-ide.md#mcp-tools).

<a id="write-confirmations"></a>
## Confirming external writes

Before a workflow step writes to Jira (a comment from **Notify Jira**, a status change from **Transition
issue**), the run stops and asks a person to confirm the exact write. Nothing has been written yet when
you see it. Why: [Approval before writing to Jira](integrations-jira.md#jira-write-approval).

### What you see

The confirmation appears in **Your turn → Needs you** like a gate, and as a desktop notification "Confirm a
Jira write" if you turned on *Your turn* notifications ([Desktop notifications](account.md#notifications)).
Its panel shows:

- the question: "Agent wants to comment on SHOP-42" or "Agent wants to move SHOP-42 to In Review";
- for a comment, **the exact text** that will be posted, as it will appear; for a move, the target status;
- the issue key, the connection (the Jira account) that will write, a SHA-256 of the content, and the run and step;
- **Why you**, **Assigned to** and **Rule**, as on a gate.

The text cannot be edited: what you approve is what is written, character for character.

### Steps

1. Open the confirmation from its card, or from the run page.
2. Read the whole text. Look for anything that should not go to Jira: internal details, links you do not
   know, instructions addressed to people or other tools, text copied from the issue that changes the
   meaning.
3. Optionally write a comment.
4. Click **Approve and write** to write exactly this, or **Reject (write nothing)**.

| Decision | Effect |
|---|---|
| **Approve and write** | The step writes the text shown (or moves the issue) and the run continues. |
| **Reject (write nothing)** | Nothing is written. The step fails with "write rejected by <your name>, nothing was written ... - <comment>" and the run follows the step's `failure` path; without one, the run fails. |

**Who may decide**: by default the project's **Owners** and **Maintainers** who hold `run.approve`; the
workflow designer can name other roles or people and forbid the person who started the run from deciding
([Confirmations before external writes](workflows.md#write-confirmations)). Owners and maintainers can hand
a waiting confirmation to someone else with **Assign…** ([Assigning a step](#assign-steps)).

Write confirmations do not expire; the run waits until someone decides or cancels the run. Both the
decision (`RUN_WRITE_DECIDED`) and the write (`EXTERNAL_WRITE`) are recorded in the audit log with a
SHA-256 digest of the text.

### Jira token expiring

People who can manage a Jira connection (administrators for platform connections, project owners for
project connections) also get a card when its token expires within 14 days ("The Jira token of <name>
expires in 5 days") or has expired ("The Jira token of <name> has expired"). Open the connection and use **Replace token**; the card disappears once
the new expiry date is more than 14 days away. Steps: [Token expiry and
rotation](integrations-jira.md#jira-token-expiry).

<a id="assign-steps"></a>
## Assigning a step

In Back Office a project manager creates the project and decides who does which step. The workflow says
who decides each gate for every run ([Gate settings](workflows.md#gates-config)); when a step is already
waiting, a project **Owner** or **Maintainer** (project permission `run.assign`) can hand it to specific
people for this run only, for example when the named approver is on leave.

### Steps

1. Open the waiting gate (from **With others**, **Needs you**, or **Review gate** / **View gate** on the
   run page), or stay on the run page.
2. Click **Assign…** (next to **Assigned to** in the gate panel, or in the run's "Waiting for an approver"
   banner).
3. In **Who decides this step**, pick one or more people in **Assign to**. The list shows only project
   members who can decide this gate: they hold `run.approve`, have one of the gate's roles (if set), and
   did not start the run when the gate forbids self-approval.
4. Optionally add a comment (for example why) and click **Assign**.

**Back to the workflow's rule** removes a run-time assignment; the gate's own rule applies again.

### What you see afterwards

- The gate shows **Assigned to** with the new names and "assigned by" with your name, time and comment.
  The run page banner says "Assigned to: ...".
- The assigned people see the gate in **Your turn → Needs you** ("Assigned to you by ..."), live; people
  who were named before see it under **With others**.
- The workflow itself does not change; the next run follows the workflow's rule again.
- The change is recorded in the audit log as `APPROVAL_ASSIGN` with the old and new assignees.

Assigning is possible only while the gate is open. The four-eyes rule and the gate's roles always apply:
you cannot assign the gate to someone the rule would refuse.
