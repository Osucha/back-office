/**
 * Local accounts in the admin UI (ADR 0021): which sign-in methods can be chosen, what switching
 * between them does (mirrors `LocalAccountAdminService.setAuthMode`), the lock state and the account
 * badges shown in the users list and the user header.
 */
import type { AuthMethods, AuthMode, UserDetail, UserStatus } from '../../../core/api/types';
import { icu } from '../../../core/i18n/icu';
import { intlLocale } from '../../../core/i18n/locale';
import type { BatonHolder } from '../../../core/ui/relay/baton';

export const AUTH_MODES: readonly AuthMode[] = ['LOCAL', 'OIDC', 'BOTH'];

const allowsLocal = (m: AuthMode) => m === 'LOCAL' || m === 'BOTH';
const allowsOidc = (m: AuthMode) => m === 'OIDC' || m === 'BOTH';

/** The neutral provider word used when the config names none (subject form). */
export function neutralProvider(): string {
  return $localize`:@@admin.localAccounts.neutralProvider:the identity provider`;
}

/** Provider name for texts ("Keycloak"); a neutral word when the config has none. */
export function providerLabel(providerName: string | null | undefined): string {
  const p = (providerName ?? '').trim();
  return p && p !== 'your identity provider' ? p : neutralProvider();
}

/**
 * The provider after "through" ("through Keycloak"): the name itself, or the neutral word in the
 * form a translation needs after that preposition (the same text in English).
 */
export function providerVia(provider: string): string {
  return provider === neutralProvider()
    ? $localize`:@@admin.localAccounts.neutralProviderVia:the identity provider`
    : provider;
}

/** Short name of a mode: "Local", "Keycloak", "Local and Keycloak". */
export function authModeName(mode: AuthMode | null | undefined, provider: string): string {
  const neutral = provider === neutralProvider();
  if (mode === 'LOCAL') return $localize`:@@admin.localAccounts.modeLocal:Local`;
  if (mode === 'BOTH')
    return neutral
      ? $localize`:@@admin.localAccounts.modeBothNeutral:Local and the identity provider`
      : $localize`:@@admin.localAccounts.modeBoth:Local and ${provider}:provider:`;
  return neutral ? $localize`:@@admin.localAccounts.modeOidcNeutral:Identity provider` : provider;
}

/** Badge word for lists: "local", "OIDC", "both". */
export function authModeBadge(mode: AuthMode | null | undefined): string {
  if (mode === 'LOCAL') return $localize`:@@admin.localAccounts.badgeLocal:local`;
  if (mode === 'BOTH') return $localize`:@@admin.localAccounts.badgeBoth:local + OIDC`;
  return 'OIDC'; // i18n-ignore: protocol name
}

/** One sentence under each option: what the mode means for the user. */
export function authModeSummary(mode: AuthMode, provider: string): string {
  const via = providerVia(provider);
  switch (mode) {
    case 'LOCAL':
      return $localize`:@@admin.localAccounts.summaryLocal:Signs in with e-mail and password here; cannot use ${via}:provider:.`;
    case 'OIDC':
      return $localize`:@@admin.localAccounts.summaryOidc:Signs in through ${via}:provider: only; has no password here.`;
    case 'BOTH':
      return $localize`:@@admin.localAccounts.summaryBoth:Signs in either with e-mail and password here or through ${via}:provider:.`;
  }
}

/** Whether switching removes a sign-in method (deletes credentials or ends sessions). */
export function authModeRemovesMethod(from: AuthMode, to: AuthMode): boolean {
  return (allowsLocal(from) && !allowsLocal(to)) || (allowsOidc(from) && !allowsOidc(to));
}

/**
 * Modes offered: those whose methods are available on this installation (LOCAL needs local login,
 * OIDC needs a provider, BOTH needs both). The current mode is always kept so it can be shown.
 */
