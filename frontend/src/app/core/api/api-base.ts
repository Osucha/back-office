import { HttpContext, HttpContextToken, HttpParams } from '@angular/common/http';

/** Base path of the REST API; nginx / the dev proxy forward it to the backend. */
export const API_BASE = '/api/v1';

/** Set on a request to suppress the global error toast (the caller handles errors itself). */
export const SKIP_ERROR_TOAST = new HttpContextToken<boolean>(() => false);

/**
 * Set on requests whose 401 means "not signed in" rather than "session expired" (the bootstrap
 * `GET /me`, the public auth config). The interceptor then leaves the 401 to the caller.
 */
export const AUTH_PROBE = new HttpContextToken<boolean>(() => false);

export function silent(): { context: HttpContext } {
  return { context: new HttpContext().set(SKIP_ERROR_TOAST, true) };
}

/** Options for identity probes: no error notification and no session-expiry handling. */
export function probe(): { context: HttpContext } {
  return { context: new HttpContext().set(SKIP_ERROR_TOAST, true).set(AUTH_PROBE, true) };
}

export type QueryValue =
  string | number | boolean | null | undefined | ReadonlyArray<string | number | boolean>;

/**
 * Builds HttpParams skipping null/undefined/empty values. Arrays are serialised as
 * comma-separated lists (`style: form, explode: false` in the contract).
 */
export function params(values: Record<string, QueryValue>): HttpParams {
  let p = new HttpParams();
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined || value === '') continue;
    if (Array.isArray(value)) {
      if (value.length === 0) continue;
      p = p.set(key, value.join(','));
    } else {
      p = p.set(key, String(value));
    }
  }
  return p;
}

export function url(...segments: Array<string | number>): string {
  return [API_BASE, ...segments.map((s) => encodeURIComponent(String(s)))].join('/');
}
