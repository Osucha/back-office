// i18n-ignore-file: test fixtures
import { clearTranslations, loadTranslations } from '@angular/localize';
import { PERMISSION_DEFAULTS, ROLE_DEFAULTS } from './catalog-defaults';
import {
  LOCALIZED_PERMISSION_KEYS,
  LOCALIZED_ROLE_KEYS,
  isBuiltInRoleKey,
  permissionDescription,
  roleDescription,
  roleName,
} from './catalog-labels';

/**
 * The backend catalogue as collected from the sources: every `PermissionDefinition` contributed by a
 * module (IdentityPermissions, ProjectsPermissions, EvaluationPermissions, TasksPermissions,
 * LlmPermissions, RunnerPermissions) and every role of `BuiltInRoles`. Update together with the
 * backend; a new key here without a message in catalog-labels.ts fails the test.
 */
const BACKEND_PERMISSIONS: ReadonlyArray<
  [key: string, scope: 'PLATFORM' | 'PROJECT', text: string]
> = [
  ['admin.users.view', 'PLATFORM', 'List users, groups and memberships'],
  ['admin.users.manage', 'PLATFORM', 'Invite, edit and deactivate users; manage groups'],
  ['admin.roles.manage', 'PLATFORM', 'Edit roles and platform grants'],
  ['admin.audit.view', 'PLATFORM', 'Read the audit log'],
  ['admin.projects.all', 'PLATFORM', 'Every project permission on every project'],
  ['admin.settings.manage', 'PLATFORM', 'Platform settings (registration mode, domains)'],
  ['tokens.personal', 'PLATFORM', 'Create own personal access tokens'],
  ['project.members.manage', 'PROJECT', 'Add/remove members and change their project roles'],
  ['projects.create', 'PLATFORM', 'create projects (creator becomes OWNER)'],
  [
    'integrations.view',
    'PLATFORM',
    'see integration status, platform connections and run connection tests',
  ],
  [
    'admin.connections.manage',
    'PLATFORM',
    'create, edit, verify and delete platform connections (Jira credentials, ADR 0030)',
  ],
  [
    'connections.personal',
    'PLATFORM',
    'create and use own personal connections (Jira as yourself, ADR 0035)',
  ],
  ['project.view', 'PROJECT', 'see the project, its workflows, runs, CI executions'],
  ['project.manage', 'PROJECT', 'edit project settings, CI entrypoints, delete the project'],
  ['project.ci.manage', 'PROJECT', 'create/revoke CI tokens'],
  [
    'project.connections.manage',
    'PROJECT',
    'create, edit, verify and delete the project connections (owners only, ADR 0030)',
  ],
  ['workflow.edit', 'PROJECT', 'create/edit/delete/duplicate workflows'],
  ['run.start', 'PROJECT', 'start and retry runs'],
  ['run.cancel', 'PROJECT', 'cancel runs'],
  ['run.approve', 'PROJECT', "decide human gates (subject to the gate's own approver rule)"],
  ['run.assign', 'PROJECT', 'assign or reassign who decides an open human gate'],
  ['run.signal', 'PROJECT', 'send a manual CI signal to a waiting run'],
  ['ci.execute', 'PROJECT', 'granted only to CI tokens: start CI executions'],
  ['ci.signal', 'PROJECT', 'granted only to CI tokens: send signals'],
  ['models.view', 'PLATFORM', 'Read model profiles'],
  ['models.manage', 'PLATFORM', 'Create and edit model profiles and pricing, test a profile'],
  ['evaluation.view', 'PLATFORM', 'Read suites, benchmarks and leaderboards'],
  ['evaluation.run', 'PLATFORM', 'Create suites and tasks; create, start and cancel benchmarks'],
  ['library.view', 'PLATFORM', 'Read the global task library'],
  ['library.manage', 'PLATFORM', 'Create and edit global templates, interfaces and skills'],
  ['library.project.manage', 'PROJECT', 'Create and edit project-scoped templates and skills'],
  ['usage.view', 'PLATFORM', 'Read the usage ledger for all projects and benchmarks'],
  ['project.usage.view', 'PROJECT', 'Usage and cost of this project'],
  ['fleet.view', 'PLATFORM', 'See runner pools, runners and agent jobs'],
  ['fleet.manage', 'PLATFORM', 'Manage runner pools, registration tokens, runners and agent jobs'],
];

