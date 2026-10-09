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
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { AdminApi } from '../../../core/api/admin.service';
import type { Group, GroupInput, Role } from '../../../core/api/types';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { IdentityService } from '../../../core/auth/identity.service';
import { roleOptions } from '../../../core/i18n/catalog-labels';
import { icu } from '../../../core/i18n/icu';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { reportFormError } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import {
  PrincipalPickerComponent,
  type PrincipalOption,
} from '../../../core/ui/principal-picker.component';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { AdminNavComponent } from '../components/admin-nav.component';
import { EDITOR_STYLES } from '../components/editor-styles';

/** Create or edit a group: name, members (user lookup), platform roles, external group mapping. */
@Component({
  selector: 'app-group-edit-page',
  imports: [
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzButtonModule,
    ReadonlyFormDirective,
    PrincipalPickerComponent,
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
        help="admin.groups"
        [title]="isNew() ? text.create : group()?.name || text.group"
        [subtitle]="isNew() ? text.subtitleNew : memberText()"
        [menu]="menu()"
      />
      @if (loading()) {
        <bo-skeleton
          variant="detail"
          label="Loading group"
          i18n-label="@@admin.groupEdit.loading"
        />
      } @else {
        <form
          nz-form
          nzLayout="vertical"
          class="editor"
          [formGroup]="form"
          [boReadonly]="!canManage()"
          (ngSubmit)="save()"
        >
          <section class="section" aria-labelledby="g-s-about">
            <div class="intro">
              <h2 id="g-s-about" i18n="@@admin.groupEdit.about.title">About</h2>
              <p i18n="@@admin.groupEdit.about.text">
                How the group appears in pickers and on project member lists.
              </p>
            </div>
            <div class="fields">
              <nz-form-item>
                <nz-form-label nzRequired nzFor="g-name" i18n="@@admin.groupEdit.name"
                  >Name</nz-form-label
                >
                <nz-form-control [nzErrorTip]="tip">
                  <input nz-input id="g-name" formControlName="name" />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="g-desc" i18n="@@admin.groupEdit.description"
                  >Description</nz-form-label
                >
                <nz-form-control [nzErrorTip]="tip">
                  <textarea
                    nz-input
                    id="g-desc"
                    formControlName="description"
                    [nzAutosize]="{ minRows: 2, maxRows: 4 }"
                  ></textarea>
                </nz-form-control>
              </nz-form-item>
            </div>
          </section>
          <section class="section" aria-labelledby="g-s-members">
            <div class="intro">
              <h2 id="g-s-members" i18n="@@admin.groupEdit.members.title">Members and roles</h2>
              <p i18n="@@admin.groupEdit.members.text">
                Every member gets these platform roles, on top of their own.
              </p>
            </div>
            <div class="fields">
              <nz-form-item>
                <nz-form-label i18n="@@admin.groupEdit.members">Members</nz-form-label>
                <nz-form-control [nzErrorTip]="tip">
                  <app-principal-picker
                    kind="user"
                    [multiple]="true"
                    ariaLabel="Group members"
                    i18n-ariaLabel="@@admin.groupEdit.membersLabel"
                    [disabled]="!canManage()"
                    [known]="knownMembers()"
                    [value]="form.controls.memberIds.value"
                    (valueChange)="setMembers($event)"
                  />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="g-roles" i18n="@@admin.groupEdit.platformRoles"
                  >Platform roles</nz-form-label
                >
                <nz-form-control [nzErrorTip]="tip">
                  <nz-select
                    nzId="g-roles"
                    nzMode="multiple"
                    formControlName="platformRoles"
                    nzPlaceHolder="None"
                    i18n-nzPlaceHolder="@@admin.groupEdit.platformRolesNone"
                  >
                    @for (r of roleOptions(); track r.key) {
                      <nz-option [nzValue]="r.key" [nzLabel]="r.label" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
            </div>
          </section>
          <section class="section" aria-labelledby="g-s-sync">
            <div class="intro">
              <h2 id="g-s-sync" i18n="@@admin.groupEdit.idp.title">Identity provider</h2>
              <p i18n="@@admin.groupEdit.idp.text">
                With group sync on (platform settings), people with this group claim join at
                sign-in.
              </p>
            </div>
            <div class="fields">
              <nz-form-item>
                <nz-form-label nzFor="g-ext" i18n="@@admin.groupEdit.externalGroup"
                  >Identity provider group</nz-form-label
                >
                <nz-form-control
                  nzExtra="Value of the OIDC groups claim."
                  i18n-nzExtra="@@admin.groupEdit.externalGroupHint"
                  [nzErrorTip]="tip"
                >
                  <input
                    nz-input
                    id="g-ext"
                    class="mono"
                    formControlName="externalGroup"
                    [placeholder]="externalGroupExample"
                  />
                </nz-form-control>
              </nz-form-item>
            </div>
          </section>
          <ng-template #tip let-control>{{ control.errors?.server || text.enterName }}</ng-template>
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
                  <ng-container i18n="@@admin.groupEdit.cancel">Cancel</ng-container>
                } @else {
                  <ng-container i18n="@@admin.groupEdit.back">Back to groups</ng-container>
                }
              </button>
            </bo-action-bar>
          }
        </form>
      }
    </div>
  `,
  styles: [EDITOR_STYLES],
})
export class GroupEditPageComponent implements HasUnsavedChanges {
  private readonly api = inject(AdminApi);
  private readonly fb = inject(FormBuilder);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly identity = inject(IdentityService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  /** Absent on `/admin/groups/new`. */
  readonly groupId = input<string | undefined>();

  readonly group = signal<Group | null>(null);
  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly roles = signal<Role[]>([]);
  readonly knownMembers = signal<PrincipalOption[]>([]);

  readonly text = {
    create: $localize`:@@admin.groupEdit.create:Create group`,
    save: $localize`:@@admin.groupEdit.save:Save group`,
    group: $localize`:@@admin.groupEdit.group:Group`,
    subtitleNew: $localize`:@@admin.groupEdit.subtitleNew:Members get the platform roles and project memberships of the group.`,
    enterName: $localize`:@@admin.groupEdit.enterName:Enter a name.`,
  };
  /** Example value of the OIDC groups claim (data, not UI text). */
  readonly externalGroupExample = '/architects'; // i18n-ignore

  readonly isNew = computed(() => !this.groupId());
  readonly canManage = computed(() => this.identity.can('admin.users.manage'));
  readonly roleOptions = computed(() =>
    roleOptions(this.roles(), this.group()?.platformRoles ?? []),
  );

  readonly form = this.fb.nonNullable.group({
    name: ['', Validators.required],
    description: [''],
    memberIds: this.fb.nonNullable.control<string[]>([]),
    platformRoles: this.fb.nonNullable.control<string[]>([]),
    externalGroup: [''],
  });

  private readonly formValue = toSignal(this.form.valueChanges, { initialValue: null });
  private readonly formStatus = toSignal(this.form.statusChanges, { initialValue: null });
  readonly dirty = computed(() => {
    this.formValue();
    return this.form.dirty;
  });
  readonly valid = computed(() => {
    this.formStatus();
    this.formValue();
    return this.form.valid;
  });
  readonly memberText = computed(() => {
    this.formValue();
    const n = this.form.controls.memberIds.value.length;
    const ext = this.group()?.externalGroup;
    const members = icu(
      $localize`:@@admin.groupEdit.memberCount:{count, plural, one {# member} other {# members}}`,
      { count: n },
    );
    return ext
      ? $localize`:@@admin.groupEdit.memberTextSynced:${members}:members: · synchronised with ${ext}:externalGroup:.`
      : $localize`:@@admin.groupEdit.memberText:${members}:members:.`;
  });
  readonly menu = computed<PageAction[]>(() =>
    !this.isNew() && this.canManage()
      ? [
          {
            label: $localize`:@@admin.groupEdit.menu.delete:Delete group`,
            icon: 'delete',
            danger: true,
            run: () => void this.remove(),
          },
        ]
      : [],
  );

  constructor() {
    effect(() => {
      const g = this.group();
      untracked(() =>
        this.breadcrumbs.set([
          { label: $localize`:@@admin.groupEdit.crumb.admin:Administration`, link: '/admin' },
          { label: $localize`:@@admin.groupEdit.crumb.groups:Groups`, link: '/admin/groups' },
          {
            label: this.groupId()
              ? g?.name || $localize`:@@admin.groupEdit.crumb.group:Group`
              : $localize`:@@admin.groupEdit.crumb.create:Create group`,
          },
        ]),
      );
    });
    this.api.roles('PLATFORM', { silent: true }).subscribe({
      next: (r) => this.roles.set(r),
      error: () => undefined,
    });
    effect(() => {
      const id = this.groupId();
      untracked(() => (id ? this.load(id) : this.form.reset()));
    });
  }

  hasUnsavedChanges(): boolean {
    return this.form.dirty && this.canManage();
  }

  cancel(): void {
    void this.router.navigate(['/admin/groups']);
  }

  setMembers(value: string | string[] | null): void {
    const ids = Array.isArray(value) ? value : value ? [value] : [];
    this.form.controls.memberIds.setValue(ids);
    this.form.controls.memberIds.markAsDirty();
  }

  load(id: string): void {
    this.loading.set(true);
    this.api.group(id).subscribe({
      next: (g) => {
        this.setGroup(g);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
    // Labels for the member ids: the users of this group.
    this.api.users({ groupId: id, size: 200 }).subscribe({
      next: (p) =>
        this.knownMembers.set(
          (p.content ?? []).map((u) => ({
            id: u.id!,
            label: u.displayName || u.email || u.id!,
            hint: u.email,
          })),
        ),
      error: () => undefined,
    });
  }

  save(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const input: GroupInput = {
      name: v.name.trim(),
      description: v.description.trim() || null,
      memberIds: v.memberIds,
      platformRoles: v.platformRoles,
      externalGroup: v.externalGroup.trim() || null,
    };
    const id = this.groupId();
    this.saving.set(true);
    const req = id ? this.api.updateGroup(id, input) : this.api.createGroup(input);
    this.pendingSaves.track(req).subscribe({
      next: (g) => {
        this.saving.set(false);
        this.setGroup(g);
        this.message.success(
          id
            ? $localize`:@@admin.groupEdit.saved:Group saved`
            : $localize`:@@admin.groupEdit.created:Group created`,
        );
        if (!id) void this.router.navigate(['/admin/groups', g.id], { replaceUrl: true });
        const me = this.identity.me();
        if (
          me &&
          (v.memberIds.includes(me.user?.id ?? '') || me.groups?.some((x) => x.id === g.id))
        )
          void this.identity.refresh();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        reportFormError(
          this.notify,
          this.form,
          err,
          $localize`:@@admin.groupEdit.saveFailed:Could not save the group`,
        );
      },
    });
  }

  async remove(): Promise<void> {
    const g = this.group();
    if (!g?.id) return;
    const ok = await this.confirm.danger(
      $localize`:@@admin.groupEdit.delete.title:Delete group "${g.name}:name:"?`,
      $localize`:@@admin.groupEdit.delete.text:Members lose the roles and project memberships they had through this group.`,
      $localize`:@@admin.groupEdit.delete.ok:Delete group`,
    );
    if (!ok) return;
    this.api.deleteGroup(g.id).subscribe(() => {
      this.form.markAsPristine();
      this.message.success($localize`:@@admin.groupEdit.deleted:Group deleted`);
      void this.router.navigate(['/admin/groups']);
    });
  }

  private setGroup(g: Group): void {
    this.group.set(g);
    this.form.reset({
      name: g.name ?? '',
      description: g.description ?? '',
      memberIds: g.memberIds ?? [],
      platformRoles: g.platformRoles ?? [],
      externalGroup: g.externalGroup ?? '',
    });
  }
}