export function availableAuthModes(
  methods: AuthMethods | null | undefined,
  current?: AuthMode | null,
): AuthMode[] {
  const local = methods?.local !== false;
  const oidc = methods?.oidc !== false;
  return AUTH_MODES.filter(
    (m) =>
      m === current ||
      (m === 'LOCAL' && local) ||
      (m === 'OIDC' && oidc) ||
      (m === 'BOTH' && local && oidc),
  );
}

/** Invite default: OIDC when a provider is configured, otherwise LOCAL (as the server does). */
export function defaultInviteMode(methods: AuthMethods | null | undefined): AuthMode {
  return methods?.oidc === false ? 'LOCAL' : 'OIDC';
}

/** Whether an invitation with this mode returns a set-password link (LOCAL / BOTH). */
export function inviteGetsSetPasswordLink(mode: AuthMode): boolean {
  return allowsLocal(mode);
}

export interface SwitchContext {
  /** First name for the texts ("Anna"). */
  name: string;
  provider: string;
  hasPassword: boolean;
}

/**
 * What switching `from` -> `to` does, in the order the server does it: the password, MFA and links
 * are deleted when local sign-in is removed; all sessions end when a method is removed; a
 * set-password link is created when local sign-in is added without a password.
 */
export function authModeConsequences(from: AuthMode, to: AuthMode, ctx: SwitchContext): string[] {
  if (from === to) return [];
  const out: string[] = [];
  const name = ctx.name;
  const removedLocal = allowsLocal(from) && !allowsLocal(to);
  const removedOidc = allowsOidc(from) && !allowsOidc(to);
  if (removedLocal) out.push(credentialsDeletedText(name));
  if (removedOidc) out.push(noOidcText(name, providerVia(ctx.provider)));
  if (removedLocal || removedOidc) out.push(sessionsEndText(name));
  if (allowsLocal(to) && !ctx.hasPassword) out.push(linkCreatedText(name));
  if (!out.length) out.push(keepsText(name));
  return out;
}

// The consequence texts, with the user's first name when known (`name` may be empty).

function credentialsDeletedText(name: string): string {
  return name
    ? $localize`:@@admin.localAccounts.deletedNamed:${name}:name:'s password, authenticator and recovery codes are deleted; open set-password links stop working.`
    : $localize`:@@admin.localAccounts.deleted:The user's password, authenticator and recovery codes are deleted; open set-password links stop working.`;
}

function noOidcText(name: string, via: string): string {
  return name
    ? $localize`:@@admin.localAccounts.noOidcNamed:${name}:name: can no longer sign in through ${via}:provider:.`
    : $localize`:@@admin.localAccounts.noOidc:The user can no longer sign in through ${via}:provider:.`;
}

function sessionsEndText(name: string): string {
  return name
    ? $localize`:@@admin.localAccounts.sessionsEndNamed:All of ${name}:name:'s sessions end.`
    : $localize`:@@admin.localAccounts.sessionsEnd:All of the sessions end.`;
}

function linkCreatedText(name: string): string {
  return name
    ? $localize`:@@admin.localAccounts.linkCreatedNamed:Switching creates a set-password link for ${name}:name:; hand it over, it works once and expires in 24 h.`
    : $localize`:@@admin.localAccounts.linkCreated:Switching creates a set-password link for the user; hand it over, it works once and expires in 24 h.`;
}

function keepsText(name: string): string {
  return name
    ? $localize`:@@admin.localAccounts.keepsNamed:${name}:name: keeps the current sessions and password.`
    : $localize`:@@admin.localAccounts.keeps:The user keeps the current sessions and password.`;
}

export interface LockState {
  locked: boolean;
  until: Date | null;
  /** "Locked until 14:32" (or with the date when not today). */
  label: string;
}

/** Locked while `lockedUntil` is in the future. */
export function lockState(lockedUntil: string | null | undefined, now = Date.now()): LockState {
  const t = lockedUntil ? Date.parse(lockedUntil) : NaN;
  if (Number.isNaN(t) || t <= now) return { locked: false, until: null, label: '' };
  const until = new Date(t);
  const time = clockTime(until, now);
  return {
    locked: true,
    until,
    label: $localize`:@@admin.localAccounts.lockedUntil:Locked until ${time}:time:`,
  };
}

