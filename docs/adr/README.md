# Architecture decision records

| # | Title | Status |
|---|---|---|
| [0001](0001-hexagonal-architecture-with-gradle-modules.md) | Hexagonal architecture with one core and one adapters module per bounded context | accepted |
| [0002](0002-kotlin-spring-boot-4.md) | Kotlin on Spring Boot 4.1 for the backend | accepted |
| [0003](0003-spring-ai-as-llm-abstraction.md) | Spring AI as the multi-vendor LLM abstraction | accepted |
| [0004](0004-langgraph4j-for-durable-pipelines.md) | LangGraph4j with Postgres checkpoints as the pipeline engine (Temporal and Argo rejected) | accepted |
| [0005](0005-spec-kit-for-specification-artifacts.md) | GitHub Spec Kit as the specification workflow and artefact format | accepted |
| [0006](0006-coding-agents-in-kubernetes-jobs.md) | Coding agents run as Kubernetes Jobs in a shared runner image (OpenCode default, Claude Code optional) | accepted |
| [0007](0007-postgres-jpa-flyway.md) | PostgreSQL with Spring Data JPA, JSONB documents and Flyway | accepted |
| [0008](0008-usage-ledger-and-langfuse.md) | Own usage ledger for cost plus optional Langfuse tracing over OTLP | accepted |
| [0009](0009-single-owner-api-key-auth.md) | Single-owner API-key authentication, OIDC deferred | superseded by 0016 |
| [0010](0010-angular-ng-zorro-frontend.md) | Angular 21 with NG-ZORRO, standalone components and signals | accepted |
| [0011](0011-code-review-as-ci-job-calling-api.md) | Merge-request gate implemented as a CI job polling the API | partially superseded by 0013 |
| [0012](0012-helm-chart-with-optional-subcharts.md) | One Helm chart with conditional PostgreSQL and Langfuse dependencies | accepted |
| [0013](0013-ci-initiated-integration-without-scm-api.md) | CI-initiated integration: project CI tokens, served client script, no SCM API (supersedes GitLab parts of 0011) | accepted |
| [0014](0014-graph-workflows-with-canvas-designer.md) | Workflows are graphs edited on a canvas (ngx-vflow); decisions are nodes; SpEL expressions | accepted |
| [0015](0015-task-library-templates-interfaces-skills.md) | Task Library context: templates with extends/implements, Agent Skills | accepted |
| [0016](0016-oidc-bff-authentication.md) | OIDC login through a backend-for-frontend with server-side sessions (supersedes 0009) | accepted |
| [0017](0017-deny-by-default-authorization.md) | Deny-by-default authorisation: code-defined permissions, three-level grants, project roles | accepted |
| [0018](0018-transactional-audit-log.md) | Transactional, write-only audit log with correlation ids | accepted |
| [0019](0019-sdlc-conventions-from-reference.md) | Delivery conventions adopted from a reference SDLC framework | accepted |
| [0020](0020-provider-rate-limits-and-build-guardrails.md) | Provider rate limits with an interactive reserve, and supply-chain guardrails | accepted |
| [0021](0021-local-accounts.md) | Local accounts with passwords and TOTP, alongside OIDC (amends 0016) | accepted |
| [0023](0023-postgres-backbone-scale-out.md) | Scale-out on a PostgreSQL backbone: api / worker / runner-controller roles, work queue, LISTEN/NOTIFY, object storage | accepted |
| [0024](0024-sandboxed-runner-fleet.md) | Agent execution: pull-based runner fleet, Agent Sandbox with gVisor/Kata, brokered credentials (supersedes execution model of 0006) | accepted |
| [0025](0025-governance-capabilities-2027.md) | Governance for 2027: budgets, delivery insights, AI provenance attestations, MCP server | accepted |
| [0022](0022-relay-design-language.md) | "Relay" design language: the UI is organised around who holds the baton | accepted |
| [0026](0026-ui-internationalisation.md) | UI internationalisation: Angular runtime `$localize` with per-area translation files, Polish next to English | accepted |
| [0027](0027-sonarqube-quality-gate.md) | SonarQube quality gate on new code ("Clean as you code") for the whole repository, CI job `quality:sonar` (GR-Q3) | accepted |
| [0028](0028-manager-led-projects-and-step-assignment.md) | Manager-led projects: `project-manager` role creates projects, owners/maintainers assign who decides a waiting gate (`run.assign`) | accepted |
| [0029](0029-prompt-injection-defences.md) | Prompt-injection defences: data blocks with random boundaries, deterministic review guards (fail closed), gateway-only agent token and in-memory git token, opt-in MCP gate decisions | accepted |
| [0030](0030-least-privilege-connections.md) | Least-privilege connections: platform and project Jira connections with capabilities, envelope-encrypted secrets (`BACKOFFICE_SECRETS_KEY`), fewest-rights selection and Jira permission verification; env credential kept as legacy | accepted |
| [0031](0031-least-privilege-git-access.md) | Least-privilege git access: `gitAccess` NONE / READ / PUSH_MR per job, read and push Secrets per repository in the runner cluster, whitelisted push options (never merge) | accepted |
| [0032](0032-desktop-notifications.md) | Desktop notifications with the Notification API while a tab is open (Web Push later), per-user browser preferences, synthesised Web Audio "relay baton" sounds | accepted |
| [0033](0033-jira-intake-and-approval-before-write.md) | Jira intake watcher (per-project polling with a reader connection, one run per transition, baseline and back-off) and approval before external writes (`<node>::confirm`, write confirmations with the exact preview, reject through `failure`) | accepted |
| [0034](0034-optional-multi-repository-projects.md) | Optional multi-repository projects: zero or more repositories per project (key, purpose, optional primary), per-step repository access READ / PUSH_MR, `RunnerJobSpec.repositories` with one workspace directory per repository, CI mapped by repository URL, starter workflow for projects without code | accepted |
| [0035](0035-jira-connection-model.md) | Jira connection model: project system connection and list, personal connections (used only with their owner's approval or run start), per-step "Act as" (`SYSTEM` / connection / `ACTING_USER`), Atlassian scoped API tokens via api.atlassian.com with cloud id discovery and scope checks; direct Jira REST API v3, no MCP or CLI (amends 0030) | accepted |
| [0036](0036-optional-spec-kit.md) | Spec Kit optional per project (off for new projects) with a platform switch: palette, validator, run start and runner (`specKit: false`) honour it; Spec Kit workflows seeded only when on, "Add Spec Kit workflows" later | accepted |
| [0037](0037-workflow-library.md) | Workflow library: versioned workflows published from projects, adopted as linked copies (update keeps project-local settings, detach), runs stamped with the library version, workflow metrics (effectiveness and efficiency per version, project and node) | accepted |
| [0038](0038-personal-work-mode.md) | Personal work next to the team mode: private projects (`projects.create.personal`, owner only), personal Jira intake rules on the owner's personal connection (amends 0035) that propose runs per new matching issue; Start runs as the owner, Dismiss / Restore | accepted |
| [0039](0039-chart-without-bundled-keycloak.md) | The Helm chart bundles no identity provider: Keycloak subchart removed, local accounts are the default, external OIDC optional (amends 0012) | accepted |
| [0040](0040-personal-runners.md) | Personal runners: a personal runner pool per user (`fleet.personal`, My account) runs the agent steps of their private projects on their own computer (runner-controller with the DOCKER backend, one `docker run`); optional `LOCAL_LOGIN`: Claude Code uses the owner's own login (`CLAUDE_CODE_OAUTH_TOKEN`), not metered, node cost 0 (extends 0024) | accepted |
| [0041](0041-workflow-drafts-and-project-short-code.md) | Workflow drafts (saved with errors, no runs, no publishing) and the project key as Back Office's own short code: optional (generated from the name), changeable, no longer the implicit Jira project key (amends 0014, 0035) | accepted |
| [0042](0042-api-equivalent-cost.md) | Cost at API prices next to the spent cost: steps, runs and workflow metrics carry `apiCost` (equal to the cost through the gateway, the harness estimate for subscription steps); budgets keep counting only spent money | accepted |
| [0043](0043-library-sources.md) | Sources: git repositories of standards, templates and reference material in the library (`/library/sources`), attached read only by steps as `{key, access: READ, library: true}` and cloned to `/workspace/lib-<key>`; the project tab Sources holds the project repositories | accepted |
| [0044](0044-run-workspaces-and-agent-sessions.md) | Run workspaces and resumed agent sessions: workspaces of the project shared by its workflows (sources, setup, roles; drawn around their steps); steps of a run that pick the same one share a Docker volume / PVC at /workspace on one runner, one job at a time; `sessionRole` continues the agent's session per role; `prepare-workspace` clones and runs setupCommand once; released when the run ends (24 h after a failure) | accepted |
| [0045](0045-llm-provider-connections.md) | LLM provider connections managed in Back Office: provider type, base URL and an API key sealed with the secret key, several per type (OpenRouter and Groq side by side), named by model profiles (`ModelRef.connection`); environment variables remain the fallback | accepted |
| [0046](0046-benchmark-execution-and-provider-limits.md) | Benchmark execution targets (shared fleet or personal runner), preflight check, persistent provider failures stop retries, request limits per provider connection, one-step local stack without Keycloak | accepted |
| [0047](0047-benchmarks-that-measure-and-library-wiring.md) | Hidden acceptance checks and the diff for the judge in coding benchmarks, scores computed from criteria, PetClinic tasks, commit subject and Spec Kit argument from Back Office, library wiring (review findings in rework, scoped feedback, checked artifact names, built-in upgrades) | accepted |
| [0048](0048-claude-code-login-models.md) | Claude Code on its user's own login as a benchmark candidate: model profile runtime CLAUDE_CODE_LOGIN (personal runner with LOCAL_LOGIN, Claude Code harness, coding tasks only, never the judge, nothing billed), checked instead of called, hidden from workflows; subscription tokens refused as Anthropic API keys | accepted |
| [0049](0049-workflow-authoring-through-mcp.md) | Workflow authoring through MCP: list_projects, get_workflow_palette, get_workflow, validate_workflow, create_workflow, update_workflow; one workflow JSON encoding in projects-core; writes need the token option Allow workflow edits via MCP | accepted |

Template: context, decision, alternatives considered, consequences. Dates are the day the decision was
taken. A superseded record keeps its text and gains a link to the successor.
