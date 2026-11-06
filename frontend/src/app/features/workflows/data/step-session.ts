/**
 * ADR 0057/0058 in the workflow designer, as pure functions (tested in step-session.spec.ts):
 *
 * - Agent session: a coding agent step may continue the agent session of an earlier step with
 *   `overrides.config.continueSession: <node id>` (same run workspace and conversation; the same
 *   harness is required). Without it the step starts a new session.
 * - Parameters: the fields of a step's parameters from its palette entry (declarations and the
 *   values the template lineage sets) or, when the palette does not carry them, the resolved
 *   template; the step writes `overrides.parameters`.
 */
import type {
  AgentRef,
  PaletteTask,
  ResolvedTask,
  TaskOverrides,
  WorkflowNode,
} from '../../../core/api/types';
import {
  type ParameterField,
  fromResolved,
  parameterFields,
} from '../../../core/ui/template-parameters';

export const CONTINUE_SESSION_KEY = 'continueSession';

/** The node id whose session the step continues; null = a new session. */
export function continuedStep(config: Record<string, unknown> | null | undefined): string | null {
  const v = config?.[CONTINUE_SESSION_KEY];
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

/** The step config continuing `nodeId`'s session, or without the key for a new session. */
export function withContinueSession(
  config: Record<string, unknown> | null | undefined,
  nodeId: string | null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (nodeId) next[CONTINUE_SESSION_KEY] = nodeId;
  else delete next[CONTINUE_SESSION_KEY];
  return Object.keys(next).length ? next : undefined;
}

/** A step whose session another step may continue. */
export interface SessionCandidate {
  id: string;
  name: string;
  harness: string;
  /** Same harness as the continuing step (a Claude Code session cannot go on in OpenCode). */
  sameHarness: boolean;
}

/**
 * The other TASK nodes run by a coding agent, in workflow order; `agentOf` gives a node's
 * agent (the step's own, else the template's).
 */
export function sessionCandidates(
  nodes: readonly WorkflowNode[],
  selfId: string,
  harness: string | null | undefined,
  agentOf: (n: WorkflowNode) => Pick<AgentRef, 'harness'> | null,
): SessionCandidate[] {
  const out: SessionCandidate[] = [];
  for (const n of nodes) {
    if (n.kind !== 'TASK' || n.id === selfId) continue;
    const h = agentOf(n)?.harness;
    if (!h) continue;
    out.push({ id: n.id, name: n.name || n.id, harness: h, sameHarness: h === harness });
  }
  return out;
}

/**
 * ADR 0058: the parameter fields of a step: the palette's declarations and lineage values, else
 * the resolved template's; the step's own values from `overrides.parameters`.
 */
export function stepParameterFields(
  task: Pick<PaletteTask, 'parameters' | 'parameterValues'> | null | undefined,
  resolved: Pick<ResolvedTask, 'parameters'> | null | undefined,
  overrides: Pick<TaskOverrides, 'parameters'> | null | undefined,
): ParameterField[] {
  if (task?.parameters?.length)
    return parameterFields(task.parameters, task.parameterValues, overrides?.parameters);
  const r = fromResolved(resolved?.parameters);
  return parameterFields(r.declared, r.values, overrides?.parameters);
}

/** The name of the step whose session a TASK node continues (for the canvas marker). */
export function continuesFromName(
  node: WorkflowNode,
  names: ReadonlyMap<string, string> | null | undefined,
): string | null {
  if (node.kind !== 'TASK') return null;
  const id = continuedStep(node.task?.overrides?.config);
  return id ? (names?.get(id) ?? id) : null;
}
