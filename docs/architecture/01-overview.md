# 01 — Architecture overview

## Purpose

One person owns architecture, implementation, quality and deployment for a set of software projects.
Back Office turns that person's process into executable pipelines run by AI agents and surfaces only
the decisions that need a human. A second capability, model evaluation, keeps the choice of models
grounded in measured quality and cost on the owner's own kind of work.

## System context (C4 level 1)

```
                          +----------------------------+
   Owner (browser) ------>|        Back Office         |<------ project CI jobs (backoffice-ci.sh)
                          |  web UI + REST API + engine|
                          +------+------+------+-------+
                                 |      |      |
             +-------------------+      |      +--------------------+
             v                          v                           v
   LLM providers                  Kubernetes                  Jira (issues) . git hosts (runner push only)
   (Anthropic, OpenAI, Google,    (agent runner Jobs)
    Mistral, Ollama, OpenAI-compatible)
                                 |
                                 v
                          Langfuse (optional tracing)
```

## Containers (C4 level 2)

| Container | Technology | Responsibility |
|---|---|---|
| `web` | Angular 21, NG-ZORRO, ngx-vflow, nginx | Single-page UI: dashboard & inbox, projects, workflow canvas designer, runs, CI executions, task library, model catalogue, task suites, benchmarks, costs |
| `api` | Kotlin, Spring Boot 4.1, Spring AI 2.0, LangGraph4j 1.9 | REST API, workflow engine, task library, CI endpoints and client script, benchmark engine, usage ledger |
| `runner` | Container image: OpenCode, Claude Code, Spec Kit, git, JDK/Node toolchains | Executes one coding/spec task inside a Kubernetes Job and reports back over HTTP |
| `postgres` | PostgreSQL 16 | Application data and LangGraph4j checkpoints |
| `langfuse` (optional) | Langfuse Helm chart | Trace and cost analytics for every LLM call, fed over OTLP |

## Hexagonal architecture

Each bounded context is a Gradle module pair: a **core** module (domain + application layers, pure
Kotlin, no framework dependencies) and an **adapters** module (driving adapters such as REST
controllers, driven adapters such as JPA repositories, Spring AI, LangGraph4j, fabric8, gitlab4j).
The `app` module boots Spring, wires configuration and hosts cross-cutting concerns (security,
Flyway, observability) and the ArchUnit tests that enforce the layering.

```
                 driving adapters                       driven adapters
     +--------------------------+          +------------------------------------+
     | REST controllers (MVC)   |          | JPA repositories (PostgreSQL)      |
     | SSE event stream         |  ports   | Spring AI LLM gateway              |
     | Runner callback endpoint |--------->| LangGraph4j workflow engine        |
     | CI executions & signals  |  in/out  | fabric8 Kubernetes runner          |
     +----------+---------------+          | SpEL expressions . Jira REST       |
                v                          +------------------------------------+
        +--------------------------------------------+
        | application: use cases (port.in) + ports   |
        | domain: aggregates, value objects, events  |
        +--------------------------------------------+
```

Rules (enforced by ArchUnit in `backend/app/src/test`):

1. `*-core` modules depend only on `shared-kernel` and the Kotlin/Java standard library.
2. Domain classes do not depend on application classes; application classes do not depend on adapters.
3. Adapters depend on their own context's core and on `shared-kernel`; adapters of one context never
   depend on adapters of another context. Cross-context calls go through a port implemented in `app`.
4. Only `app`, `llm-gateway` and `*-adapters` may reference Spring.

## Module map

