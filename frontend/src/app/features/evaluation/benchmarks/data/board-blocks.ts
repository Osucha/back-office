/**
 * The leaderboard split by kind of work (pure; tested in board-blocks.spec.ts). A model answer agent (no harness)
 * answers prompt tasks, a coding agent (Claude Code, OpenCode) does coding tasks: their scores come from different
 * tasks, so they are ranked, charted and compared only within their own block. Within a block the rows are ordered by
 * cost per accepted result (cheapest first, rows without an accepted result last).
 */
import type {
  Attempt,
  BenchmarkCandidateRef,
  Leaderboard,
  LeaderboardRow,
} from '../../../../core/api/types';
import { agentKindLabel } from '../../../../core/ui/agents';
import { compareText } from '../../../../core/i18n/compare';
import { icu } from '../../../../core/i18n/icu';
import { boardCostPerAccepted, comparesOnApi } from './board-cost';

/** The kind of work of a candidate: a model answer or a coding agent. */
export type WorkKind = 'answer' | 'coding';

export const WORK_KINDS: readonly WorkKind[] = ['answer', 'coding'];

/** A row's kind from its candidate's harness; a row without a candidate (older benchmarks) is a model answer. */
export function rowKind(r: Pick<LeaderboardRow, 'candidate'>): WorkKind {
  return r.candidate?.harness ? 'coding' : 'answer';
}

/** An attempt's kind from its task kind, else from its candidate's harness. */
export function attemptKind(a: Pick<Attempt, 'kind' | 'candidate'>): WorkKind {
  if (a.kind === 'CODING_JOB') return 'coding';
  if (a.kind === 'PROMPT') return 'answer';
  return a.candidate?.harness ? 'coding' : 'answer';
}

export interface BoardBlock {
  kind: WorkKind;
  /** The harnesses of the block's candidates, in order of appearance (coding blocks). */
  harnesses: string[];
  rows: LeaderboardRow[];
}

/** Rows grouped by kind of work, model answers first; kinds without a row are left out. */
export function splitByKind(rows: readonly LeaderboardRow[] | null | undefined): BoardBlock[] {
  return WORK_KINDS.map((kind) => {
    const own = (rows ?? []).filter((r) => rowKind(r) === kind);
    const harnesses: string[] = [
      ...new Set(own.map((r) => r.candidate?.harness ?? '').filter(Boolean)),
    ];
    return { kind, harnesses, rows: own };
  }).filter((b) => b.rows.length > 0);
}

/** Passed attempts of a row (pass rate × terminal attempts). */
export function passedCount(r: Pick<LeaderboardRow, 'passRate' | 'attempts'>): number {
  return Math.round((r.passRate ?? 0) * (r.attempts ?? 0));
}

/** Cost per accepted result ascending, rows without one last; ties: higher score, then name. */
export function rankRows(rows: readonly LeaderboardRow[]): LeaderboardRow[] {
  return [...rows].sort((a, b) => {
    const ca = boardCostPerAccepted(a)?.amount;
    const cb = boardCostPerAccepted(b)?.amount;
    const na = ca === null || ca === undefined;
    const nb = cb === null || cb === undefined;
    if (na !== nb) return na ? 1 : -1;
    if (!na && !nb && ca !== cb) return ca! - cb!;
    const sa = a.meanScore ?? -1;
    const sb = b.meanScore ?? -1;
    if (sa !== sb) return sb - sa;
    return compareText(a.modelDisplayName ?? '', b.modelDisplayName ?? '');
  });
}

/** True when the rows come from more than one agent (Claude Code and OpenCode in one coding block). */
export function mixedAgents(rows: readonly Pick<LeaderboardRow, 'candidate'>[]): boolean {
  return new Set(rows.map((r) => r.candidate?.agentKey ?? '')).size > 1;
}

/**
 * True when every candidate that made attempts ran on a subscription (ADR 0065): nothing was spent, so the page shows
 * only the figures at API prices and says so once instead of "$0.00 · API $1.14" in every cell.
 */
export function allOnSubscription(rows: readonly LeaderboardRow[] | null | undefined): boolean {
  const ran = (rows ?? []).filter((r) => (r.attempts ?? 0) > 0);
  return ran.length > 0 && ran.every((r) => comparesOnApi(r));
}

export interface TaskCountInput {
  attempts?: readonly Pick<Attempt, 'taskId' | 'kind' | 'candidate'>[] | null;
  leaderboard?: Pick<Leaderboard, 'overall' | 'byTask'> | null;
  candidates?: readonly Pick<BenchmarkCandidateRef, 'harness'>[] | null;
  repetitions?: number | null;
  totalAttempts?: number | null;
}

