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
             v                          ^                           v
   LLM providers                  runner-controller           Jira (issues) . git hosts (runner push only)
   (Anthropic, OpenAI, Google,    (pulls agent jobs over HTTP,
    Mistral, Ollama,               runs them in sandboxes:
    OpenAI-compatible)             Agent Sandbox / Kubernetes Job / docker)
                                 |
                                 v
                          Langfuse (optional tracing)
```

## Containers (C4 level 2)

| Container | Technology | Responsibility |
|---|---|---|
| `web` | Angular 21, NG-ZORRO, ngx-vflow, nginx | Single-page UI: dashboard & inbox, projects, workflow canvas designer, runs, CI executions, task library, model catalogue, task suites, benchmarks, costs |
| `api` / `worker` / `migrate` | Kotlin, Spring Boot 4.1, Spring AI 2.0, LangGraph4j 1.9 | One application image whose process plays one or more roles (`backoffice.role`): `api` serves the REST API, the BFF login and the runner protocol; `worker` takes work items from the PostgreSQL work queue (workflow runs, benchmark attempts, maintenance); `migrate` runs Flyway and exits |
| `runner-controller` | Kotlin, Spring Boot, fabric8 (deployed separately, per cluster or on a person's computer) | Registers with Back Office, pulls agent jobs over HTTP and runs each in its own sandbox (Agent Sandbox, Kubernetes Job or docker); reports status and results back over HTTP |
| `runner` | Container image: OpenCode, Claude Code, git, JDK/Node toolchains | The sandbox image: executes one coding task, or one delivery of a run's changes (commit, push, merge request; ADR 0063), and reports back over HTTP |
| `postgres` | PostgreSQL 16 | Application data and LangGraph4j checkpoints |
| `langfuse` (optional) | Langfuse Helm chart | Trace and cost analytics for every LLM call, fed over OTLP |

## Hexagonal architecture

Each bounded context is a Gradle module pair: a **core** module (domain + application layers, pure
Kotlin, no framework dependencies) and an **adapters** module (driving adapters such as REST
controllers, driven adapters such as JPA repositories, Spring AI, LangGraph4j, gitlab4j).
The `app` module boots Spring, wires configuration and hosts cross-cutting concerns (security,
Flyway, observability) and the ArchUnit tests that enforce the layering.

```
                 driving adapters                       driven adapters
     +--------------------------+          +------------------------------------+
     | REST controllers (MVC)   |          | JPA repositories (PostgreSQL)      |
     | SSE event stream         |  ports   | Spring AI LLM gateway              |
     | Runner protocol (pull)   |--------->| LangGraph4j workflow engine        |
     | CI executions & signals  |  in/out  | PostgreSQL work queue, outbox      |
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
   depend on adapters of another context. Cross-context calls go through a port in `shared-kernel`.
4. `*-core` and `shared-kernel` do not reference Spring, JPA, Jackson, LangGraph4j, fabric8 or gitlab4j.

## Module map

```
backend/
  shared-kernel/          Ids, Money, Clock, DomainEvent, LlmGateway port & ModelRef, UsageRecord
  tasks-core/             Task Library: templates (extends/implements), interfaces, skills, resolver
  tasks-adapters/         REST, JPA, TaskCatalog port implementation, seed
  projects-core/          Projects & Workflows: graph model, runs, approvals, CI executions (use cases, ports)
  projects-adapters/      REST, JPA, LangGraph4j engine, SpEL expressions, Jira, CI token auth, SSE
  evaluation-core/        Model catalogue, task suites, benchmarks: domain + application
  evaluation-adapters/    REST, JPA, benchmark attempt executor, leaderboard queries
  identity-core/          Users, groups, roles, grants, local accounts, personal access tokens: domain + application
  identity-adapters/      OIDC BFF login, Spring Security, AuthorizationGate, token filters, REST, JPA
  governance-core/        Signing, attestations, insights: domain + application
  governance-adapters/    REST, JPA, signing keys, MCP server (/mcp)
  llm-gateway/            Spring AI implementation of LlmGateway (all providers), usage ledger (JPA), budgets,
                          /usage API, the LLM proxy for coding agents
  agent-runner/           AgentRunner port: agent job queue, runner fleet (pools, registration, runners),
                          the pull protocol runner-controllers use, job tokens
  platform-runtime/       Runtime roles (api, worker, migrate), PostgreSQL work queue, ShedLock, event outbox
  blob-storage/           BlobStore on S3-compatible storage or the filesystem
  app/                    Spring Boot application, wiring, Flyway, observability, dashboard, ArchUnit tests
  runner-controller/      Separately deployed execution plane: pulls agent jobs over HTTP and runs them in
                          sandboxes (Agent Sandbox / Kubernetes Job with fabric8, or docker)
```

