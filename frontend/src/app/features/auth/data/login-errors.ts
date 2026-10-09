/**
 * Messages for `/login?error=<code>`; the backend redirects here when the OIDC login cannot finish.
 * Unknown codes get the generic message (never the raw code, which may come from a crafted link).
 */
export const LOGIN_ERROR_MESSAGES: Readonly<Record<string, string>> = {
  'not-invited': $localize`:@@auth.error.notInvited:Your account has not been invited to Back Office. Ask an administrator to invite your e-mail address.`,
  deactivated: $localize`:@@auth.error.deactivated:Your account has been deactivated. Contact an administrator if you need access again.`,
  'login-failed': $localize`:@@auth.error.loginFailed:Sign-in failed. Try again; if the problem persists, contact an administrator.`,
  'session-expired': $localize`:@@auth.error.sessionExpired:Your session has expired. Sign in again to continue.`,
};

export const GENERIC_LOGIN_ERROR = $localize`:@@auth.flow.failed:Sign-in could not be completed. Try again.`;

export function loginErrorMessage(code: string | null | undefined): string | null {
  if (!code) return null;
  return LOGIN_ERROR_MESSAGES[code] ?? GENERIC_LOGIN_ERROR;
}

/** `session-expired` is informational, the others are errors. */
export function loginErrorType(code: string | null | undefined): 'info' | 'error' {
  return code === 'session-expired' ? 'info' : 'error';
}
