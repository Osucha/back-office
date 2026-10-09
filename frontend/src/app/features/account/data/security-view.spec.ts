import {
  allowsLocal,
  disableMfaBlocked,
  methodWord,
  recoveryCodesState,
  signInMethodText,
} from './security-view';

describe('account security view', () => {
  it('describes the sign-in method', () => {
    expect(signInMethodText('LOCAL', 'Keycloak')).toBe('E-mail and password');
    expect(signInMethodText('BOTH', 'Keycloak')).toBe('E-mail and password, or Keycloak');
    expect(signInMethodText('OIDC', 'Keycloak')).toBe('Keycloak');
    expect(allowsLocal('OIDC')).toBe(false);
    expect(allowsLocal('BOTH')).toBe(true);
    expect(methodWord('LOCAL', 'Keycloak')).toBe('password');
    expect(methodWord('OIDC', 'Keycloak')).toBe('Keycloak');
  });

  it('warns when recovery codes run low', () => {
    expect(recoveryCodesState({ recoveryCodesRemaining: 10 })).toEqual({
      text: '10 recovery codes left',
      low: false,
    });
    expect(recoveryCodesState({ recoveryCodesRemaining: 1 })).toEqual({
      text: '1 recovery code left',
      low: true,
    });
    expect(recoveryCodesState({ recoveryCodesRemaining: 0 }).low).toBe(true);
  });

  it('explains why MFA cannot be turned off when required', () => {
    expect(disableMfaBlocked({ mfaRequired: true })).toContain('requires');
    expect(disableMfaBlocked({ mfaRequired: false })).toBeNull();
  });
});
