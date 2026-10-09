import { icu } from './icu';
import {
  LOCALE_STORAGE_KEY,
  readStoredLocale,
  resolveLocale,
  switchLocale,
  translationsUrl,
} from './locale';
import { timeAgo } from '../ui/pipes/time-ago.pipe';

describe('locale resolution', () => {
  it('prefers the stored choice', () => {
    expect(resolveLocale('pl', 'en-US')).toBe('pl');
    expect(resolveLocale('en', 'pl-PL')).toBe('en');
  });

  it('falls back to the browser language, Polish only for pl*', () => {
    expect(resolveLocale(null, 'pl-PL')).toBe('pl');
    expect(resolveLocale(null, 'PL')).toBe('pl');
    expect(resolveLocale(null, 'de-DE')).toBe('en');
    expect(resolveLocale(undefined, undefined)).toBe('en');
  });

  it('ignores unsupported stored values', () => {
    expect(resolveLocale('de', 'pl')).toBe('pl');
    expect(resolveLocale('', 'en')).toBe('en');
  });

  it('survives storage that throws (private mode, blocked site data)', () => {
    const throwing = {
      getItem: () => {
        throw new Error('SecurityError');
      },
    };
    expect(readStoredLocale(throwing)).toBeNull();
    expect(readStoredLocale(null)).toBeNull();
    expect(
      readStoredLocale({ getItem: (k: string) => (k === LOCALE_STORAGE_KEY ? 'pl' : null) }),
    ).toBe('pl');
  });

  it('stores the choice and reloads only when the language changes', () => {
    const saved: Record<string, string> = {};
    const storage = { setItem: (k: string, v: string) => (saved[k] = v) };
    let reloads = 0;
    switchLocale('pl', () => reloads++, storage);
    expect(saved[LOCALE_STORAGE_KEY]).toBe('pl');
    expect(reloads).toBe(1);
    switchLocale('en', () => reloads++, storage); // the active language (tests run in English)
    expect(reloads).toBe(1);
    const throwing = {
      setItem: () => {
        throw new Error('QuotaExceeded');
      },
    };
    expect(() => switchLocale('pl', () => reloads++, throwing)).not.toThrow();
  });

  it('knows where the merged translations are served', () => {
    expect(translationsUrl('pl')).toBe('/i18n/messages.pl.json');
  });
});

describe('icu()', () => {
  const workflows = '{count, plural, one {# workflow} other {# workflows}}';
  const workflowsPl =
    '{count, plural, one {# workflow} few {# workflowy} many {# workflowów} other {# workflowu}}';

  it('picks the English forms', () => {
    expect(icu(workflows, { count: 1 }, 'en-US')).toBe('1 workflow');
    expect(icu(workflows, { count: 2 }, 'en-US')).toBe('2 workflows');
    expect(icu(workflows, { count: 0 }, 'en-US')).toBe('0 workflows');
  });

  it('picks the Polish forms (one / few / many / other)', () => {
    expect(icu(workflowsPl, { count: 1 }, 'pl')).toBe('1 workflow');
    expect(icu(workflowsPl, { count: 2 }, 'pl')).toBe('2 workflowy');
    expect(icu(workflowsPl, { count: 4 }, 'pl')).toBe('4 workflowy');
    expect(icu(workflowsPl, { count: 5 }, 'pl')).toBe('5 workflowów');
    expect(icu(workflowsPl, { count: 12 }, 'pl')).toBe('12 workflowów');
    expect(icu(workflowsPl, { count: 22 }, 'pl')).toBe('22 workflowy');
    expect(icu(workflowsPl, { count: 1.5 }, 'pl')).toBe('1,5 workflowu');
  });

  it('supports =N, select, nesting and text around the ICU', () => {
    expect(icu('{n, plural, =0 {none} one {# item} other {# items}}', { n: 0 }, 'en-US')).toBe(
      'none',
    );
    expect(
      icu(
        'Updated {n, plural, one {# minute} other {# minutes}} ago by {who, select, me {you} other {{who}}}',
        { n: 3, who: 'Ann' },
        'en-US',
      ),
    ).toBe('Updated 3 minutes ago by Ann');
    expect(icu('{who, select, me {you} other {someone}}', { who: 'me' }, 'en-US')).toBe('you');
  });

  it('returns broken messages unchanged', () => {
    expect(icu('{count, plural, one {x}', { count: 1 }, 'en-US')).toBe('{count, plural, one {x}');
  });
});

describe('relative time', () => {
  const now = Date.parse('2026-10-07T12:00:00Z');
  const ago = (ms: number) => new Date(now - ms).toISOString();

  it('keeps the compact English form', () => {
    expect(timeAgo(ago(10_000), now, 'en')).toBe('just now');
    expect(timeAgo(ago(5 * 60_000), now, 'en')).toBe('5m ago');
    expect(timeAgo(ago(3 * 3600_000), now, 'en')).toBe('3h ago');
    expect(timeAgo(ago(9 * 86400_000), now, 'en')).toBe('9d ago');
  });

  it('uses Intl.RelativeTimeFormat for Polish', () => {
    expect(timeAgo(ago(5 * 60_000), now, 'pl')).toBe('5 min temu');
    expect(timeAgo(ago(3 * 3600_000), now, 'pl')).toBe('3 godz. temu');
    expect(timeAgo(ago(86400_000), now, 'pl')).toBe('1 dzień temu');
    expect(timeAgo(ago(9 * 86400_000), now, 'pl')).toBe('9 dni temu');
  });

  it('shows the date after a month in the language of the UI', () => {
    expect(timeAgo('2026-01-02T12:00:00Z', now, 'pl')).toBe('2.01.2026');
    expect(timeAgo('2026-01-02T12:00:00Z', now, 'en')).toBe('1/2/2026');
  });
});
