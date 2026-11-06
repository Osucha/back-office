import type { TemplateParameter } from '../api/types';
import {
  PARAMETER_TYPES,
  addParameter,
  cleanParameters,
  fromResolved,
  isEmptyValue,
  lineageParameters,
  missingParameters,
  moveParameter,
  parameterFields,
  parameterProblem,
  parameterTypeLabel,
  parameterValueText,
  patchParameter,
  removeParameter,
  templateNeedsParameters,
  valueFitsType,
  withParameterValue,
} from './template-parameters';

const text = (name: string, changes: Partial<TemplateParameter> = {}): TemplateParameter => ({
  name,
  label: name.toUpperCase(),
  type: 'TEXT',
  required: false,
  default: null,
  help: null,
  ...changes,
});

describe('template parameters (ADR 0058)', () => {
  it('labels every type', () => {
    expect(PARAMETER_TYPES.map(parameterTypeLabel)).toEqual([
      'Single line',
      'Multi-line text',
      'Number',
      'Yes / no',
      'Choice',
    ]);
  });

  it('checks values against the type', () => {
    expect(valueFitsType({ type: 'TEXT' }, 'x')).toBe(true);
    expect(valueFitsType({ type: 'NUMBER' }, 3)).toBe(true);
    expect(valueFitsType({ type: 'NUMBER' }, '3')).toBe(false);
    expect(valueFitsType({ type: 'NUMBER' }, Number.NaN)).toBe(false);
    expect(valueFitsType({ type: 'BOOLEAN' }, false)).toBe(true);
    expect(valueFitsType({ type: 'CHOICE', options: ['a'] }, 'a')).toBe(true);
    expect(valueFitsType({ type: 'CHOICE', options: ['a'] }, 'b')).toBe(false);
    expect(isEmptyValue('  ')).toBe(true);
    expect(isEmptyValue(0)).toBe(false);
    expect(isEmptyValue(false)).toBe(false);
  });

  it('shows values as text', () => {
    expect(parameterValueText(true)).toBe('Yes');
    expect(parameterValueText(false)).toBe('No');
    expect(parameterValueText(7)).toBe('7');
    expect(parameterValueText('abc')).toBe('abc');
    expect(parameterValueText(null)).toBe('');
  });

  it('adds, moves and removes declarations', () => {
    const list = addParameter(addParameter(undefined));
    expect(list).toHaveLength(2);
    expect(list[0]).toEqual({
      name: '',
      label: '',
      type: 'TEXT',
      required: false,
      default: null,
      help: null,
    });
    const named = [text('a'), text('b')];
    expect(moveParameter(named, 1, -1).map((p) => p.name)).toEqual(['b', 'a']);
    expect(moveParameter(named, 0, -1).map((p) => p.name)).toEqual(['a', 'b']);
    expect(removeParameter(named, 0).map((p) => p.name)).toEqual(['b']);
  });

  it('drops a default that no longer fits a new type and keeps options only for CHOICE', () => {
    const list = [text('a', { default: 'hello' })];
    const asNumber = patchParameter(list, 0, { type: 'NUMBER' });
    expect(asNumber[0].default).toBeNull();
    const choice = patchParameter(list, 0, { type: 'CHOICE', options: ['x', 'y'] });
    expect(choice[0].options).toEqual(['x', 'y']);
    expect(choice[0].default).toBeNull();
    const kept = patchParameter(choice, 0, { default: 'y' });
    expect(kept[0].default).toBe('y');
    const back = patchParameter(kept, 0, { type: 'TEXT' });
    expect(back[0].options).toBeUndefined();
    expect(back[0].default).toBe('y');
  });

  it('finds the first problem of the declarations', () => {
    expect(parameterProblem([text('a')])).toBeNull();
    expect(parameterProblem([text('')])).toEqual({ kind: 'blankName' });
    expect(parameterProblem([text('1a')])).toEqual({ kind: 'badName', name: '1a' });
    expect(parameterProblem([text('a-b')])?.kind).toBe('badName');
    expect(parameterProblem([text('a'), text('a')])).toEqual({ kind: 'duplicate', name: 'a' });
    expect(parameterProblem([text('a', { label: ' ' })])).toEqual({
      kind: 'blankLabel',
      name: 'a',
    });
    expect(parameterProblem([text('a', { help: 'x'.repeat(1001) })])?.kind).toBe('tooLong');
    expect(parameterProblem([text('a', { type: 'CHOICE', options: [] })])?.kind).toBe('noOptions');
    expect(parameterProblem([text('a', { type: 'NUMBER', default: 'x' })])?.kind).toBe(
      'badDefault',
    );
  });

  it('cleans declarations for saving', () => {
    expect(
      cleanParameters([
        { ...text(' a '), label: ' A ', help: '  ', default: ' ' },
        { ...text('c'), type: 'CHOICE', options: [' x ', ''] },
      ]),
    ).toEqual([
      { name: 'a', label: 'A', type: 'TEXT', required: false, default: null, help: null },
      {
        name: 'c',
        label: 'C',
        type: 'CHOICE',
        required: false,
        default: null,
        help: null,
        options: ['x'],
      },
    ]);
  });

  it('builds value fields: own value, inherited value and its source, missing', () => {
    const declared = [
      text('task', { required: true }),
      text('tone', { default: 'formal' }),
      text('lang', { required: true }),
      { ...text('n'), type: 'NUMBER' as const },
    ];
    const fields = parameterFields(declared, { lang: 'pl' }, { tone: 'casual', n: 'x' });
    expect(fields.map((f) => [f.param.name, f.value, f.inherited, f.inheritedSource])).toEqual([
      ['task', null, null, 'NONE'],
      ['tone', 'casual', 'formal', 'DEFAULT'],
      ['lang', null, 'pl', 'TEMPLATE'],
      ['n', 'x', null, 'NONE'],
    ]);
    expect(missingParameters(fields)).toEqual(['task']);
    expect(fields[3].invalid).toBe(true);
    expect(missingParameters(parameterFields(declared, null, { task: 'Do it' }))).toEqual(['lang']);
  });

  it('reads declarations and template values from a resolved task', () => {
    const r = fromResolved([
      { ...text('a'), value: 'from template', source: 'TEMPLATE' },
      { ...text('b', { default: 'd' }), value: 'd', source: 'DEFAULT' },
      { ...text('c'), value: 'from step', source: 'STEP' },
    ]);
    expect(r.declared.map((p) => p.name)).toEqual(['a', 'b', 'c']);
    expect(r.declared[0]).not.toHaveProperty('value');
    expect(r.values).toEqual({ a: 'from template' });
  });

  it('sets and clears one value', () => {
    expect(withParameterValue(undefined, 'a', 'x')).toEqual({ a: 'x' });
    expect(withParameterValue({ a: 'x', b: 1 }, 'a', '')).toEqual({ b: 1 });
    expect(withParameterValue({ a: 'x' }, 'a', null)).toBeUndefined();
    expect(withParameterValue({}, 'flag', false)).toEqual({ flag: false });
  });

  it('merges the lineage: child declarations and values win, a project parent first', () => {
    const base = {
      key: 'llm-task',
      scope: { type: 'GLOBAL' as const },
      parameters: [text('task', { required: true }), text('tone', { default: 'formal' })],
    };
    const projectBase = {
      key: 'llm-task',
      scope: { type: 'PROJECT' as const, projectId: 'p1' },
      extendsKey: 'llm-task',
      parameterValues: { tone: 'casual' },
    };
    const preset = {
      key: 'summarise',
      extendsKey: 'llm-task',
      scope: { type: 'PROJECT' as const, projectId: 'p1' },
      parameterValues: { task: 'Summarise the issue' },
    };
    const all = [base, projectBase, preset];
    const merged = lineageParameters(preset, all);
    expect(merged.declared.map((p) => p.name)).toEqual(['task', 'tone']);
    expect(merged.values).toEqual({ tone: 'casual', task: 'Summarise the issue' });
    expect(templateNeedsParameters(base, all)).toBe(true);
    expect(templateNeedsParameters(projectBase, all)).toBe(true);
    expect(templateNeedsParameters(preset, all)).toBe(false);
    expect(templateNeedsParameters({ key: 'x' }, all)).toBe(false);
  });

  it('stops on a cycle', () => {
    const a = { key: 'a', extendsKey: 'b', parameters: [text('p', { required: true })] };
    const b = { key: 'b', extendsKey: 'a' };
    expect(lineageParameters(a, [a, b]).declared).toHaveLength(1);
  });
});
