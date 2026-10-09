import type { AuthMode, MySecurity } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';

/** How the account signs in, in words. */
export function signInMethodText(mode: AuthMode | null | undefined, provider: string): string {
  switch (mode) {
    case 'LOCAL':
      return $localize`:@@account.security.methodLocal:E-mail and password`;
    case 'BOTH':
      return $localize`:@@account.security.methodBoth:E-mail and password, or ${provider}:provider:`;
    default:
      return provider;
  }
}

export function allowsLocal(mode: AuthMode | null | undefined): boolean {
  return mode === 'LOCAL' || mode === 'BOTH';
}

export function methodWord(method: string | null | undefined, provider: string): string {
  if (method === 'LOCAL') return $localize`:@@account.security.methodPassword:password`;
  if (method === 'OIDC') return provider;
  return method ? method.toLowerCase() : '';
}

/** Recovery codes left, as a sentence with a warning level (≤ 3 left: running low). */
export function recoveryCodesState(sec: MySecurity | null | undefined): {
  text: string;
  low: boolean;
} {
  const n = sec?.recoveryCodesRemaining ?? 0;
  if (n === 0)
    return {
      text: $localize`:@@account.mfa.noCodesLeft:No recovery codes left. Create new ones now.`,
      low: true,
    };
  const text = icu(
    $localize`:@@account.mfa.codesLeft:{count, plural, one {# recovery code left} other {# recovery codes left}}`,
    { count: n },
  );
  return { text, low: n <= 3 };
}

/** Why "Turn off" is not available, or null. */
export function disableMfaBlocked(sec: MySecurity | null | undefined): string | null {
  return sec?.mfaRequired
    ? $localize`:@@account.mfa.requiredForLocal:Your organisation requires two-factor authentication for local accounts.`
    : null;
}
