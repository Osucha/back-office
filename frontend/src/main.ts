import { registerLocaleData } from '@angular/common';
import { loadTranslations } from '@angular/localize';
import {
  type AppLocale,
  readStoredLocale,
  resolveLocale,
  setActiveLocale,
  translationsUrl,
} from './app/core/i18n/locale';

/**
 * Start-up (ADR 0026): pick the UI language, load its translations, then import the app.
 *
 * The app is imported dynamically on purpose: translated templates and module-level `$localize`
 * constants (navigation labels, route titles) must be evaluated AFTER `loadTranslations()`.
 * English needs no file; any other language falls back to English when its file cannot be loaded.
 */
const LOCALE_DATA: Partial<Record<AppLocale, () => Promise<{ default: unknown }>>> = {
  pl: () => import('@angular/common/locales/pl'),
};

async function loadLocale(locale: AppLocale): Promise<AppLocale> {
  if (locale === 'en') return 'en';
  try {
    const [response, data] = await Promise.all([
      fetch(translationsUrl(locale), { cache: 'no-cache' }),
      LOCALE_DATA[locale]?.(),
    ]);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const file = (await response.json()) as { translations?: Record<string, string> };
    loadTranslations(file.translations ?? {});
    if (data) registerLocaleData(data.default, locale);
    return locale;
  } catch (err) {
    console.warn(`Translations for "${locale}" could not be loaded; using English.`, err);
    return 'en';
  }
}

async function start(): Promise<void> {
  const wanted = resolveLocale(readStoredLocale(), globalThis.navigator?.language);
  const locale = await loadLocale(wanted);
  setActiveLocale(locale);
  document.documentElement.lang = locale;
  const { bootstrap } = await import('./bootstrap');
  await bootstrap(locale);
}

start().catch((err) => console.error(err));
