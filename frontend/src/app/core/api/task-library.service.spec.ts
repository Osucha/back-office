import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_ERROR_TOAST } from './api-base';
import { TaskLibraryApi } from './task-library.service';
import type { Agent, AgentInput, ResolvedTask, TaskConfigSchemas } from './types';

describe('TaskLibraryApi (ADR 0054)', () => {
  let api: TaskLibraryApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(TaskLibraryApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('resolveStep() posts the overrides of a step', () => {
    let key = '';
    api
      .resolveStep('code-review', { promptAppend: 'Be strict' }, 'p1')
      .subscribe((r) => (key = r.key ?? ''));
    const req = http.expectOne((r) => r.url === '/api/v1/task-templates/resolve/code-review');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('projectId')).toBe('p1');
    expect(req.request.body).toEqual({ promptAppend: 'Be strict' });
    expect(req.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    req.flush({ key: 'code-review' } satisfies ResolvedTask);
    expect(key).toBe('code-review');
  });

  it('taskConfigSchemas() is loaded once and retried after an error (ADR 0056)', () => {
    const schemas: TaskConfigSchemas = { modelAnswer: { type: 'object' }, codingAgent: {} };
    let failed = false;
    api.taskConfigSchemas().subscribe({ error: () => (failed = true) });
    http
      .expectOne('/api/v1/workflows/task-config-schemas')
      .flush(null, { status: 500, statusText: 'boom' });
    expect(failed).toBe(true);

    let a: TaskConfigSchemas | null = null;
    let b: TaskConfigSchemas | null = null;
    api.taskConfigSchemas().subscribe((x) => (a = x));
    http.expectOne('/api/v1/workflows/task-config-schemas').flush(schemas);
    api.taskConfigSchemas().subscribe((x) => (b = x));
    http.expectNone('/api/v1/workflows/task-config-schemas');
    expect(a).toEqual(schemas);
    expect(b).toEqual(schemas);
  });

  it('agents: list with a project, create, update and delete (ADR 0056)', () => {
    const input: AgentInput = {
      name: 'Cheap coder',
      scope: { type: 'GLOBAL' },
      harness: 'OPENCODE',
    };
    let list: Agent[] = [];
    api.listAgents('p1').subscribe((x) => (list = x));
    const get = http.expectOne((r) => r.url === '/api/v1/agents');
    expect(get.request.params.get('projectId')).toBe('p1');
    get.flush([{ ...input, id: 'a1' }]);
    expect(list.length).toBe(1);

    api.createAgent(input).subscribe();
    const post = http.expectOne('/api/v1/agents');
    expect(post.request.method).toBe('POST');
    expect(post.request.body).toEqual(input);
    post.flush({ ...input, id: 'a1' });

    api.updateAgent('a1', input).subscribe();
    expect(http.expectOne('/api/v1/agents/a1').request.method).toBe('PUT');

    api.deleteAgent('a1').subscribe();
    const del = http.expectOne('/api/v1/agents/a1');
    expect(del.request.method).toBe('DELETE');
    del.flush(null);
  });
});
