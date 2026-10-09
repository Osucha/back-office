/**
 * The "System | Groups | User | Effective | Source" table of a user's platform permissions
 * (`GET /admin/users/{id}/effective-permissions`), grouped by module from the catalogue.
 * Resolution itself is done by the server; this only shapes the answer for display.
 */
import type {
  EffectivePermissionRow,
  EffectivePermissions,
  LevelEffect,
  PermissionDefinition,
} from '../../../core/api/types';
import { permissionDescription } from '../../../core/i18n/catalog-labels';

export interface EffectiveRow {
  permission: string;
  description: string;
  module: string;
  system: LevelEffect;
  /** One entry per group of the user that has a decision (ALLOW/DENY) for the permission. */
  groups: Array<{ name: string; effect: LevelEffect }>;
  user: LevelEffect;
  effective: boolean;
  source: string;
}

export interface EffectiveModule {
  module: string;
  rows: EffectiveRow[];
  /** Number of effectively granted permissions in the module. */
  granted: number;
}

/** Text shown in a level column: "Allow", "Deny" or "-" (no grant on that level). */
export function effectLabel(effect: LevelEffect | null | undefined): string {
  if (effect === 'ALLOW') return $localize`:@@admin.effective.label.allow:Allow`;
  if (effect === 'DENY') return $localize`:@@admin.effective.label.deny:Deny`;
  return '-';
}

export type EffectKind = 'allow' | 'deny' | 'inherit';

/** Baton-like chip of a grant decision: allow = done (check), deny = blocked (octagon), none = idle. */
export function effectChip(effect: LevelEffect | null | undefined): {
  kind: EffectKind;
  holder: 'done' | 'blocked' | 'idle';
  label: string;
} {
  if (effect === 'ALLOW')
    return { kind: 'allow', holder: 'done', label: $localize`:@@admin.effective.chip.allow:allow` };
  if (effect === 'DENY')
    return { kind: 'deny', holder: 'blocked', label: $localize`:@@admin.effective.chip.deny:deny` };
  return {
    kind: 'inherit',
    holder: 'idle',
    label: $localize`:@@admin.effective.chip.inherit:inherit`,
  };
}

/** The Effective column: granted, denied by an explicit DENY, or not granted anywhere. */
export function effectiveChip(
  row: Pick<EffectiveRow, 'effective' | 'system' | 'user' | 'groups'>,
): {
  holder: 'done' | 'blocked' | 'idle';
  label: string;
} {
  if (row.effective)
    return { holder: 'done', label: $localize`:@@admin.effective.allowed:allowed` };
  const denied =
    row.user === 'DENY' || row.system === 'DENY' || row.groups.some((g) => g.effect === 'DENY');
  return denied
    ? { holder: 'blocked', label: $localize`:@@admin.effective.denied:denied` }
    : { holder: 'idle', label: $localize`:@@admin.effective.notGranted:not granted` };
}

/** Source column: `USER`, `GROUP:Architects`, `SYSTEM` or "no grant". */
export function sourceLabel(row: Pick<EffectiveRow, 'source' | 'effective'>): string {
  const s = row.source?.trim();
  if (!s || s === 'NONE')
    return row.effective
      ? ''
      : $localize`:@@admin.effective.source.none:no grant (denied by default)`;
  if (s.startsWith('GROUP:')) {
    const group = s.slice('GROUP:'.length);
    return $localize`:@@admin.effective.source.group:Group ${group}:group:`;
  }
  if (s === 'USER') return $localize`:@@admin.effective.source.user:User`;
  if (s === 'SYSTEM') return $localize`:@@admin.effective.source.system:System`;
  return s;
}

export function toEffectiveRow(
  row: EffectivePermissionRow,
  catalogue: ReadonlyMap<string, PermissionDefinition>,
): EffectiveRow {
  const def = row.permission ? catalogue.get(row.permission) : undefined;
  const serverText = row.description || def?.description || '';
  return {
    permission: row.permission ?? '',
    description: row.permission ? permissionDescription(row.permission, serverText) : serverText,
    module: def?.module || moduleOf(row.permission ?? ''),
    system: row.system ?? 'NONE',
    groups: (row.groups ?? [])
      .filter((g) => g.effect && g.effect !== 'NONE')
      .map((g) => ({ name: g.groupName ?? g.groupId ?? '?', effect: g.effect ?? 'NONE' })),
    user: row.user ?? 'NONE',
    effective: row.effective === true,
    source: row.source ?? '',
  };
}

/** Rows grouped by module (catalogue order of modules, then alphabetical permissions). */
export function groupEffective(
  data: EffectivePermissions | null | undefined,
  catalogue: readonly PermissionDefinition[] = [],
): EffectiveModule[] {
  const byKey = new Map(catalogue.filter((p) => p.key).map((p) => [p.key!, p]));
  const modules = new Map<string, EffectiveRow[]>();
  for (const raw of data?.rows ?? []) {
    const row = toEffectiveRow(raw, byKey);
    if (!row.permission) continue;
    const list = modules.get(row.module) ?? [];
    list.push(row);
    modules.set(row.module, list);
  }
  const order = [...new Set(catalogue.map((p) => p.module ?? ''))];
  return [...modules.entries()]
    .sort(([a], [b]) => rank(order, a) - rank(order, b) || a.localeCompare(b))
    .map(([module, rows]) => ({
      module,
      rows: rows.sort((a, b) => a.permission.localeCompare(b.permission)),
      granted: rows.filter((r) => r.effective).length,
    }));
}

/** `admin.users.view` -> `admin` (fallback when the catalogue is not loaded). */
export function moduleOf(permission: string): string {
  const i = permission.indexOf('.');
  return i > 0 ? permission.slice(0, i) : permission;
}

function rank(order: string[], module: string): number {
  const i = order.indexOf(module);
  return i < 0 ? Number.MAX_SAFE_INTEGER : i;
}
