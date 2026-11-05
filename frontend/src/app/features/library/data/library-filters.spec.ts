import type { Rule, Skill, TaskTemplate } from '../../../core/api/types';
import {
  effectiveCategory,
  executorLabel,
  filterRules,
  filterSkills,
  filterTemplates,
  nameOfKey,
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
      T({ key: 'code-review', name: 'Code review', executor: 'LLM', category: 'REVIEW' }),
      T({ key: 'fetch-issue', name: 'Fetch issue', executor: 'FETCH_ISSUE', tags: ['jira'] }),
      T({ key: 'base', name: 'Base agent', description: 'Abstract root' }),
    ];
    expect(filterTemplates(list, { query: 'review' }).map((t) => t.key)).toEqual(['code-review']);
    expect(filterTemplates(list, { query: 'JIRA' }).map((t) => t.key)).toEqual(['fetch-issue']);
    expect(filterTemplates(list, { query: 'root' }).map((t) => t.key)).toEqual(['base']);
    expect(filterTemplates(list, { executor: 'LLM' }).map((t) => t.key)).toEqual(['code-review']);
    expect(filterTemplates(list, {}).length).toBe(3);
  });

  it('filters templates by their own or inherited category (ADR 0055)', () => {
    const list = [
      T({ key: 'base', name: 'Base' }),
      T({ key: 'review', name: 'Review', extendsKey: 'base', category: 'REVIEW' }),
      T({ key: 'strict', name: 'Strict review', extendsKey: 'review' }),
      T({ key: 'other', name: 'Other', extendsKey: 'base' }),
    ];
    expect(filterTemplates(list, { category: 'REVIEW' }).map((t) => t.key)).toEqual([
      'review',
      'strict',
    ]);
    expect(filterTemplates(list, { category: 'GENERAL' }).map((t) => t.key)).toEqual([
      'base',
      'other',
    ]);
    // the category label is searchable
    expect(filterTemplates(list, { query: 'review' }).map((t) => t.key)).toEqual([
      'review',
      'strict',
    ]);
  });

  it('takes the category of a project parent over the global one', () => {
    const list = [
      T({ key: 'impl', category: 'IMPLEMENTATION' }),
      T({ key: 'impl', category: 'OPERATIONS', scope: { type: 'PROJECT', projectId: 'p1' } }),
    ];
    const child = T({
      key: 'mine',
      extendsKey: 'impl',
      scope: { type: 'PROJECT', projectId: 'p1' },
    });
    expect(effectiveCategory(child, list)).toBe('OPERATIONS');
    expect(effectiveCategory(T({ key: 'g', extendsKey: 'impl' }), list)).toBe('IMPLEMENTATION');
    // a loop in the chain ends in General
    const loop = [T({ key: 'a', extendsKey: 'b' }), T({ key: 'b', extendsKey: 'a' })];
    expect(effectiveCategory(loop[0], loop)).toBe('GENERAL');
  });

  it('filters rules by text and automatic', () => {
    const list = [
      { key: 'no-secrets', name: 'No secrets', automatic: true, tags: ['security'] },
      { key: 'polish', name: 'Answer in Polish', automatic: false, description: 'language' },
    ] as unknown as Rule[];
    expect(filterRules(list, { query: 'security' }).map((r) => r.key)).toEqual(['no-secrets']);
    expect(filterRules(list, { query: 'LANGUAGE' }).map((r) => r.key)).toEqual(['polish']);
    expect(filterRules(list, { automatic: true }).map((r) => r.key)).toEqual(['no-secrets']);
    expect(filterRules(list, {}).length).toBe(2);
  });

  it('shows names for keys and falls back to the key', () => {
    const list = [{ key: 'kotlin', name: 'Kotlin rules' }, { key: 'bare' }];
    expect(nameOfKey(list, 'kotlin')).toBe('Kotlin rules');
    expect(nameOfKey(list, 'bare')).toBe('bare');
    expect(nameOfKey(list, 'missing')).toBe('missing');
    expect(nameOfKey(list, null)).toBe('');
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
