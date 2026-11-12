/**
 * ADR 0062 in the workflow designer, as pure functions (tested in step-bindings.spec.ts):
 *
 * - Instructions of a step: `instructionsAppend` (notes added after the template's instructions) and
 *   `instructionsReplace` (replaces them). The former `systemPromptAppend` / `promptAppend` read as append,
 *   `promptReplace` as replace; every write drops the legacy keys.
 * - Parameter bindings: per parameter of the template where its value comes from (`overrides.parameterBindings`):
 *   VALUE (a fixed value), START (asked in the run start form) or ARTIFACT (the content of an artefact of an
 *   earlier step). Unbound = the preset value or the default. The former `overrides.parameters` read as VALUE.
 * - The artefacts earlier steps produce, offered for ARTIFACT bindings.
 */
import type {
  ParameterBinding,
  ParameterSource,
  TaskOverrides,
  TaskTemplate,
  WorkflowEdge,
  WorkflowNode,
} from '../../../core/api/types';
import {
  type ParameterField,
  type ParameterValues,
  isEmptyValue,
} from '../../../core/ui/template-parameters';

/** The sources a step offers, in display order. */
export const PARAMETER_SOURCES: readonly ParameterSource[] = ['VALUE', 'START', 'ARTIFACT'];

/** Override keys of earlier versions, read compatibly and dropped on the next write. */
export const LEGACY_OVERRIDE_KEYS = [
  'systemPromptAppend',
  'promptAppend',
  'promptReplace',
  'parameters',
] as const;

type Overrides = TaskOverrides | null | undefined;
type Bindings = Record<string, ParameterBinding>;

interface LegacyOverrides {
  systemPromptAppend?: string | null;
  promptAppend?: string | null;
  promptReplace?: string | null;
  parameters?: ParameterValues | null;
}

const legacy = (o: Overrides): LegacyOverrides => (o ?? {}) as LegacyOverrides;
const text = (v: unknown): string => (typeof v === 'string' ? v : '');

/** Drops empty values and the legacy keys; the overrides a step writes. */
function compact(o: Record<string, unknown>): TaskOverrides {
  for (const k of LEGACY_OVERRIDE_KEYS) delete o[k];
  for (const [k, v] of Object.entries(o))
    if (
      v === null ||
      v === undefined ||
      v === '' ||
      (Array.isArray(v) && !v.length) ||
      (typeof v === 'object' && !Array.isArray(v) && !Object.keys(v as object).length)
    )
      delete o[k];
  return o as TaskOverrides;
}

// ---------------------------------------------------------------- instructions

export interface StepInstructions {
  /** Notes added after the template's instructions ('' = none). */
  append: string;
  /** Replaces the template's instructions ('' = none). */
  replace: string;
}

/** The step's instruction overrides; legacy prompt keys read as append / replace. */
export function stepInstructions(o: Overrides): StepInstructions {
  const l = legacy(o);
  const legacyAppend = [text(l.systemPromptAppend), text(l.promptAppend)]
    .filter((s) => s.trim())
    .join('\n\n');
  return {
    append: text(o?.instructionsAppend) || legacyAppend,
    replace: text(o?.instructionsReplace) || text(l.promptReplace),
  };
}

/**
 * The overrides with the instructions changed (a key left out keeps its current value, legacy ones
 * migrated); the legacy prompt keys are dropped and the bindings migrated with them.
 */
export function withInstructions(o: Overrides, change: Partial<StepInstructions>): TaskOverrides {
  const current = stepInstructions(o);
  const next: Record<string, unknown> = {
    ...(o ?? {}),
    parameterBindings: stepBindings(o),
    instructionsAppend: change.append ?? current.append,
    instructionsReplace: change.replace ?? current.replace,
  };
  return compact(next);
}

// ---------------------------------------------------------------- bindings

/** The step's bindings: legacy `parameters` as VALUE, the bindings win. */
export function stepBindings(o: Overrides): Bindings {
  const out: Bindings = {};
  for (const [name, value] of Object.entries(legacy(o).parameters ?? {}))
    if (!isEmptyValue(value)) out[name] = { source: 'VALUE', value };
  for (const [name, b] of Object.entries(o?.parameterBindings ?? {})) if (b) out[name] = b;
  return out;
}

/** The binding of one parameter; null = unbound (the preset value or the default). */
export function bindingOf(o: Overrides, name: string): ParameterBinding | null {
  return stepBindings(o)[name] ?? null;
}

/** The source the step shows for a parameter: an unbound one shows as VALUE (empty). */
export function bindingSource(o: Overrides, name: string): ParameterSource {
  return bindingOf(o, name)?.source ?? 'VALUE';
}

/** A binding as the step stores it; null when it means "unbound" (a VALUE without a value). */
export function normalizeBinding(b: ParameterBinding | null | undefined): ParameterBinding | null {
  if (!b) return null;
  switch (b.source) {
    case 'VALUE':
      return isEmptyValue(b.value) ? null : { source: 'VALUE', value: b.value };
    case 'START':
      return { source: 'START' };
    case 'ARTIFACT':
      return { source: 'ARTIFACT', artifact: b.artifact?.trim() || null };
    default:
      return null;
  }
}

/**
 * The overrides with one parameter's binding changed (null = unbound); legacy `parameters` are
 * migrated into the bindings and every legacy key dropped.
 */
