/** Pure shaping of `GET /insights/delivery` for the Insights page (docs/architecture/11-governance.md). */
import type {
  DeliveryInsights,
  DistributionStat,
  GateDecisionCount,
  ModelSuccess,
  TemplateSuccess,
  WeeklyInsight,
} from '../../../core/api/types';
import { type ChartTokens, chartAxis, chartBase } from '../../../core/ui/relay/chart-tokens';
import { icu } from '../../../core/i18n/icu';
import { fixed, formatNumber } from '../../../core/i18n/numbers';
import { formatCost } from '../../../core/ui/relay/format';

export type PeriodId = '7d' | '30d' | '90d' | 'quarter';

export const PERIODS: { id: PeriodId; label: string }[] = [
  { id: '7d', label: $localize`:@@insights.period.7d:7 days` },
  { id: '30d', label: $localize`:@@insights.period.30d:30 days` },
  { id: '90d', label: $localize`:@@insights.period.90d:90 days` },
  { id: 'quarter', label: $localize`:@@insights.period.quarter:This quarter` },
];

/** `from` / `to` (ISO) for a preset, relative to `now`. */
export function periodRange(id: PeriodId, now = new Date()): { from: string; to: string } {
  const to = new Date(now);
  let from: Date;
  if (id === 'quarter') {
    const q = Math.floor(now.getUTCMonth() / 3) * 3;
    from = new Date(Date.UTC(now.getUTCFullYear(), q, 1));
  } else {
    const days = id === '7d' ? 7 : id === '30d' ? 30 : 90;
    from = new Date(now.getTime() - days * 86_400_000);
  }
  return { from: from.toISOString(), to: to.toISOString() };
}

/** Durations for headline figures: "42m", "3.5h", "2.1d". */
export function formatWait(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || Number.isNaN(seconds)) return '-';
  if (seconds < 60) return $localize`:@@insights.unit.seconds:${Math.round(seconds)}:n:s`;
  const m = seconds / 60;
  if (m < 60) return $localize`:@@insights.unit.minutes:${Math.round(m)}:n:m`;
  const h = m / 60;
  if (h < 48) return $localize`:@@insights.unit.hours:${h < 10 ? fixed(h, 1) : Math.round(h)}:n:h`;
  const d = h / 24;
  return $localize`:@@insights.unit.days:${d < 10 ? fixed(d, 1) : Math.round(d)}:n:d`;
}

export function formatHours(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '-';
  const h = seconds / 3600;
  return `${h < 10 ? fixed(h, 1) : formatNumber(Math.round(h))} h`;
}

export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined || Number.isNaN(rate)) return '-';
  return `${Math.round(rate * 100)}%`;
}

export interface Headline {
  id: string;
  label: string;
  value: string;
  sub: string;
  /** Human wait is the loud one (amber: it is time people owe). */
  tone: 'you' | 'default';
  hint: string;
}

function distSub(
  d: DistributionStat | null | undefined,
  none: string,
  countMessage: string,
): string {
  if (!d?.count) return none;
  return `p90 ${formatWait(d.p90Seconds)} · ${icu(countMessage, { count: d.count })}`;
}

