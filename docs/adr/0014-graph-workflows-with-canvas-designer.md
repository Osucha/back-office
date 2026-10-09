# 0014 - Workflows are graphs edited on a canvas; decisions are first-class nodes

Status: accepted - 2026-09-26. Supersedes the "ordered step list" decision in [0010](0010-angular-ng-zorro-frontend.md).

## Context
The owner wants project processes configured like n8n: a board with blocks (tasks, mostly agentic) and
decision nodes, edges between them, some blocks executing in CI and some in Back Office.

## Decision
- `WorkflowDefinition` = nodes + edges (+ canvas positions). Node kinds: `START`, `END` (with outcome
  `SUCCESS`/`FAILURE`), `TASK` (references a task template from the Task Library plus node-level
  overrides), `DECISION` (named branches with boolean expressions over the run state), `HUMAN_GATE`,
  `WAIT_FOR_CI`, `NOTE`. Every node exposes named output ports; an edge is `from.port -> to`.
- Decision expressions use Spring Expression Language evaluated read-only in a sandboxed
  `SimpleEvaluationContext` (adapter), against a `WorkflowStateView` (input, issue, artifacts,
  confidence, lastOutcome, review, ci, mr). The core sees only an `ExpressionEvaluator` port.
- The graph compiles to LangGraph4j exactly as before: ports become conditional-edge keys, gates and CI
  waits are `interruptBefore` nodes, revision loops are ordinary edges back to earlier nodes.
- The designer uses `ngx-vflow` (Angular-native flow canvas, MIT) with custom node components, a
  palette fed by the Task Library, a property panel per node and server-side validation.

## Alternatives
- Keep the ordered list with `onSuccess`/`GOTO`: simpler to validate but cannot express fan-out, parallel
  branches or readable loops, and is not what the owner asked for.
- `@foblex/flow`: capable, but ngx-vflow's Vue Flow heritage matches the n8n mental model more closely.

## Consequences
- Validation moves from "targets exist" to graph rules: one START, at least one END, every non-END node
  has its `success` (or all branch) ports connected or explicitly terminal, no unreachable nodes,
  expressions parse.
- Runs record `NodeExecution`s and the taken port so the UI can replay the path on the same canvas.
