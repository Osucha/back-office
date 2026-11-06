import type { Rule } from '../../../core/api/types';
import { automaticHint, emptyRule, ruleDraft, ruleSavable } from './rule-form';

describe('rule form (ADR 0055)', () => {
  it('starts without a key and not automatic', () => {
    const d = emptyRule();
    expect('key' in d).toBe(false);
    expect(d.automatic).toBe(false);
    expect(d.scope).toEqual({ type: 'GLOBAL', projectId: null });
  });

  it('copies the editable fields of a saved rule, never its key', () => {
    const r = {
      id: 'r1',
      key: 'no-secrets',
      name: 'No secrets',
      content: 'Never print secrets.',
      scope: { type: 'PROJECT', projectId: 'p1' },
      automatic: true,
      tags: ['security'],
      builtIn: false,
    } as Rule;
    const d = ruleDraft(r);
    expect('key' in d).toBe(false);
    expect('id' in d).toBe(false);
    expect(d).toEqual({
      name: 'No secrets',
      description: '',
      content: 'Never print secrets.',
      scope: { type: 'PROJECT', projectId: 'p1' },
      automatic: true,
      tags: ['security'],
    });
    d.tags!.push('x');
    expect(r.tags).toEqual(['security']);
  });

  it('needs a name, content and the project of a project rule', () => {
    expect(ruleSavable(emptyRule())).toBe(false);
    expect(ruleSavable({ ...emptyRule(), name: 'A' })).toBe(true);
    expect(ruleSavable({ ...emptyRule(), name: 'A', content: ' ' })).toBe(false);
    expect(
      ruleSavable({ ...emptyRule(), name: 'A', scope: { type: 'PROJECT', projectId: null } }),
    ).toBe(false);
  });

  it('explains where the rule applies', () => {
    expect(automaticHint({ automatic: false, scope: { type: 'GLOBAL' } }, '')).toContain(
      'agents that list it',
    );
    expect(automaticHint({ automatic: true, scope: { type: 'GLOBAL' } }, '')).toContain(
      'every project',
    );
    expect(
      automaticHint({ automatic: true, scope: { type: 'PROJECT', projectId: 'p1' } }, 'SHOP'),
    ).toContain('project SHOP');
  });
});
