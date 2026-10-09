import { effectChip, effectiveChip } from './effective-permissions';
import {
  DEFAULT_SETTINGS,
  lockSeriesText,
  settingsWarnings,
  toSettings,
} from './platform-settings';

const form = {
  localLoginEnabled: true,
  mfaRequiredForLocal: false,
  passwordPolicy: {
    minLength: 12,
    maxLength: 128,
    rejectCommon: true,
    rejectContainingEmail: true,
  },
  lockout: { maxFailures: 5, lockMinutes: 15 },
  registration: 'INVITE_ONLY' as const,
  allowedEmailDomains: [' Example.COM ', '@example.com', ''],
  syncGroupsFromIdentityProvider: false,
  personalTokenMaxDays: 90,
  specKitAllowed: false,
};

describe('toSettings', () => {
  it('keeps unknown saved fields and normalises domains', () => {
    const saved = { ...DEFAULT_SETTINGS, extra: 1 } as never;
    const out = toSettings(saved, form) as Record<string, unknown>;
    expect(out['extra']).toBe(1);
    expect(out['allowedEmailDomains']).toEqual(['example.com']);
    expect(out['lockout']).toEqual({ maxFailures: 5, lockMinutes: 15 });
    expect(out['specKitAllowed']).toBe(false);
  });
});

describe('settingsWarnings', () => {
  it('warns when nobody could sign in', () => {
    const w = settingsWarnings(
      { ...form, localLoginEnabled: false },
      { oidc: false, provider: 'x' },
    );
    expect(w[0]).toContain('nobody can sign in');
    expect(
      settingsWarnings({ ...form, localLoginEnabled: false }, { oidc: true, provider: 'x' }),
    ).toEqual([]);
  });

  it('explains dormant MFA and an inverted length range', () => {
    const w = settingsWarnings(
      {
        localLoginEnabled: false,
        mfaRequiredForLocal: true,
        passwordPolicy: { ...form.passwordPolicy, minLength: 200 },
      },
      { oidc: true, provider: 'x' },
    );
    expect(w).toHaveLength(2);
  });
});

describe('lockSeriesText', () => {
  it('doubles up to 24 h', () => {
    expect(lockSeriesText(15)).toBe('15 min, 30 min, 1 h, 2 h … 24 h');
    expect(lockSeriesText(600)).toBe('10 h, 20 h, 24 h');
    expect(lockSeriesText(1440)).toBe('24 h');
    expect(lockSeriesText(null)).toBe('15 min, 30 min, 1 h, 2 h … 24 h');
  });
});

describe('effect chips', () => {
  it('maps grant decisions to baton-like chips', () => {
    expect(effectChip('ALLOW')).toEqual({ kind: 'allow', holder: 'done', label: 'allow' });
    expect(effectChip('DENY')).toEqual({ kind: 'deny', holder: 'blocked', label: 'deny' });
    expect(effectChip('NONE')).toEqual({ kind: 'inherit', holder: 'idle', label: 'inherit' });
    expect(effectChip(undefined).kind).toBe('inherit');
  });

  it('tells an explicit deny from no grant', () => {
    const base = { system: 'NONE' as const, user: 'NONE' as const, groups: [] };
    expect(effectiveChip({ ...base, effective: true }).label).toBe('allowed');
    expect(effectiveChip({ ...base, effective: false }).label).toBe('not granted');
    expect(
      effectiveChip({ ...base, effective: false, groups: [{ name: 'A', effect: 'DENY' }] }).label,
    ).toBe('denied');
  });
});
