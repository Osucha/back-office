/**
 * Agent step settings in the workflow designer, as pure functions (tested in step-session.spec.ts):
 *
 * - Questions (ADR 0060): `allowQuestions` (default true) and `maxQuestionRounds` (default 2) of every agent step.
 * - Parameters (ADR 0058/0062): the fields of a step's parameters from its palette entry (declarations and the
 *   values the template lineage sets) or, when the palette does not carry them, the resolved template; the step's
 *   fixed values are its VALUE bindings (step-bindings.ts).
 *
 * ADR 0062: the session and the directory are the agent's (`Agent.session`, `Agent.access.directory`). The step keys
 * `directory`, `session` and `continueSession` are ignored by the server; they stay out of the config form, like the
 * legacy `sessionRole` (still read) and `workspace`.
 */
import type { PaletteTask, ResolvedTask, TaskOverrides } from '../../../core/api/types';
import {
  type ParameterField,
  fromResolved,
  parameterFields,
} from '../../../core/ui/template-parameters';
import { stepValues } from './step-bindings';

export const ALLOW_QUESTIONS_KEY = 'allowQuestions';
export const MAX_QUESTION_ROUNDS_KEY = 'maxQuestionRounds';
/** The deprecated `prepare-workspace` steps: prepare the run directory only (no agent, no model). */
export const PREPARE_ONLY_KEY = 'prepareOnly';

/**
 * Config keys of coding agent steps never edited by the generic config form: the ignored ADR 0060 step keys, the
 * ADR 0044 keys and `prepareOnly`. They are kept as they are on schema form changes.
 */
export const CODING_STEP_KEYS = [
  'directory',
  'session',
  'continueSession',
  PREPARE_ONLY_KEY,
  'workspace',
  'sessionRole',
];
/** Config keys of every agent step edited by the questions controls. */
export const QUESTION_KEYS = [ALLOW_QUESTIONS_KEY, MAX_QUESTION_ROUNDS_KEY];
export const DEFAULT_QUESTION_ROUNDS = 2;

type Config = Record<string, unknown> | null | undefined;

/** Drops empty keys; undefined when nothing is left. */
function compact(next: Record<string, unknown>): Record<string, unknown> | undefined {
  for (const [k, v] of Object.entries(next))
    if (v === null || v === undefined || v === '') delete next[k];
  return Object.keys(next).length ? next : undefined;
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

/**
 * ADR 0058/0062: the parameter fields of a step: the palette's declarations and lineage values,
 * else the resolved template's; the step's own values are its VALUE bindings.
 */
export function stepParameterFields(
  task: Pick<PaletteTask, 'parameters' | 'parameterValues'> | null | undefined,
  resolved: Pick<ResolvedTask, 'parameters'> | null | undefined,
  overrides: TaskOverrides | null | undefined,
): ParameterField[] {
  const own = stepValues(overrides);
  if (task?.parameters?.length) return parameterFields(task.parameters, task.parameterValues, own);
  // the resolved view of the step carries the step's values as source STEP: not the template's
  const r = fromResolved(resolved?.parameters);
  return parameterFields(r.declared, r.values, own);
}
