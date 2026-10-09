import { Injectable, inject, signal } from '@angular/core';
import { AdminApi } from '../api/admin.service';
import { BUILT_IN_PROJECT_ROLES, type Role } from '../api/types';
import { roleDescription, roleName } from '../i18n/catalog-labels';

export interface ProjectRoleOption {
  key: string;
  name: string;
  description?: string | null;
}

/** The built-in project roles, used when the role catalogue is not readable. */
export function fallbackProjectRoles(): ProjectRoleOption[] {
  return BUILT_IN_PROJECT_ROLES.map((key) => ({ key, name: roleName(key) }));
}

/**
 * Project roles for selects (members tab, invitations, gate approvers). `/admin/roles?scope=PROJECT`
 * needs an admin permission; for everyone else (403) the built-in keys are used.
 */
@Injectable({ providedIn: 'root' })
export class ProjectRolesService {
  private readonly api = inject(AdminApi);
  private loaded = false;

  /** Names and descriptions are display texts (translated for unchanged built-in roles). */
  readonly roles = signal<ProjectRoleOption[]>(fallbackProjectRoles());
  /** True when the list came from the server (custom roles included). */
  readonly fromServer = signal(false);

  load(): void {
    if (this.loaded) return;
    this.loaded = true;
    this.api.roles('PROJECT', { silent: true }).subscribe({
      next: (list: Role[]) => {
        const roles = list
          .filter((r) => !!r.key)
          .map((r) => ({
            key: r.key!,
            name: roleName(r.key!, r.name),
            description: roleDescription(r.key!, r.description),
          }));
        if (roles.length) {
          this.roles.set(roles);
          this.fromServer.set(true);
        }
      },
      error: () => {
        this.roles.set(fallbackProjectRoles());
        this.fromServer.set(false);
      },
    });
  }

  /** Forget the cache (after an admin edited roles). */
  invalidate(): void {
    this.loaded = false;
  }
}
