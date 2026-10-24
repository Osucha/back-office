import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { TaskTemplate, TaskTemplateInput } from '../../../core/api/types';
import { TemplateEditorDrawerComponent } from './template-editor-drawer.component';

const writer: TaskTemplate = {
  id: 't1',
  key: 'writer',
  name: 'Writer',
  abstract: false,
  extendsKey: 'base-agent',
  executor: 'LLM',
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
});
