import type { PaletteTask } from '../../../core/api/types';
import { deprecatedPaletteTask, paletteTasks } from './palette-tasks';

describe('palette tasks (ADR 0054)', () => {
  const tasks: PaletteTask[] = [
    { key: 'implement-opencode', name: 'Implement', executor: 'IMPLEMENT' },
    { key: 'implement-claude-code', name: 'Implement with Claude Code', executor: 'IMPLEMENT' },
    { key: 'code-review', name: 'Code review', executor: 'LLM', interfaces: ['reviewer'] },
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

  it('matches name, key, interface and the executor label', () => {
    expect(paletteTasks(tasks, 'review', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'REVIEWER', deprecated).map((t) => t.key)).toEqual(['code-review']);
    expect(paletteTasks(tasks, 'coding agent', deprecated).map((t) => t.key)).toEqual([
      'implement-opencode',
    ]);
    expect(paletteTasks(tasks, 'claude', deprecated)).toEqual([]);
  });
});
