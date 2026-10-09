import { HttpErrorResponse } from '@angular/common/http';
import { computed, signal } from '@angular/core';
import type { Observable } from 'rxjs';
import type { LocalLoginRequest, LoginStepResult, MfaLoginRequest } from '../../../core/api/types';

/**
 * The local sign-in as a small state machine (ADR 0021):
 *
 *   password ──AUTHENTICATED──────────────▶ app (returnTo)
 *      │     ──PASSWORD_CHANGE_REQUIRED───▶ /change-password (restricted session)
 *      │     ──MFA_ENROLMENT_REQUIRED─────▶ /setup-mfa (restricted session)
 *      │     ──MFA_REQUIRED {mfaToken}───▶ mfa ──AUTHENTICATED──▶ app
 *      │                                    │  ──401 (token consumed)──▶ password, e-mail kept
 *      └── 401 / 429 (countdown) / 0 ◀──────┘  ──429──▶ stays, countdown
 *
 * Framework-free apart from signals so every transition is unit-tested with fake APIs and a fake clock.
 */
export type LoginStep = 'password' | 'mfa';

export type LoginDestinationKind = 'app' | 'change-password' | 'setup-mfa';
export interface LoginDestination {
  kind: LoginDestinationKind;
  returnTo: string;
}

export type LoginErrorKind = 'invalid' | 'code-rejected' | 'throttled' | 'unreachable' | 'failed';
export interface LoginError {
  kind: LoginErrorKind;
  message: string;
}

export interface LoginFlowApi {
  login(input: LocalLoginRequest): Observable<LoginStepResult>;
  loginMfa(input: MfaLoginRequest): Observable<LoginStepResult>;
}

export const LOGIN_MESSAGES = {
  invalid: $localize`:@@auth.flow.invalid:The e-mail address or password is not correct. Check both and try again.`,
  codeRejected: $localize`:@@auth.flow.codeRejected:That code did not work. Sign in again.`,
  unreachable: $localize`:@@auth.flow.unreachable:Back Office cannot reach the server. Check your connection and try again.`,
  failed: $localize`:@@auth.flow.failed:Sign-in could not be completed. Try again.`,
};

/** Throttled for this long when the server sends no usable `Retry-After`. */
export const DEFAULT_RETRY_SECONDS = 60;

/** Where a successful step leads; `null` for `MFA_REQUIRED` (the code step comes next). */
export function destinationOf(result: LoginStepResult, returnTo: string): LoginDestination | null {
  switch (result.status) {
    case 'AUTHENTICATED':
      return { kind: 'app', returnTo };
    case 'PASSWORD_CHANGE_REQUIRED':
      return { kind: 'change-password', returnTo };
    case 'MFA_ENROLMENT_REQUIRED':
      return { kind: 'setup-mfa', returnTo };
    default:
      return null;
  }
}

/** `Retry-After` as seconds (delta-seconds or an HTTP date), at least 1. */
export function retryAfterSeconds(value: string | null | undefined, now: number): number {
  if (!value) return DEFAULT_RETRY_SECONDS;
  const trimmed = value.trim();
  if (/^\d+$/.test(trimmed)) return Math.max(1, Number(trimmed));
  const at = Date.parse(trimmed);
  if (Number.isNaN(at)) return DEFAULT_RETRY_SECONDS;
  return Math.max(1, Math.ceil((at - now) / 1000));
}

