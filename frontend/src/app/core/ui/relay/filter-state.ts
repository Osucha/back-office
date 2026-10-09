import { computed, inject, type Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';

/** Query-parameter kinds of a filter bar: a single value or a comma-separated list. */
export type FilterKind = 'string' | 'list';
export type FilterSpec = Record<string, FilterKind>;
export type FilterValues<S extends FilterSpec> = {
  [K in keyof S]: S[K] extends 'list' ? string[] : string | null;
};

/** Reads filter values from query parameters (unknown keys ignored, empty values dropped). */
export function parseFilters<S extends FilterSpec>(
  spec: S,
  params: Readonly<Record<string, string | null | undefined>>,
): FilterValues<S> {
  const out: Record<string, unknown> = {};
  for (const [key, kind] of Object.entries(spec)) {
    const raw = (params[key] ?? '').trim();
    out[key] =
      kind === 'list'
        ? raw
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean)
        : raw || null;
  }
  return out as FilterValues<S>;
}

/** Query parameters for `router.navigate` (`null` removes a parameter). */
export function serializeFilters<S extends FilterSpec>(
  spec: S,
  values: Partial<FilterValues<S>>,
): Record<string, string | null> {
  const out: Record<string, string | null> = {};
  for (const [key, kind] of Object.entries(spec)) {
    if (!(key in values)) continue;
    const v = (values as Record<string, unknown>)[key];
    if (kind === 'list') {
      const list = (v as string[] | null | undefined) ?? [];
      out[key] = list.length ? list.join(',') : null;
    } else {
      const s = ((v as string | null | undefined) ?? '').trim();
      out[key] = s || null;
    }
  }
  return out;
}

export function hasActiveFilters<S extends FilterSpec>(spec: S, values: FilterValues<S>): boolean {
  return Object.entries(spec).some(([key, kind]) => {
    const v = (values as Record<string, unknown>)[key];
    return kind === 'list' ? (v as string[]).length > 0 : !!v;
  });
}

export interface UrlFilters<S extends FilterSpec> {
  values: Signal<FilterValues<S>>;
  active: Signal<boolean>;
  /** Merges into the URL (replaceUrl, other query params kept). */
  set(patch: Partial<FilterValues<S>>): void;
  clear(): void;
}

/**
 * Filter state that lives in the URL (Relay: "state reflected in the URL"), so back/forward and
 * shared links restore the list. Call in an injection context (component field initialiser).
 *
 *   readonly filters = urlFilters({ q: 'string', holder: 'list', projectId: 'string' });
 *   this.filters.values().holder   // ['agent', 'you']
 *   this.filters.set({ q: 'SHOP-12' });
 */
export function urlFilters<S extends FilterSpec>(spec: S): UrlFilters<S> {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const params = toSignal(route.queryParamMap, { initialValue: route.snapshot.queryParamMap });
  const values = computed(() => {
    const p = params();
    const flat: Record<string, string | null> = {};
    for (const k of Object.keys(spec)) flat[k] = p.get(k);
    return parseFilters(spec, flat);
  });
  const navigate = (queryParams: Record<string, string | null>) =>
    void router.navigate([], {
      relativeTo: route,
      queryParams,
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  return {
    values,
    active: computed(() => hasActiveFilters(spec, values())),
    set: (patch) => navigate(serializeFilters(spec, patch)),
    clear: () =>
      navigate(Object.fromEntries(Object.keys(spec).map((k) => [k, null])) as Record<string, null>),
  };
}
