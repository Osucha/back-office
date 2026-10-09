/**
 * Pure helpers for the project CI configuration UI: token creation modal state, entrypoint
 * editing and the display of token rows.
 */
import type { CiEntrypoint, CiToken, CiTokenCreated } from '../../../core/api/types';

export const ENTRYPOINT_KEY_PATTERN = /^[a-z][a-z0-9-]{1,39}$/;

/** State machine of the "create token" modal: idle -> naming -> created (plaintext shown once). */
export type TokenModalState =
  | { step: 'closed' }
  | { step: 'naming'; name: string; error: string | null }
  | { step: 'created'; token: CiTokenCreated; copied: boolean };

export function openTokenModal(existing: CiToken[]): TokenModalState {
  const active = existing.filter((t) => !t.revokedAt).length;
  return { step: 'naming', name: active ? `ci-token-${active + 1}` : 'gitlab-ci', error: null };
}

export function tokenNameError(name: string, existing: CiToken[]): string | null {
  const trimmed = name.trim();
  if (!trimmed) return $localize`:@@projects.ci.token.nameRequired:Name is required`;
  if (trimmed.length > 60) return $localize`:@@projects.ci.token.nameTooLong:At most 60 characters`;
  if (existing.some((t) => !t.revokedAt && t.name === trimmed))
    return $localize`:@@projects.ci.token.nameTaken:An active token with this name already exists`;
  return null;
}

export function tokenCreated(token: CiTokenCreated): TokenModalState {
  return { step: 'created', token, copied: false };
}

/** Rows for the token table: active first, then revoked, newest first within a group. */
export function sortTokens(tokens: CiToken[]): CiToken[] {
  return [...tokens].sort((a, b) => {
    const ra = a.revokedAt ? 1 : 0;
    const rb = b.revokedAt ? 1 : 0;
    if (ra !== rb) return ra - rb;
    return (b.createdAt ?? '').localeCompare(a.createdAt ?? '');
  });
}

export function maskedToken(prefix: string | undefined): string {
  return `${prefix ?? 'bo_ci_'}${'•'.repeat(8)}`;
}

/**
 * Validates the entrypoint list before PUT: keys unique and slug-like, workflow chosen and, when
 * `repositoryKeys` is given, an entrypoint repository is one of the project's (ADR 0034).
 */
export function validateEntrypoints(list: CiEntrypoint[], repositoryKeys?: string[]): string[] {
  const errors: string[] = [];
  const seen = new Set<string>();
  list.forEach((e, i) => {
    const where = e.key || `#${i + 1}`;
    if (!e.key || !ENTRYPOINT_KEY_PATTERN.test(e.key))
      errors.push(
        $localize`:@@projects.ci.entrypoint.keySlug:${where}:entrypoint:: key must be a slug (e.g. code-review)`,
      );
    if (seen.has(e.key))
      errors.push(
        $localize`:@@projects.ci.entrypoint.duplicateKey:${where}:entrypoint:: duplicate key`,
      );
    seen.add(e.key);
    if (!e.workflowId)
      errors.push(
        $localize`:@@projects.ci.entrypoint.pickWorkflow:${where}:entrypoint:: pick a workflow`,
      );
    if (repositoryKeys && e.repository && !repositoryKeys.includes(e.repository)) {
      const repository = e.repository;
      errors.push(
        $localize`:@@projects.ci.entrypoint.unknownRepository:${where}:entrypoint:: the project has no repository ${repository}:repository:`,
      );
    }
  });
  return errors;
}

export function newEntrypoint(existing: CiEntrypoint[], workflowId = ''): CiEntrypoint {
  const keys = new Set(existing.map((e) => e.key));
  let key = keys.has('code-review') ? 'entrypoint' : 'code-review';
  let i = 2;
  while (keys.has(key)) key = `entrypoint-${i++}`;
  return { key, workflowId, mode: 'BLOCKING', uploadDiff: true, description: null };
}
