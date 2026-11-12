import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Agent, AgentAudit, WorkflowNode } from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { meFixture } from '../../../core/auth/testing';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { AgentAuditPanelComponent, coherenceError } from './agent-audit-panel.component';

const agents: Agent[] = [
  {
    session: 'PER_STEP',
    id: 'g-oc',
    key: 'opencode',
    name: 'OpenCode',
    scope: { type: 'GLOBAL' },
    harness: 'OPENCODE',
    skills: [],
  },
  {
    session: 'PER_STEP',
    id: 'p-coder',
    key: 'coder',
    name: 'Coder',
    scope: { type: 'PROJECT', projectId: 'p-1' },
    harness: 'CLAUDE_CODE',
    skills: ['gradle'],
  },
];

const entry = (agent: Agent, missing: string[]) => ({
  agent: { key: agent.key ?? '', name: agent.name, harness: agent.harness ?? null },
  agentId: agent.id,
  scope: agent.scope,
  nodeIds: ['impl'],
  rules: [],
  skills: [],
  requiredSkills: missing.map((key) => ({ key, name: key.toUpperCase(), nodeIds: ['impl'] })),
  missingSkills: missing,
  findings: missing.map((key) => ({
    kind: 'MISSING_SKILL' as const,
    severity: 'ERROR' as const,
    message: `lacks ${key}`,
    nodeIds: ['impl'],
    key,
  })),
});

const audit: AgentAudit = {
  agents: [entry(agents[0], ['kotlin']), entry(agents[1], ['docker'])],
  findings: [],
};
const nodes = [
  { id: 'impl', kind: 'TASK', name: 'Implement', position: { x: 0, y: 0 } },
] as WorkflowNode[];

describe('AgentAuditPanelComponent (ADR 0061)', () => {
  let http: HttpTestingController;
  let confirmAnswer = true;

  beforeEach(async () => {
    confirmAnswer = true;
    await TestBed.configureTestingModule({
      imports: [AgentAuditPanelComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ConfirmService, useValue: { confirm: () => Promise.resolve(confirmAnswer) } },
      ],
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
    const fixture = TestBed.createComponent(AgentAuditPanelComponent);
    fixture.componentRef.setInput('projectId', 'p-1');
    fixture.componentRef.setInput('audit', audit);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.componentRef.setInput('agents', agents);
    fixture.detectChanges();
    return { fixture, panel: fixture.componentInstance };
  }

  it('shows the agents with their steps, missing skills and findings', () => {
    const { fixture, panel } = create();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('OpenCode');
    expect(text).toContain('Implement');
    expect(text).toContain('Missing skill');
    expect(panel.canChange()).toBe(true);
    expect(fixture.nativeElement.querySelectorAll('[data-add-skill]').length).toBe(2);

    let selected = '';
    panel.selectNode.subscribe((id) => (selected = id));
    (fixture.nativeElement.querySelector('.node-link') as HTMLButtonElement).click();
    expect(selected).toBe('impl');
  });

  it('copies a global agent into the project with the missing skill, after a confirmation', async () => {
    const { panel } = create();
    let changed = 0;
    panel.changed.subscribe(() => changed++);
    const oc = panel.view().agents.find((a) => a.key === 'opencode')!;

    confirmAnswer = false;
    await panel.addSkill(oc, 'kotlin', 'KOTLIN');
    http.expectNone('/api/v1/agents');

    confirmAnswer = true;
    await panel.addSkill(oc, 'kotlin', 'KOTLIN');
    const req = http.expectOne((r) => r.method === 'POST' && r.url === '/api/v1/agents');
    expect(req.request.body.key).toBe('opencode');
    expect(req.request.body.scope).toEqual({ type: 'PROJECT', projectId: 'p-1' });
    expect(req.request.body.skills).toEqual(['kotlin']);
    req.flush({ ...agents[0], id: 'new' });
    expect(changed).toBe(1);
  });

  it('adds the skill to a project agent with a PUT', async () => {
    const { panel } = create();
    const coder = panel.view().agents.find((a) => a.key === 'coder')!;
    await panel.addSkill(coder, 'docker', 'DOCKER');
    const req = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/agents/p-coder');
    expect(req.request.body.skills).toEqual(['gradle', 'docker']);
    req.flush(agents[1]);
    expect(panel.adding()).toBeNull();
  });

  it('checks coherence on request and shows the findings, model and cost', async () => {
    const { fixture, panel } = create();
    const oc = panel.view().agents.find((a) => a.key === 'opencode')!;
    await panel.checkCoherence(oc);
    const req = http.expectOne((r) => r.url === '/api/v1/agents/g-oc/coherence-check');
    expect(req.request.method).toBe('POST');
    expect(req.request.params.get('projectId')).toBe('p-1');
    req.flush({
      agentKey: 'opencode',
      model: 'Claude Sonnet',
      cost: { amount: 0.0123, currency: 'USD' },
      findings: [
        { severity: 'REDUNDANCY', summary: 'Said twice' },
        {
          severity: 'CONTRADICTION',
          summary: 'Tabs or spaces',
          quotes: [{ source: 'repo:AGENTS.md', text: 'Use tabs' }],
          suggestion: 'Pick one',
        },
      ],
    });
    fixture.detectChanges();
    const state = panel.coherence()['opencode'];
    expect(state.findings.map((f) => f.severity)).toEqual(['CONTRADICTION', 'REDUNDANCY']);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Contradiction');
    expect(text).toContain('repo:AGENTS.md');
    expect(text).toContain('Claude Sonnet');
    expect(text).toContain('$0.0123');
  });

  it('explains a coherence check without a model (409)', async () => {
    const { panel } = create();
    const oc = panel.view().agents.find((a) => a.key === 'opencode')!;
    await panel.checkCoherence(oc);
    http
      .expectOne((r) => r.url === '/api/v1/agents/g-oc/coherence-check')
      .flush({ detail: 'no model' }, { status: 409, statusText: 'Conflict' });
    expect(panel.coherence()['opencode'].error).toContain('No model is set');
    expect(
      coherenceError(
        new HttpErrorResponse({ status: 500, error: { detail: 'boom' }, statusText: 'x' }),
      ),
    ).toBe('The check failed: boom');
    expect(coherenceError(new Error('x'))).toBe('The check failed.');
  });

  it('offers no change in a read-only designer', () => {
    const { fixture, panel } = create();
    fixture.componentRef.setInput('readonly', true);
    fixture.detectChanges();
    expect(panel.canChange()).toBe(false);
    expect(fixture.nativeElement.querySelector('[data-add-skill]')).toBeNull();
  });
});
