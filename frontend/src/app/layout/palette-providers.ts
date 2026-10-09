import { DestroyRef, inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, map, of, shareReplay, timer, switchMap, type Observable } from 'rxjs';
import { AdminApi } from '../core/api/admin.service';
import { RunsApi } from '../core/api/runs.service';
import { TaskLibraryApi } from '../core/api/task-library.service';
import type { Project, WorkflowDefinition, WorkflowRunSummary } from '../core/api/types';
import { WorkflowsApi } from '../core/api/workflows.service';
import { IdentityService } from '../core/auth/identity.service';
import { AuthConfigService } from '../core/auth/auth-config.service';
import { HelpService } from '../core/help/help.service';
import { allChapters, humanizeAnchor, manualLink, type ManualManifest } from '../core/help/manual';
import { ProjectsStore } from '../core/state/projects.store';
import { DensityService } from '../core/ui/density.service';
import { batonOf } from '../core/ui/relay/baton';
import {
  CommandPaletteService,
  type PaletteItem,
  type PaletteProvider,
} from '../core/ui/relay/command-palette.service';
import { ShortcutsService } from '../core/ui/relay/shortcuts.service';
import { primaryRepository } from '../core/ui/repositories';
import { ThemeService } from '../core/ui/theme.service';
import { NAVIGATION, visibleNavigation } from './navigation';

/** "a, b, c" -> ['a', 'b', 'c'] (keywords are one translatable message per entry). */
function keywords(list: string): string[] {
  return list
    .split(',')
    .map((k) => k.trim())
    .filter(Boolean);
}

const chapterKeywords = () => keywords($localize`:@@shell.palette.chapter.keywords:help, manual`);

/** Short-lived cache for remote palette lists (the palette may open many times a minute). */
function cached<T>(load: () => Observable<T>, ttlMs = 30_000): () => Observable<T> {
  let at = 0;
  let value$: Observable<T> | null = null;
  return () => {
    if (!value$ || Date.now() - at > ttlMs) {
      at = Date.now();
      value$ = load().pipe(shareReplay({ bufferSize: 1, refCount: false }));
    }
    return value$;
  };
}

interface ActionDeps {
  identity: IdentityService;
  projects: ProjectsStore;
  authConfig: AuthConfigService;
  theme: ThemeService;
  density: DensityService;
  shortcuts: ShortcutsService;
}

/** `items` when `ok`, nothing otherwise (permission-filtered palette actions). */
function when(ok: boolean, ...items: PaletteItem[]): PaletteItem[] {
  return ok ? items : [];
}

