import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { AdminApi } from '../../../core/api/admin.service';
import type { Group } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { icu } from '../../../core/i18n/icu';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterPreset } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { fuzzyFilter } from '../../../core/ui/relay/fuzzy';
import { listNavigation } from '../../../core/ui/relay/list-nav';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { AdminNavComponent } from '../components/admin-nav.component';

/** Groups: members, platform roles and the synchronised identity-provider group. */
@Component({
  selector: 'app-groups-page',
  imports: [
    RoleNamePipe,
    NzTableModule,
    NzButtonModule,
    NzIconModule,
    NzTooltipModule,
    AdminNavComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.groups"
        title="Groups"
        i18n-title="@@admin.groups.title"
        subtitle="Groups carry platform roles and project memberships for all their members."
        i18n-subtitle="@@admin.groups.subtitle"
      >
        <button
          boHeaderActions
          nz-button
          type="button"
          (click)="load()"
          [nzLoading]="loading() && loaded()"
          aria-label="Refresh groups"
          i18n-aria-label="@@admin.groups.refresh"
        >
          <nz-icon nzType="reload" />
        </button>
        @if (canManage()) {
          <button
            boHeaderActions
            nz-button
            nzType="primary"
            type="button"
            (click)="create()"
            i18n="@@admin.groups.create"
          >
            Create group
          </button>
        }
      </bo-page-header>

      <bo-filter-bar
        #bar
        placeholder="Search groups"
        i18n-placeholder="@@admin.groups.search"
        label="Filter groups"
        i18n-label="@@admin.groups.filter"
        [query]="f.values().q ?? ''"
        (queryChange)="f.set({ q: $event })"
        [presets]="presets()"
        (presetSelected)="f.set({ synced: f.values().synced ? null : '1' })"
        (cleared)="f.clear()"
      />

      @if (loading() && !loaded()) {
        <bo-skeleton
          variant="table"
          [rows]="6"
          [columns]="4"
          label="Loading groups"
          i18n-label="@@admin.groups.loading"
        />
      } @else if (!rows().length) {
        <bo-empty
          [title]="f.active() ? text.noMatch : text.empty"
          [detail]="f.active() ? null : text.emptyDetail"
          [actionLabel]="f.active() ? text.clearFilters : canManage() ? text.create : null"
          [primary]="!f.active()"
          (action)="f.active() ? f.clear() : create()"
        />
      } @else {
        <nz-table
          #t
          [nzData]="sorted()"
          [nzPageSize]="identity.pageSize()"
          [nzHideOnSinglePage]="true"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th nzShowSort [boSort]="sort" boSortKey="name" i18n="@@admin.groups.col.group">
                Group
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="members"
                nzWidth="116px"
                nzAlign="right"
                i18n="@@admin.groups.col.members"
              >
                Members
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="roles"
                i18n="@@admin.groups.col.platformRoles"
              >
                Platform roles
              </th>
              <th
                nzShowSort
                [boSort]="sort"
                boSortKey="external"
                nzWidth="236px"
                i18n="@@admin.groups.col.externalGroup"
              >
                Identity provider group
              </th>
              <th nzWidth="64px">
                <span class="sr-only" i18n="@@admin.groups.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (g of t.data; track g.id; let i = $index) {
              <tr
                class="clickable-row"
                [class.active]="nav.cursor() === i"
                [attr.data-nav-index]="i"
                tabindex="0"
                [attr.aria-label]="rowLabel(g)"
                (click)="open(g)"
                (keydown.enter)="open(g)"
              >
                <td>
                  <div class="name">{{ g.name }}</div>
                  @if (g.description) {
                    <div class="desc">{{ g.description }}</div>
                  }
                </td>
                <td nzAlign="right" class="num">{{ members(g) }}</td>
                <td>
                  <div class="chips">
                    @for (r of g.platformRoles ?? []; track r) {
                      <span class="tag role" [title]="r">{{ r | roleName }}</span>
                    } @empty {
                      <span class="muted">-</span>
                    }
                  </div>
                </td>
                <td>
                  @if (g.externalGroup) {
                    <span class="mono ink-2">{{ g.externalGroup }}</span>
                  } @else {
                    <span class="muted" i18n="@@admin.groups.notSynced">not synchronised</span>
                  }
                </td>
                <td class="row-actions" (click)="$event.stopPropagation()">
                  @if (canManage()) {
                    <button
                      nz-button
                      nzType="text"
                      nzSize="small"
                      type="button"
                      nz-tooltip
                      nzTooltipTitle="Delete group"
                      i18n-nzTooltipTitle="@@admin.groups.delete.tooltip"
                      (click)="remove(g)"
                      [attr.aria-label]="deleteLabel(g)"
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
      .name {
        font-weight: 500;
      }
      .desc {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .chips {
        display: flex;
        gap: 4px;
        flex-wrap: wrap;
      }
      .tag.role {
        display: inline-flex;
        align-items: center;
        height: 20px;
        padding: 0 8px;
        border-radius: 999px;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        color: var(--bo-ink-2);
        font-family: var(--bo-font-mono);
        font-size: 11px;
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
      tr.active .muted {
        color: var(--bo-ink-2);
      }
      tr:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class GroupsPageComponent {
  private readonly api = inject(AdminApi);
  private readonly confirm = inject(ConfirmService);
  private readonly message = inject(NzMessageService);
  private readonly router = inject(Router);
  readonly identity = inject(IdentityService);

  readonly f = urlFilters({ q: 'string', synced: 'string' });
  readonly groups = signal<Group[]>([]);
  readonly loading = signal(false);
  readonly loaded = signal(false);
  private readonly bar = viewChild<FilterBarComponent>('bar');

  readonly canManage = computed(() => this.identity.can('admin.users.manage'));
  readonly rows = computed(() => {
    const v = this.f.values();
    const list = v.synced ? this.groups().filter((g) => !!g.externalGroup) : this.groups();
    const q = v.q?.trim();
    return q
      ? fuzzyFilter(list, q, (g) => [g.name, g.description, g.externalGroup], 1000).map(
          (x) => x.item,
        )
      : [...list].sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''));
  });
  /** Column sorting (URL `?sort=`); the same order for the table and keyboard navigation. */
  readonly sort = tableSort<Group>({
    name: (g) => g.name,
    members: (g) => this.members(g),
    roles: (g) => (g.platformRoles ?? []).join(', '),
    external: (g) => g.externalGroup,
  });
  readonly sorted = computed(() => this.sort.apply(this.rows()));
  readonly text = {
    noMatch: $localize`:@@admin.groups.empty.noMatch:No groups match these filters.`,
    empty: $localize`:@@admin.groups.empty.none:No groups yet.`,
    emptyDetail: $localize`:@@admin.groups.empty.detail:Create one to give several people the same roles and projects.`,
    clearFilters: $localize`:@@admin.groups.empty.clearFilters:Clear filters`,
    create: $localize`:@@admin.groups.empty.create:Create group`,
  };
  readonly presets = computed<FilterPreset[]>(() => [
    {
      label: $localize`:@@admin.groups.preset.synced:Synchronised`,
      value: 'synced',
      active: !!this.f.values().synced,
    },
  ]);
  readonly nav = listNavigation({
    items: this.sorted,
    open: (g) => this.open(g),
    group: $localize`:@@admin.groups.shortcutGroup:Groups`,
  });

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@admin.groups.crumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@admin.groups.crumb.groups:Groups` },
    ]);
    this.load();
    inject(ShortcutsService).register(
      {
        keys: 'f',
        description: $localize`:@@admin.groups.shortcut.search:Search groups`,
        group: $localize`:@@admin.groups.shortcutGroup:Groups`,
        handler: () => this.bar()?.focus(),
      },
      inject(DestroyRef),
    );
  }

  members(g: Group): number {
    return g.memberCount ?? g.memberIds?.length ?? 0;
  }

  rowLabel(g: Group): string {
    const count = icu(
      $localize`:@@admin.groups.rowLabel.members:{count, plural, one {# member} other {# members}}`,
      { count: this.members(g) },
    );
    return $localize`:@@admin.groups.rowLabel:${g.name}:name:, ${count}:members:`;
  }

  deleteLabel(g: Group): string {
    return $localize`:@@admin.groups.delete.ariaLabel:Delete group ${g.name}:name:`;
  }

  open(g: Group): void {
    if (g.id) void this.router.navigate(['/admin/groups', g.id]);
  }

  create(): void {
    void this.router.navigate(['/admin/groups/new']);
  }

  load(): void {
    this.loading.set(true);
    this.api.groups().subscribe({
      next: (g) => {
        this.groups.set(g);
        this.loading.set(false);
        this.loaded.set(true);
      },
      error: () => {
        this.loading.set(false);
        this.loaded.set(true);
      },
    });
  }

  async remove(g: Group): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@admin.groups.delete.title:Delete group "${g.name}:name:"?`,
      icu(
        $localize`:@@admin.groups.delete.text:{count, plural, one {Its # member loses the roles and project memberships they had through this group.} other {Its # members lose the roles and project memberships they had through this group.}}`,
        { count: this.members(g) },
      ),
      $localize`:@@admin.groups.delete.ok:Delete group`,
    );
    if (!ok || !g.id) return;
    this.api.deleteGroup(g.id).subscribe(() => {
      this.message.success($localize`:@@admin.groups.deleted:Group deleted`);
      this.load();
    });
  }
}
