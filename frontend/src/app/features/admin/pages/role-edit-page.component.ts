import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { toSignal } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { AdminApi } from '../../../core/api/admin.service';
import type {
  PermissionDefinition,
  PermissionScope,
  Role,
  RoleInput,
} from '../../../core/api/types';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { IdentityService } from '../../../core/auth/identity.service';
import { ROLE_KEY_PATTERN } from '../../../core/forms/patterns';
import { roleName } from '../../../core/i18n/catalog-labels';
import { PermissionDescriptionPipe } from '../../../core/i18n/catalog-labels.pipes';
import { compareKeys } from '../../../core/i18n/compare';
import { icu } from '../../../core/i18n/icu';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { reportFormError } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ProjectRolesService } from '../../../core/state/project-roles.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { AdminNavComponent } from '../components/admin-nav.component';
import { EDITOR_STYLES } from '../components/editor-styles';
import {
  type MatrixModule,
  matrixFor,
  moduleState,
  toggleModule,
  togglePermission,
  unknownPermissions,
} from '../data/permission-matrix';

/** Role editor: key, name, scope and the permission matrix of that scope grouped by module. */
@Component({
  selector: 'app-role-edit-page',
  imports: [
    PermissionDescriptionPipe,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzRadioModule,
    NzCheckboxModule,
    NzButtonModule,
    NzIconModule,
    ReadonlyFormDirective,
    ActionBarComponent,
    AdminNavComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.roles"
        [title]="isNew() ? text.create : displayName() || text.role"
        [subtitle]="subtitle()"
        [menu]="menu()"
      >
        @if (role()?.builtIn) {
          <span
            boHeaderStatus
            class="builtin"
            title="Built-in roles can be edited but not deleted"
            i18n-title="@@admin.roleEdit.builtInHint"
            ><nz-icon nzType="lock" /> <span i18n="@@admin.roleEdit.builtIn">built-in</span></span
          >
        }
      </bo-page-header>
      @if (loading()) {
        <bo-skeleton variant="detail" label="Loading role" i18n-label="@@admin.roleEdit.loading" />
      } @else {
        <form
          nz-form
          nzLayout="vertical"
          class="editor"
          [formGroup]="form"
          [boReadonly]="!canManage()"
          (ngSubmit)="save()"
        >
          <section class="section" aria-labelledby="r-s-about">
            <div class="intro">
              <h2 id="r-s-about" i18n="@@admin.roleEdit.about.title">About</h2>
              <p i18n="@@admin.roleEdit.about.text">
                The key is used in grants and memberships and cannot change later.
              </p>
            </div>
            <div class="fields">
              <div class="grid">
                <nz-form-item>
                  <nz-form-label nzRequired nzFor="r-key" i18n="@@admin.roleEdit.key"
                    >Key</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="keyTip">
                    <input
                      nz-input
                      id="r-key"
                      class="mono"
                      formControlName="key"
                      [readonly]="!isNew()"
                    />
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzRequired nzFor="r-name" i18n="@@admin.roleEdit.name"
                    >Name</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="tip">
                    <input nz-input id="r-name" formControlName="name" />
                  </nz-form-control>
                </nz-form-item>
              </div>
              <ng-template #keyTip let-control>{{
                control.errors?.server || text.keyPattern
              }}</ng-template>
              <nz-form-item>
                <nz-form-label i18n="@@admin.roleEdit.scope">Scope</nz-form-label>
                <nz-form-control>
                  <nz-radio-group
                    formControlName="scope"
                    aria-label="Scope"
                    i18n-aria-label="@@admin.roleEdit.scopeLabel"
                  >
                    <label
                      nz-radio-button
                      nzValue="PLATFORM"
                      [nzDisabled]="!isNew()"
                      i18n="@@admin.roleEdit.scope.platform"
                      >Platform</label
                    >
                    <label
                      nz-radio-button
                      nzValue="PROJECT"
                      [nzDisabled]="!isNew()"
                      i18n="@@admin.roleEdit.scope.project"
                      >Project</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="r-desc" i18n="@@admin.roleEdit.description"
                  >Description</nz-form-label
                >
                <nz-form-control [nzErrorTip]="tip">
                  <input nz-input id="r-desc" formControlName="description" />
                </nz-form-control>
              </nz-form-item>
            </div>
            <ng-template #tip let-control>{{
              control.errors?.server || text.enterName
            }}</ng-template>
          </section>

          <section class="section wide" aria-labelledby="r-s-perms">
            <h2 id="r-s-perms">
              <span i18n="@@admin.roleEdit.permissions">Permissions</span>
              <span class="count" i18n="@@admin.roleEdit.selected"
                >{{ selected().size }} selected</span
              >
            </h2>
            @for (m of matrix(); track m.module) {
              <fieldset class="module">
                <legend>
                  <label
                    nz-checkbox
                    [nzChecked]="state(m) === 'all'"
                    [nzIndeterminate]="state(m) === 'some'"
                    [nzDisabled]="!canManage()"
                    (nzCheckedChange)="setModule(m, $event)"
                    class="module-title"
                  >
                    {{ m.module }}
                  </label>
                  <span class="count" i18n="@@admin.roleEdit.moduleCount"
                    >{{ count(m) }} of {{ m.permissions.length }}</span
                  >
                </legend>
                <div class="perms">
                  @for (p of m.permissions; track p.key) {
                    <label
                      nz-checkbox
                      class="perm"
                      [nzChecked]="selected().has(p.key!)"
                      [nzDisabled]="!canManage()"
                      (nzCheckedChange)="setPermission(p.key!, $event)"
                    >
                      <span class="p-key">{{ p.key }}</span>
                      <span class="p-desc">{{ p.key | permissionDescription: p.description }}</span>
                    </label>
                  }
                </div>
              </fieldset>
            } @empty {
              <p class="muted" i18n="@@admin.roleEdit.catalogueFailed">
                The permission catalogue could not be loaded.
              </p>
            }
            @if (unknown().length) {
              <p class="danger small">
                @if (formScope() === 'PROJECT') {
                  <ng-container i18n="@@admin.roleEdit.unknownProject"
                    >Not in the project catalogue, so saving is refused:
                    {{ unknown().join(', ') }}</ng-container
                  >
                } @else {
                  <ng-container i18n="@@admin.roleEdit.unknownPlatform"
                    >Not in the platform catalogue, so saving is refused:
                    {{ unknown().join(', ') }}</ng-container
                  >
                }
              </p>
            }
          </section>

          @if (canManage()) {
            <bo-action-bar
              [dirty]="dirty()"
              [saving]="saving()"
              [canSave]="valid()"
              [saveLabel]="isNew() ? text.create : text.save"
              (save)="save()"
            >
              <button nz-button type="button" (click)="cancel()">
                @if (isNew()) {
                  <ng-container i18n="@@admin.roleEdit.cancel">Cancel</ng-container>
                } @else {
                  <ng-container i18n="@@admin.roleEdit.back">Back to roles</ng-container>
                }
              </button>
            </bo-action-bar>
          }
        </form>
      }
    </div>
  `,
  styles: [
    EDITOR_STYLES,
    `
      .builtin {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .grid {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
        gap: 0 16px;
      }
      .module {
        margin: 0;
        padding: 10px 0 4px;
        border: 0;
        border-top: 1px solid var(--bo-line);
        min-width: 0;
      }
      .module legend {
        display: flex;
        align-items: baseline;
        gap: 8px;
        border: 0;
        width: auto;
        margin: 0 0 4px;
        padding: 0;
        font-size: 13px;
        line-height: inherit;
      }
      .module-title {
        font-weight: 600;
      }
      .perms {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
        gap: 2px 16px;
        padding: 0 0 4px 24px;
      }
      .perm {
        margin: 0 !important;
        padding: 3px 0;
      }
      .p-key {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .p-desc {
        margin-left: 6px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .small {
        font-size: 12px;
      }
    `,
  ],
})
export class RoleEditPageComponent implements HasUnsavedChanges {
  private readonly api = inject(AdminApi);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly identity = inject(IdentityService);
  private readonly projectRoles = inject(ProjectRolesService);
  private readonly confirm = inject(ConfirmService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  /** Absent on `/admin/roles/new`. */
  readonly roleKey = input<string | undefined>();
  /** `?scope=` preselects the scope of a new role. */
  readonly scope = input<string | undefined>();

  readonly role = signal<Role | null>(null);
  readonly catalogue = signal<PermissionDefinition[]>([]);
  readonly selected = signal<ReadonlySet<string>>(new Set());
  private readonly savedPermissions = signal<string>('[]');
  readonly loading = signal(false);
  readonly saving = signal(false);

  readonly text = {
    create: $localize`:@@admin.roleEdit.create:Create role`,
    save: $localize`:@@admin.roleEdit.save:Save role`,
    role: $localize`:@@admin.roleEdit.role:Role`,
    keyPattern: $localize`:@@admin.roleEdit.keyPattern:Use lowercase letters, digits and dashes (2 to 40).`,
    enterName: $localize`:@@admin.roleEdit.enterName:Enter a name.`,
  };

  readonly isNew = computed(() => !this.roleKey());
  readonly canManage = computed(() => this.identity.can('admin.roles.manage'));

  readonly form = this.fb.nonNullable.group({
    key: ['', [Validators.required, Validators.pattern(ROLE_KEY_PATTERN)]],
    name: ['', Validators.required],
    description: [''],
    scope: this.fb.nonNullable.control<PermissionScope>('PLATFORM'),
  });
  private readonly scopeValue = toSignal(this.form.controls.scope.valueChanges, {
    initialValue: 'PLATFORM' as PermissionScope,
  });
  readonly formScope = computed(() => this.scopeValue());
  readonly matrix = computed<MatrixModule[]>(() => matrixFor(this.catalogue(), this.formScope()));
  readonly unknown = computed(() =>
    this.catalogue().length
      ? unknownPermissions(this.selected(), this.catalogue(), this.formScope())
      : [],
  );
  readonly dirty = computed(() => this.permissionsChanged() || this.formDirty());
  private readonly formDirty = signal(false);
  private readonly permissionsChanged = computed(
    () => JSON.stringify([...this.selected()].sort(compareKeys)) !== this.savedPermissions(),
  );

  private readonly formStatus = signal(0);
  readonly valid = computed(() => {
    this.formStatus();
    this.formDirty();
    return this.form.valid;
  });
  /** Name of the role being edited: translated for an unchanged built-in role. */
  readonly displayName = computed(() => {
    const key = this.roleKey();
    return key ? roleName(key, this.role()?.name) : '';
  });
  readonly subtitle = computed(() => {
    if (this.isNew())
      return $localize`:@@admin.roleEdit.subtitleNew:A named bundle of permissions for one scope.`;
    const r = this.role();
    const n = this.selected().size;
    const scope =
      r?.scope === 'PROJECT'
        ? $localize`:@@admin.roleEdit.projectRole:Project role`
        : $localize`:@@admin.roleEdit.platformRole:Platform role`;
    const perms = icu(
      $localize`:@@admin.roleEdit.permissionCount:{count, plural, one {# permission} other {# permissions}}`,
      { count: n },
    );
    return r?.builtIn
      ? $localize`:@@admin.roleEdit.subtitleBuiltIn:${scope}:scope: · ${perms}:permissions: · built-in roles can be edited but not deleted.`
      : $localize`:@@admin.roleEdit.subtitle:${scope}:scope: · ${perms}:permissions:.`;
  });
  readonly menu = computed<PageAction[]>(() =>
    !this.isNew() && this.canManage() && !this.role()?.builtIn
      ? [
          {
            label: $localize`:@@admin.roleEdit.menu.delete:Delete role`,
            icon: 'delete',
            danger: true,
            run: () => void this.remove(),
          },
        ]
      : [],
  );

  constructor() {
    this.form.statusChanges.subscribe(() => this.formStatus.update((n) => n + 1));
    effect(() => {
      const r = this.role();
      const key = this.roleKey();
      untracked(() =>
        this.breadcrumbs.set([
          { label: $localize`:@@admin.roleEdit.crumb.admin:Administration`, link: '/admin' },
          { label: $localize`:@@admin.roleEdit.crumb.roles:Roles`, link: '/admin/roles' },
          {
            label: key
              ? roleName(key, r?.name)
              : $localize`:@@admin.roleEdit.crumb.create:Create role`,
          },
        ]),
      );
    });
    this.api
      .permissions()
      .subscribe({ next: (c) => this.catalogue.set(c), error: () => undefined });
    this.form.valueChanges.subscribe(() => this.formDirty.set(this.form.dirty));
    effect(() => {
      const key = this.roleKey();
      const scope = this.scope();
      untracked(() => {
        if (key) this.load(key);
        else {
          this.form.reset({
            key: '',
            name: '',
            description: '',
            scope: scope === 'PROJECT' ? 'PROJECT' : 'PLATFORM',
          });
          this.selected.set(new Set());
          this.savedPermissions.set('[]');
        }
      });
    });
  }

  hasUnsavedChanges(): boolean {
    return this.canManage() && this.dirty();
  }

  count(m: MatrixModule): number {
    const sel = this.selected();
    return m.permissions.filter((p) => p.key && sel.has(p.key)).length;
  }

  cancel(): void {
    void this.router.navigate(['/admin/roles']);
  }

  async remove(): Promise<void> {
    const r = this.role();
    if (!r?.key || r.builtIn) return;
    const ok = await this.confirm.danger(
      $localize`:@@admin.roleEdit.delete.title:Delete role "${r.key}:key:"?`,
      $localize`:@@admin.roleEdit.delete.text:Grants and memberships that use this role stop granting its permissions immediately.`,
      $localize`:@@admin.roleEdit.delete.ok:Delete role`,
    );
    if (!ok) return;
    this.api.deleteRole(r.key).subscribe(() => {
      this.setRole(r);
      this.message.success($localize`:@@admin.roleEdit.deleted:Role deleted`);
      this.projectRoles.invalidate();
      void this.router.navigate(['/admin/roles']);
    });
  }

  state(m: MatrixModule) {
    return moduleState(m, this.selected());
  }

  setModule(m: MatrixModule, on: boolean): void {
    this.selected.set(toggleModule(this.selected(), m, on));
  }

  setPermission(key: string, on: boolean): void {
    this.selected.set(togglePermission(this.selected(), key, on));
  }

  load(key: string): void {
    this.loading.set(true);
    this.api.roles().subscribe({
      next: (roles) => {
        const r = roles.find((x) => x.key === key) ?? null;
        this.loading.set(false);
        if (!r) {
          this.notify.error(
            $localize`:@@admin.roleEdit.notFound.title:Role not found`,
            $localize`:@@admin.roleEdit.notFound.text:There is no role "${key}:key:".`,
          );
          return;
        }
        this.setRole(r);
      },
      error: () => this.loading.set(false),
    });
  }

  save(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const input: RoleInput = {
      key: v.key.trim(),
      name: v.name.trim(),
      description: v.description.trim() || null,
      scope: v.scope,
      permissions: [...this.selected()].sort(compareKeys),
    };
    this.saving.set(true);
    const req = this.isNew()
      ? this.api.createRole(input)
      : this.api.updateRole(this.roleKey()!, input);
    this.pendingSaves.track(req).subscribe({
      next: (r) => {
        this.saving.set(false);
        this.message.success(
          this.isNew()
            ? $localize`:@@admin.roleEdit.created:Role created`
            : $localize`:@@admin.roleEdit.saved:Role saved`,
        );
        this.projectRoles.invalidate();
        const wasNew = this.isNew();
        this.setRole(r);
        if (wasNew) void this.router.navigate(['/admin/roles', r.key], { replaceUrl: true });
        void this.identity.refresh();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        reportFormError(
          this.notify,
          this.form,
          err,
          $localize`:@@admin.roleEdit.saveFailed:Could not save the role`,
        );
      },
    });
  }

  private setRole(r: Role): void {
    this.role.set(r);
    this.form.reset({
      key: r.key ?? '',
      name: r.name ?? '',
      description: r.description ?? '',
      scope: r.scope ?? 'PLATFORM',
    });
    this.formDirty.set(false);
    const perms = [...(r.permissions ?? [])].sort(compareKeys);
    this.selected.set(new Set(perms));
    this.savedPermissions.set(JSON.stringify(perms));
  }
}
