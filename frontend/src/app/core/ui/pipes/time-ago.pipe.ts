import { Pipe, PipeTransform } from '@angular/core';
import { ANGULAR_LOCALE_ID, activeLocale, isAppLocale } from '../../i18n/locale';

/**
 * "just now", "5m ago", "3h ago", "9d ago", then the date. English keeps the compact Relay form;
 * other languages use `Intl.RelativeTimeFormat` ("5 min temu", "3 godz. temu", "9 dni temu"),
 * which also gets their plural forms right. `locale` defaults to the UI language.
 */
export function timeAgo(
  iso: string | null | undefined,
  now = Date.now(),
  locale: string = activeLocale(),
): string {
  if (!iso) return '-';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return '-';
  const tag = isAppLocale(locale) ? ANGULAR_LOCALE_ID[locale] : locale;
  const diff = Math.max(0, now - t);
  const s = Math.floor(diff / 1000);
  if (s < 45) return $localize`:@@core.time.justNow:just now`;
  const m = Math.floor(s / 60);
  const h = Math.floor(m / 60);
  const d = Math.floor(h / 24);
  if (d >= 30) return new Date(t).toLocaleDateString(tag);
  if (locale === 'en') return m < 60 ? `${m}m ago` : h < 24 ? `${h}h ago` : `${d}d ago`;
  const rtf = new Intl.RelativeTimeFormat(tag, { style: 'short', numeric: 'always' });
  if (m < 60) return rtf.format(-m, 'minute');
  return h < 24 ? rtf.format(-h, 'hour') : rtf.format(-d, 'day');
}

@Pipe({ name: 'timeAgo', pure: true })
export class TimeAgoPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return timeAgo(value);
  }
}
