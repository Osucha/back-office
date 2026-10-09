import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { AdminApi } from '../../../core/api/admin.service';
import type {
  GrantLevel,
  GrantSet,
  Group,
  PermissionDefinition,
  Role,
} from '../../../core/api/types';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { IdentityService } from '../../../core/auth/identity.service';
import { permissionDescription, roleOptions } from '../../../core/i18n/catalog-labels';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { PrincipalPickerComponent } from '../../../core/ui/principal-picker.component';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { FilterBarComponent, type FilterPreset } from '../../../core/ui/relay/filter-bar.component';
import { urlFilters } from '../../../core/ui/relay/filter-state';
import { fuzzyFilter } from '../../../core/ui/relay/fuzzy';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { AdminNavComponent } from '../components/admin-nav.component';
import { EDITOR_STYLES } from '../components/editor-styles';
import { EffectToggleComponent } from '../components/effect-toggle.component';
import {
  OVERRIDE_OPTIONS,
  type OverrideState,
  overrideOf,
  pickGrantSet,
  setOverride,
  toGrantSet,
  toOverrides,
} from '../data/grants-model';
import { matrixFor } from '../data/permission-matrix';

/**
 * Platform grants of one level: SYSTEM (everyone), a group or a user. Roles assigned at the level
 * plus per-permission ALLOW/DENY overrides; "(inherit)" means no grant on this level.
 */