/** "14:32" today, "28 Sep, 14:32" on another day. */
export function clockTime(d: Date, now = Date.now()): string {
  const locale = intlLocale();
  const time = d.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' });
  const today = new Date(now);
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate();
  if (sameDay) return time;
  return `${d.toLocaleDateString(locale, { day: 'numeric', month: 'short' })}, ${time}`;
}

/** Relative time to a future instant: "in 24 h", "in 35 min", "in 2 days", "expired". */
export function timeUntil(iso: string | null | undefined, now = Date.now()): string {
  const t = iso ? Date.parse(iso) : NaN;
  if (Number.isNaN(t)) return '';
  const s = Math.round((t - now) / 1000);
  if (s <= 0) return $localize`:@@admin.localAccounts.expired:expired`;
  const m = Math.round(s / 60);
  if (m < 1) return $localize`:@@admin.localAccounts.inUnderMinute:in less than a minute`;
  if (m < 60) return $localize`:@@admin.localAccounts.inMinutes:in ${m}:minutes: min`;
  const h = Math.round(m / 60);
  if (h < 48) return $localize`:@@admin.localAccounts.inHours:in ${h}:hours: h`;
  return icu(
    $localize`:@@admin.localAccounts.inDays:{count, plural, one {in # day} other {in # days}}`,
    { count: Math.round(h / 24) },
  );
}

export type BadgeTone = BatonHolder | 'neutral';

export interface AccountBadge {
  id: 'status' | 'auth' | 'mfa' | 'locked' | 'must-change' | 'no-password';
  label: string;
  tone: BadgeTone;
  /** Tooltip / screen-reader explanation. */
  hint: string;
}

const statusBadges = (): Record<UserStatus, { label: string; tone: BadgeTone; hint: string }> => ({
  ACTIVE: {
    label: $localize`:@@admin.localAccounts.statusActive:active`,
    tone: 'done',
    hint: $localize`:@@admin.localAccounts.statusActiveHint:Can sign in`,
  },
  INVITED: {
    label: $localize`:@@admin.localAccounts.statusInvited:invited`,
    tone: 'you',
    hint: $localize`:@@admin.localAccounts.statusInvitedHint:Has not finished the invitation yet: hand over the link`,
  },
  DEACTIVATED: {
    label: $localize`:@@admin.localAccounts.statusDeactivated:deactivated`,
    tone: 'idle',
    hint: $localize`:@@admin.localAccounts.statusDeactivatedHint:Cannot sign in`,
  },
});

export function statusBadge(status: UserStatus | null | undefined): AccountBadge {
  const all = statusBadges();
  const s = all[status ?? 'ACTIVE'] ?? all.ACTIVE;
  return { id: 'status', ...s };
}

/**
 * Sign-in badges: auth mode (always), MFA on, locked, must change password, no password yet
 * (local users who have not set one). Never colour alone: every badge carries its word.
 */
export function accountBadges(
  u: Pick<
    UserDetail,
    'authMode' | 'mfaEnabled' | 'lockedUntil' | 'mustChangePassword' | 'hasPassword'
  >,
  now = Date.now(),
): AccountBadge[] {
  const mode = u.authMode ?? 'OIDC';
  const out: AccountBadge[] = [
    {
      id: 'auth',
      label: authModeBadge(mode),
      tone: 'neutral',
      hint:
        mode === 'LOCAL'
          ? $localize`:@@admin.localAccounts.hintLocal:Signs in with e-mail and password`
          : mode === 'BOTH'
            ? $localize`:@@admin.localAccounts.hintBoth:Signs in with a password or the identity provider`
            : $localize`:@@admin.localAccounts.hintOidc:Signs in through the identity provider`,
    },
  ];
  if (u.mfaEnabled)
    out.push({
      id: 'mfa',
      label: $localize`:@@admin.localAccounts.badgeMfa:MFA on`,
      tone: 'done',
      hint: $localize`:@@admin.localAccounts.badgeMfaHint:Authenticator app enrolled`,
    });
  const lock = lockState(u.lockedUntil, now);
  if (lock.locked)
    out.push({
      id: 'locked',
      label: $localize`:@@admin.localAccounts.badgeLocked:locked`,
      tone: 'blocked',
      hint: lock.label,
    });
  if (u.mustChangePassword)
    out.push({
      id: 'must-change',
      label: $localize`:@@admin.localAccounts.badgeMustChange:must change password`,
      tone: 'you',
      hint: $localize`:@@admin.localAccounts.badgeMustChangeHint:Has to choose a new password at the next sign-in`,
    });
  if (allowsLocal(mode) && u.hasPassword === false)
    out.push({
      id: 'no-password',
      label: $localize`:@@admin.localAccounts.badgeNoPassword:no password yet`,
      tone: 'idle',
      hint: $localize`:@@admin.localAccounts.badgeNoPasswordHint:Has not used a set-password link yet`,
    });
  return out;
}

