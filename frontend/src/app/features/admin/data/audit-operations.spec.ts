import {
  AUDIT_PRESETS,
  SECURITY_OPERATIONS,
  operationGroups,
  operationLabel,
  presetActive,
  resultBadge,
} from './audit-operations';
import { EMPTY_AUDIT_FILTERS, auditFiltersFromUrl, toAuditQuery } from './audit-query';

describe('operationGroups', () => {
  it('puts sign-in and security first with every new auth operation', () => {
    const groups = operationGroups();
    expect(groups[0].label).toBe('Sign-in and security');
    for (const op of [
      'LOGIN_SUCCESS',
      'LOGIN_FAILURE',
      'ACCOUNT_LOCKED',
      'ACCOUNT_UNLOCK',
      'PASSWORD_CHANGE',
      'PASSWORD_RESET_ISSUE',
      'PASSWORD_RESET_COMPLETE',
      'MFA_ENABLE',
      'MFA_DISABLE',
      'MFA_RESET',
      'RECOVERY_CODE_USED',
      'RECOVERY_CODES_REGENERATE',
      'AUTH_MODE_CHANGE',
    ])
      expect(groups[0].operations).toContain(op);
  });

  it('lists every operation once and keeps unknown ones under Other', () => {
    const groups = operationGroups([
      ...SECURITY_OPERATIONS,
      'USER_INVITE',
      'CI_TOKEN_CREATE',
      'X_Y',
    ]);
    const all = groups.flatMap((g) => g.operations);
    expect(new Set(all).size).toBe(all.length);
    expect(groups.find((g) => g.label === 'Users and groups')?.operations).toEqual(['USER_INVITE']);
    expect(groups.find((g) => g.label === 'Tokens')?.operations).toEqual(['CI_TOKEN_CREATE']);
    expect(groups.at(-1)).toEqual({ label: 'Other', operations: ['X_Y'] });
  });
});

describe('operationLabel', () => {
  it('reads codes as sentences', () => {
    expect(operationLabel('LOGIN_FAILURE')).toBe('Sign-in failed');
    expect(operationLabel('PASSWORD_RESET_ISSUE')).toBe('Set-password link issued');
    expect(operationLabel('CI_TOKEN_CREATE')).toBe('CI token create');
    expect(operationLabel('')).toBe('');
  });
});

describe('presets and results', () => {
  it('detects the active preset', () => {
    const failed = AUDIT_PRESETS.find((p) => p.id === 'failed-sign-ins')!;
    expect(presetActive(failed, { operation: 'LOGIN_FAILURE', result: null })).toBe(true);
    expect(presetActive(failed, { operation: 'LOGIN_FAILURE', result: 'DENIED' })).toBe(false);
    expect(presetActive(failed, { operation: null })).toBe(false);
  });

  it('shows results as glyph + word', () => {
    expect(resultBadge('SUCCESS')).toEqual({ holder: 'done', label: 'success' });
    expect(resultBadge('DENIED')).toEqual({ holder: 'blocked', label: 'denied' });
    expect(resultBadge('FAILURE')).toEqual({ holder: 'blocked', label: 'failed' });
    expect(resultBadge(null)).toEqual({ holder: 'idle', label: 'unknown' });
  });
});

describe('auditFiltersFromUrl', () => {
  const empty = {
    q: null,
    operation: null,
    result: null,
    actorId: null,
    projectId: null,
    entityType: null,
    correlationId: null,
    from: null,
    to: null,
  };

  it('maps empty URL values to empty filters', () => {
    expect(auditFiltersFromUrl(empty)).toEqual(EMPTY_AUDIT_FILTERS);
  });

  it('maps the search to the entity id and the range to dates', () => {
    const f = auditFiltersFromUrl({
      ...empty,
      q: 'abc',
      operation: 'LOGIN_FAILURE',
      result: 'DENIED',
      from: '2026-09-01T00:00:00.000Z',
      to: '2026-09-02T00:00:00.000Z',
    });
    expect(toAuditQuery(f)).toEqual({
      size: 50,
      entityId: 'abc',
      operation: 'LOGIN_FAILURE',
      result: 'DENIED',
      from: '2026-09-01T00:00:00.000Z',
      to: '2026-09-02T00:00:00.000Z',
    });
  });

  it('drops invalid results and half ranges', () => {
    const f = auditFiltersFromUrl({ ...empty, result: 'MAYBE', from: '2026-09-01T00:00:00Z' });
    expect(f.result).toBeNull();
    expect(f.range).toBeNull();
  });
});
