import type { TaskOverrides, WorkflowNode } from '../../../core/api/types';
import { parameterFields } from '../../../core/ui/template-parameters';
import {
  bindingOf,
  bindingSource,
  earlierArtifacts,
  hasStepOverrides,
  missingBoundParameters,
  normalizeBinding,
  stepBindings,
  stepInstructions,
  stepValues,
  templateProduces,
  unnamedArtifactBindings,
  withBinding,
  withBindingSource,
  withInstructions,
} from './step-bindings';

const legacy = (o: Record<string, unknown>) => o as TaskOverrides;
const node = (id: string, kind: WorkflowNode['kind'], extra: Partial<WorkflowNode> = {}) =>
  ({ id, kind, name: id, position: { x: 0, y: 0 }, ...extra }) as WorkflowNode;

describe('step instructions (ADR 0062)', () => {
  it('reads the instructions and the legacy prompt keys', () => {
    expect(stepInstructions(null)).toEqual({ append: '', replace: '' });
    expect(stepInstructions({ instructionsAppend: 'notes', instructionsReplace: 'all' })).toEqual({
      append: 'notes',
      replace: 'all',
    });
    expect(
      stepInstructions(
        legacy({ systemPromptAppend: 'sys', promptAppend: 'user', promptReplace: 'r' }),
      ),
    ).toEqual({ append: 'sys\n\nuser', replace: 'r' });
  });

  it('writes the instructions and drops the legacy keys', () => {
    const o = legacy({ agentKey: 'dev', promptAppend: 'old', parameters: { tone: 'formal' } });
    expect(withInstructions(o, { replace: 'new' })).toEqual({
      agentKey: 'dev',
      instructionsAppend: 'old',
      instructionsReplace: 'new',
      parameterBindings: { tone: { source: 'VALUE', value: 'formal' } },
    });
    expect(withInstructions({ instructionsAppend: 'x' }, { append: '' })).toEqual({});
  });
});

describe('parameter bindings (ADR 0062)', () => {
  it('reads legacy values as VALUE bindings; the bindings win', () => {
    const o = legacy({
      parameters: { tone: 'formal', size: '' },
      parameterBindings: {
        issue: { source: 'START' },
        tone: { source: 'ARTIFACT', artifact: 'spec' },
      },
    });
    expect(stepBindings(o)).toEqual({
      tone: { source: 'ARTIFACT', artifact: 'spec' },
      issue: { source: 'START' },
    });
    expect(bindingOf(o, 'issue')).toEqual({ source: 'START' });
    expect(bindingOf(o, 'size')).toBeNull();
    expect(bindingSource(o, 'size')).toBe('VALUE');
    expect(bindingSource(o, 'tone')).toBe('ARTIFACT');
  });

  it('normalizes: a VALUE without a value is unbound, START carries nothing else', () => {
    expect(normalizeBinding({ source: 'VALUE', value: ' ' })).toBeNull();
    expect(normalizeBinding({ source: 'VALUE', value: false })).toEqual({
      source: 'VALUE',
      value: false,
    });
    expect(normalizeBinding({ source: 'START', value: 'x', artifact: 'y' })).toEqual({
      source: 'START',
    });
    expect(normalizeBinding({ source: 'ARTIFACT', artifact: ' spec ' })).toEqual({
      source: 'ARTIFACT',
      artifact: 'spec',
    });
    expect(normalizeBinding(null)).toBeNull();
  });

  it('writes one binding, migrating the legacy values and removing empty bindings', () => {
    const o = legacy({ parameters: { tone: 'formal' }, modelProfileId: 'm' });
    const next = withBinding(o, 'issue', { source: 'START' });
    expect(next).toEqual({
      modelProfileId: 'm',
      parameterBindings: {
        tone: { source: 'VALUE', value: 'formal' },
        issue: { source: 'START' },
      },
    });
    expect(withBinding(next, 'tone', null)).toEqual({
      modelProfileId: 'm',
      parameterBindings: { issue: { source: 'START' } },
    });
    expect(withBinding({ parameterBindings: { a: { source: 'START' } } }, 'a', null)).toEqual({});
  });

  it('switches the source; the same source keeps its value', () => {
    const o: TaskOverrides = { parameterBindings: { tone: { source: 'VALUE', value: 'formal' } } };
    expect(withBindingSource(o, 'tone', 'VALUE')).toEqual(o);
    expect(withBindingSource(o, 'tone', 'START').parameterBindings).toEqual({
      tone: { source: 'START' },
    });
    expect(withBindingSource(o, 'tone', 'ARTIFACT').parameterBindings).toEqual({
      tone: { source: 'ARTIFACT', artifact: null },
    });
    const start: TaskOverrides = { parameterBindings: { tone: { source: 'START' } } };
    expect(withBindingSource(start, 'tone', 'VALUE')).toEqual({});
  });

  it('gives the fixed values and the parameters still missing', () => {
    const o: TaskOverrides = {
      parameterBindings: {
        a: { source: 'VALUE', value: 'x' },
        b: { source: 'START' },
        c: { source: 'ARTIFACT', artifact: null },
      },
    };
    expect(stepValues(o)).toEqual({ a: 'x' });
    const required = (name: string) => ({
      name,
      label: name,
      type: 'TEXT' as const,
      required: true,
    });
    const fields = parameterFields(
      [required('a'), required('b'), required('c'), required('d')],
      {},
      stepValues(o),
    );
    expect(missingBoundParameters(fields, o)).toEqual(['d']);
    expect(unnamedArtifactBindings(o)).toEqual(['c']);
  });

  it('tells whether a step overrides its template', () => {
    expect(hasStepOverrides(null)).toBe(false);
    expect(hasStepOverrides({})).toBe(false);
    expect(hasStepOverrides({ instructionsAppend: 'x' })).toBe(true);
    expect(hasStepOverrides(legacy({ promptReplace: 'x' }))).toBe(true);
    expect(hasStepOverrides({ parameterBindings: { a: { source: 'START' } } })).toBe(true);
    expect(hasStepOverrides({ config: {} })).toBe(false);
  });
});

