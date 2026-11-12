import type { ActionDescriptor, PaletteTask } from '../../../core/api/types';
import {
  deprecatedPaletteTask,
  paletteActions,
  paletteGroups,
  paletteTaskTooltip,
  paletteTasks,
} from './palette-tasks';

describe('palette tasks (ADR 0054, 0056, 0061)', () => {
  const tasks: PaletteTask[] = [
    {
      key: 'implement-opencode',
      name: 'Implement',
      needsCodingAgent: true,
      requiresSkills: ['kotlin'],
    },
    { key: 'implement-claude-code', name: 'Implement with Claude Code', needsCodingAgent: true },
    { key: 'code-review', name: 'Code review', needsCodingAgent: false, category: 'REVIEW' },
    { key: 'tagged', name: 'Tagged', tags: ['deprecated'] } as PaletteTask,
    { key: 'flagged', name: 'Flagged', deprecated: true } as PaletteTask,
  ];
  const deprecated = new Set(['implement-claude-code']);

  it('never offers deprecated templates', () => {
    expect(paletteTasks(tasks, '', deprecated).map((t) => t.key)).toEqual([
      'implement-opencode',
      'code-review',
    ]);
    expect(deprecatedPaletteTask(tasks[1], deprecated)).toBe(true);
    expect(deprecatedPaletteTask(tasks[3])).toBe(true);
    expect(deprecatedPaletteTask(tasks[4])).toBe(true);
    expect(deprecatedPaletteTask(tasks[0], deprecated)).toBe(false);
  });

  it('matches name, key, category, the kind of agent needed and required skills', () => {
    expect(paletteTasks(tasks, 'code-rev', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'REVIEW', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'coding agent', deprecated).map((t) => t.key)).toEqual([
      'implement-opencode',
    ]);
    expect(paletteTasks(tasks, 'any agent', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'kotlin', deprecated).map((t) => t.key)).toEqual([
      'implement-opencode',
    ]);
    expect(paletteTasks(tasks, 'claude', deprecated)).toEqual([]);
  });

  it('shows the kind of agent and the required skills on hover', () => {
    expect(paletteTaskTooltip(tasks[0], (k) => (k === 'kotlin' ? 'Kotlin' : k))).toBe(
      'Implement\nNeeds a coding agent\nRequired skills: Kotlin',
    );
    expect(paletteTaskTooltip({ ...tasks[2], description: 'Reviews the diff' })).toBe(
      'Reviews the diff\nAny agent',
    );
  });

  it('groups by category, those without one under General (ADR 0055)', () => {
    const groups = paletteGroups(paletteTasks(tasks, '', deprecated));
    expect(groups.map((g) => [g.title, g.tasks.map((t) => t.key)])).toEqual([
      ['Review', ['code-review']],
      ['General', ['implement-opencode']],
    ]);
  });

  it('offers actions with translated titles and searches them', () => {
    const actions: ActionDescriptor[] = [
      {
        type: 'FETCH_ISSUE',
        title: 'Fetch issue (server)',
        description: 'server',
        ports: ['success', 'failure'],
        configSchema: {},
        defaults: {},
      },
      {
        type: 'PROJECT_DIGEST',
        title: 'Project digest',
        description: 'Digest',
        ports: ['success', 'failure'],
        configSchema: {},
        defaults: {},
      },
    ];
    expect(paletteActions(actions, '').map((a) => a.title)).toEqual([
      'Fetch issue',
      'Project digest',
    ]);
    expect(paletteActions(actions, 'jira').map((a) => a.action.type)).toEqual(['FETCH_ISSUE']);
    expect(paletteActions(actions, 'digest').map((a) => a.action.type)).toEqual(['PROJECT_DIGEST']);
    expect(paletteActions(null, '')).toEqual([]);
  });
});
