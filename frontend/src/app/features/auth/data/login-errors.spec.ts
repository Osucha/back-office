import { GENERIC_LOGIN_ERROR, loginErrorMessage, loginErrorType } from './login-errors';

describe('login error messages', () => {
  it('maps the known codes and hides unknown ones behind a generic message', () => {
    expect(loginErrorMessage('not-invited')).toContain('not been invited');
    expect(loginErrorMessage('deactivated')).toContain('deactivated');
    expect(loginErrorMessage('login-failed')).toContain('Sign-in failed');
    expect(loginErrorMessage('session-expired')).toContain('session has expired');
    expect(loginErrorMessage('<script>')).toBe(GENERIC_LOGIN_ERROR);
    expect(loginErrorMessage(undefined)).toBeNull();
    expect(loginErrorType('session-expired')).toBe('info');
    expect(loginErrorType('deactivated')).toBe('error');
  });
});
