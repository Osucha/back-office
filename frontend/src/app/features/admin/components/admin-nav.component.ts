import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { IdentityService } from '../../../core/auth/identity.service';

interface AdminSection {
  path: string;
  label: string;
  permission: string | readonly string[];
}

const SECTIONS: readonly AdminSection[] = [
  {
    path: '/admin/users',
    label: $localize`:@@admin.nav.users:Users`,
    permission: 'admin.users.view',
  },
  {
    path: '/admin/groups',
    label: $localize`:@@admin.nav.groups:Groups`,
    permission: 'admin.users.view',
  },
  {
    path: '/admin/roles',
    label: $localize`:@@admin.nav.roles:Roles`,
    permission: ['admin.roles.manage', 'admin.users.view'],
  },
  {
    path: '/admin/grants',
    label: $localize`:@@admin.nav.grants:Grants`,
    permission: ['admin.roles.manage', 'admin.users.view'],
  },
  {
    path: '/admin/settings',
    label: $localize`:@@admin.nav.settings:Platform settings`,
    permission: 'admin.settings.manage',
  },
  {
    path: '/admin/connections',
    label: $localize`:@@admin.nav.connections:Connections`,
    permission: ['integrations.view', 'admin.connections.manage'],
  },
  {
    path: '/admin/fleet',
    label: $localize`:@@admin.nav.fleet:Runner fleet`,
    permission: ['fleet.view', 'fleet.manage'],
  },
  {
    path: '/admin/operations',
    label: $localize`:@@admin.nav.operations:Operations`,
    permission: 'admin.settings.manage',
  },
  {
    path: '/admin/audit',
    label: $localize`:@@admin.nav.audit:Audit log`,
    permission: 'admin.audit.view',
  },
];

/** The administration sections as a quiet tab strip above the page header. */
@Component({
  selector: 'app-admin-nav',
  imports: [RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav aria-label="Administration" i18n-aria-label="@@admin.nav.label">
      @for (s of sections(); track s.path) {
        <a
          [routerLink]="s.path"
          routerLinkActive="on"
          #rla="routerLinkActive"
          [attr.aria-current]="rla.isActive ? 'page' : null"
          >{{ s.label }}</a
        >
      }
    </nav>
  `,
  styles: [
    `
      :host {
        display: block;
        margin: -8px 0 16px;
      }
      nav {
        display: flex;
        gap: 2px;
        flex-wrap: wrap;
        border-bottom: 1px solid var(--bo-line);
      }
      a {
        position: relative;
        padding: 6px 10px 8px;
        font-size: 13px;
        color: var(--bo-ink-2);
        text-decoration: none;
        border-radius: var(--bo-radius-control) var(--bo-radius-control) 0 0;
      }
      a:hover {
        color: var(--bo-ink);
        background: var(--bo-surface-3);
      }
      a.on {
        color: var(--bo-ink);
        font-weight: 600;
      }
      a.on::after {
        content: '';
        position: absolute;
        left: 8px;
        right: 8px;
        bottom: -1px;
        height: 2px;
        border-radius: 2px;
        background: var(--bo-ink);
      }
      a:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class AdminNavComponent {
  private readonly identity = inject(IdentityService);
  readonly sections = computed(() => {
    this.identity.me();
    return SECTIONS.filter((s) => this.identity.can(s.permission));
  });
}
