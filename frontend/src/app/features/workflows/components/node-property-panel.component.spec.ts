import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { WorkflowNode, WorkflowPalette } from '../../../core/api/types';
import { NodePropertyPanelComponent } from './node-property-panel.component';

const palette: WorkflowPalette = {
  tasks: [
    { key: 'code-review', name: 'Code review', executor: 'LLM' },
    { key: 'analyse-issue', name: 'Analyse issue', executor: 'LLM' },
  ],
} as WorkflowPalette;

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

  it('keeps a template the palette no longer has as the current choice', () => {
    const { panel } = create(node('implement-claude-code'));
    const groups = panel.templateGroups();
    expect(groups.at(-1)?.tasks.map((t) => t.key)).toEqual(['implement-claude-code']);
  });
});
