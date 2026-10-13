import type {
  LibraryWorkflowSummary,
  WorkflowMetrics,
  WorkflowNodeMetrics,
  WorkflowVersionMetrics,
} from '../../../core/api/types';
import {
  filterLibraryWorkflows,
  finishedRuns,
  libraryRows,
  metricCards,
  nodeRows,
  updateAvailable,
  versionRows,
} from './workflow-metrics';

const usd = (amount: number) => ({ amount, currency: 'USD' });

const M = (
  over: Partial<WorkflowMetrics> & {
    successful?: number;
    failed?: number;
    p50?: number;
    cps?: number;
  } = {},
): WorkflowMetrics => {
  const { successful = 4, failed = 1, p50 = 3600, cps = 2, ...rest } = over;
  return {
    runs: { total: successful + failed, successful, failed, cancelled: 0, active: 0, modified: 0 },
    successRate: successful + failed ? successful / (successful + failed) : null,
    firstPassRate: 0.5,
    reworkLoopsPerRun: 0.25,
    duration: {
      p50Seconds: p50,
      p90Seconds: p50 * 2,
      totalSeconds: p50 * 5,
      count: successful + failed,
    },
    activeTime: { p50Seconds: 1800, p90Seconds: 2400, totalSeconds: 9000, count: 5 },
    humanWait: { p50Seconds: 900, p90Seconds: 1200, totalSeconds: 3000, count: 3 },
    totalCost: usd(10),
    costPerRun: usd(2),
    costPerSuccessfulRun: usd(cps),
    inputTokens: 1000,
    outputTokens: 100,
    mergeRequests: 1,
    ...rest,
  };
};

const V = (version: number, metrics: WorkflowMetrics): WorkflowVersionMetrics => ({
  version,
  metrics,
});

describe('workflow metrics', () => {
  it('counts finished runs and builds the headline cards, effectiveness first', () => {
    const m = M();
    expect(finishedRuns(m)).toBe(5);
    expect(finishedRuns(null)).toBe(0);
    const cards = metricCards(m);
    expect(cards.map((c) => c.id)).toEqual(['success', 'firstPass', 'duration', 'active', 'cost', 'apiCost']);
    expect(cards[0].value).toBe('80%');
    expect(cards[0].sub).toContain('4');
    expect(cards[2].value).toBe('1.0h');
    expect(cards[4].value).toBe('$2.00');
  });

  it('shows no cost per success without successful runs', () => {
    expect(metricCards(M({ successful: 0, failed: 2 }))[4].value).toBe('-');
  });

  it('compares each version with the next older one that has finished runs', () => {
    const rows = versionRows([
      V(1, M({ successful: 5, failed: 5, p50: 7200, cps: 4 })),
      V(3, M({ successful: 9, failed: 1, p50: 3600, cps: 2 })),
      V(2, M({ successful: 0, failed: 0 })),
    ]);
    expect(rows.map((r) => r.version)).toEqual([3, 2, 1]);
    // v3 vs v1 (v2 has no finished runs): success 90 % vs 50 %, time halved, cost halved
    expect(rows[0].successTrend).toBe('better');
    expect(rows[0].durationTrend).toBe('better');
    expect(rows[0].costTrend).toBe('better');
    expect(rows[0].fewRuns).toBe(false);
    expect(rows[1].fewRuns).toBe(true);
    expect(rows[2].successTrend).toBeNull();
  });

  it('calls small differences the same and regressions worse', () => {
    const [newer] = versionRows([
      V(2, M({ successful: 8, failed: 2, p50: 3700, cps: 3 })),
      V(1, M({ successful: 8, failed: 2, p50: 3600, cps: 2 })),
    ]);
    expect(newer.successTrend).toBe('same');
    expect(newer.durationTrend).toBe('same');
    expect(newer.costTrend).toBe('worse');
  });

  it('flags steps that fail often or take most of the cost', () => {
    const node = (
      id: string,
      succeeded: number,
      failed: number,
      cost: number,
    ): WorkflowNodeMetrics => ({
      nodeId: id,
      nodeName: id,
      kind: 'TASK',
      executions: succeeded + failed,
      succeeded,
      failed,
      failureRate: succeeded + failed ? failed / (succeeded + failed) : null,
      cost: usd(cost),
      ports: [
        { port: 'success', count: succeeded },
        { port: 'failure', count: failed },
      ],
    });
    const rows = nodeRows([
      node('draft', 3, 2, 1),
      node('implement', 10, 0, 9),
      node('notify', 1, 1, 0),
    ]);
    expect(rows.map((r) => r.hotspot)).toEqual(['failures', 'cost', null]);
    expect(rows[0].portsText).toBe('success 3 · failure 2');
    expect(nodeRows(null)).toEqual([]);
  });

  it('joins the library list with the metrics and filters it', () => {
    const W = (id: string, over: Partial<LibraryWorkflowSummary> = {}): LibraryWorkflowSummary => ({
      id,
      key: id,
      name: id.toUpperCase(),
      latestVersion: 1,
      deprecated: false,
      usage: { projects: 0, workflows: 0 },
      ...over,
    });
    const rows = libraryRows(
      [W('issue-to-mr', { tags: ['delivery'] }), W('old', { deprecated: true })],
      [{ libraryWorkflowId: 'issue-to-mr', metrics: M() }],
    );
    expect(rows[0].metrics?.runs?.total).toBe(5);
    expect(rows[1].metrics).toBeNull();
    expect(filterLibraryWorkflows(rows, '', false).map((r) => r.id)).toEqual(['issue-to-mr']);
    expect(filterLibraryWorkflows(rows, '', true)).toHaveLength(2);
    expect(filterLibraryWorkflows(rows, 'DELIV', false).map((r) => r.id)).toEqual(['issue-to-mr']);
    expect(filterLibraryWorkflows(rows, 'nothing', true)).toEqual([]);
  });

  it('knows when a linked copy is behind', () => {
    expect(updateAvailable({ version: 2, latestVersion: 3 })).toBe(true);
    expect(updateAvailable({ version: 3, latestVersion: 3 })).toBe(false);
    expect(updateAvailable(null)).toBe(false);
  });
});
