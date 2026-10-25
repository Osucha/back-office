import { TASK_EXECUTORS } from '../api/types';
import { executorDescription, executorInfos, executorLabel } from './executors';

describe('executor labels', () => {
  it('names executors in plain words', () => {
    expect(executorLabel('LLM')).toBe('Model answer');
    expect(executorLabel('IMPLEMENT')).toBe('Coding agent');
    expect(executorLabel('SPEC_KIT')).toBe('Spec Kit step');
    expect(executorLabel('FETCH_ISSUE')).toBe('Jira: read issue');
    expect(executorLabel('NOTIFY')).toBe('Jira: comment');
    expect(executorLabel('TRANSITION_ISSUE')).toBe('Jira: change status');
    expect(executorLabel('START_WORKFLOW')).toBe('Start workflows');
    expect(executorLabel('PROJECT_DIGEST')).toBe('Project digest');
  });

  it('falls back to inherited and to a humanized unknown value', () => {
    expect(executorLabel(null)).toBe('inherited');
    expect(executorLabel(undefined)).toBe('inherited');
    expect(executorLabel('SOMETHING_NEW')).toBe('Something new');
  });

  it('describes every known executor in one sentence', () => {
    for (const e of TASK_EXECUTORS) {
      const d = executorDescription(e);
      expect(d.length).toBeGreaterThan(10);
      expect(d.endsWith('.')).toBe(true);
    }
    expect(executorDescription('IMPLEMENT')).toContain('repository');
    expect(executorDescription(null)).toBe('');
    expect(executorDescription('SOMETHING_NEW')).toBe('');
  });

  it('lists infos in the given order', () => {
    const infos = executorInfos(['NOTIFY', 'LLM']);
    expect(infos.map((i) => i.executor)).toEqual(['NOTIFY', 'LLM']);
    expect(infos[1].label).toBe('Model answer');
  });
});
