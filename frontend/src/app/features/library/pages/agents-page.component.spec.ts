import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { meFixture } from '../../../core/auth/testing';
import { By } from '@angular/platform-browser';
import { AgentEditorPanelComponent } from '../components/agent-editor-panel.component';
import { AgentsPageComponent } from './agents-page.component';

const agents: Agent[] = [
  {
    session: 'PER_STEP',
    id: 'a0',
    key: 'model-answer',
    name: 'Model answer',
    scope: { type: 'GLOBAL' },
    harness: null,
    builtIn: true,
  },
  {
    session: 'PER_RUN',
    id: 'a1',
    key: 'cheap-coder',
    name: 'Cheap coder',
    description: 'OpenCode on a small model',
    scope: { type: 'GLOBAL' },
    harness: 'OPENCODE',
    modelProfileId: 'm1',
    rules: ['agent-ground-rules', 'ask-dont-guess'],
    skills: ['spec-writing'],
    tags: ['budget'],
    access: { directory: 'SEPARATE', mode: 'PATHS', paths: ['docs/**'] },
    builtIn: false,
  },
];

describe('AgentsPageComponent (ADR 0056/0057)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AgentsPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(IdentityService).setMe(
      meFixture({ platformPermissions: ['library.view', 'library.manage'] }),
    );
  });

  function create() {
    const fixture = TestBed.createComponent(AgentsPageComponent);
    fixture.detectChanges();
    http.match((r) => r.url.includes('/projects')).forEach((r) => r.flush([]));
    http
      .match((r) => r.url.includes('/model-profiles'))
      .forEach((r) =>
        r.flush([
          { id: 'm1', displayName: 'Small model', ref: { modelId: 'small' }, enabled: true },
        ]),
      );
    http.expectOne((r) => r.url === '/api/v1/agents').flush(agents);
    fixture.detectChanges();
    const ed = fixture.debugElement.query(By.directive(AgentEditorPanelComponent))
      .componentInstance as AgentEditorPanelComponent;
    return { fixture, page: fixture.componentInstance, ed };
  }

  it('lists agents with their kind and model, built-ins read-only', () => {
    const { fixture, page } = create();
    expect(page.rows().map((a) => a.name)).toEqual(['Model answer', 'Cheap coder']);
    const kinds = Array.from(
      fixture.nativeElement.querySelectorAll('[data-kind]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(kinds).toContain('OpenCode');
    expect(kinds).toContain('Model answer');
    expect(fixture.nativeElement.textContent).toContain('Default for the step');
    expect(page.canEdit(agents[0])).toBe(false);
    expect(page.canEdit(agents[1])).toBe(true);
    const env = Array.from(
      fixture.nativeElement.querySelectorAll('[data-environment]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(env).toEqual(['0 rules · 0 skills', '2 rules · 1 skill · 0 connectors']);
    const access = Array.from(
      fixture.nativeElement.querySelectorAll('td [data-access]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(access).toEqual(['Only docs/**']);
    // ADR 0062: the session and a separate directory of coding agents
    const sessions = Array.from(
      fixture.nativeElement.querySelectorAll('td [data-session]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(sessions).toEqual(['One per run']);
    expect(fixture.nativeElement.querySelector('td [data-directory]')?.textContent).toContain(
      'Separate directory',
    );
  });

  it('sets the directory and the session of a coding agent (ADR 0062)', () => {
    const { fixture, page, ed } = create();
    page.create();
    fixture.detectChanges();
    ed.patch({ name: 'Isolated' });
    ed.setKind('CLAUDE_CODE');
    fixture.detectChanges();
    expect(ed.session()).toBe('PER_STEP');
    expect(ed.directory()).toBe('RUN');
    ed.setDirectory('SEPARATE');
    ed.patch({ session: 'FRESH' });
    ed.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.session).toBe('FRESH');
    expect(req.request.body.access).toEqual({ mode: 'FULL', directory: 'SEPARATE' });
  });

  it('limits what a coding agent may change (ADR 0060)', () => {
    const { ed } = create();
    ed.patch({ name: 'Doc writer' });
    ed.setKind('CLAUDE_CODE');
    ed.setAccess('PATHS');
    expect(ed.accessError()).toBe(true);
    expect(ed.canSave()).toBe(false);
    ed.setAccessPaths(['docs/**']);
    expect(ed.canSave()).toBe(true);
    ed.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.access).toEqual({
      mode: 'PATHS',
      directory: 'RUN',
      paths: ['docs/**'],
    });
  });

  it('edits the environment: rules, skills and connectors of the scope, connectors only for coding agents (ADR 0057/0064)', () => {
    const { fixture, page, ed } = create();
    http
      .match((r) => r.url === '/api/v1/rules')
      .forEach((r) =>
        r.flush([
          {
            key: 'ask-dont-guess',
            name: 'Ask, do not guess',
            scope: { type: 'GLOBAL' },
            content: '',
          },
          {
            key: 'p2-rule',
            name: 'P2 rule',
            scope: { type: 'PROJECT', projectId: 'p2' },
            content: '',
          },
        ]),
      );
    http
      .match((r) => r.url === '/api/v1/skills')
      .forEach((r) =>
        r.flush([{ key: 'kotlin', name: 'Kotlin', scope: { type: 'GLOBAL' }, content: '' }]),
      );
    http
      .match((r) => r.url === '/api/v1/connectors')
      .forEach((r) =>
        r.flush([
          {
            id: 'c1',
            key: 'docs',
            name: 'Docs',
            kind: 'MCP_HTTP',
            auth: 'NONE',
            scope: { type: 'GLOBAL' },
          },
          {
            id: 'c2',
            key: 'gitlab-read',
            name: 'GitLab P2',
            kind: 'GITLAB_READ',
            auth: 'NONE',
            scope: { type: 'PROJECT', projectId: 'p2' },
          },
        ]),
      );
    page.create();
    fixture.detectChanges();
    expect(ed.ruleOptions()).toEqual([{ key: 'ask-dont-guess', label: 'Ask, do not guess' }]);
    expect(ed.skillOptions()).toEqual([{ key: 'kotlin', label: 'Kotlin' }]);
    // a global agent sees the platform connectors only
    expect(ed.connectorOptions()).toEqual([{ key: 'docs', label: 'Docs (docs)', unknown: false }]);

    ed.patch({ name: 'Coder', instructions: 'Write tests first.', rules: ['ask-dont-guess'] });
    ed.setKind('OPENCODE');
    fixture.detectChanges();
    ed.patch({ connectors: ['docs', 'Bad Key'] });
    expect(ed.connectorError()).toContain('Bad Key');
    expect(ed.canSave()).toBe(false);
    // a key projects provide is allowed for a global agent and shown as not defined here
    ed.patch({ connectors: ['docs', 'jira'] });
    expect(ed.connectorError()).toBeNull();
    expect(ed.connectorOptions().find((o) => o.key === 'jira')?.unknown).toBe(true);
    expect(ed.canSave()).toBe(true);

    ed.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.instructions).toBe('Write tests first.');
    expect(req.request.body.rules).toEqual(['ask-dont-guess']);
    expect(req.request.body.connectors).toEqual(['docs', 'jira']);
    req.flush({ ...req.request.body, id: 'a3', key: 'coder' });
    http.match((r) => r.url === '/api/v1/agents').forEach((r) => r.flush(agents));
  });

  it('sends no connectors for a model answer agent', () => {
    const { fixture, page, ed } = create();
    page.create();
    ed.patch({ name: 'Answer', connectors: ['Bad'] });
    fixture.detectChanges();
    expect(ed.connectorError()).toBeNull();
    expect(ed.canSave()).toBe(true);
    ed.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.connectors).toEqual([]);
  });

  it('creates an agent without a key, with the chosen kind', () => {
    const { page, ed } = create();
    ed.patch({ name: 'Reviewer' });
    ed.setKind('CLAUDE_CODE');
    expect(ed.kind()).toBe('CLAUDE_CODE');
    expect(ed.canSave()).toBe(true);
    ed.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body).toEqual({
      name: 'Reviewer',
      description: '',
      scope: { type: 'GLOBAL', projectId: null },
      harness: 'CLAUDE_CODE',
      modelProfileId: null,
      instructions: '',
      rules: [],
      skills: [],
      connectors: [],
      session: 'PER_STEP',
      tags: [],
    });
    req.flush({ ...req.request.body, id: 'a2', key: 'reviewer' });
    http.match((r) => r.url === '/api/v1/agents').forEach((r) => r.flush(agents));
    ed.setKind('MODEL_ANSWER');
    expect(ed.draft().harness).toBeNull();
  });
});