/** The "Actions" group: what the user may do here, then account and display preferences. */
function actionItems(deps: ActionDeps): PaletteItem[] {
  const { identity, projects, authConfig, theme, density, shortcuts } = deps;
  return [
    ...when(identity.canInProject('run.start'), {
      id: 'action:start-run',
      title: $localize`:@@shell.palette.startRun.title:Start run…`,
      subtitle: projects.current()
        ? $localize`:@@shell.palette.startRun.subtitleProject:Workflows of ${projects.current()!.key}:project:`
        : $localize`:@@shell.palette.startRun.subtitle:Pick a workflow`,
      icon: 'caret-right',
      keywords: keywords($localize`:@@shell.palette.startRun.keywords:run, execute, workflow`),
      scope: {
        id: 'start-run',
        placeholder: $localize`:@@shell.palette.startRun.placeholder:Start run: pick a workflow`,
      },
    }),
    ...when(identity.can('projects.create'), {
      id: 'action:create-project',
      title: $localize`:@@shell.palette.createProject.title:Create project`,
      icon: 'plus',
      link: '/projects?create=1',
      keywords: keywords($localize`:@@shell.palette.createProject.keywords:new project`),
    }),
    ...when(identity.can('admin.users.manage'), {
      id: 'action:invite-user',
      title: $localize`:@@shell.palette.inviteUser.title:Invite user`,
      icon: 'user-add',
      link: '/admin/users?invite=1',
      keywords: keywords($localize`:@@shell.palette.inviteUser.keywords:add user, people`),
    }),
    ...when(identity.can('admin.users.manage') && authConfig.config()?.methods?.local !== false, {
      id: 'action:invite-local-user',
      title: $localize`:@@shell.palette.inviteLocalUser.title:Invite local user`,
      subtitle: $localize`:@@shell.palette.inviteLocalUser.subtitle:E-mail and password; you hand over a set-password link`,
      icon: 'user-add',
      link: '/admin/users?invite=local',
      keywords: keywords(
        $localize`:@@shell.palette.inviteLocalUser.keywords:local account, password, add user`,
      ),
    }),
    ...when(identity.can('admin.audit.view'), {
      id: 'action:audit-log',
      title: $localize`:@@shell.palette.auditLog.title:Open audit log`,
      icon: 'file-search',
      link: '/admin/audit',
      keywords: keywords(
        $localize`:@@shell.palette.auditLog.keywords:audit, history, sign-ins, security`,
      ),
    }),
    ...when(identity.can(['fleet.view', 'fleet.manage']), {
      id: 'action:open-fleet',
      title: $localize`:@@shell.palette.openFleet.title:Open fleet`,
      subtitle: $localize`:@@shell.palette.openFleet.subtitle:Runner pools, runners and agent jobs`,
      icon: 'cluster',
      link: '/admin/fleet/pools',
      keywords: keywords(
        $localize`:@@shell.palette.openFleet.keywords:runners, pools, sandbox, agent jobs`,
      ),
    }),
    ...when(identity.can('fleet.manage'), {
      id: 'action:add-runner',
      title: $localize`:@@shell.palette.addRunner.title:Add runner`,
      subtitle: $localize`:@@shell.palette.addRunner.subtitle:Registration token and chart values for a pool`,
      icon: 'plus',
      link: '/admin/fleet/pools?addRunner=1',
      keywords: keywords(
        $localize`:@@shell.palette.addRunner.keywords:register runner, runner-controller, helm`,
      ),
    }),
    ...when(identity.canInProject('project.view'), {
      id: 'action:open-insights',
      title: $localize`:@@shell.palette.openInsights.title:Open insights`,
      subtitle: $localize`:@@shell.palette.openInsights.subtitle:Human wait, lead time, bottlenecks`,
      icon: 'line-chart',
      link: '/insights',
      keywords: keywords(
        $localize`:@@shell.palette.openInsights.keywords:delivery, metrics, lead time`,
      ),
    }),
    ...when(identity.canInProject('project.manage'), {
      id: 'action:set-budget',
      title: $localize`:@@shell.palette.setBudget.title:Set project budget…`,
      subtitle: projects.current()
        ? $localize`:@@shell.palette.setBudget.subtitleProject:Monthly budget of ${projects.current()!.key}:project:`
        : $localize`:@@shell.palette.setBudget.subtitle:Pick a project`,
      icon: 'dollar',
      keywords: keywords(
        $localize`:@@shell.palette.setBudget.keywords:budget, limit, spend, cost cap`,
      ),
      scope: {
        id: 'set-budget',
        placeholder: $localize`:@@shell.palette.setBudget.placeholder:Set budget: pick a project`,
      },
    }),
    ...when(identity.can('admin.connections.manage'), {
      id: 'action:open-connections',
      title: $localize`:@@shell.palette.openConnections.title:Open connections`,
      subtitle: $localize`:@@shell.palette.openConnections.subtitle:Least-privilege Jira credentials of the platform`,
      icon: 'api',
      link: '/admin/connections',
      keywords: keywords(
        $localize`:@@shell.palette.openConnections.keywords:jira, credentials, api token, reader, writer`,
      ),
    }),
    ...when(identity.can('admin.settings.manage'), {
      id: 'action:open-operations',
      title: $localize`:@@shell.palette.openOperations.title:Open operations`,
      subtitle: $localize`:@@shell.palette.openOperations.subtitle:Replicas, work queue, dead items`,
      icon: 'dashboard',
      link: '/admin/operations',
      keywords: keywords(
        $localize`:@@shell.palette.openOperations.keywords:replicas, queue, dead letter, health`,
      ),
    }),
    ...when(identity.can('library.manage') || identity.canInProject('library.project.manage'), {
      id: 'action:create-template',
      title: $localize`:@@shell.palette.createTemplate.title:Create template`,
      subtitle: $localize`:@@shell.palette.createTemplate.subtitle:Task library`,
      icon: 'plus',
      link: '/library/templates?create=1',
      keywords: keywords(
        $localize`:@@shell.palette.createTemplate.keywords:task template, new template, library`,
      ),
    }),
    ...when(identity.can('evaluation.run'), {
      id: 'action:new-benchmark',
      title: $localize`:@@shell.palette.newBenchmark.title:New benchmark`,
      subtitle: $localize`:@@shell.palette.newBenchmark.subtitle:Compare models on a task suite`,
      icon: 'experiment',
      link: '/evaluation/benchmarks?create=1',
      keywords: keywords(
        $localize`:@@shell.palette.newBenchmark.keywords:benchmark, evaluation, compare models`,
      ),
    }),
    ...when(
      authConfig.config()?.methods?.local !== false,
      {
        id: 'action:change-password',
        title: $localize`:@@shell.palette.changePassword.title:Change my password`,
        subtitle: $localize`:@@shell.palette.changePassword.subtitle:Local sign-in`,
        icon: 'lock',
        link: '/account?section=security',
        keywords: keywords(
          $localize`:@@shell.palette.changePassword.keywords:password, security, account`,
        ),
      },
      {
        id: 'action:setup-mfa',
        title: $localize`:@@shell.palette.setupMfa.title:Set up MFA`,
        subtitle: $localize`:@@shell.palette.setupMfa.subtitle:Two-factor authentication with an authenticator app`,
        icon: 'safety-certificate',
        link: '/account?section=security&setup=mfa',
        keywords: keywords(
          $localize`:@@shell.palette.setupMfa.keywords:two-factor, 2fa, totp, authenticator, recovery codes`,
        ),
      },
    ),
    ...[
      {
        id: 'action:toggle-theme',
        title: $localize`:@@shell.palette.toggleTheme.title:Toggle theme`,
        subtitle: $localize`:@@shell.palette.toggleTheme.subtitle:Light or dark`,
        icon: 'bg-colors',
        run: () => theme.toggle(),
        keywords: keywords($localize`:@@shell.palette.toggleTheme.keywords:dark mode, light mode`),
      },
      {
        id: 'action:toggle-density',
        title: $localize`:@@shell.palette.toggleDensity.title:Toggle density`,
        subtitle: $localize`:@@shell.palette.toggleDensity.subtitle:Comfortable or compact rows`,
        icon: 'column-height',
        run: () => density.toggle(),
        keywords: keywords($localize`:@@shell.palette.toggleDensity.keywords:compact, comfortable`),
      },
      {
        id: 'action:shortcuts',
        title: $localize`:@@shell.palette.shortcuts.title:Show shortcuts`,
        icon: 'question-circle',
        keys: '?',
        run: () => shortcuts.overlayOpen.set(true),
        keywords: keywords($localize`:@@shell.palette.shortcuts.keywords:keyboard, help`),
      },
    ],
  ];
}

