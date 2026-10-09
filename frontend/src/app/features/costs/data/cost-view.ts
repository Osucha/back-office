/**
 * Costs page logic (pure; tested in cost-view.spec.ts): periods, the breakdown bar list, the daily
 * series, the trend chart option in token colours and the ledger filter.
 */
import type { Money, TokenUsage, UsageEntry, UsageGroup } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { intlLocale } from '../../../core/i18n/locale';
import { formatMoney } from '../../../core/ui/pipes/money.pipe';
import { formatTokens } from '../../../core/ui/pipes/tokens.pipe';
import { chartAxis, chartBase, type ChartTokens } from '../../../core/ui/relay/chart-tokens';

export type Period = '7d' | '30d' | 'month' | '90d' | 'custom';
export const PERIODS: { id: Exclude<Period, 'custom'>; label: string }[] = [
  { id: '7d', label: $localize`:@@costs.period.7d:7 days` },
  { id: '30d', label: $localize`:@@costs.period.30d:30 days` },
  { id: 'month', label: $localize`:@@costs.period.month:This month` },
  { id: '90d', label: $localize`:@@costs.period.90d:90 days` },
];

const DAY_MS = 86_400_000;

/** "2026-09-27" in local time. */
export function isoDay(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function parseDay(s: string | null | undefined): Date | null {
  if (!s || !/^\d{4}-\d{2}-\d{2}$/.test(s)) return null;
  const [y, m, d] = s.split('-').map(Number);
  const date = new Date(y, m - 1, d);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * The inclusive day range of a period: `from`/`to` (YYYY-MM-DD) win when both are valid,
 * otherwise the preset (default 30 days) ending today.
 */
export function rangeFor(
  period: string | null | undefined,
  from: string | null | undefined,
  to: string | null | undefined,
  now = new Date(),
): { period: Period; from: Date; to: Date } {
  const f = parseDay(from);
  const t = parseDay(to);
  if (f && t)
    return f <= t ? { period: 'custom', from: f, to: t } : { period: 'custom', from: t, to: f };
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (period === 'month')
    return { period: 'month', from: new Date(now.getFullYear(), now.getMonth(), 1), to: today };
  const days = period === '7d' ? 7 : period === '90d' ? 90 : 30;
  const start = new Date(today);
  start.setDate(today.getDate() - days + 1);
  return {
    period: (period === '7d' || period === '90d' ? period : '30d') as Period,
    from: start,
    to: today,
  };
}

/** Start of the first day and end of the last day, as ISO instants for the API. */
export function apiRange(range: { from: Date; to: Date }): { from: string; to: string } {
  const start = new Date(range.from);
  start.setHours(0, 0, 0, 0);
  const end = new Date(range.to);
  end.setHours(23, 59, 59, 999);
  return { from: start.toISOString(), to: end.toISOString() };
}

/** "Aug 29 – Sep 27, 2026" */
export function rangeLabel(range: { from: Date; to: Date }): string {
  const md: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric' };
  const a = range.from.toLocaleDateString(intlLocale(), md);
  const b = range.to.toLocaleDateString(intlLocale(), { ...md, year: 'numeric' });
  return `${a} – ${b}`;
}

export interface BreakdownRow {
  key: string;
  label: string;
  cost: Money;
  calls: number;
  usage?: TokenUsage;
  /** Share of the total, 0..100. */
  share: number;
  /** Bar length relative to the largest row, 0..100. */
  bar: number;
  /** The aggregated tail ("Other"). */
  other?: boolean;
}

/**
 * Breakdown rows: largest cost first (days in calendar order), the tail beyond `max` rows folded
 * into one "n others" row. Shares are of the summary total (or the sum when there is none).
 */
export function breakdownRows(
  groups: readonly UsageGroup[] | null | undefined,
  opts: { total?: Money | null; max?: number; chronological?: boolean } = {},
): BreakdownRow[] {
  const list = [...(groups ?? [])];
  const amount = (g: UsageGroup) => g.cost?.amount ?? 0;
  if (opts.chronological) list.sort((a, b) => (a.key ?? '').localeCompare(b.key ?? ''));
  else list.sort((a, b) => amount(b) - amount(a));
  const currency = opts.total?.currency ?? list.find((g) => g.cost)?.cost?.currency ?? 'USD';
  const sum = list.reduce((a, g) => a + amount(g), 0);
  const total = opts.total?.amount || sum;
  const max = opts.max ?? 8;
  let rows: Omit<BreakdownRow, 'share' | 'bar'>[] = list.map((g) => ({
    key: g.key ?? '',
    label: g.label || g.key || $localize`:@@costs.breakdown.unknown:unknown`,
    cost: { amount: amount(g), currency },
    calls: g.calls ?? 0,
    usage: g.usage,
  }));
  if (!opts.chronological && rows.length > max) {
    const head = rows.slice(0, max - 1);
    const tail = rows.slice(max - 1);
    rows = [
      ...head,
      {
        key: '__other',
        label: icu(
          $localize`:@@costs.breakdown.others:{count, plural, one {# other} other {# others}}`,
          {
            count: tail.length,
          },
        ),
        cost: { amount: tail.reduce((a, r) => a + r.cost.amount, 0), currency },
        calls: tail.reduce((a, r) => a + r.calls, 0),
        other: true,
      },
    ];
  }
  const top = Math.max(0, ...rows.map((r) => r.cost.amount));
  return rows.map((r) => ({
    ...r,
    share: total ? (r.cost.amount / total) * 100 : 0,
    bar: top ? (r.cost.amount / top) * 100 : 0,
  }));
}

export interface DayPoint {
  day: string;
  amount: number;
  calls: number;
}

/** One point per day of the range (missing days are zero), from DAY-grouped usage. */
export function dailySeries(
  groups: readonly UsageGroup[] | null | undefined,
  range: { from: Date; to: Date },
): DayPoint[] {
  const byDay = new Map<string, UsageGroup>();
  for (const g of groups ?? []) if (g.key) byDay.set(g.key.slice(0, 10), g);
  const out: DayPoint[] = [];
  const d = new Date(range.from.getFullYear(), range.from.getMonth(), range.from.getDate());
  const end = new Date(range.to.getFullYear(), range.to.getMonth(), range.to.getDate()).getTime();
  for (let i = 0; d.getTime() <= end && i < 400; i++) {
    const key = isoDay(d);
    const g = byDay.get(key);
    out.push({ day: key, amount: g?.cost?.amount ?? 0, calls: g?.calls ?? 0 });
    d.setTime(d.getTime() + DAY_MS);
    d.setHours(0, 0, 0, 0);
  }
  return out;
}

/** ECharts option for the cost-per-day bars (accent bars, hairline axes, token tooltip). */
export function trendOption(series: readonly DayPoint[], t: ChartTokens): Record<string, unknown> {
  const axis = chartAxis(t);
  const base = chartBase(t);
  return {
    ...base,
    grid: { left: 52, right: 8, top: 12, bottom: 24 },
    tooltip: {
      ...(base['tooltip'] as Record<string, unknown>),
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: t.line, opacity: 0.35 } },
      formatter: (params: { dataIndex: number }[]) => {
        const p = series[params[0]?.dataIndex ?? 0];
        if (!p) return '';
        const calls = icu(
          $localize`:@@costs.chart.calls:{count, plural, one {# call} other {# calls}}`,
          {
            count: p.calls,
          },
        );
        return `${p.day}<br/><strong>${formatMoney(p.amount)}</strong> · ${calls}`;
      },
    },
    xAxis: {
      type: 'category',
      data: series.map((p) => p.day.slice(5)),
      ...axis,
      splitLine: { show: false },
      axisLabel: {
        ...axis.axisLabel,
        interval: series.length > 16 ? Math.ceil(series.length / 8) - 1 : 0,
      },
    },
    yAxis: {
      type: 'value',
      ...axis,
      axisLine: { show: false },
      splitNumber: 3,
      axisLabel: { ...axis.axisLabel, formatter: (v: number) => formatMoney(v) },
    },
    series: [
      {
        type: 'bar',
        data: series.map((p) => p.amount),
        barMaxWidth: 18,
        itemStyle: { color: t.accent, borderRadius: [3, 3, 0, 0] },
        emphasis: { itemStyle: { color: t.ink } },
      },
    ],
  };
}

