import type { RunStatus, WorkflowRunSummary } from '../../../core/api/types';
import { batonOf, type BatonHolder } from '../../../core/ui/relay/baton';

/** Server-side statuses that can produce each holder (the list API filters by status only). */
const STATUSES_OF: Record<BatonHolder, RunStatus[]> = {
  agent: ['RUNNING'],
  you: ['WAITING'],
  ci: ['RUNNING', 'WAITING'],
  done: ['SUCCEEDED'],
  blocked: ['FAILED', 'SUCCEEDED'],
  idle: ['PENDING', 'CANCELLED', 'WAITING'],
};

export const RUN_HOLDERS: BatonHolder[] = ['agent', 'you', 'ci', 'done', 'blocked', 'idle'];

/** Status filter for the API from holder chips (empty = no filter). */
export function statusesForHolders(holders: readonly string[]): RunStatus[] {
  const out = new Set<RunStatus>();
  for (const h of holders) for (const s of STATUSES_OF[h as BatonHolder] ?? []) out.add(s);
  return [...out];
}

export interface RunClientFilter {
  holders: readonly string[];
  query: string | null;
  startedBy: string | null;
  /** The signed-in user's id (for `startedBy=me`). */
  me: string | null | undefined;
}

/** Filters a loaded page by holder, search text (issue key, id prefix, workflow) and starter. */
export function filterRuns(
  rows: readonly WorkflowRunSummary[],
  f: RunClientFilter,
): WorkflowRunSummary[] {
  const q = (f.query ?? '').trim().toLowerCase();
  return rows.filter((r) => {
    if (f.holders.length && !f.holders.includes(batonOf.run(r).holder)) return false;
    if (f.startedBy === 'me' && (!f.me || r.startedBy?.userId !== f.me)) return false;
    if (q) {
      const hay = [r.id, r.issueKey, r.workflowName, r.projectKey, r.currentNodeId]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      if (!q.split(/\s+/).every((t) => hay.includes(t))) return false;
    }
    return true;
  });
}
