import type { ApprovalRequest } from '../../../core/api/types';
import {
  CHECKLIST_FIRST,
  COMMENT_FIRST,
  missingRequired,
  optionState,
  quickDecisionOptions,
} from './approval-gating';

const gate = (overrides: Partial<ApprovalRequest> = {}): ApprovalRequest => ({
  id: 'a1',
  status: 'OPEN',
  options: ['approve', 'revise', 'reject'],
  canDecide: true,
  checklist: [
    { id: 'tests', text: 'Tests are green', required: true },
    { id: 'docs', text: 'Docs updated', required: false },
  ],
  ...overrides,
});

describe('approval gating', () => {
  it('approve needs every required checklist item', () => {
    const a = gate();
    expect(optionState(a, 'approve', new Set(), '')).toEqual({
      enabled: false,
      reason: CHECKLIST_FIRST,
    });
    expect(optionState(a, 'approve', new Set(['docs']), '').enabled).toBe(false);
    expect(optionState(a, 'approve', new Set(['tests']), '').enabled).toBe(true);
    expect(missingRequired(a, new Set()).map((i) => i.id)).toEqual(['tests']);
  });

  it('revise and reject need a comment but no checklist', () => {
    const a = gate();
    expect(optionState(a, 'reject', new Set(), '  ')).toEqual({
      enabled: false,
      reason: COMMENT_FIRST,
    });
    expect(optionState(a, 'reject', new Set(), 'no').enabled).toBe(true);
  });

  it('canDecide=false disables everything with the server reason', () => {
    const a = gate({ canDecide: false, cannotDecideReason: 'You started this run' });
    expect(optionState(a, 'approve', new Set(['tests']), 'x')).toEqual({
      enabled: false,
      reason: 'You started this run',
    });
    expect(quickDecisionOptions(a)).toEqual([]);
  });

  it('quick options skip comment options and checklist-gated approve', () => {
    expect(quickDecisionOptions(gate())).toEqual([]);
    expect(quickDecisionOptions(gate({ checklist: [] }))).toEqual(['approve']);
    expect(quickDecisionOptions(gate({ status: 'DECIDED' }))).toEqual([]);
  });
});
