import {
  accountableOptions,
  addChecklistItem,
  compactApprovers,
  gateRuleProblems,
  normalizeApprovers,
  withProjectRoles,
} from './gate-rules';

describe('gate approver rules and checklist', () => {
  it('the accountable role must stay one of the selected roles', () => {
    const a = normalizeApprovers({ projectRoles: ['approver', 'owner'], accountable: 'owner' });
    expect(withProjectRoles(a, ['approver']).accountable).toBeNull();
    expect(withProjectRoles(a, ['owner']).accountable).toBe('owner');
    expect(accountableOptions(a, ['x'])).toEqual(['approver', 'owner']);
    expect(accountableOptions(normalizeApprovers(null), ['owner', 'viewer'])).toEqual([
      'owner',
      'viewer',
    ]);
    expect(gateRuleProblems({ projectRoles: ['approver'], accountable: 'owner' }, [])).toHaveLength(
      1,
    );
  });

  it('empty approvers are stored as null', () => {
    expect(compactApprovers(normalizeApprovers(undefined))).toBeNull();
    expect(compactApprovers({ forbidSelfApproval: true })).not.toBeNull();
  });

  it('checklist items get unique ids and are validated', () => {
    let items = addChecklistItem([]);
    items = addChecklistItem(items);
    expect(items.map((i) => i.id)).toEqual(['item-1', 'item-2']);
    expect(gateRuleProblems(null, items)).toEqual([
      'Checklist item 1: text is required',
      'Checklist item 2: text is required',
    ]);
    expect(
      gateRuleProblems(null, [
        { id: 'a', text: 'x', required: true },
        { id: 'a', text: 'y', required: true },
        { id: 'Bad Id', text: 'z', required: false },
      ]),
    ).toEqual([
      'Checklist item 2: duplicate id "a"',
      'Checklist item 3: id must be a slug (lowercase letters, digits, dashes)',
    ]);
  });
});