/**
 * How many tasks each kind of candidate attempts (repetitions count once):
 * 1. the distinct tasks of the attempts of that kind;
 * 2. else the other kind's "not applicable" count (every task is one kind or the other);
 * 3. else, with one kind only, all attempts / (repetitions × candidates);
 * 4. else the tasks of the leaderboard by task whose rows are of that kind.
 */
export function tasksPerKind(input: TaskCountInput): Record<WorkKind, number | null> {
  const out: Record<WorkKind, number | null> = { answer: null, coding: null };
  const attempts = input.attempts ?? [];
  const rows = input.leaderboard?.overall ?? [];
  const kinds = new Set<WorkKind>([
    ...rows.map(rowKind),
    ...(input.candidates ?? []).map((c) => (c.harness ? 'coding' : 'answer') as WorkKind),
  ]);
  for (const kind of WORK_KINDS) {
    const tasks = new Set(
      attempts.filter((a) => attemptKind(a) === kind && a.taskId).map((a) => a.taskId),
    );
    if (tasks.size) {
      out[kind] = tasks.size;
      continue;
    }
    if (attempts.length && !kinds.has(kind)) continue;
    const other = rows.find((r) => rowKind(r) !== kind && (r.notApplicable ?? 0) > 0);
    if (other) {
      out[kind] = other.notApplicable!;
      continue;
    }
    const candidates = (input.candidates ?? []).filter(
      (c) => (c.harness ? 'coding' : 'answer') === kind,
    ).length;
    const reps = Math.max(1, input.repetitions ?? 1);
    const total = input.totalAttempts ?? 0;
    if (kinds.size === 1 && candidates && total && total % (reps * candidates) === 0) {
      out[kind] = total / (reps * candidates);
      continue;
    }
    const byTask = (input.leaderboard?.byTask ?? []).filter((t) =>
      (t.rows ?? []).some((r) => rowKind(r) === kind),
    ).length;
    if (byTask) out[kind] = byTask;
  }
  return out;
}

/** "Model answer", "Coding agent (Claude Code)", "Coding agent (Claude Code, OpenCode)". */
export function kindName(kind: WorkKind, harnesses: readonly string[] = []): string {
  if (kind === 'answer') return $localize`:@@evaluation.board.kind.answer:Model answer`;
  if (!harnesses.length) return $localize`:@@evaluation.board.kind.coding:Coding agent`;
  const list = harnesses.map((h) => agentKindLabel(h)).join(', ');
  return $localize`:@@evaluation.board.kind.codingWith:Coding agent (${list}:harnesses:)`;
}

/** "Model answer · 7 tasks"; just the kind when the number of tasks is not known. */
export function blockTitle(
  block: Pick<BoardBlock, 'kind' | 'harnesses'>,
  tasks: number | null,
): string {
  const name = kindName(block.kind, block.harnesses);
  if (tasks === null) return name;
  return (
    name +
    ' · ' +
    icu($localize`:@@evaluation.board.tasks:{count, plural, one {# task} other {# tasks}}`, {
      count: tasks,
    })
  );
}

/** What a block answers first: the cheapest accepted result and, when another candidate has it, the best score. */
export function blockVerdict(rows: readonly LeaderboardRow[]): {
  cheapest: LeaderboardRow | null;
  best: LeaderboardRow | null;
} {
  const cheapest = rankRows(rows).find((r) => boardCostPerAccepted(r) !== null) ?? null;
  const scored = rows.filter((r) => r.meanScore !== null && r.meanScore !== undefined);
  const top = scored.reduce<LeaderboardRow | null>(
    (best, r) => (!best || r.meanScore! > best.meanScore! ? r : best),
    null,
  );
  return { cheapest, best: top && top !== cheapest ? top : null };
}

/** Below half of the attempts accepted: cheap may still be unreliable. */
export function unreliable(r: Pick<LeaderboardRow, 'passRate' | 'attempts'>): boolean {
  return (r.attempts ?? 0) > 0 && (r.passRate ?? 0) < 0.5;
}

const BUILT_IN_AGENTS = new Set(['model-answer', 'claude-code', 'opencode']);

/** The agent in words: the built-in agents in the UI language ("Model answer", "Claude Code"), others by name. */
export function agentLabel(
  c: Pick<BenchmarkCandidateRef, 'agentKey' | 'agentName' | 'harness'> | null | undefined,
): string {
  if (!c) return kindName('answer');
  if (BUILT_IN_AGENTS.has(c.agentKey))
    return c.harness ? agentKindLabel(c.harness) : kindName('answer');
  return c.agentName || (c.harness ? agentKindLabel(c.harness) : kindName('answer'));
}
