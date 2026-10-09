# Projects

A project binds its Git repositories (none, one or several), an optional Jira project and a constitution
to its workflows, members, CI tokens, sandbox policy and budget. This chapter covers creating a project,
its repositories and every tab of the project page.

<a id="create-project"></a>
## Creating a project

<a id="who-creates"></a>
### Who creates projects

Projects are created by **project managers**, not by every user: you need the platform permission
`projects.create`, which the built-in role **Project manager** (`project-manager`) and platform
administrators have. The default **Member** role does not have it. If you do not see **Create project**,
the Projects page says "Project managers create projects": ask a project manager to create the project,
or a project owner to add you to an existing one. Administrators give the role in
**Administration → Grants** ([Project managers](administration.md#project-managers)).

The manager who creates a project becomes its **Owner**, adds the team with project roles
([Members and project roles](#members)) and decides who does which step: in the workflow
([Gate settings](workflows.md#gates-config)) and, for a waiting step, with **Assign…**
([Assigning a step](your-turn.md#assign-steps)).

### Steps

1. Open **Projects** and click **Create project** (or use **Create project** in the command palette).
2. Fill in the form:

| Field | Meaning |
|---|---|
| **Key** | 2–16 uppercase letters, digits or underscores, for example `SHOP`. Cannot be changed later. |
| **Name**, **Description** | Shown on the project card and page. |
| **Repositories** | Optional. The Git repositories agents clone and push to, each with a key, URL, default branch and purpose; leave the list empty for a project without code. See [Repositories](#repositories). Back Office never calls your Git server's API; your CI calls Back Office. Agents need Git credentials on the runner (see [Git access for agents](integrations-git.md#git-overview)). |
| Issue tracker switch (**Jira** / **none**) | Turn on when the workflows use the Jira tasks (fetch issue, notify, transition). The Jira credentials are [connections](#project-connections). |
| **Jira project key** | Defaults to the project key. The designer uses it to check which connection a Jira node gets. |
| **JQL filter** | Narrows the issues the **Jira intake watcher** reacts to, for example `issuetype = Story AND labels = ai`. It does not limit runs started by hand, by CI or through MCP. |
| **Jira intake watcher** (Settings tab, after creation) | Lets Jira start runs: when an issue enters a status (or a new issue appears), a chosen workflow starts with its key. **Watch Jira and start runs**, **Poll every (minutes)**, **Workflow to start**, a status card and **Poll now**; needs `project.manage` and a reader connection. See [Jira intake watcher](integrations-jira.md#jira-intake). |
| **Default model profile** | Used by model-backed tasks that name no profile. |
| **Review model profile** | Used by code-review tasks; empty means the default profile. |
| **Spec Kit** | Off for new projects. Turn it on to use the Spec Kit steps (specify, plan, tasks) and the constitution ([Spec Kit](#spec-kit)). |
| **Spec Kit constitution (markdown)** | Shown while **Spec Kit** is on. Your project's principles, written to `.specify/memory/constitution.md` before Spec Kit jobs run, and available to prompts as `{{project.constitution}}`. Kept when Spec Kit is turned off. |

3. Click **Create project**.

### What you see afterwards

You become the project's **Owner**. What else is created depends on the repositories:

- **With at least one repository**: the workflows **CI code review** and **Change request (delta)**, plus
  **Issue to reviewed MR** when **Spec Kit** is on (it is built on Spec Kit), and the CI entrypoint
  `code-review` (see [Designing workflows](workflows.md#designer)). You can edit, duplicate or delete them.
- **Without a repository**: one starter workflow, **LLM task with review**, and no CI entrypoint (see
  [Projects without a repository](#no-repository)).

<a id="repositories"></a>
## Repositories

A project can have no repository, one, or several, for example the service code, its documentation and an
API contract. Agents in coding steps (**Implement**, **Spec Kit**) clone them; every other step (model
calls, Jira, gates, CI waits) works without any.

Edit the list on the project form (**Create project**, or tab **Settings**). You need `project.manage`.

| Field | Meaning |
|---|---|
| **Key** | Short name, lower case letters, digits and hyphens, starting with a letter (up to 31 characters), for example `service` or `docs`. Unique in the project. Workflow steps refer to the repository by it, and it is the folder the agent finds the repository in (`/workspace/<key>`). |
| **URL** | The Git URL the runner clones (and pushes to, for steps allowed to push). |
| **Default branch** | Default `main`. The branch agents start from and merge requests target. Agents never push to it. |
| **Purpose** | **Source code**, **Documentation** or **Other**. A label you see in the designer and on runs; the agent is told it too. |
| **Description** | Optional. What is in the repository, for people and for the agent (passed to the agent as data, not as instructions). |
| **Primary** | At most one. The repository coding steps use when they name none. Optional. |

1. Click **Add repository** and fill in the row.
2. Mark one repository **Primary** if most coding steps work in it, or none if every step should name its
   repositories.
3. Click **Save** (or **Create project**).

**Primary or not.** With a primary repository, workflows written for one repository work without change: a
coding step that names no repository uses the primary. Without a primary, every coding step must list its
repositories ([Repositories of a step](workflows.md#step-repositories)); the designer reports the steps that do
not. Projects created before this feature have their repository as `main`, purpose **Source code**, primary.

**Changing the list later.** Removing a repository or changing its key can break workflows that name it. The
designer shows the error on the step, and a run whose steps name a repository the project no longer has does
not start; the message names the step and lists the project's repositories.

**Credentials.** Each repository needs a read token and, if steps push to it, a push token in the runner
cluster ([Git access for agents](integrations-git.md#git-least-privilege)). A step gets only the access it
asks for, per repository.

<a id="no-repository"></a>
### Projects without a repository

Back Office is built for software delivery but serves any work done with language models: writing,
analysis, translation, reviews of documents. Create the project with an empty **Repositories** list. It
gets the starter workflow **LLM task with review**:

1. **Start** → **LLM task** (template `llm-task`): write what the model should do in the node's **User
   prompt** with **Replace**. The run parameter `input` is given to the model as material (data, not
   instructions).
2. → **Result ok?** (a gate): **approve** finishes the run with success, **revise** sends your comment back to
   the task, **reject** finishes the run with failure.

Start it with **Start run** and parameters such as `{"input": "text to work on"}`
([Starting a run](workflows.md#start-run)). Coding steps cannot be used until the project has a repository.
When you add the first repository, the three SDLC workflows and the `code-review` entrypoint are added once
(not if workflows with those names already exist).

## The project page

`/projects/:id` shows the key, the repositories (key, purpose, primary marked), the Jira key, who created the project and your
roles in it. Below the header: runs **In flight** by holder, **Open gates**, CI executions in the last 7
days and the cost of the last 30 days. **New workflow** opens the designer.

The tabs, each reachable with `?tab=` in the URL:

| Tab | Content | Changing it needs |
|---|---|---|
| **Workflows** | The project's workflows with triggers, state, last change; **Start run**, **Edit** / **View**, and the more-actions menu (**Duplicate workflow**, **Delete workflow**). | `workflow.edit` (start: `run.start`) |
| **Runs** | The project's runs (see [Runs](runs.md#run-list)). | — |
| **CI** | Entrypoints, CI tokens, a GitLab CI snippet and the CI executions. | `project.manage`, `project.ci.manage` |
| **Members** | Users and groups with their project roles. | `project.members.manage` |
| **Connections** | The project's Jira connections and the platform connections it falls back to (see [Project connections](#project-connections)). | `project.connections.manage` |
| **Sandbox** | The project's sandbox policy and what a job gets today. | `project.manage` |
| **Budget** | The project's monthly budget. | `project.manage` |
| **Settings** | The project form above with the [repositories](#repositories), the [Spec Kit](#spec-kit) switch and constitution, the [Jira intake watcher](integrations-jira.md#jira-intake), the **Spec Kit** card (**Add Spec Kit workflows**), and **Delete this project**. | `project.manage` |

<a id="members"></a>
## Members and project roles

What you can do in a project comes from your project role. A user's permissions are the union of the
roles from direct membership and from every group that is a member.

| Role | Can |
|---|---|
| **Owner** | Everything in the project, including members, CI tokens, connections, assigning waiting steps (`run.assign`) and deleting the project. |
| **Maintainer** | Everything an owner can, except managing members and connections (assigning waiting steps included). |
| **Developer** | View, edit workflows, start, retry and cancel runs, send CI signals by hand, see costs. |
| **Approver** | View, decide gates, see costs. |
| **Viewer** | View the project, its workflows, runs and CI executions. |

Holders of the platform permission `admin.projects.all` (for example platform administrators) have
every project permission in every project. Administrators can create more roles (see
[Roles and grants](administration.md#roles-grants)).

### Adding a member

1. Open the project, tab **Members**. You need `project.members.manage` (role **Owner**).
2. In **Add member**, choose user or group, search by name or e-mail, pick a role and click **Add**.
3. To change a role, pick another one in the **Role** column. To remove a member, use the delete button
   in the last column.

Changes take effect on the member's next request and are recorded in the audit log. You only find users
who exist in Back Office; inviting new people is an administrator task (see
[Administration](administration.md#users)).

<a id="project-ci"></a>
## CI: entrypoints and tokens

Your pipeline talks to Back Office with the `backoffice-ci` script and a project CI token. Back Office
never triggers your CI; pushing the branch does. The full set-up is in
[GitLab CI and other CI](integrations-gitlab-ci.md#ci-overview).

**CI entrypoints** map a key to a workflow: `backoffice-ci run <key>` in your pipeline starts that
workflow with the CI context (merge request, branch, commit).

| Column | Meaning |
|---|---|
| **Key** | The name your pipeline uses, for example `code-review`. |
| **Workflow** | The workflow to start. |
| **Mode** | **Blocking** fails the CI job when the run ends with a failure outcome; **Advisory** never fails it. |
| **Diff** | Upload the merge request or branch diff as the artefact `diff`. |
| **Repository** | Optional. Only CI jobs of this project repository may start the entrypoint; empty = any of the project's repositories. |

Click **Add**, fill the row and click **Save entrypoints** (or **Discard**). Needs `project.manage`.

Back Office recognises which repository a CI job runs in by its URL (sent by `backoffice-ci` 2.1.0 and
later) and passes the key to the run as the parameter `repository`. A job from a repository the project does
not list is refused with the list of the project's repositories
([GitLab CI and other CI](integrations-gitlab-ci.md#ci-overview)).

**CI tokens** (`bo_ci_…`) let your pipeline start runs and send signals for this project only.

1. Click **Create token**, enter a **Token name** (for example `gitlab-ci`) and click **Create**.
2. Copy the token now: it is shown only once and stored hashed.
3. Add it to your CI as the protected, masked variable `BACKOFFICE_CI_TOKEN` (see
   [CI variables](integrations-gitlab-ci.md#ci-variables)).
4. Click **Done**. The list shows the prefix, when it was created and last used; **Revoke** stops it.

Needs `project.ci.manage` (role **Owner** or **Maintainer**).

**GitLab CI snippet** is a ready `.gitlab-ci.yml` fragment with the variables to set; **Copy** puts it on
the clipboard. **CI executions** lists what your pipeline started. The **CI** page in the rail (`/ci`)
shows CI executions of all your projects; a failed one appears in **Your turn** (see
[Your turn](your-turn.md#inbox)).

<a id="spec-kit"></a>
## Spec Kit

[GitHub Spec Kit](https://github.com/github/spec-kit) writes a specification, a plan and a task list in the
repository before the code is implemented. In Back Office it is **optional per project** (ADR 0036):

- **Off** (the default for new projects): the designer hides the Spec Kit templates (also library templates
  that extend them), saving a workflow with a Spec Kit step is refused, runs with such a step do not start, the
  constitution field is hidden, and agent jobs of the project do no Spec Kit work at all (no `specify init`, no
  `.specify/` directory, no `/speckit.implement`).
- **On**: Spec Kit steps work as before, and the constitution is written to `.specify/memory/constitution.md`
  before each Spec Kit job.
- Turn it on or off with the **Spec Kit** switch in the project form on the **Settings** tab
  (`project.manage`). The constitution is kept while Spec Kit is off.
- The workflow **Issue to reviewed MR** uses Spec Kit, so it is created only for projects with Spec Kit on.
  After turning Spec Kit on, the **Spec Kit** card on the **Settings** tab lists the Spec Kit workflows the
  project does not have yet with **Add Spec Kit workflows** (`workflow.edit`); existing workflows with the same
  name are never replaced.
- Administrators can disallow Spec Kit for the whole platform (**Administration** → **Platform settings** →
  **Features** → **Allow Spec Kit**). Then no project can run Spec Kit steps, whatever its own switch says.

Projects that already used Spec Kit before this setting existed keep it on.

<a id="project-connections"></a>
## Connections

The **Connections** tab holds the credentials the project's workflow steps use for Jira, in two parts:

- **Jira connections of this project**: the **system connection**, the default of every Jira step, chosen from
  the project's own connections or the platform connections ([Administration](administration.md#connections)),
  and an ordered list of further connections the steps may use. A step whose system connection lacks a
  capability takes the least-privileged connection of that list
  ([System connection](integrations-jira.md#jira-system-connection)). Without a system connection, steps pick
  from every project and platform connection.
- **Connections of this project**: the project's own Jira accounts.

- **Add connection**, edit, **Replace token**, **Test**, **Test before saving** and delete need
  `project.connections.manage`: role **Owner** only (not **Maintainer**). Everyone with `project.view` sees
  the list, never the tokens.
- Owners get a **Your turn** card when a project connection's token expires within 14 days
  ([Token expiry and rotation](integrations-jira.md#jira-token-expiry)).
- Set up two Jira accounts with exactly the permissions needed, a reader and a writer, and add one
  connection for each: [Least privilege](integrations-jira.md#jira-least-privilege),
  [Add a connection](integrations-jira.md#jira-connections).
- **Allowed projects** limits a connection to some Jira project keys; the designer checks it against the
  **Jira project key** on the **Settings** tab.
- **Test** shows missing and excess Jira permissions ([Test a connection](integrations-jira.md#jira-test)).
- Designers choose per Jira node whom it **acts as**: the system connection, a specific connection, or the
  person who approves or starts it, through that person's own personal connection
  ([Act as](integrations-jira.md#jira-act-as)).
- With your own Jira account and no service account, use scoped API tokens
  ([Scoped API tokens](integrations-jira.md#jira-scoped-token)).

Deleting the project deletes its connections.

<a id="sandbox-policy"></a>
## Sandbox policy

Coding agents run in a sandbox whose limits are set in layers: platform, runner pool, project, then task
template and workflow node. Each layer can only narrow what the layers above allow. The **Sandbox** tab
sets the project layer and shows the result. Details are in
[How agent jobs run](agent-execution.md#isolation-levels) and [Runner fleet](runner-fleet.md#sandbox-policies).

| Section | What you set |
|---|---|
| **Isolation** | **Standard** (gVisor, the default for model-written code), **High** (a Kata microVM per job, for untrusted repositories) or **Dev** (plain container, local development only). Levels weaker than the ceiling are disabled. |
| **Resources and time** | **CPU**, **Memory**, **Ephemeral storage**, **Processes**, **Timeout (minutes)**. |
| **Network allowlist** | **Allowed hosts** the agent may reach (`host` or `host:port`, `*` for one label). The model gateway and the Git hosts of the job's repositories are always added; everything else is refused and reported. |
| **Tools** | **Allowed tools**, **Denied tools**, **Tools that ask** (shell command patterns; deny always wins; unattended jobs treat "ask" as deny). |
| **Environment and secrets** | **Runner image**, **Setup command**, **Caches**, **Secret names** (names only; the runner cluster resolves the values). |
| **Cost and token caps per job** | **Max cost (USD)** and **Max tokens**, enforced at the model gateway. |
| **Pool selector** | **Labels the runner pool must have**, to send the project's jobs to particular pools. |

Empty fields inherit. Locked chips and "at most" hints come from the ceiling. **Effective for this
project** shows what a job gets today, with a badge naming the layer that set each value. Click **Save
sandbox policy** (or **Discard changes**). Needs `project.manage`; everyone else sees the tab read-only.

<a id="project-budget"></a>
## Project budget

The **Budget** tab caps what the project's agents may spend per calendar month (UTC). Every model call of
the project, including workflow steps and reviews, is checked against it before and after the call. The
platform budget applies on top; the stricter one stops agents first.

1. Turn on **Monthly limit** and enter **Limit (USD)**.
2. Set **Alert at (%)** (default 80).
3. Choose **Hard stop**: on, agents are refused at the limit and their jobs fail with `budget-exceeded`;
   off, work continues and the overrun is reported.
4. Click **Save project budget**.

The top of the tab shows **Spent in** the current month, a bar with the alert mark and the forecast, and
a **Forecast** line for the month's end. Crossing the alert threshold, or the limit, puts a budget card
in **Your turn** for everyone who may change the budget. Needs `project.manage`; members with
`project.usage.view` see it read-only. See also [Costs, budgets and insights](costs-insights.md#budgets).

## Deleting a project

Tab **Settings** → **Delete this project** → **Delete project KEY**. This removes the workflows, CI
tokens, connections and history of the project. The button is shown to owners. Back Office refuses the deletion while
runs are still active: cancel them first.
