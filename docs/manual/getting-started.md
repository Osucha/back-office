# Getting started

Back Office is the control room where AI agents and your CI do the delivery work of a project, and a
person is asked only when a decision is needed. This chapter explains the few ideas the rest of the
manual builds on and the first things to set up.

<a id="what-is"></a>
## What Back Office is

You describe a delivery process once, as a **workflow** on a canvas: fetch the Jira issue, write a
specification, plan, implement, wait for CI, review, ask a person to approve the merge. Back Office then
runs that workflow for each piece of work:

- **Agents** do the writing and coding. Short steps are a single model call inside Back Office; coding
  steps run as agent jobs in a sandbox on a runner (see [How agent jobs run](agent-execution.md#lifecycle)).
- **Your CI** runs tests and reports back with the `backoffice-ci` script. Back Office never calls your
  Git server's API; the agent pushes a branch, your pipeline runs (see [GitLab CI and other CI](integrations-gitlab-ci.md#ci-overview)).
- **People** decide at **gates**: approve, send back for revision, or reject. Everything that needs you
  lands in one queue, **Your turn**.

Every result says who produced it (agent and model, person, or CI) and what it cost.

Software delivery is the default, not a requirement. A project can have one Git repository, several (for
example code and documentation), or none: a project without a repository serves any work done with language
models, such as writing, analysis, translation or document reviews, with the same gates, costs and audit. It
starts with the workflow **LLM task with review**: a model does the task you describe, and a person approves
the result or sends it back (see [Projects without a repository](projects.md#no-repository)).

<a id="concepts"></a>
## Key concepts

| Concept | In one sentence | Where you see it |
|---|---|---|
| Project | Workflows, members, CI tokens, sandbox policy and budget, with zero or more Git repositories. | **Projects**, `/projects/:id` |
| Workflow | A graph of nodes (tasks, decisions, gates, CI waits) that a run follows. | Project tab **Workflows**, the designer |
| Run | One execution of a workflow, usually for one Jira issue. | **Runs**, `/runs/:id` |
| Node | One block of a workflow; a run visits nodes one at a time. | Designer canvas, run **Timeline** |
| Gate | A node where the run stops until an approver picks an option. | **Your turn**, the gate side panel |
| Task template | A reusable task (prompt, executor, skills, config) that a task node points to. | **Library** → **Templates** |
| Model profile | A model with its options and prices, picked by tasks and benchmarks. | **Models** |
| Agent job | A coding task that runs in a sandbox on a runner. | Run **Timeline**; administrators see **Agent jobs** |
| Budget | A monthly spending limit for the platform or a project. | Project tab **Budget**, **Costs** |
| CI execution | A workflow started from your pipeline through an entrypoint. | **CI**, project tab **CI** |

The [Glossary](glossary.md) defines every term used in the manual.

<a id="baton"></a>
## The baton: who holds the work

Every run, step, CI execution and benchmark attempt shows a **baton chip**: a symbol and a word saying
who holds the work right now. It is the single status indicator across the app, and state is never shown
by colour alone.

| Holder | Meaning | Typical words on the chip |
|---|---|---|
| agent (blue, filled hexagon) | An agent is working. | agent working, reviewing, starting |
| you (amber, raised hand) | A person who can act must do something. | your turn |
| ci (teal, loop arrow) | The run waits for your project's CI. | waiting for CI |
| done (green, check) | Finished successfully. | done, gate passed, approved |
| blocked (red, stop sign) | Failed or rejected. | failed, timed out, rejected, finished: failure |
| idle (grey, hollow circle) | Queued, cancelled, expired, or waiting for someone else. | queued, cancelled, with approver |

Only *your turn* is amber, and only when **you** can act. A gate that waits for a different approver shows
**with approver** in grey. Animations (the breathing agent dot, the rotating CI arrow) stop when your
system asks for reduced motion.

The **relay track** is a strip of segments, one per visited step, coloured by the holder at that step. It
appears small in run lists and full size, with durations and costs, at the top of a run.

<a id="first-steps"></a>
## First steps

### If you set up the installation

Administrators see a **Set up Back Office** checklist on **Home** until every step is done (hide it with
the close button). The steps, in order:

| Step | What to do |
|---|---|
| **Configure a model provider key** | Provider keys are set on the server, not in the UI. **Settings** shows which providers are configured and has **Test connection**. See [LLM providers](integrations-llm.md#llm-overview). |
| **Create the first project** | Opens **Projects** with the **Create project** form. See [Projects](projects.md#create-project). |
| **Invite someone** | Opens **Administration** → **Users**. A gate needs an approver; a gate that forbids self-approval cannot be decided by the person who started the run. See [Administration](administration.md#users). |
| **Name a project manager** | Opens **Administration** → **Grants**. Members cannot create projects; give the **Project manager** role to the people who create projects and decide who does which step. See [Project managers](administration.md#project-managers). |
| **Connect CI** | Opens the first project's **CI** tab to create a CI token. See [Projects](projects.md#project-ci) and [GitLab CI](integrations-gitlab-ci.md#ci-token). |

### If someone invited you

1. Sign in with the link or the sign-in method your administrator gave you (see
   [Sign-in](integrations-identity.md#oidc-overview)).
2. Open **Projects**. If the list is empty, ask a project manager or a project owner to add you (project
   managers create projects, see [Who creates projects](projects.md#who-creates)); what you can do in a project
   depends on your project role (see [Members and roles](projects.md#members)).
3. Open **Your turn** to see gates you can decide. If you are an approver, this is where you will spend
   most of your time (see [Your turn and the inbox](your-turn.md#inbox)).
4. To start work yourself, open a project, tab **Workflows**, and click **Start run** next to a
   workflow (see [Starting a run](workflows.md#start-run)).

### Finding your way

| Element | What it does |
|---|---|
| Rail (left) | Project switcher on top, then **Home**, **Your turn**, **Projects**, **Runs**, **Insights**, **CI**, **Library**, **Models**, **Evaluation**, **Costs**; **Administration** and **Settings** at the bottom. Entries you have no permission for are hidden. |
| Top bar | Breadcrumb, search, the amber counter of gates you can decide (hidden at zero), the live-updates indicator, the **Activity** button, and the avatar menu (account, theme, density, sign out). |
| Command palette | `Ctrl K` (or `⌘ K`, or `/`): search projects, workflows, runs by issue key or id, templates, and actions such as **Start run…**, **Create project**, **Toggle theme**. |
| Keyboard shortcuts | `?` lists the shortcuts of the current page. Lists use `j` / `k` to move and `Enter` to open. |
| Activity dock | `.` opens a live feed of events for the current project or all projects. |

Pages update live while you look at them. There are no e-mail or chat notifications: what needs you
appears in **Your turn** and in the amber counter. A workflow can post a comment to the Jira issue with
the `notify-jira` task (see [Jira](integrations-jira.md#jira-tasks)).
