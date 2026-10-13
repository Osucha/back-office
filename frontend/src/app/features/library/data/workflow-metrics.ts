/**
 * Pure shaping of the workflow metrics (ADR 0037, `GET /insights/workflows` and `/insights/library-workflows`)
 * for the Library: headline cards, the version comparison with trends, node hotspots and the list rows.
 * Definitions: docs/architecture/11-governance.md, section "Workflow metrics".
 */
import type {
  LibraryWorkflowMetricsRow,
  LibraryWorkflowSummary,
  WorkflowMetrics,
  WorkflowNodeMetrics,
  WorkflowVersionMetrics,
} from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { formatCost } from '../../../core/ui/relay/format';
import { formatRate, formatWait } from '../../insights/public-api';

export interface MetricCard {
  id: 'success' | 'firstPass' | 'duration' | 'active' | 'cost' | 'apiCost';
  label: string;
  value: string;
  sub: string;
  hint: string;
}

/** Runs that ended (successful, failed or cancelled). */
export function finishedRuns(m: WorkflowMetrics | null | undefined): number {
  const r = m?.runs;
  return (r?.successful ?? 0) + (r?.failed ?? 0) + (r?.cancelled ?? 0);
}

/** Effectiveness first (success, first pass), then efficiency (time, active time, cost). */
export function metricCards(m: WorkflowMetrics): MetricCard[] {
  const finished = finishedRuns(m);
  return [
    {
      id: 'success',
      label: $localize`:@@library.metrics.success:Success rate`,
      value: formatRate(m.successRate),
      sub: icu(
        $localize`:@@library.metrics.successSub:{successful} of {count, plural, one {# finished run} other {# finished runs}}`,
        { successful: m.runs?.successful ?? 0, count: finished },
      ),
      hint: $localize`:@@library.metrics.successHint:Runs that reached a success end, of all finished runs (failed and cancelled count against it).`,
    },
    {
      id: 'firstPass',
      label: $localize`:@@library.metrics.firstPass:First-pass rate`,
      value: formatRate(m.firstPassRate),
      sub: $localize`:@@library.metrics.reworkSub:${formatLoops(m.reworkLoopsPerRun)}:loops: rework loops per run`,
      hint: $localize`:@@library.metrics.firstPassHint:Successful runs without a "revise" decision or a retried step, of all finished runs.`,
    },
    {
      id: 'duration',
      label: $localize`:@@library.metrics.duration:Median time to finish`,
      value: formatWait(m.duration?.p50Seconds),
      sub: m.duration?.count ? `p90 ${formatWait(m.duration.p90Seconds)}` : noData(),
      hint: $localize`:@@library.metrics.durationHint:From the start of a run to its end, waits included (cancelled runs excluded).`,
    },
    {
      id: 'active',
      label: $localize`:@@library.metrics.active:Median active time`,
      value: formatWait(m.activeTime?.p50Seconds),
      sub: m.humanWait?.count
        ? $localize`:@@library.metrics.humanWaitSub:human wait p50 ${formatWait(m.humanWait.p50Seconds)}:wait:`
        : $localize`:@@library.metrics.noGates:no gates decided`,
      hint: $localize`:@@library.metrics.activeHint:Time to finish minus the time spent waiting for people (gates) and CI: what the agents and steps take.`,
    },
    {
      id: 'cost',
      label: $localize`:@@library.metrics.cost:Cost per successful run`,
      value: m.runs?.successful ? formatCost(m.costPerSuccessfulRun) : '-',
      sub: $localize`:@@library.metrics.costSub:${formatCost(m.totalCost)}:total: in all · ${formatCost(m.costPerRun)}:perRun: per run`,
      hint: $localize`:@@library.metrics.costHint:Ledger cost of the successful runs divided by their number.`,
    },
    {
      id: 'apiCost',
      label: $localize`:@@library.metrics.apiCost:At API prices per successful run`,
      value: m.runs?.successful ? formatCost(m.apiCostPerSuccessfulRun) : '-',
      sub: $localize`:@@library.metrics.apiCostSub:${formatCost(m.totalApiCost)}:total: in all · ${formatCost(m.apiCostPerRun)}:perRun: per run`,
      hint: $localize`:@@library.metrics.apiCostHint:The same runs at API list prices: the cost plus what subscriptions paid for (Claude Code with its owner's own login on a personal runner). Use it to compare workflows whoever pays.`,
    },
  ];
}

function noData(): string {
  return $localize`:@@library.metrics.noFinished:no finished runs`;
}

export function formatLoops(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  return (Math.round(value * 100) / 100).toString();
}

export type Trend = 'better' | 'worse' | 'same';

export interface VersionRow {
  version: number;
  metrics: WorkflowMetrics;
  /** Compared with the next older version that has finished runs; null without one. */
  successTrend: Trend | null;
  durationTrend: Trend | null;
  costTrend: Trend | null;
  /** Fewer than [MIN_RUNS_FOR_TREND] finished runs: trends are shown but flagged as weak. */
  fewRuns: boolean;
}

