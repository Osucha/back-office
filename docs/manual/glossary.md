# Glossary

The terms used in Back Office and in this manual, in alphabetical order. Each entry links to the chapter
that explains it.

**Accountable role**: The single project role that answers for a gate's decision (the "A" of RACI). Shown on the turn card and
in the gate panel. See [Gate settings](workflows.md#gates-config).

**Agent**: An AI model doing a task: a single model call inside Back Office, or a coding agent (OpenCode, Claude
Code) in an agent job.

**Agent job**: A coding or Spec Kit task that runs in a sandbox on a runner, with its own job token, limits and logs.
See [How agent jobs run](agent-execution.md#lifecycle).

**Approver**: A person allowed to decide a gate: `run.approve` in the project plus the gate's own rule. Also the name of
a project role. See [Deciding a gate](your-turn.md#gates).

**Artefact**: A document a step produced, such as `spec`, `plan`, `tasks`, `review` or `diff`. Shown on the run page and,
when the gate lists it, in the gate panel. See [The run page](runs.md#run-detail).

**Attempt**: One candidate model working on one task of a benchmark (once per repetition), scored by the judge. See
[Benchmarks](evaluation.md#benchmarks).

**Attestation**: A signed statement that binds a commit and diff to how they were produced (model, prompts, sandbox, cost,
approvals). See [Provenance](runs.md#provenance).

**Audit log**: The record of every change, decision, sign-in and denied access. See [Administration](administration.md#audit).

**Baton**: Who holds a piece of work right now: agent, you, ci, done, blocked or idle. Shown as a chip with a symbol
and a word. See [The baton](getting-started.md#baton).

**Benchmark**: A run of a task suite against several candidate models, scored by one fixed judge model. See
[Benchmarks](evaluation.md#benchmarks).

**Budget**: A monthly spending limit for the platform or a project, with an alert threshold and an optional hard
stop. See [Budgets](costs-insights.md#budgets).

**Ceiling**: The most a sandbox policy layer may allow, set by the layers above it (platform, pool). Lower layers can
only narrow it. See [Sandbox policy](projects.md#sandbox-policy).

**Checklist**: Definition of Ready or Done items on a gate. Approving needs every required item ticked. See
[Gate settings](workflows.md#gates-config).

**CI execution**: A workflow run started by your pipeline through an entrypoint, with its gate result (pass or fail). See
[CI executions](runs.md#ci-executions).

**CI signal**: The message your pipeline sends with `backoffice-ci signal <name> --status success|fail` to release a run
waiting for CI. See [Wait for CI nodes](workflows.md#nodes).

**CI token**: A project token (`bo_ci_…`) that lets your pipeline start runs and send signals for one project. See
[CI: entrypoints and tokens](projects.md#project-ci).

**Confidence**: A number from 0 to 1 a task reports about its own result. Decisions use it, for example to skip a gate
when the agent is sure. See [Decisions](workflows.md#decisions).

**Constitution**: A project's principles in Markdown, given to Spec Kit and available to prompts. See
[Creating a project](projects.md#create-project).

**Decision node**: A node that routes the run by conditions over the run state. See [Decisions](workflows.md#decisions).

**Edge**: A connection from an output port of one node to another node.

**Entrypoint**: A key that maps `backoffice-ci run <key>` in your pipeline to a workflow, in blocking or advisory mode. See
[CI: entrypoints and tokens](projects.md#project-ci).

**Executor**: What runs a task template: LLM, Spec Kit, Implement, Fetch issue, Notify or Transition issue. See
[Templates](task-library.md#templates).

**Gate (human gate)**: A node where the run stops until an approver picks an option: approve, send back for revision, or
reject. See [Deciding a gate](your-turn.md#gates).

**Hard stop**: A budget setting: at the limit, the model gateway refuses further calls and agent jobs fail. See
[Budgets](costs-insights.md#budgets).

**Human wait**: The time runs spend waiting at gates; the headline figure of Insights. See [Insights](costs-insights.md#insights).

**Interface (task interface)**: A contract a task template promises: the inputs it needs, the outputs it produces and its ports. See
[Interfaces](task-library.md#interfaces).

**Isolation level**: How strongly a sandbox separates the agent from the machine: Dev, Standard (gVisor) or High (Kata
microVM). See [How agent jobs run](agent-execution.md#isolation-levels).

**Job token**: The single credential inside a sandbox (`bo_job_…`). It works only for its own job, its own model and its
budget, and expires with the job. See [How agent jobs run](agent-execution.md#credentials).

**Judge**: The model that scores benchmark attempts against the rubric. Fixed per benchmark. See
[Benchmarks](evaluation.md#benchmarks).

**Ledger (usage ledger)**: The record of every model call and agent job with tokens and cost. See [Costs](costs-insights.md#costs).

**Lineage**: A template's chain of parents, from the template itself to the root. Shown in the resolved preview. See
[Templates](task-library.md#templates).

**Max visits**: How often a node may run within one run; the guard against endless loops. See [Node kinds](workflows.md#nodes).

**MCP (Model Context Protocol)**: The protocol your IDE's coding agent uses to call Back Office tools (list turns, read artefacts, start
workflows, decide gates) with a personal access token. See [IDE and MCP](integrations-ide.md#mcp-tools).

**Model profile**: A model with its options (temperature, reasoning effort) and prices. Tasks and benchmarks pick profiles.
See [Model profiles](evaluation.md#models).

**Node**: One block of a workflow: Start, Task, Decision, Human gate, Wait for CI, End or Note. See
[Node kinds](workflows.md#nodes).

**PAT (personal access token)**: A token (`bo_pat_…`) that acts as you, with your permissions or fewer, for scripts and your IDE. See
[Personal access tokens](account.md#tokens).

**Permission**: A right such as `run.approve` or `library.manage`, at platform or project level. Roles bundle
permissions. See [Members and project roles](projects.md#members).

**Pool (runner pool)**: A group of runners that run the same kind of jobs, with a sandbox ceiling. See
[Runner fleet](runner-fleet.md#pools).

**Port**: A named output of a node. The port a node leaves through decides where the run goes next.

**Project**: Workflows, members, CI tokens, sandbox policy and budget, with zero or more Git repositories. See [Projects](projects.md#create-project).

**Project role**: Owner, Maintainer, Developer, Approver or Viewer: what a member may do in one project. See
[Members and project roles](projects.md#members).

**Relay track**: The strip of segments, one per visited step, coloured by who held each step. See
[The baton](getting-started.md#baton).

**Repository (project repository)**: A Git repository of a project, with a key, URL, default branch, purpose
(source code, documentation, other) and optionally the **primary** mark (the repository coding steps use when
they name none). Coding steps choose their repositories with read or push-and-merge-request access. See
[Repositories](projects.md#repositories) and [Repositories of a step](workflows.md#step-repositories).

**Resolved task**: What the agent finally receives: a template merged with its parents, project overrides and node
overrides. See [Templates](task-library.md#templates).

**Rubric**: The criteria the judge scores an answer by. See [Task suites](evaluation.md#suites).

**Run**: One execution of a workflow, usually for one issue. See [Runs](runs.md#run-list).

**Runner**: A runner-controller in your cluster that leases agent jobs from Back Office and runs each in a sandbox.
See [Runner fleet](runner-fleet.md#pools).

**Sandbox**: The isolated container or microVM an agent job runs in, with limited resources, network and tools. See
[How agent jobs run](agent-execution.md#lifecycle).

**Sandbox policy**: The limits of a sandbox, set in layers (platform, pool, project, template, node), each only narrowing.
See [Sandbox policy](projects.md#sandbox-policy).

**Shadowing**: A project template or skill with the same key as a global one replaces it for that project. See
[Templates](task-library.md#templates).

**Skill**: A reusable block of instructions (`SKILL.md`) added to the prompts or workspaces of the templates that
list it. See [Skills](task-library.md#skills).

**Suite (task suite)**: The set of prompt tasks and coding jobs models are measured on. See [Task suites](evaluation.md#suites).

**Task template**: A reusable task: executor, prompts, skills and config; it extends one parent and implements interfaces.
See [Templates](task-library.md#templates).

**Trigger**: How a workflow is meant to start: manual, API, CI entrypoint or Jira transition. See
[Starting a run](workflows.md#start-run).

**Turn (your turn)**: Something that needs you: a gate to decide, a failed run or CI review, a budget. Collected in the
**Your turn** queue. See [Your turn](your-turn.md#inbox).

**Workflow**: A project's delivery process as a graph of nodes that runs follow. Every save is a new version. See
[Designing workflows](workflows.md#designer).
