import {
  DEFAULT_WRITE_APPROVERS,
  hasCustomWriteApprovers,
  withWriteApprovalMode,
  withWriteApprovers,
  writeApprovalMode,
  writeApprovers,
} from './write-approval';

describe('approval before external writes (node config)', () => {
  it('treats a missing or unknown setting as REQUIRED', () => {
    expect(writeApprovalMode(undefined)).toBe('REQUIRED');
    expect(writeApprovalMode({})).toBe('REQUIRED');
    expect(writeApprovalMode({ approval: 'maybe' })).toBe('REQUIRED');
    expect(writeApprovalMode({ approval: 'not_required' })).toBe('NOT_REQUIRED');
  });

  it('writes the mode explicitly and keeps the other keys', () => {
    expect(withWriteApprovalMode({ toStatus: 'Done' }, 'NOT_REQUIRED')).toEqual({
      toStatus: 'Done',
      approval: 'NOT_REQUIRED',
    });
  });

  it('defaults to owners and maintainers, stores an empty rule explicitly, resets to the default', () => {
    expect(writeApprovers({})).toEqual(DEFAULT_WRITE_APPROVERS);
    expect(hasCustomWriteApprovers({})).toBe(false);

    const anyone = withWriteApprovers({ toStatus: 'Done' }, null);
    expect(anyone['approvers']).toEqual({
      projectRoles: [],
      users: [],
      accountable: null,
      forbidSelfApproval: false,
    });
    expect(writeApprovers(anyone).projectRoles).toEqual([]);

    const custom = withWriteApprovers(anyone, {
      ...DEFAULT_WRITE_APPROVERS,
      forbidSelfApproval: true,
    });
    expect(writeApprovers(custom).forbidSelfApproval).toBe(true);
    expect(withWriteApprovers(custom, 'default')).toEqual({ toStatus: 'Done' });
  });
});