/** The headline cards, human wait first. */
export function headlines(i: DeliveryInsights | null): Headline[] {
  const runs = i?.runs ?? {};
  const finished = (runs.succeeded ?? 0) + (runs.failed ?? 0);
  const rate = finished ? (runs.succeeded ?? 0) / finished : null;
  return [
    {
      id: 'human-wait',
      label: $localize`:@@insights.headline.humanWait:Human wait`,
      value: formatWait(i?.humanWait?.p50Seconds),
      sub: i?.humanWait?.count
        ? $localize`:@@insights.headline.humanWaitSub:p50 · p90 ${formatWait(i.humanWait.p90Seconds)}:p90: · ${formatHours(i.humanWait.totalSeconds)}:total: in total`
        : $localize`:@@insights.headline.humanWaitNone:no gate waits yet`,
      tone: 'you',
      hint: $localize`:@@insights.headline.humanWaitHint:Time runs spend waiting at human gates: the time people owe the agents.`,
    },
    {
      id: 'lead-time',
      label: $localize`:@@insights.headline.leadTime:Lead time`,
      value: formatWait(i?.leadTime?.p50Seconds),
      sub: distSub(
        i?.leadTime,
        $localize`:@@insights.headline.leadTimeNone:no changes yet`,
        $localize`:@@insights.headline.leadTimeCount:{count, plural, one {# change} other {# changes}}`,
      ),
      tone: 'default',
      hint: $localize`:@@insights.headline.leadTimeHint:First run for an issue to the merge request (p50).`,
    },
    {
      id: 'ci-wait',
      label: $localize`:@@insights.headline.ciWait:CI wait`,
      value: formatWait(i?.ciWait?.p50Seconds),
      sub: distSub(
        i?.ciWait,
        $localize`:@@insights.headline.ciWaitNone:no waits yet`,
        $localize`:@@insights.headline.ciWaitCount:{count, plural, one {# wait} other {# waits}}`,
      ),
      tone: 'default',
      hint: $localize`:@@insights.headline.ciWaitHint:Time runs spend waiting for the project CI (p50).`,
    },
    {
      id: 'success',
      label: $localize`:@@insights.headline.success:Run success rate`,
      value: formatRate(rate),
      sub:
        icu(
          $localize`:@@insights.headline.successSub:{finished, plural, one {{succeeded} of # finished run} other {{succeeded} of # finished runs}}`,
          { finished, succeeded: runs.succeeded ?? 0 },
        ) +
        (runs.cancelled
          ? ' · ' +
            icu($localize`:@@insights.headline.cancelled:{count, plural, other {# cancelled}}`, {
              count: runs.cancelled,
            })
          : ''),
      tone: 'default',
      hint: $localize`:@@insights.headline.successHint:Runs that reached a successful end, of all finished runs.`,
    },
    {
      id: 'cost',
      label: $localize`:@@insights.headline.cost:Cost per successful run`,
      value: formatCost(i?.costPerSuccessfulRun ?? null),
      sub: $localize`:@@insights.headline.costSub:${formatCost(i?.costPerMergeRequest ?? null)}:perMr: per merge request · ${formatCost(i?.totalCost ?? null)}:total: total`,
      tone: 'default',
      hint: $localize`:@@insights.headline.costHint:Ledger cost of runs that succeeded, and of those that produced a merge request.`,
    },
  ];
}

/** Is there anything to show? (A new install returns zeros.) */
export function hasData(i: DeliveryInsights | null): boolean {
  if (!i) return false;
  return !!(
    i.runs?.total ||
    i.weekly?.some((w) => (w.succeeded ?? 0) + (w.failed ?? 0) > 0) ||
    i.agentSuccessByTemplate?.length
  );
}

export interface SuccessRow {
  key: string;
  jobs: number;
  succeeded: number;
  rate: number;
  cost: TemplateSuccess['cost'];
}

/** Success tables, worst first among those with enough jobs (the ones to look at). */
export function successRows(
  rows: readonly (TemplateSuccess | ModelSuccess)[] | null | undefined,
): SuccessRow[] {
  return (rows ?? [])
    .map((r) => ({
      key: ('templateKey' in r ? r.templateKey : (r as ModelSuccess).model) ?? '-',
      jobs: r.jobs ?? 0,
      succeeded: r.succeeded ?? 0,
      rate: r.rate ?? (r.jobs ? (r.succeeded ?? 0) / r.jobs : 0),
      cost: r.cost,
    }))
    .sort((a, b) => b.jobs - a.jobs || a.key.localeCompare(b.key));
}

export interface DecisionRow {
  option: string;
  count: number;
  share: number;
  holder: 'done' | 'agent' | 'blocked' | 'idle';
}

