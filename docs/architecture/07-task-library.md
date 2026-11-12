# 07 — Task Library

The Task Library is a bounded context (`tasks-core`, `tasks-adapters`) that owns what agents do and
how they are instructed, independent of any single workflow (ADR 0015). Projects use it through the
`TaskCatalog` port; benchmarks may reference templates in a later iteration.

## Concepts

| Concept | Analogy | Content |
|---|---|---|
| `TaskTemplate` | class (what to do) | `key`, `name`, `description`, `abstract`, `extendsKey`, `category`, `scope` (`GLOBAL` or `PROJECT(projectId)`), `instructions` and `instructionsMode` (`APPEND|REPLACE` over the parent, ADR 0062), `parameters[]` and `parameterValues{}` (ADR 0058), `config{}`, `outputSchema`, `confidenceField`, `produces[]`, `consumes[]`, `tags[]`, `requiresSkills[]` (ADR 0060), `builtIn`, `version`; read-only `needsCodingAgent` (ADR 0061); no skills or rules since ADR 0057, no `agentKey` and no `modelProfileId` since ADR 0061; no system/user prompt, no `runInputs` and no `sandboxPolicy` since ADR 0062 |
| `TemplateParameter` | typed slot | `name` (`^[A-Za-z][A-Za-z0-9_]{0,63}$`), `label`, `type` `TEXT|MULTILINE|NUMBER|BOOLEAN|CHOICE|ISSUE_KEY` (+ `options`), `required`, `default`, `help`; read by the instructions and text config as `{{params.<name>}}` (ADR 0058); a step binds it (ADR 0062, below) |
| `TemplateCategory` | grouping | `ANALYSIS`, `SPECIFICATION`, `PLANNING`, `IMPLEMENTATION`, `REVIEW`, `JIRA`, `OPERATIONS`, `GENERAL`; shared kernel; groups the library list, the designer palette and MCP results (ADR 0055) |
| `ParameterBinding` | wiring | per parameter name in `TaskOverrides.parameterBindings`: `source` `VALUE` (with `value`), `START` (asked in the run start form) or `ARTIFACT` (with `artifact`, the name of an earlier step's artefact); shared kernel (ADR 0062) |
| `RunInputField` | form field | `name` (key in the run parameters), `label`, `type` `TEXT|MULTILINE|ISSUE_KEY`, `required`, `help`; shared kernel; since ADR 0062 derived from the parameters a step binds `START` (`ResolvedTask.runInputs`) and declared by actions |
| `Skill` | handbook chapter | `key`, `name`, `description`, `content` (markdown, Agent Skills `SKILL.md` convention), `scope`, `tags`, `builtIn`; knowledge or a procedure the agent opens when the task needs it |
| `Rule` | standing order | `key`, `name`, `description`, `content` (markdown, no frontmatter), `scope`, `automatic`, `tags`, `builtIn`; a norm that is always in the agent's context (ADR 0055) |
| `Agent` | worker (who and how) | `key`, `name`, `description`, `scope`, `harness` (`OPENCODE`, `CLAUDE_CODE` or none = model answer), `modelProfileId`, `instructions` (markdown, may use `{{project.*}}`), `rules[]`, `skills[]`, `mcpServers[] {name, url}`, `access {mode, paths, directory}` (ADR 0060, 0062), `session` (`PER_STEP|PER_RUN|FRESH`, ADR 0062), `tags`, `builtIn` (ADR 0056, 0057); `AgentSnapshot` in the shared kernel carries it to the Projects context |
| `ResolvedTask` | instance | the merged result the workflow engine runs: the merged `instructions`, the resolved `agent` (with its instructions, MCP servers, access and session), the step's `rules` and `skills`, and `parameters` (definition, value, source `STEP`/`TEMPLATE`/`DEFAULT`/`NONE`, the step's binding and the fallback) |

