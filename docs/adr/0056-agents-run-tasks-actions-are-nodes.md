# 0056 — Agents run tasks, actions are nodes, no executor

- Status: accepted (stage 1 implemented; stages 2 to 4 are the accepted direction, each gets its own record when built)
- Date: 2026-11-06
- Deciders: owner
- Related: ADR 0015 (task library), 0034 (generic LLM and agent tasks), 0044 (run workspaces and sessions), 0046/0047
  (benchmarks), 0050 (model availability), 0053 (run inputs), 0055 (library groups, rules); docs/architecture/07-task-library.md

## Context

A template had an **executor** (`LLM`, `SPEC_KIT`, `IMPLEMENT`, `FETCH_ISSUE`, `NOTIFY`, `TRANSITION_ISSUE`,
`START_WORKFLOW`, `PROJECT_DIGEST`). The one field answered three unrelated questions:

1. **What thinks**: the model, chosen elsewhere (template, step, project, user and platform defaults).
2. **How and where it works**: a single model answer, or a coding agent (OpenCode or Claude Code, picked by the config
   key `agent`) in a sandbox with the repository, rules, skills and tool permissions.
3. **Whether it is AI work at all**: five values are deterministic integrations that never call a model. They lived in
   the task library next to "implement the specification", with a prompt field that meant nothing to them.

The common meaning of an *agent* (and the owner's) is a conversation with a model opened in a prepared place: a model,
a harness that runs the loop (tools, context, subagents, questions) and an environment (directory, repository, rules,
skills, permissions). The agent performs tasks. Back Office had no agent: a step was a task, and each coding step was a
fresh session.

What Back Office is for decides how far to go: find cheaper models and workflows that produce accepted work, by running
the same task with different models, harnesses and workflows and measuring quality and cost. Claude Code alone does a
single session better than any wrapper, so Back Office must not re-implement a harness. It must make the harness and the
model **variables**, give every combination the same input, and measure the same output.

## Decision

### Stage 1 (this change)

1. **The executor is removed** from templates (domain, database, API, UI). A template describes the work: prompt,
   rules, skills, configuration, outputs, run inputs, category.
2. **Agent** is a new library entry in the **Tasks** group (templates, skills, rules, agents), global or project scope,
   built in or not, same permissions and audit as rules. An agent is a **harness** and optionally a **model**:
   - harness empty: **model answer**, one model call without tools or repository (the former `LLM`);
   - `OPENCODE` or `CLAUDE_CODE`: a **coding agent** session in the runner sandbox with the step's workspace.
   No model means the defaults for the step kind, as before (project, then the starting user, then the platform).
   Built-in agents: `model-answer` (Model answer), `opencode` (OpenCode), `claude-code` (Claude Code). Keys are derived
   from the name like other entries (ADR 0055).
3. **Who runs a step**: the step's agent (`overrides.agentKey`), else the template's agent (`agentKey`, inherited, child
   wins), else `model-answer`. **Which model**: the step's model, else the agent's, else the template's, then the
   defaults (`ModelResolver`).
4. **How it runs** follows from the agent and the template:
   - model answer: the in-process model call (structured output, confidence, injection guard as before);
   - coding agent and a template with the config key `phase` (Spec Kit): the Spec Kit job;
   - coding agent otherwise: the agent job (former `IMPLEMENT`): push and merge request per config; without a push its
     final answer becomes the `result` artefact.
   The resolver reports a problem (the step cannot run) when a template needs a coding agent (Spec Kit `phase`,
   `push: true`, `prepareOnly`) but gets a model answer, or has an `outputSchema` (structured answer) but gets a coding
   agent (stage 2 lifts this).
5. **Actions are nodes, not tasks.** Fetching an issue, commenting on it, transitioning it, starting another workflow and
   writing the project digest leave the task library and become the node kind `ACTION` with `action.type`
   (`FETCH_ISSUE`, `COMMENT_ISSUE`, `TRANSITION_ISSUE`, `START_WORKFLOW`, `PROJECT_DIGEST`) and `action.config`. The
   palette lists them in their own group with their configuration schema, defaults, run inputs, ports
   (`success`/`failure`) and connection capabilities. Their engine code is unchanged.
6. **Compatibility.** Stored workflows and run snapshots are read as before: a `TASK` node naming a former action
   template (`fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow`, `project-digest`) is read as an `ACTION`
   node with the former template defaults and the step's `config` (and `promptReplace` as the comment message); the
   step's config key `agent` is read as `agentKey` (`CLAUDE_CODE` to `claude-code`, `OPENCODE` to `opencode`). The next
   save writes the new form. V304 creates the `agent` table, sets `agent_key` of templates from their executor and
   config `agent`, removes the key `agent` from template config, deletes the five built-in action templates and drops
   `executor`.

### Accepted direction (later stages)

- **Stage 2, session and environment.** A step chooses its session: new, or continue the session of an earlier step
  (ADR 0044 roles, shown as such in the designer). A project's **environment** (repositories, rules, skills, MCP servers,
  tool permissions) is rendered by the runner into each harness's own form (`AGENTS.md` / `CLAUDE.md` and skill
  directories, excluded from commits) instead of the prompt, so a continued session does not get the rules twice.
  Coding agents may answer with an output schema.
- **Stage 3, templates with parameters.** Typed parameters with defaults instead of deep `extends` chains: a template is
  a prompt with declared parameters, a ready building block is a template whose parameters all have values, a preset is
  a template plus values.
- **Stage 4, evaluation matrix.** The benchmark dimension becomes the agent (harness and model) next to the workflow, on
  the same task with the same verification, and the headline metric is the **cost of an accepted result** (retries,
  verification and human time included). A single long Claude Code step is the reference point.

## Alternatives considered

- **Keep the executor, hide it.** It still mixes the three questions and blocks the agent concept. Rejected.
- **Unify the way of working across providers** (one home-grown agent loop for all models). Loses what the best
  harnesses do (context, subagents, questions) and repeats their work. Rejected: unify the input and the measurement,
  not the way of working.
- **Keep actions as templates without a prompt.** They would stay in the library people browse for work to give an
  agent, and keep the executor alive. Rejected.

## Consequences

- OpenAPI: `TaskExecutor`, `TaskExecutorDescriptor` and `/workflows/task-executors` are gone;
  `/workflows/task-config-schemas` gives the config schema of model answer and coding agent steps; `/agents` is new;
  templates have `agentKey`, steps `overrides.agentKey`, `ResolvedTask` and `PaletteTask` an `agent`; `NodeKind` has
  `ACTION`, nodes an `action` section, the palette `actions`.
- `implement-claude-code` (deprecated) is `implement-opencode` with the agent Claude Code.
- The MCP library search and palette return the agent instead of the executor.
