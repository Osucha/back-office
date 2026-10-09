import {
  formatAge,
  queueBaton,
  replicaBaton,
  versionSkew,
  workItemCounts,
} from './operations-view';

describe('operations view', () => {
  const now = Date.parse('2026-09-28T10:00:00Z');

  it('marks replicas that stopped reporting', () => {
    expect(replicaBaton({ lastSeenAt: '2026-09-28T09:59:50Z' }, now).holder).toBe('done');
    expect(replicaBaton({ lastSeenAt: '2026-09-28T09:55:00Z' }, now).label).toBe('not reporting');
    expect(replicaBaton({}, now).holder).toBe('idle');
  });

  it('counts work items in a fixed order with zeros', () => {
    expect(workItemCounts({ workItems: { DEAD: 2, READY: 5 } })).toEqual([
      { status: 'READY', count: 5 },
      { status: 'LEASED', count: 0 },
      { status: 'DONE', count: 0 },
      { status: 'DEAD', count: 2 },
    ]);
    expect(workItemCounts(null).every((r) => r.count === 0)).toBe(true);
  });

  it('formats ages and the queue state', () => {
    expect(formatAge(12)).toBe('12s');
    expect(formatAge(250)).toBe('4m 10s');
    expect(formatAge(7500)).toBe('2h 5m');
    expect(formatAge(null)).toBe('-');
    expect(queueBaton({ deadItems: 3 }).holder).toBe('you');
    expect(queueBaton({ oldestReadyAgeSeconds: 300 }).label).toBe('lagging');
    expect(queueBaton({ oldestReadyAgeSeconds: 2 }).holder).toBe('done');
  });

  it('lists distinct versions', () => {
    expect(versionSkew([{ version: '4.0.0' }, { version: '4.0.1' }, { version: '4.0.0' }])).toEqual(
      ['4.0.0', '4.0.1'],
    );
  });
});
