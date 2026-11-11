import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { meFixture } from '../../../core/auth/testing';
import { ProjectAgentsPanelComponent } from './project-agents-panel.component';

const agents: Agent[] = [
  {
    id: 'g1',
    key: 'coder',
    name: 'Coder',
    scope: { type: 'GLOBAL' },
    harness: 'CLAUDE_CODE',
    builtIn: true,
    rules: ['ask'],
    skills: ['kotlin', 'spec'],
  },
  { id: 'g2', key: 'reviewer', name: 'Reviewer', scope: { type: 'GLOBAL' }, harness: null },
  {
    id: 'p1a',
    key: 'coder',
    name: 'Coder (SHOP)',
    scope: { type: 'PROJECT', projectId: 'p-1' },
    harness: 'CLAUDE_CODE',
    access: { mode: 'READ_ONLY' },
  },
];

describe('ProjectAgentsPanelComponent (ADR 0060)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ProjectAgentsPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    const me = meFixture();
    me.projects![0].permissions = [
      ...(me.projects![0].permissions ?? []),
      'library.project.manage',
    ];
    TestBed.inject(IdentityService).setMe(me);
  });

  function create() {
    const fixture = TestBed.createComponent(ProjectAgentsPanelComponent);
    fixture.componentRef.setInput('projectId', 'p-1');
    fixture.detectChanges();
    http.match((r) => r.url.includes('/model-profiles')).forEach((r) => r.flush([]));
    const list = http.expectOne((r) => r.url === '/api/v1/agents');
    expect(list.request.params.get('projectId')).toBe('p-1');
    list.flush(agents);
    http
      .match((r) => r.url === '/api/v1/rules' || r.url === '/api/v1/skills')
      .forEach((r) => r.flush([]));
    fixture.detectChanges();
    return { fixture, panel: fixture.componentInstance };
  }

  it('lists the project agents first, with scope, access and environment', () => {
    const { fixture, panel } = create();
    expect(panel.canManage()).toBe(true);
    expect(panel.rows().map((r) => r.agent.name)).toEqual(['Coder (SHOP)', 'Coder', 'Reviewer']);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('replaces the global agent');
    expect(text).toContain('replaced in this project');
    const access = Array.from(
      fixture.nativeElement.querySelectorAll('td[data-access]') as NodeListOf<HTMLElement>,
    ).map((e) => e.textContent?.trim());
    expect(access).toEqual(['Read only', 'Full', '—']);
    expect(panel.environmentLabel(agents[0])).toBe('1 rule · 2 skills');
  });

  it('imports a global agent as a project copy with the same key and opens it', () => {
    const { panel } = create();
    expect(panel.importable().map((a) => a.key)).toEqual(['reviewer']);
    panel.importKey.set('reviewer');
    panel.importAgent();
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.key).toBe('reviewer');
    expect(req.request.body.scope).toEqual({ type: 'PROJECT', projectId: 'p-1' });
    req.flush({ ...req.request.body, id: 'p1b' });
    expect(panel.editingId()).toBe('p1b');
    expect(panel.editorOpen()).toBe(true);
    expect(panel.editingShadows()).toBe(true);
  });

  it('creates a new agent of the project', () => {
    const { panel } = create();
    panel.create();
    expect(panel.editorOpen()).toBe(true);
    expect(panel.editing()).toBeNull();
  });
});