const BACKEND_ROLES: ReadonlyArray<[key: string, name: string, description: string]> = [
  ['platform-admin', 'Platform administrator', 'All platform permissions including every project'],
  ['member', 'Member', 'Default for every authenticated user (SYSTEM level)'],
  [
    'project-manager',
    'Project manager',
    'Creates projects, becomes their owner and decides who does which step',
  ],
  ['evaluator', 'Evaluator', 'Member who runs model evaluations'],
  ['library-curator', 'Library curator', 'Member who maintains the global task library'],
  ['auditor', 'Auditor', 'Read-only access to users, audit log and usage'],
  ['owner', 'Owner', 'Every project permission except CI-token-only ones'],
  [
    'maintainer',
    'Maintainer',
    'Owner without member management (project.manage still allows settings)',
  ],
  ['developer', 'Developer', 'Edits workflows and runs them'],
  ['approver', 'Approver', 'Decides human gates'],
  ['viewer', 'Viewer', 'Read-only project access'],
];

describe('catalog labels', () => {
  afterEach(() => clearTranslations());

  it('covers every permission of the backend catalogue, English source = server text', () => {
    expect(new Set(LOCALIZED_PERMISSION_KEYS)).toEqual(
      new Set(BACKEND_PERMISSIONS.map(([k]) => k)),
    );
    expect(Object.keys(PERMISSION_DEFAULTS).sort()).toEqual(
      BACKEND_PERMISSIONS.map(([k]) => k).sort(),
    );
    for (const [key, , text] of BACKEND_PERMISSIONS) {
      expect(PERMISSION_DEFAULTS[key], key).toBe(text);
      expect(permissionDescription(key, text), key).toBe(text);
      expect(permissionDescription(key), key).toBe(text);
    }
  });

  it('covers every built-in role, English source = server text', () => {
    expect(new Set(LOCALIZED_ROLE_KEYS)).toEqual(new Set(BACKEND_ROLES.map(([k]) => k)));
    expect(Object.keys(ROLE_DEFAULTS).sort()).toEqual(BACKEND_ROLES.map(([k]) => k).sort());
    for (const [key, name, description] of BACKEND_ROLES) {
      expect(ROLE_DEFAULTS[key], key).toEqual({ name, description });
      expect(roleName(key, name), key).toBe(name);
      expect(roleName(key), key).toBe(name);
      expect(roleDescription(key, description), key).toBe(description);
      expect(isBuiltInRoleKey(key), key).toBe(true);
    }
  });

  it('falls back to the server text for unknown keys', () => {
    expect(permissionDescription('reports.export', 'Export reports')).toBe('Export reports');
    expect(permissionDescription('reports.export')).toBe('');
    expect(roleName('release-manager', 'Release manager')).toBe('Release manager');
    expect(roleName('release-manager')).toBe('release-manager');
    expect(roleName('release-manager', '  ')).toBe('release-manager');
    expect(roleDescription('release-manager', 'Ships releases')).toBe('Ships releases');
    expect(roleDescription('release-manager', null)).toBe('');
    expect(isBuiltInRoleKey('release-manager')).toBe(false);
    expect(isBuiltInRoleKey('toString')).toBe(false);
    expect(roleName('toString')).toBe('toString');
    expect(permissionDescription('constructor', 'x')).toBe('x');
  });

  it('translates known keys at call time', () => {
    loadTranslations({
      'core.permission.admin.audit.view': 'Odczyt dziennika audytu',
      'core.role.member': 'Członek',
      'core.role.owner.description': 'Wszystkie uprawnienia projektowe',
    });
    expect(permissionDescription('admin.audit.view', 'Read the audit log')).toBe(
      'Odczyt dziennika audytu',
    );
    expect(roleName('member', 'Member')).toBe('Członek');
    expect(roleName('member')).toBe('Członek');
    expect(roleDescription('owner', 'Every project permission except CI-token-only ones')).toBe(
      'Wszystkie uprawnienia projektowe',
    );
  });

  it('keeps texts an administrator or a newer backend changed', () => {
    loadTranslations({
      'core.permission.admin.audit.view': 'Odczyt dziennika audytu',
      'core.role.member': 'Członek',
      'core.role.member.description': 'Domyślna rola',
    });
    expect(permissionDescription('admin.audit.view', 'Read and export the audit log')).toBe(
      'Read and export the audit log',
    );
    expect(roleName('member', 'Employee')).toBe('Employee');
    expect(roleDescription('member', 'Everyone at ACME')).toBe('Everyone at ACME');
    expect(roleDescription('member', '')).toBe('');
  });
});
