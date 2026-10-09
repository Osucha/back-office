import type {
  ValidationResult,
  WorkflowDefinitionInput,
  WorkflowNode,
} from '../../../core/api/types';
import { ciCodeReviewTemplate, indexPalette, issueToMergeRequestTemplate } from './graph-model';
import {
  issueCounts,
  mapValidation,
  mergeValidation,
  reachableNodes,
  validateLocally,
} from './workflow-validation';

const n = (
  id: string,
  kind: WorkflowNode['kind'],
  extra: Partial<WorkflowNode> = {},
): WorkflowNode => ({
  id,
  kind,
  name: id,
  position: { x: 0, y: 0 },
  maxVisits: 3,
  ...extra,
});

describe('mapValidation / issueCounts', () => {
  const result: ValidationResult = {
    valid: false,
    errors: [
      { nodeId: 'a', field: 'task.templateKey', message: 'missing' },
      { edgeId: 'e1', message: 'bad edge' },
      { message: 'global problem' },
    ],
    warnings: [
      { nodeId: 'a', message: 'meh' },
      { nodeId: 'b', message: 'unreached' },
    ],
  };

  it('groups issues by node, edge and global with counts', () => {
    const map = mapValidation(result);
    expect(map.errorCount).toBe(3);
    expect(map.warningCount).toBe(2);
    expect(map.nodes['a'].errors).toHaveLength(1);
    expect(map.nodes['a'].warnings).toHaveLength(1);
    expect(map.nodes['b'].warnings).toHaveLength(1);
    expect(map.edges['e1'].errors[0].message).toBe('bad edge');
    expect(map.global.errors[0].message).toBe('global problem');
    expect(issueCounts(map)).toEqual({
      nodes: { a: { errors: 1, warnings: 1 }, b: { errors: 0, warnings: 1 } },
      edges: { e1: { errors: 1, warnings: 0 } },
    });
  });

  it('handles null results', () => {
    expect(mapValidation(null).errorCount).toBe(0);
  });
});

describe('mergeValidation', () => {
  it('de-duplicates identical messages across local and server results', () => {
    const a: ValidationResult = {
      valid: false,
      errors: [{ nodeId: 'x', message: 'dup' }],
      warnings: [{ message: 'w' }],
    };
    const b: ValidationResult = {
      valid: false,
      errors: [
        { nodeId: 'x', message: 'dup' },
        { nodeId: 'y', message: 'other' },
      ],
      warnings: [{ message: 'w' }],
    };
    const merged = mergeValidation(a, b, null);
    expect(merged.errors).toHaveLength(2);
    expect(merged.warnings).toHaveLength(1);
    expect(merged.valid).toBe(false);
  });

  it('keeps one issue per node and field when local and server word it differently', () => {
    const local: ValidationResult = {
      valid: false,
      errors: [{ nodeId: 'x', field: 'name', message: 'Node name is required' }],
      warnings: [],
    };
    const server: ValidationResult = {
      valid: false,
      errors: [
        { nodeId: 'x', field: 'name', message: 'must not be blank' },
        { nodeId: 'x', field: 'gate.options', message: 'must not be empty' },
      ],
      warnings: [],
    };
    expect(mergeValidation(local, server).errors.map((e) => e.message)).toEqual([
      'Node name is required',
      'must not be empty',
    ]);
  });
});

describe('validateLocally: ports as the server sees them', () => {
  const def = (nodes: WorkflowNode[], edges: WorkflowDefinitionInput['edges']) =>
    ({ name: 'x', nodes, edges, enabled: true }) as WorkflowDefinitionInput;

  it('treats a default branch that is not a branch id as its own port', () => {
    const r = validateLocally(
      def(
        [
          n('start', 'START'),
          n('d', 'DECISION', {
            decision: {
              branches: [{ id: 'yes', label: 'yes', expression: 'true' }],
              defaultBranch: 'no',
            },
          }),
          n('a', 'END', { end: { outcome: 'SUCCESS' } }),
          n('b', 'END', { end: { outcome: 'FAILURE' } }),
        ],
        [
          { id: 'e1', from: 'start', fromPort: 'out', to: 'd' },
          { id: 'e2', from: 'd', fromPort: 'yes', to: 'a' },
          { id: 'e3', from: 'd', fromPort: 'no', to: 'b' },
        ],
      ),
    );
    expect(r.errors).toEqual([]);
    expect(r.warnings).toEqual([]);
  });

  it('gives gates a timeout port and does not warn about reject, fail or timeout', () => {
    const r = validateLocally(
      def(
        [
          n('start', 'START'),
          n('g', 'HUMAN_GATE', {
            gate: { question: 'ok?', options: ['approve', 'revise', 'reject'] },
          }),
          n('done', 'END', { end: { outcome: 'SUCCESS' } }),
        ],
        [
          { id: 'e1', from: 'start', fromPort: 'out', to: 'g' },
          { id: 'e2', from: 'g', fromPort: 'APPROVE', to: 'done' },
          { id: 'e3', from: 'g', fromPort: 'timeout', to: 'done' },
        ],
      ),
    );
    expect(r.errors).toEqual([]);
    expect((r.warnings ?? []).map((w) => w.message)).toEqual([
      'port revise is not connected; the run fails when it is taken',
    ]);
  });

  it('requires the success port of reachable tasks and CI waits', () => {
    const r = validateLocally(
      def(
        [
          n('start', 'START'),
          n('t', 'TASK', { task: { templateKey: 'code-review' } }),
          n('done', 'END', { end: { outcome: 'SUCCESS' } }),
        ],
        [{ id: 'e1', from: 'start', fromPort: 'out', to: 't' }],
      ),
    );
    expect((r.errors ?? []).map((e) => [e.nodeId, e.field])).toEqual([['t', 'ports.success']]);
  });
});

