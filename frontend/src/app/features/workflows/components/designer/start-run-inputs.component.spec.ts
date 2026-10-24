import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { RunInputField } from '../../../../core/api/types';
import { StartRunInputsComponent } from './start-run-inputs.component';

const fields: RunInputField[] = [
  { name: 'issueKey', label: 'Jira issue key', type: 'ISSUE_KEY', required: true },
  { name: 'input', label: 'Input material', type: 'MULTILINE', required: false, help: 'Notes' },
];

describe('StartRunInputsComponent (ADR 0053)', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [StartRunInputsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function create(workflowId: string | null) {
    const fixture = TestBed.createComponent(StartRunInputsComponent);
    fixture.componentRef.setInput('workflowId', workflowId);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement };
  }

  it('lists the effective run inputs read-only, without any edit control', () => {
    const { fixture, el } = create('w1');
    http.expectOne('/api/v1/workflows/w1/run-inputs').flush(fields);
    fixture.detectChanges();

    const items = el.querySelectorAll('li[data-run-input]');
    expect(items.length).toBe(2);
    expect(items[0].textContent).toContain('Jira issue key');
    expect(items[0].textContent).toContain('required');
    expect(items[1].textContent).toContain('Input material');
    expect(el.querySelectorAll('input, textarea, select, button, nz-select').length).toBe(0);
  });

  it('says so when the workflow asks for nothing, and explains unsaved workflows', () => {
    const { fixture, el } = create('w2');
    http.expectOne('/api/v1/workflows/w2/run-inputs').flush([]);
    fixture.detectChanges();
    expect(el.textContent).toContain('asks for no input');

    const unsaved = create(null);
    http.expectNone(() => true);
    expect(unsaved.el.textContent).toContain('Save the workflow');
  });
});
