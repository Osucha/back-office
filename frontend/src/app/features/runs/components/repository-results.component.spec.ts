import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { RepositoryResult } from '../../../core/api/types';
import { pushState, repositoryRows } from '../data/repository-results';
import { RepositoryResultsComponent } from './repository-results.component';

const results: RepositoryResult[] = [
  {
    key: 'main',
    url: 'https://gitlab.example.com/shop/api.git',
    purpose: 'SOURCE',
    access: 'PUSH_MR',
    primary: true,
    branch: 'bo/SHOP-1-implement',
    commitSha: '0123456789abcdef',
    diffStat: { files: 3, insertions: 42, deletions: 7 },
    mergeRequestUrl: 'https://gitlab.example.com/shop/api/-/merge_requests/5',
    pushed: true,
  },
  {
    key: 'docs',
    purpose: 'DOCS',
    access: 'READ',
    primary: false,
    branch: 'main',
    commitSha: 'fedcba9876543210',
    pushed: false,
  },
  {
    key: 'infra',
    purpose: 'OTHER',
    access: 'PUSH_MR',
    pushed: false,
    error: 'push rejected: protected branch',
  },
];

describe('repository results (ADR 0034)', () => {
  it('maps results to rows with access, short commit, diff stat and push state', () => {
    const rows = repositoryRows(results);
    expect(rows.map((r) => [r.key, r.purpose, r.access, r.primary, r.commit, r.state])).toEqual([
      ['main', 'Source code', 'Push and merge request', true, '01234567', 'pushed'],
      ['docs', 'Documentation', 'Read', false, 'fedcba98', 'readOnly'],
      ['infra', 'Other', 'Push and merge request', false, '', 'failed'],
    ]);
    expect(rows[0].diff).toEqual({ files: 3, insertions: 42, deletions: 7 });
    expect(rows[1].diff).toBeNull();
    expect(repositoryRows(null)).toEqual([]);
  });

  it('says not pushed for a push step that pushed nothing', () => {
    expect(pushState({ key: 'x', access: 'PUSH_MR', pushed: false })).toBe('notPushed');
    expect(pushState({ key: 'x', access: 'READ', pushed: false })).toBe('readOnly');
  });

  it('renders one block per repository with MR link and error, nothing without results', () => {
    TestBed.configureTestingModule({
      // HttpClient testing keeps the icon requests pending instead of failing them.
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(RepositoryResultsComponent);
    fixture.componentRef.setInput('results', results);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const items = Array.from(el.querySelectorAll('li.rr'));
    expect(items.length).toBe(3);
    expect(items[0].textContent).toContain('main');
    expect(items[0].textContent).toContain('primary');
    expect(items[0].textContent).toContain('+42');
    expect(items[0].textContent).toContain('−7');
    expect(items[0].querySelector('a.rr-mr')?.getAttribute('href')).toBe(
      'https://gitlab.example.com/shop/api/-/merge_requests/5',
    );
    expect(items[1].querySelector('a.rr-mr')).toBeNull();
    expect(items[1].textContent).toContain('read only');
    expect(items[2].querySelector('.rr-error')?.textContent).toContain('protected branch');

    fixture.componentRef.setInput('results', []);
    fixture.detectChanges();
    expect(el.querySelector('.rr-block')).toBeNull();
  });
});
