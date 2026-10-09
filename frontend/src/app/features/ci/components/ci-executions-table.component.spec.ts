import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { CiExecutionSummary } from '../../../core/api/types';
import { CiExecutionsTableComponent, filterCi } from './ci-executions-table.component';

const docs: CiExecutionSummary = {
  id: 'x1',
  entrypoint: 'docs-review',
  mode: 'BLOCKING',
  status: 'SUCCEEDED',
  gateResult: 'PASS',
  projectKey: 'SHOP',
  repositoryKey: 'docs',
  context: {
    provider: 'GITLAB',
    ref: 'feature/a',
    repositoryUrl: 'https://gitlab.example.com/shop/docs.git',
  },
  createdAt: '2026-10-01T10:00:00Z',
};
const legacy: CiExecutionSummary = {
  ...docs,
  id: 'x2',
  entrypoint: 'code-review',
  repositoryKey: null,
  context: { provider: 'GITLAB', ref: 'main' },
};

describe('CI executions: repository (ADR 0034)', () => {
  it('finds executions by repository key', () => {
    expect(filterCi([docs, legacy], [], 'docs').map((x) => x.id)).toEqual(['x1']);
  });

  it('shows the repository key with its URL, and nothing for older CI clients', async () => {
    TestBed.configureTestingModule({
      imports: [CiExecutionsTableComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(CiExecutionsTableComponent);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.detectChanges();
    http
      .expectOne((r) => r.url.endsWith('/ci/executions'))
      .flush({ content: [docs, legacy], totalElements: 2, page: 0, size: 20 });
    fixture.detectChanges();
    await fixture.whenStable();
    const chips = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLElement>('.repo-key'),
    );
    expect(chips.map((c) => c.textContent?.trim())).toEqual(['docs']);
    expect(chips[0].getAttribute('title')).toBe('https://gitlab.example.com/shop/docs.git');
    expect(chips[0].getAttribute('aria-label')).toBe('Repository docs');
  });
});
