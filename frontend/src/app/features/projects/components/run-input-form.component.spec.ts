import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { RunInputField } from '../../../core/api/types';
import {
  MAX_RUN_INPUT_LENGTH,
  buildStartRequest,
  parseAdvanced,
  runInputErrors,
} from '../data/run-inputs';
import { RunInputFormComponent } from './run-input-form.component';

const issue: RunInputField = {
  name: 'issueKey',
  label: 'Jira issue key',
  type: 'ISSUE_KEY',
  required: true,
  help: 'For example ABC-123.',
};
const input: RunInputField = {
  name: 'input',
  label: 'Input material',
  type: 'MULTILINE',
  required: false,
  help: null,
};
const audience: RunInputField = {
  name: 'audience',
  label: 'Audience',
  type: 'TEXT',
  required: true,
};

describe('run inputs (ADR 0052)', () => {
  it('reports blank required fields and values over the limit', () => {
    expect(runInputErrors([issue, input, audience], { issueKey: '  ', input: '' })).toEqual({
      issueKey: 'required',
      audience: 'required',
    });
    expect(runInputErrors([input], { input: 'x'.repeat(MAX_RUN_INPUT_LENGTH + 1) })).toEqual({
      input: 'tooLong',
    });
    expect(runInputErrors([input], { input: 'x'.repeat(MAX_RUN_INPUT_LENGTH) })).toEqual({});
  });

  it('builds the start request: form values as parameters, the issue key also as issueKey', () => {
    const result = buildStartRequest(
      [issue, input],
      { issueKey: ' shop-7 ', input: 'Add a basket page' },
      '',
    );
    expect(result.request).toEqual({
      issueKey: 'shop-7',
      parameters: { issueKey: 'shop-7', input: 'Add a basket page' },
    });
  });

  it('leaves out empty optional fields and sends no issue key without an ISSUE_KEY field', () => {
    const result = buildStartRequest([input], { input: '   ' }, '');
    expect(result.request).toEqual({ issueKey: null, parameters: {} });
  });

  it('merges extra parameters from the advanced JSON', () => {
    const result = buildStartRequest([input], { input: 'text' }, '{ "depth": 3, "mode": "fast" }');
    expect(result.request?.parameters).toEqual({ depth: 3, mode: 'fast', input: 'text' });
  });

  it('refuses the issue key and declared fields in the advanced JSON', () => {
    expect(parseAdvanced('{"issueKey":"X-1"}', [input]).error).toEqual({ kind: 'issueKey' });
    expect(parseAdvanced('{"ticket":"X-1"}', [{ ...issue, name: 'ticket' }]).error).toEqual({
      kind: 'issueKey',
    });
    expect(parseAdvanced('{"input":"x"}', [input]).error).toEqual({
      kind: 'declared',
      name: 'input',
    });
    expect(parseAdvanced('[1]', []).error).toEqual({ kind: 'notObject' });
    expect(parseAdvanced('{oops', []).error?.kind).toBe('json');
    expect(parseAdvanced('  ', []).params).toEqual({});
  });

  it('returns no request while a field or the advanced JSON is invalid', () => {
    expect(buildStartRequest([issue], { issueKey: '' }, '').request).toBeUndefined();
    expect(buildStartRequest([], {}, '{"issueKey":"A-1"}').request).toBeUndefined();
  });
});

describe('RunInputFormComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RunInputFormComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  function create(fields: RunInputField[]) {
    const fixture = TestBed.createComponent(RunInputFormComponent);
    fixture.componentRef.setInput('workflowId', 'w1');
    fixture.componentRef.setInput('issueKeyPlaceholder', 'SHOP-123');
    fixture.detectChanges();
    http.expectOne('/api/v1/workflows/w1/run-inputs').flush(fields);
    fixture.detectChanges();
    return { fixture, form: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  it('renders a text area for MULTILINE and an input for TEXT and ISSUE_KEY, with marks and help', () => {
    const { el } = create([issue, input, audience]);
    const multiline = el.querySelector('[data-field="input"] textarea') as HTMLTextAreaElement;
    expect(multiline).not.toBeNull();
    expect(multiline.maxLength).toBe(MAX_RUN_INPUT_LENGTH);
    const key = el.querySelector('[data-field="issueKey"] input') as HTMLInputElement;
    expect(key.placeholder).toBe('SHOP-123');
    expect(key.maxLength).toBe(MAX_RUN_INPUT_LENGTH);
    expect(el.querySelector('[data-field="audience"] input')).not.toBeNull();
    expect(el.querySelector('[data-field="issueKey"] .required')).not.toBeNull();
    expect(el.querySelector('[data-field="input"] .required')).toBeNull();
    expect(el.textContent).toContain('For example ABC-123.');
    expect(el.textContent).toContain('Input material');
  });

  it('shows no Jira field for a workflow without an ISSUE_KEY input', () => {
    const { el } = create([input]);
    expect(el.querySelector('[data-field="issueKey"]')).toBeNull();
    expect(el.textContent).not.toContain('Jira');
  });

  it('marks blank required fields instead of building a request', () => {
    const { fixture, form, el } = create([issue, input]);
    expect(form.request()).toBeNull();
    fixture.detectChanges();
    expect(el.querySelector('[data-field="issueKey"] .field-error')?.textContent).toContain(
      'required',
    );
    form.setValue('issueKey', 'SHOP-1');
    expect(form.request()).toEqual({ issueKey: 'SHOP-1', parameters: { issueKey: 'SHOP-1' } });
  });

  it('keeps the advanced JSON collapsed, merges it and rejects the issue key there', () => {
    const { fixture, form, el } = create([input]);
    expect(el.querySelector('#run-params')).toBeNull();
    form.advancedOpen.set(true);
    fixture.detectChanges();
    expect(el.querySelector('#run-params')).not.toBeNull();

    form.advancedJson.set('{"issueKey":"SHOP-9"}');
    expect(form.request()).toBeNull();
    fixture.detectChanges();
    expect(el.querySelector('.advanced-error')?.textContent).toContain('issue key');

    form.advancedJson.set('{"depth":2}');
    form.setValue('input', 'notes');
    expect(form.request()).toEqual({ issueKey: null, parameters: { depth: 2, input: 'notes' } });
  });

  it('still allows a start when the inputs cannot be loaded; the server checks them', () => {
    const fixture = TestBed.createComponent(RunInputFormComponent);
    fixture.componentRef.setInput('workflowId', 'w2');
    fixture.detectChanges();
    http
      .expectOne('/api/v1/workflows/w2/run-inputs')
      .flush('no', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('could not be loaded');
    expect(fixture.componentInstance.request()).toEqual({ issueKey: null, parameters: {} });
  });
});