Until ADR 0056 a template had an **executor** (`LLM`, `SPEC_KIT`, `IMPLEMENT`, `FETCH_ISSUE`, `NOTIFY`,
`TRANSITION_ISSUE`, `START_WORKFLOW`, `PROJECT_DIGEST`) that mixed what thinks, how it works and whether it is AI work
at all. It was removed: the template describes the work, the **agent** says who does it, and the five deterministic
integrations are workflow nodes of kind `ACTION` in the Projects context
([03-pipelines-and-agents.md](03-pipelines-and-agents.md)), not library entries. `V304` creates the `agent` table,
sets `agent_key` of templates from their executor and config key `agent`, removes the key `agent` from template
config, deletes the five built-in action templates and drops `executor`. ADR 0061 then took the agent and the model
off the template altogether (see [The template is only the work](#the-template-is-only-the-work-adr-0061)).

### The template is only the work (ADR 0061)

A template states the work and nothing about the worker except two derived or declared facts:

- `needsCodingAgent` (read-only, on `TaskTemplate`, `PaletteTask` and the resolution): the merged config of the
  lineage has a Spec Kit `phase`, `push: true` or `prepareOnly: true` (`TaskResolver.needsCodingAgent`). The UI shows
  the tag "Needs a coding agent" and filters the template list by it (`coding` / `any`).
- `requiresSkills` (declared, unioned over the lineage): the skills the work needs. They guide the designer's
  pre-selection and produce warnings; they are never injected at run time, so an agent's environment is the same in
  every step and comparable in evaluation.

Rules stay off templates on purpose: they are norms of behaviour (repository, project, agent); what one task demands
belongs in its instructions. A fourth source of norms would make contradictions likely.

The **step chooses the agent** (`overrides.agentKey`) and optionally the model (`overrides.modelProfileId`). The
designer pre-selects an agent when a template is dropped or chosen for a step without one (or with one of the wrong
kind; `agent-choice.ts`): candidates of the right kind (a coding agent when `needsCodingAgent`, else any agent with
model answer agents first), the most covered `requiresSkills`, then the platform default of that kind (`opencode` /
`model-answer`), then built-in, then by name; a project agent shadows the global one with the same key. A stored step
without an agent resolves to `opencode` when the work needs a coding agent, else `model-answer` (shown as "Default:
{agent}"). `V307__template_is_only_the_work.sql` wrote the former template agent and model into the steps (see
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#step-repositories), "Steps stored before ADR 0061") and dropped `task_template.agent_key` and
`task_template.model_profile_id`; the `TEMPLATE` source of the model plan is no longer produced.

`TaskCatalog` gained `stepNeeds(key, projectId, overrides)` (resolved agent key, required skills with names,
`needsCodingAgent`, `pushes`) and `agentProfile(key, projectId)` / `agentProfileById(id, projectId)` (the agent's
environment: rules, skills with descriptions, unknown rules and skills) for the agent audit and the coherence check
in the Projects context ([03-pipelines-and-agents.md](03-pipelines-and-agents.md#how-task-and-action-nodes-run)).

Until ADR 0055 the library also had task interfaces (`implements`, inputs, outputs, ports). No workflow ever
referred to one: they only re-checked `consumes`/`produces`, never added a port and labelled templates in the
palette. They were removed (V303 drops the `task_interface` table); the grouping they gave is now the template's
`category`.

### Keys (ADR 0055)

Keys match `^[a-z][a-z0-9-]{1,63}$` and are what workflows, MCP and the API refer to; people do not type them.
On create a template, skill, rule or agent without a key gets one from its name (`Keys.fromName`: diacritics dropped,
everything but letters and digits becomes `-`, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit
or yields nothing) made unique by `Keys.unique` (`-2`, `-3`, ...). Template keys are unique across scopes, so a new
template never shadows anything by accident; skills and rules are unique by `(key, scope)`. An update without a key
keeps it, so renaming changes the name only; the key and scope of an entry other templates use (extend, list as a
skill or rule) cannot change. An explicit key on create is still accepted: a `PROJECT` template with a global
template's key and the same `extendsKey` customises that template for the project (UI: **Customize for project**).

### Rules (ADR 0055, 0057)

A rule applies to the steps whose agent lists it in `rules` or, when `automatic`, to every step of its scope: a
global automatic rule to every step of every project, a project one to every step of that project. Nothing can drop
an automatic rule for one task (template-level `removeRules` is gone with ADR 0057). A `PROJECT` rule with a global
rule's key replaces it for that project; a non-automatic project copy of a global automatic rule switches it off for
that project. Rules are part of the agent's **environment document** (see [Agents](#agents-adr-0056-0057)), not of
the task prompt: a model answer gets them in the system prompt, a coding agent in its `AGENTS.md` / user `CLAUDE.md`.
The former `RulesPrompt` (a `<rules>` block at the start of a coding prompt) was removed, so a continued session does
not receive the rules twice. Skills, by contrast, reach runner steps as `.claude/skills/<key>/SKILL.md` and
`.opencode/skills/<key>/SKILL.md`, which the agent opens on demand.

### Agents (ADR 0056, 0057)

| | **Agent: who and how** | **Task template: what** |
|---|---|---|
| Holds | harness, model (optional), instructions, rules, skills, MCP servers, access (ADR 0060) | instructions with parameters, output schema, produces/consumes, config, category, required skills (ADR 0060) |
| Goes to | the session's environment | the session's prompt |
| Changes when | you look for a cheaper or better way to work | the work changes |

A **step** is a template, an agent (the step's, else `opencode` when the work needs a coding agent, else
`model-answer`; ADR 0061) and parameter values. The
agent's environment is set once, on the agent (ADR 0060): a step no longer adds or removes skills (the former
`overrides.addSkills` / `removeSkills` are ignored when stored steps are read). A template may declare
`requiresSkills`; a step whose agent lacks one gets a warning in `check.warnings`, not a problem. The split follows CrewAI
(`Agent` + `Task`), Claude Code subagents and OpenCode agents, and it is what evaluation needs: the agent is the
variable compared on the same task, so it must be a named, complete unit (ADR 0057, 0059). An agent is a library
entry in the **Tasks** group, global or project scope, built in or not, with the permissions, audit and project
shadowing of rules.

- harness empty: **model answer**, one in-process model call without tools or repository (the former `LLM`);
- `OPENCODE` or `CLAUDE_CODE`: a **coding agent** session in the runner sandbox, in the run's run directory (or a
  separate one, ADR 0060).

**Session and directory** (ADR 0062, coding agents; until then the step settings `session` / `continueSession` /
`directory` of ADR 0060): a session is the agent's conversation and "does not see the run directory" is part of what
an agent may access, so both are the agent's.

| Field | Values (UI) | Meaning |
|---|---|---|
| `session` | `PER_STEP` "Per step (repeats continue)" (default), `PER_RUN` "One per run", `FRESH` "Always fresh" | which executions continue one conversation: the repeats of one step (rework, answered questions); every step of this agent in a run; none |
| `access.directory` | `RUN` "Run directory (shared)" (default), `SEPARATE` "Separate directory" | the run directory every step shares, or a fresh directory with only the step's repositories, thrown away after the step (no session continuation) |

The former "continue the session of step ..." becomes an agent with `PER_RUN`: its steps in a run share one
conversation. A legacy step `sessionRole` (ADR 0044) keeps sharing a session while the agent keeps `PER_STEP`.

**Access** (ADR 0060, coding agents): `FULL` (default), `READ_ONLY`, or `PATHS` with globs relative to a repository
root (at most 50, no leading `/`, no `..`). Enforced twice: by the harness's tool rules (Claude Code without editing
tools for other paths, OpenCode `permission.edit`), and by the runner, which snapshots the step's repositories before
the agent and reverts every change outside the access afterwards, whatever the agent did (`result.access.reverted`,
log and summary). A `READ_ONLY` agent on a step that pushes is a resolution problem. Access narrows what an agent may
change, not what it sees: hiding code is an agent with `access.directory: SEPARATE` and the step's repositories.

Built-in agents (no model of their own; all list `agent-ground-rules` and `ask-dont-guess`):

| Key | Name | Harness | Skills | Instructions |
|---|---|---|---|---|
| `model-answer` | Model answer | none | none | none |
| `analyst` | Analyst | none | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | analyst role |
| `reviewer` | Reviewer | none | `security-review-checklist`, `definition-of-ready-done` | reviewer role |
| `opencode` | OpenCode | `OPENCODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | none |
| `claude-code` | Claude Code | `CLAUDE_CODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | none |

Deleting a rule or skill an agent lists is refused (`InvalidStateException`). Deleting an agent is not blocked by
anything since ADR 0061 (templates name none); workflow steps that still name it are reported as `UNKNOWN_AGENT` by the
agent audit and cannot run.

**The environment document** (`AgentEnvironment`, Projects context) is rendered once per step:

1. the platform preamble for the kind of agent (fixed in code; platform facts, not norms): what Back Office is,
   honesty, scope, safety; for model answers "no repository, no tools, JSON only with a schema"; for coding agents
   "the runner commits and pushes, never weaken a test, quote file paths, where your skills are" (the agent's home);
   when the step allows questions (ADR 0060), how to ask (`agentQuestions` / `agent-questions.json`), otherwise that
   the agent cannot ask and records open questions in its output;
2. the agent's instructions, rendered with the step's template model (`{{project.*}}`; untrusted values become data
   blocks) under "Your role";
3. the project constitution, when the project has one (templates no longer place it);
4. the rules: automatic (global, then project), then the agent's.

The preamble ends with the precedence of sources (ADR 0061, `AgentEnvironmentText.PRECEDENCE`): the project
constitution, then the rules (repository `AGENTS.md` / `CLAUDE.md`, project, agent), then the skills, then the task;
a contradiction the agent cannot resolve is asked (when the step allows questions) instead of guessed.

| Agent | Where the document goes | Skills |
|---|---|---|
| model answer | system prompt = document + `<skills>` + data-block notice (the template has no system part since ADR 0062; the gateway adds the answer schema); PROMPT jobs (ADR 0050) get it as `systemPrompt` | in the system prompt |
| OpenCode | runner writes `<home>/AGENTS.md` and lists it under `instructions` of the home's `opencode.json` (`OPENCODE_CONFIG_DIR` = the home) | `<home>/skills/<key>/SKILL.md` |
| Claude Code | runner writes the user memory `<home>/CLAUDE.md` (`CLAUDE_CONFIG_DIR` = the home) | `<home>/skills/<key>/SKILL.md` |

**The agent home** (ADR 0060): `<home>` = `/workspace/.agents/<agent key>/` in the run directory, outside every
repository (`RunnerJobSpec.agentHome`). It holds everything of the agent: the environment document, the skills
(rewritten on every job), the MCP configuration (Claude Code `mcp.json`, OpenCode `mcp` in `opencode.json`), the
tool rules (Claude Code `settings.json`, OpenCode `permission`) and the sessions (Claude Code's own session files;
OpenCode's data directory `data/<session role>`). Agents in one run directory never see each other's skills or
memory, and nothing of an agent lands in a commit. The repository's own `AGENTS.md` / `CLAUDE.md` stay untouched and
are read by the harness as the project's rules for every agent; OpenCode also reads the repository's
`.opencode/skills` and `.claude/skills` (project skills). `RunnerJobSpec.instructions` carries the document and is
covered by the spec digest. A spec without `agentHome` (older Back Office) uses the ADR 0057 locations
(`/workspace/.backoffice-agent/`, `$CLAUDE_CONFIG_DIR`, skills in the repository's git-excluded `.claude/skills` and
`.opencode/skills`).

**MCP servers** (`mcpServers`, `{name, url}`, name `^[a-z][a-z0-9-]{0,39}$`, `http(s)` only, no credentials): the
runner configures them for coding agents (OpenCode `mcp.<name> = {type: remote, url}`; Claude Code
`<home>/mcp.json` passed with `--mcp-config`, plus `mcp__<name>` in `permissions.allow` under a
tool policy). Reachability is the sandbox egress policy's call (runner controller, ADR 0024): a host it does not
allow is refused by the proxy. Credentials for MCP servers need sealed delivery like repository tokens (ADR 0051) and
get their own record.

| Question | Answer |
|---|---|
| Who runs a step | the step's agent (`overrides.agentKey`), else `opencode` when the template needs a coding agent, else `model-answer` (ADR 0061) |
| Which model | the step's model, else the agent's, then the defaults (`ModelResolver`: project, starting user, platform) |
| How it runs | model answer: the in-process model call (structured output, confidence, injection guard); coding agent and a template with the config key `phase`: the Spec Kit job; coding agent otherwise: the agent job (former `IMPLEMENT`), push and merge request per config, without a push the final answer becomes the `result` artefact |

The resolver reports a problem in `check.problems` (the step cannot run) when a template needs a coding agent
(Spec Kit `phase`, `push: true`, `prepareOnly`) but the step's agent is a model answer. A coding agent with an `outputSchema` is
allowed since ADR 0057: it writes `structured-answer.json` (see
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#how-task-and-action-nodes-run)). The config key `agent` (`CLAUDE_CODE`/`OPENCODE`) no longer exists; a stored step's
`config.agent` is read as `overrides.agentKey` (`claude-code`, `opencode`); on a template it is dropped (ADR 0061). `GET /workflows/task-config-schemas`
returns the JSON Schema of the config keys of a model answer step and of a coding agent step (Spec Kit phase
included); it replaced `/workflows/task-executors`.

Stages of ADR 0056: stage 2 (the agent's environment, continued sessions, structured answers of coding agents) is
ADR 0057, stage 3 (typed template parameters) is ADR 0058; the agent as the benchmark dimension next to the workflow
is ADR 0059.

### Instructions (ADR 0062)

A template has one text, `instructions`: what to do and the acceptance criteria, placeholders allowed
(`{{issue.*}}`, `{{artifacts.<name>}}`, `{{params.<name>}}`, `{{feedback}}`, `{{answers}}`, `{{input.ci.*}}`, sections
`{{#path}}...{{/path}}`). `instructionsMode` (`APPEND` default, `REPLACE`) applies over the parent and matters only
for a template with `extendsKey` (presets, project customisations). There is no system prompt and no user prompt any
more: the frame (who you are, which rules) is the agent's environment document (ADR 0057), coding agents received
both fields glued into one prompt anyway, and template system prompts were left holding role sentences that duplicate
the agent plus data sections every template had to remember (`{{runInputText}}`, `{{feedback}}`, ...). The platform
composes the call (`TaskMessage`, Projects context, see
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#how-task-and-action-nodes-run)): system = the environment
document and the answer format (output schema, questions); message = the instructions, then the standard data sections
the instructions do not place themselves (the issue, the consumed artefacts, the parameter values bound at run start,
the feedback, the owner's answers; a consumed diff last). A step may add notes (`overrides.instructionsAppend`, UI
"Instructions for this step" → **Append**) or replace the text (`overrides.instructionsReplace`, **Replace
(advanced)**); the data sections are still added. The former step fields `systemPromptAppend` / `promptAppend` are
read as the append, `promptReplace` as the replacement. The built-in templates were rewritten without role sentences
(the agents carry the roles) and without the data sections the platform adds.

### Parameters (ADR 0058, 0062)

A template declares `parameters` (inherited, child wins by name) and may set `parameterValues` for the parameters of
its lineage. A step binds each parameter (`overrides.parameterBindings`, ADR 0062): `VALUE` (a fixed value; the former
`overrides.parameters`, still read as `VALUE` bindings), `START` (asked in the run start form; label, type, required
and help come from the parameter) or `ARTIFACT` (the content of an artefact of an earlier step, by name). A `VALUE`
wins, else the nearest template's value (a preset), else the default; `START` and `ARTIFACT` leave the value to the
run, with the preset value or the default as the fallback when the run has none. Unknown names, wrong types (a number
for `NUMBER`, a boolean for `BOOLEAN`, one of `options` for `CHOICE`), an `ARTIFACT` binding without an artefact
name and a required parameter that has no value and is not bound at run time are resolution problems: the step cannot
run, the preview still works. `ISSUE_KEY` (ADR 0062, UI "Jira issue key") is a parameter type for an issue key; bound
`START`, its value is the run's issue key. The template model of the instructions has `params`: values the design sets
are trusted like the instructions; values the run sets (`START`, `ARTIFACT`) are untrusted and rendered as data blocks
(ADR 0029). A template is a **ready block** when every required parameter has a value without the step;
`PaletteTask.needsParameters` marks the others. A **preset** is a template that extends another and only sets
`parameterValues` (UI: **Create preset**).

**No run inputs on templates (ADR 0062, replacing the template part of ADR 0053).** Whether a value is fixed by the
workflow designer or asked from whoever starts the run is the workflow's decision, not the template's (GitHub Actions:
an action declares `inputs`, the workflow binds them). A workflow's start form is therefore the union of its `START`
bindings and the run inputs of its action nodes (`GET /workflows/{id}/run-inputs`); the built-in templates declare the
parameter `input` ("Input material") and `spec-kit-plan` also `techHints` ("Technical hints"), which the default
workflows bind `START`.

## Resolution rules (`TaskResolver`, pure domain service)

1. Find the template by key: a `PROJECT` template for the calling project shadows a `GLOBAL` one (same for skills
   and rules).
2. Walk `extendsKey` up to the root (max depth 8, cycles are a `ValidationException`). Each ancestor is
   resolved with the same project-shadowing rule; a project template that extends its own key extends the global one.
3. Merge root → leaf:
   - `category`: nearest defined wins;
   - `outputSchema`, `confidenceField`: child wins when set;
   - `instructions`: child `APPEND` = parent + blank line + child; `REPLACE` = child only; an empty child keeps the
     parent's;
   - `parameters`: union by `name`, child wins; `parameterValues`: nearest template that sets a value wins;
   - `config`: deep merge, child keys win, `null` deletes;
   - `produces`, `consumes`, `tags`, `requiresSkills` (ADR 0060): union;
4. Apply node overrides (`TaskOverrides`): agent, model, `instructionsAppend` / `instructionsReplace`, parameter
   bindings (ADR 0062), config merge (no
   skill changes since ADR 0060); then resolve the agent (the step's, else `opencode` when the merged config needs a
   coding agent, else `model-answer`; ADR 0061) and the model (the step's, else the agent's) and check that it fits the
   template (see Agents; a `READ_ONLY` coding agent on a step that pushes is a problem) and that every required
   parameter has a value or a run-time binding.
5. Look up skills and rules from the agent: the agent's skills must exist (with project shadowing); a skill key that
   names a rule counts as that rule. The rules are the automatic ones the project sees (global, then the project's
   own, with the shadowing above), followed by the agent's rules that are not automatic anyway. A missing skill or
   rule is reported in `check.problems` and makes `resolve` throw. A skill of the lineage's `requiresSkills` the
   agent lacks is a `check.warnings` entry (ADR 0060) and does not block. Every task node has the ports `success` and
   `failure`.
6. An `abstract` leaf cannot be resolved for execution (it can be previewed).

`GET /task-templates/resolve/{key}?projectId=` returns the merged view with `lineage`, `category`, `agent`, `rules`
(automatic ones first and marked), `skills`, `instructions` (merged), `parameters`, `runInputs` (the `START` bindings) and `check`, so the owner can see what an agent will
actually receive; `POST` on the same path applies a step's overrides (ADR 0054). Without step overrides `agent` is
the default for the kind of work (`opencode` or `model-answer`, ADR 0061).

### Migration V305 (ADR 0057, 0058)

`V305__agent_environment.sql` adds `instructions`, `rules`, `skills` and `mcp_servers` to `agent`. A template that is
not a pristine built-in and has skills or rules of its own gets an agent of its own (`<key>-agent`, same scope, the
harness of its current agent, the ground rules plus its rules and skills) and names it; then `skills`,
`remove_skills`, `rules` and `remove_rules` leave `task_template`, which gets `parameters` and `parameter_values`.
Pristine built-ins are reshaped by the seed. Library imports ignore template skills and rules with a warning.

### Migration V308 (ADR 0062)

`V308__one_instruction_parameter_sources.sql` (tasks-adapters, tasks range V300-399) first rewrites the task steps
of stored workflows (`workflow_definition`), library workflow versions (`library_workflow_version`, seen from their
source project) and the definition snapshots of unfinished runs (`workflow_run` PENDING / RUNNING / WAITING), while
`task_template` still has its run inputs (the lineage walk of V307: nearest template first, a project template
shadows the global one): every run input of the step's lineage becomes a `START` binding of the parameter of that name
(unless the step binds it already), so the start forms stay the same; the step's `parameters` become `VALUE`
bindings (non-blank values only); `systemPromptAppend` and `promptAppend` become `instructionsAppend` (joined by a
blank line), `promptReplace` becomes `instructionsReplace`; the config keys `session`, `continueSession` and
`directory` are dropped (a legacy `sessionRole` stays). Then `task_template.instructions` = system and user prompt
joined by a blank line (blank ones skipped), `instructions_mode` = `prompt_mode`; every run input becomes a parameter
(`TEXT`, `MULTILINE` or `ISSUE_KEY`, label, required, help, no default; a `-` in the name becomes `_`) unless one of
that name exists; `prompt_system`, `prompt_user`, `prompt_mode` and `run_inputs` are dropped. `agent` gets
`session` (default `PER_STEP`); the directory lives in the `access` JSON (no key = `RUN`). Templates never had a
sandbox policy column (it was an API field only). Tables of other modules are touched only when they exist.

## Seeded content

Agents (five, `GLOBAL`, `builtIn`): see the table under [Agents](#agents-adr-0056-0057). Built-in agents nobody
edited are upgraded by the seed like templates.

Templates (all `GLOBAL`, `builtIn`, stamped `updatedBy = system`; twenty-one in total, the fourteen below plus the
seven SDLC templates). Since ADR 0058 they are flat: only `implement-task-list` and the deprecated
`implement-claude-code` extend `implement-opencode`; how to work (preamble, ground rules, format skills) comes from
the agent. Every concrete template has a category. Since ADR 0061 no template names an agent; the column gives the
`requiresSkills`, whether the work needs a coding agent, and the agent the default workflows choose on such a step
(the agent with those skills, so the designer pre-selects it too):

| Key | extends | category | required skills; worker |
|---|---|---|---|
| `base-agent` (abstract, deprecated) | — | — | — (emptied: its text is the platform preamble now; keeps only the parameter `input` for user templates that extend it) |
| `base-llm` (abstract, deprecated) | `base-agent` | — | — (empty) |
| `base-coding-agent` (abstract, deprecated) | `base-agent` | — | — (empty) |
| `spec-kit-specify` | — | `SPECIFICATION` | `spec-writing`; coding agent, `opencode` (config `phase` SPECIFY; instructions start with `KEY: summary`, the issue text and acceptance criteria, then the instructions) |
| `clarify-spec` | — | `SPECIFICATION` | `definition-of-ready-done`; any agent, `analyst` (schema with `confidence`, `clarifications` as markdown, `summary`) |
| `spec-kit-plan` | — | `PLANNING` | `spec-writing`; coding agent, `opencode` (PLAN; the spec is read from the repository, the message carries feedback and the optional parameter `techHints`, bound `START` in the default workflows) |
| `spec-kit-tasks` | — | `PLANNING` | `spec-writing`; coding agent, `opencode` (TASKS; the plan is read from the repository) |
| `implement-opencode` | — | `IMPLEMENTATION` | `build-guardrails`; coding agent, `opencode` (push, MR push options from `mergeRequest`, `mergeRequestTitleTemplate`, `mergeRequestDraft`; `verifyCommand` from config, none by default; on a rework the message carries the latest `review` artefact) |
| `implement-claude-code` (deprecated) | `implement-opencode` | (inherited) | (inherited); choose `claude-code` on the step (otherwise `implement-opencode`) |
| `code-review` | — | `REVIEW` | `security-review-checklist`; any agent, `reviewer` (findings schema; instructions before the data, the diff last; the issue or, in CI runs, the merge request title and description as context; one call, no chunking) |
| `analyse-issue` (deprecated) | — | `ANALYSIS` | none; any agent, `analyst` (the whole JSON answer is the `analysis` artefact) |
| `llm-task` | — | `GENERAL` | none; any agent, `model-answer` (generic task for any LLM work, ADR 0034: the task is the parameter `task` (`MULTILINE`, default "summarise the input material in at most five bullet points and list open questions"), the instructions are `{{params.task}}`; the issue, the parameter `input` (bound `START`) and the revision feedback are standard data sections; used by the starter workflow **LLM task with review** of projects without a repository) |
| `agent-task` | — | `GENERAL` | needs a coding agent through `codingAgent: true` (no push by default): `claude-code` in the reference workflow (generic coding-agent task: the task is the parameter `task` (default "describe the repository"); issue, parameter `input` and feedback are standard data sections of the message; `push` false by default, true for a branch and merge request; `repositoryOptional`: in a project without repositories an empty directory, the final answer becomes the `result` artefact) |
| `prepare-workspace` | — | `OPERATIONS` | none; coding agent (`prepareOnly`), `opencode` (ADR 0044, deprecated by ADR 0060: `prepareOnly`: clones the step's repositories into the run directory and runs the setup; starts no agent, never pushes; not needed, the first coding step prepares the run directory) |

Steps that replaced the prompt of `llm-task` or `agent-task` (`promptReplace`, now `instructionsReplace`) keep
working: the replaced text wins over `{{params.task}}`.

The former built-in templates `fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow` and `project-digest`
are gone (V304 deletes them; the seed removes them too). Their work is the node kind `ACTION` with `action.type`
`FETCH_ISSUE`, `COMMENT_ISSUE`, `TRANSITION_ISSUE`, `START_WORKFLOW` or `PROJECT_DIGEST`; their former template
defaults are the action defaults (for example `messageTemplate` =
`Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}` for `COMMENT_ISSUE`, `toStatus` =
`In Review` and `failIfMissing` = `false` for `TRANSITION_ISSUE`). A stored `TASK` node naming one of them is read as
the `ACTION` node with those defaults, the step's `config` and its `instructionsReplace` (formerly `promptReplace`) as the comment message.
`COMMENT_ISSUE` renders `messageTemplate` over the template model (variables:
[designing-a-workflow.md](../guides/designing-a-workflow.md#template-variables)). `TRANSITION_ISSUE` fails the node
when the issue's workflow offers no transition to `toStatus` (e.g. it is already there) unless `failIfMissing` is
`false`: then the node succeeds with a note in the run log; an absent `failIfMissing` counts as `true`.

SDLC templates (ADR 0019, derived from a reference SDLC framework, written in English):

| Key | extends | category | required skills; agent of the default workflows, how it runs | consumes → produces |
|---|---|---|---|---|
| `write-user-story` | — | `SPECIFICATION` | `user-story-format`; `analyst`; model answer, schema `user-story`, `openQuestions[]`, `confidence` (confidenceField) | `issue` → `user-story`, `confidence` |
| `write-use-case` | — | `SPECIFICATION` | `use-case-format`; `analyst`; model answer, schema `use-case`, `openQuestions[]`, `confidence` | `user-story` → `use-case`, `confidence` |
| `write-adr` | — | `PLANNING` | `adr-format`; `analyst`; model answer, schema `adr`, `options[]`, `decision`, `openQuestions[]`, `confidence` | `issue` (+ `analysis`) → `adr`, `confidence` |
| `impact-analysis` | — | `ANALYSIS` | `user-story-format`; `analyst`; model answer, CR sections: description, business justification, impact (artefacts, components, new stories, specification), estimate, options, recommendation, `openQuestions[]`, `confidence` | `issue` → `impact-analysis`, `confidence` |
| `plan-task-list` | — | `PLANNING` | `build-guardrails`, `definition-of-ready-done`; `analyst`; model answer, schema `tasks[] {title, description, verification, test_command, completed: false}` + `finalTaskTitle` enum `reformat, lint, and test` | `plan`, `issue` (+ `impact-analysis`, `user-story`) → `tasks` |
| `implement-task-list` | `implement-opencode` | (`IMPLEMENTATION`, inherited) | `build-guardrails` (inherited); coding agent, `opencode`; agent job; instructions replaced (`instructionsMode` REPLACE): tasks in order with TDD, run each `test_command`, stop on the first red verification, no commits by the agent, work log in `work-log.md` (collected as `work-log`, kept out of the commit) ending with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks; "Noticed out of scope" list without fixes | `tasks` → `diff`, `mr`, `work-log` |
| `review-against-spec` | — | `REVIEW` | `security-review-checklist`, `definition-of-ready-done`; `reviewer`; model answer, review schema plus `coverage[] {requirement, status MET/PARTIAL/MISSING/NOT_APPLICABLE, evidence}`; the matrix stays in the `review` artefact and a MISSING requirement makes `ReviewVerdictPolicy` return REQUEST_CHANGES | `diff`, `user-story`, `tasks` → `review` |

The skills these tasks need come from the step's agent (`analyst`: the format skills, `definition-of-ready-done`,
`build-guardrails`; `reviewer`: `security-review-checklist`, `definition-of-ready-done`; `opencode`:
`definition-of-ready-done` among others); the templates' `requiresSkills` only name them (ADR 0061).

A model answer step turns a structured field into an artefact only when the field name equals the
`produces` name, so the schemas name their document fields like the artefacts: `write-user-story`,
`write-use-case` and `write-adr` store the markdown of `user-story`, `use-case` and `adr`, `clarify-spec`
the markdown `clarifications` and `plan-task-list` the `tasks` array; `analyse-issue` and
`impact-analysis` store their whole JSON answer on purpose. A seed test checks that every `produces` name
of every built-in template is a schema field, a collected file (`work-log.md` becomes `work-log`) or an
output of the way it runs (model answer, Spec Kit phase or agent job). The review guard and the review model default recognise review steps by
`produces review` or category `REVIEW`.

Skills (eight): `spec-writing`, `conventional-commits`, `security-review-checklist`, and the SDLC skills `definition-of-ready-done` (binary DoR/DoD per user story, use case,
ADR, implementation task and release; failing DoR returns to the author; exceptions only with a recorded decision;
"DoD by vibe"), `user-story-format`, `use-case-format`, `adr-format` and `build-guardrails` (IDs `GR-A/S/Q/D`, a
rule the build does not enforce does not exist, every rule has a negative test). Each is a SKILL.md with a
frontmatter `name`/`description` ("Use when ...") and concrete instructions; agents list them (ADR 0057), and a few
built-in instructions name the skill the task relies on.

Rules (four, none automatic): `agent-ground-rules` (evidence, scope and safety: re-read before claiming, paths as
evidence, a hit outside scope is not a task, narrowest edit, report contradictions) and `ask-dont-guess` (source
hierarchy, one open-question marker per context: `[NEEDS CLARIFICATION: ...]` in Spec Kit specs, `[?]` elsewhere,
no invented owner or date, TODO/TBD with a reason, append not rewrite, never change status fields) are listed by
every built-in agent (and by the agents V305 creates); `kotlin-hexagonal` and `angular-ng-zorro` describe the Back Office
stack and are examples to copy into a project rule and make automatic there. Rules carry no frontmatter. Until
ADR 0055 all four were skills; `V303__rules_and_categories.sql` moves them from `skill` to `rule` in every scope,
moves their keys from templates' `skills`/`removeSkills` to `rules`/`removeRules`, and derives `category` from the
first implemented interface, else from the executor (removed later by ADR 0056; V305 then moved template rules and
skills to agents).

Seeding is idempotent by key. A built-in template, skill, rule or agent nobody edited (saved by the system
or before actor stamps existed, and for templates still version 1) is replaced by the shipped definition
when their content differs (for templates, skills and rules by a checksum over every editable field, including
category, parameters and parameter values, that normalises what the database changes: null config values, key
order, number types), so an unchanged entry is not rewritten on every start. Edited entries and non-built-in copies
are never overwritten (`seeded task library templates=... skills=... rules=... upgraded ...` in the startup log).

## Access and audit (ADR 0017, 0018)

| Operation | GLOBAL entry | PROJECT entry |
|---|---|---|
| list, get, resolve | `library.view` | `project.view` or `library.project.manage` on the project |
| create, update, delete | `library.manage` | `library.project.manage` on the project |

The same applies to templates, skills, rules and agents. Moving an entry between scopes needs the write permission of
both. `TaskCatalog` (used by the workflow engine and designer) is a system operation and checks nothing itself.
Every change is audited: `TEMPLATE_CREATE/UPDATE/DELETE` (update diff covers key, name, scope, `extendsKey`,
category, required skills, instructions length and whether they changed, parameters, config keys, schema length,
produces/consumes), `SKILL_*`, `RULE_CREATE/UPDATE/DELETE` (`automatic`, content length), `AGENT_*`;
entries carry `updatedBy` (`V301__actor_stamps.sql`). Built-in templates cannot be deleted; a template another one
extends, a skill or a rule an agent lists cannot be deleted, and their key and scope cannot change (an agent no longer
has such a guard, ADR 0061). `POST /agents/{agentId}/coherence-check` needs `library.view` (with `projectId` also
`project.view`) and is audited as `AGENT_COHERENCE_CHECK`.

## API

`/api/v1/task-templates` (+ `/resolve/{key}`, GET for the template, POST with a step's overrides),
`/api/v1/skills`, `/api/v1/rules`, `/api/v1/agents` (ADR 0056) — CRUD per `openapi.yaml`; `key` is optional on
create and update. `GET /api/v1/workflows/task-config-schemas` gives the config schema of model answer and coding
agent steps (it replaced `/workflows/task-executors`). Built-in
entries can be edited and shadowed per project; built-in templates cannot be deleted.

## UI

**Library** has three groups (ADR 0055, 0056): **Tasks** (Templates, Skills, Rules, Agents), **Workflows** (the workflow
library belongs to the Projects context, ADR 0037 and
[03-pipelines-and-agents.md](03-pipelines-and-agents.md#workflow-library)) and **Sources**. The template list is
grouped by category, marks templates that **needs parameters** and **Needs a coding agent**, and filters by the
kind of agent needed (**Needs a coding agent** / **Any agent**); the template editor shows the inheritance chain,
the tag **Needs a coding agent** (read-only, from the merged config), **Required skills**, **Parameters** and
**Instructions** (with **Append to parent** / **Replace parent** only when the template has a parent),
**Parameter values**, **Create preset**, an "effective prompt" preview (resolved, with the skills and rules of the
default agent for the kind of work, an **Instructions** and a **Parameters** tab) and the problems of the check. Since ADR 0061 it has no
**Agent**, no **Model profile** and no "Environment of the agent" box. Field descriptions sit behind a "?" next to
the label; warnings and consequences stay visible. The agent editor has **Instructions**, **Rules**, **Skills** and
**MCP servers**, **Access**, **Directory** and **Session** (ADR 0062). Since ADR 0062 the template editor has no
**System prompt** / **User prompt**, no **Run inputs** tab and no **Sandbox** section (the sandbox is set on the
project). The old `/library/interfaces` link redirects to the templates. The workflow designer palette lists
concrete templates grouped by category with the **needs parameters** and **coding agent** marks (hover: the kind of
agent needed and the required skills), and the actions in a group of their own; a task step has **Instructions for this step** (**Append**, **Replace (advanced)**), **Parameters** with a
source per parameter (**Value**, **Ask at start**, **Result of an earlier step**; `overrides.parameterBindings`),
**Agent** (`overrides.agentKey`, pre-selected, placeholder "Default: {agent}") and **Model profile**; the step's
**Directory** and **Agent session** of ADR 0060 are gone (they are the agent's). The designer header's **Agents in
this workflow** opens the agent audit panel with **Add to agent** and **Check coherence**.
