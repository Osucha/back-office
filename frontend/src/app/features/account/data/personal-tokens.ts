/** Personal access token helpers (create form limits, display state). */
import type { PersonalToken } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';

/** Contract maximum; the platform setting `personalTokenMaxDays` may be lower. */
export const CONTRACT_MAX_DAYS = 365;
export const DEFAULT_TOKEN_DAYS = 30;

export function maxTokenDays(platformMax: number | null | undefined): number {
  return platformMax && platformMax > 0
    ? Math.min(platformMax, CONTRACT_MAX_DAYS)
    : CONTRACT_MAX_DAYS;
}

export function tokenState(t: PersonalToken, now = Date.now()): 'active' | 'expired' | 'revoked' {
  if (t.revokedAt) return 'revoked';
  if (t.expiresAt && Date.parse(t.expiresAt) <= now) return 'expired';
  return 'active';
}

export interface TokenFormErrors {
  name?: string;
  days?: string;
}

export function validateTokenForm(name: string, days: number | null, max: number): TokenFormErrors {
  const errors: TokenFormErrors = {};
  if (!name.trim()) errors.name = $localize`:@@account.tokens.nameRequired:Name is required`;
  if (!days || days < 1) errors.days = $localize`:@@account.tokens.daysAtLeast:At least 1 day`;
  else if (days > max)
    errors.days = icu(
      $localize`:@@account.tokens.daysAtMost:{max, plural, one {At most # day} other {At most # days}}`,
      { max },
    );
  return errors;
}
