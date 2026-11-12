import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent, WorkflowNode, WorkflowPalette } from '../../../core/api/types';
import { By } from '@angular/platform-browser';
import { NodePropertyPanelComponent } from './node-property-panel.component';
import { StepAgentSectionComponent } from './step-agent-section.component';

const palette: WorkflowPalette = {
  tasks: [
    { key: 'code-review', name: 'Code review', needsCodingAgent: false },
    { key: 'analyse-issue', name: 'Analyse issue', needsCodingAgent: false },
    {
      key: 'llm-task',
      name: 'LLM task',
      needsCodingAgent: false,
      needsParameters: true,
      parameters: [
        { name: 'task', label: 'Task', type: 'MULTILINE', required: true },
        { name: 'tone', label: 'Tone', type: 'TEXT', required: false, default: 'formal' },
      ],
      parameterValues: {},
    },
    { key: 'implement', name: 'Implement', needsCodingAgent: true, requiresSkills: ['kotlin'] },
    { key: 'implement-oc', name: 'Implement (OpenCode)', needsCodingAgent: true },
  ],
  actions: [
    {
      type: 'COMMENT_ISSUE',
      title: 'Comment on issue',
      description: 'Writes a comment',
      ports: ['success', 'failure'],
      configSchema: {
        type: 'object',
        properties: {
          message: { type: 'string' },
          jiraConnection: { type: 'string' },
          approval: { type: 'string' },
        },
      },
      defaults: { message: 'Done' },
      requiredCapabilities: ['ISSUE_COMMENT'],
      externalWrite: true,
    },
  ],
};
const agents: Agent[] = [
  { key: 'model-answer', name: 'Model answer', harness: null, scope: { type: 'GLOBAL' } },
  {
    key: 'claude-code',
    name: 'Claude Code',
    harness: 'CLAUDE_CODE',
    scope: { type: 'GLOBAL' },
    skills: ['kotlin'],
  },
  { key: 'opencode', name: 'OpenCode', harness: 'OPENCODE', scope: { type: 'GLOBAL' } },
];

const node = (templateKey: string, overrides = {}): WorkflowNode =>
  ({
    id: 'review',
    kind: 'TASK',
    name: 'Review',
    position: { x: 0, y: 0 },
    maxVisits: 3,
    task: { templateKey, overrides },
  }) as WorkflowNode;

