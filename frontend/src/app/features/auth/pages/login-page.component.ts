import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { safeReturnTo } from '../../../core/auth/browser-navigation';
import { IdentityService } from '../../../core/auth/identity.service';
import { CodeInputComponent } from '../../../core/ui/relay/code-input.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { AuthShellComponent } from '../components/auth-shell.component';
import { AUTH_FORM_STYLES } from '../components/auth-styles';
import { PasswordFieldComponent } from '../components/password-field.component';
import { goAfterSignIn } from '../data/after-sign-in';
import { LoginFlow, formatCountdown } from '../data/login-flow';
import { PendingMfaService } from '../data/pending-mfa.service';
import { loginErrorMessage, loginErrorType } from '../data/login-errors';

/**
 * Public sign-in page (ADR 0021): e-mail + password when local login is enabled, "Continue with
 * <provider>" when OIDC is configured, both with a divider. A second step asks for the TOTP code
 * (or a recovery code). Only the available methods are shown; the state machine is `LoginFlow`.
 */
@Component({
  selector: 'app-login-page',
  imports: [
    NzButtonModule,
    NzIconModule,
    AuthShellComponent,
    CodeInputComponent,
    GlyphComponent,
    PasswordFieldComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-shell label="Sign in to Back Office" i18n-label="@@auth.login.shellLabel">
      @if (flow.step() === 'password') {
        <h1 class="form-title" #heading tabindex="-1" i18n="@@auth.login.title">Sign in</h1>
        <p class="form-lead">{{ lead() }}</p>

        @if (queryError(); as msg) {
          <div
            class="banner"
            [class.error]="queryErrorType() === 'error'"
            [class.info]="queryErrorType() === 'info'"
            role="alert"
          >
            <bo-glyph [holder]="queryErrorType() === 'error' ? 'blocked' : 'idle'" [size]="14" />
            <span>{{ msg }}</span>
          </div>
        }
        @if (identity.status() === 'unreachable') {
          <div class="banner warn" role="alert">
            <bo-glyph holder="you" [size]="14" />
            <span class="grow" i18n="@@auth.login.unreachable">
              <strong>Back Office cannot reach the server.</strong> Check that the backend is
              running, then try again.
            </span>
            <button
              nz-button
              nzSize="small"
              type="button"
              [nzLoading]="retrying()"
              (click)="retry()"
              i18n="@@auth.login.tryAgain"
            >
              Try again
            </button>
          </div>
        }
        @if (flow.error(); as e) {
          <div
            class="banner"
            [class.error]="e.kind !== 'throttled'"
            [class.warn]="e.kind === 'throttled'"
            role="alert"
          >
            <bo-glyph [holder]="e.kind === 'throttled' ? 'you' : 'blocked'" [size]="14" />
            <span>
              {{ e.message }}
              @if (flow.throttled()) {
                <span class="countdown" aria-hidden="true">{{ countdown() }}</span>
              }
            </span>
          </div>
        }

        @if (!config.config()) {
          <div class="placeholder" aria-hidden="true">
            <span class="ph label"></span><span class="ph input"></span>
            <span class="ph label"></span><span class="ph input"></span>
            <span class="ph button"></span>
          </div>
        } @else {
          @if (methods().local) {
            <form class="local" (submit)="$event.preventDefault(); signInLocal()" novalidate>
              <div class="field">
                <label for="login-email" i18n="@@auth.login.email">Work e-mail</label>
                <input
                  #email
                  id="login-email"
                  class="text-input"
                  type="email"
                  name="username"
                  autocomplete="username"
                  inputmode="email"
                  spellcheck="false"
                  autocapitalize="off"
                  required
                  [attr.aria-invalid]="emailError() ? true : null"
                  [attr.aria-describedby]="emailError() ? 'login-email-error' : null"
                  [value]="emailValue()"
                  (input)="emailValue.set(email.value); emailError.set(null)"
                />
                @if (emailError()) {
                  <p class="field-error" id="login-email-error">{{ emailError() }}</p>
                }
              </div>
              <app-password-field
                #password
                label="Password"
                i18n-label="@@auth.login.password"
                autocomplete="current-password"
                [(value)]="passwordValue"
                [error]="passwordError()"
              />
              <button
                nz-button
                nzType="primary"
                class="submit"
                type="submit"
                [nzLoading]="flow.busy()"
                [disabled]="flow.throttled()"
              >
                @if (flow.throttled()) {
                  <ng-container i18n="@@auth.login.tryAgainIn"
                    >Try again in {{ countdown() }}</ng-container
                  >
                } @else {
                  <ng-container i18n="@@auth.login.submit">Sign in</ng-container>
                }
              </button>
            </form>
          }
          @if (methods().local && methods().oidc) {
            <div class="divider" role="separator"><span i18n="@@auth.login.or">or</span></div>
          }
          @if (methods().oidc) {
            <button
              nz-button
              [nzType]="methods().local ? 'default' : 'primary'"
              class="oidc"
              [class.submit]="!methods().local"
              type="button"
              [nzLoading]="redirecting()"
              (click)="signInOidc()"
            >
              <nz-icon nzType="safety-certificate" />
              <span i18n="@@auth.login.continueWith">Continue with {{ providerName() }}</span>
            </button>
          }
          @if (!methods().local && !methods().oidc) {
            <div class="banner warn" role="status">
              <bo-glyph holder="you" [size]="14" />
              <span i18n="@@auth.login.noMethod"
                >No sign-in method is enabled. An administrator has to enable local sign-in or
                configure an identity provider.</span
              >
            </div>
          }
          <div class="notes">
            @if (methods().local) {
              <p i18n="@@auth.login.forgot">
                Forgot your password? Ask an administrator for a set-password link.
              </p>
            }
            @if (config.config()?.registration === 'OPEN' && methods().oidc) {
              <p i18n="@@auth.login.openRegistration">
                New to Back Office? Continue with {{ providerName() }}; your account is created on
                the first sign-in.
              </p>
            } @else {
              <p i18n="@@auth.login.inviteOnly">
                Access is by invitation. Ask an administrator to invite you.
              </p>
            }
          </div>
        }
      } @else {
        <button type="button" class="back link-button" (click)="flow.backToPassword()">
          <nz-icon nzType="arrow-left" /> <span i18n="@@auth.login.back">Back to sign in</span>
        </button>
        <h1 class="form-title" #heading tabindex="-1">
          @if (flow.useRecovery()) {
            <ng-container i18n="@@auth.mfa.recoveryTitle">Use a recovery code</ng-container>
          } @else {
            <ng-container i18n="@@auth.mfa.codeTitle">Enter your code</ng-container>
          }
        </h1>
        <p class="form-lead">
          @if (flow.useRecovery()) {
            <ng-container i18n="@@auth.mfa.recoveryLead"
              >Enter one of the recovery codes you saved when you set up two-factor authentication.
              Each code works once.</ng-container
            >
          } @else {
            <ng-container i18n="@@auth.mfa.codeLead"
              >Open your authenticator app and enter the 6-digit code for Back Office.</ng-container
            >
          }
        </p>
        <div class="who">
          <span class="avatar" aria-hidden="true">{{ initial() }}</span>
          <span class="addr">{{ flow.email() }}</span>
        </div>
        @if (flow.error(); as e) {
          <div
            class="banner"
            [class.error]="e.kind !== 'throttled'"
            [class.warn]="e.kind === 'throttled'"
            role="alert"
          >
            <bo-glyph [holder]="e.kind === 'throttled' ? 'you' : 'blocked'" [size]="14" />
            <span>
              {{ e.message }}
              @if (flow.throttled()) {
                <span class="countdown" aria-hidden="true">{{ countdown() }}</span>
              }
            </span>
          </div>
        }
        <form class="mfa" (submit)="$event.preventDefault(); submitCode()" novalidate>
          @if (flow.useRecovery()) {
            <div class="field">
              <label for="login-recovery" i18n="@@auth.mfa.recoveryCode">Recovery code</label>
              <input
                #recovery
                id="login-recovery"
                class="text-input mono"
                name="recovery"
                autocomplete="off"
                spellcheck="false"
                autocapitalize="off"
                placeholder="xxxx-xxxx"
                [value]="recoveryValue()"
                (input)="recoveryValue.set(recovery.value)"
              />
            </div>
          } @else {
            <div class="field">
              <span class="label" id="login-code-label" i18n="@@auth.mfa.codeLabel"
                >Authentication code</span
              >
              <bo-code-input
                #code
                label="Authentication code"
                i18n-label="@@auth.mfa.codeLabel"
                describedBy="login-code-hint"
                [autofocus]="true"
                [disabled]="flow.busy() || flow.throttled()"
                [(value)]="codeValue"
                (completed)="submitCode($event)"
              />
              <p class="field-note" id="login-code-hint" i18n="@@auth.mfa.codeHint">
                The code changes every 30 seconds.
              </p>
            </div>
          }
          <button
            nz-button
            nzType="primary"
            class="submit"
            type="submit"
            [nzLoading]="flow.busy()"
            [disabled]="flow.throttled() || !canSubmitCode()"
          >
            @if (flow.throttled()) {
              <ng-container i18n="@@auth.login.tryAgainIn"
                >Try again in {{ countdown() }}</ng-container
              >
            } @else if (flow.useRecovery()) {
              <ng-container i18n="@@auth.mfa.submitRecovery"
                >Sign in with recovery code</ng-container
              >
            } @else {
              <ng-container i18n="@@auth.mfa.submitCode">Verify code</ng-container>
            }
          </button>
        </form>
        <div class="notes">
          <button type="button" class="link-button" (click)="toggleRecovery()">
            @if (flow.useRecovery()) {
              <ng-container i18n="@@auth.mfa.useApp"
                >Use your authenticator app instead</ng-container
              >
            } @else {
              <ng-container i18n="@@auth.mfa.useRecovery">Use a recovery code instead</ng-container>
            }
          </button>
          <p i18n="@@auth.mfa.lostDevice">
            Lost your device and your recovery codes? An administrator can reset two-factor
            authentication for you.
          </p>
        </div>
      }
    </app-auth-shell>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      .form-title:focus {
        outline: none;
      }
      .grow {
        flex: 1;
      }
      .banner button {
        flex: none;
      }
      .divider {
        display: flex;
        align-items: center;
        gap: var(--bo-space-3);
        margin: var(--bo-space-5) 0;
        color: var(--bo-ink-3);
        font-size: var(--bo-fs-meta);
      }
      .divider::before,
      .divider::after {
        content: '';
        flex: 1;
        height: 1px;
        background: var(--bo-line);
      }
      .oidc {
        width: 100%;
        height: 40px;
        font-weight: 500;
      }
      .oidc.submit {
        margin-top: var(--bo-space-5);
      }
      .notes {
        margin-top: var(--bo-space-5);
        display: flex;
        flex-direction: column;
        gap: var(--bo-space-2);
        align-items: flex-start;
      }
      .notes p {
        margin: 0;
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .back {
        align-self: flex-start;
        display: inline-flex;
        align-items: center;
        gap: 6px;
        margin-bottom: var(--bo-space-4);
        text-decoration: none;
      }
      .placeholder {
        display: flex;
        flex-direction: column;
        margin-top: var(--bo-space-4);
      }
      .ph {
        display: block;
        background: var(--bo-surface-3);
        border-radius: var(--bo-radius-control);
      }
      .ph.label {
        width: 30%;
        height: 12px;
        margin: 6px 0;
      }
      .ph.input {
        height: 40px;
        margin-bottom: var(--bo-space-3);
      }
      .ph.button {
        height: 40px;
        margin-top: var(--bo-space-4);
      }
    `,
  ],
})
export class LoginPageComponent {
  readonly identity = inject(IdentityService);
  readonly config = inject(AuthConfigService);
  private readonly router = inject(Router);
  private readonly api = inject(LocalAuthApi);
  private readonly pendingMfa = inject(PendingMfaService);

  /** `?error=` set by the backend when the OIDC login could not finish. */
  readonly error = input<string | undefined>();
  /** `?returnTo=` set by `authGuard`. */
  readonly returnTo = input<string | undefined>();
  /** `?email=` pre-fills the e-mail (links from the set-password page). */
  readonly email = input<string | undefined>();

  readonly flow = new LoginFlow(this.api, (d) => void goAfterSignIn(d, this.identity, this.router));

  readonly emailValue = signal('');
  readonly passwordValue = signal('');
  readonly codeValue = signal('');
  readonly recoveryValue = signal('');
  readonly emailError = signal<string | null>(null);
  readonly passwordError = signal<string | null>(null);
  readonly redirecting = signal(false);
  readonly retrying = signal(false);

  private readonly emailEl = viewChild<ElementRef<HTMLInputElement>>('email');
  private readonly passwordEl = viewChild<PasswordFieldComponent>('password');
  private readonly codeEl = viewChild<CodeInputComponent>('code');
  private readonly recoveryEl = viewChild<ElementRef<HTMLInputElement>>('recovery');

  readonly queryError = computed(() => loginErrorMessage(this.error()));
  readonly queryErrorType = computed(() => loginErrorType(this.error()));
  readonly methods = computed(() => {
    const m = this.config.config()?.methods;
    // An older backend without `methods` only knows OIDC.
    return { local: m?.local ?? false, oidc: m?.oidc ?? !m };
  });
  readonly providerName = computed(
    () =>
      this.config.config()?.providerName ||
      $localize`:@@auth.login.yourProvider:your identity provider`,
  );
  readonly lead = computed(() => {
    const m = this.methods();
    if (m.local && m.oidc)
      return $localize`:@@auth.login.leadBoth:Use your work e-mail and password, or your organisation account.`;
    if (m.local) return $localize`:@@auth.login.leadLocal:Use your work e-mail and password.`;
    return $localize`:@@auth.login.leadOidc:Use your organisation account. ${this.providerName()}:provider: handles passwords and two-factor authentication.`;
  });
  readonly countdown = computed(() => formatCountdown(this.flow.retryIn()));
  readonly initial = computed(() => (this.flow.email()[0] ?? '?').toUpperCase());
  readonly canSubmitCode = computed(() =>
    this.flow.useRecovery()
      ? this.recoveryValue().trim().length >= 4
      : this.codeValue().length === 6,
  );

  constructor() {
    // Also sets the XSRF-TOKEN cookie that POST /auth/login needs.
    this.config.load().subscribe();
    const destroyRef = inject(DestroyRef);

    effect(() => {
      const e = this.email();
      untracked(() => {
        if (e && !this.emailValue()) this.emailValue.set(e);
      });
    });

    // Hand-over from the set-password page (MFA already enabled for that account).
    const pending = this.pendingMfa.take();
    if (pending) this.flow.startMfa(pending.mfaToken, pending.email, pending.returnTo);

    // Countdown while throttled.
    effect((onCleanup) => {
      if (!this.flow.retryUntil()) return;
      const id = setInterval(() => this.flow.tick(), 1000);
      onCleanup(() => clearInterval(id));
    });

    // Focus: the first empty field of the step; the password again after a failure.
    effect(() => {
      const step = this.flow.step();
      this.flow.passwordResets();
      const ready = !!this.config.config();
      untracked(() => {
        if (step === 'password') this.passwordValue.set('');
        this.codeValue.set('');
        this.recoveryValue.set('');
        if (!ready) return;
        queueMicrotask(() => this.focusStep());
      });
    });
    destroyRef.onDestroy(() => this.flow.retryUntil.set(null));
  }

  signInLocal(): void {
    const email = this.emailValue().trim();
    const password = this.passwordValue();
    this.emailError.set(
      email
        ? /^\S+@\S+$/.test(email)
          ? null
          : $localize`:@@auth.login.emailInvalid:Enter an e-mail address like anna@example.com.`
        : $localize`:@@auth.login.emailMissing:Enter your e-mail address.`,
    );
    this.passwordError.set(
      password ? null : $localize`:@@auth.login.passwordMissing:Enter your password.`,
    );
    if (this.emailError()) return this.emailEl()?.nativeElement.focus();
    if (this.passwordError()) return this.passwordEl()?.focus();
    this.flow.submitPassword(email, password, this.target());
  }

  submitCode(code?: string): void {
    if (this.flow.useRecovery()) this.flow.submitCode(this.recoveryValue());
    else this.flow.submitCode(code ?? this.codeValue());
  }

  toggleRecovery(): void {
    this.flow.toggleRecovery();
    queueMicrotask(() => this.focusStep());
  }

  signInOidc(): void {
    this.redirecting.set(true);
    void this.identity.login(this.target());
  }

  async retry(): Promise<void> {
    this.retrying.set(true);
    const me = await this.identity.load();
    this.retrying.set(false);
    this.config.load().subscribe();
    if (me) void this.router.navigateByUrl(this.target());
  }

  private target(): string {
    return safeReturnTo(this.returnTo());
  }

  private focusStep(): void {
    if (this.flow.step() === 'mfa') {
      if (this.flow.useRecovery()) this.recoveryEl()?.nativeElement.focus();
      else this.codeEl()?.focus();
      return;
    }
    if (!this.methods().local) return;
    if (this.emailValue()) this.passwordEl()?.focus();
    else this.emailEl()?.nativeElement.focus();
  }
}
