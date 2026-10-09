import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { firstValueFrom } from 'rxjs';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import type { MySecurity } from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  AUTH_FORM_STYLES,
  MfaEnrolmentComponent,
  NewPasswordComponent,
  PasswordFieldComponent,
  RecoveryCodesComponent,
  passwordErrorOf,
} from '../../auth/public-api';
import {
  allowsLocal,
  disableMfaBlocked,
  methodWord,
  recoveryCodesState,
  signInMethodText,
} from '../data/security-view';
import { PasswordConfirmDialogComponent } from './password-confirm-dialog.component';

type Panel = 'none' | 'change' | 'enrol' | 'codes';
type Dialog = 'disable' | 'regenerate' | null;

/**
 * Account security (`/me/security`): sign-in method, password, two-factor authentication (set up,
 * new recovery codes, turn off; sensitive steps ask for the password) and recent sign-ins. For
 * accounts that sign in with the identity provider only, it points to the provider's account page.
 */
@Component({
  selector: 'app-security-section',
  imports: [
    NzButtonModule,
    NzIconModule,
    BatonComponent,
    SidePanelComponent,
    SkeletonComponent,
    MfaEnrolmentComponent,
    NewPasswordComponent,
    PasswordFieldComponent,
    RecoveryCodesComponent,
    PasswordConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!sec()) {
      <bo-skeleton
        variant="list"
        [rows]="3"
        label="Loading security settings"
        i18n-label="@@account.security.loading"
      />
    } @else {
      <dl class="rows">
        <div class="row">
          <dt i18n="@@account.security.signInMethod">Sign-in method</dt>
          <dd>
            <span class="info">
              <span class="value">{{ method() }}</span>
              @if (sec()!.lastLoginAt) {
                @if (sec()!.lastLoginMethod) {
                  <span
                    class="meta"
                    [title]="abs(sec()!.lastLoginAt)"
                    i18n="@@account.security.lastSignInWith"
                    >Last sign-in {{ ago(sec()!.lastLoginAt) }} with
                    {{ word(sec()!.lastLoginMethod) }}</span
                  >
                } @else {
                  <span
                    class="meta"
                    [title]="abs(sec()!.lastLoginAt)"
                    i18n="@@account.security.lastSignIn"
                    >Last sign-in {{ ago(sec()!.lastLoginAt) }}</span
                  >
                }
              }
            </span>

            <div class="act">
              @if (!local() && accountUrl()) {
                <a nz-button [href]="accountUrl()" target="_blank" rel="noopener noreferrer">
                  <nz-icon nzType="export" />
                  <span i18n="@@account.security.openProviderAccount"
                    >Open {{ provider() }} account</span
                  >
                </a>
              }
            </div>
          </dd>
        </div>

        @if (local()) {
          <div class="row">
            <dt i18n="@@account.security.password">Password</dt>
            <dd>
              <span class="info">
                @if (sec()!.hasPassword) {
                  <span class="value" i18n="@@account.security.passwordSet">Set</span>
                } @else {
                  <span class="value" i18n="@@account.security.passwordNotSet">Not set</span>
                }
                @if (sec()!.passwordChangedAt) {
                  <span
                    class="meta"
                    [title]="abs(sec()!.passwordChangedAt)"
                    i18n="@@account.security.passwordChanged"
                    >Changed {{ ago(sec()!.passwordChangedAt) }}</span
                  >
                }
              </span>

              <div class="act">
                <button
                  nz-button
                  type="button"
                  (click)="openChange()"
                  i18n="@@account.security.changePassword"
                >
                  Change password
                </button>
              </div>
            </dd>
          </div>

          <div class="row">
            <dt i18n="@@account.security.mfa">Two-factor authentication</dt>
            <dd>
              <span class="info">
                @if (sec()!.mfaEnabled) {
                  <bo-baton
                    holder="done"
                    label="on"
                    i18n-label="@@account.security.mfaOn"
                    detail="authenticator app"
                    i18n-detail="@@account.security.mfaApp"
                    size="sm"
                  />
                  <span class="meta" [class.warn]="codesState().low">{{ codesState().text }}</span>
                } @else {
                  <bo-baton
                    [holder]="sec()!.mfaRequired ? 'you' : 'idle'"
                    [label]="sec()!.mfaRequired ? text.mfaRequired : text.mfaOff"
                    size="sm"
                  />
                  <span class="meta" i18n="@@account.security.mfaPitch"
                    >A code from your phone after your password keeps a stolen password
                    useless.</span
                  >
                }
              </span>

              <div class="act">
                @if (sec()!.mfaEnabled) {
                  <button
                    nz-button
                    type="button"
                    (click)="dialog.set('regenerate')"
                    i18n="@@account.security.newCodes"
                  >
                    Create new recovery codes
                  </button>
                  <button
                    nz-button
                    nzDanger
                    type="button"
                    [disabled]="!!blocked()"
                    [attr.title]="blocked()"
                    (click)="dialog.set('disable')"
                    i18n="@@account.security.turnOff"
                  >
                    Turn off
                  </button>
                } @else {
                  <button
                    nz-button
                    nzType="primary"
                    type="button"
                    (click)="panel.set('enrol')"
                    i18n="@@account.security.setUpMfa"
                  >
                    Set up two-factor authentication
                  </button>
                }
              </div>
            </dd>
          </div>
          @if (sec()!.mfaEnabled && blocked()) {
            <p class="note">{{ blocked() }}</p>
          }
        } @else {
          <div class="row">
            <dt i18n="@@account.security.passwordAndMfa">Password and two-factor</dt>
            <dd>
              <span class="info">
                <span class="meta" i18n="@@account.security.managedByProvider"
                  >Managed by {{ provider() }}. Back Office does not store a password for your
                  account.</span
                >
              </span>

              <div class="act"></div>
            </dd>
          </div>
        }
      </dl>

      <h3 class="sub" i18n="@@account.security.recentSignIns">Recent sign-ins</h3>
      @if (sec()!.recentLogins?.length) {
        <table class="logins">
          <thead>
            <tr>
              <th scope="col" i18n="@@account.security.colWhen">When</th>
              <th scope="col" i18n="@@account.security.colMethod">Method</th>
              <th scope="col" i18n="@@account.security.colResult">Result</th>
              <th scope="col" i18n="@@account.security.colFrom">From</th>
            </tr>
          </thead>
          <tbody>
            @for (l of sec()!.recentLogins; track $index) {
              <tr>
                <td class="num" [title]="abs(l.at)">{{ ago(l.at) }}</td>
                <td>{{ word(l.method) }}</td>
                <td>
                  <bo-baton
                    size="sm"
                    appearance="plain"
                    [holder]="l.result === 'SUCCESS' ? 'done' : 'blocked'"
                    [label]="l.result === 'SUCCESS' ? text.signedIn : text.failed"
                  />
                </td>
                <td class="mono">{{ l.sourceIp || '-' }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <p class="note" i18n="@@account.security.noSignIns">No sign-ins recorded yet.</p>
      }
    }

    <!-- change password -->
    <bo-side-panel
      [open]="panel() === 'change'"
      title="Change password"
      i18n-title="@@account.password.title"
      subtitle="Your other sessions are signed out when the password changes."
      i18n-subtitle="@@account.password.subtitle"
      storageKey="backoffice.panelWidth.security"
      [defaultWidth]="480"
      (closed)="panel.set('none')"
    >
      <form class="panel-form" (submit)="$event.preventDefault(); changePassword()" novalidate>
        <input type="email" name="username" autocomplete="username" [value]="email()" hidden />
        <app-password-field
          #current
          label="Current password"
          i18n-label="@@account.password.current"
          name="current-password"
          autocomplete="current-password"
          [error]="currentError()"
          [(value)]="currentPassword"
        />
        <app-new-password
          #next
          [email]="email()"
          [policy]="sec()?.policy"
          [serverError]="newError()"
          [(value)]="newPassword"
          [(repeat)]="repeat"
        />
        @if (formError()) {
          <p class="field-error">{{ formError() }}</p>
        }
      </form>
      <div boPanelFooter class="footer">
        <button
          nz-button
          type="button"
          (click)="panel.set('none')"
          i18n="@@account.password.cancel"
        >
          Cancel
        </button>
        <button
          nz-button
          nzType="primary"
          type="button"
          [nzLoading]="saving()"
          [disabled]="!currentPassword() || !next.valid()"
          (click)="changePassword()"
          i18n="@@account.password.submit"
        >
          Change password
        </button>
      </div>
    </bo-side-panel>

    <!-- enrol MFA, then the recovery codes -->
    <bo-side-panel
      [open]="panel() === 'enrol' || panel() === 'codes'"
      [title]="panel() === 'codes' ? text.codesTitle : text.enrolTitle"
      [subtitle]="panel() === 'codes' ? text.codesSubtitle : text.enrolSubtitle"
      storageKey="backoffice.panelWidth.security"
      [defaultWidth]="560"
      [closeOnEscape]="panel() !== 'codes'"
      (closed)="closeCodes()"
    >
      @if (panel() === 'enrol') {
        <app-mfa-enrolment (enrolled)="showCodes($event)" />
      } @else if (panel() === 'codes') {
        <app-recovery-codes
          [codes]="codes()"
          [email]="email()"
          doneLabel="Done, I saved them"
          i18n-doneLabel="@@account.mfa.codesDone"
          (done)="closeCodes()"
        />
      }
    </bo-side-panel>

    <app-password-confirm-dialog
      [open]="dialog() === 'regenerate'"
      title="Create new recovery codes?"
      i18n-title="@@account.mfa.regenerateTitle"
      consequence="Your current recovery codes stop working. You get 10 new codes to save."
      i18n-consequence="@@account.mfa.regenerateConsequence"
      confirmLabel="Create new codes"
      i18n-confirmLabel="@@account.mfa.regenerateConfirm"
      [busy]="dialogBusy()"
      [error]="dialogError()"
      (confirmed)="regenerate($event)"
      (dismissed)="closeDialog()"
    />
    <app-password-confirm-dialog
      [open]="dialog() === 'disable'"
      title="Turn off two-factor authentication?"
      i18n-title="@@account.mfa.disableTitle"
      consequence="Signing in will need only your password, and your recovery codes stop working."
      i18n-consequence="@@account.mfa.disableConsequence"
      confirmLabel="Turn off two-factor"
      i18n-confirmLabel="@@account.mfa.disableConfirm"
      [danger]="true"
      [busy]="dialogBusy()"
      [error]="dialogError()"
      (confirmed)="disable($event)"
      (dismissed)="closeDialog()"
    />
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      :host {
        display: block;
      }
      .rows {
        margin: 0;
      }
      .row {
        display: grid;
        grid-template-columns: 200px minmax(0, 1fr);
        gap: var(--bo-space-4);
        align-items: center;
        padding: var(--bo-space-3) 0;
        border-top: 1px solid var(--bo-line);
      }
      .row:first-child {
        border-top: 0;
        padding-top: 0;
      }
      dt {
        font-size: var(--bo-fs-dense);
        font-weight: 500;
        color: var(--bo-ink);
      }
      dd {
        margin: 0;
        display: flex;
        align-items: center;
        gap: var(--bo-space-4);
        min-width: 0;
      }
      .info {
        flex: 1;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 10px;
        min-width: 0;
      }
      .value {
        font-size: var(--bo-fs-body);
      }
      .meta {
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .meta.warn {
        color: var(--bo-you-ink);
        font-weight: 500;
      }
      .act {
        display: flex;
        gap: var(--bo-space-2);
        justify-content: flex-end;
        flex-wrap: wrap;
      }
      .note {
        margin: 0 0 var(--bo-space-2);
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .sub {
        margin: var(--bo-space-5) 0 var(--bo-space-2);
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .logins {
        width: 100%;
        border-collapse: collapse;
        font-size: var(--bo-fs-dense);
      }
      .logins th {
        text-align: left;
        font-weight: 500;
        color: var(--bo-ink-3);
        font-size: var(--bo-fs-meta);
        padding: 4px 8px 4px 0;
        border-bottom: 1px solid var(--bo-line);
      }
      .logins td {
        padding: 6px 8px 6px 0;
        border-bottom: 1px solid var(--bo-line);
        color: var(--bo-ink-2);
      }
      .logins tr:last-child td {
        border-bottom: 0;
      }
      .panel-form .field:first-child {
        margin-top: 0;
      }
      .footer {
        display: flex;
        justify-content: flex-end;
        gap: var(--bo-space-2);
      }
      @media (max-width: 760px) {
        .row {
          grid-template-columns: 1fr;
          gap: var(--bo-space-2);
        }
        .act {
          justify-content: flex-start;
        }
      }
    `,
  ],
})
export class SecuritySectionComponent {
  private readonly api = inject(LocalAuthApi);
  private readonly config = inject(AuthConfigService);
  private readonly identity = inject(IdentityService);
  private readonly message = inject(NzMessageService);

  /** Open the MFA set-up right away (`?setup=mfa`). */
  readonly startSetup = input(false);

  readonly sec = signal<MySecurity | null>(null);
  readonly panel = signal<Panel>('none');
  readonly dialog = signal<Dialog>(null);
  readonly dialogBusy = signal(false);
  readonly dialogError = signal<string | null>(null);
  readonly codes = signal<string[]>([]);
  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly repeat = signal('');
  readonly saving = signal(false);
  readonly currentError = signal<string | null>(null);
  readonly newError = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  private readonly current = viewChild<PasswordFieldComponent>('current');

  readonly text = {
    mfaRequired: $localize`:@@account.security.mfaRequired:required`,
    mfaOff: $localize`:@@account.security.mfaOff:off`,
    signedIn: $localize`:@@account.security.resultSignedIn:signed in`,
    failed: $localize`:@@account.security.resultFailed:failed`,
    codesTitle: $localize`:@@account.mfa.codesTitle:Save your recovery codes`,
    enrolTitle: $localize`:@@account.mfa.enrolTitle:Set up two-factor authentication`,
    codesSubtitle: $localize`:@@account.mfa.codesSubtitle:Shown once. Each code signs you in once without your phone.`,
    enrolSubtitle: $localize`:@@account.mfa.enrolSubtitle:Scan the code with an authenticator app, then confirm with a code from it.`,
  };

  readonly provider = computed(
    () =>
      this.config.config()?.providerName ||
      $localize`:@@account.security.yourProvider:your identity provider`,
  );
  readonly accountUrl = computed(() => this.config.config()?.accountUrl ?? null);
  readonly email = computed(() => this.identity.user()?.email ?? '');
  readonly local = computed(() => allowsLocal(this.sec()?.authMode));
  readonly method = computed(() => signInMethodText(this.sec()?.authMode, this.provider()));
  readonly codesState = computed(() => recoveryCodesState(this.sec()));
  readonly blocked = computed(() => disableMfaBlocked(this.sec()));

  readonly ago = timeAgo;
  readonly abs = absoluteTime;
  word = (m: string | null | undefined) => methodWord(m, this.provider());

  constructor() {
    this.config.load().subscribe();
    this.load();
  }

  load(): void {
    this.api.mySecurity().subscribe({
      next: (s) => {
        this.sec.set(s);
        if (
          this.startSetup() &&
          s.mfaEnabled === false &&
          allowsLocal(s.authMode) &&
          this.panel() === 'none'
        )
          this.panel.set('enrol');
      },
      error: () => this.sec.set({}),
    });
  }

  openChange(): void {
    this.currentPassword.set('');
    this.newPassword.set('');
    this.repeat.set('');
    this.currentError.set(null);
    this.newError.set(null);
    this.formError.set(null);
    this.panel.set('change');
  }

  async changePassword(): Promise<void> {
    if (this.saving()) return;
    this.saving.set(true);
    this.currentError.set(null);
    this.newError.set(null);
    this.formError.set(null);
    try {
      await firstValueFrom(
        this.api.changePassword({
          currentPassword: this.currentPassword(),
          newPassword: this.newPassword(),
        }),
      );
      this.saving.set(false);
      this.panel.set('none');
      this.message.success(
        $localize`:@@account.password.changed:Password changed. Your other sessions were signed out.`,
      );
      this.load();
    } catch (err) {
      this.saving.set(false);
      const e = passwordErrorOf(err);
      if (e.field === 'current') {
        this.currentError.set(e.message);
        this.current()?.focus();
      } else if (e.field === 'new') this.newError.set(e.message);
      else this.formError.set(e.message);
    }
  }

  showCodes(codes: string[]): void {
    this.codes.set(codes);
    this.panel.set('codes');
    this.load();
  }

  closeCodes(): void {
    this.panel.set('none');
    this.codes.set([]);
  }

  closeDialog(): void {
    this.dialog.set(null);
    this.dialogError.set(null);
  }

  regenerate(password: string): void {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    this.api.regenerateRecoveryCodes(password).subscribe({
      next: (r) => {
        this.dialogBusy.set(false);
        this.closeDialog();
        this.showCodes(r.codes ?? []);
      },
      error: (e: unknown) => this.failDialog(e),
    });
  }

  disable(password: string): void {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    this.api.disableTotp(password).subscribe({
      next: () => {
        this.dialogBusy.set(false);
        this.closeDialog();
        this.message.success($localize`:@@account.mfa.disabled:Two-factor authentication is off.`);
        this.load();
      },
      error: (e: unknown) => this.failDialog(e),
    });
  }

  private failDialog(e: unknown): void {
    this.dialogBusy.set(false);
    const status = e instanceof HttpErrorResponse ? e.status : -1;
    this.dialogError.set(
      status === 401
        ? $localize`:@@account.mfa.wrongPassword:That password is not correct.`
        : status === 409
          ? $localize`:@@account.mfa.requiredByOrg:Your organisation requires two-factor authentication, so it cannot be turned off.`
          : $localize`:@@account.mfa.failed:That did not work. Try again.`,
    );
  }
}
