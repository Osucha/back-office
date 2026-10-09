/**
 * Who writes a confirmed Jira write (ADR 0035), pure logic for the write-confirmation dialog: the
 * step's system (or pinned) connection, or the approver's own personal connection. Covered by
 * write-identity.spec.ts.
 */
import type { WriteIdentity } from '../../../core/api/types';

export type Identity = 'SYSTEM' | 'PERSONAL';

/** The identity selected first: the step's default when usable, else the other usable one. */
export function initialIdentity(o: WriteIdentity): Identity | null {
  const usable = usableIdentities(o);
  if (usable.includes(o.defaultIdentity as Identity)) return o.defaultIdentity as Identity;
  return usable[0] ?? null;
}

/** Identities the caller can approve with: allowed by the step and actually available to them. */
export function usableIdentities(o: WriteIdentity): Identity[] {
  const out: Identity[] = [];
  if (o.personalAllowed && o.personalConnectionName) out.push('PERSONAL');
  if (o.systemAllowed) out.push('SYSTEM');
  return out;
}

/** Why approving is impossible for the caller, or null. */
export function approveBlocker(o: WriteIdentity | null, chosen: Identity | null): string | null {
  if (!o) return null;
  if (!usableIdentities(o).length)
    return (
      o.personalProblem ??
      $localize`:@@inbox.write.identity.none:Neither your personal connection nor the system connection can make this write`
    );
  if (!chosen) return $localize`:@@inbox.write.identity.pick:Choose who writes`;
  return null;
}
