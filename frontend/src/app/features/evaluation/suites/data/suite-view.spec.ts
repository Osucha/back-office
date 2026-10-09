import type { EvalTask, TaskSuiteSummary } from '../../../../core/api/types';
import {
  deleteSuiteConsequence,
  filterSuites,
  filterTasks,
  suiteStats,
  weightsText,
} from './suite-view';

describe('suite view', () => {
  it('filters suites by name and description', () => {
    const list = [
      { id: '1', name: 'Starter: SDLC tasks', description: 'Twelve example tasks' },
      { id: '2', name: 'Payments', description: null },
    ] as TaskSuiteSummary[];
    expect(filterSuites(list, 'twelve').map((s) => s.id)).toEqual(['1']);
    expect(filterSuites(list, 'PAY').map((s) => s.id)).toEqual(['2']);
    expect(filterSuites(list, '').length).toBe(2);
  });

  it('describes the coding score weights', () => {
    expect(weightsText({ codingScoreWeights: { verification: 0.7, judge: 0.3 } })).toBe(
      'verification 0.7 · judge 0.3',
    );
    expect(weightsText({})).toBe('verification 0.6 · judge 0.4');
  });

  it('counts tasks by kind and sums weights', () => {
    const tasks = [
      { kind: 'PROMPT', weight: 1 },
      { kind: 'CODING_JOB', weight: 2.5 },
      { kind: 'PROMPT' },
    ] as EvalTask[];
    expect(suiteStats(tasks)).toEqual({ tasks: 3, prompt: 2, coding: 1, totalWeight: 4.5 });
  });

  it('filters tasks by text and kind', () => {
    const tasks = [
      { id: 'a', name: 'Review the diff', kind: 'PROMPT', category: 'REVIEW' },
      {
        id: 'b',
        name: 'Fix the test',
        kind: 'CODING_JOB',
        category: 'CODING',
        repository: { url: 'https://git/shop.git' },
      },
    ] as EvalTask[];
    expect(filterTasks(tasks, { query: 'shop' }).map((t) => t.id)).toEqual(['b']);
    expect(filterTasks(tasks, { kind: 'PROMPT' }).map((t) => t.id)).toEqual(['a']);
    expect(filterTasks(tasks, {}).length).toBe(2);
  });

  it('says what deleting a suite does', () => {
    expect(deleteSuiteConsequence(12)).toBe(
      'It removes its 12 tasks; benchmarks that used the suite keep their results.',
    );
    expect(deleteSuiteConsequence(1)).toContain('its 1 task;');
    expect(deleteSuiteConsequence(0)).toContain('It has no tasks');
  });
});
