import type { ApprovalRequest } from '../../../core/api/types';
import { assignedTo, assignedToText, isReassigned } from './approval-assignment';

const base: ApprovalRequest = { id: 'a1', status: 'OPEN', question: 'Merge?' };

describe('approval assignment', () => {
  it('names the assignees first, then the roles, else anyone who can approve', () => {
    expect(
      assignedToText({
        ...base,
        assignees: [
          { userId: 'u1', displayName: 'Ann' },
          { userId: 'u2', displayName: 'Bob' },
        ],
        approvers: { projectRoles: ['approver'], forbidSelfApproval: false },
      }),
    ).toBe('Ann, Bob');
    expect(
      assignedToText({
        ...base,
        approvers: { projectRoles: ['approver', 'owner'], forbidSelfApproval: false },
      }),
    ).toBe('Approver, Owner');
    expect(assignedToText(base)).toBe('anyone who can approve');
  });

  it('knows whether the user is assigned and whether the gate was reassigned at run time', () => {
    const named: ApprovalRequest = { ...base, assignees: [{ userId: 'me', displayName: 'Me' }] };
    expect(assignedTo(named, 'me')).toBe(true);
    expect(assignedTo(named, 'other')).toBe(false);
    expect(assignedTo(named, null)).toBe(false);
    expect(isReassigned(named)).toBe(false);
    expect(
      isReassigned({
        ...named,
        assignment: { userIds: ['me'], assignedAt: '2026-10-07T10:00:00Z' },
      }),
    ).toBe(true);
  });
});
