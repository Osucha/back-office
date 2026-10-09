import {
  CHART_TOKEN_FALLBACK,
  chartAxis,
  chartBase,
  chartTokens,
  providerColor,
  seriesColor,
} from './chart-tokens';

const DARK: Record<string, string> = {
  '--bo-ink': ' #eceae5',
  '--bo-ink-3': '#8a9098',
  '--bo-line': '#2d3137',
  '--bo-surface': '#1a1c1f',
  '--bo-accent': '#8fa2ff',
  '--bo-agent': '#8fa2ff',
  '--bo-you': '#ffb23e',
  '--bo-ci': '#3cc9b8',
  '--bo-done': '#6fc77f',
  '--bo-blocked': '#ff7a63',
};

describe('chart tokens', () => {
  it('reads (and trims) the custom properties of the current theme', () => {
    const t = chartTokens((name) => DARK[name] ?? '');
    expect(t.ink).toBe('#eceae5');
    expect(t.surface).toBe('#1a1c1f');
    expect(t.holder.you).toBe('#ffb23e');
    expect(t.series.slice(0, 4)).toEqual(['#8fa2ff', '#3cc9b8', '#ffb23e', '#6fc77f']);
  });

  it('falls back to the light values for missing tokens', () => {
    const t = chartTokens();
    expect(t.ink).toBe(CHART_TOKEN_FALLBACK['--bo-ink']);
    expect(t.holder.idle).toBe(CHART_TOKEN_FALLBACK['--bo-idle']);
    const partial = chartTokens((name) => DARK[name] ?? '');
    expect(partial.ink2).toBe(CHART_TOKEN_FALLBACK['--bo-ink-2']);
  });

  it('gives every provider a stable token colour', () => {
    const t = chartTokens((name) => DARK[name] ?? '');
    expect(providerColor('ANTHROPIC', t)).toBe('#ffb23e');
    expect(providerColor('OPENAI', t)).toBe('#3cc9b8');
    expect(providerColor('GOOGLE', t)).toBe('#8fa2ff');
    expect(providerColor('UNKNOWN', t)).toBe(t.holder.idle);
    expect(providerColor(null, t)).toBe(t.holder.idle);
  });

  it('wraps series colours', () => {
    const t = chartTokens();
    expect(seriesColor(0, t)).toBe(t.series[0]);
    expect(seriesColor(t.series.length, t)).toBe(t.series[0]);
    expect(seriesColor(-1, t)).toBe(t.series[t.series.length - 1]);
  });

  it('styles tooltips and axes with the tokens', () => {
    const t = chartTokens((name) => DARK[name] ?? '');
    const base = chartBase(t) as { tooltip: { backgroundColor: string }; color: string[] };
    expect(base.tooltip.backgroundColor).toBe('#1a1c1f');
    expect(base.color).toBe(t.series);
    expect(chartAxis(t).splitLine).toEqual({ lineStyle: { color: '#2d3137', type: 'dashed' } });
  });
});
