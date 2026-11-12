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
| **Short code** | Back Office's own code of the project: 2–16 uppercase letters, digits or underscores, for example `SHOP`. Optional: left empty, it is generated from the name. It appears in CI logins, MCP tools and the `{{project.key}}` template variable, and can be changed later in **Settings** (past runs keep the old code). It is **not** the Jira key. |
| **Name**, **Description** | Shown on the project card and page. |
| **Repositories** | Optional. The Git repositories agents clone and [Deliver changes](workflows.md#deliver-changes) pushes to, each with a key, URL, default branch and purpose; leave the list empty for a project without code. See [Repositories](#repositories). Back Office never calls your Git server's API; your CI calls Back Office. Agents need Git credentials on the runner (see [Git access for agents](integrations-git.md#git-overview)). |
| Issue tracker switch (**Jira** / **none**) | Turn on when the workflows use the Jira actions (fetch issue, comment on issue, transition issue). The Jira credentials are [connections](#project-connections). |
| **Jira project key** | Required with Jira on: the key of the Jira project, the prefix of its issue keys (`LFV` in `LFV-123`). The designer uses it to check which connection a Jira node gets, and the intake watcher reads this project. It is independent of the short code. |
| **JQL filter** | Narrows the issues the **Jira intake watcher** reacts to, for example `issuetype = Story AND labels = ai`. It does not limit runs started by hand, by CI or through MCP. |
| **Jira intake watcher** (Settings tab, after creation) | Lets Jira start runs: when an issue enters a status (or a new issue appears), a chosen workflow starts with its key. **Watch Jira and start runs**, **Poll every (minutes)**, **Workflow to start**, a status card and **Poll now**; needs `project.manage` and a reader connection. See [Jira intake watcher](integrations-jira.md#jira-intake). |
| **Default model profile** | Used by model-backed tasks that name no profile. |
| **Review model profile** | Used by code-review tasks; empty means the default profile. |
| **Constitution (markdown)** | Optional. The project owner's rules. Every agent of the project gets them as part of its [environment](task-library.md#environment) (instructions can also read them as `{{project.constitution}}`). |

3. Click **Create project**.

### What you see afterwards

You become the project's **Owner**. What else is created depends on the repositories:

- **With at least one repository**: the workflows **Issue to reviewed MR**, **CI code review**, **Change request
  (delta)** and **Reference: one Claude Code session**, and the CI entrypoint `code-review` (see [The default
  workflows](workflows.md#default-workflows)). You can edit, duplicate or delete them.
- **Without a repository**: one starter workflow, **LLM task with review**, and no CI entrypoint (see
  [Projects without a repository](#no-repository)).

<a id="repositories"></a>
## Repositories

A project can have no repository, one, or several, for example the service code, its documentation and an
API contract. A workflow lists the ones its run clones in its [Sources](workflows.md#workflow-sources); agents in
coding steps read and change them, and only [Deliver changes](workflows.md#deliver-changes) pushes to them. Every
other step (model calls, Jira, gates, CI waits) works without any.

Edit the list on the project form (**Create project**) or on the project tab **Sources**. You need
`project.manage`.

| Field | Meaning |
|---|---|
| **Key** | Short name, lower case letters, digits and hyphens, starting with a letter (up to 31 characters), for example `service` or `docs`. Unique in the project. Workflow sources and deliveries refer to the repository by it, and it is the folder the agent finds the repository in (`/workspace/<key>`). |
| **URL** | The Git URL the runner clones (and a delivery pushes to). |
| **Default branch** | Default `main`. The branch agents start from and merge requests target. Nothing is ever pushed to it. |
| **Purpose** | **Source code**, **Documentation** or **Other**. A label you see in the designer and on runs; the agent is told it too. |
| **Description** | Optional. What is in the repository, for people and for the agent (passed to the agent as data, not as instructions). |
| **Primary** | At most one. The repository a workflow uses when its sources are empty. Optional. |

1. Click **Add repository** and fill in the row.
2. Mark one repository **Primary** if most workflows work in it, or none if every workflow should list its
   sources.
3. Click **Save** (or **Create project**).

**Primary or not.** With a primary repository, workflows written for one repository work without change: a workflow
with empty **Sources** uses the primary. Without a primary, every workflow with coding steps or a delivery must list
its sources ([Sources of a workflow](workflows.md#workflow-sources)); the designer reports the workflows that do not.
Projects created before this feature have their repository as `main`, purpose **Source code**, primary.

**Changing the list later.** Removing a repository or changing its key can break workflows that name it. The
designer shows the error, and a run whose sources name a repository the project no longer has does not start; the
message lists the project's repositories.

**Credentials.** Each repository needs a read token and, if a workflow delivers to it, a push token
([Git access for agents](integrations-git.md#git-least-privilege)). Agent jobs get only the read token; only a
[Deliver changes](workflows.md#deliver-changes) job gets the push token.

<a id="sources"></a>
### The Sources tab

The project tab **Sources** gathers everything the workflows of the project can clone: on the left the project's
repositories (the editor above, saved with **Save changes**), on the right the
[library sources](task-library.md#sources) shared by all projects, read only here. Each workflow chooses its sources
in the designer ([Sources of a workflow](workflows.md#workflow-sources)).

<a id="no-repository"></a>
### Projects without a repository

Back Office is built for software delivery but serves any work done with language models: writing,
analysis, translation, reviews of documents. Create the project with an empty **Repositories** list. It
gets the starter workflow **LLM task with review**:

1. **Start** → **LLM task** (template `llm-task`): write what the model should do in the node's parameter
   **Task** (under **Parameters**; empty means the default: summarise the input material). The parameter **Input
   material**, asked at start, is given to the model as material (data, not instructions).
2. → **Result ok?** (a gate): **approve** finishes the run with success, **revise** sends your comment back to
   the task, **reject** finishes the run with failure.

Start it with **Start run** and write the text to work on into **Input material**
([Starting a run](workflows.md#start-run)). Coding steps cannot be used until the project has a repository.
When you add the first repository, the default workflows and the `code-review` entrypoint are added once
(not if workflows with those names already exist).

## The project page

`/projects/:id` shows the key, the repositories (key, purpose, primary marked), the Jira key, who created the project and your
roles in it. Below the header: runs **In flight** by holder, **Open gates**, CI executions in the last 7
days and the cost of the last 30 days. **New workflow** opens the designer.

The tabs, each reachable with `?tab=` in the URL:

| Tab | Content | Changing it needs |
|---|---|---|
| **Workflows** | The project's workflows with triggers, state, last change; **Start run**, **Edit** / **View**, and the more-actions menu (**Duplicate workflow**, **Delete workflow**). When default workflows are missing (projects created before they existed): **Add missing default workflows (N)** ([The default workflows](workflows.md#default-workflows)). | `workflow.edit` (start: `run.start`) |
| **Sources** | The project's repositories and the library sources (see [The Sources tab](#sources)). | `project.manage` |
| **Agents** | The agents the project's steps can use: the global ones and the project's own; **Create agent**, **Import from library** (see [Agents of the project](#project-agents)). | `library.project.manage` |
| **Runs** | The project's runs (see [Runs](runs.md#run-list)). | — |
| **CI** | Entrypoints, CI tokens, a GitLab CI snippet and the CI executions. | `project.manage`, `project.ci.manage` |
| **Members** | Users and groups with their project roles. | `project.members.manage` |
| **Connections** | The project's Jira connections and the platform connections it falls back to (see [Project connections](#project-connections)). | `project.connections.manage` |
| **Connectors** | The tools the project's agents use: the project's connectors and the platform's (see [Connectors of the project](#project-connectors)). | `project.connections.manage` |
| **Sandbox** | The project's sandbox policy and what a job gets today. | `project.manage` |
| **Budget** | The project's monthly budget. | `project.manage` |
| **Settings** | The project form above (without the repositories, which are on **Sources**) with the constitution, the [Jira intake watcher](integrations-jira.md#jira-intake), and **Delete this project**. | `project.manage` |

<a id="project-agents"></a>
## Agents of the project

The tab **Agents** shows who can work on the steps of this project (ADR 0060): the global agents of the library and
the project's own agents, with **Scope** (**This project** or **Global**), **Kind**, **Model**, **Access** and
**Environment** (how many rules and skills). A project agent with the key of a global agent shows "replaces the
global agent", and that global agent shows "replaced in this project". What an agent is and its fields:
[Agents](task-library.md#agents).

With `library.project.manage` in the project:

- **Create agent** opens the agent form with the scope set to this project.
- **Import from library** picks a global agent and creates a project copy with the **same key**, then opens it for
  editing (for example another model, the project's rules, a narrower **Access**). The copy replaces the global agent
  in the steps of this project, without changing any workflow; other projects keep using the global one. This is the
  same as **Customize for project** in the library.

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
### Spec Kit (removed)

Back Office no longer runs GitHub Spec Kit (ADR 0063): the project switch **Spec Kit**, the **Spec Kit** card with
**Add Spec Kit workflows** and the platform setting **Allow Spec Kit** are gone. The built-in templates **Write
specification**, **Write plan** and **Write task list** have a coding agent write the same documents into the
repository (`specs/<feature>/spec.md`, `plan.md`, `tasks.md`), and **Issue to reviewed MR** uses them, so every
project with a repository gets it. Steps of the former Spec Kit templates now run as these templates
([Workflows stored before ADR 0063](workflows.md#task-nodes)). The constitution stays: it is the project owner's rules
for every agent. An agent can still use Spec Kit by itself when your repository or the agent's skills bring it.

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

<a id="project-connectors"></a>
## Connectors of the project

The **Connectors** tab lists the tools the agents of this project use (ADR 0064): the project's own connectors and,
marked **platform**, the platform connectors it can use. A project connector with the key of a platform connector
replaces it in this project (the platform one shows **replaced by the project's**). What a connector is, its kinds,
**Test** and the **Host to allow**: [Connectors](task-library.md#connectors).

- **Add connector**, edit, **Test** and delete need `project.connections.manage`: role **Owner** only. Everyone with
  `project.view` sees the list, never the secrets. Platform connectors open read-only here.
- **GitLab (read-only)** exists only here: `glab` for the agents with the project's verified read-only token with
  `CI_READ` of a **Repository** (empty: the primary one); add that token on the **Sources** tab
  ([Git access for agents](integrations-git.md#git-agent-tools)).
- An **MCP server (HTTP)** reaches agents only when the sandbox allows its host ([Sandbox policy](#sandbox-policy)).
- Agents use a connector only when they list its key; the built-in **OpenCode** and **Claude Code** list
  `gitlab-read`.

Deleting the project deletes its connectors.

<a id="sandbox-policy"></a>
## Sandbox policy

Coding agents run in a sandbox whose limits are set in layers: platform, runner pool, project. Each layer can only
narrow what the layers above allow. The **Sandbox** tab sets the project layer and shows the result. Templates and
workflow steps have no sandbox settings (ADR 0062): the sandbox is a property of the project, so every job of the
project gets the same limits (a step's timeout can only shorten the job). Details are in
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
