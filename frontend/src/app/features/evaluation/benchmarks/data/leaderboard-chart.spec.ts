import type { LeaderboardRow } from '../../../../core/api/types';
import { leaderboardToScatter, paretoFrontier, scatterOption } from './leaderboard-chart';
import { chartTokens } from '../../../../core/ui/relay/chart-tokens';

const ROWS: LeaderboardRow[] = [
  {
    modelProfileId: 'a',
    modelDisplayName: 'Opus',
    provider: 'ANTHROPIC',
    attempts: 12,
    meanScore: 88,
    passRate: 0.9,
    meanCost: { amount: 0.42, currency: 'USD' },
  },
  {
    modelProfileId: 'b',
    modelDisplayName: 'Mini',
    provider: 'OPENAI',
    attempts: 12,
    meanScore: 71,
    passRate: 0.6,
    meanCost: { amount: 0.05, currency: 'USD' },
  },
  {
    modelProfileId: 'c',
    modelDisplayName: 'Dominated',
    provider: 'MISTRAL',
    attempts: 12,
    meanScore: 65,
    passRate: 0.5,
    meanCost: { amount: 0.3, currency: 'USD' },
  },
  {
    modelProfileId: 'd',
    modelDisplayName: 'Not run',
    provider: 'GOOGLE',
    attempts: 0,
    meanScore: null,
    passRate: null,
    meanCost: { amount: 0, currency: 'USD' },
  },
];

describe('leaderboardToScatter', () => {
  it('maps rows to [cost, score] points, skipping rows without a score', () => {
    const points = leaderboardToScatter(ROWS);
    expect(points.length).toBe(3);
    expect(points[0]).toMatchObject({
      name: 'Opus',
      value: [0.42, 88],
      provider: 'ANTHROPIC',
      passRate: 0.9,
      attempts: 12,
    });
    expect(points[0].itemStyle.color).toBeTruthy();
  });

  it('handles missing input', () => {
    expect(leaderboardToScatter(undefined)).toEqual([]);
    expect(leaderboardToScatter(null)).toEqual([]);
  });

  it('defaults cost to 0 and falls back to the id as label', () => {
    const [p] = leaderboardToScatter([{ modelProfileId: 'x', meanScore: 50 }]);
    expect(p.value).toEqual([0, 50]);
    expect(p.name).toBe('x');
  });
});

describe('leaderboard chart colours', () => {
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

  it('colours points by provider from the theme tokens', () => {
    const points = leaderboardToScatter(ROWS, dark);
    expect(points.find((p) => p.provider === 'ANTHROPIC')!.itemStyle.color).toBe('#ffb23e');
    expect(points.find((p) => p.provider === 'OPENAI')!.itemStyle.color).toBe('#3cc9b8');
    expect(points[0].itemStyle.borderColor).toBe('#1a1c1f');
  });

  it('draws the frontier and the tooltip with the theme tokens', () => {
    const option = scatterOption(ROWS, dark) as {
      series: Array<{ type: string; lineStyle?: { color: string } }>;
      tooltip: { backgroundColor: string };
    };
    expect(option.series.find((s) => s.type === 'line')!.lineStyle!.color).toBe('#8a9098');
    expect(option.tooltip.backgroundColor).toBe('#1a1c1f');
  });

  it('defaults to the light tokens', () => {
    const [p] = leaderboardToScatter(ROWS);
    expect(p.itemStyle.color).toBe(chartTokens().holder.you);
  });
});

describe('paretoFrontier', () => {
  it('keeps only non-dominated points sorted by cost', () => {
    const frontier = paretoFrontier(leaderboardToScatter(ROWS));
    expect(frontier.map((p) => p.name)).toEqual(['Mini', 'Opus']);
  });

  it('keeps equal points', () => {
    const points = leaderboardToScatter([
      {
        modelProfileId: '1',
        modelDisplayName: 'A',
        meanScore: 50,
        meanCost: { amount: 1, currency: 'USD' },
      },
      {
        modelProfileId: '2',
        modelDisplayName: 'B',
        meanScore: 50,
        meanCost: { amount: 1, currency: 'USD' },
      },
    ]);
    expect(paretoFrontier(points).length).toBe(2);
  });
});

describe('scatterOption', () => {
  it('builds a scatter series with one point per model and a frontier line', () => {
    const option = scatterOption(ROWS) as {
      series: Array<{ type: string; data: unknown[] }>;
      xAxis: { max: number };
    };
    const scatter = option.series.find((s) => s.type === 'scatter')!;
    const line = option.series.find((s) => s.type === 'line')!;
    expect(scatter.data.length).toBe(3);
    expect(line.data).toEqual([
      [0.05, 71],
      [0.42, 88],
    ]);
    expect(option.xAxis.max).toBeCloseTo(0.42 * 1.15);
  });

  it('formats tooltips with score, cost and pass rate', () => {
    const option = scatterOption(ROWS) as {
      tooltip: { formatter: (p: { data: unknown }) => string };
    };
    const [point] = leaderboardToScatter(ROWS);
    const html = option.tooltip.formatter({ data: point });
    expect(html).toContain('Opus');
    expect(html).toContain('score 88.0');
    expect(html).toContain('$0.4200');
    expect(html).toContain('pass rate 90%');
  });
});
