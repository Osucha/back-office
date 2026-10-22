import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ScmConnectionInput } from './connection-types';
import { ScmConnectionsApi } from './scm-connections-api.service';

const input: ScmConnectionInput = {
  repositoryKey: 'api',
  name: 'Reader',
  provider: 'GITLAB',
  capabilities: ['GIT_READ'],
  token: 'glpat-1',
};

describe('ScmConnectionsApi (ADR 0051)', () => {
  let api: ScmConnectionsApi;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ScmConnectionsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the tokens of a project', () => {
    let count = -1;
    api.list('p1').subscribe((l) => (count = l.length));
    const req = http.expectOne('/api/v1/projects/p1/scm-connections');
    expect(req.request.method).toBe('GET');
    req.flush([]);
    expect(count).toBe(0);
  });

  it('creates, tests, updates, verifies and deletes', () => {
    api.create('p1', input).subscribe();
    const create = http.expectOne('/api/v1/projects/p1/scm-connections');
    expect(create.request.method).toBe('POST');
    expect(create.request.body).toEqual(input);
    create.flush({});

    api.test('p1', input).subscribe();
    const test = http.expectOne('/api/v1/projects/p1/scm-connections/test');
    expect(test.request.method).toBe('POST');
    expect(test.request.body).toEqual(input);
    test.flush({});

    api.update('p1', 'c1', { ...input, token: undefined }).subscribe();
    const update = http.expectOne('/api/v1/projects/p1/scm-connections/c1');
    expect(update.request.method).toBe('PUT');
    update.flush({});

    api.verify('p1', 'c1').subscribe();
    const verify = http.expectOne('/api/v1/projects/p1/scm-connections/c1/verify');
    expect(verify.request.method).toBe('POST');
    verify.flush({});

    api.remove('p1', 'c1').subscribe();
    const remove = http.expectOne('/api/v1/projects/p1/scm-connections/c1');
    expect(remove.request.method).toBe('DELETE');
    remove.flush(null);
  });
});
