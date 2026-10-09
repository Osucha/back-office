import { PERMISSION_DEFAULTS, ROLE_DEFAULTS } from './catalog-defaults';
import { compareKeys } from './compare';

/**
 * UI texts of the backend permission catalogue and of the built-in roles. The server sends them in
 * English; each known key has a `$localize` message whose English source is exactly the server text,
 * so English output is unchanged and other languages get a translation. Unknown keys (custom roles,
 * permissions newer than this table) and texts an administrator changed keep the server text.
 */
const PERMISSIONS: Readonly<Record<string, () => string>> = {
  'admin.users.view': () =>
    $localize`:@@core.permission.admin.users.view:List users, groups and memberships`,
  'admin.users.manage': () =>
    $localize`:@@core.permission.admin.users.manage:Invite, edit and deactivate users; manage groups`,
  'admin.roles.manage': () =>
    $localize`:@@core.permission.admin.roles.manage:Edit roles and platform grants`,
  'admin.audit.view': () => $localize`:@@core.permission.admin.audit.view:Read the audit log`,
  'admin.projects.all': () =>
    $localize`:@@core.permission.admin.projects.all:Every project permission on every project`,
  'admin.settings.manage': () =>
    $localize`:@@core.permission.admin.settings.manage:Platform settings (registration mode, domains)`,
  'tokens.personal': () =>
    $localize`:@@core.permission.tokens.personal:Create own personal access tokens`,
  'project.members.manage': () =>
    $localize`:@@core.permission.project.members.manage:Add/remove members and change their project roles`,
  'projects.create': () =>
    $localize`:@@core.permission.projects.create:create projects (creator becomes OWNER)`,
  'integrations.view': () =>
    $localize`:@@core.permission.integrations.view:see integration status, platform connections and run connection tests`,
  'admin.connections.manage': () =>
    $localize`:@@core.permission.admin.connections.manage:create, edit, verify and delete platform connections (Jira credentials, ADR 0030)`,
  'connections.personal': () =>
    $localize`:@@core.permission.connections.personal:create and use own personal connections (Jira as yourself, ADR 0035)`,
  'project.view': () =>
    $localize`:@@core.permission.project.view:see the project, its workflows, runs, CI executions`,
  'project.manage': () =>
    $localize`:@@core.permission.project.manage:edit project settings, CI entrypoints, delete the project`,
  'project.ci.manage': () =>
    $localize`:@@core.permission.project.ci.manage:create/revoke CI tokens`,
  'project.connections.manage': () =>
    $localize`:@@core.permission.project.connections.manage:create, edit, verify and delete the project connections (owners only, ADR 0030)`,
  'workflow.edit': () =>
    $localize`:@@core.permission.workflow.edit:create/edit/delete/duplicate workflows`,
  'run.start': () => $localize`:@@core.permission.run.start:start and retry runs`,
  'run.cancel': () => $localize`:@@core.permission.run.cancel:cancel runs`,
  'run.approve': () =>
    $localize`:@@core.permission.run.approve:decide human gates (subject to the gate's own approver rule)`,
  'run.assign': () =>
    $localize`:@@core.permission.run.assign:assign or reassign who decides an open human gate`,
  'run.signal': () =>
    $localize`:@@core.permission.run.signal:send a manual CI signal to a waiting run`,
  'ci.execute': () =>
    $localize`:@@core.permission.ci.execute:granted only to CI tokens: start CI executions`,
  'ci.signal': () =>
    $localize`:@@core.permission.ci.signal:granted only to CI tokens: send signals`,
  'models.view': () => $localize`:@@core.permission.models.view:Read model profiles`,
  'models.manage': () =>
    $localize`:@@core.permission.models.manage:Create and edit model profiles and pricing, test a profile`,
  'evaluation.view': () =>
    $localize`:@@core.permission.evaluation.view:Read suites, benchmarks and leaderboards`,
  'evaluation.run': () =>
    $localize`:@@core.permission.evaluation.run:Create suites and tasks; create, start and cancel benchmarks`,
  'library.view': () => $localize`:@@core.permission.library.view:Read the global task library`,
  'library.manage': () =>
    $localize`:@@core.permission.library.manage:Create and edit global templates, interfaces and skills`,
  'library.project.manage': () =>
    $localize`:@@core.permission.library.project.manage:Create and edit project-scoped templates and skills`,
  'usage.view': () =>
    $localize`:@@core.permission.usage.view:Read the usage ledger for all projects and benchmarks`,
  'project.usage.view': () =>
    $localize`:@@core.permission.project.usage.view:Usage and cost of this project`,
  'fleet.view': () =>
    $localize`:@@core.permission.fleet.view:See runner pools, runners and agent jobs`,
  'fleet.manage': () =>
    $localize`:@@core.permission.fleet.manage:Manage runner pools, registration tokens, runners and agent jobs`,
};

