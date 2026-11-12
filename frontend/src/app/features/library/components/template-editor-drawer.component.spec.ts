import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { TaskTemplate, TaskTemplateInput } from '../../../core/api/types';
import { customizeForProject } from '../data/resolved-preview';
import { TemplateEditorDrawerComponent } from './template-editor-drawer.component';

const writer: TaskTemplate = {
  id: 't1',
  key: 'writer',
  name: 'Writer',
  abstract: false,
  extendsKey: 'base-agent',
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

  it('renders the configuration with the keys of both kinds of steps (ADR 0061)', async () => {
    const { fixture, drawer } = create(null);
    http.expectOne('/api/v1/workflows/task-config-schemas').flush({
      modelAnswer: { type: 'object', properties: { temperature: { type: 'number' } } },
      codingAgent: {
        type: 'object',
        required: ['phase'],
        properties: { phase: { type: 'string' }, push: { type: 'boolean' } },
      },
    });
    drawer.patch({ name: 'My task' });
    fixture.detectChanges();

    expect(drawer.configSchema()?.properties?.['phase']).toBeTruthy();
    expect(drawer.configSchema()?.properties?.['temperature']).toBeTruthy();
    expect(drawer.configSchema()?.required).toBeUndefined();
    expect(document.querySelector('[data-config-form]')).not.toBeNull();

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

  it('shows read-only whether the template needs a coding agent and sends no agent (ADR 0061)', () => {
    const { fixture, drawer } = create(null);
    fixture.componentRef.setInput('templates', [
      baseAgent,
      { ...writer, key: 'coder', extendsKey: 'base-agent', config: { push: true } },
    ]);
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));
    drawer.patch({ name: 'Brief' });
    fixture.detectChanges();
    expect(drawer.needsCodingAgent()).toBe(false);
    expect(document.querySelector('[data-needs-coding-agent]')).toBeNull();

    // inherited from the parent's config
    drawer.patch({ extendsKey: 'coder' });
    fixture.detectChanges();
    expect(drawer.needsCodingAgent()).toBe(true);
    expect(document.querySelector('[data-needs-coding-agent]')?.textContent).toContain(
      'Needs a coding agent',
    );
    // the own config deletes the key
    drawer.patch({ config: { push: null } });
    expect(drawer.needsCodingAgent()).toBe(false);
    drawer.patch({ extendsKey: null, config: { phase: 'specify' } });
    expect(drawer.needsCodingAgent()).toBe(true);

    drawer.submit();
    expect('agentKey' in payload!).toBe(false);
    expect('modelProfileId' in payload!).toBe(false);
    expect(document.querySelector('#tt-agent')).toBeNull();
    expect(document.querySelector('#tt-model')).toBeNull();
  });

  it('takes the saved value while the configuration is unchanged (ADR 0061)', () => {
    const { drawer } = create({ ...writer, needsCodingAgent: true });
    expect(drawer.needsCodingAgent()).toBe(true);
    drawer.patch({ config: { temperature: 0.1 } });
    expect(drawer.needsCodingAgent()).toBe(false);
  });

  it('never sends a key on update and none on a plain create (ADR 0055)', () => {
    const created = create(null).drawer;
    let payload: TaskTemplateInput | null = null;
    created.saved.subscribe((p) => (payload = p));
    created.patch({ name: 'Brief', category: 'ANALYSIS' });
    expect(created.canSave()).toBe(true);
    created.submit();
    expect('key' in payload!).toBe(false);
    expect(payload!.category).toBe('ANALYSIS');
    expect('rules' in payload!).toBe(false);
    expect('skills' in payload!).toBe(false);
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

  it('names the inherited category and keeps descriptions behind a "?" (ADR 0061)', async () => {
    const { fixture, drawer } = create(writer);
    await new Promise((r) => setTimeout(r, 200));
    http
      .expectOne((r) => r.url === '/api/v1/task-templates/resolve/writer')
      .flush({ key: 'writer', lineage: ['base-agent', 'writer'], category: 'REVIEW' });
    fixture.detectChanges();

    expect(drawer.categoryPlaceholder()).toBe('inherited: Review');
    expect(drawer.templateName('base-agent')).toBe('Base agent');
    expect(document.querySelector('[data-agent-environment]')).toBeNull();
    expect(document.querySelector('#tt-skills')).toBeNull();
    expect(document.querySelector('#tt-rules')).toBeNull();
    // the field descriptions are tooltips, not text under the fields
    expect(document.querySelector('.ant-form-item-extra')).toBeNull();
    expect(document.querySelectorAll('bo-help-tip').length).toBeGreaterThan(0);
  });

  it('declares parameters, blocks saving on a wrong one and sends them cleaned (ADR 0058)', () => {
    const { drawer } = create(null);
    let payload: TaskTemplateInput | null = null;
    drawer.saved.subscribe((p) => (payload = p));
    drawer.patch({ name: 'Ask' });

    drawer.addParameter();
    expect(drawer.canSave()).toBe(false);
    expect(drawer.saveBlocker()).toContain('needs a name');
    drawer.patchParameter(0, { name: 'task', label: 'Task', type: 'MULTILINE', required: true });
    drawer.addParameter();
    drawer.patchParameter(1, { name: 'tone', label: 'Tone', type: 'CHOICE' });
    expect(drawer.saveBlocker()).toContain('needs options');
    drawer.patchParameter(1, { options: ['formal', 'casual'], default: 'formal' });
    drawer.moveParameter(1, -1);
    expect(drawer.canSave()).toBe(true);
    drawer.submit();
    expect(payload!.parameters).toEqual([
      {
        name: 'tone',
        label: 'Tone',
        type: 'CHOICE',
        required: false,
        default: 'formal',
        help: null,
        options: ['formal', 'casual'],
      },
      { name: 'task', label: 'Task', type: 'MULTILINE', required: true, default: null, help: null },
    ]);
    drawer.removeParameter(0);
    expect(drawer.draft().parameters?.map((p) => p.name)).toEqual(['task']);
  });

  it('fills the values of the parent parameters for a preset and offers presets of saved templates', async () => {
    const fixture = TestBed.createComponent(TemplateEditorDrawerComponent);
    fixture.componentRef.setInput('templates', [baseAgent, writer]);
    fixture.componentRef.setInput('preset', {
      ...customizeForProject(writer, 'p1'),
      key: undefined,
      name: '',
      extendsKey: 'writer',
    });
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    const drawer = fixture.componentInstance;
    await new Promise((r) => setTimeout(r, 200));
    http
      .match((r) => r.url === '/api/v1/task-templates/resolve/writer')
      .forEach((r) =>
        r.flush({
          key: 'writer',
          parameters: [
            {
              name: 'task',
              label: 'Task',
              type: 'TEXT',
              required: true,
              value: null,
              source: 'NONE',
            },
            {
              name: 'tone',
              label: 'Tone',
              type: 'TEXT',
              required: false,
              default: 'formal',
              value: 'formal',
              source: 'DEFAULT',
            },
          ],
        }),
      );
    fixture.detectChanges();

    expect(drawer.canPreset()).toBe(false);
    const fields = drawer.inheritedParameterFields();
    expect(fields.map((f) => [f.param.name, f.missing])).toEqual([
      ['task', true],
      ['tone', false],
    ]);
    drawer.setParameterValue('task', 'Summarise');
    expect(drawer.draft().parameterValues).toEqual({ task: 'Summarise' });
    expect(drawer.inheritedParameterFields()[0].missing).toBe(false);
    drawer.setParameterValue('task', '');
    expect(drawer.draft().parameterValues).toEqual({});
    // an own declaration of the same name hides the inherited one
    drawer.addParameter();
    drawer.patchParameter(0, { name: 'tone', label: 'Tone' });
    expect(drawer.inheritedParameterFields().map((f) => f.param.name)).toEqual(['task']);

    const saved = create(writer).drawer;
    let preset: TaskTemplateInput | null = null;
    saved.createPreset.subscribe((p) => (preset = p));
    expect(saved.canPreset()).toBe(true);
    saved.requestPreset();
    expect(preset!.extendsKey).toBe('writer');
    expect(preset!.name).toBe('');
    expect(preset!.scope).toEqual({ type: 'GLOBAL', projectId: null });
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
