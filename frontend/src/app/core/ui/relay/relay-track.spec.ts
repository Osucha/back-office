import type { NodeExecution, WorkflowDefinitionInput } from '../../api/types';
import {
  SEGMENT_WIDTH,
  buildTrack,
  pointerIndex,
  segmentLabel,
  segmentWeights,
  segmentWidths,
} from './relay-track';

const def = {
  nodes: [
    { id: 'start', kind: 'START', name: 'Start' },
    { id: 'spec', kind: 'TASK', name: 'Write spec' },
    { id: 'gate', kind: 'HUMAN_GATE', name: 'Spec gate' },
    { id: 'impl', kind: 'TASK', name: 'Implement' },
    { id: 'ci', kind: 'WAIT_FOR_CI', name: 'Wait for CI' },
    { id: 'note', kind: 'NOTE', name: 'A note' },
    { id: 'end', kind: 'END', name: 'Done' },
    { id: 'fail', kind: 'END', name: 'Failed' },
  ],
  edges: [
    { id: '1', from: 'start', fromPort: 'out', to: 'spec' },
    { id: '2', from: 'spec', fromPort: 'failure', to: 'fail' },
    { id: '3', from: 'spec', fromPort: 'success', to: 'gate' },
    { id: '4', from: 'gate', fromPort: 'reject', to: 'fail' },
    { id: '5', from: 'gate', fromPort: 'approve', to: 'impl' },
    { id: '6', from: 'gate', fromPort: 'revise', to: 'spec' },
    { id: '7', from: 'impl', fromPort: 'success', to: 'ci' },
    { id: '8', from: 'ci', fromPort: 'success', to: 'end' },
  ],
} as unknown as WorkflowDefinitionInput;

const exec = (overrides: Partial<NodeExecution>): NodeExecution => ({
  id: overrides.nodeId + '-' + (overrides.attempt ?? 1),
  attempt: 1,
  ...overrides,
});

