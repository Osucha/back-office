/**
 * The permission matrix of the role editor: the catalogue (`GET /admin/permissions`) of one scope,
 * grouped by module, with whole-module toggles.
 */
import type { PermissionDefinition, PermissionScope } from '../../../core/api/types';
import { compareKeys } from '../../../core/i18n/compare';

export interface MatrixModule {
  module: string;
  permissions: PermissionDefinition[];
}

export type ModuleState = 'all' | 'some' | 'none';

export function matrixFor(
  catalogue: readonly PermissionDefinition[],
  scope: PermissionScope,
): MatrixModule[] {
  const modules = new Map<string, PermissionDefinition[]>();
  for (const p of catalogue) {
    if (!p.key || p.scope !== scope) continue;
    const m = p.module || $localize`:@@admin.matrix.otherModule:other`;
    const list = modules.get(m) ?? [];
    list.push(p);
    modules.set(m, list);
  }
  return [...modules.entries()].map(([module, permissions]) => ({
    module,
    permissions: [...permissions].sort((a, b) => a.key!.localeCompare(b.key!)),
  }));
}

export function moduleState(module: MatrixModule, selected: ReadonlySet<string>): ModuleState {
  const n = module.permissions.filter((p) => selected.has(p.key!)).length;
  if (n === 0) return 'none';
  return n === module.permissions.length ? 'all' : 'some';
}

export function togglePermission(
  selected: ReadonlySet<string>,
  key: string,
  on: boolean,
): Set<string> {
  const next = new Set(selected);
  if (on) next.add(key);
  else next.delete(key);
  return next;
}

export function toggleModule(
  selected: ReadonlySet<string>,
  module: MatrixModule,
  on: boolean,
): Set<string> {
  const next = new Set(selected);
  for (const p of module.permissions) {
    if (on) next.add(p.key!);
    else next.delete(p.key!);
  }
  return next;
}

/** Keys selected but not in the scope's catalogue (unknown to the server or of another scope). */
export function unknownPermissions(
  selected: ReadonlySet<string>,
  catalogue: readonly PermissionDefinition[],
  scope: PermissionScope,
): string[] {
  const known = new Set(catalogue.filter((p) => p.scope === scope).map((p) => p.key));
  return [...selected].filter((k) => !known.has(k)).sort(compareKeys);
}
