import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Agent, TaskTemplate, TaskTemplateInput } from '../../../core/api/types';
import { customizeForProject } from '../data/resolved-preview';
import { TemplateEditorDrawerComponent } from './template-editor-drawer.component';

const writer: TaskTemplate = {
  id: 't1',
  key: 'writer',
  name: 'Writer',
  abstract: false,
  extendsKey: 'base-agent',
  agentKey: 'model-answer',
  scope: { type: 'GLOBAL' },
  builtIn: false,
  version: 1,
  runInputs: [{ name: 'audience', label: 'Audience', type: 'TEXT', required: true }],
};
const baseAgent: TaskTemplate = {
  id: 't0',
  key: 'base-agent',
  name: 'Base agent',
  abstract: true,
  scope: { type: 'GLOBAL' },
  builtIn: true,
  version: 1,
  runInputs: [{ name: 'input', label: 'Input material', type: 'MULTILINE', required: false }],
};

describe('TemplateEditorDrawerComponent run inputs (ADR 0053)', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    // the drawer has a @defer block: its metadata resolves asynchronously
    await TestBed.configureTestingModule({
      imports: [TemplateEditorDrawerComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  function create(template: TaskTemplate | null) {
    const fixture = TestBed.createComponent(TemplateEditorDrawerComponent);
    fixture.componentRef.setInput('templates', [baseAgent, writer]);
    fixture.componentRef.setInput('template', template);
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    return { fixture, drawer: fixture.componentInstance };
  }

  it('adds and removes run inputs and sends them with the save payload', () => {
    const { drawer } = create(null);
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));
    drawer.patch({ key: 'brief', name: 'Brief' });

    drawer.addRunInput();
    drawer.addRunInput();
    expect(drawer.draft().runInputs?.length).toBe(2);
    drawer.patchRunInput(0, { name: 'topic', label: 'Topic', type: 'MULTILINE', required: true });
    drawer.removeRunInput(1);
    drawer.submit();

    expect(payload!.runInputs).toEqual([
      { name: 'topic', label: 'Topic', type: 'MULTILINE', required: true, help: null },
    ]);
  });

  it('blocks saving while a run input has no label or a duplicate name', () => {
    const { drawer } = create(null);
    drawer.patch({ key: 'brief', name: 'Brief' });
    drawer.addRunInput();
    expect(drawer.canSave()).toBe(false);
    expect(drawer.saveBlocker()).toContain('label');
    drawer.patchRunInput(0, { label: 'Topic' });
    expect(drawer.canSave()).toBe(true);
    drawer.addRunInput();
    drawer.patchRunInput(1, { name: 'field-1', label: 'Again' });
    expect(drawer.canSave()).toBe(false);
    expect(drawer.saveBlocker()).toContain('field-1');
  });

  it('lists the inherited fields of the resolved view with the template they come from', async () => {
    const { fixture, drawer } = create(writer);
    await new Promise((r) => setTimeout(r, 200));
    http
      .expectOne((r) => r.url === '/api/v1/task-templates/resolve/writer')
      .flush({
        key: 'writer',
        lineage: ['writer', 'base-agent'],
        runInputs: [
          { name: 'input', label: 'Input material', type: 'MULTILINE', required: false },
          { name: 'audience', label: 'Audience', type: 'TEXT', required: true },
        ],
      });
    fixture.detectChanges();

    expect(drawer.previewRunInputs().map((f) => [f.name, f.from])).toEqual([
      ['input', 'base-agent'],
      ['audience', 'writer'],
    ]);
  });

  const agents: Agent[] = [
    { key: 'model-answer', name: 'Model answer', scope: { type: 'GLOBAL' }, harness: null },
    { key: 'opencode', name: 'OpenCode', scope: { type: 'GLOBAL' }, harness: 'OPENCODE' },
    {
      key: 'p2-coder',
      name: 'P2 coder',
      scope: { type: 'PROJECT', projectId: 'p2' },
      harness: 'CLAUDE_CODE',
    },
  ];

  it('renders the configuration from the schema of the inherited agent kind (ADR 0056)', async () => {
    const { fixture, drawer } = create(null);
    fixture.componentRef.setInput('agents', agents);
    http.expectOne('/api/v1/workflows/task-config-schemas').flush({
      modelAnswer: { type: 'object', properties: { temperature: { type: 'number' } } },
      codingAgent: {
        type: 'object',
        required: ['phase'],
        properties: { phase: { type: 'string' }, push: { type: 'boolean' } },
      },
    });
    fixture.componentRef.setInput('templates', [
      baseAgent,
      { ...writer, key: 'coder', agentKey: 'opencode', extendsKey: 'base-agent' },
    ]);
    drawer.patch({ key: 'my-coder', name: 'My coder', agentKey: null, extendsKey: 'coder' });
    fixture.detectChanges();

    expect(drawer.effectiveAgent()?.key).toBe('opencode');
    expect(drawer.configSchema()?.properties?.['phase']).toBeTruthy();
    expect(drawer.configSchema()?.required).toBeUndefined();
    expect(drawer.agentPlaceholder()).toBe('Inherited: OpenCode');
    expect(drawer.agentHelp()).toContain('a workflow step can choose another agent');
    expect(drawer.agentHelp()).toContain('sandbox');
    expect(document.querySelector('[data-config-form]')).not.toBeNull();

    // a model answer gets the model answer schema
    drawer.patch({ agentKey: 'model-answer' });
    expect(drawer.configSchema()?.properties?.['temperature']).toBeTruthy();
    drawer.patch({ agentKey: null });

    drawer.onConfigForm({ phase: 'plan', extra: 1 });
    expect(drawer.draft().config).toEqual({ phase: 'plan', extra: 1 });
    expect(JSON.parse(drawer.configText())).toEqual({ phase: 'plan', extra: 1 });

    drawer.setConfigRaw(true);
    fixture.detectChanges();
    expect(document.querySelector('[data-config-form]')).toBeNull();
    drawer.onConfig('{ broken');
    expect(drawer.configError()).toBeTruthy();
    drawer.setConfigRaw(false);
    expect(drawer.configRaw()).toBe(true);
  });

  it('offers the agents of the template scope and sends the chosen agent key (ADR 0056)', () => {
    const { fixture, drawer } = create(null);
    fixture.componentRef.setInput('agents', agents);
    fixture.detectChanges();
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));

    expect(drawer.draft().agentKey).toBeNull();
    expect(drawer.agentOptions().map((a) => a.key)).toEqual(['model-answer', 'opencode']);
    drawer.setScope('PROJECT');
    drawer.patch({ scope: { type: 'PROJECT', projectId: 'p2' } });
    expect(drawer.agentOptions().map((a) => a.key)).toContain('p2-coder');
    drawer.patch({ name: 'Brief', agentKey: 'p2-coder' });
    expect(drawer.effectiveAgent()?.harness).toBe('CLAUDE_CODE');
    expect(drawer.missingAgentKey()).toBeNull();
    drawer.submit();
    expect(payload!.agentKey).toBe('p2-coder');
    expect('executor' in payload!).toBe(false);
  });

  it('never sends a key on update and none on a plain create (ADR 0055)', () => {
    const created = create(null).drawer;
    let payload: TaskTemplateInput | null = null;
    created.saved.subscribe((p) => (payload = p));
    created.patch({ name: 'Brief', category: 'ANALYSIS', rules: ['no-secrets'] });
    expect(created.canSave()).toBe(true);
    created.submit();
    expect('key' in payload!).toBe(false);
    expect(payload!.category).toBe('ANALYSIS');
    expect(payload!.rules).toEqual(['no-secrets']);
    expect(document.querySelector('#tt-key')).toBeNull();
  });

  it('keeps the key of an existing template out of the update payload', () => {
    const { drawer } = create(writer);
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));
    drawer.patch({ name: 'Writer 2' });
    drawer.submit();
    expect('key' in payload!).toBe(false);
    expect(payload!.name).toBe('Writer 2');
  });

  it('customises a global template for a project with its key (ADR 0055)', () => {
    const fixture = TestBed.createComponent(TemplateEditorDrawerComponent);
    fixture.componentRef.setInput('templates', [baseAgent, writer]);
    fixture.componentRef.setInput('preset', customizeForProject(writer, 'p1'));
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    const drawer = fixture.componentInstance;
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));

    expect(drawer.customizing()).toBe(true);
    expect(drawer.parents().some((t) => t.key === 'writer')).toBe(true);
    drawer.submit();
    expect(payload!.key).toBe('writer');
    expect(payload!.extendsKey).toBe('writer');
    expect(payload!.scope).toEqual({ type: 'PROJECT', projectId: 'p1' });
  });

  it('shows the automatic rules of the resolved view and names skills and rules', async () => {
    const { fixture, drawer } = create(writer);
    fixture.componentRef.setInput('skills', [{ key: 'kotlin', name: 'Kotlin', content: '' }]);
    fixture.componentRef.setInput('rules', [{ key: 'polish', automatic: false, content: '' }]);
    await new Promise((r) => setTimeout(r, 200));
    http
      .expectOne((r) => r.url === '/api/v1/task-templates/resolve/writer')
      .flush({
        key: 'writer',
        lineage: ['base-agent', 'writer'],
        category: 'REVIEW',
        rules: [{ key: 'no-secrets', name: 'No secrets', automatic: true }],
      });
    fixture.detectChanges();

    expect(drawer.automatic()).toEqual([{ key: 'no-secrets', name: 'No secrets' }]);
    expect(drawer.skillOptions()).toEqual([{ key: 'kotlin', label: 'Kotlin' }]);
    expect(drawer.ruleOptions()).toEqual([{ key: 'polish', label: 'polish' }]);
    expect(drawer.categoryPlaceholder()).toBe('inherited: Review');
    expect(drawer.templateName('base-agent')).toBe('Base agent');
    expect(document.querySelector('[data-automatic-rules]')?.textContent).toContain('No secrets');
    drawer.patch({ rules: ['polish'], removeRules: ['no-secrets'] });
    expect(drawer.delta()).toEqual({ added: ['polish'], removed: ['No secrets'] });
  });

  it('keeps the run input controls disabled in a read-only drawer', async () => {
    const { fixture } = create(writer);
    fixture.componentRef.setInput('readonly', true);
    fixture.detectChanges();
    await new Promise((r) => setTimeout(r, 50));
    fixture.detectChanges();
    await fixture.whenStable();

    const row = document.querySelector('[data-run-input="0"]') as HTMLElement;
    expect(row).not.toBeNull();
    const inputs = Array.from(row.querySelectorAll('input')) as HTMLInputElement[];
    expect(inputs.length).toBeGreaterThan(0);
    expect(inputs.every((i) => i.disabled)).toBe(true);
    expect(document.querySelector('.ri-add')).toBeNull();
  });
});
