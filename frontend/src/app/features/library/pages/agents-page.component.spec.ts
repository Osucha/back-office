import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { meFixture } from '../../../core/auth/testing';
import { AgentsPageComponent } from './agents-page.component';

const agents: Agent[] = [
  {
    id: 'a0',
    key: 'model-answer',
    name: 'Model answer',
    scope: { type: 'GLOBAL' },
    harness: null,
    builtIn: true,
  },
  {
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
    return { fixture, page: fixture.componentInstance };
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
    expect(env).toEqual(['0 rules · 0 skills', '2 rules · 1 skill']);
  });

  it('edits the environment: rules and skills of the scope, MCP servers only for coding agents (ADR 0057)', () => {
    const { fixture, page } = create();
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
    page.create();
    fixture.detectChanges();
    expect(page.ruleOptions()).toEqual([{ key: 'ask-dont-guess', label: 'Ask, do not guess' }]);
    expect(page.skillOptions()).toEqual([{ key: 'kotlin', label: 'Kotlin' }]);

    page.patch({ name: 'Coder', instructions: 'Write tests first.', rules: ['ask-dont-guess'] });
    page.setKind('OPENCODE');
    page.addMcp();
    page.patchMcp(0, { name: 'Docs', url: 'https://mcp.example.com' });
    expect(page.mcpError()).toContain('lowercase');
    expect(page.canSave()).toBe(false);
    page.patchMcp(0, { name: 'docs', url: 'ftp://x' });
    expect(page.mcpError()).toContain('http');
    page.patchMcp(0, { url: 'https://mcp.example.com/mcp' });
    expect(page.mcpError()).toBeNull();
    expect(page.canSave()).toBe(true);

    page.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.instructions).toBe('Write tests first.');
    expect(req.request.body.rules).toEqual(['ask-dont-guess']);
    expect(req.request.body.mcpServers).toEqual([
      { name: 'docs', url: 'https://mcp.example.com/mcp' },
    ]);
    req.flush({ ...req.request.body, id: 'a3', key: 'coder' });
    http.match((r) => r.url === '/api/v1/agents').forEach((r) => r.flush(agents));
  });

  it('sends no MCP servers for a model answer agent', () => {
    const { page } = create();
    page.create();
    page.patch({ name: 'Answer', mcpServers: [{ name: 'Bad', url: 'x' }] });
    expect(page.mcpError()).toBeNull();
    expect(page.canSave()).toBe(true);
    page.save();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.mcpServers).toEqual([]);
  });

  it('creates an agent without a key, with the chosen kind', () => {
    const { page } = create();
    page.patch({ name: 'Reviewer' });
    page.setKind('CLAUDE_CODE');
    expect(page.kind()).toBe('CLAUDE_CODE');
    expect(page.canSave()).toBe(true);
    page.save();
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
      mcpServers: [],
      tags: [],
    });
    req.flush({ ...req.request.body, id: 'a2', key: 'reviewer' });
    http.match((r) => r.url === '/api/v1/agents').forEach((r) => r.flush(agents));
    page.setKind('MODEL_ANSWER');
    expect(page.draft().harness).toBeNull();
  });
});
