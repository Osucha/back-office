import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { probe, silent, url } from './api-base';
import type {
  LocalLoginRequest,
  LoginStepResult,
  MfaLoginRequest,
  MySecurity,
  PasswordChangeRequest,
  PasswordResetInfo,
  RecoveryCodes,
  TotpEnrolment,
} from './types';

/**
 * Local accounts (ADR 0021): e-mail + password sign-in, the MFA step, password changes, set-password
 * links and the caller's own TOTP. Kept apart from `AuthApi` so it is only loaded by the lazy auth
 * and account pages.
 *
 * Sign-in steps are probes: their 401 means "wrong credentials", never "session expired", and the
 * caller shows the error in the form.
 */
@Injectable({ providedIn: 'root' })
export class LocalAuthApi {
  private readonly http = inject(HttpClient);

  /** Needs the XSRF cookie: load `/auth/config` first. */
  login(input: LocalLoginRequest): Observable<LoginStepResult> {
    return this.http.post<LoginStepResult>(url('auth', 'login'), input, probe());
  }

  loginMfa(input: MfaLoginRequest): Observable<LoginStepResult> {
    return this.http.post<LoginStepResult>(url('auth', 'login', 'mfa'), input, probe());
  }

  /** 401 = wrong current password, 400 = policy violations (field errors). */
  changePassword(input: PasswordChangeRequest): Observable<void> {
    return this.http.post<void>(url('auth', 'password', 'change'), input, probe());
  }

  /** 404 = unknown, used or expired link. */
  checkPasswordReset(token: string): Observable<PasswordResetInfo> {
    return this.http.get<PasswordResetInfo>(url('auth', 'password', 'reset', token), probe());
  }

  completePasswordReset(token: string, newPassword: string): Observable<LoginStepResult> {
    return this.http.post<LoginStepResult>(
      url('auth', 'password', 'reset', token),
      { newPassword },
      probe(),
    );
  }

  mySecurity(): Observable<MySecurity> {
    return this.http.get<MySecurity>(url('me', 'security'));
  }

  startTotp(): Observable<TotpEnrolment> {
    return this.http.post<TotpEnrolment>(url('me', 'mfa', 'totp'), {});
  }

  /** 400 = the code did not match (shown under the code input). */
  confirmTotp(code: string): Observable<RecoveryCodes> {
    return this.http.put<RecoveryCodes>(url('me', 'mfa', 'totp'), { code }, silent());
  }

  /** 401 = wrong password, 409 = the platform requires MFA for local accounts. */
  disableTotp(currentPassword: string): Observable<void> {
    return this.http.delete<void>(url('me', 'mfa', 'totp'), {
      body: { currentPassword },
      ...probe(),
    });
  }

  /** 401 = wrong password. */
  regenerateRecoveryCodes(currentPassword: string): Observable<RecoveryCodes> {
    return this.http.post<RecoveryCodes>(
      url('me', 'mfa', 'recovery-codes'),
      { currentPassword },
      probe(),
    );
  }
}
