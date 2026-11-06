/**
 * Pure shaping of `GET /insights/agents` (ADR 0059) for the Agents insights page: one row per agent
 * and model, cheapest accepted result first, and the same rows per template so agents are compared
 * on the same task.
 */
import type { AgentInsights, AgentMetrics } from '../../../core/api/types';
import { agentKindLabel } from '../../../core/ui/agents';

export interface AgentRow extends AgentMetrics {
  /** agent key and model id: unique within one table. */
  key: string;
  /** "Claude Code", "OpenCode", "Model answer". */
  kind: string;
  /** "-" when the agent ran with no model record. */
  model: string;
}

export interface TemplateGroup {
  templateKey: string;
  title: string;
  rows: AgentRow[];
  executions: number;
}

export function agentRowKey(m: Pick<AgentMetrics, 'agentKey' | 'modelProfileId'>): string {
  return `${m.agentKey}|${m.modelProfileId ?? ''}`;
}

/** Lowest cost per accepted result first; rows without an accepted result last, most executions first. */
export function compareAgentRows(a: AgentMetrics, b: AgentMetrics): number {
  const ca = a.costPerAccepted?.amount;
  const cb = b.costPerAccepted?.amount;
  const ea = ca === null || ca === undefined;
  const eb = cb === null || cb === undefined;
  if (ea !== eb) return ea ? 1 : -1;
  if (!ea && !eb && ca !== cb) return ca! - cb!;
  return (b.executions ?? 0) - (a.executions ?? 0) || a.agentName.localeCompare(b.agentName);
}

export function agentRows(rows: readonly AgentMetrics[] | null | undefined): AgentRow[] {
  return [...(rows ?? [])].sort(compareAgentRows).map((m) => ({
    ...m,
    key: agentRowKey(m),
    kind: agentKindLabel(m.harness),
    model: m.modelLabel || '-',
  }));
}

/** Templates with the most executions first; each with its agents, cheapest accepted result first. */
export function templateGroups(i: AgentInsights | null | undefined): TemplateGroup[] {
  return (i?.byTemplate ?? [])
    .map((t) => {
      const rows = agentRows(t.rows);
      return {
        templateKey: t.templateKey,
        title: t.templateName || t.templateKey,
        rows,
        executions: rows.reduce((s, r) => s + (r.executions ?? 0), 0),
      };
    })
    .filter((t) => t.rows.length)
    .sort((a, b) => b.executions - a.executions || a.title.localeCompare(b.title));
}

/** Is there anything to show? */
export function hasAgentData(i: AgentInsights | null | undefined): boolean {
  return !!i && (!!i.byAgent?.length || !!i.unattributed);
}

/** Accepted / executions (0..1); null without executions. */
export function acceptedShare(m: Pick<AgentMetrics, 'accepted' | 'executions'>): number | null {
  return m.executions ? m.accepted / m.executions : null;
}
