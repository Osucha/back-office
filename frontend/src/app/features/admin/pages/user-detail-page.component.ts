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
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { AdminApi } from '../../../core/api/admin.service';
import type {
  AuthMode,
  EffectivePermissions,
  Group,
  PermissionDefinition,
  Role,
  SessionSummary,
  UserDetail,
  UserProjectMembership,
} from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { IdentityService } from '../../../core/auth/identity.service';
import { roleName, roleOptions } from '../../../core/i18n/catalog-labels';
import { PermissionDescriptionPipe, RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { ReadonlyFormDirective } from '../../../core/forms/readonly-form.directive';
import { reportFormError } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ActorComponent } from '../../../core/ui/actor.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService, SECTION_LABELS } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { absoluteTime, firstName } from '../../../core/ui/relay/format';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { SortHeaderDirective } from '../../../core/ui/relay/sort-header.directive';
import { tableSort } from '../../../core/ui/relay/table-sort';
import { AdminNavComponent } from '../components/admin-nav.component';
import { AuthModePickerComponent } from '../components/auth-mode-picker.component';
import { EffectivePermissionsTableComponent } from '../components/effective-permissions-table.component';
import { OneTimeLinkComponent } from '../components/one-time-link.component';
import { StateChipComponent } from '../components/state-chip.component';
import {
  accountBadges,
  authModeConsequences,
  authModeName,
  authModeRemovesMethod,
  availableAuthModes,
  issueLinkConsequence,
  lockState,
  providerLabel,
  providerVia,
  resetMfaConsequence,
  statusBadge,
} from '../data/local-accounts';

type Busy = 'status' | 'save' | 'sessions' | 'auth' | 'link' | 'mfa' | 'unlock' | null;

interface IssuedLink {
  url: string;
  expiresAt: string | null;
  label: string;
}

/**
 * One user: header with status and sign-in badges, access (display name, groups, platform roles)
 * with a sticky action bar, sign-in and security (method, set-password link, MFA reset, unlock),
 * then effective permissions, project memberships and sessions.
 */
