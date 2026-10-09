/**
 * Editing the platform grants of one level/target (`GrantSet`): roles plus per-permission
 * overrides. In the editor every permission has one of three states; "inherit" means no grant on
 * this level (the decision falls through to the next level up), which is represented by the
 * permission being absent from `grants`.
 */
import type { GrantLevel, GrantSet } from '../../../core/api/types';
import { compareKeys } from '../../../core/i18n/compare';

export type OverrideState = 'inherit' | 'allow' | 'deny';

export const OVERRIDE_OPTIONS: ReadonlyArray<{ value: OverrideState; label: string }> = [
  { value: 'inherit', label: $localize`:@@admin.grants.option.inherit:(inherit)` },
  { value: 'allow', label: $localize`:@@admin.grants.option.allow:Allow` },
  { value: 'deny', label: $localize`:@@admin.grants.option.deny:Deny` },
];

export type OverrideMap = Readonly<Record<string, OverrideState>>;

/** GrantSet -> permission -> state (permissions without a grant are left out = inherit). */
export function toOverrides(set: GrantSet | null | undefined): Record<string, OverrideState> {
  const map: Record<string, OverrideState> = {};
  for (const g of set?.grants ?? []) {
    if (!g.permission) continue;
    map[g.permission] = g.effect === 'DENY' ? 'deny' : 'allow';
  }
  return map;
}

export function overrideOf(map: OverrideMap, permission: string): OverrideState {
  return map[permission] ?? 'inherit';
}

/** Sets one permission's state; `inherit` removes the entry. */
export function setOverride(
  map: OverrideMap,
  permission: string,
  state: OverrideState,
): Record<string, OverrideState> {
  const next = { ...map };
  if (state === 'inherit') delete next[permission];
  else next[permission] = state;
  return next;
}

/** Editor state -> the `GrantSet` for `PUT /admin/grants` (sorted, inherit omitted). */
export function toGrantSet(
  level: GrantLevel,
  targetId: string | null,
  roles: readonly string[],
  map: OverrideMap,
): GrantSet {
  return {
    level,
    targetId: level === 'SYSTEM' ? null : targetId,
    roles: [...new Set(roles)].sort(compareKeys),
    grants: Object.entries(map)
      .filter(([, state]) => state !== 'inherit')
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([permission, state]) => ({
        permission,
        effect: state === 'deny' ? ('DENY' as const) : ('ALLOW' as const),
      })),
  };
}

/** Structural equality used for the "unsaved changes" state. */
export function sameGrantSet(a: GrantSet, b: GrantSet): boolean {
  const norm = (s: GrantSet) =>
    JSON.stringify(toGrantSet(s.level, s.targetId ?? null, s.roles ?? [], toOverrides(s)));
  return norm(a) === norm(b);
}

/** The set for a target from `GET /admin/grants` (empty set when nothing is granted yet). */
export function pickGrantSet(
  sets: readonly GrantSet[],
  level: GrantLevel,
  targetId: string | null,
): GrantSet {
  const found = sets.find(
    (s) => s.level === level && (level === 'SYSTEM' || (s.targetId ?? null) === targetId),
  );
  return found ?? { level, targetId: level === 'SYSTEM' ? null : targetId, roles: [], grants: [] };
}
