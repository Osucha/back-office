# Costs, budgets and insights

Every model call and agent job is booked in a usage ledger with its cost, budgets stop spending before it
surprises you, and insights show where the time goes between an issue and a merged change and which agent gets
work accepted for the least money. This chapter covers the **Costs** page, budgets, the cost of an accepted result
and the **Insights** pages.

<a id="costs"></a>
## Costs

**Costs** (`/costs`) shows the usage ledger: what each model call and agent job cost, for which work and
with which model.

| Who sees what | Permission |
|---|---|
| Every project and every benchmark | `usage.view` (roles **Auditor**, **Platform administrator**) |
| The projects where you hold `project.usage.view` | project roles **Owner**, **Maintainer**, **Developer**, **Approver** |

The rail shows **Costs** only when you hold one of these.

### Reading the page

| Part | Content |
|---|---|
| Header | The budget state for the selected project, or the platform, with a link to change or view it. |
| **Total** | The cost of the period, with **Calls**, **Input tokens**, **Output tokens** and **Cache read**. |
| **Cost per day** | A chart of the period. |
| Breakdown | Grouped **By model**, **By project**, **By benchmark**, **By kind of work** or **By day**. |
| **Ledger** | One row per entry: **When**, **Model**, **Work** (run and step, benchmark attempt, review, test), **Tokens**, **Duration**, **Cost**. |

### Filtering

- Periods: **7 days**, **30 days** (default), **This month**, **90 days**, or a date range.
- **All projects** narrows to one project.
- Search the ledger by model, step, run or benchmark.

