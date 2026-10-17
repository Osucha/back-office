import { Routes } from '@angular/router';
import {
  authGuard,
  pendingSavesGuard,
  permissionGuard,
  publicOnlyGuard,
  sessionGuard,
  unsavedChangesGuard,
  type RouteAccessData,
} from './core/auth/guards';
import { ShellComponent } from './layout/shell.component';

/** Route data with the access rule checked by `permissionGuard` (see core/auth/guards.ts). */
const access = (rule: RouteAccessData): RouteAccessData => rule;

const ADMIN_USERS_VIEW = access({ permission: 'admin.users.view' });
const ADMIN_ROLES_VIEW = access({ permission: ['admin.roles.manage', 'admin.users.view'] });
const FLEET_VIEW = access({ permission: ['fleet.view', 'fleet.manage'] });

export const routes: Routes = [
  {
    path: 'login',
    title: $localize`:@@shell.route.signIn:Sign in`,
    canActivate: [publicOnlyGuard],
    loadComponent: () =>
      import('./features/auth/pages/login-page.component').then((m) => m.LoginPageComponent),
  },
  // ---- local accounts (ADR 0021): outside the shell, so a restricted session loads nothing else
  {
    path: 'set-password/:token',
    title: $localize`:@@shell.route.setYourPassword:Set your password`,
    loadComponent: () =>
      import('./features/auth/pages/set-password-page.component').then(
        (m) => m.SetPasswordPageComponent,
      ),
  },
  {
    path: 'change-password',
    title: $localize`:@@shell.route.changeYourPassword:Change your password`,
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/auth/pages/change-password-page.component').then(
        (m) => m.ChangePasswordPageComponent,
      ),
  },
  {
    path: 'setup-mfa',
    title: $localize`:@@shell.route.setUpTwoFactorAuthentication:Set up two-factor authentication`,
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/auth/pages/setup-mfa-page.component').then((m) => m.SetupMfaPageComponent),
  },
  {
    path: '',
    component: ShellComponent,
    canActivate: [authGuard],
    // Order: an expired session blocks everything, then saves in flight, then permissions.
    canActivateChild: [sessionGuard, pendingSavesGuard, permissionGuard],
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'home',
        title: $localize`:@@shell.route.home:Home`,
        loadComponent: () =>
          import('./features/dashboard/pages/home-page.component').then((m) => m.HomePageComponent),
      },
      { path: 'dashboard', pathMatch: 'full', redirectTo: 'home' },
      {
        path: 'inbox',
        title: $localize`:@@shell.route.yourTurn:Your turn`,
        loadComponent: () =>
          import('./features/inbox/pages/inbox-page.component').then((m) => m.InboxPageComponent),
      },
      {
        path: 'inbox/:approvalId',
        title: $localize`:@@shell.route.yourTurn:Your turn`,
        loadComponent: () =>
          import('./features/inbox/pages/inbox-page.component').then((m) => m.InboxPageComponent),
      },
      {
        path: 'projects',
        title: $localize`:@@shell.route.projects:Projects`,
        loadComponent: () =>
          import('./features/projects/pages/projects-page.component').then(
            (m) => m.ProjectsPageComponent,
          ),
      },
      {
        path: 'projects/:projectId/workflows/new',
        title: $localize`:@@shell.route.newWorkflow:New workflow`,
        data: access({ projectPermission: 'workflow.edit' }),
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/workflows/pages/workflow-designer-page.component').then(
            (m) => m.WorkflowDesignerPageComponent,
          ),
      },
      {
        path: 'projects/:projectId',
        title: $localize`:@@shell.route.project:Project`,
        data: access({ projectPermission: 'project.view' }),
        loadComponent: () =>
          import('./features/projects/pages/project-detail-page.component').then(
            (m) => m.ProjectDetailPageComponent,
          ),
      },
      {
        // Read-only without `workflow.edit` (checked on the loaded workflow's project).
        path: 'workflows/:workflowId/edit',
        title: $localize`:@@shell.route.workflow:Workflow`,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/workflows/pages/workflow-designer-page.component').then(
            (m) => m.WorkflowDesignerPageComponent,
          ),
      },
      {
        path: 'runs',
        title: $localize`:@@shell.route.runs:Runs`,
        loadComponent: () =>
          import('./features/runs/pages/runs-page.component').then((m) => m.RunsPageComponent),
      },
      {
        path: 'runs/:runId',
        title: $localize`:@@shell.route.run:Run`,
        loadComponent: () =>
          import('./features/runs/pages/run-detail-page.component').then(
            (m) => m.RunDetailPageComponent,
          ),
      },
      {
        // Delivery insights: anyone who can see a project; the API filters by project visibility.
        path: 'insights',
        title: $localize`:@@shell.route.insights:Insights`,
        data: access({ projectPermission: 'project.view' }),
        loadComponent: () =>
          import('./features/insights/pages/insights-page.component').then(
            (m) => m.InsightsPageComponent,
          ),
      },
      {
        path: 'ci',
        title: $localize`:@@shell.route.ciExecutions:CI executions`,
        loadComponent: () =>
          import('./features/ci/pages/ci-executions-page.component').then(
            (m) => m.CiExecutionsPageComponent,
          ),
      },
      {
        path: 'ci/:executionId',
        title: $localize`:@@shell.route.ciExecution:CI execution`,
        loadComponent: () =>
          import('./features/ci/pages/ci-execution-detail-page.component').then(
            (m) => m.CiExecutionDetailPageComponent,
          ),
      },
      { path: 'library', pathMatch: 'full', redirectTo: 'library/templates' },
      {
        path: 'library/templates',
        title: $localize`:@@shell.route.taskTemplates:Task templates`,
        canDeactivate: [unsavedChangesGuard],
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/templates-page.component').then(
            (m) => m.TemplatesPageComponent,
          ),
      },
      {
        path: 'library/interfaces',
        title: $localize`:@@shell.route.taskInterfaces:Task interfaces`,
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/interfaces-page.component').then(
            (m) => m.InterfacesPageComponent,
          ),
      },
      {
        path: 'library/skills',
        title: $localize`:@@shell.route.skills:Skills`,
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/skills-page.component').then(
            (m) => m.SkillsPageComponent,
          ),
      },
      {
        // Library sources (ADR 0043): git repositories steps of every project attach read-only.
        path: 'library/sources',
        title: $localize`:@@shell.route.librarySources:Library sources`,
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/library-sources-page.component').then(
            (m) => m.LibrarySourcesPageComponent,
          ),
      },
      {
        // Personal work (ADR 0038): the caller's own intake rules and run proposals.
        path: 'my-work',
        title: $localize`:@@shell.route.myWork:My work`,
        data: access({ permission: 'connections.personal' }),
        loadComponent: () =>
          import('./features/my-work/pages/my-work-page.component').then(
            (m) => m.MyWorkPageComponent,
          ),
      },
      {
        // Workflow library (ADR 0037): metrics only count runs of projects the caller may view.
        path: 'library/workflows',
        title: $localize`:@@shell.route.libraryWorkflows:Library workflows`,
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/library-workflows-page.component').then(
            (m) => m.LibraryWorkflowsPageComponent,
          ),
      },
      {
        path: 'library/workflows/:libraryWorkflowId',
        title: $localize`:@@shell.route.libraryWorkflow:Library workflow`,
        data: access({ permission: 'library.view' }),
        loadComponent: () =>
          import('./features/library/pages/library-workflow-page.component').then(
            (m) => m.LibraryWorkflowPageComponent,
          ),
      },
      {
        path: 'models',
        title: $localize`:@@shell.route.models:Models`,
        data: access({ permission: 'models.view' }),
        loadComponent: () =>
          import('./features/models/pages/models-page.component').then(
            (m) => m.ModelsPageComponent,
          ),
      },
      { path: 'evaluation', pathMatch: 'full', redirectTo: 'evaluation/suites' },
      {
        path: 'evaluation/suites',
        title: $localize`:@@shell.route.taskSuites:Task suites`,
        data: access({ permission: 'evaluation.view' }),
        loadComponent: () =>
          import('./features/evaluation/suites/pages/suites-page.component').then(
            (m) => m.SuitesPageComponent,
          ),
      },
      {
        path: 'evaluation/suites/:suiteId',
        title: $localize`:@@shell.route.taskSuite:Task suite`,
        data: access({ permission: 'evaluation.view' }),
        loadComponent: () =>
          import('./features/evaluation/suites/pages/suite-detail-page.component').then(
            (m) => m.SuiteDetailPageComponent,
          ),
      },
      {
        path: 'evaluation/benchmarks',
        title: $localize`:@@shell.route.benchmarks:Benchmarks`,
        data: access({ permission: 'evaluation.view' }),
        loadComponent: () =>
          import('./features/evaluation/benchmarks/pages/benchmarks-page.component').then(
            (m) => m.BenchmarksPageComponent,
          ),
      },
      {
        path: 'evaluation/benchmarks/:benchmarkId',
        title: $localize`:@@shell.route.benchmark:Benchmark`,
        data: access({ permission: 'evaluation.view' }),
        loadComponent: () =>
          import('./features/evaluation/benchmarks/pages/benchmark-detail-page.component').then(
            (m) => m.BenchmarkDetailPageComponent,
          ),
      },
      {
        path: 'costs',
        title: $localize`:@@shell.route.costs:Costs`,
        // Platform-wide usage, or the usage of at least one project.
        data: access({ permission: 'usage.view', projectPermission: 'project.usage.view' }),
        loadComponent: () =>
          import('./features/costs/pages/costs-page.component').then((m) => m.CostsPageComponent),
      },
      {
        path: 'settings',
        title: $localize`:@@shell.route.settings:Settings`,
        data: access({ permission: 'integrations.view' }),
        loadComponent: () =>
          import('./features/settings/pages/settings-page.component').then(
            (m) => m.SettingsPageComponent,
          ),
      },
      {
        path: 'account',
        title: $localize`:@@shell.route.myAccount:My account`,
        loadComponent: () =>
          import('./features/account/pages/account-page.component').then(
            (m) => m.AccountPageComponent,
          ),
      },
      // ---- the user manual (docs/manual, served at /manual/); everyone signed in
      {
        path: 'help',
        title: $localize`:@@shell.route.manual:Manual`,
        loadComponent: () =>
          import('./features/help/pages/help-page.component').then((m) => m.HelpPageComponent),
      },
      {
        path: 'help/:chapter',
        title: $localize`:@@shell.route.manual:Manual`,
        loadComponent: () =>
          import('./features/help/pages/help-page.component').then((m) => m.HelpPageComponent),
      },
      // ---- administration
      { path: 'admin', pathMatch: 'full', redirectTo: 'admin/users' },
      {
        path: 'admin/users',
        title: $localize`:@@shell.route.users:Users`,
        data: ADMIN_USERS_VIEW,
        loadComponent: () =>
          import('./features/admin/pages/users-page.component').then((m) => m.UsersPageComponent),
      },
      {
        path: 'admin/users/:userId',
        title: $localize`:@@shell.route.user:User`,
        data: ADMIN_USERS_VIEW,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/user-detail-page.component').then(
            (m) => m.UserDetailPageComponent,
          ),
      },
      {
        path: 'admin/groups',
        title: $localize`:@@shell.route.groups:Groups`,
        data: ADMIN_USERS_VIEW,
        loadComponent: () =>
          import('./features/admin/pages/groups-page.component').then((m) => m.GroupsPageComponent),
      },
      {
        path: 'admin/groups/new',
        title: $localize`:@@shell.route.newGroup:New group`,
        data: access({ permission: 'admin.users.manage' }),
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/group-edit-page.component').then(
            (m) => m.GroupEditPageComponent,
          ),
      },
      {
        path: 'admin/groups/:groupId',
        title: $localize`:@@shell.route.group:Group`,
        data: ADMIN_USERS_VIEW,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/group-edit-page.component').then(
            (m) => m.GroupEditPageComponent,
          ),
      },
      {
        path: 'admin/roles',
        title: $localize`:@@shell.route.roles:Roles`,
        data: ADMIN_ROLES_VIEW,
        loadComponent: () =>
          import('./features/admin/pages/roles-page.component').then((m) => m.RolesPageComponent),
      },
      {
        path: 'admin/roles/new',
        title: $localize`:@@shell.route.newRole:New role`,
        data: access({ permission: 'admin.roles.manage' }),
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/role-edit-page.component').then(
            (m) => m.RoleEditPageComponent,
          ),
      },
      {
        path: 'admin/roles/:roleKey',
        title: $localize`:@@shell.route.role:Role`,
        data: ADMIN_ROLES_VIEW,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/role-edit-page.component').then(
            (m) => m.RoleEditPageComponent,
          ),
      },
      {
        path: 'admin/grants',
        title: $localize`:@@shell.route.grants:Grants`,
        data: ADMIN_ROLES_VIEW,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/grants-page.component').then((m) => m.GrantsPageComponent),
      },
      {
        path: 'admin/settings',
        title: $localize`:@@shell.route.platformSettings:Platform settings`,
        data: access({ permission: 'admin.settings.manage' }),
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/admin/pages/platform-settings-page.component').then(
            (m) => m.PlatformSettingsPageComponent,
          ),
      },
      {
        // Least-privilege Jira credentials (ADR 0030); editing needs admin.connections.manage.
        path: 'admin/connections',
        title: $localize`:@@shell.route.connections:Connections`,
        data: access({ permission: ['integrations.view', 'admin.connections.manage'] }),
        loadComponent: () =>
          import('./features/connections/pages/connections-page.component').then(
            (m) => m.ConnectionsPageComponent,
          ),
      },
      // ---- runner fleet (ADR 0024) and operations
      { path: 'admin/fleet', pathMatch: 'full', redirectTo: 'admin/fleet/pools' },
      {
        path: 'admin/fleet/pools',
        title: $localize`:@@shell.route.runnerPools:Runner pools`,
        data: FLEET_VIEW,
        loadComponent: () =>
          import('./features/fleet/pages/fleet-pools-page.component').then(
            (m) => m.FleetPoolsPageComponent,
          ),
      },
      {
        path: 'admin/fleet/pools/new',
        title: $localize`:@@shell.route.newRunnerPool:New runner pool`,
        data: access({ permission: 'fleet.manage' }),
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/fleet/pages/pool-editor-page.component').then(
            (m) => m.PoolEditorPageComponent,
          ),
      },
      {
        path: 'admin/fleet/pools/:poolId',
        title: $localize`:@@shell.route.runnerPool:Runner pool`,
        data: FLEET_VIEW,
        canDeactivate: [unsavedChangesGuard],
        loadComponent: () =>
          import('./features/fleet/pages/pool-editor-page.component').then(
            (m) => m.PoolEditorPageComponent,
          ),
      },
      {
        path: 'admin/fleet/runners',
        title: $localize`:@@shell.route.runners:Runners`,
        data: FLEET_VIEW,
        loadComponent: () =>
          import('./features/fleet/pages/fleet-runners-page.component').then(
            (m) => m.FleetRunnersPageComponent,
          ),
      },
      {
        path: 'admin/fleet/jobs',
        title: $localize`:@@shell.route.agentJobs:Agent jobs`,
        data: FLEET_VIEW,
        loadComponent: () =>
          import('./features/fleet/pages/agent-jobs-page.component').then(
            (m) => m.AgentJobsPageComponent,
          ),
      },
      {
        path: 'admin/operations',
        title: $localize`:@@shell.route.operations:Operations`,
        data: access({ permission: 'admin.settings.manage' }),
        loadComponent: () =>
          import('./features/operations/pages/operations-page.component').then(
            (m) => m.OperationsPageComponent,
          ),
      },
      {
        path: 'admin/audit',
        title: $localize`:@@shell.route.auditLog:Audit log`,
        data: access({ permission: 'admin.audit.view' }),
        loadComponent: () =>
          import('./features/admin/pages/audit-page.component').then((m) => m.AuditPageComponent),
      },
      {
        path: 'forbidden',
        title: $localize`:@@shell.route.accessDenied:Access denied`,
        loadComponent: () =>
          import('./features/auth/pages/forbidden-page.component').then(
            (m) => m.ForbiddenPageComponent,
          ),
      },
      { path: '**', redirectTo: 'home' },
    ],
  },
];
