import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_ERROR_TOAST } from './api-base';
import { TaskLibraryApi } from './task-library.service';
import type { ResolvedTask, TaskExecutorDescriptor } from './types';

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

  it('taskExecutors() is loaded once and retried after an error', () => {
    const list: TaskExecutorDescriptor[] = [{ executor: 'LLM', configSchema: {} }];
    let failed = false;
    api.taskExecutors().subscribe({ error: () => (failed = true) });
    http
      .expectOne('/api/v1/workflows/task-executors')
      .flush(null, { status: 500, statusText: 'boom' });
    expect(failed).toBe(true);

    let a: TaskExecutorDescriptor[] = [];
    let b: TaskExecutorDescriptor[] = [];
    api.taskExecutors().subscribe((x) => (a = x));
    http.expectOne('/api/v1/workflows/task-executors').flush(list);
    api.taskExecutors().subscribe((x) => (b = x));
    http.expectNone('/api/v1/workflows/task-executors');
    expect(a).toEqual(list);
    expect(b).toEqual(list);
  });
});
