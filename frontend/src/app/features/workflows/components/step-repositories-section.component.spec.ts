import { TestBed } from '@angular/core/testing';
import type { ProjectRepository } from '../../../core/api/types';
import {
  initialExplicitList,
  isRunnerTask,
  ownEditorKeys,
  pickerRows,
  repositoryIssues,
  setRepositoryAccess,
  stepPushes,
  stepRepositories,
  toggleRepository,
  withStepRepositories,
} from '../data/step-repositories';
import { StepRepositoriesSectionComponent } from './step-repositories-section.component';

const repos: ProjectRepository[] = [
  {
    key: 'main',
    url: 'https://g/main.git',
    defaultBranch: 'main',
    purpose: 'SOURCE',
    primary: true,
  },
  {
    key: 'docs',
    url: 'https://g/docs.git',
    defaultBranch: 'main',
    purpose: 'DOCS',
    primary: false,
  },
];

describe('step repositories model (ADR 0034)', () => {
  it('knows runner steps and hides the x-editor property from the generic form', () => {
    expect(isRunnerTask({ executor: 'IMPLEMENT' })).toBe(true);
    expect(isRunnerTask({ executor: 'SPEC_KIT' })).toBe(true);
    expect(isRunnerTask({ executor: 'LLM' })).toBe(false);
    expect(isRunnerTask(null)).toBe(false);
    expect(
      ownEditorKeys({
        properties: {
          push: { type: 'boolean' },
          repos: { type: 'array', 'x-editor': 'repositories' },
        },
      }),
    ).toEqual(['repos']);
  });

  it('reads default vs explicit and writes the config', () => {
    expect(stepRepositories({ push: true })).toBeNull();
    expect(stepRepositories({ repositories: [{ key: 'docs', access: 'READ' }] })).toEqual([
      { key: 'docs', access: 'READ' },
    ]);
    expect(withStepRepositories({ push: false }, [{ key: 'docs', access: 'READ' }])).toEqual({
      push: false,
      repositories: [{ key: 'docs', access: 'READ' }],
    });
    expect(withStepRepositories({ repositories: [] }, null)).toBeUndefined();
    expect(withStepRepositories({ push: true, repositories: [] }, null)).toEqual({ push: true });
  });

  it('derives push from the node, then the template, then the schema default', () => {
    expect(stepPushes(undefined, undefined)).toBe(true);
    expect(stepPushes({ push: false }, { push: true })).toBe(false);
    expect(stepPushes({}, { push: false })).toBe(false);
    expect(stepPushes({}, {}, { properties: { push: { type: 'boolean', default: false } } })).toBe(
      false,
    );
  });

  it('starts the explicit list with the primary, adds READ, keeps project order', () => {
    expect(initialExplicitList(repos, true)).toEqual([{ key: 'main', access: 'PUSH_MR' }]);
    expect(initialExplicitList(repos, false)).toEqual([{ key: 'main', access: 'READ' }]);
    expect(initialExplicitList([{ ...repos[1] }], true)).toEqual([]);
    const list = toggleRepository(repos, [{ key: 'docs', access: 'PUSH_MR' }], 'main', true);
    expect(list).toEqual([
      { key: 'main', access: 'READ' },
      { key: 'docs', access: 'PUSH_MR' },
    ]);
    expect(toggleRepository(repos, list, 'docs', false)).toEqual([{ key: 'main', access: 'READ' }]);
    expect(setRepositoryAccess(list, 'main', 'PUSH_MR')[0]).toEqual({
      key: 'main',
      access: 'PUSH_MR',
    });
    expect(
      pickerRows(repos, list, false).map((r) => [r.repository.key, r.checked, r.pushDisabled]),
    ).toEqual([
      ['main', true, true],
      ['docs', true, true],
    ]);
  });

  it('picks the issues of task.config.repositories', () => {
    const issues = [
      { message: 'unknown repository x', field: 'task.config.repositories', nodeId: 'impl' },
      { message: 'name', field: 'name', nodeId: 'impl' },
      { message: 'dup', field: 'nodes[1].task.config.repositories[0].key', nodeId: 'impl' },
    ];
    expect(repositoryIssues(issues).map((i) => i.message)).toEqual(['unknown repository x', 'dup']);
  });
});

describe('StepRepositoriesSectionComponent', () => {
  function create(config: Record<string, unknown> | null, pushes = true) {
    const fixture = TestBed.createComponent(StepRepositoriesSectionComponent);
    fixture.componentRef.setInput('repositories', repos);
    fixture.componentRef.setInput('config', config);
    fixture.componentRef.setInput('pushes', pushes);
    fixture.detectChanges();
    const emitted: Array<Record<string, unknown> | undefined> = [];
    fixture.componentInstance.configChange.subscribe((c) => emitted.push(c));
    return { fixture, c: fixture.componentInstance, emitted };
  }

  it('shows the default mode with the primary and switches to an explicit list', () => {
    const { fixture, c, emitted } = create({ push: true });
    expect(c.explicit()).toBe(false);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('main');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Push and merge request');
    c.setMode('explicit');
    expect(emitted.at(-1)).toEqual({
      push: true,
      repositories: [{ key: 'main', access: 'PUSH_MR' }],
    });
  });

  it('goes back to the default by removing the list', () => {
    const { c, emitted } = create({ repositories: [{ key: 'docs', access: 'READ' }] });
    expect(c.explicit()).toBe(true);
    c.setMode('default');
    expect(emitted.at(-1)).toBeUndefined();
    expect(emitted.length).toBe(1);
  });

  it('adds repositories with READ and refuses PUSH_MR when push is off', () => {
    const { c, emitted } = create({ push: false, repositories: [] }, false);
    c.toggle('docs', true);
    expect(emitted.at(-1)).toEqual({
      push: false,
      repositories: [{ key: 'docs', access: 'READ' }],
    });
    c.setAccess('docs', 'PUSH_MR');
    expect(emitted.length).toBe(1);
    expect(c.rows().every((r) => r.pushDisabled)).toBe(true);
  });

  it('shows the repository issues and a hint without any repository', () => {
    const { fixture } = create({ repositories: [{ key: 'gone', access: 'READ' }] });
    fixture.componentRef.setInput('nodeIssues', [
      { message: 'Unknown repository gone', field: 'task.config.repositories', nodeId: 'n' },
    ]);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Unknown repository gone');
    expect(text).toContain('The project has no repository gone');
    fixture.componentRef.setInput('repositories', []);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain(
      'This project has no repository',
    );
  });
});