describe('NodePropertyPanelComponent (ADR 0054)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [NodePropertyPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  function create(n: WorkflowNode) {
    const fixture = TestBed.createComponent(NodePropertyPanelComponent);
    fixture.componentRef.setInput('palette', palette);
    fixture.componentRef.setInput('projectId', 'p1');
    fixture.componentRef.setInput('agents', agents);
    fixture.componentRef.setInput('deprecatedKeys', new Set(['analyse-issue']));
    fixture.componentRef.setInput('node', n);
    fixture.detectChanges();
    return { fixture, panel: fixture.componentInstance };
  }

  const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

  it('resolves what the step gets with its overrides, again when they change', async () => {
    const { fixture, panel } = create(node('code-review', { promptAppend: 'Be strict' }));
    expect(panel.templateDeprecated()).toBe(false);
    panel.stepGetsOpen.set(true);
    fixture.detectChanges();
    await wait(350);

    let req = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/task-templates/resolve/code-review',
    );
    expect(req.request.body).toEqual({ promptAppend: 'Be strict' });
    expect(req.request.params.get('projectId')).toBe('p1');
    req.flush({ key: 'code-review', prompt: { system: 'S', user: 'U\n\nBe strict' } });
    expect(panel.stepView()?.prompt?.user).toContain('Be strict');

    fixture.componentRef.setInput('node', node('code-review', { promptReplace: 'Only this' }));
    fixture.detectChanges();
    await wait(350);
    req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toEqual({ promptReplace: 'Only this' });
    req.flush(null, { status: 404, statusText: 'Not found' });
    expect(panel.stepView()).toBeNull();
    expect(panel.stepError()).toBeTruthy();
  });

  it('does not resolve the step while the section is closed', async () => {
    create(node('code-review'));
    await wait(350);
    http.expectNone((r) => r.method === 'POST');
  });

  it('tags a step that uses a deprecated template and keeps it selectable', () => {
    const { panel } = create(node('analyse-issue'));
    expect(panel.templateDeprecated()).toBe(true);
    const keys = panel.templateGroups().flatMap((g) => g.tasks.map((t) => t.key));
    expect(keys).toContain('analyse-issue');

    const other = create(node('code-review')).panel;
    const offered = other.templateGroups().flatMap((g) => g.tasks.map((t) => t.key));
    expect(offered).toContain('code-review');
    expect(offered).not.toContain('analyse-issue');
  });

  it('chooses the agent of a step and derives the step kind from it (ADR 0056, 0061)', () => {
    const { fixture, panel } = create(node('code-review'));
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.effectiveAgent()?.key).toBe('model-answer');
    expect(panel.modelUse()).toEqual({ stepKind: 'LLM_CALL', harness: null });
    expect(panel.runnerStep()).toBe(false);
    expect(panel.agentPlaceholder()).toBe('Default: Model answer');
    expect(panel.agentOptions().map((a) => a.label)).toEqual([
      'Model answer (Model answer)',
      'Claude Code (Claude Code)',
      'OpenCode (OpenCode)',
    ]);

    panel.patchOverrides({ agentKey: 'claude-code' });
    expect(changed!.task?.overrides?.agentKey).toBe('claude-code');
    fixture.componentRef.setInput('node', changed!);
    fixture.detectChanges();
    expect(panel.modelUse()).toEqual({ stepKind: 'CODING_AGENT', harness: 'CLAUDE_CODE' });
    expect(panel.runnerStep()).toBe(true);

    panel.patchOverrides({ agentKey: null });
    expect('agentKey' in (changed!.task?.overrides ?? {})).toBe(false);
  });

  it('runs a coding template without an agent with OpenCode and warns on a model answer (ADR 0061)', () => {
    const { fixture, panel } = create(node('implement-oc'));
    expect(panel.needsCodingAgent()).toBe(true);
    expect(panel.effectiveAgent()?.key).toBe('opencode');
    expect(panel.agentPlaceholder()).toBe('Default: OpenCode');
    expect(panel.agentMismatch()).toBe(false);
    expect(document.querySelector('[data-needs-coding-agent]')).toBeNull();

    fixture.componentRef.setInput('node', node('implement-oc', { agentKey: 'model-answer' }));
    fixture.detectChanges();
    expect(panel.agentMismatch()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('cannot run with a model answer agent');
  });

  it('pre-selects a fitting agent when a template is chosen (ADR 0061)', () => {
    const { fixture, panel } = create(node('code-review'));
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    // no agent yet: the coding agent with the required skill
    panel.setTemplate('implement');
    expect(changed!.task?.overrides?.agentKey).toBe('claude-code');

    // a model answer agent on a coding template is replaced
    fixture.componentRef.setInput('node', node('code-review', { agentKey: 'model-answer' }));
    fixture.detectChanges();
    panel.setTemplate('implement-oc');
    expect(changed!.task?.overrides?.agentKey).toBe('opencode');

    // an agent of the right kind stays
    fixture.componentRef.setInput('node', node('code-review', { agentKey: 'claude-code' }));
    fixture.detectChanges();
    panel.setTemplate('implement-oc');
    expect(changed!.task?.overrides?.agentKey).toBe('claude-code');
  });

  it('edits an ACTION node from its descriptor, keeping the Jira pin (ADR 0056)', () => {
    const action: WorkflowNode = {
      id: 'comment',
      kind: 'ACTION',
      name: 'Comment',
      position: { x: 0, y: 0 },
      maxVisits: 3,
      action: { type: 'COMMENT_ISSUE', config: { message: 'Hi', jiraConnection: 'c1' } },
    };
    const { panel } = create(action);
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.actionDescriptor()?.type).toBe('COMMENT_ISSUE');
    expect(panel.requiredCapabilities()).toEqual(['ISSUE_COMMENT']);
    expect(panel.externalWrite()).toBe(true);
    expect(Object.keys(panel.actionConfigSchema()?.properties ?? {})).toEqual(['message']);
    expect(panel.actionFormConfig()).toEqual({ message: 'Hi' });
    expect(panel.ports()).toEqual(['success', 'failure']);
    expect(panel.modelUse()).toBeNull();
    expect(document.querySelector('[data-action-info]')?.textContent).toContain('Comment on issue');

    panel.onActionSchemaConfig({ message: 'Bye' });
    expect(changed!.action).toEqual({
      type: 'COMMENT_ISSUE',
      config: { message: 'Bye', jiraConnection: 'c1' },
    });
    expect(changed!.task).toBeUndefined();

    panel.onConfigJson('{"message": "raw"}');
    expect(changed!.action?.config).toEqual({ message: 'raw' });
  });

  it('edits the parameters of a step, showing a required one without a value (ADR 0058)', () => {
    const { fixture, panel } = create(node('llm-task'));
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.parameterFields().map((f) => [f.param.name, f.missing, f.inherited])).toEqual([
      ['task', true, null],
      ['tone', false, 'formal'],
    ]);
    expect(document.querySelector('[data-step-parameters]')?.textContent).toContain('Task');

    panel.setParameter('task', 'Summarise the issue');
    expect(changed!.task?.overrides?.parameters).toEqual({ task: 'Summarise the issue' });
    fixture.componentRef.setInput('node', changed!);
    fixture.detectChanges();
    expect(panel.parameterFields()[0].missing).toBe(false);

    panel.setParameter('task', null);
    expect('parameters' in (changed!.task?.overrides ?? {})).toBe(false);
  });

  it('sets the directory, the session of the same agent and the questions of a coding step (ADR 0060)', () => {
    const nodes: WorkflowNode[] = [
      { ...node('implement', { agentKey: 'claude-code' }), id: 'spec', name: 'Write spec' },
      { ...node('implement-oc'), id: 'oc', name: 'OpenCode step' },
      { ...node('code-review'), id: 'answer', name: 'Answer' },
      {
        ...node('implement', { agentKey: 'claude-code', config: { push: true } }),
        id: 'impl',
        name: 'Implement',
      },
    ];
    const { fixture, panel } = create(nodes[3]);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.detectChanges();
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));
    const section = () =>
      fixture.debugElement.query(By.directive(StepAgentSectionComponent))
        .componentInstance as StepAgentSectionComponent;
    const apply = () => {
      fixture.componentRef.setInput('node', changed!);
      fixture.detectChanges();
    };

    expect(panel.runnerStep()).toBe(true);
    expect(section().directory()).toBe('RUN');
    expect(section().sessionMode()).toBe('OWN');
    expect(panel.sessionCandidates()).toEqual([
      { id: 'spec', name: 'Write spec', agent: 'Claude Code', blocked: null },
      { id: 'oc', name: 'OpenCode step', agent: 'OpenCode', blocked: 'OTHER_AGENT' },
    ]);
    expect(section().optionLabel(panel.sessionCandidates()[1])).toContain('another agent');
    expect(document.querySelector('[data-agent-session]')).not.toBeNull();
    expect(document.querySelector('[data-step-directory]')).not.toBeNull();

    section().setSessionMode('CONTINUE');
    expect(section().sessionMode()).toBe('CONTINUE');
    expect(section().sessionProblem()).toContain('Pick the step');
    section().setContinue('spec');
    expect(changed!.task?.overrides?.config).toEqual({
      push: true,
      session: 'CONTINUE',
      continueSession: 'spec',
    });
    apply();
    expect(section().continued()).toBe('spec');
    expect(section().sessionProblem()).toBeNull();
    expect(panel.formConfig()).toEqual({ push: true });

    section().setContinue('oc');
    apply();
    expect(section().sessionProblem()).toContain('another agent');

    section().setSessionMode('NEW');
    expect(changed!.task?.overrides?.config).toEqual({ push: true, session: 'NEW' });
    apply();

    // a separate directory drops the session settings
    section().setDirectory('SEPARATE');
    expect(changed!.task?.overrides?.config).toEqual({ push: true, directory: 'SEPARATE' });
    apply();
    expect(section().sessionProblem()).toBeNull();

    section().setQuestions({ allow: false });
    expect(changed!.task?.overrides?.config).toEqual({
      push: true,
      directory: 'SEPARATE',
      allowQuestions: false,
    });
    apply();
    expect(panel.formConfig()).toEqual({ push: true });
  });

  it('offers only the questions to a model answer step (ADR 0060)', () => {
    const { fixture, panel } = create(node('code-review'));
    expect(panel.runnerStep()).toBe(false);
    expect(document.querySelector('[data-step-questions]')).not.toBeNull();
    expect(document.querySelector('[data-step-directory]')).toBeNull();
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));
    const section = fixture.debugElement.query(By.directive(StepAgentSectionComponent))
      .componentInstance as StepAgentSectionComponent;
    section.setRounds(3);
    expect(changed!.task?.overrides?.config).toEqual({ maxQuestionRounds: 3 });
  });

  it('keeps a template the palette no longer has as the current choice', () => {
    const { panel } = create(node('implement-claude-code'));
    const groups = panel.templateGroups();
    expect(groups.at(-1)?.tasks.map((t) => t.key)).toEqual(['implement-claude-code']);
  });
});
