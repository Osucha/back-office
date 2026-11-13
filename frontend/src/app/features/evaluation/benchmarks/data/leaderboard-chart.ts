import type { LeaderboardRow } from '../../../../core/api/types';
import {
  chartAxis,
  chartBase,
  chartTokens,
  providerColor,
  type ChartTokens,
} from '../../../../core/ui/relay/chart-tokens';
import { icu } from '../../../../core/i18n/icu';
import { fixed } from '../../../../core/i18n/numbers';
import { boardCostPerAccepted, boardMeanCost, comparesOnApi } from './board-cost';
import { passedCount } from './board-blocks';
import { axisMoneyDecimals, benchmarkMoney, niceAxis, scoreAxis } from './benchmark-format';

export type LabelCorner = 'lowerRight' | 'lowerLeft' | 'upperLeft' | 'upperRight';

export interface ScatterPoint {
  /** [cost per accepted result, mean score]; the cost at API prices for a subscription candidate (ADR 0065) */
  value: [number, number];
  /** ADR 0065: the cost is the estimate at API prices (the candidate ran on a subscription). */
  api: boolean;
  /** The point label: the short model name. */
  name: string;
  /** The tooltip title: agent and model. */
  title: string;
  modelProfileId: string;
  provider: string;
  passed: number;
  attempts: number;
  /** Mean cost per attempt the row is compared on. */
  meanCost: number;
  /** On the Pareto front of its block. */
  frontier: boolean;
  itemStyle: { color: string; borderColor: string; borderWidth: number };
  /** Where the label sits: a corner off the Pareto line (which runs from lower left to upper right). */
  corner?: LabelCorner;
  label?: {
    position: [number, number];
    align: 'left' | 'right';
    verticalAlign: 'top' | 'bottom';
  };
}

/** The label of a point: the short model name by default. */
export type PointName = (r: LeaderboardRow) => string;

const defaultName: PointName = (r) =>
  r.modelDisplayName ?? r.modelProfileId ?? $localize`:@@evaluation.chart.model:model`;

/**
 * One point per candidate of a block: x = cost per accepted result (USD; at API prices for subscription candidates,
 * ADR 0065), y = mean score (0-100). Candidates without an accepted result or a score are not plotted (see
 * [unplotted]). Colours are set by [scatterOption].
 */
export function leaderboardToScatter(
  rows: LeaderboardRow[] | undefined | null,
  name: PointName = defaultName,
): ScatterPoint[] {
  return (rows ?? []).filter(plotted).map((r) => ({
    value: [boardCostPerAccepted(r)!.amount ?? 0, r.meanScore!] as [number, number],
    api: comparesOnApi(r),
    name: name(r),
    title: r.candidate?.label ?? name(r),
    modelProfileId: r.modelProfileId ?? '',
    provider: r.provider ?? '',
    passed: passedCount(r),
    attempts: r.attempts ?? 0,
    meanCost: boardMeanCost(r),
    frontier: false,
    itemStyle: { color: '', borderColor: '', borderWidth: 0 },
  }));
}

function plotted(r: LeaderboardRow): boolean {
  return r.meanScore !== null && r.meanScore !== undefined && boardCostPerAccepted(r) !== null;
}

