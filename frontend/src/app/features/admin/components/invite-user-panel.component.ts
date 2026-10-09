import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { AdminApi } from '../../../core/api/admin.service';
import type {
  AuthMethods,
  AuthMode,
  Group,
  Project,
  Role,
  UserDetail,
  UserInvite,
} from '../../../core/api/types';
import { reportFormError } from '../../../core/forms/server-errors';
import { roleName } from '../../../core/i18n/catalog-labels';
import { RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import type { ProjectRoleOption } from '../../../core/state/project-roles.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { firstName } from '../../../core/ui/relay/format';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import {
  authModeName,
  authModeSummary,
  availableAuthModes,
  defaultInviteMode,
  inviteGetsSetPasswordLink,
  neutralProvider,
  providerVia,
} from '../data/local-accounts';
import { AuthModePickerComponent } from './auth-mode-picker.component';
import { OneTimeLinkComponent } from './one-time-link.component';
import { StateChipComponent } from './state-chip.component';

/**
 * Invite a user (side panel): e-mail, name, sign-in method, groups, platform roles and project
 * memberships. Back Office sends no e-mail: afterwards the set-password link (local) or the sign-in
 * link (identity provider) is shown to copy and hand over.
 */
@Component({
  selector: 'app-invite-user-panel',
  imports: [
    RoleNamePipe,
    ReactiveFormsModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzButtonModule,
    NzIconModule,
    SidePanelComponent,
    AuthModePickerComponent,
    OneTimeLinkComponent,
    StateChipComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      class="invite-panel"
      [open]="open()"
      [title]="invited() ? invitedTitle() : text.title"
      [subtitle]="invited() ? null : text.subtitle"
      [defaultWidth]="600"
      storageKey="backoffice.invitePanelWidth"
      initialFocus="#inv-email"
      (closed)="closed.emit()"
    >
      @if (invited(); as u) {
        <ng-container boPanelHeader>
          <app-state-chip tone="you" label="invited" i18n-label="@@admin.invite.chipInvited" />
          <span class="muted small">{{ u.email }}</span>
        </ng-container>
      }
      @if (invited(); as u) {
        <section class="block">
          @if (u.setPasswordUrl) {
            <app-one-time-link
              label="Set-password link"
              i18n-label="@@admin.invite.setPasswordLink"
              [url]="u.setPasswordUrl"
              [name]="name(u)"
              [hint]="setPasswordHint(u)"
            />
          } @else if (u.signInUrl) {
            <app-one-time-link
              label="Sign-in link"
              i18n-label="@@admin.invite.signInLink"
              [url]="u.signInUrl"
              [name]="name(u)"
              [hint]="signInHint(u)"
            />
          } @else {
            <p class="muted" i18n="@@admin.invite.noLink">
              The server returned no link; {{ name(u) }} signs in at this Back Office address.
            </p>
          }
        </section>
        <dl class="facts">
          <div>
            <dt i18n="@@admin.invite.factSignIn">Sign-in</dt>
            <dd>{{ modeName(u.authMode) }}</dd>
          </div>
          <div>
            <dt i18n="@@admin.invite.factGroups">Groups</dt>
            <dd>{{ groupNames(u) || text.none }}</dd>
          </div>
          <div>
            <dt i18n="@@admin.invite.factRoles">Platform roles</dt>
            <dd>{{ roleNames(u.platformRoles) || text.none }}</dd>
          </div>
        </dl>
      } @else {
        <form nz-form nzLayout="vertical" [formGroup]="form" id="invite-form" (ngSubmit)="submit()">
          <nz-form-item>
            <nz-form-label nzRequired nzFor="inv-email" i18n="@@admin.invite.email"
              >E-mail</nz-form-label
            >
            <nz-form-control [nzErrorTip]="emailTip">
              <input
                nz-input
                id="inv-email"
                formControlName="email"
                type="email"
                autocomplete="off"
              />
            </nz-form-control>
          </nz-form-item>
          <ng-template #emailTip let-control>
            {{ control.errors?.server || text.emailInvalid }}
          </ng-template>
          <nz-form-item>
            <nz-form-label nzFor="inv-name" i18n="@@admin.invite.displayName"
              >Display name</nz-form-label
            >
            <nz-form-control
              nzExtra="Optional; otherwise taken from the first sign-in."
              i18n-nzExtra="@@admin.invite.displayNameExtra"
            >
              <input nz-input id="inv-name" formControlName="displayName" autocomplete="off" />
            </nz-form-control>
          </nz-form-item>

          <fieldset class="field">
            <legend i18n="@@admin.invite.method">Sign-in method</legend>
            <app-auth-mode-picker
              name="inv-auth"
              [modes]="modes()"
              [value]="mode()"
              [provider]="provider()"
              [describe]="describe"
              (valueChange)="form.controls.authMode.setValue($event)"
            />
          </fieldset>

          <nz-form-item>
            <nz-form-label nzFor="inv-groups" i18n="@@admin.invite.groups">Groups</nz-form-label>
            <nz-form-control>
              <nz-select
                nzId="inv-groups"
                nzMode="multiple"
                formControlName="groupIds"
                nzPlaceHolder="None"
                i18n-nzPlaceHolder="@@admin.invite.groupsNone"
              >
                @for (g of groups(); track g.id) {
                  <nz-option [nzValue]="g.id" [nzLabel]="g.name ?? ''" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>
          <nz-form-item>
            <nz-form-label nzFor="inv-roles" i18n="@@admin.invite.roles"
              >Platform roles</nz-form-label
            >
            <nz-form-control
              nzExtra="Assigned to the user, on top of the system defaults and the groups."
              i18n-nzExtra="@@admin.invite.rolesExtra"
            >
              <nz-select
                nzId="inv-roles"
                nzMode="multiple"
                formControlName="platformRoles"
                nzPlaceHolder="None"
                i18n-nzPlaceHolder="@@admin.invite.rolesNone"
              >
                @for (r of platformRoles(); track r.key) {
                  <nz-option [nzValue]="r.key" [nzLabel]="r.key | roleName: r.name" />
                }
              </nz-select>
            </nz-form-control>
          </nz-form-item>

          <div class="field">
            <div class="label" i18n="@@admin.invite.memberships">Project memberships</div>
            <div formArrayName="projectMemberships">
              @for (m of memberships.controls; track $index; let i = $index) {
                <div class="membership" [formGroupName]="i">
                  <nz-select
                    formControlName="projectId"
                    nzShowSearch
                    nzPlaceHolder="Project"
                    i18n-nzPlaceHolder="@@admin.invite.project"
                    [attr.aria-label]="projectLabel(i + 1)"
                  >
                    @for (p of projects(); track p.id) {
                      <nz-option [nzValue]="p.id" [nzLabel]="p.key + ' · ' + p.name" />
                    }
                  </nz-select>
                  <nz-select
                    formControlName="role"
                    nzPlaceHolder="Role"
                    i18n-nzPlaceHolder="@@admin.invite.role"
                    [attr.aria-label]="roleLabel(i + 1)"
                  >
                    @for (r of projectRoles(); track r.key) {
                      <nz-option [nzValue]="r.key" [nzLabel]="r.name" />
                    }
                  </nz-select>
                  <button
                    nz-button
                    nzType="text"
                    type="button"
                    (click)="removeMembership(i)"
                    [attr.aria-label]="removeLabel(i + 1)"
                  >
                    <nz-icon nzType="close" />
                  </button>
                </div>
              }
            </div>
            <button
              nz-button
              nzType="dashed"
              nzSize="small"
              type="button"
              (click)="addMembership()"
            >
              <nz-icon nzType="plus" />
              <span i18n="@@admin.invite.addMembership">Add project membership</span>
            </button>
          </div>
        </form>
      }
      <div boPanelFooter class="footer">
        @if (invited()) {
          <button nz-button type="button" (click)="inviteAnother()" i18n="@@admin.invite.another">
            Invite another user
          </button>
          <button
            nz-button
            nzType="primary"
            type="button"
            (click)="closed.emit()"
            i18n="@@admin.invite.done"
          >
            Done
          </button>
        } @else {
          <span class="foot-note">{{ footNote() }}</span>
          <button nz-button type="button" (click)="closed.emit()" i18n="@@admin.invite.cancel">
            Cancel
          </button>
          <button
            nz-button
            nzType="primary"
            type="submit"
            form="invite-form"
            [nzLoading]="saving()"
            [disabled]="form.invalid"
            i18n="@@admin.invite.submit"
          >
            Invite user
          </button>
        }
      </div>
    </bo-side-panel>
  `,
  styles: [
    `
      .block {
        margin-bottom: 20px;
      }
      .small {
        font-size: 12px;
      }
      .field {
        margin: 0 0 20px;
        padding: 0;
        border: 0;
        min-width: 0;
      }
      legend,
      .label {
        font-size: 13px;
        font-weight: 500;
        color: var(--bo-ink);
        margin-bottom: 8px;
        padding: 0;
        border: 0;
        width: auto;
        line-height: inherit;
      }
      .membership {
        display: grid;
        grid-template-columns: minmax(0, 1fr) 160px auto;
        gap: 8px;
        margin-bottom: 8px;
      }
      .facts {
        margin: 0;
        display: grid;
        gap: 6px;
        font-size: 13px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 120px minmax(0, 1fr);
        gap: 12px;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
      }
      .footer {
        display: flex;
        gap: 8px;
        justify-content: flex-end;
        align-items: center;
      }
      .foot-note {
        margin-right: auto;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class InviteUserPanelComponent {
  private readonly fb = inject(FormBuilder);
  private readonly api = inject(AdminApi);
  private readonly notify = inject(NotifyService);
  private readonly pendingSaves = inject(PendingSavesService);

  readonly open = input(false);
  readonly groups = input<Group[]>([]);
  readonly platformRoles = input<Role[]>([]);
  readonly projectRoles = input<ProjectRoleOption[]>([]);
  readonly projects = input<Project[]>([]);
  readonly methods = input<AuthMethods | null>(null);
  readonly providerName = input<string>(neutralProvider());
  /** Preselected sign-in method (`?invite=local`); otherwise the server default. */
  readonly preset = input<AuthMode | null>(null);
  readonly closed = output<void>();
  readonly userInvited = output<UserDetail>();

  readonly saving = signal(false);
  readonly invited = signal<UserDetail | null>(null);

  readonly text = {
    title: $localize`:@@admin.invite.title:Invite user`,
    subtitle: $localize`:@@admin.invite.subtitle:Back Office sends no e-mail: you hand over the link shown afterwards.`,
    none: $localize`:@@admin.invite.none:None`,
    emailInvalid: $localize`:@@admin.invite.emailInvalid:Enter a valid e-mail address.`,
  };

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    displayName: [''],
    authMode: this.fb.nonNullable.control<AuthMode>('OIDC'),
    groupIds: this.fb.nonNullable.control<string[]>([]),
    platformRoles: this.fb.nonNullable.control<string[]>([]),
    projectMemberships: this.fb.array<ReturnType<InviteUserPanelComponent['membershipGroup']>>([]),
  });

  readonly mode = toSignal(this.form.controls.authMode.valueChanges, {
    initialValue: this.form.controls.authMode.value,
  });
  readonly provider = computed(() => this.providerName());
  readonly modes = computed(() => availableAuthModes(this.methods()));
  readonly invitedTitle = computed(() => {
    const u = this.invited();
    if (!u) return '';
    const name = this.name(u);
    return $localize`:@@admin.invite.invitedTitle:${name}:name: is invited`;
  });
  readonly footNote = computed(() =>
    inviteGetsSetPasswordLink(this.mode())
      ? $localize`:@@admin.invite.footSetPassword:You get a set-password link to hand over.`
      : $localize`:@@admin.invite.footSignIn:You get a sign-in link to hand over.`,
  );

  /** Option text in the invite: what the user will do with the link. */
  readonly describe = (m: AuthMode, provider: string): string[] => {
    const lines = [authModeSummary(m, provider)];
    const via = providerVia(provider);
    lines.push(
      inviteGetsSetPasswordLink(m)
        ? $localize`:@@admin.invite.describeSetPassword:You get a set-password link; the account becomes active when the password is set.`
        : $localize`:@@admin.invite.describeSignIn:You get a sign-in link; the first sign-in through ${via}:provider: activates the account.`,
    );
    return lines;
  };

  setPasswordHint(u: UserDetail): string {
    const name = this.name(u);
    return $localize`:@@admin.invite.setPasswordHint:Hand this link to ${name}:name:; it works once and expires in 24 h. ${name}:name: chooses a password with it and the account becomes active.`;
  }

  signInHint(u: UserDetail): string {
    const name = this.name(u);
    const via = providerVia(this.provider());
    const email = u.email;
    return $localize`:@@admin.invite.signInHint:Hand this link to ${name}:name:. The first sign-in through ${via}:provider: with ${email}:email: activates the account.`;
  }

  projectLabel(n: number): string {
    return $localize`:@@admin.invite.projectAria:Project ${n}:number:`;
  }

  roleNames(keys: readonly string[] | null | undefined): string {
    return (keys ?? []).map((k) => roleName(k)).join(', ');
  }

  roleLabel(n: number): string {
    return $localize`:@@admin.invite.roleAria:Role in project ${n}:number:`;
  }

  removeLabel(n: number): string {
    return $localize`:@@admin.invite.removeAria:Remove membership ${n}:number:`;
  }

  get memberships(): FormArray {
    return this.form.controls.projectMemberships;
  }

  constructor() {
    effect(() => {
      if (this.open()) untracked(() => this.inviteAnother());
    });
    // The methods arrive after the panel opened: never keep an unavailable default.
    effect(() => {
      const modes = this.modes();
      const methods = this.methods();
      untracked(() => {
        if (!modes.includes(this.form.controls.authMode.value))
          this.form.controls.authMode.setValue(this.initialMode(methods));
      });
    });
  }

  private initialMode(methods = this.methods()): AuthMode {
    const preset = this.preset();
    return preset && availableAuthModes(methods).includes(preset)
      ? preset
      : defaultInviteMode(methods);
  }

  name(u: UserDetail): string {
    return (
      firstName(u.displayName, u.email) || u.email || $localize`:@@admin.invite.theUser:the user`
    );
  }

  modeName(m: AuthMode | undefined): string {
    return authModeName(m ?? 'OIDC', this.provider());
  }

  groupNames(u: UserDetail): string {
    return (u.groups ?? []).map((g) => g.name).join(', ');
  }

  membershipGroup() {
    return this.fb.nonNullable.group({
      projectId: ['', Validators.required],
      role: ['developer', Validators.required],
    });
  }

  addMembership(): void {
    this.form.controls.projectMemberships.push(this.membershipGroup());
  }

  removeMembership(i: number): void {
    this.form.controls.projectMemberships.removeAt(i);
  }

  inviteAnother(): void {
    this.invited.set(null);
    this.form.controls.projectMemberships.clear();
    this.form.reset({
      email: '',
      displayName: '',
      authMode: this.initialMode(),
      groupIds: [],
      platformRoles: [],
    });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const input: UserInvite = {
      email: v.email.trim(),
      displayName: v.displayName.trim() || null,
      authMode: v.authMode,
      groupIds: v.groupIds,
      platformRoles: v.platformRoles,
      projectMemberships: v.projectMemberships,
    };
    this.saving.set(true);
    this.pendingSaves.track(this.api.invite(input)).subscribe({
      next: (user) => {
        this.saving.set(false);
        this.invited.set(user);
        this.userInvited.emit(user);
      },
      error: (err: unknown) => {
        this.saving.set(false);
        reportFormError(
          this.notify,
          this.form,
          err,
          $localize`:@@admin.invite.failed:Could not invite the user`,
        );
      },
    });
  }
}
