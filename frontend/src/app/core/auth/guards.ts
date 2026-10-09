import { inject } from '@angular/core';
import {
  ActivatedRouteSnapshot,
  CanActivateChildFn,
  CanActivateFn,
  CanDeactivateFn,
  RedirectCommand,
  Router,
} from '@angular/router';
import { ConfirmService } from '../ui/confirm.service';
import { NotifyService } from '../ui/notify.service';
import { PendingSavesService } from '../state/pending-saves.service';
import { safeReturnTo } from './browser-navigation';
import { IdentityService } from './identity.service';
import type { AccessRule } from './permissions';
import { SessionExpiryService } from './session-expiry.service';

/**
 * Route data understood by `permissionGuard` and the menu:
 * `permission` (platform, string or any-of array) and/or `projectPermission` (checked on the
 * `:projectId` route parameter when present, otherwise "in any project"). With both, either one
 * grants access.
 */
export type RouteAccessData = AccessRule;

/** Signed in, or redirected to `/login?returnTo=<attempted url>`. */
export const authGuard: CanActivateFn = (_route, state) => {
  const identity = inject(IdentityService);
  if (identity.isAuthenticated()) return true;
  return inject(Router).createUrlTree(['/login'], {
    queryParams: { returnTo: safeReturnTo(state.url) === '/' ? null : state.url },
  });
};

/** The login page is only for signed-out users; signed-in users go to `returnTo`. */
export const publicOnlyGuard: CanActivateFn = (route) => {
  const identity = inject(IdentityService);
  if (!identity.isAuthenticated()) return true;
  return inject(Router).parseUrl(safeReturnTo(route.queryParamMap.get('returnTo')));
};

/**
 * Checks `data.permission` / `data.projectPermission` of the route (and of its parents). Missing
 * permission shows `/forbidden` while keeping the attempted URL in the address bar.
 */
export const permissionGuard: CanActivateFn = (route, state) => {
  const identity = inject(IdentityService);
  const router = inject(Router);
  const { rule, projectId } = accessOf(route);
  if (identity.allows(rule, projectId)) return true;
  return new RedirectCommand(
    router.createUrlTree(['/forbidden'], { queryParams: { from: state.url } }),
    { skipLocationChange: true },
  );
};

/**
 * While the session is expired ("Stay on this page"), in-app navigation is refused and the notice
 * is shown again; the only way forward is to sign in again.
 */
export const sessionGuard: CanActivateChildFn = () => {
  const session = inject(SessionExpiryService);
  if (!session.expired()) return true;
  session.showExpired();
  return false;
};

export const SAVE_IN_PROGRESS_TITLE = $localize`:@@core.guard.saveInProgress:A save is still in progress`;

/** Refuses in-app navigation while a save is in flight. */
export const pendingSavesGuard: CanActivateChildFn = () => {
  if (!inject(PendingSavesService).hasPending()) return true;
  inject(NotifyService).warning(
    SAVE_IN_PROGRESS_TITLE,
    $localize`:@@core.guard.saveInProgressText:Wait until the save finishes before leaving this page.`,
  );
  return false;
};

/** Implemented by editors (designer, template editor, user/group/role editors). */
export interface HasUnsavedChanges {
  hasUnsavedChanges(): boolean;
}

/** Asks before leaving an editor with unsaved changes. */
export const unsavedChangesGuard: CanDeactivateFn<HasUnsavedChanges> = async (component) => {
  if (!component?.hasUnsavedChanges?.()) return true;
  return inject(ConfirmService).confirm({
    title: $localize`:@@core.guard.discard.title:Discard unsaved changes?`,
    content: $localize`:@@core.guard.discard.text:The changes you made on this page have not been saved and will be lost.`,
    okText: $localize`:@@core.guard.discard.ok:Discard changes`,
    danger: true,
  });
};

/** Merges the access rules of the route and its ancestors (closest wins) plus `:projectId`. */
export function accessOf(route: ActivatedRouteSnapshot): {
  rule: AccessRule;
  projectId: string | null;
} {
  const rule: AccessRule = {};
  let projectId: string | null = null;
  for (let r: ActivatedRouteSnapshot | null = route; r; r = r.parent) {
    const data = r.data as RouteAccessData;
    if (rule.permission === undefined && data.permission !== undefined) {
      rule.permission = data.permission;
    }
    if (rule.projectPermission === undefined && data.projectPermission !== undefined) {
      rule.projectPermission = data.projectPermission;
    }
    projectId ??= r.paramMap.get('projectId');
  }
  return { rule, projectId };
}
