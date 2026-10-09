/**
 * Per-repository results of runner steps (ADR 0034) as display rows: what the step did in each
 * repository (access, branch, commit, diff stat, merge request, pushed or not, error).
 */
import type { RepositoryResult } from '../../../core/api/types';
import { accessLabel, diffStatParts, purposeLabel, shortSha } from '../../../core/ui/repositories';

export type PushState = 'pushed' | 'notPushed' | 'readOnly' | 'failed';

export interface RepositoryRow {
  key: string;
  url: string | null;
  purpose: string;
  access: string;
  primary: boolean;
  branch: string | null;
  commit: string;
  commitSha: string | null;
  diff: { insertions: number; deletions: number; files: number } | null;
  mergeRequestUrl: string | null;
  state: PushState;
  error: string | null;
}

export function pushState(r: RepositoryResult): PushState {
  if (r.error) return 'failed';
  if (r.pushed) return 'pushed';
  return r.access === 'PUSH_MR' ? 'notPushed' : 'readOnly';
}

/** Rows in the order of the API (the project's display order); the primary is marked. */
export function repositoryRows(
  results: readonly RepositoryResult[] | null | undefined,
): RepositoryRow[] {
  return (results ?? []).map((r) => ({
    key: r.key,
    url: r.url ?? null,
    purpose: purposeLabel(r.purpose),
    access: accessLabel(r.access),
    primary: !!r.primary,
    branch: r.branch ?? null,
    commit: shortSha(r.commitSha),
    commitSha: r.commitSha ?? null,
    diff: diffStatParts(r),
    mergeRequestUrl: r.mergeRequestUrl ?? null,
    state: pushState(r),
    error: r.error ?? null,
  }));
}

export function pushStateLabel(state: PushState): string {
  switch (state) {
    case 'pushed':
      return $localize`:@@runs.repos.state.pushed:pushed`;
    case 'notPushed':
      return $localize`:@@runs.repos.state.notPushed:not pushed`;
    case 'failed':
      return $localize`:@@runs.repos.state.failed:failed`;
    default:
      return $localize`:@@runs.repos.state.readOnly:read only`;
  }
}
