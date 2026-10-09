/**
 * Relay number and time formats (design-language.md, "Writing"): costs `$0.042` (up to four
 * decimals under a dollar, trailing zeros trimmed to two), durations `4m 12s`, relative times with
 * the absolute time on hover.
 */
import type { Money } from '../../api/types';
import { formatTokens } from '../pipes/tokens.pipe';
import type { TokenUsage } from '../../api/types';
import { intlLocale } from '../../i18n/locale';
import { decimalSeparator } from '../../i18n/numbers';

export function formatCost(value: Money | number | null | undefined): string {
  if (value === null || value === undefined) return '-';
  const amount = typeof value === 'number' ? value : value.amount;
  const currency = typeof value === 'number' ? 'USD' : value.currency || 'USD';
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '-';
  const symbol = CURRENCY_SYMBOLS.get(currency) ?? `${currency} `;
  return `${amount < 0 ? '-' : ''}${symbol}${costDigits(Math.abs(amount))}`;
}

const CURRENCY_SYMBOLS: ReadonlyMap<string, string> = new Map([
  ['USD', '$'],
  ['EUR', '€'],
  ['PLN', 'zł'],
]);

/** `text` without trailing zeros (a linear scan instead of a backtracking regex). */
function trimTrailingZeros(text: string): string {
  let end = text.length;
  while (end > 0 && text[end - 1] === '0') end--;
  return text.slice(0, end);
}

/** The digits of a non-negative amount: up to four decimals under one, two from one up. */
function costDigits(abs: number): string {
  if (abs === 0) return `0${decimalSeparator()}00`;
  if (abs >= 1)
    return new Intl.NumberFormat(intlLocale(), {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(abs);
  if (abs < 0.0001) return `<0${decimalSeparator()}0001`;
  let text = trimTrailingZeros(abs.toFixed(4));
  const decimals = text.split('.')[1]?.length ?? 0;
  if (decimals < 2) text = abs.toFixed(2);
  return text.replace('.', decimalSeparator());
}

/** "in 12k · out 1.5k · cache 8k" for the cost tick tooltip; empty when nothing was used. */
export function formatUsage(usage: TokenUsage | null | undefined): string {
  if (!usage) return '';
  const parts: string[] = [];
  if (usage.inputTokens)
    parts.push($localize`:@@core.tokens.in:in ${formatTokens(usage.inputTokens)}:n:`);
  if (usage.outputTokens)
    parts.push($localize`:@@core.tokens.out:out ${formatTokens(usage.outputTokens)}:n:`);
  if (usage.cacheReadTokens)
    parts.push(
      $localize`:@@core.tokens.cacheRead:cache read ${formatTokens(usage.cacheReadTokens)}:n:`,
    );
  if (usage.cacheWriteTokens)
    parts.push(
      $localize`:@@core.tokens.cacheWrite:cache write ${formatTokens(usage.cacheWriteTokens)}:n:`,
    );
  return parts.length ? $localize`:@@core.tokens.usage:${parts.join(' · ')}:usage: tokens` : '';
}

/** Absolute timestamp for `title` attributes next to relative times. */
export function absoluteTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '';
  return new Date(t).toLocaleString(intlLocale(), {
    dateStyle: 'medium',
    timeStyle: 'short',
  });
}

/** Sum of money values in the currency of the first one. */
export function sumMoney(values: (Money | null | undefined)[]): Money | null {
  let total = 0;
  let currency: string | null = null;
  for (const v of values) {
    if (!v) continue;
    currency ??= v.currency || 'USD';
    total += v.amount ?? 0;
  }
  return currency ? { amount: total, currency } : null;
}

/** First name for greetings: "Ann Admin" -> "Ann", "bob.builder@x" -> "Bob". */
export function firstName(displayName: string | null | undefined, email?: string | null): string {
  const source = (displayName || email || '').trim();
  if (!source) return '';
  const word =
    source
      .replace(/@.*/, '')
      .split(/[\s._-]+/)
      .filter(Boolean)[0] ?? '';
  return word ? word[0].toUpperCase() + word.slice(1) : '';
}
