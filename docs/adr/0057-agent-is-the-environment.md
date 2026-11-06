# 0057 — The agent is the environment; sessions continue; coding agents answer in a schema

- Status: accepted
- Date: 2026-11-06
- Deciders: owner
- Related: ADR 0044 (run workspaces and sessions), 0050 (model availability, PROMPT jobs), 0051 (agent tools), 0055
  (rules), 0056 (agents, stage 1; this is its stage 2), 0058 (template parameters)

## Context

Stage 1 (ADR 0056) made the agent a library entry, but only a harness and a model. The owner asked what agents are for:
"an agent should have its skills and rules assigned; then what is a task template?". The question is right. Rules and
skills sat on templates, so the agent was an empty shell, the template mixed *what to do* with *how to work*, and the
same rules travelled in every prompt (twice in a continued session).

Every comparable tool splits the two: CrewAI `Agent(role, tools, llm)` + `Task(description, expected_output)`, Claude
Code subagents (prompt, tools, model, skills), OpenCode agents (model, prompt, tools). The evaluation Back Office exists
for needs the split too: the agent is the **variable** (same task, different agents), so it must be a named, complete
unit.

## Decision

### 1. An agent is who works and how: harness, model, instructions, rules, skills, MCP servers

| | **Agent: who and how** | **Task template: what** |
|---|---|---|
| Holds | harness (none = model answer, OpenCode, Claude Code), model (optional), **instructions** (role, markdown, may use `{{project.*}}`), **rules**, **skills**, **MCP servers** | prompt with parameters (ADR 0058), run inputs, output schema, produces/consumes, config, category |
| Goes to | the session's environment | the session's prompt |
| Changes when | you look for a cheaper or better way to work | the work changes |

A **step** is a template, an agent (the step's, else the template's, else `model-answer`) and parameter values. A step
may still add or remove skills for itself (`addSkills` / `removeSkills`).

Templates lose `skills`, `removeSkills`, `rules` and `removeRules`. Rule and skill deletion is refused while an agent
uses them. Automatic rules (ADR 0055) still apply to every step of their scope.

### 2. The environment is rendered once, in each harness's own form

Back Office renders one **environment document** per step:

1. the platform preamble for the kind of agent (fixed in code: what Back Office is, honesty, scope, safety; for model
   answers "no repository, no tools, JSON only with a schema"; for coding agents "the runner commits and pushes, never
   weaken a test, quote file paths, where your skills are"),
2. the agent's instructions,
3. the project constitution (always; templates no longer place it),
4. the rules: automatic (global, then project), then the agent's.

Where it goes:

- **Model answer**: the system prompt is the environment document, then the template's system prompt, then the skills.
  PROMPT jobs (ADR 0050, Claude Code login) get it the same way.
- **OpenCode**: the runner writes it to `/workspace/.backoffice-agent/AGENTS.md` (outside every repository) and lists
  it under `instructions` of the generated `opencode.json`.
- **Claude Code**: the runner writes it as the user memory `$CLAUDE_CONFIG_DIR/CLAUDE.md` (outside every repository).

The repository's own `AGENTS.md` / `CLAUDE.md` stay untouched and are read by the harness as usual (project memory).
Skills keep their place (`.claude/skills`, `.opencode/skills`, git-excluded). The prompt of a coding agent no longer
carries the rules, so a continued session does not receive them again. `RunnerJobSpec.instructions` carries the
document (covered by the spec digest).

### 3. MCP servers of an agent

An agent may list remote MCP servers (`name`, `url`, HTTP(S) only, no credentials). The runner configures them for the
harness (Claude Code `--mcp-config`, OpenCode `mcp` with `type: remote`). Reachability is not Back Office's call: the
sandbox's egress policy (runner controller, ADR 0024) decides, and a host it does not allow is refused by the proxy.
Credentials for MCP servers are not supported yet; they need sealed delivery like repository tokens (ADR 0051) and get
their own record.

### 4. A step continues the session of an earlier step

Config `continueSession: <node id>` on a coding agent step continues the agent session of that earlier step: both run
in the same run workspace (the earlier step's `workspace`, or the implicit workspace `run`, which every project has and
which needs no definition) under the same session role (the earlier step's `sessionRole`, or one derived from its node
id). The earlier step gets the same workspace and role automatically. ADR 0044 mechanics do the rest (Claude Code
`--resume`, OpenCode `--continue`). Problems block the run: the named step does not exist, is not a coding agent step,
or uses a different harness (a Claude Code session cannot continue in OpenCode). The designer offers "new session" or
"continue the session of step …".

### 5. Coding agents may answer in a schema

The stage 1 restriction is lifted. A coding agent step with an `outputSchema` is told to write its answer as one JSON
object to `structured-answer.json` in its working directory; the runner collects it (artefact `structured-answer`) and
keeps it out of the commit; Back Office validates it like a model answer (same schema check, confidence field, produced
artefacts). A missing or invalid answer fails the step with the reasons.

### 6. Built-in agents

| Key | Name | Harness | Skills |
|---|---|---|---|
| `model-answer` | Model answer | none | none |
| `analyst` | Analyst | none | user story, use case, ADR format, definition of ready/done, build guardrails |
| `reviewer` | Reviewer | none | security review checklist, definition of ready/done |
| `opencode` | OpenCode | OpenCode | spec writing, definition of ready/done, build guardrails |
| `claude-code` | Claude Code | Claude Code | spec writing, definition of ready/done, build guardrails |

All carry the rules `agent-ground-rules` and `ask-dont-guess`. The SDLC and review templates name `analyst` or
`reviewer`; the abstract `base-agent`, `base-llm` and `base-coding-agent` are emptied and deprecated (their text is the
platform preamble now), and the built-in templates no longer extend them (ADR 0058).

### 7. Migration

V305 adds `instructions`, `rules`, `skills` and `mcp_servers` to `agent`. A template that is not a pristine built-in and
has its own skills or rules gets an agent of its own (`<key>-agent`, same scope, the harness of its current agent, the
ground rules plus its rules and skills) and names it; then the four columns leave `task_template`. Pristine built-ins are
reshaped by the seed. Library imports ignore template skills and rules with a warning.

## Alternatives considered

- **Keep rules and skills on templates and add them to agents too (union).** Two places for the same thing, and the
  evaluation question "which agent" stays muddy. Rejected.
- **Write `AGENTS.md` / `CLAUDE.md` into the repository** (git-excluded). Collides with the repository's own files and
  their history. Rejected: user-level files outside the repository are what the harnesses offer for this.
- **Editable preamble as a built-in rule.** The preamble states platform facts (the runner pushes, JSON only with a
  schema), not norms. Rejected; norms remain rules.

## Consequences

- OpenAPI: `AgentInput` has `instructions`, `rules`, `skills`, `mcpServers`; `TaskTemplateInput` loses `skills`,
  `removeSkills`, `rules`, `removeRules`; `RunnerJobSpec` has `instructions` and `mcpServers`; the model plan source
  has `AGENT`.
- A template whose prompt still contains the constitution placeholder shows the constitution twice; the built-ins are
  cleaned up.
- Template-level `removeRules` (drop an automatic rule for one task) is gone; an automatic rule applies to every step.
