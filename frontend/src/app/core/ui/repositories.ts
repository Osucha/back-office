import type {
  GitAccess,
  Project,
  ProjectRepository,
  RepositoryPurpose,
  RepositoryResult,
} from '../api/types';

/**
 * Project repositories (ADR 0034): labels and helpers shared by the project pages, the workflow
 * designer, the run page and CI. A project has zero or more repositories; at most one is primary.
 */

/** The repositories of a project, falling back to the deprecated single `repository` (older API). */
export function projectRepositories(p: Project | null | undefined): ProjectRepository[] {
  if (!p) return [];
  if (p.repositories) return p.repositories;
  if (p.repository?.url) {
    return [
      {
        key: 'main', // i18n-ignore
        url: p.repository.url,
        defaultBranch: p.repository.defaultBranch ?? 'main',
        purpose: 'SOURCE',
        primary: true,
      },
    ];
  }
  return [];
}

/** The primary repository, or null when the project has none (or none is marked primary). */
export function primaryRepository(p: Project | null | undefined): ProjectRepository | null {
  return projectRepositories(p).find((r) => r.primary) ?? null;
}

/** `gitlab.example.com/group/shop-api` from `https://gitlab.example.com/group/shop-api.git`. */
export function shortRepoUrl(url: string | null | undefined): string {
  return (url ?? '')
    .replace(/^[a-z+]+:\/\//i, '')
    .replace(/^git@/, '')
    .replace(/\.git$/, '');
}

export function purposeLabel(purpose: RepositoryPurpose | null | undefined): string {
  switch (purpose) {
    case 'DOCS':
      return $localize`:@@core.repository.purpose.docs:Documentation`;
    case 'OTHER':
      return $localize`:@@core.repository.purpose.other:Other`;
    case 'SOURCE':
      return $localize`:@@core.repository.purpose.source:Source code`;
    default:
      return '';
  }
}

export function accessLabel(access: GitAccess | null | undefined): string {
  switch (access) {
    case 'READ':
      return $localize`:@@core.repository.access.read:Read`;
    case 'PUSH_MR':
      return $localize`:@@core.repository.access.pushMr:Push and merge request`;
    case 'NONE':
      return $localize`:@@core.repository.access.none:No access`;
    default:
      return '';
  }
}

/** First 8 characters of a commit SHA. */
export function shortSha(sha: string | null | undefined): string {
  return sha ? sha.slice(0, 8) : '';
}

/** `+12 −3 (4 files)` style parts of a diff stat; null without one. */
export function diffStatParts(
  r: Pick<RepositoryResult, 'diffStat'>,
): { insertions: number; deletions: number; files: number } | null {
  const d = r.diffStat;
  if (!d) return null;
  return { insertions: d.insertions ?? 0, deletions: d.deletions ?? 0, files: d.files ?? 0 };
}
