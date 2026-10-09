/**
 * Repositories of a runner step (ADR 0034): `overrides.config.repositories = [{key, access}]`.
 * Absent = default: the project's primary repository with derived access (push on -> PUSH_MR,
 * otherwise READ). Only runner steps (palette executor IMPLEMENT or SPEC_KIT) use repositories.
 */
import type {
  PaletteTask,
  ProjectRepository,
  TaskExecutor,
  ValidationIssue,
} from '../../../core/api/types';

export const STEP_REPOSITORIES_KEY = 'repositories';
export const RUNNER_EXECUTORS: readonly TaskExecutor[] = ['IMPLEMENT', 'SPEC_KIT'];

export type StepAccess = 'READ' | 'PUSH_MR';
export interface StepRepository {
  key: string;
  access: StepAccess;
}

export function isRunnerTask(task: Pick<PaletteTask, 'executor'> | null | undefined): boolean {
  return !!task?.executor && RUNNER_EXECUTORS.includes(task.executor);
}

/** Config keys of the schema the generic form must not render (`"x-editor": "repositories"`). */
export function ownEditorKeys(
  schema: { properties?: Record<string, unknown> } | null | undefined,
): string[] {
  const props = schema?.properties ?? {};
  return Object.entries(props)
    .filter(
      ([k, v]) =>
        k === STEP_REPOSITORIES_KEY ||
        (!!v &&
          typeof v === 'object' &&
          (v as Record<string, unknown>)['x-editor'] === 'repositories'),
    )
    .map(([k]) => k);
}

/** The explicit list of the node, or null for the default (primary repository). */
export function stepRepositories(
  config: Record<string, unknown> | null | undefined,
): StepRepository[] | null {
  const raw = config?.[STEP_REPOSITORIES_KEY];
  if (!Array.isArray(raw)) return null;
  return raw
    .filter((r): r is Record<string, unknown> => !!r && typeof r === 'object')
    .map((r) => ({
      key: String(r['key'] ?? ''),
      access: r['access'] === 'PUSH_MR' ? 'PUSH_MR' : 'READ',
    }));
}

/** The node config with the list set (null removes it = default). Undefined when empty. */
export function withStepRepositories(
  config: Record<string, unknown> | null | undefined,
  list: StepRepository[] | null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (list === null) delete next[STEP_REPOSITORIES_KEY];
  else next[STEP_REPOSITORIES_KEY] = list.map((r) => ({ key: r.key, access: r.access }));
  return Object.keys(next).length ? next : undefined;
}

/**
 * Whether the step pushes: the node's `config.push`, else the template's, else the schema default,
 * else true (IMPLEMENT / SPEC_KIT push unless told not to).
 */
export function stepPushes(
  nodeConfig: Record<string, unknown> | null | undefined,
  templateConfig: Record<string, unknown> | null | undefined,
  schema?: { properties?: Record<string, unknown> } | null,
): boolean {
  const pick = (v: unknown) => (typeof v === 'boolean' ? v : undefined);
  const schemaDefault = pick(
    (schema?.properties?.['push'] as Record<string, unknown> | undefined)?.['default'],
  );
  return (
    (pick(nodeConfig?.['push']) ?? pick(templateConfig?.['push']) ?? schemaDefault ?? true) !==
    false
  );
}

/** Access of the default mode (and of a repository added to the list for the primary). */
export function derivedAccess(pushes: boolean): StepAccess {
  return pushes ? 'PUSH_MR' : 'READ';
}

/** Switching to the explicit list starts with what the default does: the primary, derived access. */
export function initialExplicitList(
  repositories: readonly ProjectRepository[],
  pushes: boolean,
): StepRepository[] {
  const primary = repositories.find((r) => r.primary);
  return primary ? [{ key: primary.key, access: derivedAccess(pushes) }] : [];
}

export interface PickerRow {
  repository: ProjectRepository;
  checked: boolean;
  access: StepAccess;
  /** PUSH_MR offered only when the step pushes. */
  pushDisabled: boolean;
}

/** One row per project repository (project order); unknown keys of the list are reported apart. */
export function pickerRows(
  repositories: readonly ProjectRepository[],
  list: readonly StepRepository[],
  pushes: boolean,
): PickerRow[] {
  const byKey = new Map(list.map((r) => [r.key, r]));
  return repositories.map((repository) => {
    const s = byKey.get(repository.key);
    return {
      repository,
      checked: !!s,
      access: s?.access ?? 'READ',
      pushDisabled: !pushes,
    };
  });
}

export function unknownKeys(
  repositories: readonly ProjectRepository[],
  list: readonly StepRepository[],
): string[] {
  const keys = new Set(repositories.map((r) => r.key));
  return list.map((r) => r.key).filter((k) => !keys.has(k));
}

/** Ticks / unticks a repository. A new entry gets READ (least privilege). Keeps project order. */
export function toggleRepository(
  repositories: readonly ProjectRepository[],
  list: readonly StepRepository[],
  key: string,
  on: boolean,
): StepRepository[] {
  if (!on) return list.filter((r) => r.key !== key);
  if (list.some((r) => r.key === key)) return [...list];
  const next = [...list, { key, access: 'READ' as StepAccess }];
  const order = new Map(repositories.map((r, i) => [r.key, i]));
  return next.sort((a, b) => (order.get(a.key) ?? 1e9) - (order.get(b.key) ?? 1e9));
}

export function setRepositoryAccess(
  list: readonly StepRepository[],
  key: string,
  access: StepAccess,
): StepRepository[] {
  return list.map((r) => (r.key === key ? { ...r, access } : r));
}

/** Validation issues that concern the step's repositories (`task.config.repositories`). */
export function repositoryIssues(
  issues: readonly ValidationIssue[] | null | undefined,
): ValidationIssue[] {
  return (issues ?? []).filter((i) => !!i.field && /(^|\.)config\.repositories\b/.test(i.field));
}
