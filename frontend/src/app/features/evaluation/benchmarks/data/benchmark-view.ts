/**
 * Benchmark status and progress in the Relay vocabulary (pure; tested in benchmark-view.spec.ts).
 * Attempts use `batonOf.attempt` from core; a benchmark maps the same way: running -> agent,
 * completed -> done, failed -> blocked, draft / cancelled -> idle.
 */
import type {
  Attempt,
  AttemptStatus,
  BenchmarkStatus,
  BenchmarkSummary,
} from '../../../../core/api/types';
import { icu } from '../../../../core/i18n/icu';
import { batonOf, type Baton, type BatonHolder } from '../../../../core/ui/relay/baton';

export function benchmarkBaton(status: BenchmarkStatus | null | undefined): Baton {
  switch (status) {
    case 'RUNNING':
      return {
        holder: 'agent',
        label: $localize`:@@evaluation.benchmark.status.running:running`,
        live: true,
      };
    case 'COMPLETED':
      return {
        holder: 'done',
        label: $localize`:@@evaluation.benchmark.status.completed:completed`,
      };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@evaluation.benchmark.status.failed:failed` };
    case 'CANCELLED':
      return {
        holder: 'idle',
        label: $localize`:@@evaluation.benchmark.status.cancelled:cancelled`,
      };
    case 'DRAFT':
    default:
      return { holder: 'idle', label: $localize`:@@evaluation.benchmark.status.draft:draft` };
  }
}

/** The holders the benchmarks filter offers. */
export const BENCHMARK_HOLDERS: BatonHolder[] = ['agent', 'done', 'blocked', 'idle'];

export function benchmarkHolder(b: Pick<BenchmarkSummary, 'status'>): BatonHolder {
  return benchmarkBaton(b.status).holder;
}

export interface ProgressSegment {
  holder: BatonHolder;
  label: string;
  count: number;
  /** Share of the total, 0..100. */
  percent: number;
}

/**
 * Attempt progress as segments (succeeded, failed, left). `done` counts every finished attempt,
 * failed ones included; what is left is agent work while the benchmark runs, idle otherwise.
 */
export function progressSegments(
  progress: BenchmarkSummary['progress'] | null | undefined,
  status: BenchmarkStatus | null | undefined,
): ProgressSegment[] {
  const total = Math.max(0, progress?.total ?? 0);
  if (!total) return [];
  const done = Math.min(total, Math.max(0, progress?.done ?? 0));
  const failed = Math.min(done, Math.max(0, progress?.failed ?? 0));
  const parts: Omit<ProgressSegment, 'percent'>[] = [
    {
      holder: 'done',
      label: $localize`:@@evaluation.progress.succeeded:succeeded`,
      count: done - failed,
    },
    { holder: 'blocked', label: $localize`:@@evaluation.progress.failed:failed`, count: failed },
    {
      holder: status === 'RUNNING' ? 'agent' : 'idle',
      label:
        status === 'RUNNING'
          ? $localize`:@@evaluation.progress.toGo:to go`
          : $localize`:@@evaluation.progress.notRun:not run`,
      count: total - done,
    },
  ];
  return parts.filter((p) => p.count > 0).map((p) => ({ ...p, percent: (p.count / total) * 100 }));
}

/** "104 / 120 attempts · 6 failed", "not started", "12 attempts". */
export function progressText(
  progress: BenchmarkSummary['progress'] | null | undefined,
  status: BenchmarkStatus | null | undefined,
): string {
  const total = progress?.total ?? 0;
  if (!total)
    return status === 'DRAFT'
      ? $localize`:@@evaluation.progress.notStarted:not started`
      : $localize`:@@evaluation.progress.noAttempts:no attempts`;
  const done = progress?.done ?? 0;
  const failed = progress?.failed ?? 0;
  const head =
    done >= total
      ? icu(
          $localize`:@@evaluation.progress.attempts:{count, plural, one {# attempt} other {# attempts}}`,
          { count: total },
        )
      : icu(
          $localize`:@@evaluation.progress.attemptsOf:{done} / {count, plural, one {# attempt} other {# attempts}}`,
          { done, count: total },
        );
  return failed
    ? head +
        ' · ' +
        icu($localize`:@@evaluation.progress.failedCount:{count, plural, other {# failed}}`, {
          count: failed,
        })
    : head;
}

export interface HolderCount {
  holder: BatonHolder;
  label: string;
  count: number;
}

/** Attempts per status word ("3 running", "2 failed"), in baton order, for the legend. */
export function attemptCounts(attempts: readonly Pick<Attempt, 'status'>[]): HolderCount[] {
  const order: AttemptStatus[] = [
    'RUNNING',
    'JUDGING',
    'SUCCEEDED',
    'FAILED',
    'TIMED_OUT',
    'PENDING',
  ];
  const counts = new Map<AttemptStatus, number>();
  for (const a of attempts) {
    const s = (a.status ?? 'PENDING') as AttemptStatus;
    counts.set(s, (counts.get(s) ?? 0) + 1);
  }
  return order
    .filter((s) => counts.get(s))
    .map((s) => {
      const b = batonOf.attempt(s);
      return { holder: b.holder, label: b.label, count: counts.get(s)! };
    });
}

/** Attempts whose holder is one of `holders` (all when empty). */
export function filterAttemptsByHolder<T extends Pick<Attempt, 'status'>>(
  attempts: readonly T[],
  holders: readonly string[],
): T[] {
  if (!holders.length) return [...attempts];
  return attempts.filter((a) => holders.includes(batonOf.attempt(a.status).holder));
}
