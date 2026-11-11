/**
 * ADR 0057/0058/0060 in the workflow designer, as pure functions (tested in step-session.spec.ts):
 *
 * - Directory (ADR 0060): a coding agent step works in the run directory (`directory` RUN, the default, shared by
 *   the coding steps of a run) or in a separate directory (SEPARATE: a fresh copy of only its repositories, no
 *   session continuation).
 * - Agent session: `session` OWN (default: repeated executions continue the step's own session), NEW, or CONTINUE
 *   with `continueSession: <node id>` (the session of an earlier step of the same agent).
 * - Questions: `allowQuestions` (default true) and `maxQuestionRounds` (default 2) of every agent step.
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

export const DIRECTORY_KEY = 'directory';
export const SESSION_KEY = 'session';
export const CONTINUE_SESSION_KEY = 'continueSession';
export const ALLOW_QUESTIONS_KEY = 'allowQuestions';
export const MAX_QUESTION_ROUNDS_KEY = 'maxQuestionRounds';
/** The deprecated `prepare-workspace` steps: prepare the run directory only (no agent, no model). */
export const PREPARE_ONLY_KEY = 'prepareOnly';
/** ADR 0044 keys, read compatibly by the backend; removed when the step's directory or session changes. */
const LEGACY_KEYS = ['workspace', 'sessionRole'];

/** Config keys of coding agent steps edited by their own controls (never by the generic config form). */
export const CODING_STEP_KEYS = [
  DIRECTORY_KEY,
  SESSION_KEY,
  CONTINUE_SESSION_KEY,
  PREPARE_ONLY_KEY,
  ...LEGACY_KEYS,
];
/** Config keys of every agent step edited by the questions controls. */
export const QUESTION_KEYS = [ALLOW_QUESTIONS_KEY, MAX_QUESTION_ROUNDS_KEY];
export const DEFAULT_QUESTION_ROUNDS = 2;

export type StepDirectory = 'RUN' | 'SEPARATE';
export type SessionMode = 'OWN' | 'NEW' | 'CONTINUE';

type Config = Record<string, unknown> | null | undefined;

const upper = (v: unknown): string => (typeof v === 'string' ? v.trim().toUpperCase() : '');

/** Drops empty keys; undefined when nothing is left. */
function compact(next: Record<string, unknown>): Record<string, unknown> | undefined {
  for (const [k, v] of Object.entries(next))
    if (v === null || v === undefined || v === '') delete next[k];
  return Object.keys(next).length ? next : undefined;
}

/** The step's directory: SEPARATE only when set so. */
export function stepDirectory(config: Config): StepDirectory {
  return upper(config?.[DIRECTORY_KEY]) === 'SEPARATE' ? 'SEPARATE' : 'RUN';
}

/** The step config with the directory changed; SEPARATE drops the session settings (it always starts fresh). */
export function withStepDirectory(
  config: Config,
  directory: StepDirectory,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  for (const k of LEGACY_KEYS) delete next[k];
  if (directory === 'SEPARATE') {
    next[DIRECTORY_KEY] = 'SEPARATE';
    delete next[SESSION_KEY];
    delete next[CONTINUE_SESSION_KEY];
  } else delete next[DIRECTORY_KEY];
  return compact(next);
}

/** The node id whose session the step continues; null = none. */
export function continuedStep(config: Config): string | null {
  const v = config?.[CONTINUE_SESSION_KEY];
  return typeof v === 'string' && v.trim() ? v.trim() : null;
}

/** The step's session mode: CONTINUE with a continued step, NEW when set so, else OWN. */
export function stepSessionMode(config: Config): SessionMode {
  if (continuedStep(config)) return 'CONTINUE';
  const s = upper(config?.[SESSION_KEY]);
  return s === 'NEW' || s === 'CONTINUE' ? s : 'OWN';
}

/** The step config with the session mode changed; OWN (the default) removes the keys. */
export function withSessionMode(
  config: Config,
  mode: SessionMode,
  continueFrom: string | null = null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  for (const k of LEGACY_KEYS) delete next[k];
  delete next[CONTINUE_SESSION_KEY];
  if (mode === 'OWN') delete next[SESSION_KEY];
  else next[SESSION_KEY] = mode;
  if (mode === 'CONTINUE' && continueFrom) next[CONTINUE_SESSION_KEY] = continueFrom;
  return compact(next);
}

/** The step config continuing `nodeId`'s session, or the step's own session without one. */
export function withContinueSession(
  config: Config,
  nodeId: string | null,
): Record<string, unknown> | undefined {
  return withSessionMode(config, nodeId ? 'CONTINUE' : 'OWN', nodeId);
}

export interface StepQuestions {
  allow: boolean;
  rounds: number;
}

/** Whether the step's agent may ask questions (default yes) and in how many rounds (default 2). */
export function stepQuestions(config: Config): StepQuestions {
  const a = config?.[ALLOW_QUESTIONS_KEY];
  const r = Number(config?.[MAX_QUESTION_ROUNDS_KEY]);
  return {
    allow: !(a === false || a === 'false'),
    rounds: Number.isInteger(r) && r >= 1 ? r : DEFAULT_QUESTION_ROUNDS,
  };
}

/** The step config with the question settings changed; defaults are not written. */
export function withStepQuestions(
  config: Config,
  change: Partial<StepQuestions>,
): Record<string, unknown> | undefined {
  const merged = { ...stepQuestions(config), ...change };
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (merged.allow) delete next[ALLOW_QUESTIONS_KEY];
  else next[ALLOW_QUESTIONS_KEY] = false;
  if (!merged.allow || merged.rounds === DEFAULT_QUESTION_ROUNDS)
    delete next[MAX_QUESTION_ROUNDS_KEY];
  else next[MAX_QUESTION_ROUNDS_KEY] = merged.rounds;
  return compact(next);
}

/** Why a step's session cannot be continued by another step; null = it can. */
export type SessionBlock = 'OTHER_AGENT' | 'SEPARATE';

/** A step whose session another step may continue. */
export interface SessionCandidate {
  id: string;
  name: string;
  /** Name of the step's agent. */
  agent: string;
  /** Null when the continuing step may pick it. */
  blocked: SessionBlock | null;
}

/**
 * The other TASK nodes run by a coding agent, in workflow order; `agentOf` gives a node's agent (the step's own,
 * else the template's). A session needs the same agent (its home holds it, ADR 0060); a step in a separate
 * directory leaves no session behind.
 */
export function sessionCandidates(
  nodes: readonly WorkflowNode[],
  selfId: string,
  agentKey: string | null | undefined,
  agentOf: (n: WorkflowNode) => Pick<AgentRef, 'key' | 'name' | 'harness'> | null,
): SessionCandidate[] {
  const out: SessionCandidate[] = [];
  for (const n of nodes) {
    if (n.kind !== 'TASK' || n.id === selfId) continue;
    const a = agentOf(n);
    if (!a?.harness) continue;
    const blocked: SessionBlock | null =
      stepDirectory(n.task?.overrides?.config) === 'SEPARATE'
        ? 'SEPARATE'
        : a.key !== agentKey
          ? 'OTHER_AGENT'
          : null;
    out.push({ id: n.id, name: n.name || n.id, agent: a.name || a.key, blocked });
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
