import type { WorkflowDefinitionInput, WorkflowNode } from '../../../core/api/types';
import { autoLayout, bfsLevels, layerGraph, needsLayout, nodeRects } from './auto-layout';
import { issueToMergeRequestTemplate } from './graph-model';

const n = (id: string, kind: WorkflowNode['kind'] = 'TASK'): WorkflowNode => ({
  id,
  kind,
  name: id,
  position: { x: 0, y: 0 },
  maxVisits: 3,
});

const def: WorkflowDefinitionInput = {
  name: 'x',
  nodes: [
    n('start', 'START'),
    n('a'),
    n('b'),
    n('c'),
    n('end', 'END'),
    n('orphan'),
    n('memo', 'NOTE'),
  ],
  edges: [
    { id: '1', from: 'start', fromPort: 'out', to: 'a' },
    { id: '2', from: 'a', fromPort: 'success', to: 'b' },
    { id: '3', from: 'a', fromPort: 'failure', to: 'c' },
    { id: '4', from: 'b', fromPort: 'success', to: 'end' },
    { id: '5', from: 'c', fromPort: 'success', to: 'a' },
  ],
  enabled: true,
};

describe('bfsLevels', () => {
  it('assigns levels by shortest path from START and ignores back edges', () => {
    const levels = bfsLevels(def);
    expect(levels.get('start')).toBe(0);
    expect(levels.get('a')).toBe(1);
    expect(levels.get('b')).toBe(2);
    expect(levels.get('c')).toBe(2);
    expect(levels.get('end')).toBe(3);
    expect(levels.has('orphan')).toBe(false);
  });

  it('is empty without a START node', () => {
    expect(bfsLevels({ ...def, nodes: def.nodes.filter((x) => x.kind !== 'START') }).size).toBe(0);
  });
});

describe('autoLayout', () => {
  it('puts the main path on the top row, branches below, unreachable nodes and notes last', () => {
    const laid = autoLayout(def, { xGap: 100, yGap: 50, originX: 0, originY: 0 });
    const pos = Object.fromEntries(laid.nodes.map((x) => [x.id, x.position]));
    const rects = nodeRects(laid);
    // START, a, b, end on one row, left to right with the gap between the cards.
    expect(new Set(['start', 'a', 'b', 'end'].map((id) => pos[id].y))).toEqual(new Set([0]));
    expect(pos['a'].x).toBeGreaterThanOrEqual(
      rects.get('start')!.x + rects.get('start')!.width + 100,
    );
    expect(pos['b'].x).toBeGreaterThan(pos['a'].x);
    expect(pos['end'].x).toBeGreaterThan(pos['b'].x);
    // The failure branch c sits in the layer of b, in the row below.
    expect(pos['c'].x).toBe(pos['b'].x);
    expect(pos['c'].y).toBe(rects.get('b')!.height + 50);
    // Unreachable node and the note go below everything.
    expect(pos['orphan'].y).toBeGreaterThan(pos['c'].y);
    expect(pos['memo'].y).toBeGreaterThan(pos['orphan'].y);
  });

  it('never overlaps cards and keeps the input untouched', () => {
    const laid = autoLayout(issueToMergeRequestTemplate());
    expect(needsLayout(laid)).toBe(false);
    expect(issueToMergeRequestTemplate().nodes.every((x) => x.position.x === 0)).toBe(true);
  });

  it('uses measured sizes when given', () => {
    const sizes = new Map([['a', { width: 600, height: 300 }]]);
    const laid = autoLayout(def, { sizes, xGap: 100, originX: 0 });
    const pos = Object.fromEntries(laid.nodes.map((x) => [x.id, x.position]));
    expect(pos['b'].x).toBeGreaterThanOrEqual(pos['a'].x + 600 + 100);
  });
});

describe('layerGraph', () => {
  it('marks loops as back edges and follows the first port for the main path', () => {
    const g = layerGraph(def);
    expect([...g.backEdges]).toEqual(['5']);
    expect(g.mainPath).toEqual(['start', 'a', 'b', 'end']);
    expect(g.layers.get('c')).toBe(2);
    expect(g.unreachable.has('orphan')).toBe(true);
  });
});

describe('needsLayout', () => {
  it('is true when all nodes sit at the origin or overlap, false for a positioned graph', () => {
    expect(needsLayout(def)).toBe(true);
    expect(needsLayout(autoLayout(def))).toBe(false);
    const overlapping = {
      ...def,
      nodes: def.nodes.map((x, i) => ({ ...x, position: { x: i < 2 ? 5 : i * 10, y: 5 } })),
    };
    expect(needsLayout(overlapping)).toBe(true);
    expect(needsLayout({ ...def, nodes: [] })).toBe(false);
  });
});