/** Below this many finished runs a version's numbers are too noisy to call a trend reliable. */
export const MIN_RUNS_FOR_TREND = 5;

/** Relative change that counts as a change in duration or cost (10 %); success rate uses 5 points. */
const RELATIVE_THRESHOLD = 0.1;
const RATE_THRESHOLD = 0.05;

/** Versions newest first, each compared with the next older one that has finished runs. */
export function versionRows(byVersion: WorkflowVersionMetrics[] | null | undefined): VersionRow[] {
  const rows = [...(byVersion ?? [])]
    .filter((v) => v.version !== undefined && v.metrics)
    .sort((a, b) => (b.version ?? 0) - (a.version ?? 0));
  return rows.map((v, i) => {
    const m = v.metrics!;
    const older = rows.slice(i + 1).find((o) => finishedRuns(o.metrics) > 0)?.metrics ?? null;
    return {
      version: v.version!,
      metrics: m,
      successTrend: older
        ? higherIsBetter(m.successRate, older.successRate, RATE_THRESHOLD, true)
        : null,
      durationTrend: older
        ? lowerIsBetter(m.duration?.p50Seconds, older.duration?.p50Seconds)
        : null,
      costTrend:
        older && m.runs?.successful && older.runs?.successful
          ? lowerIsBetter(m.costPerSuccessfulRun?.amount, older.costPerSuccessfulRun?.amount)
          : null,
      fewRuns: finishedRuns(m) < MIN_RUNS_FOR_TREND,
    };
  });
}

function higherIsBetter(
  now: number | null | undefined,
  before: number | null | undefined,
  threshold: number,
  absolute: boolean,
): Trend | null {
  if (now === null || now === undefined || before === null || before === undefined) return null;
  const delta = absolute ? now - before : before === 0 ? 0 : (now - before) / before;
  if (Math.abs(delta) < threshold) return 'same';
  return delta > 0 ? 'better' : 'worse';
}

function lowerIsBetter(
  now: number | null | undefined,
  before: number | null | undefined,
): Trend | null {
  const t = higherIsBetter(now, before, RELATIVE_THRESHOLD, false);
  return t === 'better' ? 'worse' : t === 'worse' ? 'better' : t;
}

export interface NodeRow extends WorkflowNodeMetrics {
  /** A step that fails often (>= 25 % of at least 3 attempts) or takes the largest share of the cost. */
  hotspot: 'failures' | 'cost' | null;
  portsText: string;
}

export function nodeRows(nodes: WorkflowNodeMetrics[] | null | undefined): NodeRow[] {
  const list = nodes ?? [];
  const totalCost = list.reduce((s, n) => s + (n.cost?.amount ?? 0), 0);
  const top = list.reduce<WorkflowNodeMetrics | null>(
    (best, n) => ((n.cost?.amount ?? 0) > (best?.cost?.amount ?? 0) ? n : best),
    null,
  );
  return list.map((n) => {
    const attempts = (n.succeeded ?? 0) + (n.failed ?? 0);
    const failing = attempts >= 3 && (n.failureRate ?? 0) >= 0.25;
    const costly = !!top && n === top && totalCost > 0 && (n.cost?.amount ?? 0) / totalCost >= 0.5;
    return {
      ...n,
      hotspot: failing ? 'failures' : costly ? 'cost' : null,
      portsText: (n.ports ?? []).map((p) => `${p.port} ${p.count}`).join(' · '),
    };
  });
}

export interface LibraryRow extends LibraryWorkflowSummary {
  metrics: WorkflowMetrics | null;
}

/** The library list joined with the headline metrics of the period (entries without runs get null). */
export function libraryRows(
  list: LibraryWorkflowSummary[],
  metrics: LibraryWorkflowMetricsRow[],
): LibraryRow[] {
  const byId = new Map(metrics.map((m) => [m.libraryWorkflowId, m.metrics ?? null]));
  return list.map((w) => ({ ...w, metrics: byId.get(w.id) ?? null }));
}

/** Case-insensitive match on name, key, description and tags; deprecated entries only when asked. */
export function filterLibraryWorkflows(
  rows: LibraryRow[],
  query: string | null | undefined,
  showDeprecated: boolean,
): LibraryRow[] {
  const q = (query ?? '').trim().toLowerCase();
  return rows.filter(
    (w) =>
      (showDeprecated || !w.deprecated) &&
      (!q ||
        [w.name, w.key, w.description ?? '', ...(w.tags ?? [])].some((s) =>
          s.toLowerCase().includes(q),
        )),
  );
}

/** "Update available" when the library has a newer version than the one a linked copy follows. */
export function updateAvailable(
  link: { version: number; latestVersion: number } | null | undefined,
): boolean {
  return !!link && link.latestVersion > link.version;
}
