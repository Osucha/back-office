# Documentation index

Documentation is written in English so that both the owner and the coding agents working on this
repository read the same source of truth.

## Architecture

| Document | Content |
|---|---|
| [01-overview.md](architecture/01-overview.md) | System context, containers, hexagonal layering, module map, technology stack |
| [02-domain-model.md](architecture/02-domain-model.md) | Bounded contexts, aggregates, value objects, domain events, state machines |
| [03-pipelines-and-agents.md](architecture/03-pipelines-and-agents.md) | Workflow graph (nodes, ports, decisions), CI gates, compilation to LangGraph4j, how task and action nodes run (agents, the agent's environment, the run directory and agent sessions, agent questions, structured answers of coding agents; ADR 0056, 0057, 0060; connectors at a step, ADR 0064; the agent audit and coherence check, ADR 0061; the task message, parameter bindings and the run start form, ADR 0062), workflow sources and the Deliver changes action (multi-repository projects, agents never push; ADR 0034, 0063), workflow library (ADR 0037), agent runner protocol |
| [04-model-evaluation.md](architecture/04-model-evaluation.md) | Model catalogue, task suites, benchmark candidates (agents with models, ADR 0059), benchmark execution, scoring, cost accounting, cost of an accepted result |
| [05-integrations.md](architecture/05-integrations.md) | CI client script and tokens, merge requests via push options, Jira least-privilege connections (encrypted secrets, capability selection, verification, expiry reminders), Jira intake watcher, personal intake rules (ADR 0038), approval before external writes, LLM providers, Langfuse/OpenTelemetry |
| [09-scale-and-operations.md](architecture/09-scale-and-operations.md) | Roles, PostgreSQL work queue and events, shared limits, object storage, observability, SLOs, capacity, upgrades |
| [10-agent-execution.md](architecture/10-agent-execution.md) | Threat model, runner fleet protocol v2, Agent Sandbox backends, sandbox policy, egress proxy, credential brokering (repository tokens and connector secrets, ADR 0051, 0064), multi-repository workspace, run directory, agent home and access enforcement (ADR 0060) |
| [11-governance.md](architecture/11-governance.md) | Budgets, delivery insights, workflow metrics (ADR 0037), agent insights and the cost of an accepted result (ADR 0059), provenance attestations, MCP server |
| [12-prompt-injection.md](architecture/12-prompt-injection.md) | Prompt-injection threat model, data blocks, review guards, credential isolation of the coding agent, connectors as a tool and trust surface (ADR 0064), MCP gate opt-in |
| [08-identity-and-access.md](architecture/08-identity-and-access.md) | Users, OIDC login, sessions, permission catalogue, roles, grants, project memberships, gate approver rules, tokens, audit log |
| [07-task-library.md](architecture/07-task-library.md) | Task templates (only the work: no agent, no model; needs a coding agent, required skills; ADR 0061; one instruction text, parameters bound by the step, no run inputs, no sandbox policy; ADR 0062; output files, no git settings; ADR 0063) with inheritance, categories and parameters (ADR 0058), Agent Skills, rules, agents (who and how: harness, model, instructions, rules, skills, connectors, access, directory and session; the environment document and the agent home; ADR 0056, 0057, 0060, 0062, 0064), connectors (the tools agents use: MCP servers with credentials, read-only GitLab; ADR 0064), resolution rules, seeded library |
| [06-security.md](architecture/06-security.md) | Authentication, secrets, sandboxing of agents |
| [guardrails.md](architecture/guardrails.md) | Build guardrail registry (GR-*) with the negative test that proves each rule fails |
| [conventions.md](architecture/conventions.md) | Coding conventions, hexagonal rules enforced by ArchUnit, module ownership, testing strategy |

## UI

[ui/design-language.md](ui/design-language.md) — the Relay design language: principles, baton states, tokens, signature components, layout, page patterns, writing rules and performance budgets.

[ui/i18n-glossary.md](ui/i18n-glossary.md) — the Polish UI glossary and style rules (ADR 0026); conventions for translating the UI are in the frontend guide, "Internationalisation".

## Decisions (ADR)

See [adr/](adr/). Every non-obvious technology or structure choice has a record with context,
decision, alternatives and consequences.

## API

[api/openapi.yaml](api/openapi.yaml) is the contract between frontend, backend, the GitLab CI job and
the agent runner. The backend also serves it live at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

## Guides

| Guide | For |
|---|---|
| [local-development.md](guides/local-development.md) | Running everything on a laptop |
| [configuration.md](guides/configuration.md) | Every environment variable and Helm value |
| [deploy-helm.md](guides/deploy-helm.md) | Installing on Kubernetes |
| [designing-a-workflow.md](guides/designing-a-workflow.md) | Building a process on the canvas: nodes, ports, decisions, gates, CI waits, task overrides (instructions for the step, parameter sources), what the agent is told, the run start form, the workflow's sources and delivering changes (ADR 0063), the run directory, agent sessions and agent questions; choosing agents and the panel Agents in this workflow (ADR 0061); the default workflows and comparing workflows and agents against the reference workflow |
| [task-library.md](guides/task-library.md) | Creating templates (only the work: one instruction text, with parameters, presets, required skills and output files), agents (instructions, rules, skills, connectors, access, directory, session), connectors (MCP servers with credentials, read-only GitLab; ADR 0064), skills and rules; specialising per project |
| [running-a-benchmark.md](guides/running-a-benchmark.md) | Comparing agents and models (candidates) on real work |
| [authentication-and-users.md](guides/authentication-and-users.md) | First administrator, inviting users, groups, roles, grants, project members, tokens, sessions, audit log, other OIDC providers |
| [ci-integration.md](guides/ci-integration.md) | Issuing a CI token, wiring `backoffice-ci.sh` into a project pipeline, entrypoints, signals |
| [code-quality.md](guides/code-quality.md) | SonarQube quality gate (GR-Q3): CI variables, local analysis, reading a red gate |

## Runbooks

| Runbook | Situation |
|---|---|
| [stuck-pipeline-run.md](runbooks/stuck-pipeline-run.md) | A run is neither progressing nor waiting for you |
| [runner-job-failed.md](runbooks/runner-job-failed.md) | A Kubernetes agent job failed or never reported back |
| [cannot-log-in.md](runbooks/cannot-log-in.md) | Login loops, not-invited, redirect URI, cookies behind TLS, CSRF 403, expired sessions |
| [keycloak-realm.md](runbooks/keycloak-realm.md) | Realm export/import, client secret rotation, adding users |
| [provider-errors.md](runbooks/provider-errors.md) | Rate limits, auth failures, model not found |
