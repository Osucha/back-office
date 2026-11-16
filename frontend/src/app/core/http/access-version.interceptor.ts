import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { catchError, tap, throwError } from 'rxjs';
import { API_BASE } from '../api/api-base';
import { IdentityService } from '../auth/identity.service';
import { restrictedRoute } from './http-error.interceptor';

/** Response header with the caller's current access version (ADR 0068); `Me.accessVersion` holds the loaded one. */
export const ACCESS_VERSION_HEADER = 'X-Access-Version';

/** A 403 reloads `/me` at most this often (a page that keeps hitting a forbidden endpoint must not loop). */
export const DENIED_REFRESH_COOLDOWN_MS = 10_000;

const ME_URL = `${API_BASE}/me`;

/**
 * Keeps `IdentityService.me()` in step with access changes made by someone else (ADR 0068). One reload at a
 * time: parallel responses announcing the same newer version start one `/me`, and a version that was already
 * asked for is not asked for again (an instance that has not seen the change yet cannot cause a loop).
 */
@Injectable({ providedIn: 'root' })
export class AccessFreshness {
  private readonly identity = inject(IdentityService);
  private reloading: Promise<void> | null = null;
  private requested: number | null = null;
  private deniedAt = Number.NEGATIVE_INFINITY;

  /** A response announced [version]: reload `/me` when it is newer than the loaded one. */
  observed(version: number): void {
    const known = this.identity.me()?.accessVersion;
    if (known === undefined || known === null || version <= known) return;
    if (this.reloading || version === this.requested) return;
    this.requested = version;
    this.reload();
  }

  /** A 403: the UI may show more than the caller may do; reload `/me` (throttled). */
  denied(now = Date.now()): void {
    if (!this.identity.isAuthenticated() || this.reloading) return;
    if (now - this.deniedAt < DENIED_REFRESH_COOLDOWN_MS) return;
    this.deniedAt = now;
    this.reload();
  }

  private reload(): void {
    this.reloading = this.identity.refresh().finally(() => (this.reloading = null));
  }
}

/** Reads `X-Access-Version` from API responses and reloads `/me` when the caller's access changed (ADR 0068). */
export const accessVersionInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API_BASE) || req.url === ME_URL) return next(req);
  const freshness = inject(AccessFreshness);
  const announce = (header: string | null) => {
    const version = header === null ? Number.NaN : Number(header);
    if (Number.isFinite(version)) freshness.observed(version);
  };
  return next(req).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) announce(event.headers.get(ACCESS_VERSION_HEADER));
    }),
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse) {
        announce(err.headers?.get(ACCESS_VERSION_HEADER) ?? null);
        if (err.status === 403 && !restrictedRoute(err)) freshness.denied();
      }
      return throwError(() => err);
    }),
  );
};
