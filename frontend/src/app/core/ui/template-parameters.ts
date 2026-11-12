/**
 * ADR 0058: template parameters as pure functions (tested in template-parameters.spec.ts), shared
 * by the template editor (declarations, preset values) and the workflow designer (step values).
 *
 * A template declares typed parameters (`TEXT`, `MULTILINE`, `NUMBER`, `BOOLEAN`, `CHOICE`,
 * `ISSUE_KEY`, ADR 0062);
 * prompts read them as `{{params.<name>}}`. Declarations are inherited, child wins by name. The
 * value a step uses is the step's, else the nearest template's (`parameterValues`), else the
 * default. A template is a ready block when every required parameter has a value without the step.
 */
import type {
  ResolvedParameter,
  Scope,
  TemplateParameter,
  TemplateParameterType,
} from '../api/types';

export const PARAMETER_NAME_PATTERN = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
export const PARAMETER_TYPES: readonly TemplateParameterType[] = [
  'TEXT',
  'MULTILINE',
  'NUMBER',
  'BOOLEAN',
  'CHOICE',
  'ISSUE_KEY',
];

export type ParameterValues = Record<string, unknown>;

/** Translated name of a parameter type. */
export function parameterTypeLabel(type: TemplateParameterType | string): string {
  switch (type) {
    case 'TEXT':
      return $localize`:@@core.parameter.type.text:Single line`;
    case 'MULTILINE':
      return $localize`:@@core.parameter.type.multiline:Multi-line text`;
    case 'NUMBER':
      return $localize`:@@core.parameter.type.number:Number`;
    case 'BOOLEAN':
      return $localize`:@@core.parameter.type.boolean:Yes / no`;
    case 'CHOICE':
      return $localize`:@@core.parameter.type.choice:Choice`;
    case 'ISSUE_KEY':
      return $localize`:@@core.parameter.type.issueKey:Jira issue key`;
    default:
      return type;
  }
}

/** Whether a value counts as "no value" (null, undefined, blank text). */
export function isEmptyValue(v: unknown): boolean {
  return v === null || v === undefined || (typeof v === 'string' && v.trim() === '');
}

/** Whether a (non-empty) value fits the parameter's type. */
export function valueFitsType(p: Pick<TemplateParameter, 'type' | 'options'>, v: unknown): boolean {
  switch (p.type) {
    case 'TEXT':
    case 'MULTILINE':
    case 'ISSUE_KEY':
      return typeof v === 'string';
    case 'NUMBER':
      return typeof v === 'number' && Number.isFinite(v);
    case 'BOOLEAN':
      return typeof v === 'boolean';
    case 'CHOICE':
      return typeof v === 'string' && (p.options ?? []).includes(v);
    default:
      return false;
  }
}

/** A value as text, for placeholders and chips ("Yes" / "No" for booleans). */
export function parameterValueText(v: unknown): string {
  if (isEmptyValue(v)) return '';
  if (typeof v === 'boolean')
    return v
      ? $localize`:@@core.parameter.value.yes:Yes`
      : $localize`:@@core.parameter.value.no:No`;
  return typeof v === 'string' ? v : JSON.stringify(v);
}

// ---------------------------------------------------------------- declarations (template editor)

export function addParameter(list: readonly TemplateParameter[] | undefined): TemplateParameter[] {
  return [
    ...(list ?? []),
    { name: '', label: '', type: 'TEXT', required: false, default: null, help: null },
  ];
}

export function removeParameter(
  list: readonly TemplateParameter[] | undefined,
  index: number,
): TemplateParameter[] {
  return (list ?? []).filter((_, i) => i !== index);
}

export function moveParameter(
  list: readonly TemplateParameter[] | undefined,
  index: number,
  delta: number,
): TemplateParameter[] {
  const out = [...(list ?? [])];
  const to = index + delta;
  if (to < 0 || to >= out.length) return out;
  [out[index], out[to]] = [out[to], out[index]];
  return out;
}

/**
 * Changes one declaration. A new type drops a default that does not fit it; options exist only
 * for CHOICE (and a CHOICE default must stay one of them).
 */
export function patchParameter(
  list: readonly TemplateParameter[] | undefined,
  index: number,
  changes: Partial<TemplateParameter>,
): TemplateParameter[] {
  return (list ?? []).map((p, i) => {
    if (i !== index) return p;
    const next: TemplateParameter = { ...p, ...changes };
    if (next.type === 'CHOICE') next.options = [...(next.options ?? [])];
    else delete next.options;
    if (!isEmptyValue(next.default) && !valueFitsType(next, next.default)) next.default = null;
    return next;
  });
}

/** What is wrong with the declarations; null when nothing. */
export type ParameterProblem =
  | { kind: 'blankName' }
  | { kind: 'badName'; name: string }
  | { kind: 'duplicate'; name: string }
  | { kind: 'blankLabel'; name: string }
  | { kind: 'tooLong'; name: string }
  | { kind: 'noOptions'; name: string }
  | { kind: 'badDefault'; name: string };

export function parameterProblem(
  list: readonly TemplateParameter[] | undefined,
): ParameterProblem | null {
  const seen = new Set<string>();
  for (const p of list ?? []) {
    const name = p.name.trim();
    if (!name) return { kind: 'blankName' };
    if (!PARAMETER_NAME_PATTERN.test(name)) return { kind: 'badName', name };
    if (seen.has(name)) return { kind: 'duplicate', name };
    seen.add(name);
    if (!p.label.trim()) return { kind: 'blankLabel', name };
    if (p.label.length > 200 || (p.help ?? '').length > 1000) return { kind: 'tooLong', name };
    if (p.type === 'CHOICE' && !(p.options ?? []).length) return { kind: 'noOptions', name };
    if (!isEmptyValue(p.default) && !valueFitsType(p, p.default))
      return { kind: 'badDefault', name };
  }
  return null;
}

