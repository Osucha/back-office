# My work

Back Office works in two ways at once. In a **team project** a manager designs the process and decides which
steps people decide and which agents do; the project's Jira watcher starts runs on its own
([Jira intake watcher](integrations-jira.md#jira-intake)). **My work** is for the other case: your team works
however it likes, and you use Back Office to automate **your own** part. Your rules watch the Jira issues assigned to
you, every new one shows up in **My work** with a notification, and nothing starts until you click **Start**.

<a id="my-work"></a>
## How it works

1. A **private project** holds your workflows (see below), or you use a team project where you may start runs.
2. A **personal Jira connection** lets Back Office read Jira **as you** (**My account → Connections**,
   [Personal connections](integrations-jira.md#jira-personal-connections)); a reading token is enough.
3. A **rule** says which issues are yours (JQL), how often to check and which workflow to propose.
4. Every issue that appears in the rule's results for the first time becomes a **proposal**: issue key, summary,
   type, status. Click **Start** to run the proposed workflow, the swap button to start another workflow, or
   **Dismiss**. A dismissed proposal can be restored and started later.

A run you start from a proposal is yours, exactly as if you had clicked **Start run** in the project: it receives the
issue key, and Jira steps set to act as the person who started the run use your personal connection.

<a id="private-projects"></a>
## Private projects

On the **Projects** page, **Create private project** creates a project of which you are the only member (every user
may; team projects are created by project managers). Everything else works as in a team project: repositories or
none, workflows designed on the canvas or added from the [workflow library](workflow-library.md), budgets, runs. It
is marked **private**, and members cannot be added. Platform administrators can still see it, like every project.

<a id="rules"></a>
## Rules

**My work → Rules → New rule**:

| Field | Meaning |
|---|---|
| Name | for you, shown with each proposal |
| Read Jira as | one of your personal connections with **Read issues** |
| JQL | which issues are yours. The default `assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC` finds the open issues assigned to you; `currentUser()` is the account of your connection. Narrow it as you like, e.g. `AND project = SHOP` or `AND status in ("To Do", "Selected")` |
| Check every | 1 to 1440 minutes (default 5) |
| Workflow to propose | a workflow you may start; private projects first |
| Also propose the issues that already match now | on the first check: on = every matching issue is proposed once; off = only issues that start matching later |

**An issue is proposed once per rule**, the first time it appears in the results, whatever made it appear: it was
assigned to you, it moved into a status your JQL asks for, or it was created. If it disappears from the results and
comes back, it is not proposed again. Each check reads at most the 50 most recently updated issues of the JQL, and
only their key, summary, type, status and priority; the summary is shown to you only and never sent to a model by
the rule (the run's **Fetch issue** step reads the issue when you start it).

**Check now** runs a check right away. When Jira fails (a wrong JQL, an expired token, rate limits), the rule shows
the error and checks less often until it works again; a rule whose connection was deleted stops with a reason.

<a id="notifications"></a>
## Notifications

When a rule finds new issues, Back Office shows the desktop notification **New Jira task: SHOP-42** (or the number
of tasks) to you only, if notifications are on and the type **New Jira tasks** is selected in **My account →
Notifications** ([Desktop notifications](account.md#notifications)). Clicking it opens **My work**. The **New**
tab updates live.

### Who may do what

| You want to | Permission |
|---|---|
| Use My work (rules, proposals) | `connections.personal` (every **Member**) |
| Create a private project | `projects.create.personal` (every **Member**) |
| Start a proposal | `run.start` in the workflow's project (you hold it as the owner of your private project) |

Your rules and proposals are visible to you only; their changes are audited (`INTAKE_RULE_*`, `INTAKE_PROPOSAL_*`).