describe('validateLocally', () => {
  const palette = indexPalette({
    tasks: [{ key: 'code-review', ports: ['success', 'failure'] }],
    controlNodes: [],
  });

  it('accepts the bundled templates', () => {
    for (const def of [issueToMergeRequestTemplate(), ciCodeReviewTemplate()]) {
      const r = validateLocally(def, palette);
      expect(r.errors).toEqual([]);
    }
  });

  it('requires exactly one START and at least one END', () => {
    const none: WorkflowDefinitionInput = {
      name: 'x',
      nodes: [n('a', 'TASK', { task: { templateKey: 'code-review' } })],
      edges: [],
      enabled: true,
    };
    const msgs = validateLocally(none).errors.map((e) => e.message);
    expect(msgs).toContain('A workflow needs exactly one START node');
    expect(msgs).toContain('A workflow needs at least one END node');
    const two = {
      ...none,
      nodes: [n('s1', 'START'), n('s2', 'START'), n('e', 'END', { end: { outcome: 'SUCCESS' } })],
    };
    expect(validateLocally(two).errors.some((e) => e.nodeId === 's2')).toBe(true);
  });

  it('flags duplicate ports, unknown ports and invalid targets on edges', () => {
    const def: WorkflowDefinitionInput = {
      name: 'x',
      nodes: [
        n('start', 'START'),
        n('t', 'TASK', { task: { templateKey: 'code-review' } }),
        n('end', 'END', { end: { outcome: 'SUCCESS' } }),
      ],
      edges: [
        { id: 'e1', from: 'start', fromPort: 'out', to: 't' },
        { id: 'e2', from: 't', fromPort: 'success', to: 'end' },
        { id: 'e3', from: 't', fromPort: 'success', to: 'start' },
        { id: 'e4', from: 't', fromPort: 'nope', to: 'end' },
        { id: 'e5', from: 'end', fromPort: 'x', to: 't' },
      ],
      enabled: true,
    };
    const byEdge = mapValidation(validateLocally(def, palette)).edges;
    expect(byEdge['e3'].errors.map((e) => e.message)).toEqual(
      expect.arrayContaining([
        expect.stringContaining('already has an edge'),
        'START nodes cannot be a target',
      ]),
    );
    expect(byEdge['e4'].errors[0].message).toContain('no port "nope"');
    expect(byEdge['e5'].errors[0].message).toContain('END nodes have no output ports');
  });

  it('checks decision branches, gates, ci signals and reachability', () => {
    const def: WorkflowDefinitionInput = {
      name: '',
      nodes: [
        n('start', 'START'),
        n('d', 'DECISION', {
          decision: {
            branches: [
              { id: 'Bad Id', label: 'x', expression: '' },
              { id: 'ok', label: 'ok', expression: 'true' },
            ],
            defaultBranch: 'missing',
          },
        }),
        n('g', 'HUMAN_GATE', { gate: { question: '', options: [] } }),
        n('w', 'WAIT_FOR_CI', { ci: { signal: 'Not Slug' } }),
        n('end', 'END', { end: { outcome: 'SUCCESS' } }),
        n('lost', 'TASK', { task: { templateKey: 'code-review' } }),
      ],
      edges: [
        { id: 'e1', from: 'start', fromPort: 'out', to: 'd' },
        { id: 'e2', from: 'd', fromPort: 'ok', to: 'g' },
        { id: 'e3', from: 'g', fromPort: 'approve', to: 'w' },
        { id: 'e4', from: 'w', fromPort: 'success', to: 'end' },
      ],
      triggers: [{ type: 'JIRA_TRANSITION', status: '' }],
      enabled: true,
    };
    const r = validateLocally(def, palette);
    const map = mapValidation(r);
    expect(map.global.errors.map((e) => e.field)).toEqual(
      expect.arrayContaining(['name', 'triggers']),
    );
    const dMsgs = map.nodes['d'].errors.map((e) => e.message).join('\n');
    expect(dMsgs).toContain('must be a slug');
    expect(dMsgs).toContain('needs an expression');
    // The default branch is a port of its own and must be connected like every branch.
    expect(dMsgs).toContain('port missing must be connected');
    expect(dMsgs).toContain('port Bad Id must be connected');
    expect(map.nodes['g'].errors.map((e) => e.field)).toEqual(
      expect.arrayContaining(['gate.question', 'gate.options']),
    );
    expect(map.nodes['w'].errors[0].field).toBe('ci.signal');
    expect(map.nodes['lost'].warnings[0].message).toContain('never reached');
    expect(reachableNodes(def).has('lost')).toBe(false);
    expect(reachableNodes(def).has('end')).toBe(true);
  });
});
