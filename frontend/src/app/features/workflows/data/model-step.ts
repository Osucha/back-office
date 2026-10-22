/**
 * The kind of model use of a task step (ADR 0050), as the server derives it in `ModelStepCheck`: LLM steps make one
 * gateway call, IMPLEMENT / SPEC_KIT steps run a coding agent (unless they only prepare the workspace) whose harness
 * is `config.agent` (node, then template, then the schema default; OpenCode by default). Other steps use no model.
 */
import type { CodingHarness, PaletteTask, StepKind } from '../../../core/api/types';
import { PREPARE_ONLY_KEY } from './workspaces';

export interface StepModelUse {
  stepKind: StepKind;
  harness: CodingHarness | null;
}

const HARNESSES: CodingHarness[] = ['OPENCODE', 'CLAUDE_CODE'];

function harnessOf(value: unknown): CodingHarness | null {
  const v = typeof value === 'string' ? value.trim().toUpperCase() : '';
  return HARNESSES.find((h) => h === v) ?? null;
}

function defined(...configs: Array<Record<string, unknown> | null | undefined>) {
  return (key: string): unknown => {
    for (const c of configs) if (c && c[key] !== undefined && c[key] !== null) return c[key];
    return undefined;
  };
}

export function stepModelUse(
  task: Pick<PaletteTask, 'executor' | 'configSchema'> | null | undefined,
  nodeConfig?: Record<string, unknown> | null,
  templateConfig?: Record<string, unknown> | null,
): StepModelUse | null {
  if (!task?.executor) return null;
  if (task.executor === 'LLM') return { stepKind: 'LLM_CALL', harness: null };
  if (task.executor !== 'IMPLEMENT' && task.executor !== 'SPEC_KIT') return null;
  const props = (task.configSchema?.['properties'] ?? {}) as Record<
    string,
    { default?: unknown } | undefined
  >;
  const schemaDefaults: Record<string, unknown> = {};
  for (const [k, p] of Object.entries(props))
    if (p?.default !== undefined) schemaDefaults[k] = p.default;
  const value = defined(nodeConfig, templateConfig, schemaDefaults);
  const prepareOnly = value(PREPARE_ONLY_KEY);
  if (prepareOnly === true || String(prepareOnly).toLowerCase() === 'true') return null;
  return { stepKind: 'CODING_AGENT', harness: harnessOf(value('agent')) ?? 'OPENCODE' };
}

/** Cache key of the model options of one step use. */
export function stepModelUseKey(use: StepModelUse): string {
  return use.harness ? `${use.stepKind}:${use.harness}` : use.stepKind;
}
