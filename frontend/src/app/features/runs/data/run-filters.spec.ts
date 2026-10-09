import type { WorkflowRunSummary } from '../../../core/api/types';
import { filterRuns, statusesForHolders } from './run-filters';

const runs: WorkflowRunSummary[] = [
  {
    id: 'aaaa1111',
    status: 'RUNNING',
    issueKey: 'SHOP-12',
    workflowName: 'Issue to MR',
    startedBy: { kind: 'USER', userId: 'me' },
  },
  {
    id: 'bbbb2222',
    status: 'WAITING',
    waitingOn: { kind: 'WAIT_FOR_CI', nodeId: 'ci', signal: 'tests' },
    workflowName: 'Issue to MR',
  },
  {
    id: 'cccc3333',
    status: 'WAITING',
    waitingOn: { kind: 'HUMAN_GATE', nodeId: 'gate' },
    workflowName: 'Review',
  },
  { id: 'dddd4444', status: 'FAILED', workflowName: 'Review' },
];

describe('run filters', () => {
  it('maps holders to API statuses', () => {
    expect(statusesForHolders(['agent'])).toEqual(['RUNNING']);
    expect(statusesForHolders(['you', 'ci']).sort()).toEqual(['RUNNING', 'WAITING']);
    expect(statusesForHolders([])).toEqual([]);
  });

  it('filters by holder, search and starter', () => {
    const f = (o: Partial<Parameters<typeof filterRuns>[1]>) =>
      filterRuns(runs, { holders: [], query: null, startedBy: null, me: 'me', ...o }).map(
        (r) => r.id,
      );
    expect(f({ holders: ['ci'] })).toEqual(['bbbb2222']);
    expect(f({ holders: ['you'] })).toEqual(['cccc3333']);
    expect(f({ query: 'shop-12' })).toEqual(['aaaa1111']);
    expect(f({ query: 'dddd' })).toEqual(['dddd4444']);
    expect(f({ query: 'review' })).toEqual(['cccc3333', 'dddd4444']);
    expect(f({ startedBy: 'me' })).toEqual(['aaaa1111']);
  });
});
