import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent, WorkflowNode, WorkflowPalette } from '../../../core/api/types';
import { By } from '@angular/platform-browser';
import { NodePropertyPanelComponent } from './node-property-panel.component';
import { StepAgentSectionComponent } from './step-agent-section.component';
import { StepParameterBindingsComponent } from './step-parameter-bindings.component';

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
  {
    session: 'PER_STEP',
    key: 'model-answer',
    name: 'Model answer',
    harness: null,
    scope: { type: 'GLOBAL' },
  },
  {
    session: 'PER_STEP',
    key: 'claude-code',
    name: 'Claude Code',
    harness: 'CLAUDE_CODE',
    scope: { type: 'GLOBAL' },
    skills: ['kotlin'],
  },
  {
    session: 'PER_STEP',
    key: 'opencode',
    name: 'OpenCode',
    harness: 'OPENCODE',
    scope: { type: 'GLOBAL' },
  },
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
    const { fixture, panel } = create(node('code-review', { instructionsAppend: 'Be strict' }));
    expect(panel.templateDeprecated()).toBe(false);
    panel.stepGetsOpen.set(true);
    fixture.detectChanges();
    await wait(350);

    let req = http.expectOne(
      (r) => r.method === 'POST' && r.url === '/api/v1/task-templates/resolve/code-review',
    );
    expect(req.request.body).toEqual({ instructionsAppend: 'Be strict' });
    expect(req.request.params.get('projectId')).toBe('p1');
    req.flush({ key: 'code-review', instructions: 'Review.\n\nBe strict' });
    expect(panel.stepView()?.instructions).toContain('Be strict');
    fixture.detectChanges();
    expect(document.querySelector('[data-step-gets-instructions]')?.textContent).toContain(
      'Be strict',
    );

    fixture.componentRef.setInput(
      'node',
      node('code-review', { instructionsReplace: 'Only this' }),
    );
    fixture.detectChanges();
    await wait(350);
    req = http.expectOne((r) => r.method === 'POST');
    expect(req.request.body).toEqual({ instructionsReplace: 'Only this' });
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

  it('binds the parameters of a step: value, start or an earlier result (ADR 0058/0062)', () => {
    const { fixture, panel } = create(node('llm-task'));
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));
    const bindings = () =>
      fixture.debugElement.query(By.directive(StepParameterBindingsComponent))
        .componentInstance as StepParameterBindingsComponent;
    const apply = () => {
      fixture.componentRef.setInput('node', changed!);
      fixture.detectChanges();
    };

    expect(panel.parameterFields().map((f) => [f.param.name, f.missing, f.inherited])).toEqual([
      ['task', true, null],
      ['tone', false, 'formal'],
    ]);
    expect(document.querySelector('[data-step-parameters]')?.textContent).toContain('Task');
    // unbound: the default as placeholder
    expect(bindings().placeholder(panel.parameterFields()[1])).toBe('Default: formal');
    expect(bindings().errorOf(panel.parameterFields()[0])).toContain('Required');

    const [task, tone] = panel.parameterFields();
    bindings().setValue(task, 'Summarise the issue');
    expect(changed!.task?.overrides?.parameterBindings).toEqual({
      task: { source: 'VALUE', value: 'Summarise the issue' },
    });
    apply();
    expect(panel.parameterFields()[0].missing).toBe(false);

    bindings().setSource(panel.parameterFields()[0], 'START');
    expect(changed!.task?.overrides?.parameterBindings).toEqual({ task: { source: 'START' } });
    apply();
    expect(bindings().sourceOf('task')).toBe('START');
    expect(document.querySelector('[data-binding-start]')).not.toBeNull();

    bindings().setSource(tone, 'ARTIFACT');
    bindings().setArtifact(tone, ['spec']);
    expect(changed!.task?.overrides?.parameterBindings).toEqual({
      task: { source: 'START' },
      tone: { source: 'ARTIFACT', artifact: 'spec' },
    });
    apply();
    expect(bindings().artifactOf('tone')).toEqual(['spec']);
    // a second pick replaces the first
    bindings().setArtifact(tone, ['spec', 'plan']);
    expect(changed!.task?.overrides?.parameterBindings?.['tone']).toEqual({
      source: 'ARTIFACT',
      artifact: 'plan',
    });

    bindings().setSource(panel.parameterFields()[0], 'VALUE');
    apply();
    bindings().setSource(panel.parameterFields()[1], 'VALUE');
    expect('parameterBindings' in (changed!.task?.overrides ?? {})).toBe(false);
  });

  it('reads legacy step values and offers the artefacts of earlier steps (ADR 0062)', () => {
    const nodes: WorkflowNode[] = [
      { ...node('code-review'), id: 'spec', name: 'Spec' },
      {
        id: 'fetch',
        kind: 'ACTION',
        name: 'Fetch',
        position: { x: 0, y: 0 },
        action: { type: 'COMMENT_ISSUE', config: {} },
      } as WorkflowNode,
      { ...node('llm-task', { parameters: { task: 'Old value' } }), id: 'ask', name: 'Ask' },
      { ...node('implement'), id: 'later', name: 'Later' },
    ];
    const { fixture, panel } = create(nodes[2]);
    fixture.componentRef.setInput('nodes', nodes);
    fixture.componentRef.setInput('edges', [
      { id: 'e1', from: 'spec', fromPort: 'success', to: 'fetch' },
      { id: 'e2', from: 'fetch', fromPort: 'success', to: 'ask' },
      { id: 'e3', from: 'ask', fromPort: 'success', to: 'later' },
    ]);
    fixture.componentRef.setInput(
      'templateProduces',
      new Map([
        ['code-review', ['review']],
        ['implement', ['diff']],
      ]),
    );
    fixture.componentRef.setInput('palette', {
      ...palette,
      actions: [{ ...palette.actions![0], produces: ['comment'] }],
    });
    fixture.detectChanges();

    expect(panel.parameterFields()[0].value).toBe('Old value');
    expect(panel.earlierArtifacts()).toEqual(['review', 'comment']);
  });

  it('edits the instructions of a step: append, replace collapsed, legacy read (ADR 0062)', () => {
    const { fixture, panel } = create(
      node('code-review', { systemPromptAppend: 'Old notes', modelProfileId: 'm1' }),
    );
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.instructions()).toEqual({ append: 'Old notes', replace: '' });
    expect(panel.replaceOpen()).toBe(false);
    expect(document.querySelector('#pp-sys')).toBeNull();
    expect(document.querySelector('[name="promptAppend"]')).toBeNull();
    expect(document.querySelector('#pp-instr-append')).not.toBeNull();
    expect(document.querySelector('[data-step-instructions-replace]')).not.toBeNull();

    panel.setInstructions({ append: 'Focus on security' });
    expect(changed!.task?.overrides).toEqual({
      modelProfileId: 'm1',
      instructionsAppend: 'Focus on security',
    });
    panel.setInstructions({ replace: 'Only this' });
    expect(changed!.task?.overrides).toEqual({
      modelProfileId: 'm1',
      instructionsAppend: 'Old notes',
      instructionsReplace: 'Only this',
    });

    fixture.componentRef.setInput('node', node('code-review', { instructionsReplace: 'X' }));
    fixture.detectChanges();
    expect(panel.replaceOpen()).toBe(true);
  });

  it('keeps only the questions on an agent step: no directory or session (ADR 0062)', () => {
    const { fixture, panel } = create(
      node('implement', {
        agentKey: 'claude-code',
        config: { push: true, directory: 'SEPARATE', session: 'NEW' },
      }),
    );
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));
    const section = fixture.debugElement.query(By.directive(StepAgentSectionComponent))
      .componentInstance as StepAgentSectionComponent;

    expect(panel.runnerStep()).toBe(true);
    expect(document.querySelector('[data-agent-session]')).toBeNull();
    expect(document.querySelector('[data-step-directory]')).toBeNull();
    expect(document.querySelector('[data-step-questions]')).not.toBeNull();
    // the ignored keys (ADR 0063: push too) stay out of the form and are kept as they are
    expect(panel.formConfig()).toEqual({});
    section.setQuestions({ allow: false });
    expect(changed!.task?.overrides?.config).toEqual({
      push: true,
      directory: 'SEPARATE',
      session: 'NEW',
      allowQuestions: false,
    });
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

  it('shows no git, repository, setup or Spec Kit settings on a step (ADR 0063)', () => {
    const withSchema: WorkflowPalette = {
      ...palette,
      tasks: [
        {
          key: 'implement',
          name: 'Implement',
          needsCodingAgent: true,
          configSchema: {
            type: 'object',
            properties: {
              phase: { type: 'string' },
              push: { type: 'boolean' },
              branchTemplate: { type: 'string' },
              mergeRequest: { type: 'boolean' },
              commitSubjectTemplate: { type: 'string' },
              repositories: { type: 'array' },
              setupCommand: { type: 'string' },
              verifyCommand: { type: 'string' },
            },
          },
        },
      ],
    };
    const fixture = TestBed.createComponent(NodePropertyPanelComponent);
    fixture.componentRef.setInput('palette', withSchema);
    fixture.componentRef.setInput('agents', agents);
    fixture.componentRef.setInput(
      'node',
      node('implement', { agentKey: 'claude-code', config: { push: true, verifyCommand: 'make' } }),
    );
    fixture.detectChanges();
    const panel = fixture.componentInstance;
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(Object.keys(panel.configSchema()?.properties ?? {})).toEqual(['verifyCommand']);
    expect(panel.configSchema()?.properties?.['verifyCommand']?.title).toBe('Verify command');
    expect(panel.formConfig()).toEqual({ verifyCommand: 'make' });
    expect(document.querySelector('app-step-repositories-section')).toBeNull();
    // a form change keeps the legacy key for the server's migration
    panel.onSchemaConfig({ verifyCommand: 'make test' });
    expect(changed!.task?.overrides?.config).toEqual({ verifyCommand: 'make test', push: true });
  });

  it('edits "Deliver changes": translated form, repositories of the workflow sources (ADR 0063)', () => {
    const deliver: WorkflowNode = {
      id: 'implement-deliver',
      kind: 'ACTION',
      name: 'Deliver changes',
      position: { x: 0, y: 0 },
      maxVisits: 3,
      action: {
        type: 'DELIVER_CHANGES',
        config: { branchTemplate: 'feature/x', repositories: ['old'] },
      },
    };
    const fixture = TestBed.createComponent(NodePropertyPanelComponent);
    // the palette offers the action without a schema: the form still renders
    fixture.componentRef.setInput('palette', {
      ...palette,
      actions: [
        {
          type: 'DELIVER_CHANGES',
          title: 'Deliver changes',
          description: 'server text',
          ports: ['success', 'failure'],
          configSchema: {},
          defaults: { mergeRequest: true },
        },
      ],
    });
    fixture.componentRef.setInput('projectRepositories', [
      { key: 'app', url: 'u', defaultBranch: 'main', primary: true },
      { key: 'web', url: 'u', defaultBranch: 'main', primary: false },
    ]);
    fixture.componentRef.setInput('sources', [
      { key: 'web', library: false },
      { key: 'docs', library: true },
    ]);
    fixture.componentRef.setInput('node', deliver);
    fixture.detectChanges();
    const panel = fixture.componentInstance;
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.isDelivery()).toBe(true);
    expect(document.querySelector('[data-action-info]')?.textContent).toContain('Deliver changes');
    expect(Object.keys(panel.actionConfigSchema()?.properties ?? {})).toContain('branchTemplate');
    expect(document.querySelector('#pp-act-branchTemplate')).not.toBeNull();
    expect(document.querySelector('[data-deliver-repositories]')).not.toBeNull();
    expect(panel.actionFormConfig()).toEqual({ branchTemplate: 'feature/x' });
    // project repositories of the sources (not the library), plus a key the node names already
    expect(panel.deliverOptions()).toEqual(['web', 'old']);

    panel.onActionSchemaConfig({ branchTemplate: 'fix/y', mergeRequest: false });
    expect(changed!.action?.config).toEqual({
      branchTemplate: 'fix/y',
      mergeRequest: false,
      repositories: ['old'],
    });
    panel.setDeliverRepositories([]);
    expect(changed!.action?.config).toEqual({ branchTemplate: 'feature/x' });
  });

  it('keeps a template the palette no longer has as the current choice', () => {
    const { panel } = create(node('implement-claude-code'));
    const groups = panel.templateGroups();
    expect(groups.at(-1)?.tasks.map((t) => t.key)).toEqual(['implement-claude-code']);
  });
});