/**
 * The shell's palette providers: destinations (permission-filtered), actions, projects, workflows
 * of the current project, recent runs (id prefix or issue key), templates and users (admins).
 * Registered by the shell; pages can add their own (CommandPaletteService.register).
 */
export function registerDefaultPaletteProviders(): void {
  const palette = inject(CommandPaletteService);
  const identity = inject(IdentityService);
  const projects = inject(ProjectsStore);
  const runsApi = inject(RunsApi);
  const workflowsApi = inject(WorkflowsApi);
  const library = inject(TaskLibraryApi);
  const admin = inject(AdminApi);
  const theme = inject(ThemeService);
  const density = inject(DensityService);
  const shortcuts = inject(ShortcutsService);
  const router = inject(Router);
  const authConfig = inject(AuthConfigService);
  // Which sign-in methods exist decides the password / MFA actions (cached, one request).
  authConfig.load().subscribe();

  const recentRuns = cached(() =>
    runsApi.list({ size: 50 }).pipe(
      map((p) => p.content ?? []),
      catchError(() => of([] as WorkflowRunSummary[])),
    ),
  );
  const workflowsByProject = new Map<string, () => Observable<WorkflowDefinition[]>>();
  const workflowsOf = (projectId: string) => {
    if (!workflowsByProject.has(projectId))
      workflowsByProject.set(
        projectId,
        cached(() =>
          workflowsApi
            .listForProject(projectId)
            .pipe(catchError(() => of([] as WorkflowDefinition[]))),
        ),
      );
    return workflowsByProject.get(projectId)!();
  };
  const templates = cached(() => library.listTemplates({}).pipe(catchError(() => of([]))), 120_000);

  const providers: PaletteProvider[] = [
    {
      id: 'destinations',
      group: $localize`:@@shell.palette.group.goTo:Go to`,
      order: 10,
      items: () =>
        visibleNavigation(NAVIGATION, (rule) => identity.allows(rule))
          .flatMap((item) => {
            const own: PaletteItem[] = item.children?.length
              ? []
              : [
                  {
                    id: `nav:${item.path}`,
                    title: item.label,
                    icon: item.icon,
                    link: item.path,
                    keywords: item.keywords,
                  },
                ];
            const children = (item.children ?? []).map<PaletteItem>((c) => ({
              id: `nav:${c.path}`,
              title: `${item.label}: ${c.label}`,
              icon: item.icon,
              link: c.path,
              keywords: [c.label, ...(item.keywords ?? [])],
            }));
            return [...own, ...children];
          })
          .concat([
            {
              id: 'nav:/account',
              title: $localize`:@@shell.palette.account.title:Account`,
              icon: 'user',
              link: '/account',
              keywords: keywords(
                $localize`:@@shell.palette.account.keywords:profile, tokens, preferences`,
              ),
            },
          ]),
    },
    {
      id: 'actions',
      group: $localize`:@@shell.palette.group.actions:Actions`,
      order: 20,
      items: () => actionItems({ identity, projects, authConfig, theme, density, shortcuts }),
    },
    {
      id: 'projects',
      group: $localize`:@@shell.palette.group.projects:Projects`,
      order: 30,
      items: () => {
        projects.ensure();
        return projects.projects().map((p) => ({
          id: `project:${p.id}`,
          title: `${p.key} · ${p.name}`,
          subtitle: primaryRepository(p)?.url ?? null,
          icon: 'project',
          link: `/projects/${p.id}`,
          keywords: [p.key, p.name, p.description],
        }));
      },
    },
    {
      id: 'workflows',
      group: $localize`:@@shell.palette.group.workflows:Workflows`,
      order: 40,
      items: () => {
        const cur = projects.current();
        if (!cur) return [];
        return workflowsOf(cur.id).pipe(
          map((list) =>
            list.map<PaletteItem>((w) => ({
              id: `workflow:${w.id}`,
              title: w.name,
              subtitle: `${cur.key} · v${w.version}`,
              icon: 'apartment',
              link: `/workflows/${w.id}/edit`,
              keywords: [w.description, cur.key],
            })),
          ),
        );
      },
    },
    {
      id: 'runs',
      group: $localize`:@@shell.palette.group.runs:Runs`,
      order: 50,
      items: (q) =>
        recentRuns().pipe(
          map((runs) =>
            runs.slice(0, q ? 50 : 4).map<PaletteItem>((r) => ({
              id: `run:${r.id}`,
              title: `${r.issueKey ? r.issueKey + ' · ' : ''}${r.workflowName ?? $localize`:@@shell.palette.run.fallback:Run`}`,
              subtitle: `${r.projectKey ?? ''} · ${r.id!.slice(0, 8)}`,
              holder: batonOf.run(r).holder,
              link: `/runs/${r.id}`,
              keywords: [r.id, r.id!.slice(0, 8), r.issueKey, r.projectKey],
            })),
          ),
        ),
    },
    {
      id: 'templates',
      group: $localize`:@@shell.palette.group.templates:Templates`,
      order: 60,
      minQuery: 2,
      items: () =>
        identity.can('library.view')
          ? templates().pipe(
              map((list) =>
                list.map<PaletteItem>((t) => ({
                  id: `template:${t.key}`,
                  title: t.name || t.key,
                  subtitle: t.key,
                  icon: 'file-text',
                  link: `/library/templates?key=${encodeURIComponent(t.key)}`,
                  keywords: [t.key, t.description],
                })),
              ),
            )
          : [],
    },
    {
      id: 'users',
      group: $localize`:@@shell.palette.group.users:Users`,
      order: 70,
      minQuery: 2,
      items: (q) =>
        identity.can('admin.users.view')
          ? timer(120).pipe(
              switchMap(() =>
                admin.users({ q, size: 8 }).pipe(catchError(() => of({ content: [] }))),
              ),
              map((page) =>
                (page.content ?? []).map<PaletteItem>((u) => ({
                  id: `user:${u.id}`,
                  title: u.displayName || u.email || $localize`:@@shell.palette.user.fallback:User`,
                  subtitle: u.email ?? null,
                  icon: 'user',
                  link: `/admin/users/${u.id}`,
                  keywords: [u.email],
                })),
              ),
            )
          : [],
    },
    // Scope "Set project budget…": projects the user manages -> the project's Budget tab.
    {
      id: 'set-budget',
      group: $localize`:@@shell.palette.group.projects:Projects`,
      scope: 'set-budget',
      items: () => {
        projects.ensure();
        const cur = projects.current();
        const list = projects
          .projects()
          .filter((p) => identity.canInProject('project.manage', p.id))
          .sort((a, b) => Number(b.id === cur?.id) - Number(a.id === cur?.id));
        return list.map<PaletteItem>((p) => ({
          id: `budget:${p.id}`,
          title: `${p.key} · ${p.name}`,
          subtitle: $localize`:@@shell.palette.budget.subtitle:Monthly budget`,
          icon: 'dollar',
          link: `/projects/${p.id}?tab=budget`,
          keywords: [p.key, p.name],
        }));
      },
    },
    // Scope "Start run…": workflows of the current project (or of the first projects) -> the
    // project page opens the start dialog for the chosen workflow.
    {
      id: 'start-run',
      group: $localize`:@@shell.palette.group.workflows:Workflows`,
      scope: 'start-run',
      items: () => {
        projects.ensure();
        const cur = projects.current();
        const list = (cur ? [cur] : projects.projects().slice(0, 8)).filter((p) =>
          identity.canInProject('run.start', p.id),
        );
        if (!list.length) return [];
        return new Promise<PaletteItem[]>((resolve) => {
          const out: PaletteItem[] = [];
          let open = list.length;
          for (const p of list) {
            workflowsOf(p.id).subscribe({
              next: (wfs) => out.push(...startRunItems(p, wfs, router)),
              complete: () => --open === 0 && resolve(out),
              error: () => --open === 0 && resolve(out),
            });
          }
        });
      },
    },
  ];
  providers.push(helpPaletteProvider(inject(HelpService), router));
  palette.register(providers, inject(DestroyRef));
}

