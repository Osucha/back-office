# Workflow library

A workflow that works well in one project does not have to be rebuilt in the next. Publish it to the
**Library**, add it to other projects from there, and see how it performs in every project that runs it:
how often it succeeds, how often it is right the first time, how long it takes and what a success costs.
This chapter covers the **Library → Workflows** pages and the library actions in a project's **Workflows** tab.

<a id="library-workflows"></a>
## Library workflows

**Library → Workflows** lists the shared workflows with their latest version, the projects that use them
and the headline figures of the chosen period (7, 30 or 90 days, or this quarter):

| Column | Meaning |
|---|---|
| Used in | projects (that you can see) with a linked copy |
| Runs | runs of the workflow started in the period |
| Success | runs that reached a success end, of the finished runs |
| First pass | successful runs without a "revise" decision or a retried step, of the finished runs |
| Median time | half of the finished runs took less than this, waits included |
| Cost / success | ledger cost of the successful runs divided by their number |

Click a row to open the workflow. The **Performance** tab shows the figures in detail, the **Graph** tab
shows any version on a read-only canvas, **Versions** lists what changed in each version and where it was
published from, and **Projects** lists the linked copies, which version they follow, whether a newer one is
available and whether they were changed locally.

### Who may do what

| You want to | Permission |
|---|---|
| See the library workflows and their metrics | `library.view` (every **Member**); metrics count only runs of projects you can see |
| Publish a workflow to the library | `library.manage` (role **Library curator**) and `workflow.edit` in the project it comes from |
| Rename, tag, deprecate or delete a library workflow | `library.manage` |
| Add a library workflow to a project, update or detach a copy | `workflow.edit` in that project (and `library.view`) |

<a id="publish"></a>
## Publishing a workflow

1. Design and run the workflow in a project until it does what you want, then save it.
2. In the project's **Workflows** tab open the workflow's menu (**⋯**) and choose **Publish to library**.
3. Choose **New library workflow** (name, key, description, tags) or **New version of an existing one**.
   A workflow that is already linked to a library workflow offers its next version by default.
4. Say in a sentence what changed. It is shown in the version history next to the metrics of each version.

What is **not** published, because it belongs to the project: the repositories of a step, a Jira connection
pinned to a step (the modes *system connection* and *acting user* are kept), named approvers of gates and
write steps (approver roles are kept), and the workflow's name, triggers and on/off state. Steps must use
**global** task templates: a project template has to be published to the global task library first.
Published versions never change; publishing an unchanged graph is refused.

<a id="adopt"></a>
## Adding a workflow to a project

In a project, click **Add from library** (next to **New workflow**), or open the library workflow and click
**Use in a project**. Pick the workflow, the version (the latest by default) and, if you like, another name.
The project gets a **linked copy**: an ordinary workflow that runs, can be started from CI and can be changed
in the designer, marked with the library key and version (for example `issue-to-mr v3`). Set the
project-local parts in the designer: step repositories, the Jira connection and named approvers.
Deprecated library workflows are not offered.

<a id="update-detach"></a>
## Taking new versions, local changes and detaching

When the library has a newer version, the copy shows **v4 available**. Choose **Update from library** in the
workflow's menu: the steps, decisions and gates of the new version replace the old ones, and the project-local
parts of steps with the same id, the name, the triggers and the on/off state stay. The workflow gets a new
version, as with any save; runs in progress keep the version they started with.

A copy whose graph you changed in the project is marked **changed locally** (moving nodes on the canvas or
changing project-local parts does not count). Updating such a copy replaces your changes, so Back Office
asks first. If the change is an improvement for everyone, publish it as a new library version instead.

**Detach from library** removes the link: the workflow stays as it is, receives no more library versions,
and its future runs no longer count towards the library metrics. Past runs keep the version they ran.

<a id="metrics"></a>
## Measuring workflows

Every run of a linked copy records the library workflow and version it ran, so the metrics stay exact after
the copy or the library changes. The **Performance** tab of a library workflow shows:

- **Effectiveness**: success rate (successful of finished runs; failed and cancelled runs count against
  it), first-pass rate (successful without a revision or a retried step) and rework loops per run.
- **Efficiency**: median and p90 time to finish (waits included), median **active time** (time to finish
  minus waiting for people at gates and for CI: what the agents and steps take), human wait, cost per run
  and per successful run, tokens.
- **By version**, newest first, each compared with the previous version that has finished runs: a green
  arrow is better (success rate up by 5 points or more, time or cost down by 10 % or more), a red one worse.
  Versions with fewer than 5 finished runs are marked with `*`: wait for more runs before drawing
  conclusions.
- **By project**: the same figures per project, to spot a project where the workflow struggles.
- **Steps**: executions, failures, median and p90 duration, cost and the ports taken (for example how often
  a gate answered `approve` or `revise`). A step that fails often or takes most of the cost is highlighted.

Runs of copies that were changed locally are counted and their number is shown, so you can tell whether a
version or the project's edits made the difference. Any project workflow, linked or not, has the same view
for its own versions: the chart icon in the project's **Workflows** tab.

Definitions of every figure: [docs/architecture/11-governance.md](../architecture/11-governance.md#workflow-metrics).