Shared ports (in `shared-kernel`) and who implements them:

| Port | Implemented in | Used by |
|---|---|---|
| `LlmGateway` | `llm-gateway` | projects (LLM steps, code review), evaluation (attempts, judge) |
| `UsageLedger` | `llm-gateway` | gateway itself, runner adapter, usage API, dashboards |
| `ModelCatalog` | `evaluation-adapters` | projects (resolve step model), gateway (pricing) |
| `TaskCatalog` | `tasks-adapters` | projects (resolve TASK nodes, designer palette) |
| `AgentRunner` | `agent-runner` | projects (IMPLEMENT agent jobs, DELIVER jobs of `DELIVER_CHANGES`; ADR 0063), evaluation (CODING_JOB attempts) |
| `Clock` | `shared-kernel` (`Clock.SYSTEM`) | everyone |
| `EventPublisher` | `platform-runtime` (transactional outbox) | everyone |

Flyway version ranges: `V1..V49` llm-gateway (usage ledger), `V50..V99` agent-runner, `V100..V199` projects,
`V200..V299` evaluation, `V300..V399` tasks, `V400..V499` identity, `V500..V549` platform, `V550..V599` governance.
LangGraph4j creates its own checkpoint tables.

## Bounded contexts

| Context | Aggregates | Owns |
|---|---|---|
| **Projects & Workflows** | Project, ProjectCiToken, WorkflowDefinition, WorkflowRun, ApprovalRequest, CiExecution | Everything about delivering work for a project |
| **Task Library** | TaskTemplate, TaskInterface, Skill | What agents do and how they are prompted (ADR 0015) |
| **Model Evaluation** | ModelProfile, TaskSuite, Benchmark | Which models exist, what they cost, how good they are |
| **Identity & Access** | User, Group, Role, ProjectMembership, PersonalAccessToken | Who may do what (docs/architecture/08-identity-and-access.md) |
| **Governance** | Attestation, insights | Signed provenance, insights, MCP server (docs/architecture/11-governance.md) |
| **Shared kernel** | UsageRecord (ledger), ModelRef, Money | Cost accounting used by all contexts |

The Projects context refers to models only by `ModelProfileId` and resolves them through the
`ModelCatalog` port, implemented in `evaluation-adapters` (persistence adapters of the Evaluation context). This keeps the
two contexts independently evolvable while sharing one model catalogue.

## Runtime view: one workflow run

1. A person (or CI, a Jira intake rule or an API client) starts a run of a workflow for issue `PROJ-123`.
2. `StartRun` creates a `WorkflowRun` (status `PENDING`, then `RUNNING`) and hands it to the
   `WorkflowEngine` port. The LangGraph4j adapter does not execute anything in the request: it enqueues a
   `RUN_ADVANCE` work item. A `worker` process leases the item, compiles the definition into a `StateGraph`
   (with `interruptBefore` on every `HUMAN_GATE` and `WAIT_FOR_CI` node) and invokes the graph on its own thread.
3. Each node maps to a node executor: run a task step (a model answer, or a coding agent as an agent job that a
   runner-controller pulls and runs in a sandbox), run an action such as delivering the changes and opening an MR,
   route on a decision, and so on. Every node writes a `NodeExecution`, artefacts and usage records. While an agent
   job runs, no worker thread waits: the job's completion enqueues the next `RUN_ADVANCE`.
4. At a gate the graph checkpoints to PostgreSQL and the run becomes `WAITING`; an `ApprovalRequest`
   appears in the inbox.
5. A person approves, rejects or asks for revision. `DecideApproval` resumes the run through the engine (another
   `RUN_ADVANCE` item); the routing edge after the gate reads the decision.
6. After the END node the run is `SUCCEEDED`. On unhandled failure it is `FAILED` with the failing node
   recorded and can be retried from that node; a cancelled run is `CANCELLED`.

## Technology stack and versions

| Component | Version |
|---|---|
| JDK | 21 |
| Kotlin | 2.3 |
| Spring Boot | 4.1.2 |
| Spring AI | 2.0.1 |
| LangGraph4j | 1.9.1 |
| fabric8 kubernetes-client | 7.9 |
| gitlab4j-api | 6.3 |
| springdoc-openapi | 3.1 |
| PostgreSQL | 16 |
| Angular / NG-ZORRO | 21 / 21 |
| Helm chart apiVersion | v2 |
