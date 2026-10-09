/**
 * Pure permission logic over `GET /me`. The backend is authoritative (every use case checks its own
 * permission); the UI uses this only to decide what to show, hide or disable.
 */
import type { Me } from '../api/types';

export interface ProjectAccess {
  projectId: string;
  projectKey: string;
  roles: string[];
  permissions: ReadonlySet<string>;
}

export interface PermissionView {
  platform: ReadonlySet<string>;
  /** Holder of `admin.projects.all`: every project permission on every project. */
  allProjects: boolean;
  projects: ReadonlyMap<string, ProjectAccess>;
}

export const EMPTY_VIEW: PermissionView = {
  platform: new Set(),
  allProjects: false,
  projects: new Map(),
};

/** Permissions that only machine tokens hold; `admin.projects.all` does not imply them. */
const TOKEN_ONLY = new Set(['ci.execute', 'ci.signal']);

export function buildPermissionView(me: Me | null | undefined): PermissionView {
  if (!me) return EMPTY_VIEW;
  const platform = new Set(me.platformPermissions ?? []);
  const projects = new Map<string, ProjectAccess>();
  for (const p of me.projects ?? []) {
    if (!p.projectId) continue;
    projects.set(p.projectId, {
      projectId: p.projectId,
      projectKey: p.projectKey ?? '',
      roles: p.roles ?? [],
      permissions: new Set(p.permissions ?? []),
    });
  }
  return {
    platform,
    allProjects: me.allProjects === true || platform.has('admin.projects.all'),
    projects,
  };
}

/** Platform permission check; an array means "any of". */
export function hasPermission(
  view: PermissionView,
  permission: string | readonly string[],
): boolean {
  const wanted = typeof permission === 'string' ? [permission] : permission;
  return wanted.some((p) => view.platform.has(p));
}

/**
 * Project permission check. Without a project id the question is "in at least one project"
 * (used for menu entries such as Costs that exist per project).
 */
export function hasProjectPermission(
  view: PermissionView,
  permission: string,
  projectId?: string | null,
): boolean {
  if (view.allProjects && !TOKEN_ONLY.has(permission)) return true;
  if (projectId) return view.projects.get(projectId)?.permissions.has(permission) ?? false;
  for (const access of view.projects.values()) {
    if (access.permissions.has(permission)) return true;
  }
  return false;
}

export function projectRolesOf(
  view: PermissionView,
  projectId: string | null | undefined,
): string[] {
  if (!projectId) return [];
  return view.projects.get(projectId)?.roles ?? [];
}

/** Ids of the projects on which the caller holds `permission` (not expanded for `allProjects`). */
export function projectIdsWith(view: PermissionView, permission: string): string[] {
  return [...view.projects.values()]
    .filter((a) => a.permissions.has(permission))
    .map((a) => a.projectId);
}

/** Any platform permission starting with `admin.` - shows the Administration menu. */
export function hasAnyAdminPermission(view: PermissionView): boolean {
  for (const p of view.platform) if (p.startsWith('admin.')) return true;
  return false;
}

/**
 * Route rule evaluation. `permission` (platform, any-of) and `projectPermission` are alternatives:
 * with both set either one grants access. The project permission is checked on `projectId` when the
 * route has one, otherwise on any project.
 */
export interface AccessRule {
  permission?: string | readonly string[];
  projectPermission?: string;
}

export function isAllowed(
  view: PermissionView,
  rule: AccessRule,
  projectId?: string | null,
): boolean {
  const hasPlatformRule = rule.permission !== undefined && rule.permission.length > 0;
  const hasProjectRule = !!rule.projectPermission;
  if (!hasPlatformRule && !hasProjectRule) return true;
  if (hasPlatformRule && hasPermission(view, rule.permission!)) return true;
  if (hasProjectRule && hasProjectPermission(view, rule.projectPermission!, projectId)) return true;
  return false;
}