/** Gate decisions with their share; approve-like options are done, revise agent, reject blocked. */
export function decisionRows(rows: readonly GateDecisionCount[] | null | undefined): DecisionRow[] {
  const total = (rows ?? []).reduce((s, r) => s + (r.count ?? 0), 0);
  return (rows ?? [])
    .map((r) => {
      const o = (r.option ?? '').toUpperCase();
      const holder: DecisionRow['holder'] = /APPROVE|MERGE|ACCEPT|YES/.test(o)
        ? 'done'
        : /REVISE|RETRY|REDO/.test(o)
          ? 'agent'
          : /REJECT|NO|DECLINE/.test(o)
            ? 'blocked'
            : 'idle';
      return {
        option: r.option ?? '-',
        count: r.count ?? 0,
        share: total ? (r.count ?? 0) / total : 0,
        holder,
      };
    })
    .sort((a, b) => b.count - a.count);
}

/** Weekly chart: stacked succeeded (done) and failed (blocked) runs, human wait hours (you) as a line. */
export function weeklyOption(
  weeks: readonly WeeklyInsight[] | null | undefined,
  t: ChartTokens,
): Record<string, unknown> {
  const w = weeks ?? [];
  const names = {
    succeeded: $localize`:@@insights.chart.succeeded:Succeeded`,
    failed: $localize`:@@insights.chart.failed:Failed`,
    humanWait: $localize`:@@insights.chart.humanWait:Human wait (h)`,
  };
  const axis = chartAxis(t);
  const base = chartBase(t);
  return {
    ...base,
    grid: { left: 40, right: 40, top: 40, bottom: 24 },
    legend: {
      top: 0,
      right: 0,
      itemWidth: 10,
      itemHeight: 10,
      textStyle: { color: t.ink2, fontSize: 12 },
      data: [names.succeeded, names.failed, names.humanWait],
    },
    tooltip: { ...(base['tooltip'] as Record<string, unknown>), trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: w.map((x) => (x.weekStart ?? '').slice(5)),
      ...axis,
      splitLine: { show: false },
    },
    yAxis: [
      { type: 'value', ...axis, axisLine: { show: false }, minInterval: 1 },
      {
        type: 'value',
        ...axis,
        axisLine: { show: false },
        splitLine: { show: false },
      },
    ],
    series: [
      {
        name: names.succeeded,
        type: 'bar',
        stack: 'runs',
        data: w.map((x) => x.succeeded ?? 0),
        itemStyle: { color: t.holder.done },
        barMaxWidth: 22,
      },
      {
        name: names.failed,
        type: 'bar',
        stack: 'runs',
        data: w.map((x) => x.failed ?? 0),
        itemStyle: { color: t.holder.blocked, borderRadius: [3, 3, 0, 0] },
        barMaxWidth: 22,
      },
      {
        name: names.humanWait,
        type: 'line',
        yAxisIndex: 1,
        data: w.map((x) => Math.round((x.humanWaitHours ?? 0) * 10) / 10),
        itemStyle: { color: t.holder.you },
        lineStyle: { color: t.holder.you, width: 2 },
        symbolSize: 6,
      },
    ],
  };
}

/** Screen-reader summary of the weekly chart. */
export function weeklyLabel(weeks: readonly WeeklyInsight[] | null | undefined): string {
  const w = weeks ?? [];
  if (!w.length) return $localize`:@@insights.chart.noData:No weekly data.`;
  const s = w.reduce((a, x) => a + (x.succeeded ?? 0), 0);
  const f = w.reduce((a, x) => a + (x.failed ?? 0), 0);
  const peak = [...w].sort((a, b) => (b.humanWaitHours ?? 0) - (a.humanWaitHours ?? 0))[0];
  return $localize`:@@insights.chart.label:Runs per week over ${w.length}:weeks: weeks: ${s}:succeeded: succeeded, ${f}:failed: failed. Longest human wait in the week of ${peak.weekStart}:week:: ${peak.humanWaitHours ?? 0}:hours: hours.`;
}
