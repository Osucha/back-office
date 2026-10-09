import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Connection } from '../data/connection-types';
import { ProjectJiraConnectionsComponent } from './project-jira-connections.component';

const conn = (id: string, name: string, scope: 'PLATFORM' | 'PROJECT', capabilities: string[]): Connection => ({
  id,
  scope,
  type: 'JIRA',
  name,
  siteUrl: 'https://acme.atlassian.net',
  accountEmail: 'bot@acme.com',
  capabilities: capabilities as Connection['capabilities'],
  allowedProjectKeys: [],
  secretSet: true,
  legacy: false,
  expiryStatus: 'NONE',
});
const reader = conn('r', 'Reader', 'PLATFORM', ['ISSUE_READ']);
const writer = conn('w', 'Writer', 'PROJECT', ['ISSUE_READ', 'ISSUE_COMMENT']);
const mover = conn('m', 'Mover', 'PLATFORM', ['ISSUE_READ', 'ISSUE_TRANSITION']);

describe('ProjectJiraConnectionsComponent (ADR 0035)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ProjectJiraConnectionsComponent],
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
    const fixture = TestBed.createComponent(ProjectJiraConnectionsComponent);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.componentRef.setInput('canManage', canManage);
    fixture.detectChanges();
    http.expectOne('/api/v1/projects/p1/jira-connections').flush({
      systemConnectionId: 'r',
      additionalConnectionIds: ['w'],
      systemConnection: reader,
      additionalConnections: [writer],
      available: [writer, reader, mover],
    });
    fixture.detectChanges();
    return fixture;
  }

  it('shows the system connection and the ordered list, and saves a changed choice', () => {
    const fixture = create();
    const c = fixture.componentInstance;
    expect(c.systemConnection()?.name).toBe('Reader');
    expect(c.additional().map((x) => x.name)).toEqual(['Writer']);
    expect(c.addable().map((x) => x.id)).toEqual(['m']);
    expect(c.dirty()).toBe(false);

    c.add('m');
    c.move(1, -1);
    c.setSystem('w'); // the new system connection leaves the list
    expect(c.additionalIds()).toEqual(['m']);
    expect(c.dirty()).toBe(true);

    c.save();
    const req = http.expectOne('/api/v1/projects/p1/jira-connections');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ systemConnectionId: 'w', additionalConnectionIds: ['m'] });
    req.flush({
      systemConnectionId: 'w',
      additionalConnectionIds: ['m'],
      additionalConnections: [mover],
      available: [writer, reader, mover],
    });
    expect(c.dirty()).toBe(false);
  });

  it('is read-only without project.connections.manage', () => {
    const fixture = create(false);
    expect(fixture.nativeElement.textContent).not.toContain('Save Jira connections');
  });
});
