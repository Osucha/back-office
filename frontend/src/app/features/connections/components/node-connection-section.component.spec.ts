import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Connection } from '../data/connection-types';
import { NodeConnectionSectionComponent } from './node-connection-section.component';

const reader: Connection = {
  id: 'c1',
  scope: 'PLATFORM',
  type: 'JIRA',
  name: 'Reader',
  siteUrl: 'https://acme.atlassian.net',
  accountEmail: 'reader@acme.com',
  capabilities: ['ISSUE_READ'],
  allowedProjectKeys: [],
  secretSet: true,
  legacy: false,
  expiryStatus: 'NONE',
};
const writer: Connection = {
  ...reader,
  id: 'c2',
  scope: 'PROJECT',
  name: 'Writer',
  capabilities: ['ISSUE_READ', 'ISSUE_TRANSITION'],
};

describe('NodeConnectionSectionComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NodeConnectionSectionComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(config: Record<string, unknown> | null = { toStatus: 'Done' }) {
    const fixture = TestBed.createComponent(NodeConnectionSectionComponent);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.componentRef.setInput('requiredCapabilities', ['ISSUE_TRANSITION']);
    fixture.componentRef.setInput('config', config);
    fixture.detectChanges();
    http.expectOne('/api/v1/projects/p1/connections/usable').flush([writer, reader]);
    return fixture;
  }

  it('shows the resolved connection and offers only connections that cover the need', () => {
    const fixture = create();
    const req = http.expectOne((r) => r.url === '/api/v1/projects/p1/connections/resolve');
    expect(req.request.params.get('capabilities')).toBe('ISSUE_TRANSITION');
    expect(req.request.params.has('connectionId')).toBe(false);
    req.flush({
      status: 'OK',
      required: ['ISSUE_TRANSITION'],
      connection: writer,
      excess: [],
      pinned: false,
      message: 'ok',
    });
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(c.view()).toMatchObject({ tone: 'ok', text: 'Uses Writer (least privilege).' });
    expect(c.options().map((o) => [o.id, o.disabled])).toEqual([
      ['c2', false],
      ['c1', true],
    ]);
    expect(fixture.nativeElement.textContent).toContain('Move issues');
  });

  it('pins a specific connection and goes back to the system connection', () => {
    const fixture = create({ toStatus: 'Done', jiraConnection: 'c2' });
    const req = http.expectOne((r) => r.url === '/api/v1/projects/p1/connections/resolve');
    expect(req.request.params.get('connectionId')).toBe('c2');
    req.flush({
      status: 'PINNED_INSUFFICIENT',
      required: ['ISSUE_TRANSITION'],
      connection: reader,
      excess: [],
      pinned: true,
      message: 'x',
    });
    const c = fixture.componentInstance;
    expect(c.actAs()).toBe('SPECIFIC');
    expect(c.view()?.tone).toBe('bad');
    const emitted: Array<Record<string, unknown> | undefined> = [];
    c.configChange.subscribe((v) => emitted.push(v));
    c.setActAs('SYSTEM');
    c.pin('c1');
    expect(emitted).toEqual([{ toStatus: 'Done' }, { toStatus: 'Done', jiraConnection: 'c1' }]);
  });

  it('acts as the approver of a write, with an optional fallback to the system connection', () => {
    const fixture = create({ toStatus: 'Done', jiraConnection: 'ACTING_USER' });
    const c = fixture.componentInstance;
    expect(c.actAs()).toBe('ACTING_USER');
    expect(c.writes()).toBe(true);
    // no fallback: nothing to resolve, the personal connection is chosen when someone approves
    http.expectNone((r) => r.url === '/api/v1/projects/p1/connections/resolve');
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('The person who approves the write');
    const emitted: Array<Record<string, unknown> | undefined> = [];
    c.configChange.subscribe((v) => emitted.push(v));
    c.setFlag('fallbackToSystem', true);
    expect(emitted).toEqual([
      { toStatus: 'Done', jiraConnection: 'ACTING_USER', fallbackToSystem: true },
    ]);
  });

  it('lets a SYSTEM write step allow the approver to write as themselves', () => {
    const fixture = create({ toStatus: 'Done' });
    http
      .expectOne((r) => r.url === '/api/v1/projects/p1/connections/resolve')
      .flush({
        status: 'OK',
        required: ['ISSUE_TRANSITION'],
        connection: writer,
        excess: [],
        pinned: false,
        message: 'ok',
      });
    const c = fixture.componentInstance;
    const emitted: Array<Record<string, unknown> | undefined> = [];
    c.configChange.subscribe((v) => emitted.push(v));
    c.setFlag('allowActingUser', true);
    c.setActAs('ACTING_USER');
    expect(emitted).toEqual([
      { toStatus: 'Done', allowActingUser: true },
      { toStatus: 'Done', jiraConnection: 'ACTING_USER' },
    ]);
  });
});