describe('relay track', () => {
  const now = Date.parse('2026-01-01T00:10:00Z');

  it('orders visited nodes, hides START/END/NOTE and marks the current one', () => {
    const track = buildTrack(
      {
        status: 'WAITING',
        currentNodeId: 'gate',
        waitingOn: { kind: 'HUMAN_GATE', nodeId: 'gate' },
        definitionSnapshot: def,
        nodes: [
          exec({
            nodeId: 'gate',
            kind: 'HUMAN_GATE',
            status: 'WAITING',
            startedAt: '2026-01-01T00:05:00Z',
          }),
          exec({
            nodeId: 'start',
            kind: 'START',
            status: 'SUCCEEDED',
            startedAt: '2026-01-01T00:00:00Z',
          }),
          exec({
            nodeId: 'spec',
            kind: 'TASK',
            status: 'SUCCEEDED',
            startedAt: '2026-01-01T00:01:00Z',
            finishedAt: '2026-01-01T00:04:00Z',
            cost: { amount: 0.31, currency: 'USD' },
          }),
        ],
      },
      { now },
    );
    expect(track.map((s) => [s.nodeId, s.holder, s.state, s.current])).toEqual([
      ['spec', 'agent', 'finished', false],
      ['gate', 'you', 'active', true],
      ['impl', 'agent', 'ahead', false],
      ['ci', 'ci', 'ahead', false],
    ]);
    expect(track[0].durationMs).toBe(180_000);
    expect(track[1].durationMs).toBe(300_000); // running: measured up to now
    expect(track[1].live).toBe(false); // a person's turn does not animate
  });

  it('adds the current node when it has no execution yet', () => {
    const track = buildTrack({
      status: 'RUNNING',
      currentNodeId: 'impl',
      definitionSnapshot: def,
      nodes: [],
    });
    expect(track[0]).toMatchObject({ nodeId: 'impl', state: 'active', current: true, live: true });
    expect(track.map((s) => s.nodeId)).toEqual(['impl', 'ci']);
  });

  it('keeps repeated visits (revision loops) as separate segments', () => {
    const track = buildTrack({
      status: 'SUCCEEDED',
      definitionSnapshot: def,
      nodes: [
        exec({ id: 'a', nodeId: 'spec', kind: 'TASK', status: 'SUCCEEDED' }),
        exec({ id: 'b', nodeId: 'gate', kind: 'HUMAN_GATE', status: 'SUCCEEDED', port: 'revise' }),
        exec({ id: 'c', nodeId: 'spec', kind: 'TASK', status: 'SUCCEEDED', attempt: 2 }),
      ],
    });
    expect(track.map((s) => s.key)).toEqual(['a', 'b', 'c']);
    expect(track[2].attempt).toBe(2);
    expect(track.some((s) => s.current)).toBe(false);
  });

  it('marks the failed step of a failed run and shows nothing ahead', () => {
    const track = buildTrack({
      status: 'FAILED',
      currentNodeId: 'impl',
      definitionSnapshot: def,
      nodes: [
        exec({ nodeId: 'spec', kind: 'TASK', status: 'SUCCEEDED' }),
        exec({ nodeId: 'impl', kind: 'TASK', status: 'FAILED' }),
      ],
    });
    expect(track.map((s) => [s.nodeId, s.holder, s.current])).toEqual([
      ['spec', 'agent', false],
      ['impl', 'blocked', true],
    ]);
  });

  it('weights segments by the square root of their duration and labels them', () => {
    const track = buildTrack({
      status: 'SUCCEEDED',
      nodes: [
        exec({
          nodeId: 'a',
          kind: 'TASK',
          status: 'SUCCEEDED',
          startedAt: '2026-01-01T00:00:00Z',
          finishedAt: '2026-01-01T00:00:04Z',
        }),
        exec({
          nodeId: 'b',
          kind: 'TASK',
          status: 'SUCCEEDED',
          startedAt: '2026-01-01T00:00:00Z',
          finishedAt: '2026-01-01T00:00:16Z',
        }),
      ],
    });
    expect(segmentWeights(track)).toEqual([2, 4]);
    expect(
      segmentLabel(
        track[1],
        (ms) => `${ms / 1000}s`,
        (m) => `$${m.amount}`,
      ),
    ).toBe('b, agent, finished, 16s');
  });

  it('keeps segment widths between a minimum and a maximum and never stretches a short run', () => {
    const one = buildTrack({
      status: 'FAILED',
      nodes: [
        exec({
          nodeId: 'a',
          kind: 'TASK',
          status: 'FAILED',
          startedAt: '2026-01-01T00:00:00.000Z',
          finishedAt: '2026-01-01T00:00:00.076Z',
        }),
      ],
    });
    expect(segmentWidths(one, 'full')).toEqual([SEGMENT_WIDTH.full.min]);
    expect(segmentWidths(one, 'mini')).toEqual([SEGMENT_WIDTH.mini.min]);
    // The failed step is where the pointer is.
    expect(pointerIndex(one)).toBe(0);
    const long = buildTrack({
      status: 'SUCCEEDED',
      nodes: [
        exec({
          nodeId: 'a',
          kind: 'TASK',
          status: 'SUCCEEDED',
          startedAt: '2026-01-01T00:00:00Z',
          finishedAt: '2026-01-01T03:00:00Z',
        }),
        exec({
          nodeId: 'b',
          kind: 'TASK',
          status: 'SUCCEEDED',
          startedAt: '2026-01-01T03:00:00Z',
          finishedAt: '2026-01-01T03:01:00Z',
        }),
      ],
    });
    const [a, b] = segmentWidths(long, 'full');
    expect(a).toBe(SEGMENT_WIDTH.full.max);
    expect(b).toBeGreaterThan(SEGMENT_WIDTH.full.min);
    expect(b).toBeLessThan(a);
    // A finished run without a current step points at its last step.
    expect(pointerIndex(long)).toBe(1);
    expect(pointerIndex([])).toBe(-1);
  });
});