export function withBinding(
  o: Overrides,
  name: string,
  binding: ParameterBinding | null,
): TaskOverrides {
  const bindings = stepBindings(o);
  const b = normalizeBinding(binding);
  if (b) bindings[name] = b;
  else delete bindings[name];
  const instructions = stepInstructions(o);
  return compact({
    ...(o ?? {}),
    instructionsAppend: instructions.append,
    instructionsReplace: instructions.replace,
    parameterBindings: bindings,
  });
}

/** The overrides with the source of a parameter switched; a fixed value or artefact is kept for its own source. */
export function withBindingSource(
  o: Overrides,
  name: string,
  source: ParameterSource,
): TaskOverrides {
  const current = bindingOf(o, name);
  if (current?.source === source) return withBinding(o, name, current);
  switch (source) {
    case 'START':
      return withBinding(o, name, { source: 'START' });
    case 'ARTIFACT':
      return withBinding(o, name, { source: 'ARTIFACT', artifact: null });
    default:
      return withBinding(o, name, null);
  }
}

/** The fixed values of the step (VALUE bindings), for the parameter value fields. */
export function stepValues(o: Overrides): ParameterValues {
  const out: ParameterValues = {};
  for (const [name, b] of Object.entries(stepBindings(o)))
    if (b.source === 'VALUE' && !isEmptyValue(b.value)) out[name] = b.value;
  return out;
}

/**
 * Required parameters without a value: neither a fixed value of the step, nor a preset value or
 * default, nor a START or ARTIFACT binding.
 */
export function missingBoundParameters(fields: readonly ParameterField[], o: Overrides): string[] {
  const bindings = stepBindings(o);
  return fields
    .filter((f) => f.missing)
    .filter((f) => {
      const s = bindings[f.param.name]?.source;
      return s !== 'START' && s !== 'ARTIFACT';
    })
    .map((f) => f.param.name);
}

/** Parameters bound to an artefact without naming it. */
export function unnamedArtifactBindings(o: Overrides): string[] {
  return Object.entries(stepBindings(o))
    .filter(([, b]) => b.source === 'ARTIFACT' && !b.artifact?.trim())
    .map(([name]) => name);
}

/** Whether a step changes anything of its template (the canvas marks it). */
export function hasStepOverrides(o: Overrides): boolean {
  if (!o) return false;
  const i = stepInstructions(o);
  return Boolean(
    o.agentKey ||
    o.modelProfileId ||
    i.append.trim() ||
    i.replace.trim() ||
    Object.keys(stepBindings(o)).length ||
    (o.config && Object.keys(o.config).length),
  );
}

// ---------------------------------------------------------------- artefacts of earlier steps

/**
 * What each template key produces, its lineage included (a template inherits its parent's
 * artefacts); the global and project templates of one key are united.
 */
export function templateProduces(
  templates: readonly Pick<TaskTemplate, 'key' | 'extendsKey' | 'produces'>[],
): Map<string, string[]> {
  const byKey = new Map<string, Array<Pick<TaskTemplate, 'extendsKey' | 'produces'>>>();
  for (const t of templates) {
    if (!t.key) continue;
    if (!byKey.has(t.key)) byKey.set(t.key, []);
    byKey.get(t.key)!.push(t);
  }
  const out = new Map<string, string[]>();
  for (const key of byKey.keys()) {
    const names = new Set<string>();
    const seen = new Set<string>();
    let level = [key];
    // at most 8 levels (ADR 0058)
    for (let depth = 0; depth < 9 && level.length; depth++) {
      const next: string[] = [];
      for (const k of level) {
        if (seen.has(k)) continue;
        seen.add(k);
        for (const t of byKey.get(k) ?? []) {
          for (const p of t.produces ?? []) if (p.trim()) names.add(p.trim());
          if (t.extendsKey && !seen.has(t.extendsKey)) next.push(t.extendsKey);
        }
      }
      level = next;
    }
    out.set(key, [...names]);
  }
  return out;
}

/**
 * The artefact names the steps before `selfId` produce (TASK nodes by their template, ACTION nodes
 * by their action), in workflow order. "Before" = every node from which an edge path leads to the
 * step; a step without incoming edges gets the artefacts of all other nodes.
 */
export function earlierArtifacts(
  nodes: readonly WorkflowNode[],
  edges: readonly Pick<WorkflowEdge, 'from' | 'to'>[],
  selfId: string,
  producesOf: (n: WorkflowNode) => readonly string[] | null | undefined,
): string[] {
  const incoming = new Map<string, string[]>();
  for (const e of edges) {
    if (!incoming.has(e.to)) incoming.set(e.to, []);
    incoming.get(e.to)!.push(e.from);
  }
  const before = new Set<string>();
  const stack = [...(incoming.get(selfId) ?? [])];
  while (stack.length) {
    const id = stack.pop()!;
    if (id === selfId || before.has(id)) continue;
    before.add(id);
    stack.push(...(incoming.get(id) ?? []));
  }
  const all = !incoming.get(selfId)?.length;
  const names = new Set<string>();
  for (const n of nodes) {
    if (n.id === selfId || (!all && !before.has(n.id))) continue;
    if (n.kind !== 'TASK' && n.kind !== 'ACTION') continue;
    for (const p of producesOf(n) ?? []) if (p.trim()) names.add(p.trim());
  }
  return [...names];
}
