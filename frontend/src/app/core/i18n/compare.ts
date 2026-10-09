import { intlLocale } from './locale';

/**
 * Comparators for `Array.prototype.sort`. Never call `sort()` without one (SonarQube S2871):
 *
 * - {@link compareKeys} for machine identifiers (permission keys, role keys, enum values) that end up in
 *   payloads or equality checks: UTF-16 code-unit order, the same as the default `sort()`, but explicit
 *   and independent of the UI language.
 * - {@link compareText} for text shown to people: collated in the active UI language.
 */
export function compareKeys(a: string, b: string): number {
  if (a < b) return -1;
  return a > b ? 1 : 0;
}

export function compareText(a: string, b: string): number {
  return a.localeCompare(b, intlLocale());
}
