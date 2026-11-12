# Task Library: templates, agents, skills and rules

## When to use this guide

You want to change what an agent does or how it works without editing a workflow: add a new kind of task, give a
template parameters a workflow step fills in, specialise a built-in for a single project, define an agent (a way of
working with its instructions, rules, skills and connectors), give agents tools (connectors: MCP servers with
credentials, read-only GitLab), or understand why what an agent receives looks the way it does. The concepts and merge
rules are specified in `docs/architecture/07-task-library.md` (ADR 0015, 0055, 0056, 0057, 0058, 0064); this guide
walks through the API (`/api/v1/task-templates`, `/api/v1/agents`, `/api/v1/skills`, `/api/v1/rules`,
`/api/v1/connectors`) and the Library UI (**Library** → **Tasks** → **Templates**, **Skills**, **Rules**,
**Agents**, **Connectors**; the other groups are **Workflows** and **Sources**).

## 1. Concepts in one table

| Concept | Analogy | You use it to |
|---|---|---|
| Task template | the assignment: **what** | describe the work and only the work: category, one `instructions` text with parameters (`{{params.<name>}}`), config, outputs, the skills the work needs (`requiresSkills`), optionally `extends` one parent; no agent, no model (ADR 0061), no system/user prompt, no run inputs, no sandbox policy (ADR 0062) |
| Agent | the worker: **who and how** | name a way of working: harness (none = model answer, `OPENCODE`, `CLAUDE_CODE`), optional model, `instructions`, `rules`, `skills`, `connectors` (ADR 0056, 0057, 0064), `access` (with `directory`) and `session` (ADR 0060, 0062) |
| Connector | a tool | give coding agents a tool by key: an MCP server (`MCP_HTTP`) with its credentials or read-only GitLab (`GITLAB_READ`); platform or project scope (section 6a, ADR 0064) |
| Rule | standing order | state a norm the agent must always follow; always in the agent's context |
| Skill | handbook chapter | give the agent knowledge or a procedure (`SKILL.md` convention) it opens when the task needs it |
| Step | the job on the board | a template + the agent it chooses (`overrides.agentKey`, optionally `overrides.modelProfileId`) + parameter values (a `TASK` node of a workflow) |
| Resolved task | instance | the merged result the step runs: template + ancestors + project shadowing + node overrides, with the resolved agent, its rules and skills, and the parameter values |

The template changes when the work changes; the agent changes when you look for a cheaper or better way to work.
Because the agent is complete, the same task can run with two agents and be compared on equal terms (ADR 0057,
0059).

Until ADR 0057 templates carried `skills`, `removeSkills`, `rules` and `removeRules`. They are gone: rules and skills
belong to the agent (section 6), and `V305__agent_environment.sql` gave every non-pristine template that had its own
an agent of its own (`<key>-agent`, same scope, the harness of its former agent, the ground rules plus its rules and
skills) and named it. Library imports ignore template skills and rules with a warning.

Until ADR 0056 a template had an `executor` (`LLM`, `SPEC_KIT` (gone since ADR 0063), `IMPLEMENT`, `FETCH_ISSUE`, `NOTIFY`,
`TRANSITION_ISSUE`, `START_WORKFLOW`, `PROJECT_DIGEST`). It is gone: who runs a step is its agent (section 6), and
the five deterministic integrations are `ACTION` nodes of the workflow designer, not library entries (section 8).

Until ADR 0055 there were also task interfaces (`/api/v1/task-interfaces`, `implements`). No workflow ever
referred to one and they only duplicated `consumes`/`produces`, so they were removed; what they gave, a grouping,
is now the template's `category` (`ANALYSIS`, `SPECIFICATION`, `PLANNING`, `IMPLEMENTATION`, `REVIEW`, `JIRA`,
`OPERATIONS`, `GENERAL`; inherited, child wins), by which the library list and the designer palette are grouped.
Every task node has the ports `success` and `failure`.

Keys match `^[a-z][a-z0-9-]{1,63}$` and are optional in the API. Without one, create derives it from the name
(diacritics dropped, `-` between words, a `task-`/`skill-`/`rule-` prefix when the name starts with a digit,
`-2`, `-3`, ... when taken: `"Analiza zgłoszenia"` becomes `analiza-zgloszenia`); update keeps the stored key.
Template keys are unique across scopes, so a template created without a key never shadows anything; skills,
rules and agents are unique by `(key, scope)` where scope is `GLOBAL` or `PROJECT(projectId)`. Send an explicit key only to
shadow a global entry in a project (section 3).

The built-in library (seeded on startup, `backoffice.tasks.seed-builtin=true`) contains flat templates (ADR 0058:
only `implement-task-list` and the deprecated `implement-claude-code` extend `implement-opencode`): `write-spec`,
`write-plan` and `write-task-list` (ADR 0063: a coding agent writes `specs/<feature>/spec.md`, `plan.md` and
`tasks.md` into the repository; the former `spec-kit-specify`, `spec-kit-plan` and `spec-kit-tasks` are deprecated
templates that extend them unchanged, so stored steps still resolve), `implement-opencode`, `clarify-spec`, `code-review`, `analyse-issue` (deprecated), the generic `llm-task`
and `agent-task`, `prepare-workspace` and the SDLC templates of section 9; the deprecated, emptied abstract
`base-agent`, `base-llm` and `base-coding-agent` (kept only for user templates that extend them; their text is the
platform preamble now); eight skills; four rules (`agent-ground-rules`, `ask-dont-guess`, `kotlin-hexagonal`,
`angular-ng-zorro`); and five agents (`model-answer`, `analyst`, `reviewer`, `opencode`, `claude-code`). Seeding is
idempotent by key: edit built-in templates, skills and rules freely, an edited entry is never overwritten (a
never-edited built-in is replaced by the shipped version when its content differs); delete a non-built-in copy and
restart to get the shipped version back. Built-in templates cannot be deleted, only shadowed; built-in agents cannot
be changed, only shadowed per project.