@Component({
  selector: 'app-user-detail-page',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzFormModule,
    NzInputModule,
    NzSelectModule,
    NzTableModule,
    NzTabsModule,
    NzTooltipModule,
    ActorComponent,
    ReadonlyFormDirective,
    ActionBarComponent,
    AdminNavComponent,
    AuthModePickerComponent,
    EffectivePermissionsTableComponent,
    EmptyComponent,
    OneTimeLinkComponent,
    PageHeaderComponent,
    SkeletonComponent,
    SortHeaderDirective,
    StateChipComponent,
    PermissionDescriptionPipe,
    RoleNamePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      @if (!user()) {
        @if (notFound()) {
          <bo-empty
            title="This user could not be loaded."
            i18n-title="@@admin.userDetail.notFound"
            detail="It may have been removed, or you lack access."
            i18n-detail="@@admin.userDetail.notFoundDetail"
            actionLabel="Back to users"
            i18n-actionLabel="@@admin.userDetail.backToUsers"
            [primary]="false"
            (action)="back()"
          />
        } @else {
          <bo-skeleton
            variant="detail"
            label="Loading user"
            i18n-label="@@admin.userDetail.loading"
          />
        }
      } @else if (user(); as u) {
        <bo-page-header
          help="admin.users"
          [title]="u.displayName || u.email || text.user"
          [subtitle]="u.email ?? null"
          [menu]="menu()"
        >
          <ng-container boHeaderStatus>
            <app-state-chip
              [tone]="status().tone"
              [label]="status().label"
              [hint]="status().hint"
            />
            @for (b of badges(); track b.id) {
              <app-state-chip [tone]="b.tone" [label]="b.label" [hint]="b.hint" />
            }
            @if (isSelf()) {
              <span class="you" i18n="@@admin.userDetail.you">you</span>
            }
          </ng-container>
          <div boHeaderMeta class="meta-line">
            @if (u.lastLoginAt) {
              <span [attr.title]="abs(u.lastLoginAt)" i18n="@@admin.userDetail.lastSignIn"
                >Last sign-in {{ ago(u.lastLoginAt) }}</span
              >
            } @else {
              <span [attr.title]="abs(u.lastLoginAt)" i18n="@@admin.userDetail.lastSignInNever"
                >Last sign-in never</span
              >
            }
            <span aria-hidden="true">·</span>
            <span [attr.title]="abs(u.createdAt)" i18n="@@admin.userDetail.created"
              >Created {{ ago(u.createdAt) }}</span
            >
            @if (u.createdBy) {
              <span i18n="@@admin.userDetail.createdBy"
                >by <app-actor [actor]="u.createdBy"
              /></span>
            }
          </div>
          @if (canManage()) {
            @if (u.status === 'DEACTIVATED') {
              <button
                boHeaderActions
                nz-button
                nzType="primary"
                type="button"
                [nzLoading]="busy() === 'status'"
                (click)="activate()"
                i18n="@@admin.userDetail.activate"
              >
                Activate user
              </button>
            } @else {
              <button
                boHeaderActions
                nz-button
                nzDanger
                type="button"
                [disabled]="isSelf()"
                nz-tooltip
                [nzTooltipTitle]="isSelf() ? text.cannotDeactivateSelf : ''"
                [nzLoading]="busy() === 'status'"
                (click)="deactivate()"
                i18n="@@admin.userDetail.deactivate"
              >
                Deactivate user
              </button>
            }
          }
        </bo-page-header>

        <div class="grid">
          <section class="card" aria-labelledby="sec-access">
            <header class="card-head">
              <h2 id="sec-access" i18n="@@admin.userDetail.access">Access</h2>
              @if (!canManage()) {
                <span class="muted small" i18n="@@admin.userDetail.readOnly">read-only</span>
              }
            </header>
            <form
              nz-form
              nzLayout="vertical"
              class="card-body"
              [formGroup]="form"
              [boReadonly]="!canManage()"
              (ngSubmit)="save()"
            >
              <nz-form-item>
                <nz-form-label nzFor="u-name" i18n="@@admin.userDetail.displayName"
                  >Display name</nz-form-label
                >
                <nz-form-control [nzErrorTip]="serverTip">
                  <input nz-input id="u-name" formControlName="displayName" />
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="u-groups" i18n="@@admin.userDetail.groups"
                  >Groups</nz-form-label
                >
                <nz-form-control [nzErrorTip]="serverTip">
                  <nz-select
                    nzId="u-groups"
                    nzMode="multiple"
                    formControlName="groupIds"
                    nzPlaceHolder="None"
                    i18n-nzPlaceHolder="@@admin.userDetail.groupsNone"
                  >
                    @for (g of groups(); track g.id) {
                      <nz-option [nzValue]="g.id" [nzLabel]="g.name ?? ''" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label nzFor="u-roles" i18n="@@admin.userDetail.roles"
                  >Platform roles</nz-form-label
                >
                <nz-form-control
                  [nzErrorTip]="serverTip"
                  nzExtra="Assigned to this user, on top of the system defaults and the groups."
                  i18n-nzExtra="@@admin.userDetail.rolesExtra"
                >
                  <nz-select
                    nzId="u-roles"
                    nzMode="multiple"
                    formControlName="platformRoles"
                    nzPlaceHolder="None"
                    i18n-nzPlaceHolder="@@admin.userDetail.rolesNone"
                  >
                    @for (r of roleOptions(); track r.key) {
                      <nz-option [nzValue]="r.key" [nzLabel]="r.label" />
                    }
                  </nz-select>
                </nz-form-control>
              </nz-form-item>
              <ng-template #serverTip let-control>{{
                control.errors?.server || text.invalidValue
              }}</ng-template>
              @if (u.issuer || u.subject || currentMode() !== 'LOCAL') {
                <dl class="facts">
                  <div>
                    <dt i18n="@@admin.userDetail.issuer">Identity provider</dt>
                    <dd class="mono">{{ u.issuer || '-' }}</dd>
                  </div>
                  <div>
                    <dt i18n="@@admin.userDetail.subject">Subject</dt>
                    <dd [class.mono]="!!u.subject" [class.muted]="!u.subject">
                      {{ u.subject || notSignedInYet() }}
                    </dd>
                  </div>
                </dl>
              }
            </form>
            @if (canManage()) {
              <bo-action-bar
                class="card-foot"
                [sticky]="false"
                [dirty]="dirty()"
                [saving]="busy() === 'save'"
                saveLabel="Save access"
                i18n-saveLabel="@@admin.userDetail.saveAccess"
                (save)="save()"
              >
                <button
                  nz-button
                  type="button"
                  [disabled]="!dirty()"
                  (click)="resetForm()"
                  i18n="@@admin.userDetail.discard"
                >
                  Discard changes
                </button>
              </bo-action-bar>
            }
          </section>

          <section class="card" aria-labelledby="sec-security">
            <header class="card-head">
              <h2 id="sec-security" i18n="@@admin.userDetail.security">Sign-in and security</h2>
            </header>
            <div class="card-body">
              <fieldset class="field">
                <legend i18n="@@admin.userDetail.method">Sign-in method</legend>
                <app-auth-mode-picker
                  name="u-auth"
                  [modes]="modes()"
                  [value]="pendingMode()"
                  [current]="u.authMode ?? 'OIDC'"
                  [provider]="provider()"
                  [disabled]="!canManage() || busy() === 'auth'"
                  (valueChange)="pendingMode.set($event)"
                />
                @if (switching()) {
                  <div class="consequences" role="status">
                    <div class="c-title" i18n="@@admin.userDetail.switching">
                      Switching {{ name() }} to {{ modeName(pendingMode()) }}
                    </div>
                    <ul>
                      @for (c of consequences(); track $index) {
                        <li>{{ c }}</li>
                      }
                    </ul>
                    <div class="c-actions">
                      <button
                        nz-button
                        type="button"
                        (click)="pendingMode.set(currentMode())"
                        i18n="@@admin.userDetail.keepMode"
                      >
                        Keep {{ modeName(currentMode()) }}
                      </button>
                      <button
                        nz-button
                        nzType="primary"
                        type="button"
                        [nzLoading]="busy() === 'auth'"
                        (click)="applyMode()"
                        i18n="@@admin.userDetail.switchTo"
                      >
                        Switch to {{ modeName(pendingMode()) }}
                      </button>
                    </div>
                  </div>
                }
              </fieldset>

              <ul
                class="rows"
                aria-label="Credentials"
                i18n-aria-label="@@admin.userDetail.credentials"
              >
                <li>
                  <span class="r-label" i18n="@@admin.userDetail.password">Password</span>
                  <span class="r-value">
                    @if (!localAllowed()) {
                      <span class="muted" i18n="@@admin.userDetail.passwordNone"
                        >None; signs in through {{ providerVia() }}</span
                      >
                    } @else if (u.hasPassword) {
                      <app-state-chip
                        size="sm"
                        tone="done"
                        label="set"
                        i18n-label="@@admin.userDetail.passwordSet"
                      />
                      @if (u.mustChangePassword) {
                        <app-state-chip
                          size="sm"
                          tone="you"
                          label="must change at next sign-in"
                          i18n-label="@@admin.userDetail.passwordMustChange"
                        />
                      }
                    } @else {
                      <app-state-chip
                        size="sm"
                        tone="idle"
                        label="not set yet"
                        i18n-label="@@admin.userDetail.passwordNotSet"
                        hint="Set with a one-time link"
                        i18n-hint="@@admin.userDetail.passwordNotSetHint"
                      />
                    }
                  </span>
                  @if (canManage() && localAllowed()) {
                    <button
                      nz-button
                      nzSize="small"
                      type="button"
                      [nzLoading]="busy() === 'link'"
                      (click)="issueLink()"
                      i18n="@@admin.userDetail.issueLink"
                    >
                      Issue set-password link
                    </button>
                  }
                </li>
                <li>
                  <span class="r-label">MFA</span>
                  <span class="r-value">
                    @if (u.mfaEnabled) {
                      <app-state-chip
                        size="sm"
                        tone="done"
                        label="on"
                        i18n-label="@@admin.userDetail.mfaOn"
                        hint="Authenticator app enrolled"
                        i18n-hint="@@admin.userDetail.mfaOnHint"
                      />
                    } @else if (localAllowed()) {
                      <app-state-chip
                        size="sm"
                        tone="idle"
                        label="off"
                        i18n-label="@@admin.userDetail.mfaOff"
                      />
                      @if (mfaRequired()) {
                        <span class="muted small" i18n="@@admin.userDetail.mfaRequired"
                          >required: enrols at the next sign-in</span
                        >
                      }
                    } @else {
                      <span class="muted" i18n="@@admin.userDetail.mfaHandledBy"
                        >Handled by {{ provider() }}</span
                      >
                    }
                  </span>
                  @if (canManage() && u.mfaEnabled) {
                    <button
                      nz-button
                      nzSize="small"
                      nzDanger
                      type="button"
                      [nzLoading]="busy() === 'mfa'"
                      (click)="resetMfa()"
                      i18n="@@admin.userDetail.resetMfa"
                    >
                      Reset MFA
                    </button>
                  }
                </li>
                @if (localAllowed() || lock().locked) {
                  <li>
                    <span class="r-label" i18n="@@admin.userDetail.lockout">Lockout</span>
                    <span class="r-value">
                      @if (lock().locked) {
                        <app-state-chip
                          size="sm"
                          tone="blocked"
                          [label]="lock().label"
                          [hint]="lockHint(u.lockedUntil)"
                        />
                      } @else {
                        <span class="muted" i18n="@@admin.userDetail.notLocked">Not locked</span>
                      }
                    </span>
                    @if (canManage() && lock().locked) {
                      <button
                        nz-button
                        nzSize="small"
                        type="button"
                        [nzLoading]="busy() === 'unlock'"
                        (click)="unlock()"
                        i18n="@@admin.userDetail.unlock"
                      >
                        Unlock account
                      </button>
                    }
                  </li>
                }
              </ul>

              @if (issued(); as l) {
                <app-one-time-link
                  class="link"
                  [label]="l.label"
                  [url]="l.url"
                  [expiresAt]="l.expiresAt"
                  [name]="name()"
                />
              } @else if (u.status === 'INVITED' && u.signInUrl && !localAllowed()) {
                <app-one-time-link
                  class="link"
                  label="Sign-in link"
                  i18n-label="@@admin.userDetail.signInLink"
                  [url]="u.signInUrl"
                  [name]="name()"
                  [hint]="signInHint()"
                />
              }
            </div>
          </section>
        </div>

        <nz-tabs class="tabs" [nzAnimated]="false">
          <nz-tab nzTitle="Effective permissions" i18n-nzTitle="@@admin.userDetail.tabEffective">
            @if (effectiveError()) {
              <bo-empty
                tone="quiet"
                title="The effective permissions could not be loaded."
                i18n-title="@@admin.userDetail.effectiveError"
              />
            } @else {
              <app-effective-permissions-table [data]="effective()" [catalogue]="catalogue()" />
            }
          </nz-tab>
          <nz-tab [nzTitle]="membershipsTab(u.projectMemberships?.length ?? 0)">
            @if (!u.projectMemberships?.length) {
              <bo-empty
                tone="quiet"
                [title]="noProjects()"
                detail="Memberships are managed on each project's Members tab."
                i18n-detail="@@admin.userDetail.membershipsManagedEmpty"
              />
            } @else {
              <nz-table [nzData]="memberships()" [nzShowPagination]="false" nzSize="small">
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="membershipSort"
                      boSortKey="project"
                      nzWidth="136px"
                      i18n="@@admin.userDetail.colProject"
                    >
                      Project
                    </th>
                    <th
                      nzShowSort
                      [boSort]="membershipSort"
                      boSortKey="role"
                      nzWidth="156px"
                      i18n="@@admin.userDetail.colRole"
                    >
                      Role
                    </th>
                    <th
                      nzShowSort
                      [boSort]="membershipSort"
                      boSortKey="via"
                      nzWidth="176px"
                      i18n="@@admin.userDetail.colVia"
                    >
                      Via
                    </th>
                    <th
                      nzShowSort
                      [boSort]="membershipSort"
                      boSortKey="permissions"
                      i18n="@@admin.userDetail.colPermissions"
                    >
                      Effective project permissions
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (m of memberships(); track $index) {
                    <tr>
                      <td>
                        <a
                          class="mono"
                          [routerLink]="['/projects', m.projectId]"
                          [queryParams]="{ tab: 'members' }"
                          >{{ m.projectKey }}</a
                        >
                      </td>
                      <td>
                        <span class="tag" [title]="m.role ?? ''">{{ m.role | roleName }}</span>
                      </td>
                      <td class="ink-2">
                        @if (m.viaGroup) {
                          <span i18n="@@admin.userDetail.viaGroup">Group {{ m.viaGroup }}</span>
                        } @else {
                          <span i18n="@@admin.userDetail.direct">Direct</span>
                        }
                      </td>
                      <td>
                        <div class="perms">
                          @for (p of projectPermissions(m.projectId); track p) {
                            <span class="perm" [title]="p | permissionDescription">{{ p }}</span>
                          }
                        </div>
                      </td>
                    </tr>
                  }
                </tbody>
              </nz-table>
              <p class="muted small note" i18n="@@admin.userDetail.membershipsManaged">
                Memberships are managed on each project's Members tab.
              </p>
            }
          </nz-tab>
          <nz-tab [nzTitle]="sessionsTab()">
            @if (!sessions().length) {
              <bo-empty tone="quiet" [title]="noSessions()" />
            } @else {
              @if (canManage()) {
                <div class="toolbar">
                  <button
                    nz-button
                    nzDanger
                    type="button"
                    [nzLoading]="busy() === 'sessions'"
                    (click)="terminateSessions()"
                    i18n="@@admin.userDetail.endSessions"
                  >
                    End all sessions
                  </button>
                </div>
              }
              <nz-table [nzData]="sortedSessions()" [nzShowPagination]="false" nzSize="small">
                <thead>
                  <tr>
                    <th
                      nzShowSort
                      [boSort]="sessionSort"
                      boSortKey="session"
                      nzWidth="156px"
                      i18n="@@admin.userDetail.colSession"
                    >
                      Session
                    </th>
                    <th
                      nzShowSort
                      [boSort]="sessionSort"
                      boSortKey="started"
                      nzWidth="170px"
                      i18n="@@admin.userDetail.colStarted"
                    >
                      Started
                    </th>
                    <th
                      nzShowSort
                      [boSort]="sessionSort"
                      boSortKey="lastAccess"
                      nzWidth="136px"
                      i18n="@@admin.userDetail.colLastAccess"
                    >
                      Last access
                    </th>
                    <th
                      nzShowSort
                      [boSort]="sessionSort"
                      boSortKey="ip"
                      nzWidth="140px"
                      i18n="@@admin.userDetail.colIp"
                    >
                      IP
                    </th>
                    <th
                      nzShowSort
                      [boSort]="sessionSort"
                      boSortKey="browser"
                      i18n="@@admin.userDetail.colBrowser"
                    >
                      Browser
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of sortedSessions(); track s.id) {
                    <tr>
                      <td class="mono">{{ (s.id ?? '').slice(0, 12) }}</td>
                      <td [attr.title]="abs(s.createdAt)">{{ ago(s.createdAt) }}</td>
                      <td [attr.title]="abs(s.lastAccessAt)">{{ ago(s.lastAccessAt) }}</td>
                      <td class="mono">{{ s.sourceIp || '-' }}</td>
                      <td class="ua" [attr.title]="s.userAgent">{{ s.userAgent || '-' }}</td>
                    </tr>
                  }
                </tbody>
              </nz-table>
            }
          </nz-tab>
        </nz-tabs>
      }
    </div>
  `,
  styles: [
    `
      .you {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-accent);
      }
      .meta-line {
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(min(460px, 100%), 1fr));
        gap: 16px;
        align-items: start;
      }
      .card {
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        overflow: hidden;
      }
      .card-head {
        display: flex;
        align-items: baseline;
        gap: 8px;
        padding: 14px 16px 0;
      }
      h2 {
        margin: 0;
        font-size: 14px;
        font-weight: 600;
      }
      .card-body {
        padding: 12px 16px 16px;
      }
      .card-foot {
        border-radius: 0;
      }
      .small {
        font-size: 12px;
      }
      .field {
        margin: 0 0 16px;
        padding: 0;
        border: 0;
        min-width: 0;
      }
      legend {
        font-size: 13px;
        font-weight: 500;
        margin-bottom: 8px;
        padding: 0;
        color: var(--bo-ink);
        border: 0;
        width: auto;
        line-height: inherit;
      }
      .consequences {
        margin-top: 10px;
        padding: 10px 12px;
        border-radius: var(--bo-radius-card);
        background: var(--bo-you-soft);
        box-shadow: inset 0 0 0 1px var(--bo-you);
        font-size: 12.5px;
        color: var(--bo-ink);
      }
      .c-title {
        font-weight: 600;
        margin-bottom: 4px;
      }
      .consequences ul {
        margin: 0 0 10px;
        padding-left: 18px;
      }
      .c-actions {
        display: flex;
        gap: 8px;
        justify-content: flex-end;
        flex-wrap: wrap;
      }
      .rows {
        list-style: none;
        margin: 0;
        padding: 0;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .rows li {
        display: flex;
        align-items: center;
        gap: 12px;
        min-height: 44px;
        padding: 6px 12px;
        border-bottom: 1px solid var(--bo-line);
        font-size: 13px;
      }
      .rows li:last-child {
        border-bottom: 0;
      }
      .r-label {
        flex: 0 0 80px;
        color: var(--bo-ink-3);
      }
      .r-value {
        flex: 1;
        display: flex;
        align-items: center;
        gap: 6px;
        flex-wrap: wrap;
        min-width: 0;
      }
      .link {
        display: block;
        margin-top: 12px;
      }
      .facts {
        margin: 4px 0 0;
        padding-top: 12px;
        border-top: 1px solid var(--bo-line);
        display: grid;
        gap: 4px;
        font-size: 12.5px;
      }
      .facts > div {
        display: grid;
        grid-template-columns: 130px minmax(0, 1fr);
        gap: 12px;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        overflow-wrap: anywhere;
      }
      .tabs {
        margin-top: 24px;
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
      }
      .perms {
        display: flex;
        flex-wrap: wrap;
        gap: 4px;
      }
      .perm {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        padding: 1px 6px;
        border-radius: 4px;
        background: var(--bo-surface-2);
        box-shadow: inset 0 0 0 1px var(--bo-line);
        color: var(--bo-ink-2);
      }
      .note {
        margin-top: 8px;
      }
      .ua {
        max-width: 360px;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class UserDetailPageComponent implements HasUnsavedChanges {
  private readonly api = inject(AdminApi);
  private readonly fb = inject(FormBuilder);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly confirm = inject(ConfirmService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly identity = inject(IdentityService);
  private readonly authConfig = inject(AuthConfigService);
  private readonly breadcrumbs = inject(BreadcrumbService);

  readonly userId = input.required<string>();

  readonly user = signal<UserDetail | null>(null);
  readonly notFound = signal(false);
  readonly groups = signal<Group[]>([]);
  readonly platformRoles = signal<Role[]>([]);
  readonly catalogue = signal<PermissionDefinition[]>([]);
  readonly effective = signal<EffectivePermissions | null>(null);
  readonly effectiveError = signal(false);
  readonly sessions = signal<SessionSummary[]>([]);
  readonly busy = signal<Busy>(null);
  readonly issued = signal<IssuedLink | null>(null);
  readonly mfaRequired = signal(false);
  readonly pendingMode = signal<AuthMode>('OIDC');

  readonly text = {
    user: $localize`:@@admin.userDetail.user:User`,
    cannotDeactivateSelf: $localize`:@@admin.userDetail.cannotDeactivateSelf:You cannot deactivate yourself`,
    invalidValue: $localize`:@@admin.userDetail.invalidValue:Invalid value`,
  };

  readonly canManage = computed(() => this.identity.can('admin.users.manage'));
  readonly isSelf = computed(() => this.user()?.id === this.identity.user()?.id);
  readonly name = computed(() => {
    const u = this.user();
    return firstName(u?.displayName, u?.email) || $localize`:@@admin.userDetail.theUser:the user`;
  });
  readonly provider = computed(() => providerLabel(this.authConfig.config()?.providerName));
  /** The provider after "through" (see `providerVia`). */
  readonly providerVia = computed(() => providerVia(this.provider()));
  readonly notSignedInYet = computed(() => {
    const provider = this.providerVia();
    return $localize`:@@admin.userDetail.notSignedInYet:Not signed in through ${provider}:provider: yet`;
  });
  readonly signInHint = computed(() => {
    const name = this.name();
    const provider = this.providerVia();
    return $localize`:@@admin.userDetail.signInHint:Hand this link to ${name}:name:; the first sign-in through ${provider}:provider: activates the account.`;
  });
  readonly noProjects = computed(() => {
    const name = this.name();
    return $localize`:@@admin.userDetail.noProjects:${name}:name: is not a member of any project.`;
  });
  readonly noSessions = computed(() => {
    const name = this.name();
    return $localize`:@@admin.userDetail.noSessions:${name}:name: has no active sessions.`;
  });
  /** Column sorting of the memberships (URL `?sort=`) and the sessions (`?sessionSort=`). */
  readonly membershipSort = tableSort<UserProjectMembership>({
    project: (m) => m.projectKey,
    role: (m) => (m.role ? roleName(m.role) : null),
    via: (m) => m.viaGroup,
    permissions: (m) => this.projectPermissions(m.projectId).join(', '),
  });
  readonly memberships = computed(() =>
    this.membershipSort.apply(this.user()?.projectMemberships ?? []),
  );
  readonly sessionSort = tableSort<SessionSummary>(
    {
      session: (s) => s.id,
      started: (s) => s.createdAt,
      lastAccess: (s) => s.lastAccessAt,
      ip: (s) => s.sourceIp,
      browser: (s) => s.userAgent,
    },
    { param: 'sessionSort' },
  );
  readonly sortedSessions = computed(() => this.sessionSort.apply(this.sessions()));
  readonly sessionsTab = computed(() => {
    const count = this.sessions().length;
    return $localize`:@@admin.userDetail.tabSessions:Sessions · ${count}:count:`;
  });
  readonly currentMode = computed<AuthMode>(() => this.user()?.authMode ?? 'OIDC');
  readonly localAllowed = computed(() => this.currentMode() !== 'OIDC');
  readonly modes = computed(() =>
    availableAuthModes(this.authConfig.config()?.methods, this.currentMode()),
  );
  readonly switching = computed(() => !!this.user() && this.pendingMode() !== this.currentMode());
  readonly consequences = computed(() =>
    authModeConsequences(this.currentMode(), this.pendingMode(), {
      name: this.name(),
      provider: this.provider(),
      hasPassword: !!this.user()?.hasPassword,
    }),
  );
  readonly status = computed(() => statusBadge(this.user()?.status));
  readonly badges = computed(() => (this.user() ? accountBadges(this.user()!) : []));
  readonly lock = computed(() => lockState(this.user()?.lockedUntil));
  readonly menu = computed<PageAction[]>(() =>
    this.canManage() && this.sessions().length
      ? [
          {
            label: $localize`:@@admin.userDetail.menuEndSessions:End all sessions`,
            icon: 'logout',
            danger: true,
            run: () => this.terminateSessions(),
          },
        ]
      : [],
  );
  /** Assigned roles stay selectable even when the role list is not readable. */
  readonly roleOptions = computed(() =>
    roleOptions(this.platformRoles(), this.user()?.platformRoles ?? []),
  );

  readonly form = this.fb.nonNullable.group({
    displayName: [''],
    groupIds: this.fb.nonNullable.control<string[]>([]),
    platformRoles: this.fb.nonNullable.control<string[]>([]),
  });
  private readonly formState = toSignal(this.form.valueChanges, { initialValue: null });
  readonly dirty = computed(() => {
    this.formState();
    return this.form.dirty;
  });

  constructor() {
    this.authConfig.load().subscribe();
    this.api.groups().subscribe({ next: (g) => this.groups.set(g), error: () => undefined });
    this.api
      .permissions()
      .subscribe({ next: (c) => this.catalogue.set(c), error: () => undefined });
    this.api.roles('PLATFORM', { silent: true }).subscribe({
      next: (r) => this.platformRoles.set(r),
      error: () => undefined,
    });
    if (this.identity.can('admin.settings.manage'))
      this.api.settings().subscribe({
        next: (s) => this.mfaRequired.set(!!s.mfaRequiredForLocal),
        error: () => undefined,
      });
    effect(() => {
      const id = this.userId();
      untracked(() => this.load(id));
    });
    effect(() => {
      const u = this.user();
      untracked(() =>
        this.breadcrumbs.set([
          { label: SECTION_LABELS['admin'], link: '/admin' },
          { label: $localize`:@@admin.userDetail.crumbUsers:Users`, link: '/admin/users' },
          { label: u?.displayName || u?.email || this.text.user },
        ]),
      );
    });
  }

  ago = timeAgo;
  abs = absoluteTime;

  modeName(m: AuthMode): string {
    return authModeName(m, this.provider());
  }

  membershipsTab(count: number): string {
    return $localize`:@@admin.userDetail.tabMemberships:Project memberships · ${count}:count:`;
  }

  lockHint(lockedUntil: string | null | undefined): string {
    const until = this.abs(lockedUntil);
    return $localize`:@@admin.userDetail.lockHint:Too many failed sign-ins; ${until}:until:`;
  }

  hasUnsavedChanges(): boolean {
    return this.form.dirty && this.canManage();
  }

  back(): void {
    history.back();
  }

  load(id = this.userId()): void {
    this.issued.set(null);
    this.notFound.set(false);
    this.api.user(id).subscribe({
      next: (u) => this.setUser(u),
      error: () => this.notFound.set(true),
    });
    this.loadEffective(id);
    this.api.sessions(id).subscribe({ next: (s) => this.sessions.set(s), error: () => undefined });
  }

  private loadEffective(id: string): void {
    this.effectiveError.set(false);
    this.api.effectivePermissions(id).subscribe({
      next: (e) => this.effective.set(e),
      error: () => this.effectiveError.set(true),
    });
  }

  projectPermissions(projectId: string | undefined): string[] {
    return this.effective()?.projects?.find((p) => p.projectId === projectId)?.permissions ?? [];
  }

  resetForm(): void {
    const u = this.user();
    if (u) this.setUser(u);
  }

  save(): void {
    const v = this.form.getRawValue();
    this.busy.set('save');
    this.pendingSaves
      .track(
        this.api.updateUser(this.userId(), {
          displayName: v.displayName.trim(),
          groupIds: v.groupIds,
          platformRoles: v.platformRoles,
        }),
      )
      .subscribe({
        next: (u) => {
          this.busy.set(null);
          this.setUser(u);
          this.message.success($localize`:@@admin.userDetail.saved:Access saved`);
          this.loadEffective(u.id!);
          if (this.isSelf()) void this.identity.refresh();
        },
        error: (err: unknown) => {
          this.busy.set(null);
          reportFormError(
            this.notify,
            this.form,
            err,
            $localize`:@@admin.userDetail.saveFailed:Could not save the user`,
          );
        },
      });
  }

  // ---- sign-in and security

  async applyMode(): Promise<void> {
    const to = this.pendingMode();
    const name = this.name();
    const mode = this.modeName(to);
    const ok = await this.confirm.confirm({
      title: $localize`:@@admin.userDetail.modeConfirmTitle:Change how ${name}:name: signs in?`,
      content: this.consequences().join(' '),
      okText: $localize`:@@admin.userDetail.modeConfirmOk:Switch to ${mode}:mode:`,
      danger: authModeRemovesMethod(this.currentMode(), to),
    });
    if (!ok) return;
    this.busy.set('auth');
    this.api.setAuthMode(this.userId(), to).subscribe({
      next: (u) => {
        this.busy.set(null);
        this.setUser(u, { keepIssued: true });
        this.issued.set(
          u.setPasswordUrl
            ? { url: u.setPasswordUrl, expiresAt: null, label: this.setPasswordLabel() }
            : null,
        );
        const who = this.name();
        const now = this.modeName(to);
        this.message.success(
          $localize`:@@admin.userDetail.modeChanged:${who}:name: now signs in: ${now}:mode:`,
        );
        this.api.sessions(this.userId()).subscribe({
          next: (s) => this.sessions.set(s),
          error: () => undefined,
        });
      },
      error: (err: unknown) => {
        this.busy.set(null);
        this.pendingMode.set(this.currentMode());
        this.notify.error(
          $localize`:@@admin.userDetail.modeFailed:Could not change the sign-in method`,
          problemText(err),
        );
      },
    });
  }

  private setPasswordLabel(): string {
    return $localize`:@@admin.userDetail.setPasswordLink:Set-password link`;
  }

  async issueLink(): Promise<void> {
    const u = this.user();
    if (!u?.id) return;
    const name = this.name();
    const ok = await this.confirm.confirm({
      title: $localize`:@@admin.userDetail.issueConfirmTitle:Issue a set-password link for ${name}:name:?`,
      content: issueLinkConsequence(name, !!u.hasPassword),
      okText: $localize`:@@admin.userDetail.issueConfirmOk:Issue set-password link`,
      danger: !!u.hasPassword,
    });
    if (!ok) return;
    this.busy.set('link');
    this.api.issuePasswordReset(u.id).subscribe({
      next: (link) => {
        this.busy.set(null);
        if (link.url)
          this.issued.set({
            url: link.url,
            expiresAt: link.expiresAt ?? null,
            label: this.setPasswordLabel(),
          });
        this.sessions.set([]);
        this.reloadUser();
      },
      error: () => this.busy.set(null),
    });
  }

  async resetMfa(): Promise<void> {
    const u = this.user();
    if (!u?.id) return;
    const name = this.name();
    const ok = await this.confirm.confirm({
      title: $localize`:@@admin.userDetail.resetMfaTitle:Reset MFA for ${name}:name:?`,
      content: resetMfaConsequence(name, this.mfaRequired()),
      okText: $localize`:@@admin.userDetail.resetMfaOk:Reset MFA`,
      danger: true,
    });
    if (!ok) return;
    this.busy.set('mfa');
    this.api.resetMfa(u.id).subscribe({
      next: () => {
        this.busy.set(null);
        const who = this.name();
        this.message.success($localize`:@@admin.userDetail.mfaReset:MFA reset for ${who}:name:`);
        this.reloadUser();
      },
      error: () => this.busy.set(null),
    });
  }

  unlock(): void {
    const u = this.user();
    if (!u?.id) return;
    this.busy.set('unlock');
    this.api.unlock(u.id).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.setUser(updated, { keepIssued: true });
        const who = this.name();
        this.message.success(
          $localize`:@@admin.userDetail.unlocked:${who}:name: can sign in again`,
        );
      },
      error: () => this.busy.set(null),
    });
  }

  // ---- status and sessions

  activate(): void {
    this.busy.set('status');
    this.api.activate(this.userId()).subscribe({
      next: (u) => {
        this.busy.set(null);
        this.setUser(u, { keepIssued: true });
        const who = this.name();
        this.message.success($localize`:@@admin.userDetail.activated:${who}:name: is active`);
      },
      error: () => this.busy.set(null),
    });
  }

  async deactivate(): Promise<void> {
    const u = this.user();
    if (!u || this.isSelf()) return;
    const user = u.displayName || u.email;
    const name = this.name();
    const ok = await this.confirm.danger(
      $localize`:@@admin.userDetail.deactivateTitle:Deactivate ${user}:user:?`,
      $localize`:@@admin.userDetail.deactivateContent:${name}:name: is signed out everywhere and cannot sign in; personal access tokens are revoked. You can activate the account again later.`,
      $localize`:@@admin.userDetail.deactivateOk:Deactivate user`,
    );
    if (!ok) return;
    this.busy.set('status');
    this.api.deactivate(u.id!).subscribe({
      next: (updated) => {
        this.busy.set(null);
        this.setUser(updated, { keepIssued: true });
        this.sessions.set([]);
        const who = this.name();
        this.message.success(
          $localize`:@@admin.userDetail.deactivated:${who}:name: is deactivated`,
        );
      },
      error: () => this.busy.set(null),
    });
  }

  async terminateSessions(): Promise<void> {
    const name = this.name();
    const ok = await this.confirm.danger(
      $localize`:@@admin.userDetail.endSessionsTitle:End all sessions of ${name}:name:?`,
      this.isSelf()
        ? $localize`:@@admin.userDetail.endSessionsSelf:This includes your current session: you will have to sign in again.`
        : $localize`:@@admin.userDetail.endSessionsOther:${name}:name: has to sign in again on every device.`,
      $localize`:@@admin.userDetail.endSessionsOk:End all sessions`,
    );
    if (!ok) return;
    this.busy.set('sessions');
    this.api.terminateSessions(this.userId()).subscribe({
      next: () => {
        this.busy.set(null);
        this.sessions.set([]);
        this.message.success($localize`:@@admin.userDetail.sessionsEnded:Sessions ended`);
      },
      error: () => this.busy.set(null),
    });
  }

  private reloadUser(): void {
    this.api.user(this.userId()).subscribe({
      next: (u) => this.setUser(u, { keepIssued: true }),
      error: () => undefined,
    });
  }

  private setUser(u: UserDetail, opts: { keepIssued?: boolean } = {}): void {
    if (!opts.keepIssued) this.issued.set(null);
    this.user.set(u);
    this.pendingMode.set(u.authMode ?? 'OIDC');
    // Keep unsaved access edits when only the sign-in state changed.
    if (opts.keepIssued && this.form.dirty) return;
    this.form.reset({
      displayName: u.displayName ?? '',
      groupIds: (u.groups ?? []).map((g) => g.id!).filter(Boolean),
      platformRoles: u.platformRoles ?? [],
    });
  }
}

function problemText(err: unknown): string {
  const e = err as { error?: { detail?: string; title?: string } } | null;
  return (
    e?.error?.detail ||
    e?.error?.title ||
    $localize`:@@admin.userDetail.tryAgain:Try again, or reload the page.`
  );
}
