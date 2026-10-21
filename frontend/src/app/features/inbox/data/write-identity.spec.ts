import type { WriteIdentity } from '../../../core/api/types';
import { approveBlocker, initialIdentity, usableIdentities } from './write-identity';

const options = (o: Partial<WriteIdentity>): WriteIdentity => ({
  defaultIdentity: 'PERSONAL',
  systemAllowed: false,
  personalAllowed: true,
  canApprove: false,
  ...o,
});

describe('write identity (ADR 0035)', () => {
  it('an ACTING_USER write is approved through the approver’s own connection', () => {
    const o = options({ personalConnectionName: 'My Jira', canApprove: true });
    expect(usableIdentities(o)).toEqual(['PERSONAL']);
    expect(initialIdentity(o)).toBe('PERSONAL');
    expect(approveBlocker(o, 'PERSONAL')).toBeNull();
  });

  it('without a personal connection and without fallback the approver cannot approve', () => {
    const o = options({
      personalProblem: 'you have no personal Jira connection with ISSUE_COMMENT',
    });
    expect(initialIdentity(o)).toBeNull();
    expect(approveBlocker(o, null)).toContain('no personal Jira connection');
  });

  it('with fallbackToSystem the system connection is offered instead', () => {
    const o = options({
      systemAllowed: true,
      systemConnectionName: 'bot',
      personalProblem: 'none',
    });
    expect(initialIdentity(o)).toBe('SYSTEM');
    expect(approveBlocker(o, 'SYSTEM')).toBeNull();
  });

  it('a SYSTEM step that allows the approver keeps the system connection as default', () => {
    const o = options({
      defaultIdentity: 'SYSTEM',
      systemAllowed: true,
      personalConnectionName: 'My Jira',
    });
    expect(usableIdentities(o)).toEqual(['PERSONAL', 'SYSTEM']);
    expect(initialIdentity(o)).toBe('SYSTEM');
  });
});