The former built-in templates `fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow` and
`project-digest` are actions now (ADR 0056; section 8). Their former defaults are the action defaults:

- **Comment on issue** (`COMMENT_ISSUE`, former `notify-jira`) posts a comment rendered from
  `action.config.messageTemplate`, by default
  `Back Office run {{run.short}} ({{run.workflow}}): {{node.name}}. {{mr.url}}` (`{{mr.url}}` is empty
  before a step that opened a merge request).
- **Transition issue** (`TRANSITION_ISSUE`) moves the issue to `action.config.toStatus` (default `In Review`).
  When the issue's workflow offers no such transition (e.g. the issue is already there),
  `failIfMissing` decides: `false` (default) skips it with a note in the run log and the node
  succeeds; `true` fails the node.

All variables usable in instructions and templates: [designing-a-workflow.md](designing-a-workflow.md#template-variables).

### Who may do what

| You want to | GLOBAL (the shared library) | PROJECT (one project's own entries) |
|---|---|---|
| see templates, skills, rules, agents, the resolved preview | `library.view` (role `member` has it) | `project.view` or `library.project.manage` on that project |
| create, edit, delete templates, skills, rules and agents | `library.manage` (role `library-curator`) | `library.project.manage` on that project |
| see connectors (never their secrets) | `library.view` | `project.view` on that project |
| create, edit, test, delete connectors | `library.manage` | `project.connections.manage` on that project (role `owner`) |

`GET /api/v1/task-templates?projectId=P` (likewise `/skills`, `/rules` and `/agents`) returns the global entries when you
hold `library.view` plus P's entries when you are a member of P; without either you get `403`. Moving an entry
between scopes needs the write permission of both. Every change is recorded in the audit log (`TEMPLATE_*`,
`SKILL_*`, `RULE_CREATE`/`RULE_UPDATE`/`RULE_DELETE`, `AGENT_*`, with a field diff on update) and the response shows
`updatedBy`.

## 2. A template with parameters

A template is only the work (ADR 0061): it names no agent and no model, the workflow step does. Say which skills the
work needs in `requiresSkills`, and write what to do and the acceptance criteria into `instructions` (one text;
templates have no rules, no system prompt and no role sentences: the agent carries the role, ADR 0062). Leave
`category` empty to inherit it.

```http
POST /api/v1/task-templates
{
  "name": "Write changelog entry",
  "scope": { "type": "GLOBAL" },
  "category": "GENERAL",
  "requiresSkills": ["conventional-commits"],
  "instructions": "Write a Keep-a-Changelog entry for the diff below, for {{params.audience}} readers. One bullet per user-visible change; leave out refactorings.",
  "parameters": [
    { "name": "audience", "label": "Audience", "type": "CHOICE", "options": ["developer", "customer"],
      "required": true, "default": null, "help": "Who reads the changelog" }
  ],
  "consumes": ["diff"],
  "produces": ["changelog"],
  "config": { "maxInputChars": 100000 },
  "tags": ["docs"]
}
```

The answer carries the derived key, here `write-changelog-entry`, and the read-only `needsCodingAgent` (here `false`:
the config has no `codingAgent: true` or `prepareOnly: true`, so any agent can do it; with one of them it is `true`
and only an OpenCode or Claude Code agent can). `agentKey` and `modelProfileId` are no longer part of
`TaskTemplateInput`. Workflows refer to the key. Because `audience` is
required and has no default, the template **needs parameters**: a step must give it a value or bind it
(`overrides.parameterBindings`, section 8), or you create a preset (below).

Work in the repository is a template with `"config": { "codingAgent": true }` (UI: the checkbox **Works in the
repository (needs a coding agent)**), optionally `verifyCommand` (**Verify command**, the acceptance check run after
the agent) and `outputFiles` (**Output files**, under **Advanced: artefacts, output files, output schema**): globs of
files the agent leaves that come back as artefacts, named like the file without its extension (ADR 0063; the former
config key `collectArtifacts`). A template has no git settings, repositories or setup command: the workflow's
`sources` and `setupCommand` and its **Deliver changes** action cover them
([designing-a-workflow.md](designing-a-workflow.md#deliver-changes)).

```http
POST /api/v1/task-templates
{
  "name": "Write release notes",
  "category": "OPERATIONS",
  "instructions": "Write docs/release-notes.md for the change of this issue ...",
  "config": { "codingAgent": true, "verifyCommand": "test -s docs/release-notes.md" },
  "outputFiles": ["docs/release-notes.md"],
  "produces": ["release-notes"]
}
```

A preset:

```http
POST /api/v1/task-templates
{
  "name": "Customer changelog entry",
  "scope": { "type": "GLOBAL" },
  "extendsKey": "write-changelog-entry",
  "parameterValues": { "audience": "customer" }
}
```

A preset is a ready block: the palette offers it without the mark (`PaletteTask.needsParameters = false`). The UI
makes one with **Create preset** in the template editor.

**What the agent receives (ADR 0062).** The instructions do not place the diff: the platform adds the standard data
sections after them, each only when it has content and the instructions do not place it with its own placeholder:
the issue (`{{issue.*}}`), the consumed artefacts (`{{artifacts.<name>}}`, here the diff, which always comes last so a
message cut at its size limit loses diff lines, never the instructions), the parameter values bound at run start that
the instructions do not read (`{{params.<name>}}`), the reviewer's feedback (`{{feedback}}`) and the owner's answers
(`{{answers}}`). The system part is the platform's: the agent's environment document and the answer format (output
schema, how to ask). There is no system prompt and no user prompt on a template any more: the frame belongs to the
agent (ADR 0057), coding agents got both fields as one prompt anyway, and template system prompts were mostly role
sentences duplicating the agent. `instructionsMode` (`APPEND` default, `REPLACE`) only matters with `extendsKey`
(the UI shows **Append to parent** / **Replace parent** only then).

**Parameters** (`TemplateParameter`, ADR 0058, 0062): `name` (`^[A-Za-z][A-Za-z0-9_]{0,63}$`), `label` (at most 200),
`type` `TEXT` | `MULTILINE` | `NUMBER` | `BOOLEAN` | `CHOICE` (with `options`) | `ISSUE_KEY` (a Jira issue key; UI
"Jira issue key"), `required`, `default` (string, number or boolean by type), `help` (at most 1 000). The instructions
and text config read them as `{{params.<name>}}`. They are the inputs of the work; the template does not say whether a
value is fixed or asked when a run starts. The workflow step decides per parameter (`overrides.parameterBindings`,
section 8): `VALUE` (a fixed value), `START` (asked in the run start form, with the parameter's label, type, required
flag and help; a `START` `ISSUE_KEY` is the run's issue key) or `ARTIFACT` (the content of an earlier step's artefact).
Unbound, the value is the nearest template's (`parameterValues`), else the default; a `START` or `ARTIFACT` value the
run leaves empty falls back the same way. Design-time values are trusted like the instructions; values from the run
(`START`, `ARTIFACT`) are untrusted and rendered as data blocks (ADR 0029). Unknown names, wrong types, an `ARTIFACT`
binding without an artefact name and a required parameter that has neither a value nor a run-time binding are
resolution problems: the step cannot run and the designer marks it, the preview still works.

**Run inputs are not declared on templates any more (ADR 0062).** A workflow's start form is the union of the
parameters its steps bind `START` and the run inputs of its action nodes. To ask for something when a run starts,
declare a parameter and bind it **Ask at start** on the step. Migration V308 turned every template's former
`runInputs` into parameters (`TEXT`, `MULTILINE` or `ISSUE_KEY`, a `-` in the name becoming `_`) and bound them `START` on
every existing step of such a template, so start forms stayed the same.

What the validator checks on every save (`400` with all problems listed): key format, parent exists and
is not the template itself, `outputSchema` is well-formed JSON, `confidenceField` needs an `outputSchema` somewhere in
the chain, parameter declarations (names, labels, options of a `CHOICE`, defaults of the right type) and
`parameterValues` against the lineage's parameters, no inheritance cycle and at most 8
templates in the chain. Updates bump `version`.

Merge rules, root to leaf:

| Field | Rule |
|---|---|
| `category` | nearest defined wins |
| `outputSchema`, `confidenceField` | child wins when set |
| `instructions` | child `APPEND` (`instructionsMode`) = parent + blank line + child; `REPLACE` = child only; an empty child keeps the parent's |
| `parameters` | union by name; a child's parameter replaces the parent's with the same name |
| `parameterValues` | the nearest template that sets a value wins; the step's value wins over all |
| `config` | deep merge, child keys win, a `null` value deletes the key (so `null`s never reach the step) |
| `produces`, `consumes`, `tags`, `requiresSkills` | union |

`needsCodingAgent` follows from the merged `config`. The project constitution and the rules are not template text
any more: every step gets them in its agent's environment (section 6). A template whose instructions still contain `{{project.constitution}}` show it twice; the
built-ins were cleaned up. ADR 0062 rewrote the built-ins once more: no role sentences ("You are ...", the agents carry
the roles) and no data sections the platform adds itself; V308 joined the system and user prompt of every stored
template into its `instructions` (system first, blank line, user).

## 3. A project-scoped override (shadowing)

A `PROJECT` template with the same key as a `GLOBAL` one replaces it for that project only, including
when the key is used as a parent by another template. To keep the global template and change only what differs,
give the project template the global key and the same key as `extendsKey`: it then extends the global one (this
is what **Customize for project** in the UI does).

```http
POST /api/v1/task-templates
{
  "key": "code-review",
  "name": "Code review (SHOP)",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "extendsKey": "code-review",
  "instructions": "This is a payment system: treat any change to money handling as MAJOR at least.",
  "instructionsMode": "APPEND"
}
```

Without the `extendsKey` the project template is a plain replacement, not an extension: it has to bring its own
instructions, schema and so on. To have the project's code reviews done by `shop-reviewer`, choose that agent on the steps
(or give the project an agent with the key `reviewer` that shadows the built-in one); the template does not name it.

`GET /api/v1/task-templates?projectId=<uuid>` lists global templates plus that project's own, both
entries for a shadowed key (the `scope` field tells them apart). `includeAbstract=false` hides abstract
ones. The designer palette (`TaskCatalog.palette`) shows one entry per key, the project's own winning, grouped by
`category`.

Skills, rules and agents shadow the same way: a `PROJECT` skill, rule or agent with the key of a global one is what
that project's steps receive, without changing any template or agent.

## 4. Skills

A skill is knowledge or a procedure the agent loads when it needs it ("how to write an ADR"): a markdown document
following the Agent Skills convention, a frontmatter with `name` and `description` ("Use when ...", agents decide
by it whether to open the skill), then short, concrete, numbered steps. Something the agent must always follow
belongs in a rule (section 5).

```http
POST /api/v1/skills
{
  "name": "SQL migrations",
  "description": "Use when writing or reviewing a Flyway migration.",
  "scope": { "type": "GLOBAL" },
  "content": "---\nname: sql-migrations\ndescription: Use when writing or reviewing a Flyway migration.\n---\n\n# SQL migrations\n\n1. Append-only: never edit an applied version.\n2. ...",
  "tags": ["database"]
}
```

A step gets the skills of its agent, nothing else: since ADR 0060 a step no longer adds or removes skills
(`overrides.addSkills` / `removeSkills` of stored workflows are ignored). A template may list the skills its work needs
in `requiresSkills` (inherited, unioned); a step whose agent lacks one gets a `check.warnings` entry, not a problem,
and a `MISSING_SKILL` finding in the agent audit (section 6). Required skills are never injected at run time; the
designer uses them to pre-select the step's agent, and "Add to agent" adds a missing one to the agent once.
How skills reach the agent: model answer steps get them appended to the system prompt in a `<skills>` section (a
single model call cannot open files); coding agent steps get them written to
`skills/<key>/SKILL.md` in the agent's home `/workspace/.agents/<agent key>/` (never into a repository), which Claude
Code and OpenCode load on their own; the platform preamble tells the agent where they are. A skill an agent
lists cannot be deleted, and its key and scope cannot change (`409`); built-in skills cannot be deleted.

## 5. Rules

A rule is a norm that is always in the agent's context ("the domain never imports Spring"): plain markdown, no
frontmatter, short, concrete, checkable sentences.

```http
POST /api/v1/rules
{
  "name": "Shop Kotlin conventions",
  "description": "Layers and naming of the shop backend.",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "automatic": true,
  "content": "1. The domain never imports Spring, JPA or Jackson.\n2. ...",
  "tags": ["kotlin"]
}
```

Which rules a step gets, in this order:

1. the **automatic** rules of the global scope (every step of every project), then those of the step's project
   (every step of that project);
2. the rules the step's agent lists in `rules`, unless already there.

An automatic rule applies to every step of its scope; the former template-level `removeRules` is gone. A project
rule with the key of a global rule replaces it for that project; a non-automatic project copy of a global
automatic rule switches that rule off in the project. A key in an agent's `skills` that names a rule (as the
built-in skills turned rules did, see section 9) counts as that rule.

How rules reach the agent: as part of the environment document (section 6), never in the task prompt, so a
continued session does not receive them twice. Permissions are the same as for skills; a rule an agent lists cannot
be deleted, and its key and scope cannot change (`409`).

## 6. Agents

An agent is who works and how (ADR 0056, 0057): a **harness**, optionally a **model**, and the agent's environment:
**instructions**, **rules**, **skills** and **connectors** (tools, section 6a). Without a harness the step is a **model answer**, one
in-process model call without tools or repository; with `OPENCODE` or `CLAUDE_CODE` it is a **coding agent** session
in the runner sandbox, in the run directory (ADR 0060). Without a model the step uses the defaults for its kind (project,
starting user, platform).

```http
POST /api/v1/agents
{
  "name": "Shop reviewer",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "harness": null,
  "modelProfileId": "9b1c...-model-profile-uuid",
  "instructions": "You review changes to the shop's payment code for {{project.name}}. Every finding names a file and what to change.",
  "rules": ["agent-ground-rules", "ask-dont-guess", "shop-kotlin-conventions"],
  "skills": ["security-review-checklist", "definition-of-ready-done"],
  "connectors": [],
  "access": { "mode": "FULL", "directory": "RUN" },
  "session": "PER_STEP",
  "tags": ["review"]
}
```

Built-in agents (no model of their own; all list `agent-ground-rules` and `ask-dont-guess`):

| Key | Harness | Skills | Good for (the default workflows name it on these steps) |
|---|---|---|---|
| `model-answer` | none | none | `llm-task`; a step without its own agent whose template needs no coding agent |
| `analyst` | none | `user-story-format`, `use-case-format`, `adr-format`, `definition-of-ready-done`, `build-guardrails` | `write-user-story`, `write-use-case`, `write-adr`, `impact-analysis`, `plan-task-list`, `clarify-spec`, `analyse-issue` |
| `reviewer` | none | `security-review-checklist`, `definition-of-ready-done` | `code-review`, `review-against-spec` |
| `opencode` | `OPENCODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | `write-spec`, `write-plan`, `write-task-list`, `implement-opencode`, `implement-task-list`, `prepare-workspace`; a step without its own agent whose template needs a coding agent |
| `claude-code` | `CLAUDE_CODE` | `spec-writing`, `definition-of-ready-done`, `build-guardrails` | `agent-task` (reference workflow), any coding work |

`opencode` and `claude-code` list the connector `gitlab-read` (ADR 0064): define it in a project (section 6a) and
they read GitLab there without any change to the agents.

Deleting an agent is not blocked by templates any more (they name none); steps that still name it get the
`UNKNOWN_AGENT` finding of the agent audit and cannot run.

- **Who runs a step** (ADR 0061): the step's `overrides.agentKey`, else `opencode` when the template needs a coding
  agent (`needsCodingAgent`), else `model-answer`. The designer pre-selects an agent when a template is dropped: right
  kind (coding agent when needed; otherwise model answer agents first), most `requiresSkills` covered, then the
  platform default of the kind, then built-in, then name.
- **Which model**: the step's `modelProfileId`, else the agent's, then the defaults (`ModelResolver`; the model plan
  shows the source `AGENT` for the agent's model; `TEMPLATE` is no longer produced).
- **How it runs**: model answer → the in-process call (structured output, confidence, injection guard); coding
  agent → the agent job in the run directory, which never commits or pushes (ADR 0063: the workflow's
  `DELIVER_CHANGES` action delivers); its `outputFiles` come back as artefacts, and without a repository the final
  answer becomes the `result` artefact.
- **Problems** (the step cannot run, listed in `check.problems`): a template that needs a coding agent
  (`codingAgent: true`, `prepareOnly`) with a model answer agent on the step; a skill or rule the agent lists does not exist; a
  required parameter has no value.

**Agent audit and coherence check (ADR 0061).** Before a run, check the agents of a workflow draft:

```http
POST /api/v1/workflows/agent-audit
{ "projectId": "5d3e...-project-uuid", "nodes": [ ...the draft's nodes... ] }
```

The answer (`AgentAudit`) lists per agent its steps (`nodeIds`), `rules`, `skills`, `requiredSkills` (with the steps
needing each) and `missingSkills`, plus `findings` (`MISSING_SKILL`, `DUPLICATE_SKILL_NAME`, `UNKNOWN_CONNECTOR` as WARNING;
`NEEDS_CODING_AGENT`, `UNKNOWN_RULE`, `UNKNOWN_SKILL`, `UNKNOWN_AGENT` as ERROR; `READ_ONLY_PUSH` is no longer
reported since agents never push, ADR 0063). No model is
called and nothing changes (`project.view`). To fix a missing skill, update the agent (a global agent: create a
project copy with the same key and the skill, as the designer's "Add to agent" does).

`POST /api/v1/agents/{agentId}/coherence-check?projectId=<uuid>` (`library.view`, with a project also
`project.view`) asks the platform default model for model answers to read the agent's whole environment (preamble,
instructions, constitution, rules, skill names and descriptions, and the primary repository's `AGENTS.md` /
`CLAUDE.md` when Back Office can read them). The `CoherenceReport` has the `model`, the `cost` (booked as usage), the
`sources` read and `findings` (`CONTRADICTION`, `AMBIGUITY`, `REDUNDANCY`, each with `summary`, `quotes` and an optional
`suggestion`). Without a usable default model the answer is `409`. Run it on request; it is never part of a run.

**Precedence.** The platform preamble tells every agent the order when its sources disagree: the project
constitution, then the rules (repository `AGENTS.md` / `CLAUDE.md`, project, agent), then the skills, then the task;
when it cannot resolve a contradiction it asks instead of guessing.

**The environment document.** Back Office renders one document per step: the platform preamble for the kind of agent
(fixed in code: what Back Office is, honesty, scope, safety; for model answers no repository, no tools, JSON only
with a schema; for coding agents do not commit or push, leave the changes uncommitted, never weaken a test, quote
file paths, where the skills are), the agent's `instructions` (rendered like a prompt, `{{project.*}}` allowed), the project constitution,
then the rules. A model answer gets it as the start of its system prompt (then the skills; the template contributes
no system text since ADR 0062, the task message is the user message). For coding agents it travels as `RunnerJobSpec.instructions` and the runner writes it into the agent's
**home** `/workspace/.agents/<agent key>/` (`RunnerJobSpec.agentHome`, ADR 0060), outside every repository: Claude
Code `CLAUDE.md` with the home as `CLAUDE_CONFIG_DIR` (with `skills/`, `mcp.json` (its MCP connectors, no secrets), `settings.json` and its sessions);
OpenCode `AGENTS.md`, listed under `instructions` of the home's `opencode.json` with the home as
`OPENCODE_CONFIG_DIR` (with `skills/` and its sessions under `data/<session role>`). Nothing of an agent is written into
a repository, so agents of one run directory never see each other's skills or memory. The repository's own
`AGENTS.md` / `CLAUDE.md` stay untouched and are read by the harness as the project's rules for every agent; OpenCode
also reads the repository's `.opencode/skills` and `.claude/skills` as project skills.

**Access** (`access`, ADR 0060, coding agents only): `{"mode": "FULL"}` (default), `{"mode": "READ_ONLY"}` or
`{"mode": "PATHS", "paths": ["docs/**"]}` (globs relative to a repository root, at most 50, no leading `/`, no `..`).
The harness's tool rules keep the agent from editing elsewhere, and the runner reverts every change outside the access
after the agent finished (shell included), lists it in the step log and ends the summary with `[Back Office runner]
reverted N change(s) outside the agent's access (...)`; ignored files are not covered. Agents never push (ADR 0063),
so a `READ_ONLY` agent fits every step. Access narrows what the agent may change, not what it sees: use an agent with
`"directory": "SEPARATE"` to hide the rest of the run; what it sees of the code is the workflow's `sources`.

**Directory** (`access.directory`, ADR 0062, coding agents; UI **Directory**): `RUN` (**Run directory (shared)**,
default: the run directory every coding step of the run works in, seeing what earlier steps left) or `SEPARATE`
(**Separate directory**: a fresh copy of the workflow's sources, thrown away after the step, never delivered; no session
continuation).

**Connectors.** `connectors` lists connector keys (`^[a-z][a-z0-9-]{0,39}$`); see section 6a. A model answer has
no tools and ignores them (the UI shows the field for coding agents only).

**Coding agents answering in a schema.** A template with an `outputSchema` works with a coding agent too: the agent is
told to write one JSON object to `structured-answer.json` in its working directory; the runner collects it as the
artefact `structured-answer` and keeps it out of the commit; Back Office validates it like a model answer (schema
check, confidence field, produced artefacts). A missing or invalid file fails the step.

**Session** (`session`, ADR 0062, coding agents; UI **Session**): `PER_STEP` (**Per step (repeats continue)**,
default: the repeats of one step, such as rework or answered questions, continue its conversation), `PER_RUN` (**One
per run**: all steps of this agent in a run are one conversation) or `FRESH` (**Always fresh**). The session lives in
the agent's home, so only steps of the same agent can share one. Until ADR 0062 the session (`OWN`, `NEW`,
`CONTINUE` with `continueSession`) and the directory were step settings; V308 dropped them from stored steps; see
[designing-a-workflow.md](designing-a-workflow.md#agent-sessions).

The config key `agent` (`CLAUDE_CODE`/`OPENCODE`) no longer exists: choose the agent on the step instead. A stored
step's `config.agent` is read as its `agentKey` (`claude-code`, `opencode`); on a template it is dropped. V304 had moved
the key out of template config into the template's `agentKey`; V307 (ADR 0061) wrote every template's agent and model
into the stored steps that had none and dropped both template columns.
`GET /api/v1/workflows/task-config-schemas` returns the JSON Schema of the config keys of model answer and coding
agent steps (it replaced `/workflows/task-executors`).

To compare harnesses, models or ways of working on the same task, create one agent per variant and pick it on the
step; the template stays the same.

## 6a. Connectors: tools for agents (ADR 0064)

A connector is a tool coding agents use: read pipelines and job logs, an issue tracker, documentation search. Back
Office uses **MCP** for it, the standard agent-tool protocol (Claude's connectors are MCP servers; Claude Code,
OpenCode, Copilot, Cursor and Codex speak it; most systems have a published server), instead of a plugin API of its
own: it stores the servers and their secrets and hands them to agents safely. Connectors live in the Projects context
(`projects-core`), next to the other secrets; the library stores only the keys an agent lists.

| Kind | Fields | The agent gets |
|---|---|---|
| `MCP_HTTP` (UI **MCP server (HTTP)**) | `url` (http/https, streamable HTTP), `auth` `NONE` / `BEARER` (`Authorization: Bearer <secret>`) / `HEADER` (`<headerName>: <secret>`), write-only `secret` | the server's tools in Claude Code (`--mcp-config`) or OpenCode (`mcp`) |
| `GITLAB_READ` (UI **GitLab (read-only)**, project scope only) | `repository` (a project repository key; empty = the primary) | glab with the repository's verified read-only `CI_READ` token (ADR 0051); the step fails before the job when there is none |

Platform connectors (`scope: GLOBAL`, **Library** → **Connectors**, `library.manage`) serve every project; project
connectors (`scope: PROJECT`, project tab **Connectors**, `project.connections.manage`) serve that project and
**shadow** the platform connector with the same key.

```http
POST /api/v1/connectors
{
  "name": "Docs search",
  "description": "Searches the internal documentation portal.",
  "kind": "MCP_HTTP",
  "scope": { "type": "GLOBAL" },
  "url": "https://mcp.docs.example.com/mcp",
  "auth": "HEADER",
  "headerName": "X-Api-Key",
  "secret": "<token issued for this connector>"
}
```

```http
POST /api/v1/connectors
{
  "key": "gitlab-read",
  "name": "GitLab (read only)",
  "kind": "GITLAB_READ",
  "scope": { "type": "PROJECT", "projectId": "5d3e...-project-uuid" },
  "repository": null
}
```

- **Key**: optional on create (derived from the name), fixed afterwards; the scope cannot change either.
- **Secret**: write-only and never returned (`hasSecret`); stored sealed like connection secrets (needs
  `BACKOFFICE_SECRETS_KEY`); on `PUT` an omitted or `null` secret keeps the stored one, `""` removes it. UI: **Secret**
  with the placeholder **stored - leave empty to keep** and **Remove secret**. Single line, `BEARER` / `HEADER` only.
- **Test**: `POST /api/v1/connectors/{id}/test` runs from Back Office (not the sandbox): `MCP_HTTP` sends `initialize`,
  `notifications/initialized` and `tools/list` with the stored secret (JSON or SSE answers, no redirects, short
  timeouts) and returns the announced tool names; `GITLAB_READ` re-verifies the repository's read-only `CI_READ`
  token. The result is stored as `lastTest` (UI **Last test**, **Tools:**).
- **Host to allow**: the response's `host`. Agents reach it only when the sandbox allowlist allows it (project
  sandbox policy within the pool ceiling and the platform policy, [runner-fleet.md](runner-fleet.md#3-sandbox-policies)).
- **Audit**: `CONNECTOR_CREATE`, `CONNECTOR_UPDATE`, `CONNECTOR_DELETE`, `CONNECTOR_TEST` (never the secret).

**Agents list keys.** `"connectors": ["docs-search", "gitlab-read"]` on an agent (UI **Connectors**, coding agents
only). At a step each key is looked up in the run's project, then on the platform; a key with no connector is skipped
with a step-log line (`connector <key> is defined neither in this project nor on the platform; skipped`) and shown by
the agent audit as `UNKNOWN_CONNECTOR` (UI **Unknown connector**, a warning). A global agent may list keys that only
some projects define. An `MCP_HTTP` connector whose auth needs a secret it does not have is skipped too. Deleting a
connector never blocks: agents that list it skip it from then on.

**Delivery.** The job spec carries `connectors` (key, kind, URL, auth type, header name, repository) and names the
secrets in `credentials` (capability `CONNECTOR`); the runner-controller fetches them sealed and the runner gives each
secret only to the agent process as `BO_CONNECTOR_<KEY>`. The harness configuration refers to it (Claude Code
`${BO_CONNECTOR_<KEY>}`, OpenCode `{env:BO_CONNECTOR_<KEY>}`), so no secret is on disk; it is redacted from logs.
Details: `runner/README.md` and [10-agent-execution.md](../architecture/10-agent-execution.md#project-repository-tokens-adr-0051).

**Upgrade (V310).** Agent `mcpServers` became platform connectors (`MCP_HTTP`, `NONE`) the agents list; step
`agentTools: gitlab-read` became the project connector `gitlab-read` in every project whose steps used it, and the key
left the steps and templates.

## 7. Reading the resolved preview

`GET /api/v1/task-templates/resolve/{key}?projectId=` returns exactly what the step will receive,
plus fields the agent never sees (`POST` on the same path with a node's `TaskOverrides` shows what that step
gets):

```json
{
  "key": "llm-task",
  "agent": { "key": "model-answer", "name": "Model answer", "harness": null, "modelProfileId": null },
  "category": "GENERAL",
  "lineage": ["llm-task"],
  "instructions": "{{params.task}}",
  "parameters": [
    { "name": "task", "label": "Task", "type": "MULTILINE", "required": false,
      "default": "Summarise the input material in at most five bullet points and list open questions.",
      "value": "Summarise the input material in at most five bullet points and list open questions.", "source": "DEFAULT" },
    { "name": "input", "label": "Input material", "type": "MULTILINE", "required": false, "value": null, "source": "NONE" }
  ],
  "runInputs": [],
  "skills": [],
  "rules": [ { "key": "agent-ground-rules", "name": "Agent ground rules", "automatic": false }, { "key": "ask-dont-guess", "name": "Ask, don't guess", "automatic": false } ],
  "config": {},
  "produces": ["result"],
  "consumes": [],
  "ports": ["success", "failure"],
  "abstract": false,
  "check": { "ok": true, "problems": [], "warnings": [] }
}
```

- `lineage` is leaf first; the UI draws it as the inheritance chain.
- `rules` and `skills` are the agent's; `rules` lists the automatic rules first (`automatic: true`).
- `parameters` gives each parameter with the value the step uses and its `source` (`STEP`, `TEMPLATE`, `DEFAULT`,
  `NONE`; a parameter the step binds `START` or `ARTIFACT` has no value here, the run sets it).
- `runInputs` lists the parameters the step binds `START`, as start form fields (here none: no step overrides).
- `instructions` is the merged text (lineage, then the step's append or replacement); the environment document and the
  standard data sections are added by the Projects context at run time.
- `check.problems` lists every skill and rule the agent names that does not exist, an agent that does not fit the
  template and a required parameter without a value (section 6). The preview still returns the merged task, but the
  `TaskCatalog` port refuses to execute it.
- `check.warnings` (ADR 0060) lists what does not block, for example a skill of the template's `requiresSkills` the
  agent lacks.
- `abstract: true` means the same: previewable, not executable.
- `ports` is always `success`, `failure`.
- `agent` is always set: the step's, else `opencode` when the template needs a coding agent, else `model-answer`
  (ADR 0061). Without step overrides the `rules` and `skills` are therefore those of that default agent.

The endpoint answers `404` for an unknown key and `400` for a cycle or a missing parent.

## 8. How workflow nodes reference templates

A `TASK` node in a workflow holds a `templateKey` and optional `TaskOverrides`, never a prompt. At run
time the Projects context calls `TaskCatalog.resolve(templateKey, projectId, overrides)`; the overrides
are applied last, after the whole chain:

| Override | Effect |
|---|---|
| `agentKey` | the agent that runs this step (section 6); empty: `opencode` when the template needs a coding agent, else `model-answer` |
| `parameterBindings` | per parameter (section 2), where its value comes from: `{"source": "VALUE", "value": ...}`, `{"source": "START"}` or `{"source": "ARTIFACT", "artifact": "<name>"}` (ADR 0062; the former `parameters` map is read as `VALUE` bindings) |
| `modelProfileId` | the model of this step; empty: the agent's, else the defaults |
| `instructionsAppend` | notes added after the merged instructions (UI **Instructions for this step** → **Append**); the former `systemPromptAppend` / `promptAppend` are read as this |
| `instructionsReplace` | replaces the merged instructions for this step (UI **Replace (advanced)**; wins over a parameterised instruction; the data sections are still added); the former `promptReplace` is read as this |
| `config` | deep-merged with the same null-deletes rule; `codingAgent`, `verifyCommand`; `allowQuestions` / `maxQuestionRounds` the agent questions (ADR 0060); the former keys `directory`, `session` and `continueSession` are the agent's since ADR 0062 and dropped on read; the git keys, `repositories`, `setupCommand` and `phase` left the step with ADR 0063 (read compatibly, [designing-a-workflow.md](designing-a-workflow.md#before-adr-0063)); `agentTools` left with ADR 0064 (tools are the agent's connectors, section 6a) |

The designer validates a node's `templateKey` against the catalog (error when unknown, warning when
abstract), required parameters and their bindings; every task node has the ports `success` and `failure`.
Because nodes reference keys, editing a template changes every workflow using it on its next run; shadow it per
project when only one project should change.

Work without a model is not a template: an `ACTION` node holds `action.type` (`FETCH_ISSUE`, `COMMENT_ISSUE`,
`TRANSITION_ISSUE`, `DELIVER_CHANGES`, `START_WORKFLOW`, `PROJECT_DIGEST`) and `action.config`; the palette (`actions`) gives each its
config schema, defaults, run inputs, ports and required connection capabilities. A stored `TASK` node naming a former
action template (`fetch-issue`, `notify-jira`, `transition-issue`, `start-workflow`, `project-digest`) is read as
that action with the former template defaults, the step's `config` and its `instructionsReplace` (formerly
`promptReplace`) as the comment message;
the next save writes the action.

## 9. SDLC templates, skills and rules (ADR 0019)

Every built-in agent lists two rules: `agent-ground-rules` (evidence, scope and safety: re-read a file before
claiming anything about it, paths as evidence, a search hit outside the task is not a task, narrowest edit, report
contradictions) and `ask-dont-guess` (source hierarchy; one open-question marker per context,
`[NEEDS CLARIFICATION: ...]` in feature specifications (`write-spec`) and `[?] question` elsewhere, never an invented owner or date;
TODO/TBD with a reason; append, do not rewrite; never touch status fields). Leave them out of your own agent if a task
must not see them. Until ADR 0055 they were skills; `V303__rules_and_categories.sql` moved them, together with the
example rules `kotlin-hexagonal` and `angular-ng-zorro`, into rules. The two stack rules describe the Back Office code
base: copy them into a project rule for your stack and make that one automatic, or list them on a project agent.

| Template | Agent (chosen on the step; pre-selected by the required skills) | Use it for | Output |
|---|---|---|---|
| `impact-analysis` | `analyst` | a change request arrives as an issue | JSON with description, business justification, impact on artefacts/components/new stories/specification, estimate, options, recommendation, open questions, `confidence` |
| `write-user-story` | `analyst` | turn the issue into a story with falsifiable acceptance criteria ("System: 1. displays ..., 2. validates ...") | artefact `user-story` (the markdown of the schema field `user-story`; `openQuestions` and `confidence` in the answer) |
| `write-use-case` | `analyst` | expand an accepted story into flows A, A1.., rules RB-xx, postconditions, permissions | artefact `use-case` |
| `write-adr` | `analyst` | draft a decision record (status Proposed, at least two options) | artefact `adr` (markdown) |
| `plan-task-list` | `analyst` | break the plan / story into tasks for an autonomous agent | artefact `tasks`: `[{title, description, verification, test_command, completed: false}]`, always ending with "reformat, lint, and test" |
| `implement-task-list` | `opencode` | execute that list with TDD, stop on the first red `test_command` (the runner commits once; the work log ends with `RESULT: GREEN` or `RESULT: STOPPED at <task>`, which `verifyCommand` checks) | `diff`, `mr`, artefact `work-log` with a "Noticed out of scope" section |
| `review-against-spec` | `reviewer` | review the diff against story, spec and task list | `review` with per-requirement coverage (MET/PARTIAL/MISSING) plus findings |

The skills these templates need (`definition-of-ready-done`, `user-story-format`, `use-case-format`, `adr-format`,
`build-guardrails`, `security-review-checklist`) come from the step's agent; the templates list them in
`requiresSkills`, so the designer pre-selects `analyst`, `reviewer` or `opencode`. A human gate after `write-user-story` or
`plan-task-list` with a DoR checklist (ADR 0017) is the usual way to stop a run on low `confidence`. The "Change
request (delta)" workflow template chains the **Fetch issue** action → `impact-analysis` → `write-user-story` →
`plan-task-list` (which reads the approved impact analysis) → `implement-task-list`; after the AI review the rework
message carries the `review` artefact.

## Where to look when it fails

| Symptom | Where to look |
|---|---|
| `403` on a library call | the caller lacks the permission of the table in section 1 ("Who may do what"); the audit log has an `ACCESS_DENIED` row with the permission |
| a project member does not see global templates | they hold `project.view` on the project but not `library.view` (normally granted by the SYSTEM `member` role) |
| a built-in on an old installation does not get the shipped changes | it was edited (version > 1, or saved by a person), so the seed leaves it alone; compare it with a fresh installation and copy the changes by hand |
| a step reports that it needs a coding agent | the agent does not fit the template (section 6): choose OpenCode or Claude Code (or your coding agent) for a template with `codingAgent: true` (**Works in the repository**) or `prepareOnly` |
| a step reports `parameter X needs a value` | a required parameter has no value on the step, in the template chain or as a default and is not bound at run time: give it a **Value**, choose **Ask at start** or **Result of an earlier step** in the step's **Parameters**, or use a preset |
| a step reports `an ARTIFACT binding needs the name of the artefact` | a parameter bound **Result of an earlier step** has no artefact name: type the name an earlier step produces |
| a coding agent step with an `outputSchema` fails with "wrote no structured-answer.json" | the agent did not write the file or wrote invalid JSON; tighten the instructions, or use a model answer agent |
| `400` with `extendsKey: inheritance cycle ...` | the message prints the chain; break it at the template you are saving |
| `400` with `outputSchema: not well-formed JSON at offset N` | the schema is checked as raw JSON; escape quotes when sending it as a string |
| `400` with `skills[i]: skill X does not exist in scope ...` or `rules[i]: rule X does not exist in scope ...` on an agent | the key is wrong, the entry is in another project's scope, or names the other kind of entry (for example a former skill that is now a rule): move it between `skills` and `rules` |
| `409` on delete | built-in template, a template another one extends, or a skill or rule an agent lists; the message names the referrers |
| a step shows **Unknown agent** in "Agents in this workflow" | the agent it names was deleted or is in another project's scope: choose another agent on the step |
| `409` from the coherence check | no usable platform default model for model answers: an administrator sets it under **Models**, then try again |
| resolve shows the global template although a project one exists | the `projectId` query parameter is missing, or the project template's key differs |
| `check.warnings` on a step | the template's `requiresSkills` names a skill the step's agent lacks: add it to the agent ("Add to agent" in the designer's "Agents in this workflow") or choose another agent (the step still runs) |
| a template's output file does not appear as an artefact | the glob in **Output files** does not match the path the agent wrote (relative to the primary repository; `**` for any directories), or the file is over the size cap; the artefact is named like the file without extension |
| a rule does not reach a step | the step's agent does not list it and it is not automatic, or the project has a non-automatic copy of a global automatic rule |
| a coding step does not continue an earlier conversation | the session is the agent's (ADR 0062): **Per step** continues only the repeats of one step, **One per run** all steps of that agent, **Always fresh** nothing; an agent with **Separate directory** never continues |
| an agent's connector (tool) is not available in the job | the agent does not list the key, or it is defined neither in the project nor on the platform (step log `connector <key> is defined neither in this project nor on the platform; skipped`, audit finding `UNKNOWN_CONNECTOR`); an MCP connector with auth `BEARER` / `HEADER` has no secret (step log, or `[connector] WARN <key>: no secret arrived` in the runner log); the sandbox egress policy does not allow its host (**Egress denied** on the job; **Test** passes because it runs from Back Office); or the installed Claude Code has no `--mcp-config` (`[mcp] WARN` in the runner log) |
| a step with a `GITLAB_READ` connector fails before the job | the repository has no verified read-only token with `CI_READ`: add one on the project's **Sources** tab (role Reporter, `read_api`, `read_repository`) and test it |
| a skill or rule edit does not show up in the runner | the run started before the edit; skills and rules are snapshotted at resolution time |
| the built-in library is missing after a fresh deploy | `backoffice.tasks.seed-builtin` is `false`, or the startup log lacks `seeded task library ...` because Flyway `V300__task_library.sql` failed; check the `task_template` table |
| ArchUnit fails after touching `tasks-core` | the core module may only import `pl.mos.backoffice.tasks.*`, `pl.mos.backoffice.shared.*` and the standard library |
