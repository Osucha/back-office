import type { UsageEntry, UsageGroup } from '../../../core/api/types';
import { chartTokens } from '../../../core/ui/relay/chart-tokens';
import {
  apiRange,
  breakdownRows,
  compactUsage,
  contextLabel,
  dailySeries,
  filterLedger,
  isoDay,
  ledgerLink,
  rangeFor,
  trendOption,
} from './cost-view';

const money = (amount: number) => ({ amount, currency: 'USD' });
const NOW = new Date(2026, 8, 27, 15, 30); // Sep 27, 2026

describe('cost periods', () => {
  it('resolves presets ending today', () => {
    const r = rangeFor('7d', null, null, NOW);
    expect(r.period).toBe('7d');
    expect(isoDay(r.from)).toBe('2026-09-21');
    expect(isoDay(r.to)).toBe('2026-09-27');
    expect(isoDay(rangeFor('month', null, null, NOW).from)).toBe('2026-09-01');
    expect(isoDay(rangeFor('90d', null, null, NOW).from)).toBe('2026-06-30');
  });

  it('defaults to 30 days and prefers a valid custom range', () => {
    const d = rangeFor(null, null, null, NOW);
    expect(d.period).toBe('30d');
    expect(isoDay(d.from)).toBe('2026-08-29');
    const c = rangeFor('7d', '2026-09-10', '2026-09-01', NOW);
    expect(c.period).toBe('custom');
    expect([isoDay(c.from), isoDay(c.to)]).toEqual(['2026-09-01', '2026-09-10']);
    expect(rangeFor('7d', 'garbage', '2026-09-01', NOW).period).toBe('7d');
  });

  it('turns days into an inclusive instant range', () => {
    const r = apiRange({ from: new Date(2026, 8, 1, 12), to: new Date(2026, 8, 2, 1) });
    expect(new Date(r.from).getHours()).toBe(0);
    expect(new Date(r.to).getHours()).toBe(23);
    expect(new Date(r.to).getMinutes()).toBe(59);
  });
});

describe('cost breakdown', () => {
  const groups: UsageGroup[] = [
    { key: 'b', label: 'Sonnet', cost: money(2), calls: 10 },
    { key: 'a', label: 'Opus', cost: money(6), calls: 4 },
    { key: 'c', label: '', cost: money(1), calls: 3 },
    { key: 'd', label: 'Haiku', cost: money(0.5), calls: 30 },
    { key: 'e', label: 'Mini', cost: money(0.5), calls: 12 },
  ];

  it('sorts by cost with shares of the total and bars relative to the largest', () => {
    const rows = breakdownRows(groups, { total: money(10) });
    expect(rows.map((r) => r.label)).toEqual(['Opus', 'Sonnet', 'c', 'Haiku', 'Mini']);
    expect(rows[0].share).toBeCloseTo(60);
    expect(rows[0].bar).toBe(100);
    expect(rows[1].bar).toBeCloseTo(33.33, 1);
  });

  it('folds the tail into one "others" row', () => {
    const rows = breakdownRows(groups, { max: 3 });
    expect(rows.map((r) => r.label)).toEqual(['Opus', 'Sonnet', '3 others']);
    expect(rows[2]).toMatchObject({ other: true, calls: 45, cost: money(2) });
    expect(rows.reduce((a, r) => a + r.share, 0)).toBeCloseTo(100);
  });

  it('keeps days in calendar order and handles no data', () => {
    const days: UsageGroup[] = [
      { key: '2026-09-02', cost: money(1) },
      { key: '2026-09-01', cost: money(3) },
    ];
    expect(breakdownRows(days, { chronological: true }).map((r) => r.key)).toEqual([
      '2026-09-01',
      '2026-09-02',
    ]);
    expect(breakdownRows(null)).toEqual([]);
    expect(breakdownRows([{ key: 'x', cost: money(0) }])[0]).toMatchObject({ share: 0, bar: 0 });
  });
});

describe('daily series and trend chart', () => {
  it('fills every day of the range', () => {
    const s = dailySeries([{ key: '2026-09-02', cost: money(1.5), calls: 3 }], {
      from: new Date(2026, 8, 1),
      to: new Date(2026, 8, 3),
    });
    expect(s).toEqual([
      { day: '2026-09-01', amount: 0, calls: 0 },
      { day: '2026-09-02', amount: 1.5, calls: 3 },
      { day: '2026-09-03', amount: 0, calls: 0 },
    ]);
  });

  it('draws accent bars from the theme tokens', () => {
    const t = chartTokens((n) => (n === '--bo-accent' ? '#8fa2ff' : ''));
    const s = dailySeries([], { from: new Date(2026, 8, 1), to: new Date(2026, 8, 2) });
    const o = trendOption(s, t) as {
      series: { itemStyle: { color: string }; data: number[] }[];
      xAxis: { data: string[] };
    };
    expect(o.series[0].itemStyle.color).toBe('#8fa2ff');
    expect(o.series[0].data).toEqual([0, 0]);
    expect(o.xAxis.data).toEqual(['09-01', '09-02']);
  });
});

describe('ledger', () => {
  const entries = [
    {
      id: '1',
      modelRef: { provider: 'ANTHROPIC', modelId: 'claude-opus-5' },
      context: { kind: 'PIPELINE_STEP', runId: 'aaaaaaaa-1', projectId: 'p1', stepId: 'implement' },
    },
    {
      id: '2',
      modelRef: { provider: 'OPENAI', modelId: 'gpt-5' },
      context: { kind: 'BENCHMARK_ATTEMPT', benchmarkId: 'bbbbbbbb-2' },
    },
    { id: '3', context: { kind: 'CODE_REVIEW', reviewId: 'cccccccc-3', projectId: 'p2' } },
  ] as UsageEntry[];

  it('filters by text and project', () => {
    expect(filterLedger(entries, { query: 'opus' }).map((e) => e.id)).toEqual(['1']);
    expect(filterLedger(entries, { query: 'benchmark' }).map((e) => e.id)).toEqual(['2']);
    expect(filterLedger(entries, { query: 'implement' }).map((e) => e.id)).toEqual(['1']);
    expect(filterLedger(entries, { projectId: 'p2' }).map((e) => e.id)).toEqual(['3']);
    expect(filterLedger(entries, {}).length).toBe(3);
  });

  it('labels contexts and links runs and benchmarks', () => {
    expect(contextLabel('PIPELINE_STEP')).toBe('Workflow step');
    expect(contextLabel('SOMETHING_ELSE')).toBe('Something else');
    expect(ledgerLink(entries[0])).toEqual({ link: ['/runs', 'aaaaaaaa-1'], text: 'run aaaaaaaa' });
    expect(ledgerLink(entries[1])?.link).toEqual(['/evaluation/benchmarks', 'bbbbbbbb-2']);
    expect(ledgerLink(entries[2])).toEqual({ link: null, text: 'review cccccccc' });
    expect(ledgerLink({ id: 'x' } as UsageEntry)).toBeNull();
  });
});

describe('compact usage', () => {
  it('shortens token usage for ledger rows', () => {
    expect(compactUsage({ inputTokens: 10100, outputTokens: 1300, cacheReadTokens: 4000 })).toBe(
      'in 10.1k · out 1.3k · cache 4k',
    );
    expect(compactUsage({ outputTokens: 12 })).toBe('out 12');
    expect(compactUsage(null)).toBe('');
  });
});
