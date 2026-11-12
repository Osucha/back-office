import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { SKIP_ERROR_TOAST } from './api-base';
import { ConnectorsApi } from './connectors.service';
import type { ConnectorInput } from './types';

describe('ConnectorsApi (ADR 0064)', () => {
  let api: ConnectorsApi;
  let http: HttpTestingController;
  const input: ConnectorInput = {
    name: 'Docs',
    kind: 'MCP_HTTP',
    auth: 'NONE',
    url: 'https://mcp.example.com/mcp',
    scope: { type: 'GLOBAL', projectId: null },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    api = TestBed.inject(ConnectorsApi);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the platform connectors, with a project also its own', () => {
    api.list().subscribe();
    const platform = http.expectOne((r) => r.url === '/api/v1/connectors');
    expect(platform.request.params.has('projectId')).toBe(false);
    platform.flush([]);
    api.list('p1').subscribe();
    const project = http.expectOne((r) => r.url === '/api/v1/connectors');
    expect(project.request.params.get('projectId')).toBe('p1');
    project.flush([]);
  });

  it('creates and updates with errors left to the editor', () => {
    api.create(input).subscribe();
    const created = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/connectors');
    expect(created.request.body).toEqual(input);
    expect(created.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    created.flush({ ...input, id: 'c1' });
    api.update('c1', input).subscribe();
    const updated = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/connectors/c1');
    expect(updated.request.context.get(SKIP_ERROR_TOAST)).toBe(true);
    updated.flush({ ...input, id: 'c1' });
  });

  it('deletes and tests a connector', () => {
    api.delete('c 1').subscribe();
    http
      .expectOne((r) => r.method === 'DELETE' && r.url === '/api/v1/connectors/c%201')
      .flush(null);
    let ok = false;
    api.test('c1').subscribe((r) => (ok = r.ok));
    const test = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/connectors/c1/test',
    );
    test.flush({ ok: true, testedAt: '2026-11-12T10:00:00Z', tools: ['search'] });
    expect(ok).toBe(true);
  });
});
