<p align="center"><img src="docs/ui/logo.svg" width="96" height="96" alt=""></p>

# Back Office — SDLC Control Room for a one-person engineering team

Back Office is a self-hosted application that lets a single technical owner (architect, developer,
reviewer and operator in one) run their whole software delivery life cycle from one place, with AI
agents doing the routine work and the human stepping in only when a decision is genuinely theirs
("man when needed", not "man in the loop").

Two capabilities are first class:

| Capability | What it does |
|---|---|
| **Projects & Workflows** | Design per-project processes on a canvas: blocks are agent tasks from a reusable Task Library (templates with `extends`/`implements`, skills), plus decisions, human gates and CI gates. Proven workflows are published to a shared Workflow Library, adopted by other projects as linked copies and measured per version (success and first-pass rate, time, cost). Runs are durable, pause at gates and resume from an inbox. Next to this team mode, anyone can automate their own work: private projects and personal Jira rules that propose a run for every new issue assigned to them, started only when they click Start. The project CI calls Back Office through `backoffice-ci.sh` with a project token to run code review or any configured workflow and passes or fails accordingly; Back Office never calls the SCM API. |
| **Model Evaluation** | Maintain a catalogue of models from any vendor, define task suites of different classes and difficulty (coding tasks executed in a real agent harness, analytical/specification tasks judged by a rubric), run benchmarks and compare quality against real cost. |

## Built on proven building blocks, not home-grown engines

| Concern | Choice | Why |
|---|---|---|
| Multi-vendor LLM access | [Spring AI 2.0](https://docs.spring.io/spring-ai/reference/) | One `ChatModel` abstraction for Anthropic, OpenAI, Google Gemini, Mistral, Ollama, Bedrock and any OpenAI-compatible endpoint; token usage and Micrometer observability out of the box. |
| Durable agent workflows with human and CI gates | [LangGraph4j 1.9](https://github.com/langgraph4j/langgraph4j) | State-graph engine with Postgres checkpoints and native `interruptBefore`; no extra workflow server. |
| Workflow canvas | [ngx-vflow](https://github.com/artem-mangilev/ngx-vflow) | Angular-native n8n-style canvas for the designer. |
| Spec-driven development | [GitHub Spec Kit](https://github.com/github/spec-kit) | Standard `constitution → specify → plan → tasks → implement` artefacts instead of a bespoke doc format. |
| Coding agents | [OpenCode](https://opencode.ai) (provider-agnostic) and Claude Code inside a Kubernetes Job | Same harness for every model makes benchmarks fair and keeps agents sandboxed. |
| LLM tracing & cost analytics | [Langfuse](https://langfuse.com) (optional, self-hosted) via OpenTelemetry | Every model call is traced with tokens and cost; the app keeps its own usage ledger too. |
| Issue tracking / CI | Jira REST; any CI through the served `backoffice-ci.sh` | Merge requests are opened by the runner with git push options; CI jobs call this API with project tokens. |

## Repository layout

```
backend/    Kotlin · Spring Boot 4.1 · hexagonal architecture (Gradle multi-module)
frontend/   Angular 21 · NG-ZORRO (Ant Design)
runner/     Container image for coding agents (OpenCode, Claude Code, Spec Kit, git)
deploy/     Helm chart `back-office` (app + PostgreSQL, optional Langfuse)
ci/            backoffice-ci.sh - the CI client served by the API (ADR 0013) and its tests
ci-templates/  GitLab CI template: MR review gate and CI signals through backoffice-ci.sh
docs/       Architecture, ADRs, API contract, guides and runbooks
```

## Documentation

Start at [docs/README.md](docs/README.md). The most useful entry points:

- [Architecture overview](docs/architecture/01-overview.md) — context, containers, hexagonal layering
- [Domain model](docs/architecture/02-domain-model.md)
- [Pipelines & agents](docs/architecture/03-pipelines-and-agents.md) — how a pipeline compiles into a LangGraph, human gates, runner protocol
- [Model evaluation](docs/architecture/04-model-evaluation.md)
- [API contract (OpenAPI)](docs/api/openapi.yaml)
- [Architecture decision records](docs/adr/)
- [Local development](docs/guides/local-development.md) · [Deploying with Helm](docs/guides/deploy-helm.md) · [Designing a workflow](docs/guides/designing-a-workflow.md) · [Task library](docs/guides/task-library.md) · [Running a benchmark](docs/guides/running-a-benchmark.md) · [CI integration](docs/guides/ci-integration.md)

## Quick start (local)

```bash
# 1. database
docker compose -f deploy/compose/docker-compose.yml up -d postgres

# 2. backend (JDK 21)
cd backend && ./gradlew :app:bootRun

# 3. frontend
cd frontend && npm install && npm start      # http://localhost:4200, proxies /api to :8080
```

Set at least `BACKOFFICE_API_KEY` and one provider key (for example `ANTHROPIC_API_KEY`).
All configuration is described in [docs/guides/configuration.md](docs/guides/configuration.md).

## Licence

[Apache License 2.0](LICENSE).