```
backend/
  shared-kernel/          Ids, Money, Clock, DomainEvent, LlmGateway port & ModelRef, UsageRecord
  tasks-core/             Task Library: templates (extends/implements), interfaces, skills, resolver
  tasks-adapters/         REST, JPA, TaskCatalog port implementation, seed
  projects-core/          Projects & Workflows: graph model, runs, approvals, CI executions (use cases, ports)
  projects-adapters/      REST, JPA, LangGraph4j engine, SpEL expressions, Jira, CI token auth, SSE
  evaluation-core/        Model catalogue, task suites, benchmarks: domain + application
  evaluation-adapters/    REST, JPA, benchmark executor, leaderboard queries
  llm-gateway/            Spring AI implementation of LlmGateway (all providers), usage ledger (JPA) and /usage API
  agent-runner/           AgentRunner port: Kubernetes Job (fabric8) and docker-run implementations, callback endpoint
  app/                    Spring Boot application, security, Flyway, observability, dashboard, ArchUnit tests
```

Shared ports (in `shared-kernel`) and who implements them:

| Port | Implemented in | Used by |
|---|---|---|
| `LlmGateway` | `llm-gateway` | projects (LLM steps, code review), evaluation (attempts, judge) |
| `UsageLedger` | `llm-gateway` | gateway itself, runner adapter, usage API, dashboards |
| `ModelCatalog` | `evaluation-adapters` | projects (resolve step model), gateway (pricing) |
| `TaskCatalog` | `tasks-adapters` | projects (resolve TASK nodes, designer palette) |
| `AgentRunner` | `agent-runner` | projects (SPEC_KIT, IMPLEMENT steps), evaluation (CODING_JOB attempts) |
| `Clock`, `EventPublisher` | `app` | everyone |

Flyway version ranges: `V1..V49` llm-gateway (usage ledger), `V50..V99` agent-runner, `V100..V199` projects,
`V200..V299` evaluation, `V300..V399` tasks. LangGraph4j creates its own checkpoint tables.

## Bounded contexts

| Context | Aggregates | Owns |
|---|---|---|
| **Projects & Workflows** | Project, ProjectCiToken, WorkflowDefinition, WorkflowRun, ApprovalRequest, CiExecution | Everything about delivering work for a project |
| **Task Library** | TaskTemplate, TaskInterface, Skill | What agents do and how they are prompted (ADR 0015) |
| **Model Evaluation** | ModelProfile, TaskSuite, Benchmark | Which models exist, what they cost, how good they are |
| **Shared kernel** | UsageRecord (ledger), ModelRef, Money | Cost accounting used by both |

The Projects context refers to models only by `ModelProfileId` and resolves them through the
`ModelCatalog` port, implemented in `app` by delegating to the Evaluation context. This keeps the
two contexts independently evolvable while sharing one model catalogue.

## Runtime view: one pipeline run

1. Owner (or a Jira automation via API) starts a run of a pipeline for issue `PROJ-123`.
2. `StartPipelineRun` creates a `PipelineRun` (status `RUNNING`) and hands it to the
   `PipelineEngine` port. The LangGraph4j adapter compiles the definition into a `StateGraph`,
   registers `interruptBefore` for every `HUMAN_GATE` step and starts streaming on a virtual thread.
3. Each node maps to a step executor: fetch the issue (Jira), run an LLM step (Spring AI), run a
   Spec Kit phase or implementation in a runner Job (Kubernetes), open an MR (GitLab), run an AI
   code review, and so on. Every step writes a `StepExecution`, artefacts and usage records.
4. At a gate the graph checkpoints to Postgres and the run becomes `WAITING_FOR_HUMAN`; an
   `ApprovalRequest` appears in the inbox. Nothing else runs for this thread.
5. The owner approves, rejects or asks for revision. `DecideApproval` resumes the graph with
   `GraphInput.resume(decision)`; the routing edge after the gate reads the decision.
6. On the last node the run is `SUCCEEDED`; the thread is released. On unhandled failure it is
   `FAILED` with the failing step recorded and can be retried from that step.

## Technology stack and versions

| Component | Version |
|---|---|
| JDK | 21 |
| Kotlin | 2.3 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| LangGraph4j | 1.9.1 |
| fabric8 kubernetes-client | 7.9 |
| gitlab4j-api | 6.3 |
| springdoc-openapi | 3.1 |
| PostgreSQL | 16 |
| Angular / NG-ZORRO | 21 / 21 |
| Helm chart apiVersion | v2 |
