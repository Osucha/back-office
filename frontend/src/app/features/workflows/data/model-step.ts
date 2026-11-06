/**
 * The kind of model use of a task step (ADR 0050/0056), as the server derives it: the step's agent
 * decides. An agent without a harness gives a model answer (one gateway call, `LLM_CALL`); an agent
 * with a harness (OpenCode, Claude Code) runs a coding agent (`CODING_AGENT`) unless the step only
 * prepares the workspace. The step's agent is its own (`overrides.agentKey`), else the template's.
 */
import type {
  Agent,
  AgentRef,
  CodingHarness,
  PaletteTask,
  StepKind,
} from '../../../core/api/types';
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

function truthy(value: unknown): boolean {
  return value === true || String(value).toLowerCase() === 'true';
}

/**
 * ADR 0056: the agent that runs a task step: the step's own (looked up among the project's agents,
 * a project agent before a global one of the same key), else the template's (palette). Null when
 * the step names an agent that is not among the loaded ones, or nothing is known.
 */
export function effectiveStepAgent(
  task: Pick<PaletteTask, 'agent'> | null | undefined,
  stepAgentKey: string | null | undefined,
  agents: readonly Agent[] | null | undefined,
): AgentRef | null {
  if (!stepAgentKey) return task?.agent ?? null;
  const candidates = (agents ?? []).filter((a) => a.key === stepAgentKey);
  const a = candidates.find((x) => x.scope?.type === 'PROJECT') ?? candidates[0];
  return a
    ? {
        key: stepAgentKey,
        name: a.name,
        harness: a.harness ?? null,
        modelProfileId: a.modelProfileId ?? null,
      }
    : null;
}

/**
 * The model use of a step run by `agent`: null without an agent and for a coding agent step that
 * only prepares the workspace (`prepareOnly`, node config before template config).
 */
export function stepModelUse(
  agent: Pick<AgentRef, 'harness'> | null | undefined,
  nodeConfig?: Record<string, unknown> | null,
  templateConfig?: Record<string, unknown> | null,
): StepModelUse | null {
  if (!agent) return null;
  const harness = harnessOf(agent.harness);
  if (!harness) return { stepKind: 'LLM_CALL', harness: null };
  const prepareOnly = nodeConfig?.[PREPARE_ONLY_KEY] ?? templateConfig?.[PREPARE_ONLY_KEY];
  if (truthy(prepareOnly)) return null;
  return { stepKind: 'CODING_AGENT', harness };
}

/** Cache key of the model options of one step use. */
export function stepModelUseKey(use: StepModelUse): string {
  return use.harness ? `${use.stepKind}:${use.harness}` : use.stepKind;
}
