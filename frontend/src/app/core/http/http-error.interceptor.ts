import { HttpErrorResponse, HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, tap, throwError } from 'rxjs';
import { API_BASE, AUTH_PROBE, SKIP_ERROR_TOAST } from '../api/api-base';
import type { AccessProblem, Problem } from '../api/types';
import { IdentityService } from '../auth/identity.service';
import { SessionExpiryService } from '../auth/session-expiry.service';
import { NotifyService } from '../ui/notify.service';

export const CONNECTION_LOST_TITLE = $localize`:@@core.http.connectionLost.title:Connection to the server lost`;
export const CONNECTION_LOST_TEXT = $localize`:@@core.http.connectionLost.text:The request did not reach Back Office. Check your network connection and try again; you are still signed in.`;
export const ACCESS_DENIED_TITLE = $localize`:@@core.http.accessDenied:Access denied`;

/** Extracts a human message from an RFC 9457 problem (or a plain HttpErrorResponse). */
export function problemMessage(err: unknown): string {
  if (err instanceof HttpErrorResponse) return httpErrorMessage(err);
  if (err instanceof Error) return err.message;
  return $localize`:@@core.http.unexpected:Unexpected error`;
}

function httpErrorMessage(err: HttpErrorResponse): string {
  const body = err.error as Problem | string | null | undefined;
  const fromProblem = body && typeof body === 'object' ? problemBodyMessage(body) : null;
  if (fromProblem) return fromProblem;
  if (typeof body === 'string' && body.trim()) return body.slice(0, 300);
  if (err.status === 0)
    return $localize`:@@core.http.cannotReachApi:Cannot reach the API. Is the backend running?`;
  return `${err.status} ${err.statusText || $localize`:@@core.http.requestFailed:Request failed`}`;
}

/** `detail` (or `title`) plus the field errors; null when the problem carries neither. */
function problemBodyMessage(body: Problem): string | null {
  const fieldErrors = (body.errors ?? [])
    .map((e) => [e.field, e.message].filter(Boolean).join(': '))
    .filter(Boolean);
  const main = body.detail || body.title;
  if (main && fieldErrors.length) return `${main} (${fieldErrors.join('; ')})`;
  if (main) return main;
  return fieldErrors.length ? fieldErrors.join('; ') : null;
}

/** Text of a 403 notification: the problem detail plus the missing permission. */
export function accessDeniedMessage(err: HttpErrorResponse): string {
  const body = (typeof err.error === 'object' && err.error ? err.error : {}) as AccessProblem;
  const detail =
    body.detail || $localize`:@@core.http.noPermission:You do not have permission to do this.`;
  if (!body.permission) return detail;
  const missing = body.projectId
    ? $localize`:@@core.http.missingPermissionProject:Missing permission: ${body.permission}:permission: in this project.`
    : $localize`:@@core.http.missingPermission:Missing permission: ${body.permission}:permission:.`;
  return `${detail} ${missing}`;
}

/**
 * One place for the HTTP rules of the multi-user UI (API requests only):
 *
 * - GET requests are sent with `cache: no-store` so a browser cache never shows one account's data
 *   to another account after switching users;
 * - every successful response tells `SessionExpiryService` that the server session was extended;
 * - **401** (except identity probes) = the session ended: the non-blocking "session expired" notice,
 *   never a redirect;
 * - **status 0** = the request did not reach the server: "connection lost", never a logout;
 * - **403** = the problem `detail` and the missing permission in an error notification, no redirect;
 *   except the restricted-session codes of a local sign-in (ADR 0021): `password-change-required`
 *   and `mfa-enrolment-required` route to `/change-password` / `/setup-mfa` without a notification;
 * - other errors: the problem detail in an error notification unless the request is `silent()`
 *   (the caller shows field errors itself, see `core/forms/server-errors.ts`).
 */
export const httpErrorInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith(API_BASE)) return next(req);
  const isProbe = req.context.get(AUTH_PROBE);
  const isSilent = req.context.get(SKIP_ERROR_TOAST);
  const identity = inject(IdentityService);
  const session = inject(SessionExpiryService);
  const notify = inject(NotifyService);
  const router = inject(Router);
  const request = req.method === 'GET' ? req.clone({ cache: 'no-store' }) : req;

  return next(request).pipe(
    tap((event) => {
      if (event instanceof HttpResponse) session.touched();
    }),
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse)) return throwError(() => err);
      if (isProbe) return throwError(() => err);
      if (err.status === 401) {
        session.markExpired();
      } else if (err.status === 0) {
        notify.error(CONNECTION_LOST_TITLE, CONNECTION_LOST_TEXT);
      } else if (err.status === 403 && restrictedRoute(err)) {
        routeRestricted(router, restrictedRoute(err)!);
      } else if (err.status === 403) {
        notify.error(ACCESS_DENIED_TITLE, accessDeniedMessage(err));
      } else {
        // The server answered, so the session is alive.
        if (identity.isAuthenticated()) session.touched();
        if (!isSilent) notify.error(errorTitle(err.status), problemMessage(err));
      }
      return throwError(() => err);
    }),
  );
};

/** Where a restricted session (ADR 0021) must go: the page that lifts the restriction, or null. */
export function restrictedRoute(err: HttpErrorResponse): string | null {
  const code = (err.error as { code?: unknown } | null)?.code;
  if (code === 'password-change-required') return '/change-password';
  if (code === 'mfa-enrolment-required') return '/setup-mfa';
  return null;
}

function routeRestricted(router: Router, target: string): void {
  const current = router.url;
  if (current.startsWith(target)) return;
  const returnTo = current.startsWith('/login') || current === '/' ? null : current;
  void router.navigate([target], { queryParams: { forced: 1, returnTo } });
}

function errorTitle(status: number): string {
  if (status === 404) return $localize`:@@core.http.notFound:Not found`;
  if (status === 409) return $localize`:@@core.http.conflict:Conflict`;
  if (status === 400 || status === 422)
    return $localize`:@@core.http.rejected:The request was rejected`;
  if (status >= 500) return $localize`:@@core.http.serverError:Server error`;
  return $localize`:@@core.http.requestFailed:Request failed`;
}