/** "0:42", "2:05" for the countdown. */
export function formatCountdown(seconds: number): string {
  const s = Math.max(0, Math.ceil(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

export class LoginFlow {
  readonly step = signal<LoginStep>('password');
  readonly email = signal('');
  readonly mfaToken = signal<string | null>(null);
  readonly useRecovery = signal(false);
  readonly busy = signal(false);
  readonly error = signal<LoginError | null>(null);
  /** Epoch ms until which the server refuses attempts (429). */
  readonly retryUntil = signal<number | null>(null);
  /** Bumped with every password-step reset so the view can clear and refocus the password. */
  readonly passwordResets = signal(0);
  private readonly now = signal(0);

  readonly retryIn = computed(() => {
    const until = this.retryUntil();
    return until ? Math.max(0, Math.ceil((until - this.now()) / 1000)) : 0;
  });
  readonly throttled = computed(() => this.retryIn() > 0);

  private returnTo = '/';

  constructor(
    private readonly api: LoginFlowApi,
    private readonly done: (destination: LoginDestination) => void,
    private readonly clock: () => number = Date.now,
  ) {
    this.now.set(clock());
  }

  submitPassword(email: string, password: string, returnTo: string): void {
    if (this.busy() || this.throttled()) return;
    this.returnTo = returnTo;
    this.email.set(email.trim());
    this.error.set(null);
    this.busy.set(true);
    this.api.login({ email: this.email(), password, returnTo }).subscribe({
      next: (r) => this.handleResult(r),
      error: (e: unknown) => this.handleError(e, 'password'),
    });
  }

  /** The TOTP code (6 digits) or, with `useRecovery`, a recovery code. */
  submitCode(code: string): void {
    const token = this.mfaToken();
    if (this.busy() || this.throttled() || !token) return;
    const value = code.trim();
    if (!value) return;
    this.error.set(null);
    this.busy.set(true);
    const input: MfaLoginRequest = this.useRecovery()
      ? { mfaToken: token, recoveryCode: value }
      : { mfaToken: token, code: value };
    this.api.loginMfa(input).subscribe({
      next: (r) => this.handleResult(r),
      error: (e: unknown) => this.handleError(e, 'mfa'),
    });
  }

  /** Enters the code step with a token obtained elsewhere (setting a password from a link). */
  startMfa(mfaToken: string, email: string, returnTo = '/'): void {
    this.returnTo = returnTo;
    this.email.set(email);
    this.mfaToken.set(mfaToken);
    this.useRecovery.set(false);
    this.error.set(null);
    this.step.set('mfa');
  }

  toggleRecovery(): void {
    this.useRecovery.update((v) => !v);
    if (this.error()?.kind !== 'throttled') this.error.set(null);
  }

  /** "Use a different account": back to the first step, the e-mail stays filled in. */
  backToPassword(error: LoginError | null = null): void {
    this.mfaToken.set(null);
    this.useRecovery.set(false);
    this.step.set('password');
    this.error.set(error);
    this.passwordResets.update((n) => n + 1);
  }

  /** Called every second by the view while throttled. */
  tick(): void {
    this.now.set(this.clock());
    if (this.retryUntil() && this.retryIn() === 0) {
      this.retryUntil.set(null);
      if (this.error()?.kind === 'throttled') this.error.set(null);
    }
  }

  private handleResult(result: LoginStepResult): void {
    if (result.status === 'MFA_REQUIRED') {
      this.busy.set(false);
      if (!result.mfaToken) {
        this.error.set({ kind: 'failed', message: LOGIN_MESSAGES.failed });
        return;
      }
      this.mfaToken.set(result.mfaToken);
      this.useRecovery.set(false);
      this.step.set('mfa');
      return;
    }
    const destination = destinationOf(result, result.returnTo || this.returnTo);
    if (!destination) {
      this.busy.set(false);
      this.error.set({ kind: 'failed', message: LOGIN_MESSAGES.failed });
      return;
    }
    // busy stays true: the view is about to navigate away.
    this.done(destination);
  }

  private handleError(err: unknown, step: LoginStep): void {
    this.busy.set(false);
    const status = err instanceof HttpErrorResponse ? err.status : -1;
    if (status === 429) {
      const now = this.clock();
      const seconds = retryAfterSeconds(
        err instanceof HttpErrorResponse ? err.headers.get('Retry-After') : null,
        now,
      );
      this.now.set(now);
      this.retryUntil.set(now + seconds * 1000);
      this.error.set({
        kind: 'throttled',
        message: $localize`:@@auth.flow.throttled:Too many sign-in attempts from this network. Wait for the countdown, then try again.`,
      });
      return;
    }
    if (status === 401) {
      if (step === 'mfa') {
        // The server consumed the step token: the only way on is the password again.
        this.backToPassword({ kind: 'code-rejected', message: LOGIN_MESSAGES.codeRejected });
      } else {
        this.error.set({ kind: 'invalid', message: LOGIN_MESSAGES.invalid });
        this.passwordResets.update((n) => n + 1);
      }
      return;
    }
    if (status === 0) {
      this.error.set({ kind: 'unreachable', message: LOGIN_MESSAGES.unreachable });
      return;
    }
    const detail =
      err instanceof HttpErrorResponse && err.error && typeof err.error === 'object'
        ? (err.error as { detail?: string }).detail
        : null;
    this.error.set({ kind: 'failed', message: detail || LOGIN_MESSAGES.failed });
  }
}
