/**
 * Number formats of the benchmark pages (pure; tested in benchmark-format.spec.ts): one money format for the
 * leaderboard, the attempts and the attempt panel (two decimals, three under $0.10), rounded chart axes and one
 * duration format (`formatDuration`: "850 ms", "4,2 s", "6 min 51 s").
 */
import type { Money } from '../../../../core/api/types';
import { intlLocale } from '../../../../core/i18n/locale';
import { formatDuration } from '../../../../core/ui/pipes/duration.pipe';

const SYMBOLS: Readonly<Record<string, string>> = { USD: '$', EUR: '€', PLN: 'zł' };

/** Decimals of an amount: two, three under 0.10 (a cheap model answer costs fractions of a cent). */
export function moneyDecimals(amount: number): 2 | 3 {
  const abs = Math.abs(amount);
  return abs > 0 && abs < 0.1 ? 3 : 2;
}

/**
 * "$22.71", "$0.042", "$0.00"; "<$0.001" for a positive amount that would round to zero; "-" without a value.
 * [decimals] fixes the precision (chart axes: the same for every tick).
 */
export function benchmarkMoney(
  value: Money | number | null | undefined,
  decimals?: number,
): string {
  if (value === null || value === undefined) return '-';
  const amount = typeof value === 'number' ? value : value.amount;
  const currency = typeof value === 'number' ? 'USD' : value.currency || 'USD';
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '-';
  const digits = decimals ?? moneyDecimals(amount);
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  const abs = Math.abs(amount);
  const smallest = Math.pow(10, -digits);
  if (abs > 0 && abs < smallest / 2) {
    const text = new Intl.NumberFormat(intlLocale(), {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    }).format(smallest);
    return `<${symbol}${text}`;
  }
  const text = new Intl.NumberFormat(intlLocale(), {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(abs);
  return `${amount < 0 ? '-' : ''}${symbol}${text}`;
}

/** A rounded axis from 0: about [ticks] steps of 1, 2 or 5 × 10^n with room above [max]. */
export function niceAxis(max: number, ticks = 5): { max: number; interval: number } {
  const top = max > 0 ? max * 1.1 : 0.1;
  const raw = top / ticks;
  const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
  const n = raw / magnitude;
  const step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * magnitude;
  const round = (v: number) => Number(v.toPrecision(12));
  return { max: round(Math.ceil(round(top / step)) * step), interval: round(step) };
}

/** Decimals of every tick of a money axis up to [max]: three below $0.10, two otherwise. */
export function axisMoneyDecimals(max: number): 2 | 3 {
  return max < 0.1 ? 3 : 2;
}

/** The one duration format of the benchmark pages. */
export const benchmarkDuration = formatDuration;

/**
 * Decimals of a whole money column, so the decimal separators line up: three when any non-zero amount is below $1,
 * two otherwise.
 */
export function columnDecimals(amounts: readonly (number | null | undefined)[]): 2 | 3 {
  return amounts.some((a) => a !== null && a !== undefined && a !== 0 && Math.abs(a) < 1) ? 3 : 2;
}

const FIXED: Record<2 | 3, (v: Money | number | null | undefined) => string> = {
  2: (v) => benchmarkMoney(v, 2),
  3: (v) => benchmarkMoney(v, 3),
};

/** The money format of a column with [decimals] (stable functions, for `bo-cost [format]`). */
export function moneyWith(decimals: 2 | 3): (v: Money | number | null | undefined) => string {
  return FIXED[decimals];
}

/** A score axis fitted to the data: 5 points of room, round steps of 5, 10 or 20, within 0-100. */
export function scoreAxis(scores: readonly number[]): {
  min: number;
  max: number;
  interval: number;
} {
  if (!scores.length) return { min: 0, max: 100, interval: 20 };
  const lo = Math.max(0, Math.min(...scores) - 5);
  const hi = Math.min(100, Math.max(...scores) + 5);
  const span = hi - lo;
  const interval = span <= 20 ? 5 : span <= 50 ? 10 : 20;
  return {
    min: Math.max(0, Math.floor(lo / interval) * interval),
    max: Math.min(100, Math.ceil(hi / interval) * interval),
    interval,
  };
}