const CONTEXT_LABELS: Record<string, string> = {
  PIPELINE_STEP: $localize`:@@costs.kind.pipelineStep:Workflow step`,
  CODE_REVIEW: $localize`:@@costs.kind.codeReview:Code review`,
  BENCHMARK_ATTEMPT: $localize`:@@costs.kind.benchmarkAttempt:Benchmark attempt`,
  MODEL_TEST: $localize`:@@costs.kind.modelTest:Model test`,
};

export function contextLabel(kind: string | null | undefined): string {
  if (!kind) return '-';
  return (
    CONTEXT_LABELS[kind] ??
    kind
      .replace(/_/g, ' ')
      .toLowerCase()
      .replace(/^\w/, (c) => c.toUpperCase())
  );
}

/** Ledger entries matching the search (model, context, step, ids) and project. */
export function filterLedger(
  entries: readonly UsageEntry[],
  opts: { query?: string | null; projectId?: string | null },
): UsageEntry[] {
  const q = (opts.query ?? '').trim().toLowerCase();
  return entries.filter((e) => {
    if (opts.projectId && e.context?.projectId !== opts.projectId) return false;
    if (!q) return true;
    const c = e.context;
    return [
      e.modelRef?.modelId,
      e.modelRef?.provider,
      contextLabel(c?.kind),
      c?.stepId,
      c?.runId,
      c?.benchmarkId,
      c?.reviewId,
    ].some((f) => !!f && f.toLowerCase().includes(q));
  });
}

/** Where a ledger entry links to (run, benchmark or code review), if anywhere. */
export function ledgerLink(e: UsageEntry): { link: unknown[] | null; text: string } | null {
  const c = e.context;
  if (c?.runId)
    return {
      link: ['/runs', c.runId],
      text: $localize`:@@costs.ledger.linkRun:run ${c.runId.slice(0, 8)}:id:`,
    };
  if (c?.benchmarkId)
    return {
      link: ['/evaluation/benchmarks', c.benchmarkId],
      text: $localize`:@@costs.ledger.linkBenchmark:benchmark ${c.benchmarkId.slice(0, 8)}:id:`,
    };
  // Code reviews have no page of their own (their CI execution links the run).
  if (c?.reviewId)
    return {
      link: null,
      text: $localize`:@@costs.ledger.linkReview:review ${c.reviewId.slice(0, 8)}:id:`,
    };
  return null;
}

/** "in 10.1k · out 1.3k · cache 4k" (short form for dense ledger rows). */
export function compactUsage(usage: TokenUsage | null | undefined): string {
  if (!usage) return '';
  const parts: string[] = [];
  if (usage.inputTokens)
    parts.push($localize`:@@costs.usage.in:in ${formatTokens(usage.inputTokens)}:tokens:`);
  if (usage.outputTokens)
    parts.push($localize`:@@costs.usage.out:out ${formatTokens(usage.outputTokens)}:tokens:`);
  const cache = (usage.cacheReadTokens ?? 0) + (usage.cacheWriteTokens ?? 0);
  if (cache) parts.push($localize`:@@costs.usage.cache:cache ${formatTokens(cache)}:tokens:`);
  return parts.join(' · ');
}