/** The rows the chart leaves out: no accepted result (or no score) yet. */
export function unplotted(rows: LeaderboardRow[] | undefined | null): LeaderboardRow[] {
  return (rows ?? []).filter((r) => !plotted(r));
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

/** The x axis title: what the cost is and whether it is at API prices. */
export function costAxisName(points: readonly Pick<ScatterPoint, 'api'>[]): string {
  const api = points.filter((p) => p.api).length;
  if (api && api === points.length)
    return $localize`:@@evaluation.chart.axis.costPerAcceptedApi:cost per accepted result (API prices)`;
  if (api)
    return $localize`:@@evaluation.chart.axis.costPerAcceptedMixed:cost per accepted result (subscriptions at API prices)`;
  return $localize`:@@evaluation.chart.axis.costPerAccepted:cost per accepted result`;
}

/**
 * ECharts option for the score-vs-cost scatter of one block: neutral points, the Pareto front (points and line) in the
 * accent; with more than one provider the points take the provider colours and the front points an accent ring.
 * [providers] forces the colouring by provider (the page has candidates of several providers). Rounded money ticks, labels kept off each other and off the right edge.
 */
export function scatterOption(
  rows: LeaderboardRow[] | undefined | null,
  tokens: ChartTokens = chartTokens(),
  name: PointName = defaultName,
  providers?: boolean,
): Record<string, unknown> {
  const raw = leaderboardToScatter(rows, name);
  const frontier = new Set(paretoFrontier(raw));
  const byProvider = providers ?? new Set(raw.map((p) => p.provider)).size > 1;
  const axisX = niceAxis(Math.max(0, ...raw.map((p) => p.value[0])));
  const decimals = axisMoneyDecimals(axisX.max);
  const axisY = scoreAxis(raw.map((p) => p.value[1]));
  const points = raw.map((p): ScatterPoint => {
    const front = frontier.has(p);
    return {
      ...p,
      frontier: front,
      itemStyle: byProvider
        ? {
            color: providerColor(p.provider, tokens),
            borderColor: front ? tokens.accent : tokens.surface,
            borderWidth: 2,
          }
        : {
            color: front ? tokens.accent : tokens.ink3,
            borderColor: tokens.surface,
            borderWidth: 2,
          },
      ...placeLabel(p, front, axisX.max, axisY),
    };
  });
  const line = points.filter((p) => p.frontier).sort((a, b) => a.value[0] - b.value[0]);
  const axis = chartAxis(tokens);
  return {
    ...chartBase(tokens),
    grid: { left: 16, right: 24, top: 28, bottom: 28, containLabel: true },
    tooltip: {
      ...(chartBase(tokens)['tooltip'] as Record<string, unknown>),
      trigger: 'item',
      formatter: (params: { data: ScatterPoint }) => tooltipHtml(params.data),
    },
    xAxis: {
      type: 'value',
      name: costAxisName(points),
      nameLocation: 'middle',
      nameGap: 28,
      min: 0,
      max: axisX.max,
      interval: axisX.interval,
      ...axis,
      axisLabel: {
        ...axis.axisLabel,
        fontSize: 12,
        formatter: (v: number) => benchmarkMoney(v, decimals),
      },
      nameTextStyle: { ...axis.nameTextStyle, fontSize: 12 },
    },
    yAxis: {
      type: 'value',
      name: $localize`:@@evaluation.chart.axis.score:mean score`,
      nameLocation: 'end',
      nameGap: 12,
      min: axisY.min,
      max: axisY.max,
      interval: axisY.interval,
      ...axis,
      axisLabel: { ...axis.axisLabel, fontSize: 12 },
      nameTextStyle: { ...axis.nameTextStyle, fontSize: 12, align: 'left' },
    },
    series: [
      {
        name: $localize`:@@evaluation.chart.pareto:Pareto frontier`,
        type: 'line',
        data: line.map((p) => p.value),
        symbol: 'none',
        lineStyle: { type: 'dashed', color: tokens.accent, width: 1 },
        tooltip: { show: false },
        silent: true,
        z: 1,
      },
      {
        name: $localize`:@@evaluation.chart.candidates:Candidates`,
        type: 'scatter',
        data: points,
        symbolSize: (_v: unknown, params: { data: ScatterPoint }) => symbolSize(params.data),
        label: {
          show: true,
          formatter: (params: { data: ScatterPoint }) => params.data.name,
          fontSize: 12,
          color: tokens.ink2,
          backgroundColor: tokens.surface,
          padding: [0, 2],
        },
        labelLayout: { hideOverlap: true },
        emphasis: { focus: 'self', scale: 1.3 },
        z: 2,
      },
    ],
  };
}

function tooltipHtml(d: ScatterPoint): string {
  const score = fixed(d.value[1], 1);
  const cost = benchmarkMoney(d.value[0]);
  const mean = benchmarkMoney(d.meanCost);
  const passed = d.passed;
  const attempts = icu(
    $localize`:@@evaluation.chart.tooltip.attempts:{count, plural, one {# attempt} other {# attempts}}`,
    { count: d.attempts },
  );
  return (
    `<strong>${escapeHtml(d.title)}</strong><br/>` +
    $localize`:@@evaluation.chart.tooltip.score:score ${score}:score:` +
    '<br/>' +
    (d.api
      ? $localize`:@@evaluation.chart.tooltip.perAcceptedApi:${cost}:cost: per accepted result at API prices`
      : $localize`:@@evaluation.chart.tooltip.perAccepted:${cost}:cost: per accepted result`) +
    '<br/>' +
    $localize`:@@evaluation.chart.tooltip.meanCost:mean cost per attempt ${mean}:cost:` +
    '<br/>' +
    $localize`:@@evaluation.chart.tooltip.passed:${passed}:passed: of ${attempts}:attempts: accepted`
  );
}

function escapeHtml(s: string): string {
  return s.replace(
    /[&<>"]/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c,
  );
}

/** Point size by attempts. */
export function symbolSize(p: Pick<ScatterPoint, 'attempts'>): number {
  return 10 + Math.min(12, Math.sqrt(p.attempts));
}

/**
 * The label corner of a point, off the dashed Pareto line: the line runs from lower left to upper right, so labels go
 * to the lower right (front points too); near the right edge to the left, near the bottom upwards. The position is in
 * pixels from the symbol's top-left corner.
 */
function placeLabel(
  p: ScatterPoint,
  front: boolean,
  maxX: number,
  axisY: { min: number; max: number },
): Pick<ScatterPoint, 'corner' | 'label'> {
  const right = p.value[0] > maxX * 0.7;
  const bottom = p.value[1] - axisY.min < (axisY.max - axisY.min) * 0.15;
  let corner: LabelCorner;
  if (front) corner = right || bottom ? 'upperLeft' : 'lowerRight';
  else if (bottom) corner = right ? 'upperLeft' : 'upperRight';
  else corner = right ? 'lowerLeft' : 'lowerRight';
  const s = symbolSize(p);
  const label = {
    lowerRight: { position: [s, s], align: 'left', verticalAlign: 'top' },
    lowerLeft: { position: [0, s], align: 'right', verticalAlign: 'top' },
    upperLeft: { position: [0, 0], align: 'right', verticalAlign: 'bottom' },
    upperRight: { position: [s, 0], align: 'left', verticalAlign: 'bottom' },
  }[corner] as NonNullable<ScatterPoint['label']>;
  return { corner, label };
}

/** What the accent marks, under the chart; the dashed line is mentioned only when it joins two or more points. */
export function frontCaption(frontPoints: number, byProvider: boolean): string {
  if (frontPoints <= 0) return '';
  if (byProvider)
    return frontPoints > 1
      ? $localize`:@@evaluation.chart.caption.frontRingLine:Points with a blue ring: the Pareto front (nothing is both cheaper and better), joined by the dashed line`
      : $localize`:@@evaluation.chart.caption.frontRing:The point with a blue ring: the Pareto front (no other candidate is both cheaper and better)`;
  return frontPoints > 1
    ? $localize`:@@evaluation.chart.caption.frontLine:Blue points: the Pareto front (nothing is both cheaper and better), joined by the dashed line`
    : $localize`:@@evaluation.chart.caption.front:The blue point: the Pareto front (no other candidate is both cheaper and better)`;
}
