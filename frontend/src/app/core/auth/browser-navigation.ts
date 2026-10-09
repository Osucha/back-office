import { DOCUMENT } from '@angular/common';
import { InjectionToken, inject } from '@angular/core';

/**
 * Full-page navigation leaves the SPA (OIDC login, provider logout). Behind a token so tests can
 * observe it instead of unloading jsdom.
 */
export interface BrowserNavigation {
  /** Leaves the SPA: `location.assign(url)`. */
  assign(url: string): void;
  /** Path + query of the current page, used as `returnTo`. */
  currentPath(): string;
}

export const BROWSER_NAVIGATION = new InjectionToken<BrowserNavigation>('BROWSER_NAVIGATION', {
  providedIn: 'root',
  factory: () => {
    const doc = inject(DOCUMENT);
    return {
      assign: (url: string) => doc.defaultView?.location.assign(url),
      currentPath: () => {
        const loc = doc.defaultView?.location;
        return loc ? loc.pathname + loc.search : '/';
      },
    };
  },
});

/**
 * Only same-origin absolute paths are accepted as `returnTo` (no `//host`, no scheme), so a crafted
 * link cannot turn the login into an open redirect. The login page itself is never a target.
 */
export function safeReturnTo(value: string | null | undefined): string {
  if (!value || !value.startsWith('/') || value.startsWith('//') || value.startsWith('/\\')) {
    return '/';
  }
  if (value === '/login' || value.startsWith('/login?') || value.startsWith('/login/')) return '/';
  return value;
}
