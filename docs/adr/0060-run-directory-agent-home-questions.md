# 0060 — One run directory, an agent home, agent access, agents ask questions

- Status: accepted
- Date: 2026-11-11
- Deciders: owner
- Related: ADR 0031 (git access), 0044 (run workspaces and sessions; replaced here), 0052 (side session), 0056-0059
  (agents, environment, parameters, evaluation)

## Context

After ADR 0057 the word "environment" meant three things: the **place** (a directory with the repositories and what
earlier steps left there), the **project's rules** (`AGENTS.md` / `CLAUDE.md` in the repository) and the **agent's
environment** (instructions, rules, skills, MCP, sessions). Run workspaces (ADR 0044) were named places drawn on the
canvas and defined per project, with session roles. Three problems:

- Agents of one workspace saw each other's skills (skills were written into the repository's `.claude/skills`) and
  shared one Claude Code configuration directory.
- Naming, drawing and assigning workspaces is ceremony: what people need is "the steps of a run continue each other's
  work", setup once, separate sessions.
- What an agent may do (only read, write documentation only) had no home; a separate directory was the only isolation.

The market agrees on the split: one environment per task (repository plus setup: Codex, Copilot coding agent, Devin);
an agent is a profile loaded into its own session (Copilot custom agents, Claude Code subagents and skills); separate
directories (git worktrees) serve parallel work, not roles. And agents that cannot finish should ask, not guess: Claude
does it on its own, cheaper models rarely do.

## Decision

### 1. One run directory instead of run workspaces

Every run has one **run directory**: the coding steps of the run work in it one after another, see what earlier steps
left (uncommitted work included) and keep their sessions there. The workflow may set a **setup command**
(`WorkflowDefinition.setupCommand`), run once per run directory before the first coding step (again when it changes).
A step chooses its directory with config `directory`:

- `RUN` (default): the run directory;
- `SEPARATE`: a fresh directory with only the step's own repositories, thrown away after the step (no session
  continuation). For isolation (an agent that must not see the code) and for future parallel branches.

Project workspaces, their API and tab, the workspace frames on the canvas and the step keys `workspace`,
`sessionRole` are removed. The workflow engine runs one node at a time, so the run directory has one writer at a time.
The run directory is removed when the run ends (cancelled, failed, succeeded).

### 2. Sessions

Config `session` of a coding step: `OWN` (default: repeated executions of the step, e.g. rework loops, continue the
step's own session), `NEW` (every execution starts fresh) or `CONTINUE` with `continueSession: <node id>` (share the
session of an earlier step; ADR 0057). A shared session needs the **same agent** (its home holds the session). A
`SEPARATE` step always starts a new session.

### 3. The agent home

Each agent of a run gets a **home** inside the run directory, outside every repository
(`/workspace/.agents/<agent key>/`): its environment document, its skills, its MCP configuration and its sessions.
Claude Code: the home is `CLAUDE_CONFIG_DIR` (`CLAUDE.md` user memory, `skills/`, sessions). OpenCode: its global
config directory (`AGENTS.md` listed in `opencode.json`, `skills/`) and its data directory (sessions). Nothing of an
agent is written into a repository any more; agents in one run directory never see each other's skills or memory.
The repository's own `AGENTS.md` / `CLAUDE.md` remain the project's rules for everyone.

### 4. The environment is set once

An agent's environment is defined when the agent is created or edited, not per step: the step keys `addSkills` /
`removeSkills` are removed (stored steps read without them). A template may declare `requiresSkills`; a step whose
agent lacks one gets a **warning** (not a blocking problem).

### 5. Agent access

An agent has an **access** mode: `FULL` (default), `READ_ONLY`, or `PATHS` with path globs it may change (e.g.
`docs/**`). Enforced twice:

1. the harness's own tool rules (Claude Code deny rules for editing tools, OpenCode `permission.edit`), so the agent
   learns early;
2. the runner after the agent finished: every change in the repositories of the step outside what the agent may change
   is reverted to the state before the step and listed in the step's log and result. This holds whatever the agent did
   (shell included).

A `READ_ONLY` agent on a step that pushes is a resolution problem. Whether a step commits and pushes stays a property of
the step (ADR 0031); whether the agent sees the code at all is the step's `directory` and repositories.

### 6. Agents ask questions

When an agent cannot do the step well without the owner's answers, it stops with questions instead of guessing:

- a coding agent writes `agent-questions.json` (`{"questions": [{"question", "why"?}]}`) in its working directory
  (collected, never committed);
- a model answer replies with `{"agentQuestions": [...]}` instead of its normal answer.

The run then waits on an approval of kind `AGENT_QUESTIONS` (same approvers as the run's gates: the starter and the
accountable role), showing the questions. Answering (`answer`, with the answers as text) re-runs the step: the answers
are added to its prompt (`{{answers}}`, section "Answers of the owner") and a coding agent continues its own session.
`cancel` fails the step. At most `maxQuestionRounds` rounds per step (config, default 2); after that the agent must
finish with what it has. The platform preamble tells every agent how to ask; config `allowQuestions: false` turns it
off for a step (CI-triggered runs keep it on: they wait like at a gate).

### 7. Agents in a project

Projects get a tab **Agents**: the agents a project can use (global and its own), create a project agent, or import
one from the library (a project copy that can then be changed; it shadows the global agent of the same key, like
template customisation).

### 8. Compatibility

- Stored steps: `workspace` is ignored (the run directory); steps sharing a `sessionRole` read as one session chain
  (the first such step `OWN`, the others `CONTINUE` it) when they have the same agent, else `OWN`; `prepareOnly` steps
  (the deprecated `prepare-workspace`) prepare the run directory.
- A project workspace's `setupCommand` becomes the setup command of the workflows whose steps named it (migration);
  its sources become the `repositories` of those steps that had none of their own.

## Alternatives considered

- **Keep named workspaces as an option.** Two concepts for "where" again; `SEPARATE` covers the isolation case.
  Rejected.
- **A directory per agent.** Isolation without benefit: roles need separate sessions and profiles, not separate copies
  of the code. Rejected.
- **Access by tool rules only.** An agent with a shell can bypass them. Rejected; the runner's revert is the guarantee,
  the rules are the guidance.
- **Questions only for Claude Code (its own question tool).** Ties the feature to one harness; the file / JSON contract
  works for every agent. Rejected.

## Consequences

- OpenAPI: `WorkflowDefinitionInput.setupCommand`; step config `directory`, `session`, `allowQuestions`,
  `maxQuestionRounds`; `AgentInput.access`; `TaskTemplateInput.requiresSkills`; `ResolvedTask.check.warnings`;
  `ApprovalRequest.kind` `AGENT_QUESTIONS` with `questions`; `RunnerJobSpec.agentHome`, `access`; project workspace
  endpoints and `TaskOverrides.addSkills` / `removeSkills` removed.
- The `run` workspace name of ADR 0057 becomes the only run directory; all coding steps of a run go to the runner that
  holds it (ADR 0044 leasing).
