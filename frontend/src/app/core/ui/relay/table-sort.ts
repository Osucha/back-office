import { computed, inject, signal, type Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { compareText } from '../../i18n/compare';

/** NG-ZORRO's sort order of a column header. */
export type SortOrder = 'ascend' | 'descend' | null;

/** What a column sorts by: a value read from the row (text, number, date string, boolean; null and empty sort last). */
export type SortAccessor<T> = (row: T) => unknown;

/** Text compares by the UI locale (Polish letters in place), numbers numerically, booleans true first; empty last. */
export function compareValues(a: unknown, b: unknown): number {
  const emptyA = a === null || a === undefined || a === '' || (typeof a === 'number' && Number.isNaN(a));
  const emptyB = b === null || b === undefined || b === '' || (typeof b === 'number' && Number.isNaN(b));
  if (emptyA || emptyB) return emptyA === emptyB ? 0 : emptyA ? 1 : -1;
  if (typeof a === 'number' && typeof b === 'number') return a - b;
  if (typeof a === 'boolean' && typeof b === 'boolean') return a === b ? 0 : a ? -1 : 1;
  if (a instanceof Date && b instanceof Date) return a.getTime() - b.getTime();
  return compareText(String(a), String(b));
}

/** Rows ordered by [accessor]; stable, empty values last in both directions. */
export function sortRows<T>(rows: readonly T[], accessor: SortAccessor<T>, order: SortOrder): T[] {
  if (!order) return [...rows];
  const sign = order === 'descend' ? -1 : 1;
  return rows
    .map((row, index) => ({ row, index, value: accessor(row) }))
    .sort((x, y) => {
      const emptyX = x.value === null || x.value === undefined || x.value === '';
      const emptyY = y.value === null || y.value === undefined || y.value === '';
      if (emptyX !== emptyY) return emptyX ? 1 : -1;
      return sign * compareValues(x.value, y.value) || x.index - y.index;
    })
    .map((x) => x.row);
}

/** `name.asc` / `name.desc` of the query parameter; null for anything else. */
export function parseSort(raw: string | null | undefined, keys: readonly string[]): { key: string; order: SortOrder } | null {
  const m = /^(.+)\.(asc|desc)$/.exec((raw ?? '').trim());
  if (!m || !keys.includes(m[1])) return null;
  return { key: m[1], order: m[2] === 'asc' ? 'ascend' : 'descend' };
}

/** What a sortable header needs, whatever the row type. */
export interface SortControl {
  /** The header's order of [key] (for `[nzSortOrder]`). */
  order(key: string): SortOrder;
  /** From a header click; `null` goes back to the list's own order. */
  set(key: string, order: SortOrder): void;
}

export interface TableSort<T> extends SortControl {
  /** The column sorted now and its direction (none = the list's own order). */
  readonly state: Signal<{ key: string; order: SortOrder } | null>;
  /** [rows] in the chosen order; give the same list to the table and to keyboard navigation. */
  apply(rows: readonly T[]): T[];
}

/**
 * Column sorting kept in the URL (`?sort=name.asc`, Relay: "state reflected in the URL"); one per table. With
 * [options.param] two tables of one page keep separate state. Call in an injection context. Without a router
 * (component tests) the state only lives in memory.
 *
 *   readonly sort = tableSort<Profile>({ name: (p) => p.displayName, price: (p) => p.pricing.inputPerMTok });
 *   readonly rows = computed(() => this.sort.apply(this.filtered()));
 *   <th nzShowSort [boSort]="sort" boSortKey="name">Name</th>
 */
export function tableSort<T>(columns: Record<string, SortAccessor<T>>, options: { param?: string } = {}): TableSort<T> {
  const param = options.param ?? 'sort';
  const keys = Object.keys(columns);
  const route = inject(ActivatedRoute, { optional: true });
  const router = inject(Router, { optional: true });
  const local = signal<string | null>(null);
  const params = route ? toSignal(route.queryParamMap, { initialValue: route.snapshot.queryParamMap }) : null;
  const raw = computed(() => (params ? params().get(param) : local()));
  const state = computed(() => parseSort(raw(), keys));

  return {
    state,
    order: (key) => (state()?.key === key ? state()!.order : null),
    set: (key, order) => {
      const current = state();
      // a header that resets itself because another column was clicked must not undo that click
      if (!order && current && current.key !== key) return;
      const value = order ? `${key}.${order === 'ascend' ? 'asc' : 'desc'}` : null;
      if (value === raw()) return;
      if (router && route) {
        void router.navigate([], { relativeTo: route, queryParams: { [param]: value }, queryParamsHandling: 'merge', replaceUrl: true });
      } else {
        local.set(value);
      }
    },
    apply: (rows) => {
      const s = state();
      return s ? sortRows(rows, columns[s.key], s.order) : [...rows];
    },
  };
}
