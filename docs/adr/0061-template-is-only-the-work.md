# 0061 — The template is only the work; agent audit in the workflow

- Status: accepted
- Date: 2026-11-12
- Deciders: owner
- Related: ADR 0056-0060 (agents, environment, parameters, evaluation, run directory)

## Context

After ADR 0060 a task template still chose an agent and a model profile and showed the agent's environment. Both
belong to the step: the same work is done by different agents (that is what evaluation compares), and the model is the
agent's or the step's. Templates kept one thing that is really theirs: the skills the work needs (`requiresSkills`).
Rules stay out of templates: they are norms of behaviour (project, repository, agent); what one task demands is part
of its prompt. A fourth source of norms would make contradictions likely.

Contradictions still happen between the sources an agent gets (constitution, repository `AGENTS.md` / `CLAUDE.md`,
project and agent rules, skills, the task). Nobody saw them before a run.

## Decision

1. **Template = the work.** `agentKey` and `modelProfileId` leave templates. A template states what kind of worker it
   needs, derived from its config: a coding agent (`codingAgent: true` for repository work without a push, Spec Kit
   `phase`, `push`, `prepareOnly`) or any agent
   (`needsCodingAgent`, read-only). Its `requiresSkills` stay.
2. **The step chooses the agent.** The designer pre-selects one when a template is dropped: an agent of the right kind
   that has the most of the required skills (ties: built-in first, then name). A stored step without an agent runs with
   `opencode` when the template needs a coding agent, else `model-answer`. Model: the step's, else the agent's, else
   the defaults.
3. **Agent audit in the workflow.** `POST /workflows/agent-audit` (the designer's draft) returns, per agent the workflow
   uses: its rules and skills, the skills its steps require and which are missing, and deterministic findings
   (missing required skill, a step that needs a coding agent with a model answer agent, a read-only agent on a pushing
   step, a rule or skill that no longer exists, two skills with the same name). The designer shows it as a panel
   "Agents in this workflow" with "Add to agent" for missing skills: it changes the agent once, before any run (a
   global agent is copied into the project first, so other projects keep theirs).
4. **Coherence check on demand.** `POST /agents/{id}/coherence-check` (optionally for a project) asks a model (the
   platform default for model answers, booked as usage) to read the agent's whole environment (platform preamble,
   instructions, constitution, project and agent rules, skill names and descriptions, and with a project the primary
   repository's `AGENTS.md` / `CLAUDE.md` when the files are readable) and to list contradictions with quotes. Only on
   request, never before a run.
5. **Precedence.** The platform preamble states the order when sources disagree: the project constitution, then the
   rules (repository, project, agent), then the skills, then the task. When the agent cannot resolve a contradiction it
   asks (ADR 0060) instead of guessing.
6. **Migration.** Steps of stored workflows and library versions that had no agent of their own get the agent their
   template lineage named (and the template's model when the step had none); then the two template columns are dropped.
7. **Less text on screen.** Field descriptions move behind a "?" next to the label (tooltip); warnings, errors and
   consequences people must read before acting stay visible.

## Alternatives considered

- **Rules on templates.** A fourth source of norms; task demands belong in the prompt. Rejected.
- **Inject required skills at run time.** The agent's environment would differ per step and evaluation would no longer
  compare one agent. Rejected: the audit adds them to the agent, visibly, before a run.
- **Automatic coherence check before each run.** Costs tokens on every run for a rarely changing environment. Rejected.

## Consequences

- OpenAPI: `TaskTemplateInput.agentKey` and `.modelProfileId` removed; `TaskTemplate.needsCodingAgent`,
  `PaletteTask.needsCodingAgent` and `.requiresSkills` (the palette's `agent` removed); `/workflows/agent-audit`;
  `/agents/{agentId}/coherence-check`. The model plan source `TEMPLATE` is no longer produced.
