import type { LeaderboardRow } from '../../../../core/api/types';
import {
  costAxisName,
  frontCaption,
  leaderboardToScatter,
  paretoFrontier,
  scatterOption,
  unplotted,
  type ScatterPoint,
} from './leaderboard-chart';
import { chartTokens } from '../../../../core/ui/relay/chart-tokens';

const usd = (amount: number) => ({ amount, currency: 'USD' });

const ROWS: LeaderboardRow[] = [
  {
    modelProfileId: 'a',
    modelDisplayName: 'Opus',
    provider: 'ANTHROPIC',
    attempts: 10,
    meanScore: 88,
    passRate: 0.9,
    meanCost: usd(0.42),
    costPerAccepted: usd(0.5),
  },
  {
    modelProfileId: 'b',
    modelDisplayName: 'Mini',
    provider: 'ANTHROPIC',
    attempts: 10,
    meanScore: 71,
    passRate: 0.6,
    meanCost: usd(0.05),
    costPerAccepted: usd(0.08),
  },
  {
    modelProfileId: 'c',
    modelDisplayName: 'Dominated',
    provider: 'ANTHROPIC',
    attempts: 10,
    meanScore: 65,
    passRate: 0.5,
    meanCost: usd(0.3),
    costPerAccepted: usd(0.6),
  },
  {
    modelProfileId: 'd',
    modelDisplayName: 'Haiku',
    provider: 'ANTHROPIC',
    attempts: 10,
    meanScore: 40,
    passRate: 0,
    meanCost: usd(0.01),
    costPerAccepted: null,
  },
];

const dark = chartTokens(
  (name) =>
    ({
      '--bo-you': '#ffb23e',
      '--bo-ci': '#3cc9b8',
      '--bo-accent': '#8fa2ff',
      '--bo-surface': '#1a1c1f',
      '--bo-ink-3': '#8a9098',
    })[name] ?? '',
);

type Option = {
  series: Array<{ type: string; data: unknown[]; lineStyle?: { color: string } }>;
  yAxis: { min: number; max: number; interval: number };
  xAxis: {
    max: number;
    interval: number;
    name: string;
    axisLabel: { formatter: (v: number) => string };
  };
  tooltip: { backgroundColor: string; formatter: (p: { data: unknown }) => string };
};

describe('leaderboardToScatter', () => {
  it('plots cost per accepted result against the score and leaves out rows without an accepted result', () => {
    const points = leaderboardToScatter(ROWS);
    expect(points.map((p) => p.name)).toEqual(['Opus', 'Mini', 'Dominated']);
    expect(points[0]).toMatchObject({ value: [0.5, 88], passed: 9, attempts: 10, meanCost: 0.42 });
    expect(unplotted(ROWS).map((r) => r.modelDisplayName)).toEqual(['Haiku']);
  });

  it('labels points with the given short name', () => {
    const [p] = leaderboardToScatter(ROWS, (r) => `short ${r.modelDisplayName}`);
    expect(p.name).toBe('short Opus');
  });

  it('handles missing input', () => {
    expect(leaderboardToScatter(undefined)).toEqual([]);
    expect(unplotted(null)).toEqual([]);
  });
});

describe('paretoFrontier', () => {
  it('keeps only non-dominated points sorted by cost', () => {
    expect(paretoFrontier(leaderboardToScatter(ROWS)).map((p) => p.name)).toEqual(['Mini', 'Opus']);
  });

  it('keeps equal points', () => {
    const rows: LeaderboardRow[] = ['A', 'B'].map((n) => ({
      modelDisplayName: n,
      meanScore: 50,
      costPerAccepted: usd(1),
    }));
    expect(paretoFrontier(leaderboardToScatter(rows)).length).toBe(2);
  });
});

