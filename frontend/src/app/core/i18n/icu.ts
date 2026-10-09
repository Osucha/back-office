import { intlLocale } from './locale';

/**
 * ICU plural / select for messages in TypeScript. `$localize` itself does not evaluate ICU (only
 * templates do), so a TS message keeps the ICU text and this function picks the case with
 * `Intl.PluralRules` of the active language:
 *
 *   icu($localize`:@@shell.dock.count:{count, plural, one {# event} other {# events}}`, { count: n })
 *
 * The Polish target of that id is
 *   "{count, plural, one {# zdarzenie} few {# zdarzenia} many {# zdarzeń} other {# zdarzenia}}".
 *
 * Supported: `{v, plural, =N {..} zero|one|two|few|many|other {..}}` with `#` (the number, formatted
 * for the locale), `{v, select, a {..} other {..}}`, nesting, and plain `{v}` arguments. Text outside
 * the ICU is kept, so `Updated {n, plural, ...} ago` works too. Unknown syntax is returned as is.
 */
export function icu(
  message: string,
  values: Record<string, string | number | null | undefined>,
  locale: string = intlLocale(),
): string {
  try {
    return render(message, values, locale, null);
  } catch {
    return message;
  }
}

function render(
  text: string,
  values: Record<string, string | number | null | undefined>,
  locale: string,
  hash: number | null,
): string {
  let out = '';
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    if (ch === '{') {
      const end = matchingBrace(text, i);
      out += argument(text.slice(i + 1, end), values, locale);
      i = end + 1;
    } else if (ch === '#' && hash !== null) {
      out += new Intl.NumberFormat(locale).format(hash);
      i++;
    } else {
      out += ch;
      i++;
    }
  }
  return out;
}

function argument(
  body: string,
  values: Record<string, string | number | null | undefined>,
  locale: string,
): string {
  const m = /^\s*([\w$]+)\s*(?:,\s*(plural|select|selectordinal)\s*,)?/.exec(body);
  if (!m) throw new Error('bad icu');
  const name = m[1];
  const value = values[name];
  if (!m[2]) return value === null || value === undefined ? '' : String(value);
  const cases = parseCases(body.slice(m[0].length));
  if (m[2] === 'select') {
    const key = String(value ?? 'other');
    const chosen = cases.get(key) ?? cases.get('other') ?? '';
    return render(chosen, values, locale, null);
  }
  const n = Number(value ?? 0);
  const exact = cases.get('=' + n);
  if (exact !== undefined) return render(exact, values, locale, n);
  const category = new Intl.PluralRules(locale, {
    type: m[2] === 'selectordinal' ? 'ordinal' : 'cardinal',
  }).select(n);
  const chosen = cases.get(category) ?? cases.get('other') ?? '';
  return render(chosen, values, locale, n);
}

function parseCases(rest: string): Map<string, string> {
  const cases = new Map<string, string>();
  let i = 0;
  while (i < rest.length) {
    while (i < rest.length && /\s/.test(rest[i])) i++;
    if (i >= rest.length) break;
    const keyStart = i;
    while (i < rest.length && rest[i] !== '{' && !/\s/.test(rest[i])) i++;
    const key = rest.slice(keyStart, i);
    while (i < rest.length && /\s/.test(rest[i])) i++;
    if (rest[i] !== '{' || !key) throw new Error('bad icu case');
    const end = matchingBrace(rest, i);
    cases.set(key, rest.slice(i + 1, end));
    i = end + 1;
  }
  return cases;
}

function matchingBrace(text: string, open: number): number {
  let depth = 0;
  for (let i = open; i < text.length; i++) {
    if (text[i] === '{') depth++;
    else if (text[i] === '}' && --depth === 0) return i;
  }
  throw new Error('unbalanced braces');
}
