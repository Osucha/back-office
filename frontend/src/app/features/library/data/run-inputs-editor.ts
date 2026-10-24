/**
 * Run inputs of a task template (ADR 0053), pure helpers for the template editor: the list editor (add,
 * remove, reorder, patch), the save check, and where each field of the resolved view comes from.
 */
import type { RunInputField, TaskTemplate } from '../../../core/api/types';

export type RunInputProblem =
  | { kind: 'blankName' }
  | { kind: 'blankLabel'; name: string }
  | { kind: 'duplicate'; name: string };

/** A resolved field and the template of the lineage that declares it (null when none of them does). */
export type RunInputWithOrigin = RunInputField & { from: string | null };

/** Appends a single-line optional field with a name not used yet. */
export function addRunInput(list: readonly RunInputField[]): RunInputField[] {
  const used = new Set(list.map((f) => f.name));
  let n = list.length + 1;
  while (used.has(`field-${n}`)) n++;
  return [...list, { name: `field-${n}`, label: '', type: 'TEXT', required: false, help: null }];
}

export function removeRunInput(list: readonly RunInputField[], index: number): RunInputField[] {
  return list.filter((_, i) => i !== index);
}

/** Moves the field at [index] by [delta]; the same list when it would leave the bounds. */
export function moveRunInput<T extends readonly RunInputField[]>(
  list: T,
  index: number,
  delta: number,
): T | RunInputField[] {
  const target = index + delta;
  if (target < 0 || target >= list.length) return list;
  const next = [...list];
  [next[index], next[target]] = [next[target], next[index]];
  return next;
}

export function patchRunInput(
  list: readonly RunInputField[],
  index: number,
  changes: Partial<RunInputField>,
): RunInputField[] {
  return list.map((f, i) => (i === index ? { ...f, ...changes } : f));
}

/** The first problem that stops saving (the server refuses the same), or null. */
export function runInputProblem(list: readonly RunInputField[]): RunInputProblem | null {
  const seen = new Set<string>();
  for (const f of list) {
    const name = f.name.trim();
    if (!name) return { kind: 'blankName' };
    if (!f.label.trim()) return { kind: 'blankLabel', name };
    if (seen.has(name)) return { kind: 'duplicate', name };
    seen.add(name);
  }
  return null;
}

/**
 * Marks each resolved field with the nearest template of [lineage] (leaf first, as the server returns it)
 * whose own run inputs declare the name: the child wins, so that is where the field's current form comes from.
 */
export function withOrigins(
  resolved: readonly RunInputField[],
  lineage: readonly string[],
  templates: readonly Pick<TaskTemplate, 'key' | 'runInputs'>[],
): RunInputWithOrigin[] {
  return resolved.map((field) => {
    const from = lineage.find((key) =>
      templates.some(
        (t) => t.key === key && (t.runInputs ?? []).some((r) => r.name === field.name),
      ),
    );
    return { ...field, from: from ?? null };
  });
}
