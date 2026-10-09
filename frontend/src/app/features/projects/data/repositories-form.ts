/**
 * The "Repositories" list editor of the project form (ADR 0034) as pure functions over a reactive
 * FormArray: add, remove, reorder, a single (optional) primary, key pattern and uniqueness.
 * A project may have no repository at all (LLM work only).
 */
import {
  type AbstractControl,
  FormArray,
  FormControl,
  FormGroup,
  type ValidationErrors,
  type ValidatorFn,
  Validators,
} from '@angular/forms';
import type { ProjectRepository, RepositoryPurpose } from '../../../core/api/types';

export const REPOSITORY_KEY_PATTERN = /^[a-z][a-z0-9-]{0,30}$/;

export type RepositoryGroup = FormGroup<{
  key: FormControl<string>;
  url: FormControl<string>;
  defaultBranch: FormControl<string>;
  purpose: FormControl<RepositoryPurpose>;
  description: FormControl<string>;
  primary: FormControl<boolean>;
}>;

export type RepositoriesArray = FormArray<RepositoryGroup>;

export function repositoryGroup(r: Partial<ProjectRepository> = {}): RepositoryGroup {
  return new FormGroup({
    key: new FormControl(r.key ?? '', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(REPOSITORY_KEY_PATTERN)],
    }),
    url: new FormControl(r.url ?? '', { nonNullable: true, validators: [Validators.required] }),
    defaultBranch: new FormControl(r.defaultBranch || 'main', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    purpose: new FormControl<RepositoryPurpose>(r.purpose ?? 'SOURCE', { nonNullable: true }),
    description: new FormControl(r.description ?? '', { nonNullable: true }),
    primary: new FormControl(r.primary ?? false, { nonNullable: true }),
  });
}

/**
 * Array validator: keys unique (the later duplicates get `{duplicate: true}` on their key control)
 * and at most one primary. Runs on every value change of the array.
 */
export const repositoriesValidator: ValidatorFn = (
  control: AbstractControl,
): ValidationErrors | null => {
  const array = control as RepositoriesArray;
  const seen = new Set<string>();
  let duplicates = false;
  for (const g of array.controls) {
    const key = g.controls.key;
    const value = key.value.trim();
    const dup = !!value && seen.has(value);
    seen.add(value);
    const errors = { ...(key.errors ?? {}) };
    if (dup) errors['duplicate'] = true;
    else delete errors['duplicate'];
    const next = Object.keys(errors).length ? errors : null;
    if (JSON.stringify(next) !== JSON.stringify(key.errors)) key.setErrors(next);
    duplicates ||= dup;
  }
  const primaries = array.controls.filter((g) => g.controls.primary.value).length;
  const errors: ValidationErrors = {};
  if (duplicates) errors['duplicateKeys'] = true;
  if (primaries > 1) errors['manyPrimaries'] = true;
  return Object.keys(errors).length ? errors : null;
};

export function repositoriesArray(list: ProjectRepository[] = []): RepositoriesArray {
  return new FormArray(
    list.map((r) => repositoryGroup(r)),
    { validators: repositoriesValidator },
  );
}

/** A free key: `main` for the first repository, then `repo-2`, `repo-3`, ... */
export function suggestKey(existing: readonly string[]): string {
  const taken = new Set(existing);
  if (!taken.has('main')) return 'main';
  let i = existing.length + 1;
  while (taken.has(`repo-${i}`)) i++;
  return `repo-${i}`;
}

/** Appends a repository; the first one of an empty list is primary. Returns its index. */
export function addRepository(array: RepositoriesArray): number {
  const keys = array.controls.map((g) => g.controls.key.value);
  array.push(repositoryGroup({ key: suggestKey(keys), primary: array.length === 0 }));
  array.markAsDirty();
  return array.length - 1;
}

/** Removes a repository. The primary flag is not moved: having no primary is allowed. */
export function removeRepository(array: RepositoriesArray, index: number): void {
  if (index < 0 || index >= array.length) return;
  array.removeAt(index);
  array.markAsDirty();
}

/** Moves the repository at `index` by `delta` (-1 up, +1 down); no-op at the ends. */
export function moveRepository(array: RepositoriesArray, index: number, delta: number): void {
  const target = index + delta;
  if (index < 0 || index >= array.length || target < 0 || target >= array.length) return;
  const group = array.at(index);
  array.removeAt(index, { emitEvent: false });
  array.insert(target, group);
  array.markAsDirty();
}

/** Radio-like primary: turning one on turns the others off; turning it off leaves none. */
export function setPrimary(array: RepositoriesArray, index: number, on: boolean): void {
  array.controls.forEach((g, i) => {
    const want = i === index ? on : on ? false : g.controls.primary.value;
    if (g.controls.primary.value !== want) g.controls.primary.setValue(want, { emitEvent: false });
  });
  array.updateValueAndValidity();
  array.markAsDirty();
}

/** The list sent as `ProjectInput.repositories` (trimmed, empty description omitted). */
export function repositoriesValue(array: RepositoriesArray): ProjectRepository[] {
  return array.getRawValue().map((r) => ({
    key: r.key.trim(),
    url: r.url.trim(),
    defaultBranch: r.defaultBranch.trim() || 'main',
    purpose: r.purpose,
    description: r.description.trim() || null,
    primary: r.primary,
  }));
}