Costs are computed from the prices on the model profiles (see [Model profiles](evaluation.md#models)).
For Claude Code jobs the cost the agent reports is booked, and the price-list estimate is kept for
comparison. Home shows **Cost this week**; projects show the cost of the last 30 days; runs and steps show
their own cost.

<a id="api-cost"></a>
### Cost at API prices

Next to the cost (money spent through Back Office) every step, run and workflow metric also has the **cost at API
prices**: the same work at the providers' list prices. The two are equal for work through the LLM gateway. They
differ for steps a subscription paid for, such as Claude Code with your own login on a
[personal runner](personal-runner.md#credentials): there the cost is 0 and the cost at API prices is Claude Code's
estimate. Wherever they differ the UI shows `$0.00 · API $0.05`, and workflow metrics have the card **At API prices per
successful run**. Use it to compare tasks and workflows whoever pays; budgets and the costs page count only money spent.

<a id="cost-per-accepted"></a>
### Cost of an accepted result

Back Office exists to find cheaper ways to get work accepted: the same task done by different agents and different
workflows, measured the same way. The headline number for that is the **cost of an accepted result**: everything
spent, failures and rework included, divided by what was accepted. A cheap agent that fails half of the time is not
cheap; this number shows it.

| Level | Accepted means | Cost of an accepted result | Where you see it |
|---|---|---|---|
| Task step | an execution that succeeded, is the last execution of its step in its run, and the run ended successfully | the cost of all executions / accepted executions | [Insights → Agents](#agent-insights) |
| Workflow | a successful run (it reached a success end, its gates approved) | the cost of every run / successful runs | **Cost per accepted result** in the workflow metrics ([Measuring workflows](workflow-library.md#metrics)) and in the library list |
| Benchmark | a passed attempt | the candidate's total cost / passed attempts | the leaderboard column **Cost per accepted result** ([Benchmarks](evaluation.md#benchmarks)) |

So a step that failed and was retried, or that a gate sent back for a revision, is paid by the execution that was
finally accepted; a run that failed is paid by the runs that succeeded.

Human time is not converted to money: Back Office knows how long a run waited for people, not how long they worked.
The workflow metrics show the **Human wait** and the **First-pass rate** next to the cost instead. A merge in GitLab
is not tracked either: a successful run (its gates approved) is the accepted result.

<a id="budgets"></a>
## Budgets

A budget caps spending per calendar month (UTC). Spend counts every ledger entry: agent calls through the
model gateway, workflow steps, reviews and benchmarks.

| Budget | Where | Who sets it |
|---|---|---|
| Platform | **Administration** → **Platform settings**, **Platform budget** | `admin.settings.manage` |
| Project | Project tab **Budget** | `project.manage` (project **Owner**, **Maintainer**) |
| Per agent job | **Max cost (USD)** and **Max tokens** in a sandbox policy | project, task template |

The platform budget is a ceiling over every project; project budgets apply inside it, and the stricter
one stops agents first.

### Steps: set a budget

1. Open the project's **Budget** tab (or the **Platform budget** section of **Platform settings**).
2. Turn on **Monthly limit**. Off means no limit: spend is only reported.
3. Enter **Limit (USD)** and **Alert at (%)** (default 80).
4. Choose **Hard stop**.
5. Click **Save project budget** (or **Save platform budget**).

### What happens as spend grows

| State | Chip | What happens |
|---|---|---|
| No limit | no limit | Spend is only reported. |
| Under the alert threshold | within budget | Nothing. |
| Alert | budget alert | A budget card appears in **Your turn** for everyone who may change the budget, for example "SHOP budget at 82%: $410.00 of $500.00", with the forecast for the month's end. |
| Exceeded, with **Hard stop** | over budget · agents stopped | The model gateway refuses further calls (HTTP 402). Agent jobs stop and fail with `budget-exceeded`. The turn card says "Agents are stopped until the limit is raised or the month ends" and offers **Raise the limit**. |
| Exceeded, without hard stop | over budget | Work continues; the overrun is reported and a turn card appears. |

The chip is amber (your turn) when you may change the budget, and grey or red otherwise.

The **Budget** tab shows **Spent in** the month, a bar with the alert mark and the forecast mark, and a
**Forecast** line. The forecast is a straight-line projection of the month so far. Budgets reset at the
start of each month. How budgets are enforced inside the sandbox is described in
[How agent jobs run](agent-execution.md#budgets-in-sandbox).

<a id="insights"></a>
## Insights

**Insights** (`/insights`) shows delivery metrics for the projects you can see (`project.view`), from the
runs created in the selected period. The switcher under the title, **Delivery / Agents**, moves between this page
and [Agents](#agent-insights) (`/insights/agents`).

### Filtering

- **All visible projects**, or one project.
- Periods: **7 days**, **30 days** (default), **90 days**, **This quarter**.

### Headline figures

| Figure | Meaning |
|---|---|
| **Human wait** | Time runs spent waiting at gates: the time people owe the agents. This is the headline. |
| **Lead time** | From the first run for an issue to the merge request (p50). |
| **CI wait** | Time runs spent waiting for the project's CI (p50). |
| **Run success rate** | Runs that reached a successful end, of all finished runs. |
| **Cost per successful run** | Ledger cost of successful runs, plus the cost per merge request and the total. |

### The rest of the page

| Section | Content |
|---|---|
| **Bottleneck** | The gate with the longest p90 wait, in which workflow. **Open the gate in the designer** jumps to it. Fewer approvers, a checklist that is quicker to tick, or an accountable role on call shorten it. |
| **Runs and human wait per week** | Succeeded and failed runs and human wait hours by week. |
| **Agent success by template** | Agent jobs per task template: **Jobs**, **Success**, **Cost**. |
| **Agent success by model** | The same per model. |
| **Gate decisions** | How often each option (approve, revise, reject) was chosen. |

An empty page means no run in the period reached a gate or CI yet; **Show 90 days** widens the period.
The same figures are available to your IDE through the MCP tool `get_delivery_insights` (see
[IDE and MCP](integrations-ide.md#mcp-tools)).

### Limits

- Metrics start from runs **created** in the period; a long run that started earlier is not counted.
- Waits are counted when they end; a gate that is still open adds nothing yet.
- Gate decisions are not split by approver role.

<a id="agent-insights"></a>
## Agents: cost and effectiveness

**Insights** → **Agents** (`/insights/agents`, also **Compare agents** in the command palette) answers: which agent
with which model gets a task step accepted for the least money? It reads the task steps of the runs you can see
(`project.view`) created in the period. Every task step records who did it: the agent (for example **Claude Code**,
**OpenCode**, **Analyst** or one of your own) and the model it ran with; the run page shows the same
([The run page](runs.md#run-detail)).

Filters as on the delivery page: **All visible projects** or one project, and the period.

| Section | Content |
|---|---|
| **Per agent and model** | One row per agent and model, cheapest accepted result first. |
| **Per template** | The same rows per task template: the same agents on the same task. Compare within one template; an agent that only ever does easy steps looks cheap in the first table. |

| Column | Meaning |
|---|---|
| **Agent**, **Model** | Who did the steps and with which model profile. |
| **Executions** | Task step executions, retries and revisions included. |
| **Accepted** | Executions that succeeded, were the last execution of their step in the run, and the run ended successfully. |
| **First-pass rate** | Accepted executions that were the step's first attempt / accepted executions. |
| **Cost** | The cost of all executions (with the cost at API prices where a subscription paid, see [Cost at API prices](#api-cost)). |
| **Cost per accepted result** | **Cost** / **Accepted**: failed attempts and rework are paid by the accepted ones. **No accepted result yet** when nothing was accepted. |
| **Median time** | Median duration of an execution. |

Task step executions from before agents were recorded have no agent; the page says how many there are ("... have no
agent record ... and are not counted"). An empty page means no run in the period executed a task step;
**Show 90 days** widens the period.

The same figures are available to your IDE through the MCP tool `get_agent_insights` (see
[IDE and MCP](integrations-ide.md#mcp-tools)).

### Steps: compare agents on the same issues

1. Every project with a repository has the workflow **Reference: one Claude Code session**: one Claude Code
   session implements the issue end to end and opens the merge request, a person reviews it
   ([The default workflows](workflows.md#default-workflows)). It is the yardstick.
2. Run the same issues through the reference and through the cheaper workflows, or through the same workflow with
   cheaper agents and models on its task steps ([Task nodes](workflows.md#task-nodes)).
3. Compare the steps in **Insights** → **Agents**, **Per template**, and the whole workflows in their metrics:
   **Cost per accepted result**, **First-pass rate** and **Human wait** side by side
   ([Measuring workflows](workflow-library.md#metrics)).
4. Keep the cheaper way where its cost of an accepted result is lower without more human wait; a benchmark measures
   the same agents on prepared tasks before you try them on real issues ([Benchmarks](evaluation.md#benchmarks)).
