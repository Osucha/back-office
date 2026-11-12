import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Connector } from '../../../core/api/types';
import { ConnectorsPanelComponent } from './connectors-panel.component';

const list: Connector[] = [
  {
    id: 'c1',
    key: 'docs',
    name: 'Docs',
    kind: 'MCP_HTTP',
    url: 'https://mcp.example.com/mcp',
    host: 'mcp.example.com',
    auth: 'BEARER',
    hasSecret: true,
    scope: { type: 'GLOBAL', projectId: null },
    lastTest: { ok: false, message: 'HTTP 401', testedAt: '2026-11-12T10:00:00Z' },
  },
  {
    id: 'c2',
    key: 'gitlab-read',
    name: 'CI logs',
    kind: 'GITLAB_READ',
    auth: 'NONE',
    scope: { type: 'PROJECT', projectId: 'p1' },
  },
];

describe('ConnectorsPanelComponent (ADR 0064)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ConnectorsPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  function create(projectId: string | null, canManage = true) {
    const fixture = TestBed.createComponent(ConnectorsPanelComponent);
    fixture.componentRef.setInput('projectId', projectId);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.componentRef.setInput('repositories', [
      { key: 'main', url: 'https://g/x.git', primary: true },
      { key: 'api', url: 'https://g/api.git' },
    ]);
    fixture.detectChanges();
    const req = http.expectOne((r) => r.url === '/api/v1/connectors');
    expect(req.request.params.get('projectId')).toBe(projectId);
    req.flush(projectId ? list : [list[0]]);
    fixture.detectChanges();
    return { fixture, panel: fixture.componentInstance };
  }

  it('lists the project connectors and, read-only, the platform ones with the last test', () => {
    const { fixture, panel } = create('p1');
    expect(panel.rows().map((r) => r.connector.key)).toEqual(['gitlab-read', 'docs']);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('platform');
    expect(text).toContain('mcp.example.com');
    expect(text).toContain('Primary repository');
    expect(fixture.nativeElement.querySelector('[data-test-ok="false"]')?.textContent).toContain(
      'failed',
    );
    panel.edit(panel.rows()[1]);
    expect(panel.canEdit()).toBe(false);
    panel.edit(panel.rows()[0]);
    expect(panel.canEdit()).toBe(true);
    expect(panel.kinds()).toEqual(['MCP_HTTP', 'GITLAB_READ']);
  });

  it('creates a project MCP connector with a bearer secret and keeps the editor open', () => {
    const { panel } = create('p1');
    panel.create();
    panel.patch({ name: 'Search', url: 'https://search.example.com/mcp', auth: 'BEARER' });
    expect(panel.draftHost()).toBe('search.example.com');
    expect(panel.problems()).toEqual(['Secret: required for this authentication.']);
    panel.patch({ secret: 'tok' });
    expect(panel.problems()).toEqual([]);
    panel.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/connectors');
    expect(req.request.body).toEqual({
      name: 'Search',
      description: '',
      kind: 'MCP_HTTP',
      scope: { type: 'PROJECT', projectId: 'p1' },
      url: 'https://search.example.com/mcp',
      auth: 'BEARER',
      headerName: null,
      secret: 'tok',
      repository: null,
    });
    req.flush({ ...req.request.body, secret: undefined, id: 'c9', key: 'search', hasSecret: true });
    expect(panel.items().map((c) => c.id)).toContain('c9');
    expect(panel.editing()?.connector.id).toBe('c9');
    expect(panel.draft()?.secret).toBe('');
    expect(panel.hasSecret()).toBe(true);
  });

  it('removes a stored secret on request and tests a platform connector', () => {
    const { fixture, panel } = create(null);
    expect(panel.kinds()).toEqual(['MCP_HTTP']);
    panel.edit(panel.rows()[0]);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[data-remove-secret]')).not.toBeNull();
    panel.patch({ auth: 'HEADER', headerName: 'X-Api-Key', removeSecret: true });
    // removing the only secret of a header authentication blocks saving
    expect(panel.problems()).toEqual(['Secret: required for this authentication.']);
    panel.patch({ auth: 'NONE' });
    panel.save();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/connectors/c1');
    expect(put.request.body.secret).toBe('');
    expect(put.request.body.scope).toEqual({ type: 'GLOBAL', projectId: null });
    put.flush({ ...list[0], auth: 'NONE', hasSecret: false });

    panel.test(panel.editing()!.connector);
    http
      .expectOne((r) => r.method === 'POST' && r.url === '/api/v1/connectors/c1/test')
      .flush({ ok: true, testedAt: '2026-11-12T11:00:00Z', tools: ['search', 'fetch'] });
    fixture.detectChanges();
    expect(panel.editing()?.connector.lastTest?.ok).toBe(true);
    const tools = fixture.nativeElement.querySelector('[data-test-tools]') as HTMLElement;
    expect(tools.textContent).toContain('fetch');
  });

  it('is read-only without the permission', () => {
    const { fixture, panel } = create('p1', false);
    expect(fixture.nativeElement.querySelector('[data-add-connector]')).toBeNull();
    panel.edit(panel.rows()[0]);
    expect(panel.canEdit()).toBe(false);
    panel.save();
    http.expectNone((r) => r.method === 'PUT');
  });
});
