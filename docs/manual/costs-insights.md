# Costs, budgets and insights

Every model call and agent job is booked in a usage ledger with its cost, budgets stop spending before it
surprises you, and insights show where the time goes between an issue and a merged change. This chapter
covers the **Costs** page, budgets and the **Insights** page.

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
runs created in the selected period.

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