/** Confirmation text of "Issue set-password link" (the server disables the password, ends sessions). */
export function issueLinkConsequence(name: string, hasPassword: boolean): string {
  let first: string;
  if (hasPassword)
    first = name
      ? $localize`:@@admin.localAccounts.issuePasswordNamed:${name}:name:'s current password stops working immediately and ${name}:name: is signed out everywhere.`
      : $localize`:@@admin.localAccounts.issuePassword:The user's current password stops working immediately and The user is signed out everywhere.`;
  else
    first = name
      ? $localize`:@@admin.localAccounts.issueSignedOutNamed:${name}:name: is signed out everywhere.`
      : $localize`:@@admin.localAccounts.issueSignedOut:The user is signed out everywhere.`;
  const rest = $localize`:@@admin.localAccounts.issueLinkWorksOnce:The new link works once and expires in 24 h; you hand it over.`;
  return `${first} ${rest}`;
}

/** Confirmation text of "Reset MFA" (removes the authenticator and the recovery codes). */
export function resetMfaConsequence(name: string, mfaRequired: boolean): string {
  if (mfaRequired)
    return name
      ? $localize`:@@admin.localAccounts.resetMfaRequiredNamed:Removes ${name}:name:'s authenticator app and recovery codes. MFA is required for local accounts, so ${name}:name: enrols a new authenticator at the next sign-in.`
      : $localize`:@@admin.localAccounts.resetMfaRequired:Removes the user's authenticator app and recovery codes. MFA is required for local accounts, so the user enrols a new authenticator at the next sign-in.`;
  return name
    ? $localize`:@@admin.localAccounts.resetMfaNamed:Removes ${name}:name:'s authenticator app and recovery codes. ${capitalize(name)}:capitalName: then signs in with the password alone until enrolling again.`
    : $localize`:@@admin.localAccounts.resetMfa:Removes the user's authenticator app and recovery codes. The user then signs in with the password alone until enrolling again.`;
}

function capitalize(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s;
}

// ---- users list: client-side sign-in filter

export type SignInFilter = 'local' | 'oidc' | 'locked' | 'mfa';

/** Client-side filters over the loaded page (the API filters by text, status and group). */
export function matchesSignIn(u: UserDetail, filter: string | null, now = Date.now()): boolean {
  switch (filter as SignInFilter | null) {
    case 'local':
      return u.authMode === 'LOCAL' || u.authMode === 'BOTH';
    case 'oidc':
      return (u.authMode ?? 'OIDC') !== 'LOCAL';
    case 'locked':
      return lockState(u.lockedUntil, now).locked;
    case 'mfa':
      return !!u.mfaEnabled;
    default:
      return true;
  }
}

export const SIGN_IN_LABELS: Record<SignInFilter, string> = {
  local: $localize`:@@admin.localAccounts.filterLocal:Local accounts`,
  oidc: $localize`:@@admin.localAccounts.filterOidc:Identity provider`,
  locked: $localize`:@@admin.localAccounts.filterLocked:Locked`,
  mfa: $localize`:@@admin.localAccounts.filterMfa:MFA on`,
};
