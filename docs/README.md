# Documentation index

Documentation is written in English so that both the owner and the coding agents working on this
repository read the same source of truth.

## Architecture

| Document | Content |
|---|---|
| [01-overview.md](architecture/01-overview.md) | System context, containers, hexagonal layering, module map, technology stack |
| [02-domain-model.md](architecture/02-domain-model.md) | Bounded contexts, aggregates, value objects, domain events, state machines |
| [03-pipelines-and-agents.md](architecture/03-pipelines-and-agents.md) | Workflow graph (nodes, ports, decisions), CI gates, compilation to LangGraph4j, task executors, step repositories (multi-repository projects, ADR 0034), agent runner protocol, Spec Kit |
| [04-model-evaluation.md](architecture/04-model-evaluation.md) | Model catalogue, task suites, benchmark execution, scoring, cost accounting |
| [05-integrations.md](architecture/05-integrations.md) | CI client script and tokens, merge requests via push options, Jira least-privilege connections (encrypted secrets, capability selection, verification, expiry reminders), Jira intake watcher, approval before external writes, LLM providers, Langfuse/OpenTelemetry |
| [09-scale-and-operations.md](architecture/09-scale-and-operations.md) | Roles, PostgreSQL work queue and events, shared limits, object storage, observability, SLOs, capacity, upgrades |
| [10-agent-execution.md](architecture/10-agent-execution.md) | Threat model, runner fleet protocol v2, Agent Sandbox backends, sandbox policy, egress proxy, credential brokering, multi-repository workspace |
| [11-governance.md](architecture/11-governance.md) | Budgets, delivery insights, provenance attestations, MCP server |
| [12-prompt-injection.md](architecture/12-prompt-injection.md) | Prompt-injection threat model, data blocks, review guards, credential isolation of the coding agent, MCP gate opt-in |
| [08-identity-and-access.md](architecture/08-identity-and-access.md) | Users, OIDC login, sessions, permission catalogue, roles, grants, project memberships, gate approver rules, tokens, audit log |
| [07-task-library.md](architecture/07-task-library.md) | Task templates with inheritance, interfaces, Agent Skills, resolution rules, seeded library |
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
| [designing-a-workflow.md](guides/designing-a-workflow.md) | Building a process on the canvas: nodes, ports, decisions, gates, CI waits, task overrides |
| [task-library.md](guides/task-library.md) | Creating templates, interfaces and skills; specialising per project |
| [running-a-benchmark.md](guides/running-a-benchmark.md) | Comparing models on real work |
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