/** "Start run…" entries: the enabled workflows of `p`; choosing one opens the start dialog. */
function startRunItems(
  p: Pick<Project, 'id' | 'key'>,
  workflows: readonly WorkflowDefinition[],
  router: Router,
): PaletteItem[] {
  const start = (w: WorkflowDefinition) =>
    void router.navigate(['/projects', p.id], { queryParams: { start: w.id } });
  return workflows
    .filter((w) => w.enabled !== false)
    .map<PaletteItem>((w) => ({
      id: `start:${w.id}`,
      title: w.name,
      subtitle: `${p.key} · v${w.version}`,
      icon: 'caret-right',
      run: () => start(w),
      keywords: [p.key, w.description],
    }));
}

/**
 * The manual in the palette: "Open manual" and "Help for this page" always; with a query also one
 * entry per chapter and per anchored section (titles from the manifest, anchors humanised). The
 * manifest is fetched on the first palette query.
 */
export function helpPaletteProvider(help: HelpService, router: Router): PaletteProvider {
  return {
    id: 'help',
    group: $localize`:@@shell.palette.group.manual:Manual`,
    order: 80,
    items: (q) => {
      const base: PaletteItem[] = [
        {
          id: 'help:manual',
          title: $localize`:@@shell.palette.openManual.title:Open manual`,
          subtitle: $localize`:@@shell.palette.openManual.subtitle:How Back Office works, integrations, operations`,
          icon: 'read',
          link: '/help',
          keywords: keywords(
            $localize`:@@shell.palette.openManual.keywords:help, docs, documentation, guide, manual`,
          ),
        },
        {
          id: 'help:page',
          title: $localize`:@@shell.palette.helpPage.title:Help for this page`,
          icon: 'question-circle',
          keys: 'F1',
          run: () => help.openForUrl(router.url),
          keywords: keywords(
            $localize`:@@shell.palette.helpPage.keywords:help, what is this, explain`,
          ),
        },
      ];
      if (!q.trim()) return base;
      return help.manifest().then((m) => [...base, ...manualPaletteItems(m)]);
    },
  };
}

/** One palette entry per chapter and per anchor of the manual. */
export function manualPaletteItems(m: ManualManifest | null): PaletteItem[] {
  return allChapters(m).flatMap((c) => [
    {
      id: `help:${c.slug}`,
      title: $localize`:@@shell.palette.manualChapter:Manual: ${c.title}:chapter:`,
      icon: 'read',
      link: manualLink({ chapter: c.slug }),
      keywords: [...chapterKeywords(), c.slug],
    },
    ...c.anchors.map<PaletteItem>((a) => ({
      id: `help:${c.slug}#${a}`,
      title: `${c.title}: ${humanizeAnchor(a)}`,
      subtitle: $localize`:@@shell.palette.group.manual:Manual`,
      icon: 'read',
      link: manualLink({ chapter: c.slug, anchor: a }),
      keywords: [...chapterKeywords(), a.replace(/-/g, ' ')],
    })),
  ]);
}
