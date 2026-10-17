import { TestBed } from '@angular/core/testing';
import type { LibrarySource, ProjectRepository } from '../../../core/api/types';
import {
  setRepositoryAccess,
  stepLibraryKeys,
  stepRepositories,
  toggleLibrarySource,
  toggleRepository,
  unknownKeys,
  unknownLibraryKeys,
  withStepRepositories,
} from '../data/step-repositories';
import { StepRepositoriesSectionComponent } from './step-repositories-section.component';

const repos: ProjectRepository[] = [
  { key: 'main', url: 'https://g/main.git', defaultBranch: 'main', purpose: 'SOURCE', primary: true },
  { key: 'docs', url: 'https://g/docs.git', defaultBranch: 'main', purpose: 'DOCS', primary: false },
];

const source = (key: string): LibrarySource => ({
  id: key,
  key,
  name: key,
  url: `https://g/${key}.git`,
  purpose: 'STANDARDS',
  jobKey: `lib-${key}`,
  createdAt: '2026-10-17T10:00:00Z',
  updatedAt: '2026-10-17T10:00:00Z',
});
const sources = [source('standards'), source('templates')];

describe('library sources of a step (ADR 0043)', () => {
  it('reads and writes library entries as READ with library: true', () => {
    const list = stepRepositories({
      repositories: [
        { key: 'main', access: 'PUSH_MR' },
        { key: 'standards', access: 'PUSH_MR', library: true },
      ],
    });
    expect(list).toEqual([
      { key: 'main', access: 'PUSH_MR' },
      { key: 'standards', access: 'READ', library: true },
    ]);
    expect(withStepRepositories({}, list)).toEqual({
      repositories: [
        { key: 'main', access: 'PUSH_MR' },
        { key: 'standards', access: 'READ', library: true },
      ],
    });
    expect(stepLibraryKeys(list)).toEqual(['standards']);
  });

  it('keeps project and library entries apart', () => {
    let list = toggleLibrarySource(sources, [{ key: 'docs', access: 'READ' }], 'templates', true);
    list = toggleLibrarySource(sources, list, 'standards', true);
    expect(list.map((r) => r.key)).toEqual(['docs', 'standards', 'templates']);
    list = toggleRepository(repos, list, 'main', true);
    expect(list.map((r) => r.key)).toEqual(['main', 'docs', 'standards', 'templates']);
    list = toggleRepository(repos, list, 'docs', false);
    expect(list.map((r) => r.key)).toEqual(['main', 'standards', 'templates']);
    expect(setRepositoryAccess(list, 'standards', 'PUSH_MR')[1].access).toBe('READ');
    expect(unknownKeys(repos, list)).toEqual([]);
    expect(unknownLibraryKeys([sources[0]], list)).toEqual(['templates']);
    expect(toggleLibrarySource(sources, list, 'standards', false).map((r) => r.key)).toEqual([
      'main',
      'templates',
    ]);
  });

  it('ticking a source in the default mode starts the explicit list with the primary', () => {
    const fixture = TestBed.createComponent(StepRepositoriesSectionComponent);
    fixture.componentRef.setInput('repositories', repos);
    fixture.componentRef.setInput('librarySources', sources);
    fixture.componentRef.setInput('config', { push: false });
    fixture.componentRef.setInput('pushes', false);
    fixture.detectChanges();
    const emitted: Array<Record<string, unknown> | undefined> = [];
    fixture.componentInstance.configChange.subscribe((c) => emitted.push(c));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('lib-standards');
    fixture.componentInstance.toggleLibrary('standards', true);
    expect(emitted.at(-1)).toEqual({
      push: false,
      repositories: [
        { key: 'main', access: 'READ' },
        { key: 'standards', access: 'READ', library: true },
      ],
    });
  });
});
