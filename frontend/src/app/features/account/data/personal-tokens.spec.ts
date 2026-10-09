import { maxTokenDays, tokenState, validateTokenForm } from './personal-tokens';

describe('personal tokens', () => {
  it('limits the lifetime by the platform setting and the contract', () => {
    expect(maxTokenDays(90)).toBe(90);
    expect(maxTokenDays(1000)).toBe(365);
    expect(maxTokenDays(null)).toBe(365);
    expect(validateTokenForm('', 100, 90)).toEqual({
      name: 'Name is required',
      days: 'At most 90 days',
    });
    expect(validateTokenForm('ci', 30, 90)).toEqual({});
  });

  it('state', () => {
    const now = Date.parse('2026-09-27T00:00:00Z');
    expect(tokenState({ expiresAt: '2026-10-01T00:00:00Z' }, now)).toBe('active');
    expect(tokenState({ expiresAt: '2026-09-01T00:00:00Z' }, now)).toBe('expired');
    expect(tokenState({ revokedAt: '2026-09-02T00:00:00Z' }, now)).toBe('revoked');
  });
});
