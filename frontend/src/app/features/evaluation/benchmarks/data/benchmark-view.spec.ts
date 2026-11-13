import type { Attempt } from '../../../../core/api/types';
import { icu } from '../../../../core/i18n/icu';
import {
  attemptResult,
  attemptResultLabel,
  attemptStatusBaton,
  attemptSummary,
  benchmarkBaton,
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

  it('drops the failed count from the progress text when the legend says it in words', () => {
    expect(progressText({ total: 120, done: 104, failed: 6 }, 'RUNNING', false)).toBe(
      '104 / 120 attempts',
    );
  });

  it('summarises attempts in one vocabulary with plurals', () => {
    const attempts = [
      { status: 'SUCCEEDED' },
      { status: 'SUCCEEDED' },
      { status: 'RUNNING' },
      { status: 'JUDGING' },
      { status: 'TIMED_OUT' },
      { status: 'FAILED' },
      { status: 'PENDING' },
    ] as Attempt[];
    expect(attemptSummary(attempts).map((p) => [p.holder, p.text])).toEqual([
      ['done', '2 done'],
      ['blocked', '1 timed out'],
      ['blocked', '1 error'],
      ['agent', '2 running'],
      ['idle', '1 waiting'],
    ]);
    expect(attemptSummary([])).toEqual([]);
  });

  it('uses the Polish plural forms', () => {
    const pl = (n: number) =>
      icu(
        '{count, plural, one {# przekroczenie czasu} few {# przekroczenia czasu} many {# przekroczeń czasu} other {# przekroczenia czasu}}',
        { count: n },
        'pl',
      );
    expect([1, 2, 5, 22].map(pl)).toEqual([
      '1 przekroczenie czasu',
      '2 przekroczenia czasu',
      '5 przekroczeń czasu',
      '22 przekroczenia czasu',
    ]);
  });

  it('tags only exceptions and calls a timeout a timeout', () => {
    expect(attemptStatusBaton('SUCCEEDED')).toBeNull();
    expect(attemptStatusBaton('TIMED_OUT')).toEqual({ holder: 'blocked', label: 'timed out' });
    expect(attemptStatusBaton('FAILED')).toEqual({ holder: 'blocked', label: 'error' });
    expect(attemptStatusBaton('RUNNING')?.live).toBe(true);
    expect(attemptStatusBaton('PENDING')?.holder).toBe('idle');
  });

  it('classifies attempt results for the filter', () => {
    expect(attemptResult({ status: 'SUCCEEDED', passed: true })).toBe('accepted');
    expect(attemptResult({ status: 'SUCCEEDED', passed: false })).toBe('notAccepted');
    expect(attemptResult({ status: 'SUCCEEDED', passed: null })).toBe('notAccepted');
    expect(attemptResult({ status: 'TIMED_OUT', passed: false })).toBe('timedOut');
    expect(attemptResult({ status: 'FAILED' })).toBe('failed');
    expect(attemptResult({ status: 'RUNNING' })).toBeNull();
    expect(attemptResultLabel('notAccepted')).toBe('not accepted');
  });
});
