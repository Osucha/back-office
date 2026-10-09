import { Pipe, PipeTransform } from '@angular/core';
import type { Money } from '../../api/types';
import { intlLocale } from '../../i18n/locale';

/**
 * Formats a Money value. Amounts below 1 show 4 decimals (LLM calls are cheap), otherwise 2.
 * `{{ cost | money }}` -> "$0.0123" / "$12.34"; null/undefined -> "-".
 */
export function formatMoney(value: Money | number | null | undefined, currency = 'USD'): string {
  if (value === null || value === undefined) return '-';
  const amount = typeof value === 'number' ? value : value.amount;
  const cur = typeof value === 'number' ? currency : value.currency || currency;
  if (amount === null || amount === undefined || Number.isNaN(amount)) return '-';
  const abs = Math.abs(amount);
  const decimals = abs === 0 ? 2 : abs < 1 ? 4 : 2;
  const formatted = new Intl.NumberFormat(intlLocale(), {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  }).format(amount);
  const symbol = cur === 'USD' ? '$' : cur === 'EUR' ? '€' : cur === 'PLN' ? 'zł' : `${cur} `;
  return `${symbol}${formatted}`;
}

@Pipe({ name: 'money', pure: true })
export class MoneyPipe implements PipeTransform {
  transform(value: Money | number | null | undefined, currency = 'USD'): string {
    return formatMoney(value, currency);
  }
}
