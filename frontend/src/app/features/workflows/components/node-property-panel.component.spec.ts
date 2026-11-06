import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent, WorkflowNode, WorkflowPalette } from '../../../core/api/types';
import { NodePropertyPanelComponent } from './node-property-panel.component';

const answer = { key: 'model-answer', name: 'Model answer', harness: null };
const palette: WorkflowPalette = {
  tasks: [
    { key: 'code-review', name: 'Code review', agent: answer },
    { key: 'analyse-issue', name: 'Analyse issue', agent: answer },
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
  { ...answer, scope: { type: 'GLOBAL' } },
  { key: 'claude-code', name: 'Claude Code', harness: 'CLAUDE_CODE', scope: { type: 'GLOBAL' } },
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
    expect(offered).toEqual(['code-review']);
  });

  it('chooses the agent of a step and derives the step kind from it (ADR 0056)', () => {
    const { fixture, panel } = create(node('code-review'));
    let changed: WorkflowNode | null = null;
    panel.nodeChange.subscribe((n) => (changed = n));

    expect(panel.effectiveAgent()?.key).toBe('model-answer');
    expect(panel.modelUse()).toEqual({ stepKind: 'LLM_CALL', harness: null });
    expect(panel.runnerStep()).toBe(false);
    expect(panel.agentPlaceholder()).toBe('Template default: Model answer');
    expect(panel.agentOptions().map((a) => a.label)).toEqual([
      'Model answer (Model answer)',
      'Claude Code (Claude Code)',
    ]);

    panel.patchOverrides({ agentKey: 'claude-code' });
    expect(changed!.task?.overrides?.agentKey).toBe('claude-code');
    fixture.componentRef.setInput('node', changed!);
    fixture.detectChanges();
    expect(panel.modelUse()).toEqual({ stepKind: 'CODING_AGENT', harness: 'CLAUDE_CODE' });
    expect(panel.runnerStep()).toBe(true);
    expect(panel.canPrepare()).toBe(true);

    panel.patchOverrides({ agentKey: null });
    expect('agentKey' in (changed!.task?.overrides ?? {})).toBe(false);
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

  it('keeps a template the palette no longer has as the current choice', () => {
    const { panel } = create(node('implement-claude-code'));
    const groups = panel.templateGroups();
    expect(groups.at(-1)?.tasks.map((t) => t.key)).toEqual(['implement-claude-code']);
  });
});
