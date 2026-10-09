import type { Attempt } from '../../../../core/api/types';
import {
  attemptCounts,
  benchmarkBaton,
  filterAttemptsByHolder,
  progressSegments,
  progressText,
} from './benchmark-view';

describe('benchmark view', () => {
  it('maps every benchmark status to a baton', () => {
    expect(benchmarkBaton('RUNNING')).toEqual({ holder: 'agent', label: 'running', live: true });
    expect(benchmarkBaton('COMPLETED').holder).toBe('done');
    expect(benchmarkBaton('FAILED').holder).toBe('blocked');
    expect(benchmarkBaton('CANCELLED')).toEqual({ holder: 'idle', label: 'cancelled' });
    expect(benchmarkBaton('DRAFT')).toEqual({ holder: 'idle', label: 'draft' });
    expect(benchmarkBaton(undefined).label).toBe('draft');
  });

  it('splits progress into succeeded, failed and remaining', () => {
    const s = progressSegments({ total: 120, done: 104, failed: 6 }, 'RUNNING');
    expect(s.map((x) => [x.holder, x.count])).toEqual([
      ['done', 98],
      ['blocked', 6],
      ['agent', 16],
    ]);
    expect(s.reduce((a, x) => a + x.percent, 0)).toBeCloseTo(100);
    const stopped = progressSegments({ total: 10, done: 4, failed: 0 }, 'CANCELLED');
    expect(stopped.map((x) => [x.holder, x.label])).toEqual([
      ['done', 'succeeded'],
      ['idle', 'not run'],
    ]);
    expect(progressSegments({ total: 0 }, 'DRAFT')).toEqual([]);
    expect(progressSegments(null, 'DRAFT')).toEqual([]);
  });

  it('clamps inconsistent counts', () => {
    const s = progressSegments({ total: 4, done: 9, failed: 12 }, 'COMPLETED');
    expect(s).toEqual([{ holder: 'blocked', label: 'failed', count: 4, percent: 100 }]);
  });

  it('describes progress in words', () => {
    expect(progressText({ total: 120, done: 104, failed: 6 }, 'RUNNING')).toBe(
      '104 / 120 attempts · 6 failed',
    );
    expect(progressText({ total: 48, done: 48, failed: 0 }, 'COMPLETED')).toBe('48 attempts');
    expect(progressText({ total: 0 }, 'DRAFT')).toBe('not started');
  });

  it('counts attempts per status and filters them by holder', () => {
    const attempts = [
      { status: 'SUCCEEDED' },
      { status: 'SUCCEEDED' },
      { status: 'RUNNING' },
      { status: 'TIMED_OUT' },
      { status: 'PENDING' },
    ] as Attempt[];
    expect(attemptCounts(attempts)).toEqual([
      { holder: 'agent', label: 'running', count: 1 },
      { holder: 'done', label: 'done', count: 2 },
      { holder: 'blocked', label: 'timed out', count: 1 },
      { holder: 'idle', label: 'pending', count: 1 },
    ]);
    expect(filterAttemptsByHolder(attempts, ['blocked']).length).toBe(1);
    expect(filterAttemptsByHolder(attempts, []).length).toBe(5);
  });
});