describe('artefacts of earlier steps (ADR 0062)', () => {
  it('collects what a template produces over its lineage', () => {
    const m = templateProduces([
      { key: 'base', produces: ['analysis'] },
      { key: 'spec', extendsKey: 'base', produces: ['spec'] },
      { key: 'spec', extendsKey: 'base', produces: ['clarifications'] },
      { key: 'loop', extendsKey: 'loop', produces: ['x'] },
    ]);
    expect(m.get('spec')).toEqual(['spec', 'clarifications', 'analysis']);
    expect(m.get('loop')).toEqual(['x']);
  });

  it('offers the artefacts of the nodes before the step', () => {
    const nodes = [
      node('start', 'START'),
      node('spec', 'TASK', { task: { templateKey: 'spec' } }),
      node('fetch', 'ACTION', { action: { type: 'JIRA_FETCH', config: {} } } as never),
      node('impl', 'TASK', { task: { templateKey: 'impl' } }),
      node('review', 'TASK', { task: { templateKey: 'review' } }),
    ];
    const edges = [
      { from: 'start', to: 'fetch' },
      { from: 'fetch', to: 'spec' },
      { from: 'spec', to: 'impl' },
      { from: 'impl', to: 'review' },
      { from: 'review', to: 'impl' },
    ];
    const produces: Record<string, string[]> = {
      spec: ['spec'],
      fetch: ['issue'],
      impl: ['diff'],
      review: ['review'],
    };
    const of = (n: WorkflowNode) => produces[n.id];
    expect(earlierArtifacts(nodes, edges, 'spec', of)).toEqual(['issue']);
    // a rework loop makes the review an earlier step of the implementation too
    expect(earlierArtifacts(nodes, edges, 'impl', of)).toEqual(['spec', 'issue', 'review']);
    // a step without incoming edges sees every other node
    expect(earlierArtifacts(nodes, [], 'spec', of)).toEqual(['issue', 'diff', 'review']);
  });
});
