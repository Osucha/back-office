import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { of, throwError, type Observable } from 'rxjs';
import type { LoginStepResult } from '../../../core/api/types';
import {
  LOGIN_MESSAGES,
  LoginFlow,
  destinationOf,
  formatCountdown,
  retryAfterSeconds,
  type LoginDestination,
  type LoginFlowApi,
} from './login-flow';

const httpError = (status: number, headers: Record<string, string> = {}, body: unknown = {}) =>
  new HttpErrorResponse({ status, headers: new HttpHeaders(headers), error: body });

function setup(responses: {
  login?: Observable<LoginStepResult>[];
  mfa?: Observable<LoginStepResult>[];
}) {
  let now = 1_000_000;
  const calls = { login: [] as unknown[], mfa: [] as unknown[] };
  const api: LoginFlowApi = {
    login: (input) => {
      calls.login.push(input);
      return responses.login!.shift()!;
    },
    loginMfa: (input) => {
      calls.mfa.push(input);
      return responses.mfa!.shift()!;
    },
  };
  const done: LoginDestination[] = [];
  const flow = new LoginFlow(
    api,
    (d) => done.push(d),
    () => now,
  );
  return { flow, done, calls, advance: (ms: number) => (now += ms) };
}

describe('LoginFlow', () => {
  it.each([
    ['AUTHENTICATED', 'app'],
    ['PASSWORD_CHANGE_REQUIRED', 'change-password'],
    ['MFA_ENROLMENT_REQUIRED', 'setup-mfa'],
  ] as const)('password step %s leads to %s with returnTo', (status, kind) => {
    const { flow, done, calls } = setup({ login: [of({ status })] });
    flow.submitPassword(' anna@example.com ', 'secret', '/runs?x=1');
    expect(calls.login[0]).toEqual({
      email: 'anna@example.com',
      password: 'secret',
      returnTo: '/runs?x=1',
    });
    expect(done).toEqual([{ kind, returnTo: '/runs?x=1' }]);
    expect(flow.busy()).toBe(true); // navigating away
  });

  it('MFA_REQUIRED moves to the code step; a TOTP code then signs in', () => {
    const { flow, done, calls } = setup({
      login: [of({ status: 'MFA_REQUIRED', mfaToken: 't1' })],
      mfa: [of({ status: 'AUTHENTICATED' })],
    });
    flow.submitPassword('anna@example.com', 'secret', '/home');
    expect(flow.step()).toBe('mfa');
    expect(flow.busy()).toBe(false);
    flow.submitCode('123456');
    expect(calls.mfa[0]).toEqual({ mfaToken: 't1', code: '123456' });
    expect(done).toEqual([{ kind: 'app', returnTo: '/home' }]);
  });

  it('sends a recovery code instead when toggled', () => {
    const { flow, calls } = setup({
      login: [of({ status: 'MFA_REQUIRED', mfaToken: 't1' })],
      mfa: [of({ status: 'AUTHENTICATED' })],
    });
    flow.submitPassword('a@b.c', 'pw', '/');
    flow.toggleRecovery();
    flow.submitCode(' abcd-efgh ');
    expect(calls.mfa[0]).toEqual({ mfaToken: 't1', recoveryCode: 'abcd-efgh' });
  });

  it('a rejected code returns to the password step with the e-mail kept', () => {
    const { flow } = setup({
      login: [of({ status: 'MFA_REQUIRED', mfaToken: 't1' })],
      mfa: [throwError(() => httpError(401, {}, { code: 'invalid-credentials' }))],
    });
    flow.submitPassword('anna@example.com', 'pw', '/');
    const resets = flow.passwordResets();
    flow.submitCode('000000');
    expect(flow.step()).toBe('password');
    expect(flow.email()).toBe('anna@example.com');
    expect(flow.mfaToken()).toBeNull();
    expect(flow.error()).toEqual({ kind: 'code-rejected', message: LOGIN_MESSAGES.codeRejected });
    expect(flow.passwordResets()).toBe(resets + 1);
  });

  it('401 on the password step shows the uniform message and stays', () => {
    const { flow } = setup({ login: [throwError(() => httpError(401))] });
    flow.submitPassword('anna@example.com', 'wrong', '/');
    expect(flow.step()).toBe('password');
    expect(flow.error()?.kind).toBe('invalid');
    expect(flow.error()?.message).toBe(LOGIN_MESSAGES.invalid);
    expect(flow.busy()).toBe(false);
  });

  it('429 starts a countdown from Retry-After that blocks attempts until it ends', () => {
    const { flow, calls, advance } = setup({
      login: [
        throwError(() => httpError(429, { 'Retry-After': '42' })),
        of({ status: 'AUTHENTICATED' }),
      ],
    });
    flow.submitPassword('a@b.c', 'pw', '/');
    expect(flow.error()?.kind).toBe('throttled');
    expect(flow.retryIn()).toBe(42);
    expect(flow.throttled()).toBe(true);
    flow.submitPassword('a@b.c', 'pw', '/');
    expect(calls.login.length).toBe(1); // refused locally
    advance(40_500);
    flow.tick();
    expect(flow.retryIn()).toBe(2);
    advance(2_000);
    flow.tick();
    expect(flow.throttled()).toBe(false);
    expect(flow.error()).toBeNull();
    flow.submitPassword('a@b.c', 'pw', '/');
    expect(calls.login.length).toBe(2);
  });

  it('429 on the code step keeps the code step (the token is still valid)', () => {
    const { flow } = setup({
      login: [of({ status: 'MFA_REQUIRED', mfaToken: 't1' })],
      mfa: [throwError(() => httpError(429, { 'Retry-After': '5' }))],
    });
    flow.submitPassword('a@b.c', 'pw', '/');
    flow.submitCode('123456');
    expect(flow.step()).toBe('mfa');
    expect(flow.retryIn()).toBe(5);
  });

  it('status 0 says the server is unreachable; other errors show the problem detail', () => {
    const { flow } = setup({
      login: [
        throwError(() => httpError(0)),
        throwError(() => httpError(400, {}, { detail: 'email is required' })),
      ],
    });
    flow.submitPassword('a@b.c', 'pw', '/');
    expect(flow.error()?.kind).toBe('unreachable');
    flow.submitPassword('', 'pw', '/');
    expect(flow.error()).toEqual({ kind: 'failed', message: 'email is required' });
  });

  it('can start at the code step with a token from the set-password page', () => {
    const { flow, done } = setup({ mfa: [of({ status: 'AUTHENTICATED' })] });
    flow.startMfa('t9', 'anna@example.com', '/account');
    expect(flow.step()).toBe('mfa');
    flow.submitCode('123456');
    expect(done).toEqual([{ kind: 'app', returnTo: '/account' }]);
  });
});

describe('login flow helpers', () => {
  it('maps step results to destinations', () => {
    expect(destinationOf({ status: 'MFA_REQUIRED', mfaToken: 'x' }, '/')).toBeNull();
    expect(destinationOf({ status: 'AUTHENTICATED' }, '/runs')).toEqual({
      kind: 'app',
      returnTo: '/runs',
    });
  });

  it('reads Retry-After as seconds or as an HTTP date', () => {
    const now = Date.parse('2026-09-27T10:00:00Z');
    expect(retryAfterSeconds('30', now)).toBe(30);
    expect(retryAfterSeconds('Sun, 27 Sep 2026 10:01:30 GMT', now)).toBe(90);
    expect(retryAfterSeconds(null, now)).toBe(60);
    expect(retryAfterSeconds('soon', now)).toBe(60);
    expect(retryAfterSeconds('0', now)).toBe(1);
  });

  it('formats the countdown', () => {
    expect(formatCountdown(42)).toBe('0:42');
    expect(formatCountdown(125)).toBe('2:05');
  });
});
