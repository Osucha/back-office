import type { DeliveryInsights } from '../../../core/api/types';
import { chartTokens } from '../../../core/ui/relay/chart-tokens';
import {
  decisionRows,
  formatRate,
  formatWait,
  hasData,
  headlines,
  periodRange,
  successRows,
  weeklyLabel,
  weeklyOption,
} from './insights-view';

const sample: DeliveryInsights = {
  humanWait: { p50Seconds: 2520, p90Seconds: 30600, totalSeconds: 151200, count: 40 },
  leadTime: { p50Seconds: 93600, p90Seconds: 259200, count: 12 },
  ciWait: { p50Seconds: 420, p90Seconds: 1500, count: 30 },
  runs: { total: 50, succeeded: 36, failed: 9, cancelled: 5 },
  costPerSuccessfulRun: { amount: 0.84, currency: 'USD' },
  costPerMergeRequest: { amount: 1.2, currency: 'USD' },
  totalCost: { amount: 38.5, currency: 'USD' },
  agentSuccessByTemplate: [
    {
      templateKey: 'implement',
      jobs: 30,
      succeeded: 24,
      rate: 0.8,
      cost: { amount: 20, currency: 'USD' },
    },
    { templateKey: 'spec', jobs: 40, succeeded: 39 },
  ],
  gateDecisions: [
    { option: 'approve', count: 30 },
    { option: 'revise', count: 8 },
    { option: 'reject', count: 2 },
  ],
  weekly: [
    { weekStart: '2026-09-07', succeeded: 8, failed: 2, humanWaitHours: 10.25 },
    { weekStart: '2026-09-14', succeeded: 10, failed: 1, humanWaitHours: 14 },
  ],
};

describe('insights view', () => {
  it('computes period ranges', () => {
    const now = new Date('2026-09-28T12:00:00Z');
    expect(periodRange('7d', now).from).toBe('2026-09-21T12:00:00.000Z');
    expect(periodRange('quarter', now).from).toBe('2026-07-01T00:00:00.000Z');
    expect(periodRange('30d', now).to).toBe('2026-09-28T12:00:00.000Z');
  });

  it('formats waits and rates', () => {
    expect(formatWait(30)).toBe('30s');
    expect(formatWait(2520)).toBe('42m');
    expect(formatWait(30600)).toBe('8.5h');
    expect(formatWait(93600)).toBe('26h');
    expect(formatWait(259200)).toBe('3.0d');
    expect(formatWait(null)).toBe('-');
    expect(formatRate(0.8)).toBe('80%');
    expect(formatRate(undefined)).toBe('-');
  });

  it('builds the headline cards with human wait first and loud', () => {
    const h = headlines(sample);
    expect(h.map((x) => x.id)).toEqual(['human-wait', 'lead-time', 'ci-wait', 'success', 'cost']);
    expect(h[0]).toMatchObject({ value: '42m', tone: 'you' });
    expect(h[0].sub).toBe('p50 · p90 8.5h · 42 h in total');
    expect(h[3].value).toBe('80%');
    expect(h[3].sub).toBe('36 of 45 finished runs · 5 cancelled');
    expect(h[4].value).toBe('$0.84');
    expect(headlines(null)[0].sub).toBe('no gate waits yet');
  });

  it('knows when there is no data yet', () => {
    expect(hasData(sample)).toBe(true);
    expect(hasData({ runs: { total: 0 }, weekly: [{ succeeded: 0, failed: 0 }] })).toBe(false);
    expect(hasData(null)).toBe(false);
  });

  it('shapes the success and decision tables', () => {
    const rows = successRows(sample.agentSuccessByTemplate);
    expect(rows.map((r) => r.key)).toEqual(['spec', 'implement']);
    expect(rows[0].rate).toBeCloseTo(39 / 40);
    expect(successRows([{ model: 'claude-opus-5', jobs: 2, succeeded: 1 }])[0]).toMatchObject({
      key: 'claude-opus-5',
      rate: 0.5,
    });
    const d = decisionRows(sample.gateDecisions);
    expect(d.map((x) => [x.option, x.holder])).toEqual([
      ['approve', 'done'],
      ['revise', 'agent'],
      ['reject', 'blocked'],
    ]);
    expect(d[0].share).toBe(0.75);
  });

  it('builds the weekly chart with the token colours', () => {
    const t = chartTokens();
    const o = weeklyOption(sample.weekly, t) as {
      series: { name: string; data: number[]; itemStyle: { color: string } }[];
    };
    expect(o.series.map((s) => s.name)).toEqual(['Succeeded', 'Failed', 'Human wait (h)']);
    expect(o.series[0].itemStyle.color).toBe(t.holder.done);
    expect(o.series[1].itemStyle.color).toBe(t.holder.blocked);
    expect(o.series[2].itemStyle.color).toBe(t.holder.you);
    expect(o.series[2].data).toEqual([10.3, 14]);
    expect(weeklyLabel(sample.weekly)).toContain('18 succeeded, 3 failed');
    expect(weeklyLabel([])).toBe('No weekly data.');
  });
});