describe('scatterOption', () => {
  it('draws the front in the accent and other points neutral for one provider', () => {
    const option = scatterOption(ROWS, dark) as unknown as Option;
    const points = option.series.find((s) => s.type === 'scatter')!.data as ScatterPoint[];
    expect(points.filter((p) => p.frontier).map((p) => p.name)).toEqual(['Opus', 'Mini']);
    expect(points.find((p) => p.name === 'Mini')!.itemStyle.color).toBe('#8fa2ff');
    expect(points.find((p) => p.name === 'Dominated')!.itemStyle.color).toBe('#8a9098');
    const line = option.series.find((s) => s.type === 'line')!;
    expect(line.lineStyle!.color).toBe('#8fa2ff');
    expect(line.data).toEqual([
      [0.08, 71],
      [0.5, 88],
    ]);
    expect(option.tooltip.backgroundColor).toBe('#1a1c1f');
  });

  it('colours by provider with an accent ring on the front when providers differ', () => {
    const rows = ROWS.map(
      (r, i) => ({ ...r, provider: i ? 'ANTHROPIC' : 'OPENAI' }) as LeaderboardRow,
    );
    const option = scatterOption(rows, dark) as unknown as Option;
    const points = option.series.find((s) => s.type === 'scatter')!.data as ScatterPoint[];
    expect(points.find((p) => p.name === 'Opus')!.itemStyle).toMatchObject({
      color: '#3cc9b8',
      borderColor: '#8fa2ff',
    });
    expect(points.find((p) => p.name === 'Dominated')!.itemStyle.color).toBe('#ffb23e');
  });

  it('uses round money ticks and moves labels of points near the right edge to the left', () => {
    const option = scatterOption(ROWS) as unknown as Option;
    expect(option.xAxis.max).toBe(0.8);
    expect(option.xAxis.interval).toBe(0.2);
    expect(option.xAxis.axisLabel.formatter(0.2)).toBe('$0.20');
    const points = option.series.find((s) => s.type === 'scatter')!.data as ScatterPoint[];
    expect(points.find((p) => p.name === 'Mini')!.corner).toBe('lowerRight');
    expect(points.find((p) => p.name === 'Opus')!.label!.align).toBe('left');
    expect(points.find((p) => p.name === 'Dominated')!.corner).toBe('upperLeft');
    expect(option.yAxis).toMatchObject({ min: 60, max: 100, interval: 10 });
  });

  it('formats small axes with three decimals', () => {
    const option = scatterOption([
      { modelDisplayName: 'A', meanScore: 80, costPerAccepted: usd(0.04) },
    ]) as unknown as Option;
    expect(option.xAxis.axisLabel.formatter(0.01)).toBe('$0.010');
  });

  it('formats tooltips with score, cost per accepted result and accepted attempts', () => {
    const option = scatterOption(ROWS) as unknown as Option;
    const [point] = leaderboardToScatter(ROWS);
    const html = option.tooltip.formatter({ data: point });
    expect(html).toContain('Opus');
    expect(html).toContain('score 88.0');
    expect(html).toContain('$0.50 per accepted result');
    expect(html).toContain('9 of 10 attempts accepted');
    expect(html).not.toContain('API');
  });
});

describe('subscription candidates (ADR 0065)', () => {
  const sub: LeaderboardRow = {
    modelProfileId: 's',
    modelDisplayName: 'Login Opus',
    provider: 'ANTHROPIC',
    attempts: 6,
    meanScore: 80,
    passRate: 0.5,
    subscription: true,
    meanCost: usd(0),
    totalCost: usd(0),
    costPerAccepted: usd(0),
    meanApiCost: usd(1.14),
    apiCostPerAccepted: usd(2.28),
  };

  it('places them at their cost per accepted result at API prices', () => {
    const [p] = leaderboardToScatter([sub]);
    expect(p.value).toEqual([2.28, 80]);
    expect(p.api).toBe(true);
    expect(leaderboardToScatter(ROWS)[0].api).toBe(false);
  });

  it('says so in the tooltip and the axis title', () => {
    const option = scatterOption([sub]) as unknown as Option;
    const html = option.tooltip.formatter({ data: leaderboardToScatter([sub])[0] });
    expect(html).toContain('per accepted result at API prices');
    expect(option.xAxis.name).toBe('cost per accepted result (API prices)');
    expect(costAxisName([{ api: true }, { api: false }])).toContain('subscriptions');
    expect(costAxisName([{ api: false }])).toBe('cost per accepted result');
  });
});

describe('frontCaption', () => {
  it('mentions the dashed line only for a front of two or more points', () => {
    expect(frontCaption(2, false)).toContain('dashed line');
    expect(frontCaption(1, false)).not.toContain('dashed');
    expect(frontCaption(1, true)).toContain('ring');
    expect(frontCaption(0, false)).toBe('');
  });
});
