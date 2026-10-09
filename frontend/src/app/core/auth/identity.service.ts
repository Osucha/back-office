import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthApi } from '../api/auth.service';
import type { Me, UserPreferences } from '../api/types';
import { AuthConfigService } from './auth-config.service';
import { BROWSER_NAVIGATION, safeReturnTo } from './browser-navigation';
import {
  buildPermissionView,
  hasAnyAdminPermission,
  hasPermission,
  hasProjectPermission,
  isAllowed,
  projectIdsWith,
  projectRolesOf,
  type AccessRule,
} from './permissions';

/**
 * - `unknown`: `/me` not answered yet (app bootstrap)
 * - `authenticated`: `/me` returned 200
 * - `unauthenticated`: `/me` returned 401 (no or expired session)
 * - `unreachable`: `/me` failed otherwise (backend down, 5xx); the login page offers a retry
 */
export type IdentityStatus = 'unknown' | 'authenticated' | 'unauthenticated' | 'unreachable';

/**
 * The signed-in user and their effective permissions from `GET /api/v1/me`. Single source of truth
 * for guards, the menu, the `*boCan` directives and every permission-aware button. Loaded once by
 * `provideAppInitializer`; reloaded after changes that affect the caller's own access (creating a
 * project, changing memberships).
 */
@Injectable({ providedIn: 'root' })
export class IdentityService {
  private readonly api = inject(AuthApi);
  private readonly authConfig = inject(AuthConfigService);
  private readonly nav = inject(BROWSER_NAVIGATION);

  readonly me = signal<Me | null>(null);
  readonly status = signal<IdentityStatus>('unknown');

  readonly isAuthenticated = computed(() => this.me() !== null);
  readonly user = computed(() => this.me()?.user ?? null);
  readonly allProjects = computed(() => this.view().allProjects);
  readonly isAdmin = computed(() => hasAnyAdminPermission(this.view()));
  readonly preferences = computed<UserPreferences>(
    () => this.me()?.preferences ?? { theme: 'SYSTEM', pageSize: 20 },
  );
  readonly pageSize = computed(() => {
    const size = this.me()?.preferences?.pageSize;
    return size && size >= 10 && size <= 200 ? size : 20;
  });

  private readonly view = computed(() => buildPermissionView(this.me()));

  /** Loads `/me`; never rejects. */
  async load(): Promise<Me | null> {
    try {
      const me = await firstValueFrom(this.api.me());
      this.setMe(me);
      return me;
    } catch (err) {
      this.me.set(null);
      this.status.set(
        err instanceof HttpErrorResponse && err.status === 401 ? 'unauthenticated' : 'unreachable',
      );
      return null;
    }
  }

  /** Re-reads `/me` after a change of the caller's own access; keeps the old state on failure. */
  async refresh(): Promise<void> {
    try {
      this.setMe(await firstValueFrom(this.api.me()));
    } catch {
      // A 401 here is not escalated: the next real request runs the session-expiry flow.
    }
  }

  setMe(me: Me | null): void {
    this.me.set(me);
    this.status.set(me ? 'authenticated' : 'unauthenticated');
  }

  /** Updates the preferences part of `me` after `PUT /me/preferences`. */
  setPreferences(prefs: UserPreferences): void {
    const me = this.me();
    if (me) this.me.set({ ...me, preferences: prefs });
  }

  // ---- permission checks (UI only; the backend decides)

  /** Platform permission; an array means "any of". */
  can(permission: string | readonly string[]): boolean {
    return hasPermission(this.view(), permission);
  }

  /** Project permission on `projectId`, or on any project when the id is omitted. */
  canInProject(permission: string, projectId?: string | null): boolean {
    return hasProjectPermission(this.view(), permission, projectId);
  }

  /** Route rule: `permission` or `projectPermission` (see `isAllowed`). */
  allows(rule: AccessRule, projectId?: string | null): boolean {
    return isAllowed(this.view(), rule, projectId);
  }

  projectRoles(projectId: string | null | undefined): string[] {
    return projectRolesOf(this.view(), projectId);
  }

  projectsWith(permission: string): string[] {
    return projectIdsWith(this.view(), permission);
  }

  // ---- login / logout (full-page navigations: the API is the BFF)

  /** Starts the OIDC login at the backend; `returnTo` defaults to the current page. */
  async login(returnTo?: string): Promise<void> {
    const config = await firstValueFrom(this.authConfig.load());
    const target = safeReturnTo(returnTo ?? this.nav.currentPath());
    const loginUrl = config.loginUrl || '/oauth2/authorization/oidc';
    const sep = loginUrl.includes('?') ? '&' : '?';
    this.nav.assign(`${loginUrl}${sep}returnTo=${encodeURIComponent(target)}`);
  }

  /** Invalidates the session, then leaves for the provider's end-session URL (or `/login`). */
  async logout(): Promise<void> {
    let logoutUrl: string | null | undefined = null;
    try {
      logoutUrl = (await firstValueFrom(this.api.logout())).logoutUrl;
    } catch {
      // the session is gone either way; fall through to the login page
    }
    this.me.set(null);
    this.status.set('unauthenticated');
    this.nav.assign(logoutUrl || '/login');
  }
}