const ROLE_NAMES: Readonly<Record<string, () => string>> = {
  'platform-admin': () => $localize`:@@core.role.platform-admin:Platform administrator`,
  member: () => $localize`:@@core.role.member:Member`,
  'project-manager': () => $localize`:@@core.role.project-manager:Project manager`,
  evaluator: () => $localize`:@@core.role.evaluator:Evaluator`,
  'library-curator': () => $localize`:@@core.role.library-curator:Library curator`,
  auditor: () => $localize`:@@core.role.auditor:Auditor`,
  owner: () => $localize`:@@core.role.owner:Owner`,
  maintainer: () => $localize`:@@core.role.maintainer:Maintainer`,
  developer: () => $localize`:@@core.role.developer:Developer`,
  approver: () => $localize`:@@core.role.approver:Approver`,
  viewer: () => $localize`:@@core.role.viewer:Viewer`,
};

const ROLE_DESCRIPTIONS: Readonly<Record<string, () => string>> = {
  'platform-admin': () =>
    $localize`:@@core.role.platform-admin.description:All platform permissions including every project`,
  member: () =>
    $localize`:@@core.role.member.description:Default for every authenticated user (SYSTEM level)`,
  'project-manager': () =>
    $localize`:@@core.role.project-manager.description:Creates projects, becomes their owner and decides who does which step`,
  evaluator: () => $localize`:@@core.role.evaluator.description:Member who runs model evaluations`,
  'library-curator': () =>
    $localize`:@@core.role.library-curator.description:Member who maintains the global task library`,
  auditor: () =>
    $localize`:@@core.role.auditor.description:Read-only access to users, audit log and usage`,
  owner: () =>
    $localize`:@@core.role.owner.description:Every project permission except CI-token-only ones`,
  maintainer: () =>
    $localize`:@@core.role.maintainer.description:Owner without member management (project.manage still allows settings)`,
  developer: () => $localize`:@@core.role.developer.description:Edits workflows and runs them`,
  approver: () => $localize`:@@core.role.approver.description:Decides human gates`,
  viewer: () => $localize`:@@core.role.viewer.description:Read-only project access`,
};

/** Keys with a translated text (for tests and completeness checks). */
export const LOCALIZED_PERMISSION_KEYS: readonly string[] = Object.keys(PERMISSIONS);
export const LOCALIZED_ROLE_KEYS: readonly string[] = Object.keys(ROLE_NAMES);

function own<T>(table: Readonly<Record<string, T>>, key: string): T | undefined {
  return Object.prototype.hasOwnProperty.call(table, key) ? table[key] : undefined;
}

function localized(
  label: (() => string) | undefined,
  serverDefault: string | undefined,
  serverText: string | null | undefined,
  translateMissing: boolean,
): string | null {
  if (!label) return null;
  const text = serverText?.trim();
  // The backend's own default (or no text, when only the key is known): translate. Any other text
  // was changed on the server and is shown as it is.
  if (text ? text === serverDefault : translateMissing) return label();
  return null;
}

/**
 * Description of a permission of the catalogue: translated for known keys, else `serverText`.
 * `serverText` may be empty (only the key is known).
 */
export function permissionDescription(key: string, serverText?: string | null): string {
  return (
    localized(own(PERMISSIONS, key), own(PERMISSION_DEFAULTS, key), serverText, true) ??
    (serverText?.trim() || '')
  );
}

/**
 * Display name of a role: the translated name of a built-in role (unless an administrator renamed
 * it), else `serverName`, else the key itself (custom roles keep their own names).
 */
export function roleName(key: string, serverName?: string | null): string {
  return (
    localized(own(ROLE_NAMES, key), own(ROLE_DEFAULTS, key)?.name, serverName, true) ??
    (serverName?.trim() || key)
  );
}

/**
 * Description of a role: translated for a built-in role whose description is still the default,
 * else `serverText` (empty stays empty).
 */
export function roleDescription(key: string, serverText?: string | null): string {
  return (
    localized(
      own(ROLE_DESCRIPTIONS, key),
      own(ROLE_DEFAULTS, key)?.description,
      serverText,
      false,
    ) ??
    (serverText?.trim() || '')
  );
}

/** Whether `key` is a built-in role with a translated name. */
export function isBuiltInRoleKey(key: string): boolean {
  return own(ROLE_NAMES, key) !== undefined;
}

/**
 * Options for a role select: the roles of `list` plus every key of `assigned` that is not in it
 * (assigned roles stay selectable when the list is not readable), sorted by key, labelled by name.
 */
export function roleOptions(
  list: ReadonlyArray<{ key?: string | null; name?: string | null }>,
  assigned: readonly string[] = [],
): Array<{ key: string; label: string }> {
  const names = new Map<string, string | null | undefined>();
  for (const r of list) if (r.key) names.set(r.key, r.name);
  for (const key of assigned) if (!names.has(key)) names.set(key, undefined);
  return [...names.keys()]
    .sort(compareKeys)
    .map((key) => ({ key, label: roleName(key, names.get(key)) }));
}
