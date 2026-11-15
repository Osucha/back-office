# Documentation index

Documentation is written in English so that both the owner and the coding agents working on this
repository read the same source of truth. Where a decision is recorded, the documents link the ADR.

## Architecture

| Document | Content |
|---|---|
| [01-overview.md](architecture/01-overview.md) | System context, containers, hexagonal layering, module map, technology stack |
| [02-domain-model.md](architecture/02-domain-model.md) | Bounded contexts, aggregates, value objects, domain events, state machines |
| [03-pipelines-and-agents.md](architecture/03-pipelines-and-agents.md) | Workflow graph and its compilation to LangGraph4j; how task and action nodes run, agents at a step, workflow sources and the Deliver changes action, workflow library |
| [04-model-evaluation.md](architecture/04-model-evaluation.md) | Model catalogue, task suites, benchmark candidates, execution, scoring and cost accounting |
| [05-integrations.md](architecture/05-integrations.md) | CI client and tokens, merge requests via push options, Jira connections and intake, approval before external writes, LLM providers, Langfuse |
| [06-security.md](architecture/06-security.md) | Authentication, secrets, sandboxing of agents |
| [07-task-library.md](architecture/07-task-library.md) | Task templates, skills, rules, agents and connectors; inheritance and resolution rules; the seeded library |
| [08-identity-and-access.md](architecture/08-identity-and-access.md) | Users, OIDC login, sessions, permissions, roles, grants, project memberships, tokens, audit log |
| [09-scale-and-operations.md](architecture/09-scale-and-operations.md) | Roles, PostgreSQL work queue and events, shared limits, object storage, observability, SLOs, upgrades |
| [10-agent-execution.md](architecture/10-agent-execution.md) | Threat model, runner fleet protocol, sandbox backends and policy, egress proxy, credential brokering, run directory |
| [11-governance.md](architecture/11-governance.md) | Budgets, delivery and agent insights, workflow metrics, provenance attestations, MCP server |
| [12-prompt-injection.md](architecture/12-prompt-injection.md) | Prompt-injection threat model, data blocks, review guards, credential isolation, connectors as a trust surface |
| [conventions.md](architecture/conventions.md) | Coding conventions, hexagonal rules enforced by ArchUnit, module ownership, testing strategy |
| [guardrails.md](architecture/guardrails.md) | Build guardrail registry (GR-*) with the negative test that proves each rule fails |
| [performance.md](architecture/performance.md) | Query review and load-test baseline, and how to reproduce it |

## Decisions (ADR)

See [adr/](adr/). Every non-obvious technology or structure choice has a record with context,
decision, alternatives and consequences.

## API

[api/openapi.yaml](api/openapi.yaml) is the contract between frontend, backend, the CI client and
the runner fleet. The backend also serves it live at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

## Guides

| Guide | For |
|---|---|
| [local-development.md](guides/local-development.md) | Running everything on a laptop: the one-step compose stack or the parts by hand |
| [frontend-development.md](guides/frontend-development.md) | Changing the Angular UI: pages, API types, i18n, building the web image |
| [configuration.md](guides/configuration.md) | Every environment variable and Helm value |
| [deploy-helm.md](guides/deploy-helm.md) | Installing Back Office on Kubernetes |
| [cloud-reference.md](guides/cloud-reference.md) | Reference production setups on AWS, Google Cloud, Azure and on-prem |
| [runner-fleet-install.md](guides/runner-fleet-install.md) | Installing a runner pool (the `back-office-runner` chart) in a cluster |
| [runner-fleet.md](guides/runner-fleet.md) | Operating the runner fleet: pools, registration, sandbox policies, budgets |
| [designing-a-workflow.md](guides/designing-a-workflow.md) | Building a process on the canvas: nodes, gates, CI waits, agents at a step, delivering changes |
| [task-library.md](guides/task-library.md) | Creating templates, agents, connectors, skills and rules; specialising per project |
| [running-a-benchmark.md](guides/running-a-benchmark.md) | Comparing agents and models on real work |
| [authentication-and-users.md](guides/authentication-and-users.md) | First administrator, inviting users, roles, grants, tokens, audit log, OIDC providers |
| [ci-integration.md](guides/ci-integration.md) | Issuing a CI token, wiring `backoffice-ci.sh` into a pipeline, entrypoints, signals |
| [provenance.md](guides/provenance.md) | Verifying signed provenance attestations offline (with [verify-attestation.mjs](guides/verify-attestation.mjs)) |
| [code-quality.md](guides/code-quality.md) | SonarQube quality gate: CI variables, local analysis, reading a red gate |

## Runbooks

| Runbook | Situation |
|---|---|
| [stuck-pipeline-run.md](runbooks/stuck-pipeline-run.md) | A run is neither progressing nor waiting for you |
| [agent-jobs.md](runbooks/agent-jobs.md) | An agent job is queued, lost or failed, or delivering changes (git push, merge request) failed |
| [work-queue.md](runbooks/work-queue.md) | Background work items stay ready or die, leases expire, the LISTEN bridge is down |
| [scaling.md](runbooks/scaling.md) | Slow API, stalled queue, runners missing, database connections, KEDA |
| [upgrade.md](runbooks/upgrade.md) | Upgrades, failed migrations, rollback, signing key rotation |
| [provider-errors.md](runbooks/provider-errors.md) | LLM provider rate limits, auth failures, model not found |
| [cannot-log-in.md](runbooks/cannot-log-in.md) | Login loops, not invited, redirect URI, cookies behind TLS, CSRF 403, expired sessions |
| [keycloak-realm.md](runbooks/keycloak-realm.md) | Realm export/import, client secret rotation, adding users |

## UI

| Document | Content |
|---|---|
| [design-language.md](ui/design-language.md) | The Relay design language: principles, tokens, components, page patterns, writing rules, performance budgets |
| [i18n-glossary.md](ui/i18n-glossary.md) | Polish UI glossary and style rules for translating the UI |

## Quality

| Document | Content |
|---|---|
| [sonarqube-baseline.md](quality/sonarqube-baseline.md) | SonarQube measurements of existing code when the quality gate was introduced |

## User manual

[manual/](manual/) is the in-app user manual (English; Polish in [manual/pl/](manual/pl/)), shown as the
help of the web app.
