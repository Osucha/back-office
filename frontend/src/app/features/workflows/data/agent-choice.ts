/**
 * ADR 0061: the step chooses the agent. When a template is dropped (or chosen) for a step the
 * designer pre-selects one: an agent of the kind the template needs (a coding agent when it needs
 * one; otherwise any agent, model answer agents first) that has the most of the template's
 * required skills; ties: the platform default of that kind, then built-in agents, then by name. A
 * stored step without an agent runs with `opencode` when its template needs a coding agent, else
 * with `model-answer`. Pure; tested in agent-choice.spec.ts.
 */
import type { Agent, PaletteTask } from '../../../core/api/types';

/** The agent of a step without one whose template needs a coding agent. */
export const DEFAULT_CODING_AGENT_KEY = 'opencode';
/** The agent of a step without one whose template does not need a coding agent. */
export const DEFAULT_ANSWER_AGENT_KEY = 'model-answer';

/** ADR 0061: the agent key a step without its own agent runs with. */
export function defaultStepAgentKey(needsCodingAgent: boolean | null | undefined): string {
  return needsCodingAgent ? DEFAULT_CODING_AGENT_KEY : DEFAULT_ANSWER_AGENT_KEY;
}

/** One agent per key as a step of the project sees it: the project's own before the global one. */
export function visibleAgents(agents: readonly Agent[]): Agent[] {
  const byKey = new Map<string, Agent>();
  for (const a of agents) {
    const key = a.key ?? '';
    if (!byKey.has(key) || a.scope?.type === 'PROJECT') byKey.set(key, a);
  }
  return [...byKey.values()];
}

/** How many of the required skills the agent has. */
export function coveredSkills(agent: Pick<Agent, 'skills'>, required: readonly string[]): number {
  const own = new Set(agent.skills ?? []);
  return required.filter((k) => own.has(k)).length;
}

/**
 * ADR 0061: the agent to pre-select for a new step of `task`; null when no agent fits (no coding
 * agent for a template that needs one, or no agents loaded).
 */
export function pickAgent(
  task: Pick<PaletteTask, 'needsCodingAgent' | 'requiresSkills'> | null | undefined,
  agents: readonly Agent[],
): Agent | null {
  const coding = !!task?.needsCodingAgent;
  const required = task?.requiresSkills ?? [];
  const candidates = visibleAgents(agents).filter((a) => !coding || !!a.harness);
  const preferred = defaultStepAgentKey(coding);
  const rank = (a: Agent): number[] => [
    -coveredSkills(a, required),
    // a template that does not need a coding agent prefers a model answer agent
    !coding && a.harness ? 1 : 0,
    a.key === preferred ? 0 : 1,
    a.builtIn ? 0 : 1,
  ];
  const sorted = [...candidates].sort((x, y) => {
    const rx = rank(x);
    const ry = rank(y);
    for (let i = 0; i < rx.length; i++) if (rx[i] !== ry[i]) return rx[i] - ry[i];
    return (x.name ?? '').localeCompare(y.name ?? '');
  });
  return sorted[0] ?? null;
}
