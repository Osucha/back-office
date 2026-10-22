import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_ERROR_TOAST } from './api-base';
import type { ModelOption, StepModelPlan } from './types';
import { WorkflowsApi } from './workflows.service';

describe('WorkflowsApi model availability (ADR 0050)', () => {
  let api: WorkflowsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(WorkflowsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('modelOptions() asks for the options of a step kind and harness', () => {
    let ids: string[] = [];
    api
      .modelOptions('p1', 'CODING_AGENT', 'CLAUDE_CODE')
      .subscribe((list) => (ids = list.map((o) => o.profileId)));
    const req = http.expectOne((r) => r.url === '/api/v1/workflows/model-options');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('projectId')).toBe('p1');
    expect(req.request.params.get('stepKind')).toBe('CODING_AGENT');
    expect(req.request.params.get('harness')).toBe('CLAUDE_CODE');
    req.flush([{ profileId: 'a' } as ModelOption]);
    expect(ids).toEqual(['a']);
  });

  it('modelOptions() leaves out a missing harness', () => {
    api.modelOptions('p1', 'LLM_CALL').subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/workflows/model-options');
    expect(req.request.params.get('stepKind')).toBe('LLM_CALL');
    expect(req.request.params.has('harness')).toBe(false);
    req.flush([]);
  });

  it('modelPlan() loads the plan of a workflow without an error toast', () => {
    let plan: StepModelPlan[] = [];
    api.modelPlan('w 1').subscribe((p) => (plan = p));
    const req = http.expectOne('/api/v1/workflows/w%201/model-plan');
    expect(req.request.method).toBe('GET');
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush([{ nodeId: 'n', nodeName: 'N', stepKind: 'LLM_CALL', source: 'NONE' }]);
    expect(plan.map((s) => s.nodeId)).toEqual(['n']);
  });
});
