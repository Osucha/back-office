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
import type { Baton, BatonHolder } from '../../../../core/ui/relay/baton';

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
  withFailed = true,
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
  return failed && withFailed
    ? head +
        ' · ' +
        icu($localize`:@@evaluation.progress.failedCount:{count, plural, other {# failed}}`, {
          count: failed,
        })
    : head;
}

/**
 * The state of an attempt as the attempts tab shows it: only exceptions get a tag ("timed out", "error", "running",
 * "judging", "waiting"); a succeeded attempt gets none (null).
 */
export function attemptStatusBaton(status: AttemptStatus | null | undefined): Baton | null {
  switch (status) {
    case 'SUCCEEDED':
      return null;
    case 'TIMED_OUT':
      return {
        holder: 'blocked',
        label: $localize`:@@evaluation.attempt.state.timedOut:timed out`,
      };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@evaluation.attempt.state.failed:error` };
    case 'RUNNING':
      return {
        holder: 'agent',
        label: $localize`:@@evaluation.attempt.state.running:running`,
        live: true,
      };
    case 'JUDGING':
      return {
        holder: 'agent',
        label: $localize`:@@evaluation.attempt.state.judging:judging`,
        live: true,
      };
    case 'PENDING':
    default:
      return { holder: 'idle', label: $localize`:@@evaluation.attempt.state.pending:waiting` };
  }
}

/** The outcome filter of the attempts tab. */
export type AttemptResult = 'accepted' | 'notAccepted' | 'timedOut' | 'failed';

export const ATTEMPT_RESULTS: readonly AttemptResult[] = [
  'accepted',
  'notAccepted',
  'timedOut',
  'failed',
];

/** Accepted (passed), not accepted (finished and scored below the bar), timed out, error; null while running. */
export function attemptResult(a: Pick<Attempt, 'status' | 'passed'>): AttemptResult | null {
  if (a.status === 'TIMED_OUT') return 'timedOut';
  if (a.status === 'FAILED') return 'failed';
  if (a.passed === true) return 'accepted';
  if (a.status === 'SUCCEEDED') return 'notAccepted';
  return null;
}

export function attemptResultLabel(r: AttemptResult): string {
  switch (r) {
    case 'accepted':
      return $localize`:@@evaluation.attempt.result.accepted:accepted`;
    case 'notAccepted':
      return $localize`:@@evaluation.attempt.result.notAccepted:not accepted`;
    case 'timedOut':
      return $localize`:@@evaluation.attempt.state.timedOut:timed out`;
    case 'failed':
      return $localize`:@@evaluation.attempt.state.failed:error`;
  }
}

export interface SummaryPart {
  holder: BatonHolder;
  count: number;
  /** "34 done", "2 timed out" with the plural of the UI language. */
  text: string;
}

/**
 * Attempts in words for the progress line and the attempts tab, in one vocabulary: done, timed out, errors, running
 * (judging included), waiting. Empty kinds are left out.
 */
export function attemptSummary(attempts: readonly Pick<Attempt, 'status'>[]): SummaryPart[] {
  const count = (...s: AttemptStatus[]) =>
    attempts.filter((a) => s.includes((a.status ?? 'PENDING') as AttemptStatus)).length;
  const parts: SummaryPart[] = [
    {
      holder: 'done',
      count: count('SUCCEEDED'),
      text: $localize`:@@evaluation.summary.done:{count, plural, one {# done} other {# done}}`,
    },
    {
      holder: 'blocked',
      count: count('TIMED_OUT'),
      text: $localize`:@@evaluation.summary.timedOut:{count, plural, one {# timed out} other {# timed out}}`,
    },
    {
      holder: 'blocked',
      count: count('FAILED'),
      text: $localize`:@@evaluation.summary.failed:{count, plural, one {# error} other {# errors}}`,
    },
    {
      holder: 'agent',
      count: count('RUNNING', 'JUDGING'),
      text: $localize`:@@evaluation.summary.running:{count, plural, one {# running} other {# running}}`,
    },
    {
      holder: 'idle',
      count: count('PENDING'),
      text: $localize`:@@evaluation.summary.pending:{count, plural, one {# waiting} other {# waiting}}`,
    },
  ];
  return parts
    .filter((p) => p.count > 0)
    .map((p) => ({ ...p, text: icu(p.text, { count: p.count }) }));
}