@Component({
  selector: 'app-grants-page',
  imports: [
    FormsModule,
    NzRadioModule,
    NzSelectModule,
    NzTableModule,
    NzButtonModule,
    PrincipalPickerComponent,
    ActionBarComponent,
    AdminNavComponent,
    EffectToggleComponent,
    EmptyComponent,
    FilterBarComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.grants"
        title="Grants"
        i18n-title="@@admin.grants.title"
        subtitle="Closest level wins: a user over their groups over the system; within groups deny wins; no grant means denied."
        i18n-subtitle="@@admin.grants.subtitle"
      />

      <div class="editor">
        <section class="section" aria-labelledby="gr-s-target">
          <div class="intro">
            <h2 id="gr-s-target" i18n="@@admin.grants.level.title">Level</h2>
            <p i18n="@@admin.grants.level.text">
              System grants apply to everyone; group and user grants refine them.
            </p>
          </div>
          <div class="fields target">
            <nz-radio-group
              [ngModel]="level()"
              (ngModelChange)="setLevel($event)"
              aria-label="Grant level"
              i18n-aria-label="@@admin.grants.level.label"
            >
              <label nz-radio-button nzValue="SYSTEM" i18n="@@admin.grants.level.system"
                >System (everyone)</label
              >
              <label nz-radio-button nzValue="GROUP" i18n="@@admin.grants.level.group">Group</label>
              <label nz-radio-button nzValue="USER" i18n="@@admin.grants.level.user">User</label>
            </nz-radio-group>
            @if (level() === 'GROUP') {
              <nz-select
                style="width: 260px"
                nzShowSearch
                nzPlaceHolder="Choose a group"
                i18n-nzPlaceHolder="@@admin.grants.chooseGroup"
                aria-label="Group"
                i18n-aria-label="@@admin.grants.groupLabel"
                [ngModel]="targetId()"
                (ngModelChange)="setTarget($event)"
              >
                @for (g of groups(); track g.id) {
                  <nz-option [nzValue]="g.id" [nzLabel]="g.name ?? ''" />
                }
              </nz-select>
            }
            @if (level() === 'USER') {
              <div style="width: 320px">
                <app-principal-picker
                  kind="user"
                  ariaLabel="User"
                  i18n-ariaLabel="@@admin.grants.userLabel"
                  placeholder="Search a user"
                  i18n-placeholder="@@admin.grants.searchUser"
                  [value]="targetId()"
                  (valueChange)="setTarget($any($event))"
                />
              </div>
            }
          </div>
        </section>

        @if (level() !== 'SYSTEM' && !targetId()) {
          <section class="section wide">
            <bo-empty
              tone="quiet"
              [title]="level() === 'GROUP' ? text.chooseGroupFirst : text.chooseUserFirst"
            />
          </section>
        } @else {
          <section class="section" aria-labelledby="gr-s-roles">
            <div class="intro">
              <h2 id="gr-s-roles" i18n="@@admin.grants.roles.title">Roles at this level</h2>
              <p i18n="@@admin.grants.roles.text">
                A role allows all its permissions at this level.
              </p>
            </div>
            <div class="fields">
              <nz-select
                nzMode="multiple"
                style="width: 100%"
                nzPlaceHolder="No roles"
                i18n-nzPlaceHolder="@@admin.grants.roles.none"
                aria-label="Roles at this level"
                i18n-aria-label="@@admin.grants.roles.label"
                [nzDisabled]="!canManage()"
                [ngModel]="roles()"
                (ngModelChange)="roles.set($event)"
              >
                @for (r of roleOptions(); track r.key) {
                  <nz-option [nzValue]="r.key" [nzLabel]="r.label" />
                }
              </nz-select>
            </div>
          </section>

          <section class="section wide" aria-labelledby="gr-s-over">
            <h2 id="gr-s-over">
              <span i18n="@@admin.grants.overrides.title">Overrides</span>
              <span class="count" i18n="@@admin.grants.overrides.count"
                >{{ overrideCount() }} set at this level</span
              >
            </h2>
            <bo-filter-bar
              placeholder="Search permissions"
              i18n-placeholder="@@admin.grants.search"
              label="Filter permissions"
              i18n-label="@@admin.grants.filter"
              [query]="f.values().q ?? ''"
              (queryChange)="f.set({ q: $event })"
              [presets]="presets()"
              (presetSelected)="f.set({ only: f.values().only ? null : '1' })"
              (cleared)="f.clear()"
            />
            @if (loading()) {
              <bo-skeleton
                variant="table"
                [rows]="8"
                [columns]="3"
                label="Loading grants"
                i18n-label="@@admin.grants.loading"
              />
            } @else if (!visibleRows().length) {
              <bo-empty tone="quiet" [title]="f.active() ? text.noMatch : text.emptyCatalogue" />
            } @else {
              <nz-table
                [nzData]="visibleRows()"
                [nzShowPagination]="false"
                [nzScroll]="{ y: '56vh' }"
                nzSize="small"
              >
                <thead>
                  <tr>
                    <th nzWidth="140px" i18n="@@admin.grants.col.module">Module</th>
                    <th i18n="@@admin.grants.col.permission">Permission</th>
                    <th nzWidth="230px" i18n="@@admin.grants.col.thisLevel">This level</th>
                  </tr>
                </thead>
                <tbody>
                  @for (p of visibleRows(); track p.key) {
                    <tr [class.set]="state(p.key!) !== 'inherit'">
                      <td class="module">{{ p.module }}</td>
                      <td>
                        <div class="p-key">{{ p.key }}</div>
                        <div class="p-desc">{{ p.description }}</div>
                      </td>
                      <td>
                        <app-effect-toggle
                          [value]="state(p.key!)"
                          [disabled]="!canManage()"
                          [label]="toggleLabel(p.key)"
                          (valueChange)="setState(p.key!, $event)"
                        />
                      </td>
                    </tr>
                  }
                </tbody>
              </nz-table>
            }
          </section>

          @if (canManage()) {
            <bo-action-bar
              [dirty]="dirty()"
              [saving]="saving()"
              saveLabel="Save grants"
              i18n-saveLabel="@@admin.grants.save"
              (save)="save()"
            >
              <button
                nz-button
                type="button"
                [disabled]="!dirty()"
                (click)="reset()"
                i18n="@@admin.grants.discard"
              >
                Discard changes
              </button>
            </bo-action-bar>
          }
        }
      </div>
    </div>
  `,
  styles: [
    EDITOR_STYLES,
    `
      .editor {
        max-width: none;
      }
      .fields.target {
        flex-direction: row;
        flex-wrap: wrap;
        align-items: center;
        gap: 12px;
      }
      .module {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      .p-key {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .p-desc {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      tr.set > td:first-child {
        box-shadow: inset 2px 0 0 var(--bo-ink-3);
      }
    `,
  ],
})
export class GrantsPageComponent implements HasUnsavedChanges {
  private readonly api = inject(AdminApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly identity = inject(IdentityService);

  readonly options = OVERRIDE_OPTIONS;
  readonly text = {
    chooseGroupFirst: $localize`:@@admin.grants.chooseGroupFirst:Choose a group to see and edit its grants.`,
    chooseUserFirst: $localize`:@@admin.grants.chooseUserFirst:Choose a user to see and edit their grants.`,
    noMatch: $localize`:@@admin.grants.empty.noMatch:No permissions match these filters.`,
    emptyCatalogue: $localize`:@@admin.grants.empty.catalogue:The catalogue is empty.`,
  };
  readonly f = urlFilters({ q: 'string', only: 'string' });
  readonly level = signal<GrantLevel>('SYSTEM');
  readonly targetId = signal<string | null>(null);
  readonly groups = signal<Group[]>([]);
  readonly platformRoles = signal<Role[]>([]);
  readonly catalogue = signal<PermissionDefinition[]>([]);
  readonly roles = signal<string[]>([]);
  readonly overrides = signal<Record<string, OverrideState>>({});
  private readonly saved = signal<GrantSet | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);

  readonly canManage = computed(() => this.identity.can('admin.roles.manage'));
  readonly rows = computed(() =>
    matrixFor(this.catalogue(), 'PLATFORM').flatMap((m) =>
      m.permissions.map((p) => ({
        ...p,
        module: m.module,
        description: permissionDescription(p.key!, p.description),
      })),
    ),
  );
  readonly overrideCount = computed(() => Object.keys(this.overrides()).length);
  readonly visibleRows = computed(() => {
    const v = this.f.values();
    const overrides = this.overrides();
    const list = v.only ? this.rows().filter((r) => r.key && overrides[r.key]) : this.rows();
    const q = v.q?.trim();
    return q
      ? fuzzyFilter(list, q, (r) => [r.key, r.description, r.module], 1000).map((x) => x.item)
      : list;
  });
  readonly presets = computed<FilterPreset[]>(() => [
    {
      label: $localize`:@@admin.grants.preset.overridesOnly:Overrides only`,
      value: 'only',
      active: !!this.f.values().only,
    },
  ]);
  /** Platform roles plus any role of the grant set that is not in the list; labelled by name. */
  readonly roleOptions = computed(() => roleOptions(this.platformRoles(), this.roles()));
  private readonly current = computed(() =>
    toGrantSet(this.level(), this.targetId(), this.roles(), this.overrides()),
  );
  readonly dirty = computed(() => {
    const s = this.saved();
    if (!s) return false;
    return (
      JSON.stringify(this.current()) !==
      JSON.stringify(toGrantSet(s.level, s.targetId ?? null, s.roles ?? [], toOverrides(s)))
    );
  });

  constructor() {
    inject(BreadcrumbService).set([
      { label: $localize`:@@admin.grants.crumb.admin:Administration`, link: '/admin' },
      { label: $localize`:@@admin.grants.crumb.grants:Grants` },
    ]);
    this.api.groups().subscribe({ next: (g) => this.groups.set(g), error: () => undefined });
    this.api
      .permissions()
      .subscribe({ next: (c) => this.catalogue.set(c), error: () => undefined });
    this.api.roles('PLATFORM', { silent: true }).subscribe({
      next: (r) => this.platformRoles.set(r),
      error: () => undefined,
    });
    this.load();
  }

  hasUnsavedChanges(): boolean {
    return this.canManage() && this.dirty();
  }

  toggleLabel(permission: string | undefined): string {
    return $localize`:@@admin.grants.toggleLabel:Grant of ${permission}:permission:`;
  }

  state(permission: string): OverrideState {
    return overrideOf(this.overrides(), permission);
  }

  setState(permission: string, state: OverrideState): void {
    this.overrides.set(setOverride(this.overrides(), permission, state));
  }

  async setLevel(level: GrantLevel): Promise<void> {
    if (!(await this.discardOk())) {
      this.level.set(this.level());
      return;
    }
    this.level.set(level);
    this.targetId.set(null);
    this.load();
  }

  async setTarget(id: string | null): Promise<void> {
    if (!(await this.discardOk())) return;
    this.targetId.set(id);
    this.load();
  }

  reset(): void {
    const s = this.saved();
    if (s) this.apply(s);
  }

  load(): void {
    const level = this.level();
    const targetId = this.targetId();
    this.saved.set(null);
    if (level !== 'SYSTEM' && !targetId) return;
    this.loading.set(true);
    this.api.grants(level, level === 'SYSTEM' ? null : targetId).subscribe({
      next: (sets) => {
        this.loading.set(false);
        this.apply(pickGrantSet(sets, level, targetId));
      },
      error: () => this.loading.set(false),
    });
  }

  save(): void {
    this.saving.set(true);
    this.pendingSaves.track(this.api.replaceGrants(this.current())).subscribe({
      next: (s) => {
        this.saving.set(false);
        this.apply(s);
        this.message.success($localize`:@@admin.grants.saved:Grants saved`);
        void this.identity.refresh();
      },
      error: () => this.saving.set(false),
    });
  }

  private apply(s: GrantSet): void {
    this.saved.set(s);
    this.roles.set(s.roles ?? []);
    this.overrides.set(toOverrides(s));
  }

  private async discardOk(): Promise<boolean> {
    if (!this.hasUnsavedChanges()) return true;
    return this.confirm.confirm({
      title: $localize`:@@admin.grants.discardConfirm.title:Discard unsaved changes?`,
      content: $localize`:@@admin.grants.discardConfirm.text:The grants of the current target have not been saved.`,
      okText: $localize`:@@admin.grants.discardConfirm.ok:Discard changes`,
      danger: true,
    });
  }
}
