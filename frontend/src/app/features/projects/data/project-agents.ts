/**
 * ADR 0060: the project tab Agents as pure functions (tested in project-agents.spec.ts). A project uses the global
 * agents and its own; a project agent with the key of a global one replaces it in the project's steps (like template
 * customisation).
 */
import type { Agent } from '../../../core/api/types';

export interface ProjectAgentRow {
  agent: Agent;
  /** The agent belongs to this project (else global). */
  own: boolean;
  /** A project agent that replaces the global agent of its key. */
  replacesGlobal: boolean;
  /** A global agent the project replaces with its own copy (not used by the project's steps). */
  replaced: boolean;
}

/** The agents of `GET /agents?projectId=` as rows: the project's own first, then the global ones, by name. */
export function projectAgentRows(agents: readonly Agent[], projectId: string): ProjectAgentRow[] {
  const own = agents.filter((a) => a.scope?.type === 'PROJECT' && a.scope.projectId === projectId);
  const global = agents.filter((a) => a.scope?.type !== 'PROJECT');
  const ownKeys = new Set(own.map((a) => a.key));
  const globalKeys = new Set(global.map((a) => a.key));
  const byName = (a: Agent, b: Agent) => a.name.localeCompare(b.name);
  return [
    ...[...own].sort(byName).map((agent) => ({
      agent,
      own: true,
      replacesGlobal: globalKeys.has(agent.key),
      replaced: false,
    })),
    ...[...global].sort(byName).map((agent) => ({
      agent,
      own: false,
      replacesGlobal: false,
      replaced: ownKeys.has(agent.key),
    })),
  ];
}

/** Global agents the project can import (it has no copy of them yet), by name. */
export function importableAgents(agents: readonly Agent[], projectId: string): Agent[] {
  const ownKeys = new Set(
    agents
      .filter((a) => a.scope?.type === 'PROJECT' && a.scope.projectId === projectId)
      .map((a) => a.key),
  );
  return agents
    .filter((a) => a.scope?.type !== 'PROJECT' && !ownKeys.has(a.key))
    .sort((a, b) => a.name.localeCompare(b.name));
}
