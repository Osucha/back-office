import type { LibrarySource, LibrarySourceInput } from '../../../core/api/types';

/** Keys of library sources (ADR 0043): `lib-<key>` must be a valid repository key of at most 31 characters. */
export const SOURCE_KEY_PATTERN = /^[a-z][a-z0-9-]{1,26}$/;

export function emptySource(): LibrarySourceInput {
  return {
    key: '',
    name: '',
    description: '',
    kind: 'GIT',
    url: '',
    defaultBranch: 'main',
    purpose: 'STANDARDS',
  };
}

export function sourceInputOf(s: LibrarySource): LibrarySourceInput {
  return {
    key: s.key,
    name: s.name,
    description: s.description ?? '',
    kind: 'GIT',
    url: s.url,
    defaultBranch: s.defaultBranch ?? 'main',
    purpose: s.purpose ?? 'STANDARDS',
  };
}

/** What blocks saving [input]; empty when it can be saved. */
export function sourceProblems(input: LibrarySourceInput): string[] {
  const out: string[] = [];
  if (!SOURCE_KEY_PATTERN.test(input.key ?? ''))
    out.push(
      $localize`:@@library.sources.problem.key:Key: 2-27 lowercase letters, digits or dashes, starting with a letter.`,
    );
  if (!input.name?.trim()) out.push($localize`:@@library.sources.problem.name:Name is required.`);
  if (!input.url?.trim()) out.push($localize`:@@library.sources.problem.url:Git URL is required.`);
  return out;
}