/** Declarations as the editor saves them: trimmed, empty help and default as null. */
export function cleanParameters(
  list: readonly TemplateParameter[] | undefined,
): TemplateParameter[] {
  return (list ?? []).map((p) => {
    const out: TemplateParameter = {
      name: p.name.trim(),
      label: p.label.trim(),
      type: p.type,
      required: !!p.required,
      default: isEmptyValue(p.default) ? null : p.default,
      help: p.help?.trim() ? p.help.trim() : null,
    };
    if (p.type === 'CHOICE') out.options = (p.options ?? []).map((o) => o.trim()).filter(Boolean);
    return out;
  });
}

// ---------------------------------------------------------------- values (presets, steps)

/** One field of a parameter values form. */
export interface ParameterField {
  param: TemplateParameter;
  /** The value set at this level (preset or step); null = none. */
  value: unknown;
  /** The value without this level: the nearer templates' or the default; null = none. */
  inherited: unknown;
  /** Where the inherited value comes from. */
  inheritedSource: 'TEMPLATE' | 'DEFAULT' | 'NONE';
  /** Required and no value at this level or below. */
  missing: boolean;
  /** The own value does not fit the type (e.g. an option that no longer exists). */
  invalid: boolean;
}

/**
 * The fields for declared parameters with the values the templates set (`templateValues`) and
 * the values of this level (`own`).
 */
export function parameterFields(
  declared: readonly TemplateParameter[] | null | undefined,
  templateValues: ParameterValues | null | undefined,
  own: ParameterValues | null | undefined,
): ParameterField[] {
  return (declared ?? []).map((param) => {
    const value = own?.[param.name];
    const fromTemplate = templateValues?.[param.name];
    const inheritedSource = !isEmptyValue(fromTemplate)
      ? 'TEMPLATE'
      : !isEmptyValue(param.default)
        ? 'DEFAULT'
        : 'NONE';
    const inherited =
      inheritedSource === 'TEMPLATE'
        ? fromTemplate
        : inheritedSource === 'DEFAULT'
          ? param.default
          : null;
    const empty = isEmptyValue(value);
    return {
      param,
      value: empty ? null : value,
      inherited,
      inheritedSource,
      missing: !!param.required && empty && inheritedSource === 'NONE',
      invalid: !empty && !valueFitsType(param, value),
    };
  });
}

/**
 * The declarations and template values of a resolved task (`ResolvedTask.parameters`): a value
 * whose source is a template counts as the template's; a step value is left out.
 */
export function fromResolved(resolved: readonly ResolvedParameter[] | null | undefined): {
  declared: TemplateParameter[];
  values: ParameterValues;
} {
  const declared: TemplateParameter[] = [];
  const values: ParameterValues = {};
  for (const r of resolved ?? []) {
    const { value, source, ...param } = r;
    declared.push(param);
    if (source === 'TEMPLATE' && !isEmptyValue(value)) values[param.name] = value;
  }
  return { declared, values };
}

/**
 * Sets one value; an empty value removes the name. Returns undefined when nothing is left (the
 * field is then left out of the request).
 */
export function withParameterValue(
  values: ParameterValues | null | undefined,
  name: string,
  value: unknown,
): ParameterValues | undefined {
  const next: ParameterValues = { ...(values ?? {}) };
  if (isEmptyValue(value)) delete next[name];
  else next[name] = value;
  return Object.keys(next).length ? next : undefined;
}

/** Names of required parameters without a value at this level or below. */
export function missingParameters(fields: readonly ParameterField[]): string[] {
  return fields.filter((f) => f.missing).map((f) => f.param.name);
}

// ---------------------------------------------------------------- lineage (library list)

interface TemplateLike {
  key?: string | null;
  extendsKey?: string | null;
  scope?: Scope | null;
  parameters?: TemplateParameter[] | null;
  parameterValues?: ParameterValues | null;
}

/**
 * The parameters of a template's lineage, child wins by name, and the values the lineage sets
 * (child wins). A parent in the template's project wins over the global one of the same key.
 */
export function lineageParameters(
  t: TemplateLike,
  all: readonly TemplateLike[],
): { declared: TemplateParameter[]; values: ParameterValues } {
  const chain: TemplateLike[] = [t];
  const projectId = t.scope?.type === 'PROJECT' ? t.scope.projectId : null;
  let key = t.extendsKey ?? null;
  // at most 8 levels (ADR 0058); a project template customising the global one of its own key
  // extends that global one, so a template is never its own parent
  while (key && chain.length < 9) {
    const candidates = all.filter((x) => x.key === key && !chain.includes(x));
    const parent =
      (projectId ? candidates.find((x) => x.scope?.projectId === projectId) : undefined) ??
      candidates.find((x) => x.scope?.type !== 'PROJECT') ??
      candidates[0];
    if (!parent) break;
    chain.push(parent);
    key = parent.extendsKey ?? null;
  }
  const byName = new Map<string, TemplateParameter>();
  const values: ParameterValues = {};
  for (const x of [...chain].reverse()) {
    for (const p of x.parameters ?? []) byName.set(p.name, p);
    for (const [k, v] of Object.entries(x.parameterValues ?? {}))
      if (!isEmptyValue(v)) values[k] = v;
  }
  return { declared: [...byName.values()], values };
}

/** ADR 0058: a required parameter has no value without the step (not a ready block). */
export function templateNeedsParameters(t: TemplateLike, all: readonly TemplateLike[]): boolean {
  const { declared, values } = lineageParameters(t, all);
  return missingParameters(parameterFields(declared, values, null)).length > 0;
}
