import type { UserDetail } from '../../../core/api/types';
import {
  accountBadges,
  authModeBadge,
  authModeConsequences,
  authModeName,
  authModeSummary,
  availableAuthModes,
  clockTime,
  defaultInviteMode,
  inviteGetsSetPasswordLink,
  issueLinkConsequence,
  lockState,
  matchesSignIn,
  providerLabel,
  resetMfaConsequence,
  statusBadge,
  timeUntil,
} from './local-accounts';

const NOW = Date.parse('2026-09-27T12:00:00Z');
const ctx = { name: 'Anna', provider: 'Keycloak', hasPassword: false };

describe('auth mode texts', () => {
  it('names and summarises every mode with the provider', () => {
    expect(authModeName('LOCAL', 'Keycloak')).toBe('Local');
    expect(authModeName('OIDC', 'Keycloak')).toBe('Keycloak');
    expect(authModeName('BOTH', 'Keycloak')).toBe('Local and Keycloak');
    expect(authModeName('OIDC', 'the identity provider')).toBe('Identity provider');
    expect(authModeSummary('LOCAL', 'Keycloak')).toBe(
      'Signs in with e-mail and password here; cannot use Keycloak.',
    );
    expect(authModeSummary('OIDC', 'Keycloak')).toContain('through Keycloak only');
    expect(authModeSummary('BOTH', 'Keycloak')).toContain('either');
    expect(authModeBadge('LOCAL')).toBe('local');
    expect(authModeBadge(undefined)).toBe('OIDC');
  });

  it('falls back to a neutral provider word', () => {
    expect(providerLabel('Keycloak')).toBe('Keycloak');
    expect(providerLabel('')).toBe('the identity provider');
    expect(providerLabel('your identity provider')).toBe('the identity provider');
  });
});

describe('availableAuthModes / defaultInviteMode', () => {
  it('offers only methods the installation has', () => {
    expect(availableAuthModes({ local: true, oidc: true })).toEqual(['LOCAL', 'OIDC', 'BOTH']);
    expect(availableAuthModes({ local: false, oidc: true })).toEqual(['OIDC']);
    expect(availableAuthModes({ local: true, oidc: false })).toEqual(['LOCAL']);
    expect(availableAuthModes(null)).toEqual(['LOCAL', 'OIDC', 'BOTH']);
  });

  it('keeps the current mode even when it is no longer available', () => {
    expect(availableAuthModes({ local: false, oidc: true }, 'BOTH')).toEqual(['OIDC', 'BOTH']);
  });

  it('defaults invitations to OIDC when a provider exists, else LOCAL', () => {
    expect(defaultInviteMode({ local: true, oidc: true })).toBe('OIDC');
    expect(defaultInviteMode({ local: true, oidc: false })).toBe('LOCAL');
    expect(defaultInviteMode(null)).toBe('OIDC');
    expect(inviteGetsSetPasswordLink('LOCAL')).toBe(true);
    expect(inviteGetsSetPasswordLink('BOTH')).toBe(true);
    expect(inviteGetsSetPasswordLink('OIDC')).toBe(false);
  });
});

describe('authModeConsequences', () => {
  it('is empty without a change', () => {
    expect(authModeConsequences('OIDC', 'OIDC', ctx)).toEqual([]);
  });

  it('OIDC -> LOCAL without a password: provider gone, sessions end, link created', () => {
    const c = authModeConsequences('OIDC', 'LOCAL', ctx);
    expect(c).toEqual([
      'Anna can no longer sign in through Keycloak.',
      "All of Anna's sessions end.",
      'Switching creates a set-password link for Anna; hand it over, it works once and expires in 24 h.',
    ]);
  });

  it('LOCAL -> OIDC deletes the password, MFA and links and ends sessions', () => {
    const c = authModeConsequences('LOCAL', 'OIDC', { ...ctx, hasPassword: true });
    expect(c[0]).toContain("Anna's password, authenticator and recovery codes are deleted");
    expect(c).toContain("All of Anna's sessions end.");
    expect(c.some((x) => x.includes('set-password link for'))).toBe(false);
  });

  it('adding a method keeps sessions; a link only without a password', () => {
    expect(authModeConsequences('LOCAL', 'BOTH', { ...ctx, hasPassword: true })).toEqual([
      'Anna keeps the current sessions and password.',
    ]);
    expect(authModeConsequences('OIDC', 'BOTH', ctx)).toEqual([
      'Switching creates a set-password link for Anna; hand it over, it works once and expires in 24 h.',
    ]);
  });
});

