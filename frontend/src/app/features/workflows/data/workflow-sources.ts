/**
 * ADR 0063: the sources of a workflow (`WorkflowDefinitionInput.sources`): the project repositories and library
 * sources (ADR 0043) cloned into the run directory. Empty = the project's primary repository. Agents read them;
 * only the "Deliver changes" action writes back, and only to project repositories. Pure; tested in
 * workflow-sources.spec.ts.
 */
import type {
  LibrarySource,
  ProjectRepository,
  WorkflowDefinitionInput,
} from '../../../core/api/types';

export type WorkflowSource = NonNullable<WorkflowDefinitionInput['sources']>[number];

const LIBRARY_PREFIX = 'lib:';
const REPOSITORY_PREFIX = 'repo:';

/** The value of a source in the multi-select ("repo:<key>" or "lib:<key>"). */
export function sourceValue(s: WorkflowSource): string {
  return (s.library ? LIBRARY_PREFIX : REPOSITORY_PREFIX) + s.key;
}

/** The select values of the workflow's sources, in their order. */
export function sourceValues(sources: readonly WorkflowSource[] | null | undefined): string[] {
  return (sources ?? []).filter((s) => !!s.key).map(sourceValue);
}

/** The sources of the selected values (duplicates dropped); unknown prefixes are project repositories. */
export function sourcesFromValues(values: readonly string[] | null | undefined): WorkflowSource[] {
  const seen = new Set<string>();
  const out: WorkflowSource[] = [];
  for (const v of values ?? []) {
    const library = v.startsWith(LIBRARY_PREFIX);
    const key = library
      ? v.slice(LIBRARY_PREFIX.length)
      : v.startsWith(REPOSITORY_PREFIX)
        ? v.slice(REPOSITORY_PREFIX.length)
        : v;
    if (!key) continue;
    const s: WorkflowSource = { key, library };
    if (seen.has(sourceValue(s))) continue;
    seen.add(sourceValue(s));
    out.push(s);
  }
  return out;
}

export interface SourceOption {
  value: string;
  key: string;
  library: boolean;
  /** Not (or no longer) a repository of the project / a source of the library. */
  unknown: boolean;
}

/**
 * Options of the "Sources" select: the project's repositories, then the library sources; a selected key that is
 * gone stays selectable (marked unknown) so the user sees it and can remove it.
 */
export function sourceOptions(
  repositories: readonly ProjectRepository[],
  librarySources: readonly LibrarySource[],
  selected: readonly WorkflowSource[] | null | undefined,
): { repositories: SourceOption[]; library: SourceOption[] } {
  const repoOptions: SourceOption[] = repositories.map((r) => ({
    value: sourceValue({ key: r.key, library: false }),
    key: r.key,
    library: false,
    unknown: false,
  }));
  const libOptions: SourceOption[] = librarySources.map((s) => ({
    value: sourceValue({ key: s.key, library: true }),
    key: s.key,
    library: true,
    unknown: false,
  }));
  const known = new Set([...repoOptions, ...libOptions].map((o) => o.value));
  for (const s of selected ?? []) {
    const value = sourceValue(s);
    if (known.has(value) || !s.key) continue;
    known.add(value);
    const option = { value, key: s.key, library: !!s.library, unknown: true };
    (s.library ? libOptions : repoOptions).push(option);
  }
  return { repositories: repoOptions, library: libOptions };
}

/**
 * The project repositories "Deliver changes" may write to: the project repositories among the workflow's
 * sources; without sources, the primary repository (or the only one).
 */
export function deliverableRepositories(
  sources: readonly WorkflowSource[] | null | undefined,
  repositories: readonly ProjectRepository[],
): string[] {
  const own = (sources ?? []).filter((s) => !s.library && !!s.key).map((s) => s.key);
  if (own.length) return own;
  if ((sources ?? []).length === 0) {
    const primary = repositories.find((r) => r.primary) ?? repositories[0];
    return primary ? [primary.key] : [];
  }
  return [];
}
