/**
 * ADR 0056: the agent editor as pure functions (tested in agent-form.spec.ts). An agent is a
 * harness (none = one model answer) and optionally a model (none = the defaults for the step).
 */
import type { Agent, AgentHarness, AgentInput } from '../../../core/api/types';

/** The kinds the editor offers, in display order (`null` = model answer). */
export const AGENT_KINDS: ReadonlyArray<AgentHarness | null> = [null, 'OPENCODE', 'CLAUDE_CODE'];

/** The radio value of a kind (a radio group cannot hold `null` reliably). */
export type AgentKindValue = 'MODEL_ANSWER' | AgentHarness;

export function kindValue(harness: AgentHarness | null | undefined): AgentKindValue {
  return harness ?? 'MODEL_ANSWER';
}

export function harnessOf(value: AgentKindValue): AgentHarness | null {
  return value === 'MODEL_ANSWER' ? null : value;
}

/** Blank agent draft (no key: the server derives it from the name). */
export function emptyAgent(): AgentInput {
  return {
    name: '',
    description: '',
    scope: { type: 'GLOBAL', projectId: null },
    harness: null,
    modelProfileId: null,
    tags: [],
  };
}

/** The editable fields of a saved agent (the key is kept by the server, never sent). */
export function agentDraft(a: Agent): AgentInput {
  return {
    name: a.name,
    description: a.description ?? '',
    scope: { type: a.scope?.type ?? 'GLOBAL', projectId: a.scope?.projectId ?? null },
    harness: a.harness ?? null,
    modelProfileId: a.modelProfileId ?? null,
    tags: [...(a.tags ?? [])],
  };
}

/** Whether the draft may be saved: a name and, for a project agent, its project. */
export function agentSavable(d: AgentInput): boolean {
  return !!d.name.trim() && (d.scope.type !== 'PROJECT' || !!d.scope.projectId);
}

/**
 * ADR 0055/0056: a new PROJECT agent that customises the global agent `a` for one project: the
 * same key, name, kind and model (a project agent with a global agent's key replaces it there).
 */
export function customizeAgentForProject(a: Agent, projectId: string | null): AgentInput {
  return { ...agentDraft(a), key: a.key ?? null, scope: { type: 'PROJECT', projectId } };
}
