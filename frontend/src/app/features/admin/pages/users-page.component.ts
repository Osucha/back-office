import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { AdminApi } from '../../../core/api/admin.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import {
  USER_STATUSES,
  type AuthMode,
  type Group,
  type Project,
  type Role,
  type UserDetail,
  type UserStatus,
} from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { ProjectRolesService } from '../../../core/state/project-roles.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { icu } from '../../../core/i18n/icu';
import { BreadcrumbService, SECTION_LABELS } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import {
  FilterBarComponent,
  type FilterChip,
  type FilterPreset,
} from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { absoluteTime } from '../../../core/ui/relay/format';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { AdminNavComponent } from '../components/admin-nav.component';
import { InviteUserPanelComponent } from '../components/invite-user-panel.component';
import { StateChipComponent } from '../components/state-chip.component';
import {
  SIGN_IN_LABELS,
  type SignInFilter,
  accountBadges,
  matchesSignIn,
  providerLabel,
  statusBadge,
} from '../data/local-accounts';

/** Users: search, status, group and sign-in filters in the URL, server paging, the invite panel. */
@Component({
  selector: 'app-users-page',
  imports: [
    RoleNamePipe,
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSelectModule,
    AdminNavComponent,
    EmptyComponent,
    FilterBarComponent,
    InviteUserPanelComponent,
    PageHeaderComponent,
    SkeletonComponent,
    StateChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.users"
        title="Users"
        i18n-title="@@admin.users.title"
        subtitle="People who can sign in; access comes from groups, platform roles and project memberships."
        i18n-subtitle="@@admin.users.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="load()"
          [nzLoading]="loading() && loaded()"
          aria-label="Refresh users"
          i18n-aria-label="@@admin.users.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
        @if (canManage()) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="invite.open('1')"
            i18n="@@admin.users.invite"
          >
            Invite user
          </button>
        }
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search e-mail or name"
        i18n-placeholder="@@admin.users.search"
        label="Filter users"
        i18n-label="@@admin.users.filterLabel"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [chips]="chips()"
        (chipRemoved)="removeChip($event)"
        (cleared)="f.clear()"
        [presets]="presets()"
        (presetSelected)="applyPreset($any($event))"
      >
        <nz-select
          style="width: 150px"
          nzAllowClear
          nzPlaceHolder="Any status"
          i18n-nzPlaceHolder="@@admin.users.anyStatus"
          aria-label="Filter by status"
          i18n-aria-label="@@admin.users.filterStatus"
          [ngModel]="f.values().status"
          (ngModelChange)="f.set({ status: $event })"
        >
          @for (s of statuses; track s) {
            <nz-option [nzValue]="s" [nzLabel]="statusLabel(s)" />
          }
        </nz-select>
        <nz-select
          style="width: 190px"
          nzAllowClear
          nzShowSearch
          nzPlaceHolder="Any group"
          i18n-nzPlaceHolder="@@admin.users.anyGroup"
          aria-label="Filter by group"
          i18n-aria-label="@@admin.users.filterGroup"
          [ngModel]="f.values().groupId"
          (ngModelChange)="f.set({ groupId: $event })"
        >
          @for (g of groups(); track g.id) {
            <nz-option [nzValue]="g.id" [nzLabel]="g.name ?? ''" />
          }
        </nz-select>
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="8"
          [columns]="6"
          label="Loading users"
          i18n-label="@@admin.users.loading"
        />
      } @else if (!rows().length) {
        <bo-empty
          [title]="f.active() ? text.noMatch : text.empty"
          [detail]="f.active() ? null : text.emptyDetail"
          [actionLabel]="f.active() ? text.clearFilters : canManage() ? text.invite : null"
          [primary]="!f.active()"
          (action)="f.active() ? f.clear() : invite.open('1')"
        />
      } @else {
        <nz-table
          #t
          class="users"
          [nzData]="rows()"
          [nzFrontPagination]="false"
          [nzTotal]="total()"
          [nzPageIndex]="page() + 1"
          [nzPageSize]="size()"
          [nzLoading]="loading() && loaded()"
          [nzShowSizeChanger]="true"
          [nzPageSizeOptions]="[20, 50, 100, 200]"
          [nzShowTotal]="totalTpl"
          (nzPageIndexChange)="setPage($event - 1)"
          (nzPageSizeChange)="setSize($event)"
          nzSize="middle"
        >
          <ng-template #totalTpl let-total>{{ totalLabel(total) }}</ng-template>
          <thead>
            <tr>
              <th i18n="@@admin.users.colUser">User</th>
              <th nzWidth="120px" i18n="@@admin.users.colStatus">Status</th>
              <th nzWidth="260px" i18n="@@admin.users.colSignIn">Sign-in</th>
              <th i18n="@@admin.users.colGroups">Groups and roles</th>
              <th nzWidth="90px" nzAlign="right" i18n="@@admin.users.colProjects">Projects</th>
              <th nzWidth="120px" nzAlign="right" i18n="@@admin.users.colLastSignIn">
                Last sign-in
              </th>
            </tr>
          </thead>
          <tbody>
            @for (u of t.data; track u.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                [attr.aria-label]="rowLabel(u)"
                (click)="open(u)"
                (keydown.enter)="open(u)"
              >
                <td>
                  <div class="who">
                    <span class="avatar" aria-hidden="true">{{ initials(u) }}</span>
                    <div class="names">
                      <span class="name"
                        >{{ u.displayName || u.email }}
                        @if (u.id === identity.user()?.id) {
                          <span class="you" i18n="@@admin.users.you">you</span>
                        }
                      </span>
                      <span class="email">{{ u.email }}</span>
                    </div>
                  </div>
                </td>
                <td>
                  <app-state-chip
                    size="sm"
                    [tone]="status(u.status).tone"
                    [label]="status(u.status).label"
                    [hint]="status(u.status).hint"
                  />
                </td>
                <td>
                  <div class="chips">
                    @for (b of badges(u); track b.id) {
                      <app-state-chip size="sm" [tone]="b.tone" [label]="b.label" [hint]="b.hint" />
                    }
                  </div>
                </td>
                <td>
                  <div class="chips">
                    @for (g of u.groups ?? []; track g.id) {
                      <span class="tag">{{ g.name }}</span>
                    }
                    @for (r of u.platformRoles ?? []; track r) {
                      <span class="tag role" [title]="r">{{ r | roleName }}</span>
                    }
                    @if (!u.groups?.length && !u.platformRoles?.length) {
                      <span class="muted">-</span>
                    }
                  </div>
                </td>
                <td nzAlign="right" class="num">{{ u.projectMemberships?.length ?? 0 }}</td>
                <td nzAlign="right" class="age" [attr.title]="abs(u.lastLoginAt)">
                  {{ u.lastLoginAt ? ago(u.lastLoginAt) : text.never }}
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>

    <app-invite-user-panel
      [open]="!!invite.value() && canManage()"
      [groups]="groups()"
      [platformRoles]="platformRoles()"
      [projectRoles]="projectRoles.roles()"
      [projects]="projects()"
      [methods]="methods()"
      [providerName]="provider()"
      [preset]="invitePreset()"
      (userInvited)="load()"
      (closed)="invite.close()"
    />
  `,
  styles: [
    `
      .who {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
      }
      .avatar {
        flex: none;
        width: 28px;
        height: 28px;
        border-radius: 50%;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11px;
        font-weight: 600;
      }
      .names {
        display: flex;
        flex-direction: column;
        min-width: 0;
        line-height: 1.35;
      }
      .name {
        font-weight: 500;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .email {
        font-size: 12px;
        color: var(--bo-ink-3);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .you {
        margin-left: 6px;
        font-size: 11px;
        font-weight: 500;
        color: var(--bo-accent);
      }
      .chips {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .tag {
        display: inline-flex;
        align-items: center;
        height: 20px;
        padding: 0 8px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        color: var(--bo-ink-2);
        font-size: 11.5px;
        white-space: nowrap;
      }
      .tag.role {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        font-family: var(--bo-font-mono);
        font-size: 11px;
      }
      .age {
        color: var(--bo-ink-2);
        font-size: 12.5px;
        white-space: nowrap;
      }
      tr.active > td {
        background: var(--bo-accent-soft) !important;
      }
      /* meta text keeps 4.5:1 on the selection tint */
      tr.active .email {
        color: var(--bo-ink-2);
      }
      tr:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class UsersPageComponent {
  private readonly api = inject(AdminApi);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly router = inject(Router);
  private readonly authConfig = inject(AuthConfigService);
  readonly identity = inject(IdentityService);
  readonly projectRoles = inject(ProjectRolesService);

  readonly statuses = USER_STATUSES;
  readonly text = {
    noMatch: $localize`:@@admin.users.noMatch:No users match these filters.`,
    empty: $localize`:@@admin.users.empty:No users yet.`,
    emptyDetail: $localize`:@@admin.users.emptyDetail:Invite the first person; you hand them a link to sign in.`,
    clearFilters: $localize`:@@admin.users.clearFilters:Clear filters`,
    invite: $localize`:@@admin.users.emptyInvite:Invite user`,
    never: $localize`:@@admin.users.never:never`,
  };
  readonly f = urlFilters({ q: 'string', status: 'string', groupId: 'string', signIn: 'string' });
  /** `?invite=1` opens the invite panel (command palette "Invite user"). */
  readonly invite = panelParam('invite');

  readonly users = signal<UserDetail[]>([]);
  readonly total = signal(0);
  readonly page = signal(0);
  readonly size = signal(this.identity.pageSize());
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly groups = signal<Group[]>([]);
  readonly platformRoles = signal<Role[]>([]);
  readonly projects = signal<Project[]>([]);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  readonly canManage = computed(() => this.identity.can('admin.users.manage'));
  /** `?invite=local` / `?invite=oidc` / `?invite=both` preselect the sign-in method. */
  readonly invitePreset = computed<AuthMode | null>(() => {
    const v = (this.invite.value() ?? '').toUpperCase();
    return v === 'LOCAL' || v === 'OIDC' || v === 'BOTH' ? v : null;
  });
  readonly methods = computed(() => this.authConfig.config()?.methods ?? null);
  readonly provider = computed(() => providerLabel(this.authConfig.config()?.providerName));
  readonly rows = computed(() => {
    const now = Date.now();
    return this.users().filter((u) => matchesSignIn(u, this.f.values().signIn, now));
  });
  private readonly serverFilters = computed(() => {
    const v = this.f.values();
    return JSON.stringify([v.q, v.status, v.groupId]);
  });
  readonly hidden = computed(() => this.users().length - this.rows().length);

  readonly chips = computed<FilterChip[]>(() => {
    const v = this.f.values();
    const out: FilterChip[] = [];
    if (v.status) {
      const status = this.statusLabel(v.status);
      out.push({
        id: 'status',
        label: $localize`:@@admin.users.chipStatus:Status: ${status}:status:`,
      });
    }
    if (v.groupId) {
      const group =
        this.groups().find((g) => g.id === v.groupId)?.name ??
        $localize`:@@admin.users.chipGroupSelected:selected`;
      out.push({
        id: 'groupId',
        label: $localize`:@@admin.users.chipGroup:Group: ${group}:group:`,
      });
    }
    if (v.signIn && v.signIn in SIGN_IN_LABELS) {
      const method = SIGN_IN_LABELS[v.signIn as SignInFilter];
      out.push({
        id: 'signIn',
        label: $localize`:@@admin.users.chipSignIn:Sign-in: ${method}:method:`,
      });
    }
    return out;
  });

  readonly presets = computed<FilterPreset[]>(() => {
    const v = this.f.values();
    return [
      {
        label: $localize`:@@admin.users.presetInvited:Invited`,
        value: { status: 'INVITED' },
        active: v.status === 'INVITED',
      },
      {
        label: $localize`:@@admin.users.presetLocal:Local accounts`,
        value: { signIn: 'local' },
        active: v.signIn === 'local',
      },
      {
        label: $localize`:@@admin.users.presetLocked:Locked`,
        value: { signIn: 'locked' },
        active: v.signIn === 'locked',
      },
      {
        label: $localize`:@@admin.users.presetDeactivated:Deactivated`,
        value: { status: 'DEACTIVATED' },
        active: v.status === 'DEACTIVATED',
      },
    ];
  });

  readonly nav = listNavigation({
    items: this.rows,
    open: (u) => this.open(u),
    group: $localize`:@@admin.users.shortcutGroup:Users`,
    when: () => !this.invite.value(),
  });

  constructor() {
    inject(BreadcrumbService).set([
      { label: SECTION_LABELS['admin'], link: '/admin' },
      { label: $localize`:@@admin.users.crumb:Users` },
    ]);
    this.authConfig.load().subscribe();
    this.api.groups().subscribe({ next: (g) => this.groups.set(g), error: () => undefined });
    if (this.canManage()) {
      this.api.roles('PLATFORM', { silent: true }).subscribe({
        next: (r) => this.platformRoles.set(r),
        error: () => undefined,
      });
      this.projectsApi
        .list()
        .subscribe({ next: (p) => this.projects.set(p), error: () => undefined });
      this.projectRoles.load();
    }
    effect(() => {
      // Server-side filters reload from the first page; the sign-in filter is client-side.
      this.serverFilters();
      untracked(() => {
        this.page.set(0);
        this.load();
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: 'f',
          description: $localize`:@@admin.users.shortcutSearch:Search users`,
          group: $localize`:@@admin.users.shortcutGroup:Users`,
          handler: () => this.bar()?.focus(),
        },
        {
          keys: 'i',
          description: $localize`:@@admin.users.shortcutInvite:Invite user`,
          group: $localize`:@@admin.users.shortcutGroup:Users`,
          when: () => this.canManage() && !this.invite.value(),
          handler: () => this.invite.open('1'),
        },
      ],
      inject(DestroyRef),
    );
  }

  status = statusBadge;
  ago = timeAgo;
  abs = absoluteTime;

  badges(u: UserDetail) {
    return accountBadges(u);
  }

  statusLabel(s: string): string {
    return statusBadge(s as UserStatus).label.replace(/^./, (c) => c.toUpperCase());
  }

  initials(u: UserDetail): string {
    const src = (u.displayName || u.email || '?').replace(/@.*/, '');
    const parts = src.split(/[\s._-]+/).filter(Boolean);
    return ((parts[0]?.[0] ?? '?') + (parts[1]?.[0] ?? '')).toUpperCase();
  }

  totalLabel(total: number): string {
    const users = icu(
      $localize`:@@admin.users.total:{count, plural, one {# user} other {# users}}`,
      { count: total },
    );
    const hidden = this.hidden();
    return hidden
      ? $localize`:@@admin.users.totalHidden:${users}:users: · ${hidden}:hidden: hidden by filters`
      : users;
  }

  rowLabel(u: UserDetail): string {
    const b = [statusBadge(u.status).label, ...accountBadges(u).map((x) => x.label)];
    return `${u.displayName || u.email}, ${u.email}, ${b.join(', ')}`;
  }

  open(u: UserDetail): void {
    if (u.id) void this.router.navigate(['/admin/users', u.id]);
  }

  removeChip(id: string): void {
    this.f.set({ [id]: null });
  }

  applyPreset(value: Record<string, string>): void {
    const [key, val] = Object.entries(value)[0];
    const cur = (this.f.values() as Record<string, string | null>)[key];
    this.f.set({ [key]: cur === val ? null : val });
  }

  setPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  setSize(size: number): void {
    this.size.set(size);
    this.page.set(0);
    this.load();
  }

  load(): void {
    const v = this.f.values();
    this.loading.set(true);
    this.api
      .users({
        q: v.q?.trim() || undefined,
        status: (v.status as UserStatus | null) ?? undefined,
        groupId: v.groupId ?? undefined,
        page: this.page(),
        size: this.size(),
      })
      .subscribe({
        next: (p) => {
          this.users.set(p.content ?? []);
          this.total.set(p.totalElements ?? p.content?.length ?? 0);
          this.loading.set(false);
          this.loaded.set(true);
        },
        error: () => {
          this.loading.set(false);
          this.loaded.set(true);
        },
      });
  }
}
