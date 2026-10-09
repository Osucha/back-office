import { intlLocale } from './locale';

/**
 * Numbers in the UI language ("1,234.5" in English, "1 234,5" in Polish). For pure helpers outside
 * DI; templates can use Angular's `number` / `date` pipes, which follow `LOCALE_ID`.
 */
export function formatNumber(value: number, options: Intl.NumberFormatOptions = {}): string {
  return new Intl.NumberFormat(intlLocale(), options).format(value);
}

/** Fixed decimals with the locale's decimal separator and no grouping ("2.3" / "2,3"). */
export function fixed(value: number, digits: number): string {
  return formatNumber(value, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
    useGrouping: false,
  });
}

/** The locale's decimal separator ("." / ","). */
export function decimalSeparator(): string {
  return (
    new Intl.NumberFormat(intlLocale()).formatToParts(1.5).find((p) => p.type === 'decimal')
      ?.value ?? '.'
  );
}
