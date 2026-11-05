import type { PaletteTask } from '../../../core/api/types';
import { deprecatedPaletteTask, paletteGroups, paletteTasks } from './palette-tasks';

describe('palette tasks (ADR 0054)', () => {
  const tasks: PaletteTask[] = [
    { key: 'implement-opencode', name: 'Implement', executor: 'IMPLEMENT' },
    { key: 'implement-claude-code', name: 'Implement with Claude Code', executor: 'IMPLEMENT' },
    { key: 'code-review', name: 'Code review', executor: 'LLM', category: 'REVIEW' },
    { key: 'tagged', name: 'Tagged', executor: 'LLM', tags: ['deprecated'] } as PaletteTask,
    { key: 'flagged', name: 'Flagged', executor: 'LLM', deprecated: true } as PaletteTask,
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

  it('matches name, key, category and the executor label', () => {
    expect(paletteTasks(tasks, 'code-rev', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'REVIEW', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'coding agent', deprecated).map((t) => t.key)).toEqual([
      'implement-opencode',
    ]);
    expect(paletteTasks(tasks, 'claude', deprecated)).toEqual([]);
  });

  it('groups by category, those without one under General (ADR 0055)', () => {
    const groups = paletteGroups(paletteTasks(tasks, '', deprecated));
    expect(groups.map((g) => [g.title, g.tasks.map((t) => t.key)])).toEqual([
      ['Review', ['code-review']],
      ['General', ['implement-opencode']],
    ]);
  });
});
