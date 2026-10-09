import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ProjectRepository } from '../../../core/api/types';
import {
  addRepository,
  moveRepository,
  removeRepository,
  repositoriesArray,
  repositoriesValue,
  setPrimary,
  suggestKey,
} from '../data/repositories-form';
import { ProjectRepositoriesEditorComponent } from './project-repositories-editor.component';

const main: ProjectRepository = {
  key: 'main',
  url: 'https://g/shop/api.git',
  defaultBranch: 'main',
  purpose: 'SOURCE',
  primary: true,
};
const docs: ProjectRepository = {
  key: 'docs',
  url: 'https://g/shop/docs.git',
  defaultBranch: 'trunk',
  purpose: 'DOCS',
  description: 'Handbook',
  primary: false,
};

describe('repositories form (ADR 0034)', () => {
  it('allows an empty list (LLM work only)', () => {
    const a = repositoriesArray();
    expect(a.valid).toBe(true);
    expect(repositoriesValue(a)).toEqual([]);
  });

  it('adds the first repository as primary, the next ones not', () => {
    const a = repositoriesArray();
    addRepository(a);
    expect(a.at(0).getRawValue()).toMatchObject({ key: 'main', primary: true, purpose: 'SOURCE' });
    addRepository(a);
    expect(a.at(1).getRawValue()).toMatchObject({ key: 'repo-2', primary: false });
    expect(suggestKey([])).toBe('main');
    expect(suggestKey(['main', 'repo-3'])).toBe('repo-4');
  });

  it('removes and reorders', () => {
    const a = repositoriesArray([main, docs]);
    moveRepository(a, 0, 1);
    expect(repositoriesValue(a).map((r) => r.key)).toEqual(['docs', 'main']);
    moveRepository(a, 0, -1);
    expect(repositoriesValue(a).map((r) => r.key)).toEqual(['docs', 'main']);
    moveRepository(a, 1, -1);
    expect(repositoriesValue(a).map((r) => r.key)).toEqual(['main', 'docs']);
    removeRepository(a, 0);
    expect(repositoriesValue(a).map((r) => r.key)).toEqual(['docs']);
    expect(a.dirty).toBe(true);
  });

  it('keeps at most one primary and allows none', () => {
    const a = repositoriesArray([main, docs]);
    setPrimary(a, 1, true);
    expect(repositoriesValue(a).map((r) => r.primary)).toEqual([false, true]);
    setPrimary(a, 1, false);
    expect(repositoriesValue(a).map((r) => r.primary)).toEqual([false, false]);
    expect(a.valid).toBe(true);
  });

  it('validates key pattern, uniqueness and the URL per row', () => {
    const a = repositoriesArray([main, docs]);
    a.at(1).controls.key.setValue('main');
    expect(a.at(1).controls.key.hasError('duplicate')).toBe(true);
    expect(a.at(0).controls.key.hasError('duplicate')).toBe(false);
    expect(a.hasError('duplicateKeys')).toBe(true);
    a.at(1).controls.key.setValue('docs');
    expect(a.at(1).controls.key.errors).toBeNull();
    expect(a.valid).toBe(true);
    a.at(1).controls.key.setValue('Docs!');
    expect(a.at(1).controls.key.hasError('pattern')).toBe(true);
    a.at(1).controls.key.setValue('docs');
    a.at(1).controls.url.setValue('');
    expect(a.at(1).controls.url.hasError('required')).toBe(true);
    expect(a.valid).toBe(false);
  });

  it('sends trimmed values and null for an empty description', () => {
    const a = repositoriesArray([{ ...main, url: '  https://g/x.git ', description: '' }, docs]);
    expect(repositoriesValue(a)).toEqual([
      { ...main, url: 'https://g/x.git', description: null },
      docs,
    ]);
  });
});

describe('ProjectRepositoriesEditorComponent', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      imports: [ProjectRepositoriesEditorComponent],
      // HttpClient testing keeps the icon requests pending instead of failing them.
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }),
  );

  function create(list: ProjectRepository[]) {
    const array = repositoriesArray(list);
    const fixture = TestBed.createComponent(ProjectRepositoriesEditorComponent);
    fixture.componentRef.setInput('array', array);
    fixture.detectChanges();
    return { fixture, array, el: fixture.nativeElement as HTMLElement };
  }

  it('shows the LLM-only hint for an empty list and adds a primary repository', async () => {
    const { fixture, array, el } = create([]);
    expect(el.textContent).toContain('No repository - this project runs LLM work only');
    fixture.componentInstance.add();
    fixture.detectChanges();
    expect(array.length).toBe(1);
    expect(el.querySelectorAll('li.repo').length).toBe(1);
    expect(el.textContent).not.toContain('No repository - this project runs LLM work only');
  });

  it('renders a row per repository, re-renders on changes from outside, and moves rows', async () => {
    const { fixture, array, el } = create([main, docs]);
    expect(el.querySelectorAll('li.repo').length).toBe(2);
    array.removeAt(1);
    fixture.detectChanges();
    expect(el.querySelectorAll('li.repo').length).toBe(1);
    array.push(repositoriesArray([docs]).at(0));
    fixture.componentInstance.move(1, -1);
    expect(repositoriesValue(array).map((r) => r.key)).toEqual(['docs', 'main']);
  });

  it('turning one primary on turns the other off, and says when there is none', async () => {
    const { fixture, array, el } = create([main, docs]);
    fixture.componentInstance.togglePrimary(1, true);
    expect(repositoriesValue(array).map((r) => r.primary)).toEqual([false, true]);
    fixture.componentInstance.togglePrimary(1, false);
    fixture.detectChanges();
    expect(el.textContent).toContain('No primary repository');
  });

  it('changes nothing when read-only', () => {
    const { fixture, array, el } = create([main]);
    fixture.componentRef.setInput('readonly', true);
    fixture.detectChanges();
    fixture.componentInstance.add();
    fixture.componentInstance.remove(0);
    expect(array.length).toBe(1);
    expect(el.querySelector('button.add')).toBeNull();
  });
});
