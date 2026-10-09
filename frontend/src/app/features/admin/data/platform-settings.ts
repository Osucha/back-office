/** Platform settings form <-> `PlatformSettings`, defaults (openapi) and the consequence texts. */
import type { PasswordPolicy, PlatformSettings } from '../../../core/api/types';
import { formatNumber } from '../../../core/i18n/numbers';

export const DEFAULT_SETTINGS: Required<
  Pick<
    PlatformSettings,
    | 'registration'
    | 'allowedEmailDomains'
    | 'syncGroupsFromIdentityProvider'
    | 'personalTokenMaxDays'
    | 'localLoginEnabled'
    | 'mfaRequiredForLocal'
    | 'specKitAllowed'
  >
> & {
  passwordPolicy: Required<PasswordPolicy>;
  lockout: { maxFailures: number; lockMinutes: number };
} = {
  registration: 'INVITE_ONLY',
  allowedEmailDomains: [],
  syncGroupsFromIdentityProvider: false,
  personalTokenMaxDays: 90,
  localLoginEnabled: true,
  mfaRequiredForLocal: false,
  specKitAllowed: true,
  passwordPolicy: {
    minLength: 12,
    maxLength: 128,
    rejectCommon: true,
    rejectContainingEmail: true,
  },
  lockout: { maxFailures: 5, lockMinutes: 15 },
};

export interface SettingsFormValue {
  localLoginEnabled: boolean;
  mfaRequiredForLocal: boolean;
  passwordPolicy: Required<PasswordPolicy>;
  lockout: { maxFailures: number; lockMinutes: number };
  registration: 'OPEN' | 'INVITE_ONLY';
  allowedEmailDomains: string[];
  syncGroupsFromIdentityProvider: boolean;
  personalTokenMaxDays: number;
  /** ADR 0036: projects may turn Spec Kit on. */
  specKitAllowed: boolean;
}

/** Request body: the saved settings (fields this form does not know are kept) + the form. */
export function toSettings(saved: PlatformSettings, v: SettingsFormValue): PlatformSettings {
  return {
    ...saved,
    ...v,
    passwordPolicy: { ...saved.passwordPolicy, ...v.passwordPolicy },
    lockout: { ...saved.lockout, ...v.lockout },
    allowedEmailDomains: [
      ...new Set(
        v.allowedEmailDomains.map((d) => d.trim().toLowerCase().replace(/^@/, '')).filter(Boolean),
      ),
    ],
  };
}

/** Warnings shown under the sign-in section (consequences of the current choice). */
export function settingsWarnings(
  v: Pick<SettingsFormValue, 'localLoginEnabled' | 'mfaRequiredForLocal' | 'passwordPolicy'>,
  ctx: { oidc: boolean; provider: string; minGreaterThanMax?: boolean },
): string[] {
  const out: string[] = [];
  if (!v.localLoginEnabled && !ctx.oidc)
    out.push(
      $localize`:@@admin.platformSettings.warn.noProvider:No identity provider is configured: with local sign-in off, nobody can sign in, including you.`,
    );
  if (!v.localLoginEnabled && v.mfaRequiredForLocal)
    out.push(
      $localize`:@@admin.platformSettings.warn.mfaDormant:MFA for local accounts takes effect when local sign-in is allowed again.`,
    );
  if (v.passwordPolicy.minLength > v.passwordPolicy.maxLength)
    out.push(
      $localize`:@@admin.platformSettings.warn.minOverMax:The minimum password length is larger than the maximum.`,
    );
  return out;
}

/** The doubling lock durations: "15 min, 30 min, 1 h, 2 h … 24 h". */
export function lockSeriesText(first: number | null | undefined): string {
  const start = Math.max(1, Math.round(first ?? 15));
  const fmt = (m: number) =>
    m < 60 ? `${m} min` : `${formatNumber(+(m / 60).toFixed(1), { useGrouping: false })} h`;
  const steps: string[] = [];
  let m = start;
  while (m < 1440 && steps.length < 4) {
    steps.push(fmt(m));
    m *= 2;
  }
  if (m >= 1440) return [...steps, '24 h'].join(', ');
  return `${steps.join(', ')} … 24 h`;
}
