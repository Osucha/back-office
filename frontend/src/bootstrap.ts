import { LOCALE_ID, mergeApplicationConfig } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { ANGULAR_LOCALE_ID, type AppLocale } from './app/core/i18n/locale';

/**
 * Imported by main.ts once the translations of `locale` are loaded (see there). `LOCALE_ID` drives
 * Angular's date, number and plural pipes; DEFAULT_CURRENCY_CODE stays Angular's default (USD).
 */
export function bootstrap(locale: AppLocale) {
  document.title = $localize`:@@shell.app.title:Back Office`;
  return bootstrapApplication(
    App,
    mergeApplicationConfig(appConfig, {
      providers: [{ provide: LOCALE_ID, useValue: ANGULAR_LOCALE_ID[locale] }],
    }),
  );
}
