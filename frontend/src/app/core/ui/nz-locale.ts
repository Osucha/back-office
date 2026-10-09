import type { Injector } from '@angular/core';
import { activeLocale } from '../i18n/locale';

/**
 * Sets NG-ZORRO's locale (English or Polish, following the UI language; core/i18n/locale.ts)
 * without putting `ng-zorro-antd/i18n` in the initial bundle. That entry point pulls
 * `ng-zorro-antd/core/time` and the whole `date-fns` barrel (about 18 kB gzip); only lazy pages
 * (tables, pickers, forms, modals) need it. The app initializer awaits this in parallel with
 * `GET /me`, so the locale is set before the first route renders.
 */
export async function loadNzLocale(injector: Injector): Promise<void> {
  const i18n = await import('ng-zorro-antd/i18n');
  const service = injector.get(i18n.NzI18nService);
  if (activeLocale() === 'pl') {
    const { default: pl } = await import('date-fns/locale/pl');
    service.setLocale(i18n.pl_PL);
    service.setDateLocale(pl);
  } else {
    service.setLocale(i18n.en_US);
  }
}
