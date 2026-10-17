/**
 * Repositories of a runner step (ADR 0034): `overrides.config.repositories = [{key, access}]`.
 * Absent = default: the project's primary repository with derived access (push on -> PUSH_MR,
 * otherwise READ). Only runner steps (palette executor IMPLEMENT or SPEC_KIT) use repositories.
 * Library sources (ADR 0043) are entries `{key, access: READ, library: true}` of the same list; the job clones
 * them into /workspace/lib-<key>.
 */
import type {
  LibrarySource,
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
  /** A library source (always READ), not a project repository. */
  library?: boolean;
}

const isLibrary = (r: StepRepository) => r.library === true;

export function isRunnerTask(task: Pick<PaletteTask, 'executor'> | null | undefined): boolean {
  return !!task?.executor && RUNNER_EXECUTORS.includes(task.executor);
}

/** Config keys of the schema the generic form must not render (`"x-editor": "repositories"` or `"workspace"`). */
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
          ['repositories', 'workspace'].includes(String((v as Record<string, unknown>)['x-editor']))),
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
    .map((r): StepRepository => {
      const library = r['library'] === true;
      return {
        key: String(r['key'] ?? ''),
        access: !library && r['access'] === 'PUSH_MR' ? 'PUSH_MR' : 'READ',
        ...(library ? { library } : {}),
      };
    });
}

/** The node config with the list set (null removes it = default). Undefined when empty. */
export function withStepRepositories(
  config: Record<string, unknown> | null | undefined,
  list: StepRepository[] | null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (list === null) delete next[STEP_REPOSITORIES_KEY];
  else
    next[STEP_REPOSITORIES_KEY] = list.map((r) =>
      isLibrary(r) ? { key: r.key, access: 'READ', library: true } : { key: r.key, access: r.access },
    );
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
  const byKey = new Map(list.filter((r) => !isLibrary(r)).map((r) => [r.key, r]));
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
  return list
    .filter((r) => !isLibrary(r))
    .map((r) => r.key)
    .filter((k) => !keys.has(k));
}

/**
 * Ticks / unticks a project repository. A new entry gets READ (least privilege). Keeps project order;
 * library entries stay after the project ones.
 */
export function toggleRepository(
  repositories: readonly ProjectRepository[],
  list: readonly StepRepository[],
  key: string,
  on: boolean,
): StepRepository[] {
  const own = list.filter((r) => !isLibrary(r));
  const library = list.filter(isLibrary);
  if (!on) return [...own.filter((r) => r.key !== key), ...library];
  if (own.some((r) => r.key === key)) return [...list];
  const next = [...own, { key, access: 'READ' as StepAccess }];
  const order = new Map(repositories.map((r, i) => [r.key, i]));
  next.sort((a, b) => (order.get(a.key) ?? 1e9) - (order.get(b.key) ?? 1e9));
  return [...next, ...library];
}

/** Library sources the step attaches (keys in list order). */
export function stepLibraryKeys(list: readonly StepRepository[] | null): string[] {
  return (list ?? []).filter(isLibrary).map((r) => r.key);
}

/** Ticks / unticks a library source: always READ, after the project repositories, in library order. */
export function toggleLibrarySource(
  sources: readonly LibrarySource[],
  list: readonly StepRepository[],
  key: string,
  on: boolean,
): StepRepository[] {
  const own = list.filter((r) => !isLibrary(r));
  const library = list.filter(isLibrary).filter((r) => r.key !== key);
  if (on) library.push({ key, access: 'READ', library: true });
  const order = new Map(sources.map((s, i) => [s.key, i]));
  library.sort((a, b) => (order.get(a.key) ?? 1e9) - (order.get(b.key) ?? 1e9));
  return [...own, ...library];
}

/** Library keys of the list the library no longer has. */
export function unknownLibraryKeys(
  sources: readonly LibrarySource[],
  list: readonly StepRepository[],
): string[] {
  const keys = new Set(sources.map((s) => s.key));
  return stepLibraryKeys(list).filter((k) => !keys.has(k));
}

export function setRepositoryAccess(
  list: readonly StepRepository[],
  key: string,
  access: StepAccess,
): StepRepository[] {
  return list.map((r) => (r.key === key && !isLibrary(r) ? { ...r, access } : r));
}

/** Validation issues that concern the step's repositories (`task.config.repositories`). */
export function repositoryIssues(
  issues: readonly ValidationIssue[] | null | undefined,
): ValidationIssue[] {
  return (issues ?? []).filter((i) => !!i.field && /(^|\.)config\.repositories\b/.test(i.field));
}
