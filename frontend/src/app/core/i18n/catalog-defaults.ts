// i18n-ignore-file: server-side defaults, not UI text (the UI text is in catalog-labels.ts)
/**
 * The English texts the backend seeds for its permission catalogue (`PermissionDefinition`s of every
 * module) and its built-in roles (`BuiltInRoles` in identity-core). `catalog-labels.ts` translates a
 * known key only while the server still sends this text: a built-in role an administrator renamed,
 * or a description the backend changed since, is shown as the server sends it.
 */
export const PERMISSION_DEFAULTS: Readonly<Record<string, string>> = {
  // identity
  'admin.users.view': 'List users, groups and memberships',
  'admin.users.manage': 'Invite, edit and deactivate users; manage groups',
  'admin.roles.manage': 'Edit roles and platform grants',
  'admin.audit.view': 'Read the audit log',
  'admin.projects.all': 'Every project permission on every project',
  'admin.settings.manage': 'Platform settings (registration mode, domains)',
  'tokens.personal': 'Create own personal access tokens',
  'project.members.manage': 'Add/remove members and change their project roles',
  // projects
  'projects.create': 'create projects (creator becomes OWNER)',
  'integrations.view': 'see integration status, platform connections and run connection tests',
  'admin.connections.manage':
    'create, edit, verify and delete platform connections (Jira credentials, ADR 0030)',
  'connections.personal': 'create and use own personal connections (Jira as yourself, ADR 0035)',
  'project.view': 'see the project, its workflows, runs, CI executions',
  'project.manage': 'edit project settings, CI entrypoints, delete the project',
  'project.ci.manage': 'create/revoke CI tokens',
  'project.connections.manage':
    'create, edit, verify and delete the project connections (owners only, ADR 0030)',
  'workflow.edit': 'create/edit/delete/duplicate workflows',
  'run.start': 'start and retry runs',
  'run.cancel': 'cancel runs',
  'run.approve': "decide human gates (subject to the gate's own approver rule)",
  'run.assign': 'assign or reassign who decides an open human gate',
  'run.signal': 'send a manual CI signal to a waiting run',
  'ci.execute': 'granted only to CI tokens: start CI executions',
  'ci.signal': 'granted only to CI tokens: send signals',
  // evaluation
  'models.view': 'Read model profiles',
  'models.manage': 'Create and edit model profiles and pricing, test a profile',
  'evaluation.view': 'Read suites, benchmarks and leaderboards',
  'evaluation.run': 'Create suites and tasks; create, start and cancel benchmarks',
  // tasks
  'library.view': 'Read the global task library',
  'library.manage': 'Create and edit global templates, interfaces and skills',
  'library.project.manage': 'Create and edit project-scoped templates and skills',
  // llm
  'usage.view': 'Read the usage ledger for all projects and benchmarks',
  'project.usage.view': 'Usage and cost of this project',
  // runner
  'fleet.view': 'See runner pools, runners and agent jobs',
  'fleet.manage': 'Manage runner pools, registration tokens, runners and agent jobs',
};

export const ROLE_DEFAULTS: Readonly<Record<string, { name: string; description: string }>> = {
  'platform-admin': {
    name: 'Platform administrator',
    description: 'All platform permissions including every project',
  },
  member: { name: 'Member', description: 'Default for every authenticated user (SYSTEM level)' },
  'project-manager': {
    name: 'Project manager',
    description: 'Creates projects, becomes their owner and decides who does which step',
  },
  evaluator: { name: 'Evaluator', description: 'Member who runs model evaluations' },
  'library-curator': {
    name: 'Library curator',
    description: 'Member who maintains the global task library',
  },
  auditor: { name: 'Auditor', description: 'Read-only access to users, audit log and usage' },
  owner: { name: 'Owner', description: 'Every project permission except CI-token-only ones' },
  maintainer: {
    name: 'Maintainer',
    description: 'Owner without member management (project.manage still allows settings)',
  },
  developer: { name: 'Developer', description: 'Edits workflows and runs them' },
  approver: { name: 'Approver', description: 'Decides human gates' },
  viewer: { name: 'Viewer', description: 'Read-only project access' },
};
