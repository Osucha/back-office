import type { RunInputField, TaskTemplate } from '../../../core/api/types';
import {
  addRunInput,
  moveRunInput,
  patchRunInput,
  removeRunInput,
  runInputProblem,
  withOrigins,
} from './run-inputs-editor';
import { emptyTemplate, toTemplateInput } from './resolved-preview';

const input: RunInputField = {
  name: 'input',
  label: 'Input material',
  type: 'MULTILINE',
  required: false,
};
const issue: RunInputField = {
  name: 'issueKey',
  label: 'Jira issue',
  type: 'ISSUE_KEY',
  required: true,
};

describe('run inputs editor (ADR 0053)', () => {
  it('adds a field with a free name, removes and reorders', () => {
    let list = addRunInput([]);
    expect(list).toEqual([
      { name: 'field-1', label: '', type: 'TEXT', required: false, help: null },
    ]);
    list = addRunInput(list);
    expect(list.map((f) => f.name)).toEqual(['field-1', 'field-2']);
    list = moveRunInput(list, 1, -1);
    expect(list.map((f) => f.name)).toEqual(['field-2', 'field-1']);
    expect(moveRunInput(list, 0, -1)).toBe(list);
    list = removeRunInput(list, 0);
    expect(list.map((f) => f.name)).toEqual(['field-1']);
  });

  it('patches one field and leaves the others', () => {
    const list = patchRunInput([input, issue], 1, { label: 'Ticket', required: false });
    expect(list[0]).toBe(input);
    expect(list[1]).toEqual({ ...issue, label: 'Ticket', required: false });
  });

  it('names the first problem: blank name or label, duplicate names', () => {
    expect(runInputProblem([input, issue])).toBeNull();
    expect(runInputProblem([{ ...input, name: ' ' }])).toEqual({ kind: 'blankName' });
    expect(runInputProblem([{ ...input, label: '' }])).toEqual({
      kind: 'blankLabel',
      name: 'input',
    });
    expect(runInputProblem([input, { ...issue, name: 'input' }])).toEqual({
      kind: 'duplicate',
      name: 'input',
    });
  });

  it('marks where each resolved field comes from: the nearest template of the lineage declaring it', () => {
    const templates = [
      { key: 'base-agent', runInputs: [input] },
      { key: 'writer', runInputs: [issue, { ...input, label: 'Story text' }] },
    ] as TaskTemplate[];
    const resolved = [{ ...input, label: 'Story text' }, issue, { ...input, name: 'ghost' }];
    expect(withOrigins(resolved, ['writer', 'base-agent'], templates).map((f) => f.from)).toEqual([
      'writer',
      'writer',
      null,
    ]);
    expect(withOrigins([input], ['child', 'base-agent'], templates)[0].from).toBe('base-agent');
  });

  it('drafts carry run inputs: empty for a new template, copied when editing', () => {
    expect(emptyTemplate().runInputs).toEqual([]);
    const t = {
      key: 'k',
      name: 'n',
      scope: { type: 'GLOBAL' },
      runInputs: [issue],
    } as TaskTemplate;
    expect(toTemplateInput(t as TaskTemplate & Record<string, unknown>).runInputs).toEqual([issue]);
    const old = { key: 'k', name: 'n', scope: { type: 'GLOBAL' } } as TaskTemplate;
    expect(toTemplateInput(old as TaskTemplate & Record<string, unknown>).runInputs).toEqual([]);
  });
});
