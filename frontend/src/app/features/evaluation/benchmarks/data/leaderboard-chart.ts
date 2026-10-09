import type { LeaderboardRow } from '../../../../core/api/types';
import {
  chartAxis,
  chartBase,
  chartTokens,
  providerColor,
  type ChartTokens,
} from '../../../../core/ui/relay/chart-tokens';
import { formatMoney } from '../../../../core/ui/pipes/money.pipe';
import { icu } from '../../../../core/i18n/icu';
import { fixed } from '../../../../core/i18n/numbers';

export interface ScatterPoint {
  /** [meanCost, meanScore] */
  value: [number, number];
  name: string;
  modelProfileId: string;
  provider: string;
  passRate: number | null;
  attempts: number;
  itemStyle: { color: string; borderColor: string; borderWidth: number };
}

/**
 * One point per model: x = mean cost per attempt (USD), y = mean score (0-100), coloured by
 * provider from the Relay tokens (`tokens` defaults to the light theme; pages pass
 * `chartTokensSignal()()` so the chart follows light and dark).
 * Rows without a score (no successful attempt yet) are skipped.
 */
export function leaderboardToScatter(
  rows: LeaderboardRow[] | undefined | null,
  tokens: ChartTokens = chartTokens(),
): ScatterPoint[] {
  return (rows ?? [])
    .filter((r) => r.meanScore !== null && r.meanScore !== undefined)
    .map((r) => ({
      value: [r.meanCost?.amount ?? 0, r.meanScore!] as [number, number],
      name: r.modelDisplayName ?? r.modelProfileId ?? $localize`:@@evaluation.chart.model:model`,
      modelProfileId: r.modelProfileId ?? '',
      provider: r.provider ?? '',
      passRate: r.passRate ?? null,
      attempts: r.attempts ?? 0,
      itemStyle: {
        color: providerColor(r.provider, tokens),
        borderColor: tokens.surface,
        borderWidth: 2,
      },
    }));
}

/**
 * Points on the Pareto frontier (no other point is both cheaper-or-equal and better-or-equal
 * with at least one strict improvement). Sorted by cost ascending for drawing as a line.
 */
export function paretoFrontier(points: ScatterPoint[]): ScatterPoint[] {
  const frontier = points.filter(
    (p) =>
      !points.some(
        (q) =>
          q !== p &&
          q.value[0] <= p.value[0] &&
          q.value[1] >= p.value[1] &&
          (q.value[0] < p.value[0] || q.value[1] > p.value[1]),
      ),
  );
  return [...frontier].sort((a, b) => a.value[0] - b.value[0]);
}

/** ECharts option for the score-vs-cost scatter with labelled points and the Pareto frontier. */
export function scatterOption(
  rows: LeaderboardRow[] | undefined | null,
  tokens: ChartTokens = chartTokens(),
): Record<string, unknown> {
  const points = leaderboardToScatter(rows, tokens);
  const frontier = paretoFrontier(points);
  const maxCost = Math.max(0.001, ...points.map((p) => p.value[0]));
  const axis = chartAxis(tokens);
  return {
    ...chartBase(tokens),
    grid: { left: 48, right: 32, top: 24, bottom: 44 },
    tooltip: {
      ...(chartBase(tokens)['tooltip'] as Record<string, unknown>),
      trigger: 'item',
      formatter: (params: { data: ScatterPoint }) => {
        const d = params.data;
        const rate = d.passRate === null ? '' : fixed(d.passRate * 100, 0);
        const pass =
          d.passRate === null
            ? ''
            : '<br/>' + $localize`:@@evaluation.chart.tooltip.passRate:pass rate ${rate}:rate:%`;
        const score = fixed(d.value[1], 1);
        const cost = formatMoney(d.value[0]);
        const attempts = icu(
          $localize`:@@evaluation.chart.tooltip.attempts:{count, plural, one {# attempt} other {# attempts}}`,
          { count: d.attempts },
        );
        return (
          `<strong>${escapeHtml(d.name)}</strong><br/>` +
          $localize`:@@evaluation.chart.tooltip.score:score ${score}:score:` +
          '<br/>' +
          $localize`:@@evaluation.chart.tooltip.cost:mean cost ${cost}:cost:` +
          pass +
          '<br/>' +
          attempts
        );
      },
    },
    xAxis: {
      type: 'value',
      name: $localize`:@@evaluation.chart.axis.cost:mean cost per attempt`,
      nameLocation: 'middle',
      nameGap: 28,
      min: 0,
      max: maxCost * 1.15,
      ...axis,
      axisLabel: { ...axis.axisLabel, formatter: (v: number) => formatMoney(v) },
    },
    yAxis: {
      type: 'value',
      name: $localize`:@@evaluation.chart.axis.score:mean score`,
      nameLocation: 'end',
      min: 0,
      max: 100,
      ...axis,
    },
    series: [
      {
        name: $localize`:@@evaluation.chart.pareto:Pareto frontier`,
        type: 'line',
        data: frontier.map((p) => p.value),
        symbol: 'none',
        lineStyle: { type: 'dashed', color: tokens.ink3, width: 1 },
        tooltip: { show: false },
        silent: true,
        z: 1,
      },
      {
        name: $localize`:@@evaluation.chart.models:Models`,
        type: 'scatter',
        data: points,
        symbolSize: (_v: unknown, params: { data: ScatterPoint }) =>
          12 + Math.min(12, Math.sqrt(params.data.attempts)),
        label: {
          show: true,
          position: 'right',
          formatter: (params: { data: ScatterPoint }) => params.data.name,
          fontSize: 11,
          color: tokens.ink2,
        },
        labelLayout: { hideOverlap: true, moveOverlap: 'shiftY' },
        emphasis: { focus: 'self', scale: 1.3 },
        z: 2,
      },
    ],
  };
}

function escapeHtml(s: string): string {
  return s.replace(
    /[&<>"]/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c,
  );
}
