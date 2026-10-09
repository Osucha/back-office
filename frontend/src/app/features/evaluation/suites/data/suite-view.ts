/** Task suite list and detail helpers (pure; tested in suite-view.spec.ts). */
import type { EvalTask, TaskSuiteSummary } from '../../../../core/api/types';
import { formatNumber } from '../../../../core/i18n/numbers';
import { icu } from '../../../../core/i18n/icu';
import { humanize } from '../../../../core/ui/status-tag.component';

/** Task categories in the UI language (unknown values are humanized). */
const CATEGORY_LABELS: Record<string, string> = {
  CODING: $localize`:@@evaluation.category.coding:Coding`,
  ANALYSIS: $localize`:@@evaluation.category.analysis:Analysis`,
  SPECIFICATION: $localize`:@@evaluation.category.specification:Specification`,
  REVIEW: $localize`:@@evaluation.category.review:Review`,
  ARCHITECTURE: $localize`:@@evaluation.category.architecture:Architecture`,
};

export function categoryLabel(value: string | null | undefined): string {
  return (value && CATEGORY_LABELS[value]) || humanize(value);
}

export function filterSuites(
  list: readonly TaskSuiteSummary[],
  query: string | null | undefined,
): TaskSuiteSummary[] {
  const q = (query ?? '').trim().toLowerCase();
  if (!q) return [...list];
  return list.filter((s) => [s.name, s.description ?? ''].some((f) => f.toLowerCase().includes(q)));
}

/** "verification 0.6 · judge 0.4" (defaults when the suite has none). */
export function weightsText(s: Pick<TaskSuiteSummary, 'codingScoreWeights'>): string {
  const v = s.codingScoreWeights?.verification ?? 0.6;
  const j = s.codingScoreWeights?.judge ?? 0.4;
  const verification = formatNumber(round(v));
  const judge = formatNumber(round(j));
  return $localize`:@@evaluation.suite.weights:verification ${verification}:verification: · judge ${judge}:judge:`;
}

export interface SuiteStats {
  tasks: number;
  prompt: number;
  coding: number;
  totalWeight: number;
}

export function suiteStats(tasks: readonly Pick<EvalTask, 'kind' | 'weight'>[]): SuiteStats {
  return {
    tasks: tasks.length,
    prompt: tasks.filter((t) => t.kind === 'PROMPT').length,
    coding: tasks.filter((t) => t.kind === 'CODING_JOB').length,
    totalWeight: round(tasks.reduce((a, t) => a + (t.weight ?? 1), 0)),
  };
}

/** Tasks matching the search (name, category, repository) and kind. */
export function filterTasks(
  tasks: readonly EvalTask[],
  opts: { query?: string | null; kind?: string | null },
): EvalTask[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return tasks.filter(
    (t) =>
      (!opts.kind || t.kind === opts.kind) &&
      (!q ||
        [t.name, t.category ?? '', t.repository?.url ?? ''].some((f) =>
          f.toLowerCase().includes(q),
        )),
  );
}

/** "Deleting the suite removes its 12 tasks; benchmarks keep their results." */
export function deleteSuiteConsequence(taskCount: number): string {
  return taskCount === 0
    ? $localize`:@@evaluation.suite.deleteNoTasks:It has no tasks; benchmarks that used the suite keep their results.`
    : icu(
        $localize`:@@evaluation.suite.deleteTasks:It removes its {count, plural, one {# task} other {# tasks}}; benchmarks that used the suite keep their results.`,
        { count: taskCount },
      );
}

function round(n: number): number {
  return Math.round(n * 100) / 100;
}
