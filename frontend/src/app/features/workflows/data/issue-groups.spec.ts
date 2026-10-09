import type { WorkflowDefinitionInput } from '../../../core/api/types';
import { groupIssues } from './issue-groups';
import { mapValidation } from './workflow-validation';

const issue = (
  message: string,
  where: { nodeId?: string; edgeId?: string; field?: string } = {},
) => ({
  message,
  nodeId: null,
  edgeId: null,
  field: null,
  ...where,
});

const def = {
  name: 'wf',
  nodes: [
    { id: 'b', name: 'Bravo', kind: 'TASK', position: { x: 300, y: 0 } },
    { id: 'a', name: '', kind: 'TASK', position: { x: 100, y: 50 } },
  ],
  edges: [{ id: 'e1', from: 'a', fromPort: 'success', to: 'b' }],
} as unknown as WorkflowDefinitionInput;

describe('groupIssues', () => {
  it('puts workflow issues first, then nodes in canvas order, errors before warnings', () => {
    const map = mapValidation({
      valid: false,
      errors: [issue('bad b', { nodeId: 'b' }), issue('no name', { field: 'name' })],
      warnings: [issue('edge warn', { edgeId: 'e1' }), issue('b warn', { nodeId: 'b' })],
    });
    const groups = groupIssues(map, def);
    expect(groups.map((g) => [g.key, g.title, g.errors, g.warnings])).toEqual([
      ['__workflow', 'Workflow', 1, 0],
      ['a', 'a', 0, 1],
      ['b', 'Bravo', 1, 1],
    ]);
    expect(groups[0].items).toEqual([{ severity: 'error', message: 'no name', where: 'name' }]);
    expect(groups[1].items).toEqual([
      { severity: 'warning', message: 'edge warn', where: 'success → b' },
    ]);
    expect(groups[2].items.map((i) => i.severity)).toEqual(['error', 'warning']);
  });

  it('names dangling node references and unknown edges', () => {
    const map = mapValidation({
      valid: false,
      errors: [issue('gone', { nodeId: 'zz' }), issue('lost edge', { edgeId: 'e9' })],
      warnings: [],
    });
    const groups = groupIssues(map, def);
    const unknown = groups.find((g) => g.key === 'zz')!;
    expect(unknown.nodeId).toBeNull();
    expect(unknown.title).toBe('Unknown node zz');
    const workflow = groups.find((g) => g.key === '__workflow')!;
    expect(workflow.items).toEqual([{ severity: 'error', message: 'lost edge', where: 'e9' }]);
  });
});
