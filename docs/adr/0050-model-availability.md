# 0050 — Model availability: who may use which model, where and by which route

- Status: accepted (step 1 implemented; steps 2-4 planned)
- Date: 2026-10-22
- Deciders: owner
- Related: ADR 0040 (personal runners, LOCAL_LOGIN), 0045 (model profiles from providers), 0048 (Claude Code login
  models), 0038 (private projects); docs/manual/evaluation.md#model-availability, docs/manual/workflows.md

## Context

"Available model" meant four unconnected things: profiles of the providers an administrator connected (callable
through the LLM gateway by everyone, metered), a user's own Claude login on their personal runner (only that user,
only coding steps of private projects, paid by the subscription), the global list of model profiles, and whatever a
step happened to name. The designer, the project defaults and the benchmark form offered every profile without
saying whether it would work there; a missing or unusable model surfaced only when a run reached the step.

## Decision

A model has two properties that decide where it can be used:

| | through the API (gateway) | through an agent with its own login |
|---|---|---|
| **platform** | provider connections of the administrator; everyone; budgets | (none yet) |
| **personal** | the user's own API key (step 2) | Claude Code with the user's login on their personal runner |

Personal models are usable only by their owner, in the owner's private projects and personal benchmarks; platform
models are usable everywhere, private projects included.

**Step 1 (this change): one availability service and everything that picks a model uses it.**

1. Port `ModelAvailability` (shared-kernel): for a model and a use (step kind `LLM_CALL` or `CODING_AGENT`, coding
   harness, private project or not, the user) it returns the route (`GATEWAY`, `OWN_LOGIN`), whether the model is
   usable and why not. Implemented by the Evaluation context, which owns the profiles, over the shared ports
   `LlmGateway` (connection configured) and `AgentRunner` (personal pool, LOCAL_LOGIN, online runners).
2. Rules: an API model needs its provider connection; a Claude Code login model needs a coding-agent step with
   Claude Code in a private project and a personal runner with the user's login (offline is a warning: jobs wait).
3. `ModelRuntime` moves to shared-kernel and `ModelProfileSnapshot` carries it, so login models are visible to the
   Projects context: coding steps of private projects may use them (they run on the personal runner, ADR 0040), an
   LLM step refuses them with the reason instead of failing in the gateway.
4. Projects: `ModelStepCheck` resolves every task step's model as a run would (node, template, project default or
   review default) and gives warnings in the designer validation, refuses the start of a run whose step has no
   usable model, and a per-step **model plan** (model, where it comes from, route, problem). REST
   `GET /projects/{id}/model-options` and `GET /workflows/{id}/model-plan`; MCP `get_model_options` and the model
   plan in `validate_workflow`.
5. UI: model pickers group platform and personal models, show the route and grey out unusable ones with the
   reason; the start dialog shows the model plan.

**Planned steps** (each usable on its own):

2. Personal scope for model profiles (owner) and personal API keys (personal provider connections, sealed like
   platform keys); a personal default model per step kind in the resolution order node > template > project >
   personal > platform default.
3. Runners report their harnesses and active logins in the heartbeat; login models appear and disappear with the
   runner instead of being created by hand; Account, "My models".
4. A prompt mode of Claude Code in the runner, so LLM steps and the benchmark judge can run on a subscription.

## Consequences

- A workflow that names an unusable model is still saved (it may become usable later), with a warning; its run
  does not start until the model is usable.
- Availability depends on the user and on the moment (a runner may be offline): designer warnings describe the
  current user, the start check the user who starts the run.
- Benchmarks keep their own checks (ADR 0048) until step 2 moves them onto the same service.
