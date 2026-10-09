import { DurationPipe, durationBetween, formatDuration } from './duration.pipe';
import { MoneyPipe, formatMoney } from './money.pipe';
import { TokensPipe, formatTokens, totalTokens } from './tokens.pipe';

describe('MoneyPipe', () => {
  const pipe = new MoneyPipe();

  it('uses 4 decimals below one dollar', () => {
    expect(pipe.transform({ amount: 0.0123, currency: 'USD' })).toBe('$0.0123');
    expect(pipe.transform(0.5)).toBe('$0.5000');
  });

  it('uses 2 decimals from one dollar up', () => {
    expect(pipe.transform({ amount: 1, currency: 'USD' })).toBe('$1.00');
    expect(pipe.transform({ amount: 1234.5, currency: 'USD' })).toBe('$1,234.50');
  });

  it('formats zero and missing values', () => {
    expect(pipe.transform({ amount: 0, currency: 'USD' })).toBe('$0.00');
    expect(pipe.transform(null)).toBe('-');
    expect(pipe.transform(undefined)).toBe('-');
  });

  it('respects other currencies', () => {
    expect(formatMoney({ amount: 2, currency: 'EUR' })).toBe('€2.00');
    expect(formatMoney({ amount: 2, currency: 'CHF' })).toBe('CHF 2.00');
  });
});

describe('TokensPipe', () => {
  const pipe = new TokensPipe();

  it('abbreviates thousands and millions', () => {
    expect(formatTokens(999)).toBe('999');
    expect(formatTokens(1000)).toBe('1k');
    expect(formatTokens(12345)).toBe('12.3k');
    expect(formatTokens(2_500_000)).toBe('2.5M');
  });

  it('renders usage objects split or total', () => {
    const usage = { inputTokens: 12000, outputTokens: 1500, cacheReadTokens: 8000 };
    expect(pipe.transform(usage)).toBe('in 12k / out 1.5k / cache 8k');
    expect(pipe.transform(usage, 'total')).toBe('13.5k');
    expect(totalTokens(undefined)).toBe(0);
  });

  it('handles empty values', () => {
    expect(pipe.transform(null)).toBe('-');
    expect(pipe.transform(undefined)).toBe('-');
  });
});

describe('DurationPipe', () => {
  const pipe = new DurationPipe();

  it('formats milliseconds through days', () => {
    expect(formatDuration(850)).toBe('850ms');
    expect(formatDuration(12_300)).toBe('12s');
    expect(formatDuration(2_300)).toBe('2.3s');
    expect(formatDuration(125_000)).toBe('2m 5s');
    expect(formatDuration(7_260_000)).toBe('2h 1m');
    expect(formatDuration(90_000_000)).toBe('1d 1h');
  });

  it('computes the elapsed time between two timestamps', () => {
    expect(durationBetween('2026-01-01T00:00:00Z', '2026-01-01T00:02:05Z')).toBe(125_000);
    expect(pipe.transform('2026-01-01T00:00:00Z', '2026-01-01T00:00:01.500Z')).toBe('1.5s');
    expect(durationBetween(null)).toBeNull();
  });

  it('accepts seconds with the s unit', () => {
    expect(pipe.transform(90, 's')).toBe('1m 30s');
  });

  it('handles empty values', () => {
    expect(pipe.transform(null)).toBe('-');
    expect(pipe.transform(-5)).toBe('-');
  });
});
