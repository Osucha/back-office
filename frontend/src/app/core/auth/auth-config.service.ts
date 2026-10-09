import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, shareReplay, tap } from 'rxjs';
import { AuthApi } from '../api/auth.service';
import type { AuthConfig } from '../api/types';

/** Used when `GET /auth/config` cannot be read (backend down): the Spring Security default. */
export const FALLBACK_AUTH_CONFIG: AuthConfig = {
  loginUrl: '/oauth2/authorization/oidc',
  providerName: 'your identity provider',
  registration: 'INVITE_ONLY',
  accountUrl: null,
};

/** Public login configuration (`GET /api/v1/auth/config`), loaded once and cached. */
@Injectable({ providedIn: 'root' })
export class AuthConfigService {
  private readonly api = inject(AuthApi);
  private cached$: Observable<AuthConfig> | null = null;

  readonly config = signal<AuthConfig | null>(null);
  /** True when the config endpoint could not be reached (fallback in use). */
  readonly unavailable = signal(false);

  load(): Observable<AuthConfig> {
    if (!this.cached$) {
      this.cached$ = this.api.config().pipe(
        map((c) => ({ ...FALLBACK_AUTH_CONFIG, ...c })),
        tap((c) => {
          this.config.set(c);
          this.unavailable.set(false);
        }),
        catchError(() => {
          this.cached$ = null;
          this.unavailable.set(true);
          this.config.set(FALLBACK_AUTH_CONFIG);
          return of(FALLBACK_AUTH_CONFIG);
        }),
        shareReplay(1),
      );
    }
    return this.cached$;
  }
}
