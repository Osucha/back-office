import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { ProjectRepository } from '../../../core/api/types';
import type { ScmConnection } from '../data/connection-types';
import { ScmConnectionEditorComponent } from './scm-connection-editor.component';
import { ScmConnectionsPanelComponent } from './scm-connections-panel.component';

const repositories = [
  { key: 'api', url: 'https://gitlab.example.com/g/api.git', defaultBranch: 'main', primary: true },
  { key: 'web', url: 'https://gitlab.example.com/g/web.git', defaultBranch: 'main' },
] as ProjectRepository[];

const conn = (
  id: string,
  repositoryKey: string,
  name: string,
  capabilities: string[],
  extra: Partial<ScmConnection> = {},
): ScmConnection => ({
  id,
  repositoryKey,
  repositoryExists: true,
  name,
  provider: 'GITLAB',
  capabilities: capabilities as ScmConnection['capabilities'],
  expiryStatus: 'NONE',
  readOnly: false,
  createdAt: '2026-10-01T10:00:00Z',
  createdBy: 'Owner',
  updatedAt: '2026-10-01T10:00:00Z',
  updatedBy: 'Owner',
  ...extra,
});

const reader = conn('r', 'api', 'Reader', ['GIT_READ', 'CI_READ'], {
  readOnly: true,
  verification: {
    status: 'OK',
    checkedAt: '2026-10-01T10:00:00Z',
    role: 'Reporter',
    scopes: ['read_repository', 'read_api'],
    granted: ['GIT_READ', 'CI_READ'],
    missing: [],
    writePowers: [],
    messages: [],
  },
});
const pusher = conn('w', 'api', 'Pusher', ['PUSH_BRANCH'], { expiryStatus: 'EXPIRED' });
const orphan = conn('o', 'old', 'Old reader', ['GIT_READ'], { repositoryExists: false });

describe('ScmConnectionsPanelComponent (ADR 0051)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ScmConnectionsPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // nz-icon loads its svg files over HTTP
    http.match((r) => r.url.startsWith('assets/')).forEach((r) => r.flush('<svg></svg>'));
    http.verify();
  });

  function create(canManage = true) {
    const fixture = TestBed.createComponent(ScmConnectionsPanelComponent);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.componentRef.setInput('repositories', repositories);
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
    http.expectOne('/api/v1/projects/p1/scm-connections').flush([pusher, reader, orphan]);
    fixture.detectChanges();
    return fixture;
  }

  it('lists the tokens per repository with read-only, expiry and orphan markers', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    expect(c.groups().map((g) => [g.key, g.connections.map((x) => x.name)])).toEqual([
      ['api', ['Reader', 'Pusher']],
      ['web', []],
      ['old', ['Old reader']],
    ]);
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('read-only, verified');
    expect(text).toContain('Back Office only');
    expect(text).toContain('token expired');
    expect(text).toContain('repository removed');
    expect(text).toContain('No tokens for this repository yet.');
    expect(text).toContain('Test again');
  });

  it('tests a stored token again and shows the new result', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    c.test(pusher);
    const req = http.expectOne('/api/v1/projects/p1/scm-connections/w/verify');
    expect(req.request.method).toBe('POST');
    req.flush({ ...pusher, verification: { ...reader.verification!, status: 'FAILED' } });
    expect(c.connections().find((x) => x.id === 'w')?.verification?.status).toBe('FAILED');
    expect(c.expanded().has('w')).toBe(true);
  });

  it('is read-only without project.connections.manage', () => {
    const fixture = create(false);
    const text: string = fixture.nativeElement.textContent;
    expect(text).not.toContain('Add token');
    expect(text).not.toContain('Test again');
    expect(text).not.toContain('Least privilege');
  });
});

describe('ScmConnectionEditorComponent (ADR 0051)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ScmConnectionEditorComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.match((r) => r.url.startsWith('assets/')).forEach((r) => r.flush('<svg></svg>'));
    http.verify();
  });

  function open(mode: 'create' | 'edit' | 'rotate', connection: ScmConnection | null) {
    const fixture = TestBed.createComponent(ScmConnectionEditorComponent);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.componentRef.setInput('repositories', repositories);
    fixture.componentRef.setInput('mode', mode);
    fixture.componentRef.setInput('connection', connection);
    fixture.componentRef.setInput('repositoryKey', 'web');
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  it('creates a token for the preselected repository after a draft test', () => {
    const c = open('create', null);
    expect(c.draft().repositoryKey).toBe('web');
    c.save(); // name and token missing: nothing is sent
    expect(Object.keys(c.shown()).sort()).toEqual(['name', 'token']);

    c.set('name', 'Reader');
    c.set('token', 'tkn-1');
    c.toggle('CI_READ', true);
    expect(c.recommended()).toEqual({ role: 'Reporter', scopes: ['read_repository', 'read_api'] });

    c.test();
    const test = http.expectOne('/api/v1/projects/p1/scm-connections/test');
    expect(test.request.body.capabilities).toEqual(['GIT_READ', 'CI_READ']);
    test.flush(reader.verification!);
    expect(c.tested()?.status).toBe('OK');

    c.save();
    const req = http.expectOne('/api/v1/projects/p1/scm-connections');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      repositoryKey: 'web',
      name: 'Reader',
      provider: 'GITLAB',
      capabilities: ['GIT_READ', 'CI_READ'],
      secretExpiresAt: null,
      token: 'tkn-1',
    });
    req.flush(reader);
    expect(c.result()?.id).toBe('r');
  });

  it('keeps the stored token on edit and cannot test without a new one', () => {
    const c = open('edit', pusher);
    expect(c.canTest()).toBe(false);
    c.toggle('MR_COMMENT', true);
    c.save();
    const req = http.expectOne('/api/v1/projects/p1/scm-connections/w');
    expect(req.request.method).toBe('PUT');
    expect('token' in req.request.body).toBe(false);
    expect(req.request.body.capabilities).toEqual(['PUSH_BRANCH', 'MR_COMMENT']);
    req.flush(pusher);
  });

  it('replaces only the token', () => {
    const c = open('rotate', pusher);
    c.save();
    expect(Object.keys(c.shown())).toEqual(['token']);
    c.set('token', 'tkn-2');
    c.save();
    const req = http.expectOne('/api/v1/projects/p1/scm-connections/w');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.token).toBe('tkn-2');
    expect(req.request.body.name).toBe('Pusher');
    req.flush(pusher);
  });
});
