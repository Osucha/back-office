import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SKIP_ERROR_TOAST, params, probe, url } from './api-base';
import type {
  AuditPage,
  AuditQuery,
  AuthMode,
  EffectivePermissions,
  PasswordResetLink,
  GrantLevel,
  GrantSet,
  Group,
  GroupInput,
  GroupRef,
  PermissionDefinition,
  PermissionScope,
  PlatformSettings,
  Role,
  RoleInput,
  SessionSummary,
  UserDetail,
  UserInvite,
  UserPage,
  UserQuery,
  UserRef,
  UserUpdate,
} from './types';

/** `admin` and `audit` tags plus the picker lookups of the `members` tag. */
@Injectable({ providedIn: 'root' })
export class AdminApi {
  private readonly http = inject(HttpClient);

  // ---- users

  users(query: UserQuery = {}): Observable<UserPage> {
    return this.http.get<UserPage>(url('admin', 'users'), { params: params({ ...query }) });
  }

  user(userId: string): Observable<UserDetail> {
    return this.http.get<UserDetail>(url('admin', 'users', userId));
  }

  /** Field errors (400/409) are mapped onto the invite form by the caller. */
  invite(input: UserInvite): Observable<UserDetail> {
    return this.http.post<UserDetail>(url('admin', 'users'), input, quiet());
  }

  updateUser(userId: string, input: UserUpdate): Observable<UserDetail> {
    return this.http.put<UserDetail>(url('admin', 'users', userId), input, quiet());
  }

  activate(userId: string): Observable<UserDetail> {
    return this.http.post<UserDetail>(url('admin', 'users', userId, 'activate'), {});
  }

  deactivate(userId: string): Observable<UserDetail> {
    return this.http.post<UserDetail>(url('admin', 'users', userId, 'deactivate'), {});
  }

  effectivePermissions(userId: string): Observable<EffectivePermissions> {
    return this.http.get<EffectivePermissions>(
      url('admin', 'users', userId, 'effective-permissions'),
    );
  }

  sessions(userId: string): Observable<SessionSummary[]> {
    return this.http.get<SessionSummary[]>(url('admin', 'users', userId, 'sessions'));
  }

  terminateSessions(userId: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'users', userId, 'sessions'));
  }

  // ---- local accounts (ADR 0021)

  /** Switching to LOCAL / BOTH without a password returns `setPasswordUrl` on the user. */
  setAuthMode(userId: string, authMode: AuthMode): Observable<UserDetail> {
    return this.http.put<UserDetail>(url('admin', 'users', userId, 'auth'), { authMode }, quiet());
  }

  /** One-time set-password link; the current password stops working and sessions end. */
  issuePasswordReset(userId: string): Observable<PasswordResetLink> {
    return this.http.post<PasswordResetLink>(url('admin', 'users', userId, 'password-reset'), {});
  }

  /** Removes TOTP and recovery codes (lost device). */
  resetMfa(userId: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'users', userId, 'mfa'));
  }

  unlock(userId: string): Observable<UserDetail> {
    return this.http.post<UserDetail>(url('admin', 'users', userId, 'unlock'), {});
  }

  // ---- groups

  groups(): Observable<Group[]> {
    return this.http.get<Group[]>(url('admin', 'groups'));
  }

  group(groupId: string): Observable<Group> {
    return this.http.get<Group>(url('admin', 'groups', groupId));
  }

  createGroup(input: GroupInput): Observable<Group> {
    return this.http.post<Group>(url('admin', 'groups'), input, quiet());
  }

  updateGroup(groupId: string, input: GroupInput): Observable<Group> {
    return this.http.put<Group>(url('admin', 'groups', groupId), input, quiet());
  }

  deleteGroup(groupId: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'groups', groupId));
  }

  // ---- roles, permissions, grants

  roles(scope?: PermissionScope, options: { silent?: boolean } = {}): Observable<Role[]> {
    return this.http.get<Role[]>(url('admin', 'roles'), {
      params: params({ scope }),
      // Background lookup: a 403 (non-admin) falls back to built-in roles without a notification.
      ...(options.silent ? probe() : {}),
    });
  }

  createRole(input: RoleInput): Observable<Role> {
    return this.http.post<Role>(url('admin', 'roles'), input, quiet());
  }

  updateRole(roleKey: string, input: RoleInput): Observable<Role> {
    return this.http.put<Role>(url('admin', 'roles', roleKey), input, quiet());
  }

  deleteRole(roleKey: string): Observable<void> {
    return this.http.delete<void>(url('admin', 'roles', roleKey));
  }

  permissions(): Observable<PermissionDefinition[]> {
    return this.http.get<PermissionDefinition[]>(url('admin', 'permissions'));
  }

  grants(level?: GrantLevel, targetId?: string | null): Observable<GrantSet[]> {
    return this.http.get<GrantSet[]>(url('admin', 'grants'), {
      params: params({ level, targetId }),
    });
  }

  replaceGrants(set: GrantSet): Observable<GrantSet> {
    return this.http.put<GrantSet>(url('admin', 'grants'), set);
  }

  // ---- platform settings

  settings(): Observable<PlatformSettings> {
    return this.http.get<PlatformSettings>(url('admin', 'settings'));
  }

  updateSettings(input: PlatformSettings): Observable<PlatformSettings> {
    return this.http.put<PlatformSettings>(url('admin', 'settings'), input, quiet());
  }

  // ---- audit

  audit(query: AuditQuery): Observable<AuditPage> {
    return this.http.get<AuditPage>(url('admin', 'audit'), { params: params({ ...query }) });
  }

  // ---- lookups for pickers (members tag)

  lookupUsers(q: string): Observable<UserRef[]> {
    // Pickers report errors inline (403 for callers without member/user management).
    return this.http.get<UserRef[]>(url('users', 'lookup'), { params: params({ q }), ...probe() });
  }

  lookupGroups(q = ''): Observable<GroupRef[]> {
    return this.http.get<GroupRef[]>(url('groups', 'lookup'), {
      params: params({ q }),
      ...probe(),
    });
  }
}

/** Requests whose errors are shown by the form (field errors) instead of the global handler. */
function quiet(): { context: HttpContext } {
  return { context: new HttpContext().set(SKIP_ERROR_TOAST, true) };
}
