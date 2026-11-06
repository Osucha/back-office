import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_ERROR_TOAST } from './api-base';
import { ProjectsApi } from './projects.service';
import type { WorkflowDefinition } from './types';

describe('ProjectsApi default workflows (ADR 0059)', () => {
  let api: ProjectsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ProjectsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('missingDefaultWorkflows() lists the missing names without an error toast', () => {
    let names: string[] = [];
    api.missingDefaultWorkflows('p 1').subscribe((n) => (names = n));
    const req = http.expectOne('/api/v1/projects/p%201/default-workflows');
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush(['Reference: one Claude Code session']);
    expect(names).toEqual(['Reference: one Claude Code session']);
  });

  it('addDefaultWorkflows() posts and returns the added workflows, errors left to the caller', () => {
    let added: string[] = [];
    api.addDefaultWorkflows('p1').subscribe((list) => (added = list.map((w) => w.name)));
    const req = http.expectOne('/api/v1/projects/p1/default-workflows');
    expect(req.request.method).toBe('POST');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush([{ id: 'w1', name: 'Reference: one Claude Code session' } as WorkflowDefinition]);
    expect(added).toEqual(['Reference: one Claude Code session']);
  });
});
