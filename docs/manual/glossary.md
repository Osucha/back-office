# Glossary

The terms used in Back Office and in this manual, in alphabetical order. Each entry links to the chapter
that explains it.

**Accepted result**: Work that counts as done. A task step execution that succeeded, is the last execution of its step
in its run, and the run ended successfully; a successful run; a passed benchmark attempt. See
[Cost of an accepted result](costs-insights.md#cost-per-accepted).

**Accountable role**: The single project role that answers for a gate's decision (the "A" of RACI). Shown on the turn card and
in the gate panel. See [Gate settings](workflows.md#gates-config).

**Action**: A workflow node that runs a fixed operation without a model: Fetch issue, Comment on issue, Transition
issue, Start workflow or Project digest. See [Action nodes](workflows.md#actions).

**Agent**: Who works and how: a library entry with a harness, optionally a model, instructions, rules, skills and MCP
servers. Without a harness it gives a single model answer inside Back Office (**Model answer**); with OpenCode or
Claude Code it is a coding agent session in an agent job. A template names its default agent; a step can choose
another, for example to compare two agents on the same task. See [Agents](task-library.md#agents).

**Access (of an agent)**: What a coding agent may change in the repositories of its steps: **Full**, **Read only** or
**Only these paths**. The runner reverts other changes after the agent finished. See
[Access of an agent](task-library.md#agent-access).

**Agent home**: The folder of one agent in the run directory, outside every repository (`/workspace/.agents/<agent
key>/`): its environment, skills, MCP configuration and sessions. See
[The environment of the agent](task-library.md#environment).

**Agent job**: A coding or Spec Kit task that runs in a sandbox on a runner, with its own job token, limits and logs.
See [How agent jobs run](agent-execution.md#lifecycle).

**Agent questions**: Questions an agent stops a step with when it cannot do it well without your answers; the run
waits until you answer or cancel the step. See [Answering an agent's questions](your-turn.md#agent-questions).

**Agent session**: The conversation of a coding agent. By default a step keeps its own session (its repeats
continue it); it can also start a new one each time or continue the session of an earlier step of the same agent.
See [Agent sessions](workflows.md#continue-session).

**Approver**: A person allowed to decide a gate: `run.approve` in the project plus the gate's own rule. Also the name of
a project role. See [Deciding a gate](your-turn.md#gates).

**Artefact**: A document a step produced, such as `spec`, `plan`, `tasks`, `review` or `diff`. Shown on the run page and,
when the gate lists it, in the gate panel. See [The run page](runs.md#run-detail).

**Attempt**: One candidate (an agent with a model) working on one task of a benchmark (once per repetition), scored by
the judge. See [Benchmarks](evaluation.md#benchmarks).

**Attestation**: A signed statement that binds a commit and diff to how they were produced (model, prompts, sandbox, cost,
approvals). See [Provenance](runs.md#provenance).

**Audit log**: The record of every change, decision, sign-in and denied access. See [Administration](administration.md#audit).

**Baton**: Who holds a piece of work right now: agent, you, ci, done, blocked or idle. Shown as a chip with a symbol
and a word. See [The baton](getting-started.md#baton).

**Benchmark**: A run of a task suite against several candidates (agents with models), scored by one fixed judge model.
See [Benchmarks](evaluation.md#benchmarks).

**Budget**: A monthly spending limit for the platform or a project, with an alert threshold and an optional hard
stop. See [Budgets](costs-insights.md#budgets).

**Candidate**: What a benchmark compares: an agent with a model (the agent's own model when none is picked), shown as
`agent · model`. Prompt tasks go to model answer agents, coding tasks to coding agents. See
[Benchmarks](evaluation.md#benchmarks).

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

**Constitution**: A project's principles in Markdown, given to Spec Kit and part of every agent's environment. See
[Creating a project](projects.md#create-project).

**Cost per accepted result (cost of an accepted result)**: Everything spent, failures and rework included, divided by
the accepted results; the headline number for comparing agents, workflows and benchmark candidates. See
[Cost of an accepted result](costs-insights.md#cost-per-accepted).

**Decision node**: A node that routes the run by conditions over the run state. See [Decisions](workflows.md#decisions).

**Edge**: A connection from an output port of one node to another node.

**Entrypoint**: A key that maps `backoffice-ci run <key>` in your pipeline to a workflow, in blocking or advisory mode. See
[CI: entrypoints and tokens](projects.md#project-ci).

**Environment of the agent**: The document every step gets before its task: the platform preamble, the agent's
instructions, the project constitution and the rules. A model answer gets it in the system prompt, OpenCode as an
`AGENTS.md` and Claude Code as its user `CLAUDE.md`, both outside the repository. See
[The environment of the agent](task-library.md#environment).

**Gate (human gate)**: A node where the run stops until an approver picks an option: approve, send back for revision, or
reject. See [Deciding a gate](your-turn.md#gates).

**Hard stop**: A budget setting: at the limit, the model gateway refuses further calls and agent jobs fail. See
[Budgets](costs-insights.md#budgets).

**Harness**: The program that runs an agent's conversation with its model (tools, context, questions): OpenCode or
Claude Code. An agent without one gives a model answer. See [Agents](task-library.md#agents).

**Human wait**: The time runs spend waiting at gates; the headline figure of Insights. See [Insights](costs-insights.md#insights).

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
workflows, decide gates) with a personal access token. See [IDE and MCP](integrations-ide.md#mcp-tools). An agent
of the library may also list remote MCP servers it uses in its sandbox; see
[MCP servers of an agent](task-library.md#agent-mcp).

**Model answer**: The built-in agent `model-answer`: one model call inside Back Office, without tools or a
repository; it can return structured JSON and a confidence. A step whose template and step name no agent runs this
way. See [Agents](task-library.md#agents).

**Model profile**: A model with its options (temperature, reasoning effort) and prices. Tasks and benchmarks pick profiles.
See [Model profiles](evaluation.md#models).

**Node**: One block of a workflow: Start, Task, Action, Decision, Human gate, Wait for CI, End or Note. See
[Node kinds](workflows.md#nodes).

**Parameter (template parameter)**: A typed field of a template (single line, multi-line text, number, yes / no,
choice) that a workflow step fills in; prompts read it as `{{params.<name>}}`. A required one without a value blocks
the step; such templates are marked **needs parameters**. See [Parameters](task-library.md#parameters).

**PAT (personal access token)**: A token (`bo_pat_…`) that acts as you, with your permissions or fewer, for scripts and your IDE. See
[Personal access tokens](account.md#tokens).

**Permission**: A right such as `run.approve` or `library.manage`, at platform or project level. Roles bundle
permissions. See [Members and project roles](projects.md#members).

**Pool (runner pool)**: A group of runners that run the same kind of jobs, with a sandbox ceiling. See
[Runner fleet](runner-fleet.md#pools).

**Port**: A named output of a node. The port a node leaves through decides where the run goes next.

**Preset**: A template that extends another one and only sets values for its parameters, made with **Create
preset**. See [Parameters](task-library.md#parameters).

**Project**: Workflows, members, CI tokens, sandbox policy and budget, with zero or more Git repositories. See [Projects](projects.md#create-project).

**Project role**: Owner, Maintainer, Developer, Approver or Viewer: what a member may do in one project. See
[Members and project roles](projects.md#members).

**Reference workflow**: The default workflow **Reference: one Claude Code session**: one Claude Code session implements
the issue end to end and opens the merge request, a person reviews it. The yardstick the cheaper workflows and agents
are compared with. See [The default workflows](workflows.md#default-workflows).

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

**Run directory**: The one directory of a run in which its coding steps work one after another, seeing what the
earlier steps left; removed when the run ends. See [The run directory](workflows.md#run-directory).

**Runner**: A runner-controller in your cluster that leases agent jobs from Back Office and runs each in a sandbox.
See [Runner fleet](runner-fleet.md#pools).

**Sandbox**: The isolated container or microVM an agent job runs in, with limited resources, network and tools. See
[How agent jobs run](agent-execution.md#lifecycle).

**Sandbox policy**: The limits of a sandbox, set in layers (platform, pool, project, template, node), each only narrowing.
See [Sandbox policy](projects.md#sandbox-policy).

**Shadowing**: A project template, skill, rule or agent with the same key as a global one replaces it for that project. See
[Templates](task-library.md#templates).

**Rule**: Something an agent must always follow, always in its context; automatic rules apply to every step of
their scope. See [Rules](task-library.md#rules).

**Skill**: Knowledge or a procedure (`SKILL.md`) an agent opens when the task needs it; given to the agents that
list it. See [Skills](task-library.md#skills).

**Suite (task suite)**: The set of prompt tasks and coding jobs models are measured on. See [Task suites](evaluation.md#suites).

**Task template**: A reusable description of what to do: category, prompts with parameters, config, outputs and the
agent that does it by default; it may be based on one parent. Rules and skills belong to the agent.
See [Templates](task-library.md#templates).

**Trigger**: How a workflow is meant to start: manual, API, CI entrypoint or Jira transition. See
[Starting a run](workflows.md#start-run).

**Turn (your turn)**: Something that needs you: a gate to decide, a failed run or CI review, a budget. Collected in the
**Your turn** queue. See [Your turn](your-turn.md#inbox).

**Workflow**: A project's delivery process as a graph of nodes that runs follow. Every save is a new version. See
[Designing workflows](workflows.md#designer).
