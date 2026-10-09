import { Pipe, PipeTransform } from '@angular/core';
import type { TokenUsage } from '../../api/types';
import { decimalSeparator } from '../../i18n/numbers';

/** 1234 -> "1.2k", 2_500_000 -> "2.5M", 999 -> "999" (decimal comma in Polish). */
export function formatTokens(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '-';
  const abs = Math.abs(value);
  if (abs >= 1_000_000) return `${trim(value / 1_000_000)}M`;
  if (abs >= 1_000) return `${trim(value / 1_000)}k`;
  return String(Math.round(value));
}

/** Total tokens of a usage object (input + output; cache tokens are informational). */
export function totalTokens(usage: TokenUsage | null | undefined): number {
  if (!usage) return 0;
  return (usage.inputTokens ?? 0) + (usage.outputTokens ?? 0);
}

function trim(n: number): string {
  const s = n.toFixed(1);
  return (s.endsWith('.0') ? s.slice(0, -2) : s).replace('.', decimalSeparator());
}

@Pipe({ name: 'tokens', pure: true })
export class TokensPipe implements PipeTransform {
  /**
   * `{{ 12345 | tokens }}` -> "12.3k".
   * `{{ usage | tokens }}` -> "in 12.3k / out 1.2k".
   * `{{ usage | tokens:'total' }}` -> "13.5k".
   */
  transform(
    value: number | TokenUsage | null | undefined,
    mode: 'split' | 'total' = 'split',
  ): string {
    if (value === null || value === undefined) return '-';
    if (typeof value === 'number') return formatTokens(value);
    if (mode === 'total') return formatTokens(totalTokens(value));
    const parts = [
      $localize`:@@core.tokens.in:in ${formatTokens(value.inputTokens ?? 0)}:n:`,
      $localize`:@@core.tokens.out:out ${formatTokens(value.outputTokens ?? 0)}:n:`,
    ];
    if (value.cacheReadTokens)
      parts.push($localize`:@@core.tokens.cache:cache ${formatTokens(value.cacheReadTokens)}:n:`);
    return parts.join(' / ');
  }
}
