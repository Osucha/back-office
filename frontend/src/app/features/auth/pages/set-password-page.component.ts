import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import type { PasswordResetInfo } from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { IdentityService } from '../../../core/auth/identity.service';
import { icu } from '../../../core/i18n/icu';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { AuthShellComponent } from '../components/auth-shell.component';
import { AUTH_FORM_STYLES } from '../components/auth-styles';
import { NewPasswordComponent } from '../components/new-password.component';
import { goAfterSignIn } from '../data/after-sign-in';
import { destinationOf } from '../data/login-flow';
import { passwordErrorOf } from '../data/password-errors';
import { PendingMfaService } from '../data/pending-mfa.service';

type State = 'checking' | 'valid' | 'invalid' | 'unreachable';

/** "in 23 hours" from the relative helper (which says "23h ago" for the past). */
export function expiresIn(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '';
  const ms = Date.parse(iso) - now;
  if (Number.isNaN(ms)) return '';
  if (ms <= 0) return $localize`:@@auth.setPassword.now:now`;
  const min = Math.round(ms / 60_000);
  if (min < 60) return $localize`:@@auth.setPassword.inMinutes:in ${Math.max(1, min)}:minutes: min`;
  const h = Math.round(min / 60);
  return icu(
    $localize`:@@auth.setPassword.inHours:{count, plural, one {in # hour} other {in # hours}}`,
    { count: h },
  );
}

/**
 * `/set-password/:token`: the one-time link an administrator hands out (first password of an
 * invited local user, or a reset). Checks the link first (e-mail, purpose, expiry), then sets the
 * password and signs in; when the account has MFA, the sign-in page asks for the code next.
 */
