import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSegmentedModule } from 'ng-zorro-antd/segmented';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { AdminApi } from '../../../core/api/admin.service';
import type { PermissionScope, Role } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { roleDescription, roleName } from '../../../core/i18n/catalog-labels';
import { RoleDescriptionPipe, RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { ProjectRolesService } from '../../../core/state/project-roles.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { fuzzyFilter } from '../../../core/ui/relay/fuzzy';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { AdminNavComponent } from '../components/admin-nav.component';

/** Roles per scope: named permission bundles; built-ins are editable but not deletable. */
@Component({
  selector: 'app-roles-page',
  imports: [
    FormsModule,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzSegmentedModule,
    NzTooltipModule,
    AdminNavComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
    RoleNamePipe,
    RoleDescriptionPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.roles"
        title="Roles"
        i18n-title="@@admin.roles.title"
        subtitle="Named bundles of permissions: platform roles are granted to everyone, groups or users; project roles through memberships."
        i18n-subtitle="@@admin.roles.subtitle"
      >
        @if (canManage()) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="create()"
            i18n="@@admin.roles.create"
          >
            Create role
          </button>
        }
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search key, name or permission"
        i18n-placeholder="@@admin.roles.search"
        label="Filter roles"
        i18n-label="@@admin.roles.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        (cleared)="f.clear()"
      >
        <nz-segmented
          [nzOptions]="scopes"
          [ngModel]="scope()"
          (ngModelChange)="f.set({ scope: $event === 'PLATFORM' ? null : $event })"
          aria-label="Role scope"
          i18n-aria-label="@@admin.roles.scopeLabel"
        />
      </bo-filter-bar>

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="6"
          [columns]="4"
          label="Loading roles"
          i18n-label="@@admin.roles.loading"
        />
      } @else if (!visible().length) {
        <bo-empty
          [title]="f.values().q ? text.noMatch : text.empty"
          [actionLabel]="f.values().q ? text.clearSearch : canManage() ? text.create : null"
          [primary]="!f.values().q"
          (action)="f.values().q ? f.set({ q: null }) : create()"
        />
      } @else {
        <nz-table #t [nzData]="sorted()" [nzShowPagination]="false" nzSize="middle">
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="key" nzWidth="240px" i18n="@@admin.roles.col.key">Key</th>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@admin.roles.col.name">Name</th>
              <th nzShowSort [boSort]="sort" boSortKey="permissions" nzWidth="136px" nzAlign="right" i18n="@@admin.roles.col.permissions">
                Permissions
              </th>
              <th nzWidth="64px">
                <span class="sr-only" i18n="@@admin.roles.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (r of t.data; track r.key; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                [attr.aria-label]="rowLabel(r)"
                (click)="open(r)"
                (keydown.enter)="open(r)"
              >
                <td>
                  <span class="mono key">{{ r.key }}</span>
                  @if (r.builtIn) {
                    <span
                      class="builtin"
                      title="Built-in roles can be edited but not deleted"
                      i18n-title="@@admin.roles.builtInHint"
                      ><nz-icon nzType="lock" />
                      <span i18n="@@admin.roles.builtIn">built-in</span></span
                    >
                  }
                </td>
                <td>
                  <div>{{ r.key | roleName: r.name }}</div>
                  @if (r.description) {
                    <div class="desc">{{ r.key | roleDescription: r.description }}</div>
                  }
                </td>
                <td nzAlign="right" class="num">{{ r.permissions?.length ?? 0 }}</td>
                <td class="row-actions" (click)="$event.stopPropagation()">
                  @if (canManage() && !r.builtIn) {
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      nz-tooltip
                      nzTooltipTitle="Delete role"
                      i18n-nzTooltipTitle="@@admin.roles.delete.tooltip"
                      (click)="remove(r)"
                      [attr.aria-label]="deleteLabel(r)"
                    >
                      <nz-icon nzType="delete" />
                    </button>
                  }
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </div>
  `,
  styles: [
    `
      .key {
        font-weight: 500;
      }
      .builtin {
        margin-left: 8px;
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .desc {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .row-actions button {
        opacity: 0;
        transition: opacity var(--bo-dur-fast);
      }
      tr:hover .row-actions button,
      tr.active .row-actions button,
      tr:focus-within .row-actions button {
        opacity: 1;
      }
      tr.active > td {
        background: var(--bo-accent-soft) !important;
      }
      /* meta text keeps 4.5:1 on the selection tint */
      tr.active .desc,
      tr.active .builtin {
        color: var(--bo-ink-2);
      }
      tr:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class RolesPageComponent {
  private readonly api = inject(AdminApi);
  private readonly confirm = inject(ConfirmService);
  private readonly message = inject(NzMessageService);
  private readonly projectRoles = inject(ProjectRolesService);
  private readonly router = inject(Router);
  private readonly identity = inject(IdentityService);

  readonly scopes = [
    { label: $localize`:@@admin.roles.scope.platform:Platform`, value: 'PLATFORM' },
    { label: $localize`:@@admin.roles.scope.project:Project`, value: 'PROJECT' },
  ];
  readonly text = {
    noMatch: $localize`:@@admin.roles.empty.noMatch:No roles match this search.`,
    empty: $localize`:@@admin.roles.empty.none:No roles in this scope yet.`,
    clearSearch: $localize`:@@admin.roles.empty.clearSearch:Clear search`,
    create: $localize`:@@admin.roles.empty.create:Create role`,
  };
  readonly f = urlFilters({ q: 'string', scope: 'string' });
  readonly scope = computed<PermissionScope>(() =>
    this.f.values().scope === 'PROJECT' ? 'PROJECT' : 'PLATFORM',
  );
  readonly roles = signal<Role[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  readonly canManage = computed(() => this.identity.can('admin.roles.manage'));
  readonly visible = computed(() => {
    const list = this.roles().filter((r) => r.scope === this.scope());
    const q = this.f.values().q?.trim();
    return q
      ? fuzzyFilter(
          list,
          q,
          (r) => [
            r.key,
            r.name,
            r.key ? roleName(r.key, r.name) : '',
            r.description,
            r.key ? roleDescription(r.key, r.description) : '',
            ...(r.permissions ?? []),
          ],
          1000,
        ).map((x) => x.item)
      : list;
  });
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<Role>({
    key: (r) => r.key,
    name: (r) => (r.key ? roleName(r.key, r.name) : r.name),
    permissions: (r) => r.permissions?.length ?? 0,
  });
  readonly sorted = computed(() => this.sort.apply(this.visible()));
  readonly nav = listNavigation({
    items: this.sorted,
    open: (r) => this.open(r),
    group: $localize`:@@admin.roles.shortcutGroup:Roles`,
  });

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@admin.roles.crumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@admin.roles.crumb.roles:Roles` },
    ]);
    this.load();
    inject(ShortcutsService).register(
      {
        keys: 'f',
        description: $localize`:@@admin.roles.shortcut.search:Search roles`,
        group: $localize`:@@admin.roles.shortcutGroup:Roles`,
        handler: () => this.bar()?.focus(),
      },
      inject(DestroyRef),
    );
  }

  rowLabel(r: Role): string {
    const name = r.key ? roleName(r.key, r.name) : r.name || '';
    return r.builtIn
      ? $localize`:@@admin.roles.rowLabelBuiltIn:${r.key}:key:, ${name}:name:, built-in`
      : $localize`:@@admin.roles.rowLabel:${r.key}:key:, ${name}:name:`;
  }

  deleteLabel(r: Role): string {
    return $localize`:@@admin.roles.delete.ariaLabel:Delete role ${r.key}:key:`;
  }

  open(r: Role): void {
    if (r.key) void this.router.navigate(['/admin/roles', r.key]);
  }

  create(): void {
    void this.router.navigate(['/admin/roles/new'], { queryParams: { scope: this.scope() } });
  }

  load(): void {
    this.loading.set(true);
    this.api.roles().subscribe({
      next: (r) => {
        this.roles.set(r);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  async remove(r: Role): Promise<void> {
    if (r.builtIn || !r.key) return;
    const ok = await this.confirm.danger(
      $localize`:@@admin.roles.delete.title:Delete role "${r.key}:key:"?`,
      $localize`:@@admin.roles.delete.text:Grants and memberships that use this role stop granting its permissions immediately.`,
      $localize`:@@admin.roles.delete.ok:Delete role`,
    );
    if (!ok) return;
    this.api.deleteRole(r.key).subscribe(() => {
      this.message.success($localize`:@@admin.roles.deleted:Role deleted`);
      this.projectRoles.invalidate();
      this.load();
    });
  }
}
