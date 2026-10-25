import type { Skill, TaskInterface, TaskTemplate } from '../../../core/api/types';
import {
  executorLabel,
  filterInterfaces,
  filterSkills,
  filterTemplates,
  sizeLabel,
} from './library-filters';

const T = (over: Partial<TaskTemplate>): TaskTemplate =>
  ({ key: 'k', name: 'N', scope: { type: 'GLOBAL' }, ...over }) as TaskTemplate;

describe('library filters', () => {
  it('labels executors with the shared plain names', () => {
    expect(executorLabel('LLM')).toBe('Model answer');
    expect(executorLabel('FETCH_ISSUE')).toBe('Jira: read issue');
    expect(executorLabel('SOMETHING_NEW')).toBe('Something new');
    expect(executorLabel(null)).toBe('inherited');
  });

  it('hides deprecated templates unless asked to show them', () => {
    const list = [
      T({ key: 'implement-claude-code', executor: 'IMPLEMENT', tags: ['deprecated'] }),
      T({ key: 'implement', executor: 'IMPLEMENT' }),
    ];
    expect(filterTemplates(list, {}).map((t) => t.key)).toEqual(['implement']);
    expect(filterTemplates(list, { showDeprecated: true }).map((t) => t.key)).toEqual([
      'implement-claude-code',
      'implement',
    ]);
    expect(filterTemplates(list, { query: 'coding agent' }).map((t) => t.key)).toEqual([
      'implement',
    ]);
  });

  it('filters templates by text and executor', () => {
    const list = [
      T({ key: 'code-review', name: 'Code review', executor: 'LLM', implements: ['reviewer'] }),
      T({ key: 'fetch-issue', name: 'Fetch issue', executor: 'FETCH_ISSUE', tags: ['jira'] }),
      T({ key: 'base', name: 'Base agent', description: 'Abstract root' }),
    ];
    expect(filterTemplates(list, { query: 'review' }).map((t) => t.key)).toEqual(['code-review']);
    expect(filterTemplates(list, { query: 'JIRA' }).map((t) => t.key)).toEqual(['fetch-issue']);
    expect(filterTemplates(list, { query: 'root' }).map((t) => t.key)).toEqual(['base']);
    expect(filterTemplates(list, { executor: 'LLM' }).map((t) => t.key)).toEqual(['code-review']);
    expect(filterTemplates(list, {}).length).toBe(3);
  });

  it('filters interfaces by slots and implementers', () => {
    const list = [
      {
        key: 'reviewer',
        name: 'Reviewer',
        inputs: [{ name: 'diff', kind: 'ARTIFACT' }],
        outputs: [{ name: 'review', kind: 'ARTIFACT' }],
        ports: [],
        implementedBy: ['code-review'],
      },
      { key: 'analyst', name: 'Analyst', inputs: [], outputs: [], ports: [] },
    ] as unknown as TaskInterface[];
    expect(filterInterfaces(list, 'diff').map((i) => i.key)).toEqual(['reviewer']);
    expect(filterInterfaces(list, 'code-review').map((i) => i.key)).toEqual(['reviewer']);
    expect(filterInterfaces(list, '').length).toBe(2);
  });

  it('filters skills and sizes their body', () => {
    const list = [
      { key: 'kotlin', name: 'Kotlin rules', tags: ['backend'], content: '' },
      { key: 'docs', name: 'Docs', content: '' },
    ] as unknown as Skill[];
    expect(filterSkills(list, 'backend').map((s) => s.key)).toEqual(['kotlin']);
    expect(filterSkills(list, null).length).toBe(2);
    expect(sizeLabel(640)).toBe('640 chars');
    expect(sizeLabel(2400)).toBe('2.4k chars');
    expect(sizeLabel(24000)).toBe('24k chars');
  });
});