@Component({
  selector: 'app-set-password-page',
  imports: [RouterLink, NzButtonModule, AuthShellComponent, GlyphComponent, NewPasswordComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-shell label="Set your password" i18n-label="@@auth.setPassword.title">
      @switch (state()) {
        @case ('invalid') {
          <div class="state-icon" aria-hidden="true"><span></span></div>
          <h1 class="form-title" i18n="@@auth.setPassword.invalidTitle">
            This link does not work any more
          </h1>
          <p class="form-lead" i18n="@@auth.setPassword.invalidText">
            Set-password links work once and expire after 24 hours. Ask the administrator who sent
            it for a new link.
          </p>
          <a
            nz-button
            nzType="primary"
            class="submit"
            routerLink="/login"
            i18n="@@auth.setPassword.goToSignIn"
            >Go to sign in</a
          >
        }
        @case ('unreachable') {
          <h1 class="form-title" i18n="@@auth.setPassword.title">Set your password</h1>
          <div class="banner warn" role="alert">
            <bo-glyph holder="you" [size]="14" />
            <span class="grow" i18n="@@auth.setPassword.unreachable"
              >Back Office cannot reach the server. Check your connection, then try again.</span
            >
            <button
              nz-button
              nzSize="small"
              type="button"
              (click)="check()"
              i18n="@@auth.login.tryAgain"
            >
              Try again
            </button>
          </div>
        }
        @case ('valid') {
          @if (info()?.purpose === 'RESET') {
            <h1 class="form-title" i18n="@@auth.setPassword.resetTitle">Choose a new password</h1>
            <p class="form-lead" i18n="@@auth.setPassword.resetLead">
              An administrator reset your password. Choose a new one to sign in again.
            </p>
          } @else {
            <h1 class="form-title" i18n="@@auth.setPassword.title">Set your password</h1>
            <p class="form-lead" i18n="@@auth.setPassword.welcomeLead">
              Welcome to Back Office. Choose a password to finish setting up your account.
            </p>
          }
          <div class="who">
            <span class="avatar" aria-hidden="true">{{ initial() }}</span>
            <span class="addr">{{ info()?.email }}</span>
          </div>
          <p class="expiry" [title]="expiresAbs()" i18n="@@auth.setPassword.expiry">
            This link works once and expires {{ expires() }}.
          </p>
          @if (otherUser(); as other) {
            <div class="banner info" role="status">
              <bo-glyph holder="idle" [size]="14" />
              <span i18n="@@auth.setPassword.otherUser"
                >You are signed in as {{ other }}. Setting this password signs you in as
                {{ info()?.email }} instead.</span
              >
            </div>
          }
          @if (formError()) {
            <div class="banner error" role="alert">
              <bo-glyph holder="blocked" [size]="14" />
              <span>{{ formError() }}</span>
            </div>
          }
          <form (submit)="$event.preventDefault(); save()" novalidate>
            <input
              type="email"
              name="username"
              autocomplete="username"
              [value]="info()?.email ?? ''"
              hidden
            />
            <app-new-password
              #next
              label="New password"
              i18n-label="@@auth.newPassword.label"
              [email]="info()?.email"
              [policy]="info()?.policy ?? config.config()?.passwordPolicy"
              [serverError]="newError()"
              [(value)]="password"
              [(repeat)]="repeat"
            />
            <button
              nz-button
              nzType="primary"
              class="submit"
              type="submit"
              [nzLoading]="saving()"
              [disabled]="!next.valid()"
              i18n="@@auth.setPassword.submit"
            >
              Set password and sign in
            </button>
          </form>
        }
        @default {
          <div
            class="checking"
            aria-busy="true"
            aria-label="Checking the link"
            i18n-aria-label="@@auth.setPassword.checking"
          >
            <span class="ph title"></span>
            <span class="ph lead"></span>
            <span class="ph pill"></span>
            <span class="ph input"></span>
            <span class="ph input"></span>
          </div>
        }
      }
    </app-auth-shell>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      .grow {
        flex: 1;
      }
      .expiry {
        margin: var(--bo-space-2) 0 0;
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      a.submit {
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }
      .state-icon {
        width: 40px;
        height: 40px;
        border-radius: 50%;
        background: var(--bo-idle-soft);
        display: flex;
        align-items: center;
        justify-content: center;
        margin-bottom: var(--bo-space-4);
      }
      .state-icon span {
        width: 16px;
        height: 16px;
        border-radius: 50%;
        border: 2px solid var(--bo-idle);
      }
      .checking {
        display: flex;
        flex-direction: column;
        gap: var(--bo-space-3);
      }
      .ph {
        display: block;
        background: var(--bo-surface-3);
        border-radius: var(--bo-radius-control);
      }
      .ph.title {
        width: 60%;
        height: 24px;
      }
      .ph.lead {
        width: 90%;
        height: 14px;
      }
      .ph.pill {
        width: 50%;
        height: 30px;
        border-radius: var(--bo-radius-chip);
      }
      .ph.input {
        height: 40px;
        margin-top: var(--bo-space-4);
      }
    `,
  ],
})
export class SetPasswordPageComponent {
  readonly config = inject(AuthConfigService);
  private readonly identity = inject(IdentityService);
  private readonly api = inject(LocalAuthApi);
  private readonly router = inject(Router);
  private readonly pendingMfa = inject(PendingMfaService);

  readonly token = input.required<string>();

  readonly state = signal<State>('checking');
  readonly info = signal<PasswordResetInfo | null>(null);
  readonly password = signal('');
  readonly repeat = signal('');
  readonly saving = signal(false);
  readonly newError = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  private readonly next = viewChild<NewPasswordComponent>('next');

  readonly initial = computed(() => (this.info()?.email?.[0] ?? '?').toUpperCase());
  readonly expires = computed(() => expiresIn(this.info()?.expiresAt));
  readonly expiresAbs = computed(() => absoluteTime(this.info()?.expiresAt));
  readonly otherUser = computed(() => {
    const me = this.identity.user()?.email;
    const target = this.info()?.email;
    return me && target && me.toLowerCase() !== target.toLowerCase() ? me : null;
  });

  constructor() {
    // Loads the XSRF cookie for the POST.
    this.config.load().subscribe();
    effect(() => {
      this.token();
      untracked(() => this.check());
    });
  }

  check(): void {
    this.state.set('checking');
    this.api.checkPasswordReset(this.token()).subscribe({
      next: (info) => {
        this.info.set(info);
        this.state.set('valid');
        queueMicrotask(() => this.next()?.focus());
      },
      error: (e: unknown) =>
        this.state.set(
          e instanceof HttpErrorResponse && e.status === 0 ? 'unreachable' : 'invalid',
        ),
    });
  }

  save(): void {
    if (this.saving() || !this.next()?.valid()) return;
    this.saving.set(true);
    this.newError.set(null);
    this.formError.set(null);
    this.api.completePasswordReset(this.token(), this.password()).subscribe({
      next: (result) => {
        const email = this.info()?.email ?? '';
        if (result.status === 'MFA_REQUIRED' && result.mfaToken) {
          this.pendingMfa.set({ mfaToken: result.mfaToken, email, returnTo: '/' });
          void this.router.navigate(['/login'], { queryParams: { email } });
          return;
        }
        const destination = destinationOf(result, '/');
        if (destination) void goAfterSignIn(destination, this.identity, this.router);
        else void this.router.navigate(['/login'], { queryParams: { email } });
      },
      error: (err: unknown) => {
        this.saving.set(false);
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.state.set('invalid');
          return;
        }
        const e = passwordErrorOf(err);
        if (e.field === 'new') this.newError.set(e.message);
        else this.formError.set(e.message);
      },
    });
  }
}
