import type {
  NodeExecution,
  WorkflowEdge,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';
import {
  EMPTY_DEFINITION,
  buildEdgeData,
  buildNodeData,
  ciCodeReviewTemplate,
  createNode,
  duplicateNode,
  edgeId,
  indexPalette,
  issueToMergeRequestTemplate,
  moveNode,
  normalizeDefinition,
  portFromHandleId,
  portHandleId,
  portsFor,
  requiredPortsFor,
  visiblePortsFor,
  removeEdge,
  removeNode,
  replaceNode,
  takenPorts,
  uniqueNodeId,
  upsertEdge,
} from './graph-model';

const PALETTE: WorkflowPalette = {
  controlNodes: [
    { kind: 'START', ports: ['out'] },
    { kind: 'WAIT_FOR_CI', ports: ['success', 'fail', 'timeout'] },
  ],
  tasks: [
    {
      key: 'code-review',
      name: 'Code review',
      agent: { key: 'model-answer', name: 'Model answer', harness: null },
      category: 'REVIEW',
      ports: ['success', 'failure', 'blocked'],
    },
    { key: 'empty-ports', name: 'Empty ports', ports: [] },
  ],
  actions: [
    {
      type: 'TRANSITION_ISSUE',
      title: 'Transition issue',
      description: 'Moves the issue',
      ports: ['success', 'failure'],
      configSchema: { type: 'object', properties: { toStatus: { type: 'string' } } },
      defaults: { toStatus: 'In Review', approval: 'REQUIRED' },
      requiredCapabilities: ['ISSUE_TRANSITION'],
      externalWrite: true,
    },
  ],
  stateFields: [],
};

const node = (
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

describe('portsFor', () => {
  const palette = indexPalette(PALETTE);

  it('uses template ports for TASK nodes and falls back to success/failure', () => {
    expect(portsFor(node('r', 'TASK', { task: { templateKey: 'code-review' } }), palette)).toEqual([
      'success',
      'failure',
      'blocked',
    ]);
    expect(portsFor(node('f', 'TASK', { task: { templateKey: 'empty-ports' } }), palette)).toEqual([
      'success',
      'failure',
    ]);
    expect(portsFor(node('x', 'TASK', { task: { templateKey: 'unknown' } }), null)).toEqual([
      'success',
      'failure',
    ]);
  });

  it('derives DECISION ports from branches and HUMAN_GATE ports from options', () => {
    expect(
      portsFor(
        node('d', 'DECISION', {
          decision: {
            branches: [
              { id: 'a', label: 'A', expression: 'true' },
              { id: 'b', label: 'B', expression: 'true' },
            ],
          },
        }),
      ),
    ).toEqual(['a', 'b']);
    // Like the server: gates always have a timeout port.
    expect(
      portsFor(node('g', 'HUMAN_GATE', { gate: { question: 'q', options: ['ok', 'no'] } })),
    ).toEqual(['ok', 'no', 'timeout']);
    expect(portsFor(node('g2', 'HUMAN_GATE', { gate: { question: 'q', options: [] } }))).toEqual([
      'approve',
      'revise',
      'reject',
      'timeout',
    ]);
  });

  it('adds the default branch as a port when it is not a branch id (server semantics)', () => {
    const d = (defaultBranch: string | null) =>
      node('d', 'DECISION', {
        decision: { branches: [{ id: 'yes', label: 'y', expression: 'true' }], defaultBranch },
      });
    expect(portsFor(d('no'))).toEqual(['yes', 'no']);
    expect(portsFor(d('yes'))).toEqual(['yes']);
    expect(portsFor(d(null))).toEqual(['yes']);
  });

  it('shows a gate timeout port only when the gate has a timeout or it is connected', () => {
    const g = node('g', 'HUMAN_GATE', { gate: { question: 'q', options: ['approve'] } });
    expect(visiblePortsFor(g, null, new Set())).toEqual(['approve']);
    expect(visiblePortsFor(g, null, new Set(['timeout']))).toEqual(['approve', 'timeout']);
    const timed = { ...g, gate: { ...g.gate!, timeoutSeconds: 60 } };
    expect(visiblePortsFor(timed, null, new Set())).toEqual(['approve', 'timeout']);
    expect(requiredPortsFor(g)).toEqual(['approve']);
  });

  it('END and NOTE have no ports; START/WAIT_FOR_CI take the palette descriptor', () => {
    expect(portsFor(node('e', 'END'))).toEqual([]);
    expect(portsFor(node('n', 'NOTE'))).toEqual([]);
    expect(portsFor(node('s', 'START'), palette)).toEqual(['out']);
    expect(portsFor(node('w', 'WAIT_FOR_CI'))).toEqual(['success', 'fail', 'timeout']);
  });
});

describe('edges', () => {
  it('keeps one edge per (from, fromPort): a new connection replaces the old target', () => {
    let edges: WorkflowEdge[] = [];
    edges = upsertEdge(edges, { from: 'a', fromPort: 'success', to: 'b' });
    edges = upsertEdge(edges, { from: 'a', fromPort: 'failure', to: 'c' });
    edges = upsertEdge(edges, { from: 'a', fromPort: 'success', to: 'd' });
    expect(edges).toHaveLength(2);
    expect(edges.find((e) => e.fromPort === 'success')?.to).toBe('d');
    expect(edges[1].id).toBe(edgeId('a', 'success', 'd'));
  });

  it('removes edges by id', () => {
    const edges = upsertEdge([], { from: 'a', fromPort: 'out', to: 'b' });
    expect(removeEdge(edges, edges[0].id)).toEqual([]);
  });

  it('maps ports to handle ids and back', () => {
    expect(portHandleId('success')).toBe('port:success');
    expect(portFromHandleId('port:success')).toBe('success');
    expect(portFromHandleId(undefined)).toBeNull();
  });
});

describe('node mutations', () => {
  it('generates unique slugs', () => {
    expect(uniqueNodeId('TASK', [])).toBe('task');
    expect(uniqueNodeId('code-review', ['code-review'])).toBe('code-review-2');
    expect(uniqueNodeId('code-review', ['code-review', 'code-review-2'])).toBe('code-review-3');
    expect(uniqueNodeId('1abc', [])).toMatch(/^[a-z]/);
  });

  it('creates nodes with kind-specific defaults', () => {
    const task = createNode('TASK', { x: 10.6, y: 20.2 }, [], PALETTE.tasks![0]);
    expect(task.id).toBe('code-review');
    expect(task.task?.templateKey).toBe('code-review');
    expect(task.position).toEqual({ x: 11, y: 20 });
    expect(createNode('DECISION', { x: 0, y: 0 }, []).decision?.branches.length).toBe(2);
    expect(createNode('END', { x: 0, y: 0 }, []).end?.outcome).toBe('SUCCESS');
    expect(createNode('WAIT_FOR_CI', { x: 0, y: 0 }, []).ci?.signal).toBe('tests');
  });

  it('creates an ACTION node with the action defaults and its title (ADR 0056)', () => {
    const descriptor = PALETTE.actions![0];
    const a = createNode('ACTION', { x: 0, y: 0 }, ['transition-issue'], null, descriptor);
    expect(a.kind).toBe('ACTION');
    expect(a.id).toBe('transition-issue-2');
    expect(a.name).toBe('Change issue status');
    expect(a.action).toEqual({
      type: 'TRANSITION_ISSUE',
      config: { toStatus: 'In Review', approval: 'REQUIRED' },
    });
    // the defaults are copied, not shared
    a.action!.config!['toStatus'] = 'Done';
    expect(descriptor.defaults['toStatus']).toBe('In Review');
    expect(a.task).toBeUndefined();
  });

  it('ACTION nodes take the action ports, require success and round-trip (ADR 0056)', () => {
    const palette = indexPalette(PALETTE);
    const a = node('t', 'ACTION', {
      action: { type: 'TRANSITION_ISSUE', config: { toStatus: 'Done', jiraConnection: 'c1' } },
    });
    expect(portsFor(a, palette)).toEqual(['success', 'failure']);
    expect(portsFor(node('u', 'ACTION'), null)).toEqual(['success', 'failure']);
    expect(requiredPortsFor(a, palette)).toEqual(['success']);
    const def = normalizeDefinition({
      name: 'x',
      nodes: [node('start', 'START'), a],
      edges: [{ id: '', from: 't', fromPort: 'failure', to: 'start' }],
    });
    const back = normalizeDefinition(JSON.parse(JSON.stringify(def)));
    expect(back.nodes[1].action).toEqual({
      type: 'TRANSITION_ISSUE',
      config: { toStatus: 'Done', jiraConnection: 'c1' },
    });
    // a config change keeps the edges of the action's ports
    const changed = replaceNode(back, 't', {
      ...back.nodes[1],
      action: { type: 'TRANSITION_ISSUE', config: {} },
    });
    expect(changed.edges).toHaveLength(1);
  });

  it('renaming a node rewrites its edges and drops edges from removed ports', () => {
    const def = {
      ...EMPTY_DEFINITION,
      nodes: [
        node('start', 'START'),
        node('d', 'DECISION', {
          decision: {
            branches: [
              { id: 'a', label: 'A', expression: 'true' },
              { id: 'b', label: 'B', expression: 'true' },
            ],
          },
        }),
        node('done', 'END', { end: { outcome: 'SUCCESS' } }),
      ],
      edges: [
        { id: edgeId('start', 'out', 'd'), from: 'start', fromPort: 'out', to: 'd' },
        { id: edgeId('d', 'a', 'done'), from: 'd', fromPort: 'a', to: 'done' },
        { id: edgeId('d', 'b', 'done'), from: 'd', fromPort: 'b', to: 'done' },
      ],
    };
    const renamed = replaceNode(def, 'd', {
      ...def.nodes[1],
      id: 'decide',
      decision: { branches: [{ id: 'a', label: 'A', expression: 'true' }] },
    });
    expect(renamed.edges.map((e) => `${e.from}.${e.fromPort}->${e.to}`)).toEqual([
      'start.out->decide',
      'decide.a->done',
    ]);
    expect(renamed.edges[0].id).toBe(edgeId('start', 'out', 'decide'));
  });

  it('removing a node removes its edges; moving rounds and is a no-op when unchanged', () => {
    const def = {
      ...EMPTY_DEFINITION,
      edges: [{ id: 'e', from: 'start', fromPort: 'out', to: 'done' }],
    };
    expect(removeNode(def, 'done').edges).toEqual([]);
    expect(moveNode(def, 'start', { x: 40, y: 200 })).toBe(def);
    expect(moveNode(def, 'start', { x: 1.4, y: 2.6 }).nodes[0].position).toEqual({ x: 1, y: 3 });
  });

  it('duplicates a node without its edges and never duplicates START', () => {
    const def = {
      ...EMPTY_DEFINITION,
      edges: [{ id: 'e', from: 'start', fromPort: 'out', to: 'done' }],
    };
    const dup = duplicateNode(def, 'done');
    expect(dup.nodes).toHaveLength(3);
    expect(dup.nodes[2].id).toBe('done-2');
    expect(dup.edges).toHaveLength(1);
    expect(duplicateNode(def, 'start')).toBe(def);
  });
});

describe('canvas mapping', () => {
  const executions: NodeExecution[] = [
    { nodeId: 'review', status: 'FAILED', port: 'failure', attempt: 1 },
    { nodeId: 'review', status: 'SUCCEEDED', port: 'success', attempt: 2 },
    { nodeId: 'gate', status: 'WAITING', attempt: 1 },
  ];

  it('builds node data with template metadata, status of the last execution and taken ports', () => {
    const data = buildNodeData(node('review', 'TASK', { task: { templateKey: 'code-review' } }), {
      palette: indexPalette(PALETTE),
      executions,
      issues: { nodes: { review: { errors: 1, warnings: 2 } }, edges: {} },
    });
    expect(data.templateName).toBe('Code review');
    expect(data.agent?.key).toBe('model-answer');
    expect(data.category).toBe('REVIEW');
    expect(data.status).toBe('SUCCEEDED');
    expect(data.attempts).toBe(2);
    expect(data.takenPortsList).toEqual(['failure', 'success']);
    expect(data.errorCount).toBe(1);
    expect(data.warningCount).toBe(2);
  });

  it('names the agent a step chooses itself before the template agent (ADR 0056)', () => {
    const palette = indexPalette(PALETTE);
    const own = node('r', 'TASK', {
      task: { templateKey: 'code-review', overrides: { agentKey: 'claude-code' } },
    });
    const agents = [{ key: 'claude-code', name: 'Claude Code', harness: 'CLAUDE_CODE' as const }];
    expect(buildNodeData(own, { palette, agents }).agent?.harness).toBe('CLAUDE_CODE');
    expect(buildNodeData(own, { palette }).agent).toEqual({
      key: 'claude-code',
      name: 'claude-code',
    });
    expect(buildNodeData(node('n', 'NOTE'), { palette }).agent).toBeNull();
  });

  it('builds ACTION node data with the translated title and the capabilities (ADR 0056)', () => {
    const data = buildNodeData(
      node('t', 'ACTION', { action: { type: 'TRANSITION_ISSUE', config: {} } }),
      { palette: indexPalette(PALETTE) },
    );
    expect(data.actionTitle).toBe('Change issue status');
    expect(data.requiredCapabilities).toEqual(['ISSUE_TRANSITION']);
    expect(data.agent).toBeNull();
    expect(data.templateName).toBeNull();
  });

  it('marks the current node when it has no execution yet', () => {
    expect(buildNodeData(node('next', 'TASK'), { currentNodeId: 'next' }).status).toBe('CURRENT');
    expect(buildNodeData(node('other', 'TASK'), { currentNodeId: 'next' }).status).toBeNull();
  });

  it('marks edges whose port was taken and edges with errors', () => {
    const taken = takenPorts(executions);
    const edge = { id: 'x', from: 'review', fromPort: 'success', to: 'gate' };
    expect(buildEdgeData(edge, taken).taken).toBe(true);
    expect(buildEdgeData({ ...edge, fromPort: 'blocked' }, taken).taken).toBe(false);
    expect(
      buildEdgeData(edge, taken, { nodes: {}, edges: { x: { errors: 1, warnings: 0 } } }).hasError,
    ).toBe(true);
  });
});

describe('templates and normalisation', () => {
  it('bundled templates are internally consistent', () => {
    for (const def of [issueToMergeRequestTemplate(), ciCodeReviewTemplate()]) {
      const ids = new Set(def.nodes.map((n) => n.id));
      for (const e of def.edges) {
        expect(ids.has(e.from)).toBe(true);
        expect(ids.has(e.to)).toBe(true);
        expect(portsFor(def.nodes.find((n) => n.id === e.from)!)).toContain(e.fromPort);
      }
      const ports = new Set(def.edges.map((e) => `${e.from}.${e.fromPort}`));
      expect(ports.size).toBe(def.edges.length);
    }
  });

  it('normalizes partial definitions with defaults and edge ids', () => {
    const d = normalizeDefinition({
      name: 'x',
      nodes: [{ id: 'a', kind: 'START', name: 'a' } as WorkflowNode],
      edges: [{ from: 'a', fromPort: 'out', to: 'b' } as WorkflowEdge],
    });
    expect(d.nodes[0].position).toEqual({ x: 0, y: 0 });
    expect(d.nodes[0].maxVisits).toBe(3);
    expect(d.edges[0].id).toBe('a.out->b');
    expect(d.enabled).toBe(true);
    expect(d.triggers).toEqual([]);
  });
});
