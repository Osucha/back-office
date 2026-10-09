import { Injectable, inject, signal } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';

export interface Crumb {
  label: string;
  /** Router link; the last crumb usually has none. */
  link?: unknown[] | string;
  /** Render as an id / key (monospace). */
  mono?: boolean;
}

/**
 * Breadcrumb in the top bar. The shell derives a default from the URL and the route title; pages
 * that know entity names replace it once loaded:
 *
 *   inject(BreadcrumbService).set([{ label: 'Projects', link: '/projects' }, { label: p.key, mono: true }]);
 *
 * Page crumbs are dropped when the path changes (query-only navigations such as filters keep them).
 */
@Injectable({ providedIn: 'root' })
export class BreadcrumbService {
  readonly crumbs = signal<Crumb[] | null>(null);
  private path = '';

  constructor() {
    const router = inject(Router);
    this.path = pathOf(router.url);
    router.events.subscribe((e) => {
      if (!(e instanceof NavigationStart)) return;
      const next = pathOf(e.url);
      if (next !== this.path) {
        this.path = next;
        this.crumbs.set(null);
      }
    });
  }

  set(crumbs: Crumb[]): void {
    this.crumbs.set(crumbs);
  }
}

function pathOf(url: string): string {
  return url.split(/[?#]/)[0];
}

/** Section names for the default breadcrumb, by first path segment. */
export const SECTION_LABELS: Record<string, string> = {
  home: $localize`:@@core.section.home:Home`,
  inbox: $localize`:@@core.section.inbox:Your turn`,
  projects: $localize`:@@core.section.projects:Projects`,
  workflows: $localize`:@@core.section.workflows:Workflows`,
  runs: $localize`:@@core.section.runs:Runs`,
  ci: $localize`:@@core.section.ci:CI`,
  library: $localize`:@@core.section.library:Library`,
  models: $localize`:@@core.section.models:Models`,
  evaluation: $localize`:@@core.section.evaluation:Evaluation`,
  costs: $localize`:@@core.section.costs:Costs`,
  insights: $localize`:@@core.section.insights:Insights`,
  settings: $localize`:@@core.section.settings:Settings`,
  account: $localize`:@@core.section.account:Account`,
  admin: $localize`:@@core.section.admin:Administration`,
  help: $localize`:@@core.section.help:Manual`,
  forbidden: $localize`:@@core.section.forbidden:Access denied`,
};