describe('lockState / clockTime / timeUntil', () => {
  it('is locked only while lockedUntil is in the future', () => {
    expect(lockState(null, NOW).locked).toBe(false);
    expect(lockState('2026-09-27T11:00:00Z', NOW).locked).toBe(false);
    const s = lockState('2026-09-27T12:32:00Z', NOW);
    expect(s.locked).toBe(true);
    expect(s.label).toMatch(/^Locked until /);
    expect(s.until?.toISOString()).toBe('2026-09-27T12:32:00.000Z');
  });

  it('adds the date when not today', () => {
    const later = new Date(NOW + 3 * 86_400_000);
    expect(clockTime(later, NOW)).toContain(',');
    expect(clockTime(new Date(NOW + 60_000), NOW)).not.toContain(',');
  });

  it('describes a future instant', () => {
    expect(timeUntil('2026-09-28T12:00:00Z', NOW)).toBe('in 24 h');
    expect(timeUntil('2026-09-27T12:35:00Z', NOW)).toBe('in 35 min');
    expect(timeUntil('2026-10-01T12:00:00Z', NOW)).toBe('in 4 days');
    expect(timeUntil('2026-09-27T11:00:00Z', NOW)).toBe('expired');
    expect(timeUntil(null, NOW)).toBe('');
  });
});

describe('badges', () => {
  it('maps the user status to glyph tones with words', () => {
    expect(statusBadge('ACTIVE')).toMatchObject({ label: 'active', tone: 'done' });
    expect(statusBadge('INVITED')).toMatchObject({ label: 'invited', tone: 'you' });
    expect(statusBadge('DEACTIVATED')).toMatchObject({ label: 'deactivated', tone: 'idle' });
  });

  it('lists sign-in badges', () => {
    const u: UserDetail = {
      authMode: 'LOCAL',
      mfaEnabled: true,
      lockedUntil: '2026-09-27T12:30:00Z',
      mustChangePassword: true,
      hasPassword: true,
    };
    expect(accountBadges(u, NOW).map((b) => b.label)).toEqual([
      'local',
      'MFA on',
      'locked',
      'must change password',
    ]);
    expect(accountBadges({ authMode: 'BOTH', hasPassword: false }, NOW).map((b) => b.id)).toEqual([
      'auth',
      'no-password',
    ]);
    expect(accountBadges({}, NOW).map((b) => b.label)).toEqual(['OIDC']);
  });
});

describe('confirmation texts', () => {
  it('says what issuing a link does', () => {
    expect(issueLinkConsequence('Anna', true)).toContain(
      "Anna's current password stops working immediately and Anna is signed out everywhere.",
    );
    expect(issueLinkConsequence('Anna', false)).not.toContain('current password');
    expect(issueLinkConsequence('Anna', false)).toContain('expires in 24 h');
  });

  it('says what resetting MFA does', () => {
    expect(resetMfaConsequence('Anna', true)).toContain('enrols a new authenticator');
    expect(resetMfaConsequence('Anna', false)).toContain(
      'Anna then signs in with the password alone',
    );
    expect(resetMfaConsequence('', false)).toContain("the user's authenticator");
  });
});

describe('matchesSignIn', () => {
  const local: UserDetail = { authMode: 'LOCAL', mfaEnabled: true };
  const oidc: UserDetail = { authMode: 'OIDC' };
  const both: UserDetail = { authMode: 'BOTH', lockedUntil: '2026-09-27T13:00:00Z' };
  it('filters the loaded page', () => {
    const all = [local, oidc, both];
    expect(all.filter((u) => matchesSignIn(u, 'local', NOW))).toEqual([local, both]);
    expect(all.filter((u) => matchesSignIn(u, 'oidc', NOW))).toEqual([oidc, both]);
    expect(all.filter((u) => matchesSignIn(u, 'locked', NOW))).toEqual([both]);
    expect(all.filter((u) => matchesSignIn(u, 'mfa', NOW))).toEqual([local]);
    expect(all.filter((u) => matchesSignIn(u, null, NOW))).toEqual(all);
  });
});
