import {
  provideHttpClient,
  withFetch,
  withInterceptors,
  withXsrfConfiguration,
} from '@angular/common/http';
import {
  ApplicationConfig,
  Injector,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import {
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withPreloading,
  withViewTransitions,
} from '@angular/router';
import { provideNzConfig } from 'ng-zorro-antd/core/config';
import { provideEchartsCore } from 'ngx-echarts';

import { routes } from './app.routes';
import { IdentityService } from './core/auth/identity.service';
import { httpErrorInterceptor } from './core/http/http-error.interceptor';
import { HoverPreloadingStrategy } from './core/routing/hover-preloading';
import { loadNzLocale } from './core/ui/nz-locale';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
      withViewTransitions(),
      // Lazy routes are fetched when the user hovers or focuses a rail item.
      withPreloading(HoverPreloadingStrategy),
    ),
    // Fetch backend: needed for `cache: 'no-store'` on API GETs (set by the interceptor).
    // Same origin, so the session cookie is sent without `withCredentials`; Spring Security's
    // SPA CSRF mode sets the XSRF-TOKEN cookie that Angular echoes on unsafe methods.
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([httpErrorInterceptor]),
    ),
    // Who is signed in is known before the first route is resolved (guards need it). NG-ZORRO's
    // English locale loads in parallel (ng-zorro-antd/i18n + date-fns stay out of the initial
    // bundle; see core/ui/nz-locale.ts). NzModalService is created on demand by ConfirmService.
    provideAppInitializer(() => {
      const injector = inject(Injector);
      return Promise.all([inject(IdentityService).load(), loadNzLocale(injector)]);
    }),
    provideNzConfig({
      message: { nzMaxStack: 3, nzDuration: 4000 },
      notification: { nzPlacement: 'bottomRight', nzMaxStack: 3 },
      table: { nzSize: 'small' },
      card: { nzSize: 'small' },
    }),
    // ECharts is loaded lazily on the first chart so it stays out of the initial bundle.
    provideEchartsCore({ echarts: () => import('./core/charts/echarts') }),
  ],
};
