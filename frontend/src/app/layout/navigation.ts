import type { AccessRule } from '../core/auth/permissions';

export interface NavChild {
  path: string;
  label: string;
  access?: AccessRule;
}

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  exact?: boolean;
  access?: AccessRule;
  /** Sub-pages, shown under the item in the expanded rail while the section is active. */
  children?: NavChild[];
  /** Rail placement: the main list, or the bottom (Administration, Settings). */
  place?: 'main' | 'bottom';
  /** Palette keywords (translated; see `keywords()`). */
  keywords?: string[];
}

/** "a, b, c" -> ['a', 'b', 'c']: one translatable message per entry keeps translators in context. */
function keywords(list: string): string[] {
  return list
    .split(',')
    .map((k) => k.trim())
    .filter(Boolean);
}

/** The full menu; entries mirror the `data` access rules of `app.routes.ts`. */
export const NAVIGATION: NavItem[] = [
  {
    path: '/home',
    label: $localize`:@@shell.nav.home:Home`,
    icon: 'home',
    keywords: keywords($localize`:@@shell.nav.home.keywords:dashboard, start`),
  },
  {
    path: '/inbox',
    label: $localize`:@@shell.nav.inbox:Your turn`,
    icon: 'inbox',
    keywords: keywords($localize`:@@shell.nav.inbox.keywords:inbox, approvals, gates`),
  },
  {
    path: '/my-work',
    label: $localize`:@@shell.nav.myWork:My work`,
    icon: 'user',
    access: { permission: 'connections.personal' },
    keywords: keywords(
      $localize`:@@shell.nav.myWork.keywords:my jira issues, proposals, personal intake, automation`,
    ),
  },
  { path: '/projects', label: $localize`:@@shell.nav.projects:Projects`, icon: 'project' },
  {
    path: '/runs',
    label: $localize`:@@shell.nav.runs:Runs`,
    icon: 'play-circle',
    keywords: keywords($localize`:@@shell.nav.runs.keywords:executions, workflow runs`),
  },
  {
    path: '/insights',
    label: $localize`:@@shell.nav.insights:Insights`,
    icon: 'line-chart',
    access: { projectPermission: 'project.view' },
    keywords: keywords(
      $localize`:@@shell.nav.insights.keywords:delivery, lead time, human wait, bottleneck, metrics`,
    ),
  },
  {
    path: '/ci',
    label: $localize`:@@shell.nav.ci:CI`,
    icon: 'cloud-sync',
    keywords: keywords($localize`:@@shell.nav.ci.keywords:code review, pipelines`),
  },
  {
    path: '/library',
    label: $localize`:@@shell.nav.library:Library`,
    icon: 'book',
    children: [
      {
        path: '/library/templates',
        label: $localize`:@@shell.nav.templates:Templates`,
        access: { permission: 'library.view' },
      },
      {
        path: '/library/interfaces',
        label: $localize`:@@shell.nav.interfaces:Interfaces`,
        access: { permission: 'library.view' },
      },
      {
        path: '/library/skills',
        label: $localize`:@@shell.nav.skills:Skills`,
        access: { permission: 'library.view' },
      },
      {
        path: '/library/sources',
        label: $localize`:@@shell.nav.librarySources:Sources`,
        access: { permission: 'library.view' },
      },
      {
        path: '/library/workflows',
        label: $localize`:@@shell.nav.libraryWorkflows:Workflows`,
        access: { permission: 'library.view' },
      },
    ],
  },
  {
    path: '/models',
    label: $localize`:@@shell.nav.models:Models`,
    icon: 'robot',
    access: { permission: 'models.view' },
    keywords: keywords($localize`:@@shell.nav.models.keywords:model profiles, llm`),
  },
  {
    path: '/evaluation',
    label: $localize`:@@shell.nav.evaluation:Evaluation`,
    icon: 'experiment',
    children: [
      {
        path: '/evaluation/suites',
        label: $localize`:@@shell.nav.suites:Suites`,
        access: { permission: 'evaluation.view' },
      },
      {
        path: '/evaluation/benchmarks',
        label: $localize`:@@shell.nav.benchmarks:Benchmarks`,
        access: { permission: 'evaluation.view' },
      },
    ],
  },
  {
    path: '/costs',
    label: $localize`:@@shell.nav.costs:Costs`,
    icon: 'dollar',
    access: { permission: 'usage.view', projectPermission: 'project.usage.view' },
    keywords: keywords($localize`:@@shell.nav.costs.keywords:usage, spend, tokens`),
  },
  {
    path: '/admin',
    label: $localize`:@@shell.nav.admin:Administration`,
    icon: 'safety-certificate',
    place: 'bottom',
    children: [
      {
        path: '/admin/users',
        label: $localize`:@@shell.nav.users:Users`,
        access: { permission: 'admin.users.view' },
      },
      {
        path: '/admin/groups',
        label: $localize`:@@shell.nav.groups:Groups`,
        access: { permission: 'admin.users.view' },
      },
      {
        path: '/admin/roles',
        label: $localize`:@@shell.nav.roles:Roles`,
        access: { permission: ['admin.roles.manage', 'admin.users.view'] },
      },
      {
        path: '/admin/grants',
        label: $localize`:@@shell.nav.grants:Grants`,
        access: { permission: ['admin.roles.manage', 'admin.users.view'] },
      },
      {
        path: '/admin/settings',
        label: $localize`:@@shell.nav.platformSettings:Platform settings`,
        access: { permission: 'admin.settings.manage' },
      },
      {
        // least-privilege Jira credentials (ADR 0030): shown to those who manage them
        path: '/admin/connections',
        label: $localize`:@@shell.nav.connections:Connections`,
        access: { permission: 'admin.connections.manage' },
      },
      {
        path: '/admin/fleet',
        label: $localize`:@@shell.nav.fleet:Runner fleet`,
        access: { permission: ['fleet.view', 'fleet.manage'] },
      },
      {
        path: '/admin/operations',
        label: $localize`:@@shell.nav.operations:Operations`,
        access: { permission: 'admin.settings.manage' },
      },
      {
        path: '/admin/audit',
        label: $localize`:@@shell.nav.audit:Audit log`,
        access: { permission: 'admin.audit.view' },
      },
    ],
  },
  {
    path: '/settings',
    label: $localize`:@@shell.nav.settings:Settings`,
    icon: 'setting',
    place: 'bottom',
    access: { permission: 'integrations.view' },
    keywords: keywords($localize`:@@shell.nav.settings.keywords:integrations, provider keys`),
  },
];

/**
 * The menu for one user: entries without access are dropped, and so is a group whose children are
 * all dropped (an empty "Administration" is never shown). A group links to its first visible child.
 */
export function visibleNavigation(
  items: readonly NavItem[],
  allows: (rule: AccessRule) => boolean,
): NavItem[] {
  const out: NavItem[] = [];
  for (const item of items) {
    if (item.access && !allows(item.access)) continue;
    if (item.children) {
      const children = item.children.filter((c) => !c.access || allows(c.access));
      if (!children.length) continue;
      out.push({ ...item, children });
    } else {
      out.push(item);
    }
  }
  return out;
}

/** Where a rail item goes: itself, or its first visible child. */
export function navTarget(item: NavItem): string {
  return item.children?.[0]?.path ?? item.path;
}
