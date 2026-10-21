/**
 * Pure logic of least-privilege connections (ADR 0030, ADR 0035), covered by connection-model.spec.ts:
 * capability labels and their Jira permissions, the Atlassian scopes of scoped tokens, the
 * step-by-step guide that follows the selected capabilities and token kind, localized verification
 * messages, chips, connection resolution for workflow nodes and the node's "Act as" settings
 * (`jiraConnection`, `fallbackToSystem`, `allowActingUser`).
 *
 * Capabilities and Jira permissions are read as plain strings: the server may add values (the
 * contract says so), and an unknown value is shown humanized instead of breaking the screen.
 */
import { formatDate } from '@angular/common';
import { intlLocale } from '../../../core/i18n/locale';
import type {
  Connection,
  ConnectionInput,
  ConnectionMessage,
  ConnectionResolution,
  ConnectionVerification,
  JiraTokenKind,
} from './connection-types';

export const KNOWN_CAPABILITIES = ['ISSUE_READ', 'ISSUE_COMMENT', 'ISSUE_TRANSITION'] as const;

/** Jira project permissions each capability needs (mirrors the backend policy). */
export const CAPABILITY_PERMISSIONS: Readonly<Record<string, readonly string[]>> = {
  ISSUE_READ: ['BROWSE_PROJECTS'],
  ISSUE_COMMENT: ['BROWSE_PROJECTS', 'ADD_COMMENTS'],
  ISSUE_TRANSITION: ['BROWSE_PROJECTS', 'TRANSITION_ISSUES'],
};

/** Display order of Jira permissions. */
const PERMISSION_ORDER = [
  'BROWSE_PROJECTS',
  'ADD_COMMENTS',
  'TRANSITION_ISSUES',
  'EDIT_ISSUES',
  'ASSIGN_ISSUES',
  'CREATE_ISSUES',
  'MANAGE_WATCHERS',
  'DELETE_ISSUES',
  'ADMINISTER_PROJECTS',
];

/**
 * Jira's own names of its project permissions. They stay in English in every UI language: they are
 * what the Jira administration screens show (glossary: product names unchanged).
 */
const JIRA_PERMISSION_NAMES: Readonly<Record<string, string>> = {
  BROWSE_PROJECTS: 'Browse Projects', // i18n-ignore
  ADD_COMMENTS: 'Add Comments', // i18n-ignore
  TRANSITION_ISSUES: 'Transition Issues', // i18n-ignore
  EDIT_ISSUES: 'Edit Issues', // i18n-ignore
  DELETE_ISSUES: 'Delete Issues', // i18n-ignore
  ASSIGN_ISSUES: 'Assign Issues', // i18n-ignore
  CREATE_ISSUES: 'Create Issues', // i18n-ignore
  ADMINISTER_PROJECTS: 'Administer Projects', // i18n-ignore
  MANAGE_WATCHERS: 'Manage Watchers', // i18n-ignore
};

/** `SOME_VALUE` -> `Some value` (unknown enum values). */
export function humanizeEnum(value: string): string {
  const s = value.replace(/_/g, ' ').toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

export function jiraPermissionName(p: string): string {
  return JIRA_PERMISSION_NAMES[p] ?? humanizeEnum(p);
}

export function jiraPermissionNames(list: readonly string[]): string {
  return sortPermissions(list).map(jiraPermissionName).join(', ');
}

export function sortPermissions(list: readonly string[]): string[] {
  const rank = (p: string) => {
    const i = PERMISSION_ORDER.indexOf(p);
    return i < 0 ? PERMISSION_ORDER.length : i;
  };
  return Array.from(new Set(list)).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

/** What an excess permission lets the account do, for "this account can also …". */
function permissionAction(p: string): string {
  switch (p) {
    case 'BROWSE_PROJECTS':
      return $localize`:@@connections.permission.action.browse:browse projects`;
    case 'ADD_COMMENTS':
      return $localize`:@@connections.permission.action.comment:comment on issues`;
    case 'TRANSITION_ISSUES':
      return $localize`:@@connections.permission.action.transition:move issues`;
    case 'EDIT_ISSUES':
      return $localize`:@@connections.permission.action.edit:edit issues`;
    case 'DELETE_ISSUES':
      return $localize`:@@connections.permission.action.delete:delete issues`;
    case 'ASSIGN_ISSUES':
      return $localize`:@@connections.permission.action.assign:assign issues`;
    case 'CREATE_ISSUES':
      return $localize`:@@connections.permission.action.create:create issues`;
    case 'ADMINISTER_PROJECTS':
      return $localize`:@@connections.permission.action.administer:administer the project`;
    case 'MANAGE_WATCHERS':
      return $localize`:@@connections.permission.action.watchers:manage watchers`;
    default:
      return jiraPermissionName(p);
  }
}

export function permissionActions(list: readonly string[]): string {
  return sortPermissions(list).map(permissionAction).join(', ');
}

// ---------------------------------------------------------------- capabilities

export function capabilityLabel(c: string): string {
  switch (c) {
    case 'ISSUE_READ':
      return $localize`:@@connections.capability.read:Read issues`;
    case 'ISSUE_COMMENT':
      return $localize`:@@connections.capability.comment:Comment on issues`;
    case 'ISSUE_TRANSITION':
      return $localize`:@@connections.capability.transition:Move issues`;
    default:
      return humanizeEnum(c);
  }
}

export function capabilityLabels(list: readonly string[]): string {
  return sortCapabilities(list).map(capabilityLabel).join(', ');
}

/** What the capability is for (checkbox explanation, without the Jira permissions). */
export function capabilityPurpose(c: string): string {
  switch (c) {
    case 'ISSUE_READ':
      return $localize`:@@connections.capability.read.purpose:Fetch issue text for prompts (Fetch issue step).`;
    case 'ISSUE_COMMENT':
      return $localize`:@@connections.capability.comment.purpose:Post results as comments (Notify Jira step).`;
    case 'ISSUE_TRANSITION':
      return $localize`:@@connections.capability.transition.purpose:Change the status (Transition issue step).`;
    default:
      return '';
  }
}

export function isWriteCapability(c: string): boolean {
  return c !== 'ISSUE_READ';
}

export function sortCapabilities(list: readonly string[]): string[] {
  const rank = (c: string) => {
    const i = (KNOWN_CAPABILITIES as readonly string[]).indexOf(c);
    return i < 0 ? KNOWN_CAPABILITIES.length : i;
  };
  return Array.from(new Set(list)).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

/** The Jira permissions a set of capabilities needs (union, display order). */
export function permissionsFor(capabilities: readonly string[]): string[] {
  return sortPermissions(capabilities.flatMap((c) => CAPABILITY_PERMISSIONS[c] ?? []));
}

/** Does the connection hold every required capability? */
export function covers(connection: Pick<Connection, 'capabilities'>, required: readonly string[]) {
  const have = new Set<string>(connection.capabilities ?? []);
  return required.every((c) => have.has(c));
}

// ---------------------------------------------------------------- scoped tokens (ADR 0035)

/**
 * Atlassian scopes per capability, mirroring `JiraScopes` of the backend: a capability is covered by
 * the CLASSIC set or the GRANULAR set; `granularRecommended` is what the UI suggests ticking.
 */
export const SCOPE_OPTIONS: Readonly<
  Record<
    string,
    {
      classic: readonly string[];
      granular: readonly string[];
      granularRecommended: readonly string[];
    }
  >
> = {
  ISSUE_READ: {
    classic: ['read:jira-work'],
    granular: [
      'read:issue:jira',
      'read:issue-details:jira',
      'read:jql:jira',
      'read:audit-log:jira',
    ],
    granularRecommended: [
      'read:issue:jira',
      'read:issue-details:jira',
      'read:jql:jira',
      'read:audit-log:jira',
      'read:issue-meta:jira',
      'read:field-configuration:jira',
      'read:status:jira',
      'read:issue.changelog:jira',
      'read:field:jira',
      'read:user:jira',
      'read:avatar:jira',
    ],
  },
  ISSUE_COMMENT: {
    classic: ['read:jira-work', 'write:jira-work'],
    granular: ['write:comment:jira', 'read:comment:jira'],
    granularRecommended: [
      'write:comment:jira',
      'read:comment:jira',
      'read:comment.property:jira',
      'read:project:jira',
      'read:project-role:jira',
      'read:group:jira',
      'read:user:jira',
      'read:avatar:jira',
    ],
  },
  ISSUE_TRANSITION: {
    classic: ['read:jira-work', 'write:jira-work'],
    granular: ['write:issue:jira', 'read:issue.transition:jira', 'read:status:jira'],
    granularRecommended: [
      'write:issue:jira',
      'write:issue.property:jira',
      'read:issue.transition:jira',
      'read:status:jira',
      'read:field-configuration:jira',
    ],
  },
};

/** `GET /myself` of every verification: classic `read:jira-user`, or all granular scopes the endpoint lists. */
export const VERIFICATION_SCOPES = {
  classic: ['read:jira-user'],
  granular: [
    'read:user:jira',
    'read:group:jira',
    'read:application-role:jira',
    'read:avatar:jira',
    'read:permission:jira',
  ],
} as const;

const SCOPE_PATTERN = /^[a-z]+:[a-z0-9._-]+(:[a-z0-9._-]+)?$/;

export function isWriteScope(scope: string): boolean {
  return scope.startsWith('write:') || scope.startsWith('delete:') || scope.startsWith('manage:');
}

/** The scopes to tick for `capabilities` (and the verification), granular (default) or classic. */
export function recommendedScopes(
  capabilities: readonly string[],
  kind: 'GRANULAR' | 'CLASSIC' = 'GRANULAR',
): string[] {
  const out = new Set<string>(
    kind === 'GRANULAR' ? VERIFICATION_SCOPES.granular : VERIFICATION_SCOPES.classic,
  );
  for (const c of capabilities) {
    const o = SCOPE_OPTIONS[c];
    if (o) (kind === 'GRANULAR' ? o.granularRecommended : o.classic).forEach((s) => out.add(s));
  }
  return [...out].sort();
}

/** Capabilities the declared scopes do not cover with any option. */
export function uncoveredCapabilities(
  capabilities: readonly string[],
  declared: readonly string[],
): string[] {
  const have = new Set(declared);
  return sortCapabilities(
    capabilities.filter((c) => {
      const o = SCOPE_OPTIONS[c];
      return !!o && !o.classic.every((s) => have.has(s)) && !o.granular.every((s) => have.has(s));
    }),
  );
}

/** Declared write scopes no option of `capabilities` needs (the excess check). */
export function excessWriteScopes(
  capabilities: readonly string[],
  declared: readonly string[],
): string[] {
  const needed = new Set(
    capabilities.flatMap((c) => [
      ...(SCOPE_OPTIONS[c]?.granularRecommended ?? []),
      ...(SCOPE_OPTIONS[c]?.classic ?? []),
    ]),
  );
  return [...new Set(declared.filter((s) => isWriteScope(s) && !needed.has(s)))].sort();
}

/** Splits, trims, lower-cases and de-duplicates scopes ("read:jira-work, write:comment:jira"). */
export function normalizeScopes(values: readonly string[]): string[] {
  const out: string[] = [];
  for (const v of values)
    for (const part of v.split(/[\s,;]+/)) {
      const s = part.trim().toLowerCase();
      if (s && !out.includes(s)) out.push(s);
    }
  return out.sort();
}

export function invalidScopes(scopes: readonly string[]): string[] {
  return scopes.filter((s) => !SCOPE_PATTERN.test(s));
}

export const CLOUD_ID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** Short label of a token kind. */
export function tokenKindLabel(kind: string | null | undefined): string {
  return kind === 'SCOPED'
    ? $localize`:@@connections.tokenKind.scoped:scoped token`
    : $localize`:@@connections.tokenKind.classic:classic token`;
}

// ---------------------------------------------------------------- the step-by-step guide

export type GuideRole = 'reader' | 'writer';

export interface ConnectionGuide {
  /** null while no capability is selected. */
  role: GuideRole | null;
  /** Jira permissions to grant, by their Jira names. */
  permissions: string[];
  steps: string[];
}

/**
 * The account set-up steps for the selected capabilities: a read-only connection gets a reader
 * account with Browse Projects only; any write capability makes it a writer with exactly the
 * permissions of the selected capabilities.
 */
export function connectionGuide(
  capabilities: readonly string[],
  allowedProjectKeys: readonly string[] = [],
  tokenKind: JiraTokenKind | string = 'CLASSIC',
  personal = false,
): ConnectionGuide {
  if (!capabilities.length) return { role: null, permissions: [], steps: [] };
  const role: GuideRole = capabilities.some(isWriteCapability) ? 'writer' : 'reader';
  const permissions = permissionsFor(capabilities).map(jiraPermissionName);
  const perms = permissions.join(', ');
  if (tokenKind === 'SCOPED') {
    const scopes = recommendedScopes(capabilities).join(', ');
    return {
      role,
      permissions,
      steps: [
        personal
          ? $localize`:@@connections.guide.scoped.accountPersonal:Sign in to Atlassian with your own account: the token acts as you, limited to the scopes you choose.`
          : $localize`:@@connections.guide.scoped.account:Sign in to Atlassian as the account Back Office should act as. Your own account is fine when no service account can be created: the token's scopes limit what it can do.`,
        $localize`:@@connections.guide.scoped.create:Open id.atlassian.com → Security → API tokens → Create API token with scopes. Give it a name and an expiry date (at most one year).`,
        $localize`:@@connections.guide.scoped.scopes:Choose the app Jira and tick exactly these scopes: ${scopes}:scopes:.`,
        $localize`:@@connections.guide.scoped.save:Copy the token (it is shown once), paste it here, list the same scopes and save: Back Office finds the site's cloud id and checks the scopes against Jira.`,
      ],
    };
  }
  if (personal) {
    return {
      role,
      permissions,
      steps: [
        $localize`:@@connections.guide.personal.token:Sign in to Atlassian with your own account and create a classic API token at id.atlassian.com → Security → API tokens → Create API token.`,
        $localize`:@@connections.guide.personal.warning:A classic token can do everything your account can (${perms}:permissions: and more). Back Office uses it only for the steps you approve or start, each limited to its capability.`,
        $localize`:@@connections.guide.step.save:Paste the e-mail and the token here and save: Back Office checks the permissions.`,
      ],
    };
  }
  const account =
    role === 'reader'
      ? 'backoffice-jira-reader@your-company' // i18n-ignore (example account)
      : 'backoffice-jira-writer@your-company'; // i18n-ignore (example account)
  const keys = allowedProjectKeys.join(', ');
  return {
    role,
    permissions,
    steps: [
      $localize`:@@connections.guide.step.account:Create a dedicated Atlassian account, for example ${account}:account:.`,
      keys
        ? $localize`:@@connections.guide.step.permissionsKeys:In each Jira project (${keys}:keys:) give it ONLY ${perms}:permissions:: add it to a project role that has just these permissions (Project settings → People / Permissions).`
        : $localize`:@@connections.guide.step.permissions:In each Jira project it works in, give it ONLY ${perms}:permissions:: add it to a project role that has just these permissions (Project settings → People / Permissions).`,
      $localize`:@@connections.guide.step.token:Sign in as that account and create a classic API token at id.atlassian.com → Security → API tokens → Create API token (not "with scopes").`,
      $localize`:@@connections.guide.step.save:Paste the e-mail and the token here and save: Back Office checks the permissions.`,
    ],
  };
}

// ---------------------------------------------------------------- chips and verification

export type Tone = 'ok' | 'warn' | 'bad' | 'neutral';

export interface Chip {
  tone: Tone;
  label: string;
  /** Longer explanation (tooltip). */
  title?: string;
}

/** The extra permissions are the account's, out of reach of a scoped token (the backend reports them as INFO). */
export function excessBoundByScopes(v: ConnectionVerification | null | undefined): boolean {
  return (v?.messages ?? []).some((m) => m.code === 'EXCESS_PERMISSIONS' && m.severity === 'INFO');
}

export function verificationChip(v: ConnectionVerification | null | undefined): Chip {
  if (!v)
    return { tone: 'neutral', label: $localize`:@@connections.verification.none:not checked` };
  switch (v.status) {
    case 'OK':
      return { tone: 'ok', label: $localize`:@@connections.verification.ok:verified` };
    case 'FAILED':
      return { tone: 'bad', label: $localize`:@@connections.verification.failed:check failed` };
    default:
      return v.excess?.length && !excessBoundByScopes(v)
        ? {
            tone: 'warn',
            label: $localize`:@@connections.verification.overPrivileged:over-privileged`,
          }
        : {
            tone: 'warn',
            label: $localize`:@@connections.verification.warning:check with warnings`,
          };
  }
}

function shortDate(date: string): string {
  try {
    return formatDate(date, 'mediumDate', intlLocale());
  } catch {
    return date;
  }
}

/** Token expiry reminder; null when nothing needs attention or no date is set. */
export function expiryChip(c: Pick<Connection, 'expiryStatus' | 'secretExpiresAt'>): Chip | null {
  const date = c.secretExpiresAt ? shortDate(c.secretExpiresAt) : '';
  switch (c.expiryStatus) {
    case 'EXPIRED':
      return {
        tone: 'bad',
        label: $localize`:@@connections.expiry.expired:token expired`,
        title: $localize`:@@connections.expiry.expiredTitle:The API token expired on ${date}:date:. Replace it.`,
      };
    case 'EXPIRING_SOON':
      return {
        tone: 'warn',
        label: $localize`:@@connections.expiry.soon:token expires ${date}:date:`,
        title: $localize`:@@connections.expiry.soonTitle:Create a new API token before ${date}:date: and replace it here.`,
      };
    default:
      return null;
  }
}

/**
 * The localized text of a verification message. The English `text` of the server is used only for
 * codes this UI does not know. `needed` = the Jira permissions of the connection's capabilities.
 */
export function verificationMessageText(
  m: ConnectionMessage,
  needed: readonly string[] = [],
): string {
  const key = m.projectKey ?? '';
  const perms = jiraPermissionNames(m.permissions ?? []);
  switch (m.code) {
    case 'AUTHENTICATION_FAILED':
      return $localize`:@@connections.message.authFailed:Jira rejected the e-mail and API token. Check both; a scoped token also needs every scope listed for identifying the account (read:user, read:group, read:application-role, read:avatar).`;
    case 'SITE_UNREACHABLE':
      return $localize`:@@connections.message.unreachable:The Jira site could not be reached. Check the site URL.`;
    case 'HTTP_ERROR':
      return key
        ? $localize`:@@connections.message.httpErrorKey:Jira answered with an error for ${key}:key:.`
        : $localize`:@@connections.message.httpError:Jira answered with an error.`;
    case 'PROJECT_NOT_FOUND':
      return $localize`:@@connections.message.projectNotFound:Jira project ${key}:key: does not exist or this account cannot see it.`;
    case 'NO_PROJECT_TO_CHECK':
      return $localize`:@@connections.message.noProject:No Jira project to check the permissions in: set allowed projects or a sample project key.`;
    case 'MISSING_PERMISSIONS':
      return key
        ? $localize`:@@connections.message.missingKey:Missing in ${key}:key: for the declared capabilities: ${perms}:permissions:. Steps that need them will fail.`
        : $localize`:@@connections.message.missing:Missing for the declared capabilities: ${perms}:permissions:. Steps that need them will fail.`;
    case 'EXCESS_PERMISSIONS': {
      const actions = permissionActions(m.permissions ?? []);
      const only = jiraPermissionNames(needed);
      // a scoped token (INFO): the account may do more, the token only what its scopes allow - nothing to fix
      if (m.severity === 'INFO')
        return key
          ? $localize`:@@connections.message.excessScopedKey:In ${key}:key: this account can also ${actions}:actions:, but the scoped token can only do what its scopes allow.`
          : $localize`:@@connections.message.excessScoped:This account can also ${actions}:actions:, but the scoped token can only do what its scopes allow.`;
      if (key && only)
        return $localize`:@@connections.message.excessKeyOnly:Over-privileged in ${key}:key:: this account can also ${actions}:actions:. Use a dedicated account with only ${only}:needed:.`;
      if (key)
        return $localize`:@@connections.message.excessKey:Over-privileged in ${key}:key:: this account can also ${actions}:actions:. Use a dedicated account with only the permissions it needs.`;
      return only
        ? $localize`:@@connections.message.excessOnly:Over-privileged: this account can also ${actions}:actions:. Use a dedicated account with only ${only}:needed:.`
        : $localize`:@@connections.message.excess:Over-privileged: this account can also ${actions}:actions:. Use a dedicated account with only the permissions it needs.`;
    }
    case 'SECRET_UNAVAILABLE':
      return $localize`:@@connections.message.secretUnavailable:The stored API token cannot be read. Replace the token.`;
    case 'MISSING_SCOPES':
      return $localize`:@@connections.message.missingScopes:The token's scopes do not allow what the capabilities need. Details from Jira: ${m.text}:detail:`;
    case 'EXCESS_SCOPES':
      return $localize`:@@connections.message.excessScopes:The token has write scopes no capability needs. Create a token with only the needed scopes. Details: ${m.text}:detail:`;
    case 'BROAD_SCOPE':
      return $localize`:@@connections.message.broadScope:The classic scope write:jira-work also lets the token create, edit and delete issues. Prefer the granular scopes.`;
    case 'VERIFIED':
      return key
        ? $localize`:@@connections.message.verifiedKey:${key}:key:: the account has the permissions the capabilities need.`
        : $localize`:@@connections.message.verified:The account has the permissions the capabilities need.`;
    default:
      return m.text;
  }
}

export function messageTone(m: Pick<ConnectionMessage, 'severity'>): Tone {
  return m.severity === 'ERROR' ? 'bad' : m.severity === 'WARNING' ? 'warn' : 'ok';
}

// ---------------------------------------------------------------- resolution (workflow nodes)

export interface ResolutionView {
  tone: Tone;
  text: string;
  /** Show the link to the least-privilege guide. */
  guide: boolean;
}

export function resolutionView(r: ConnectionResolution): ResolutionView {
  const name = r.connection?.name ?? '';
  const needs = capabilityLabels(r.required ?? []);
  if (r.fallback && r.connection)
    return {
      tone: r.status === 'OK' ? 'ok' : 'warn',
      text: $localize`:@@connections.resolve.fallback:The system connection does not fit this step; uses ${name}:name: from the project's connection list.`,
      guide: false,
    };
  switch (r.status) {
    case 'OK':
      return r.pinned
        ? {
            tone: 'ok',
            text: $localize`:@@connections.resolve.okPinned:Uses the pinned connection ${name}:name:.`,
            guide: false,
          }
        : {
            tone: 'ok',
            text: $localize`:@@connections.resolve.ok:Uses ${name}:name: (least privilege).`,
            guide: false,
          };
    case 'OVER_PRIVILEGED':
      return {
        tone: 'warn',
        text: $localize`:@@connections.resolve.overPrivileged:Uses ${name}:name:, a connection with more rights than needed (extra: ${capabilityLabels(r.excess ?? [])}:extra:).`,
        guide: true,
      };
    case 'NONE':
      return {
        tone: 'bad',
        text: $localize`:@@connections.resolve.none:No connection can do this (needs: ${needs}:needs:). Add a connection with these capabilities, or the step fails.`,
        guide: true,
      };
    case 'PINNED_NOT_FOUND':
      return {
        tone: 'bad',
        text: $localize`:@@connections.resolve.pinnedNotFound:The pinned connection does not exist or this project cannot use it. Pick another one or switch to automatic.`,
        guide: true,
      };
    case 'PINNED_INSUFFICIENT': {
      const missing = (r.required ?? []).filter(
        (c) => !(r.connection?.capabilities ?? []).includes(c),
      );
      return {
        tone: 'bad',
        text: $localize`:@@connections.resolve.pinnedInsufficient:The pinned connection ${name}:name: lacks: ${capabilityLabels(missing)}:missing:. Pick another one or switch to automatic.`,
        guide: true,
      };
    }
    case 'NO_ACTING_USER':
    case 'NO_PERSONAL_CONNECTION':
      return { tone: 'bad', text: r.message, guide: false };
    default:
      return { tone: 'warn', text: r.message, guide: false };
  }
}

// ---------------------------------------------------------------- "Act as" of a Jira node (ADR 0035)

export const ACTING_USER = 'ACTING_USER';
export const SYSTEM_CONNECTION = 'SYSTEM';
export const FALLBACK_KEY = 'fallbackToSystem';
export const ALLOW_ACTING_USER_KEY = 'allowActingUser';

export type ActAs = 'SYSTEM' | 'SPECIFIC' | 'ACTING_USER';

/** `jiraConnection`: missing / SYSTEM -> SYSTEM, ACTING_USER -> ACTING_USER, an id -> SPECIFIC. */
export function actAsOf(config: Record<string, unknown> | null | undefined): ActAs {
  const v = config?.[JIRA_CONNECTION_KEY];
  if (typeof v !== 'string' || !v || v.toUpperCase() === SYSTEM_CONNECTION) return 'SYSTEM';
  return v.toUpperCase() === ACTING_USER ? 'ACTING_USER' : 'SPECIFIC';
}

export function configFlag(
  config: Record<string, unknown> | null | undefined,
  key: string,
): boolean {
  const v = config?.[key];
  return v === true || v === 'true';
}

/**
 * The config for a new "Act as": SYSTEM removes `jiraConnection` (and `fallbackToSystem`), SPECIFIC
 * sets the connection id (`pinned`), ACTING_USER sets `ACTING_USER` (and drops `allowActingUser`).
 * Undefined when the config became empty.
 */
export function withActAs(
  config: Record<string, unknown> | null | undefined,
  mode: ActAs,
  pinned: string | null = null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  delete next[JIRA_CONNECTION_KEY];
  if (mode !== 'ACTING_USER') delete next[FALLBACK_KEY];
  if (mode === 'ACTING_USER') {
    next[JIRA_CONNECTION_KEY] = ACTING_USER;
    delete next[ALLOW_ACTING_USER_KEY];
  } else if (mode === 'SPECIFIC' && pinned) next[JIRA_CONNECTION_KEY] = pinned;
  return Object.keys(next).length ? next : undefined;
}

/** Sets (true) or removes (false) a boolean flag of the node config. */
export function withFlag(
  config: Record<string, unknown> | null | undefined,
  key: string,
  on: boolean,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (on) next[key] = true;
  else delete next[key];
  return Object.keys(next).length ? next : undefined;
}

// ---------------------------------------------------------------- pinning on a workflow node

/** Key in a Jira task node's `overrides.config` that pins a connection. */
export const JIRA_CONNECTION_KEY = 'jiraConnection';

export function pinnedConnection(
  config: Record<string, unknown> | null | undefined,
): string | null {
  const v = config?.[JIRA_CONNECTION_KEY];
  return typeof v === 'string' && v && actAsOf(config) === 'SPECIFIC' ? v : null;
}

/** The config with the pin set (`id`) or removed (`null` = automatic); undefined when empty. */
export function withPinnedConnection(
  config: Record<string, unknown> | null | undefined,
  id: string | null,
): Record<string, unknown> | undefined {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (id) next[JIRA_CONNECTION_KEY] = id;
  else delete next[JIRA_CONNECTION_KEY];
  return Object.keys(next).length ? next : undefined;
}

export interface PinOption {
  id: string;
  label: string;
  disabled: boolean;
  /** Why it cannot be picked. */
  reason?: string;
}

/** Usable connections as picker options; those that do not cover `required` are disabled. */
export function pinOptions(
  usable: readonly Connection[],
  required: readonly string[],
): PinOption[] {
  return usable.map((c) => {
    const ok = covers(c, required);
    const have: readonly string[] = c.capabilities ?? [];
    const missing = capabilityLabels(required.filter((r) => !have.includes(r)));
    const scope = c.legacy
      ? $localize`:@@connections.pin.scope.legacy:legacy`
      : c.scope === 'USER'
        ? $localize`:@@connections.pin.scope.personal:personal`
        : c.scope === 'PROJECT'
          ? $localize`:@@connections.pin.scope.project:project`
          : $localize`:@@connections.pin.scope.platform:platform`;
    return {
      id: c.id,
      label: `${c.name} (${scope}: ${capabilityLabels(c.capabilities ?? [])})`,
      disabled: !ok,
      reason: ok ? undefined : $localize`:@@connections.pin.lacks:lacks ${missing}:missing:`,
    };
  });
}

/** `list` with the item at `index` moved by `delta` (bounded); a new array. */
export function moveItem<T>(list: readonly T[], index: number, delta: number): T[] {
  const to = Math.max(0, Math.min(list.length - 1, index + delta));
  const next = [...list];
  if (index < 0 || index >= list.length || to === index) return next;
  const [item] = next.splice(index, 1);
  next.splice(to, 0, item);
  return next;
}

// ---------------------------------------------------------------- the form

export const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9_]{0,19}$/;

/** Splits, trims, upper-cases and de-duplicates Jira project keys ("shop, crm" -> SHOP, CRM). */
export function normalizeProjectKeys(values: readonly string[]): string[] {
  const out: string[] = [];
  for (const v of values)
    for (const part of v.split(/[\s,;]+/)) {
      const k = part.trim().toUpperCase();
      if (k && !out.includes(k)) out.push(k);
    }
  return out;
}

export function invalidProjectKeys(keys: readonly string[]): string[] {
  return keys.filter((k) => !PROJECT_KEY_PATTERN.test(k));
}

export function isHttpsUrl(value: string): boolean {
  try {
    const u = new URL(value.trim());
    return u.protocol === 'https:' && !!u.hostname;
  } catch {
    return false;
  }
}

export interface ConnectionDraft {
  name: string;
  siteUrl: string;
  accountEmail: string;
  /** Empty = keep the stored token (edit). */
  secret: string;
  capabilities: string[];
  allowedProjectKeys: string[];
  verifyProjectKey: string;
  /** yyyy-MM-dd or empty. */
  secretExpiresAt: string;
  /** ADR 0035: scoped (recommended) or classic token. */
  tokenKind: 'CLASSIC' | 'SCOPED';
  /** Scoped: the site's cloud id; empty = discovered on save. */
  cloudId: string;
  /** Scoped: the scopes chosen when the token was created. */
  tokenScopes: string[];
  /** Classic: "this token can do everything my account can" (required unless already stored). */
  acknowledgeClassicToken: boolean;
  /** The stored connection already carries the acknowledgement (edit). */
  classicAcknowledged: boolean;
}

export function emptyDraft(): ConnectionDraft {
  return {
    name: '',
    siteUrl: '',
    accountEmail: '',
    secret: '',
    capabilities: ['ISSUE_READ'],
    allowedProjectKeys: [],
    verifyProjectKey: '',
    secretExpiresAt: '',
    tokenKind: 'SCOPED',
    cloudId: '',
    tokenScopes: recommendedScopes(['ISSUE_READ']),
    acknowledgeClassicToken: false,
    classicAcknowledged: false,
  };
}

export function draftOf(c: Connection): ConnectionDraft {
  return {
    name: c.name,
    siteUrl: c.siteUrl,
    accountEmail: c.accountEmail,
    secret: '',
    capabilities: [...(c.capabilities ?? [])],
    allowedProjectKeys: [...(c.allowedProjectKeys ?? [])],
    verifyProjectKey: c.verifyProjectKey ?? '',
    secretExpiresAt: c.secretExpiresAt ?? '',
    tokenKind: c.tokenKind === 'SCOPED' ? 'SCOPED' : 'CLASSIC',
    cloudId: c.cloudId ?? '',
    tokenScopes: [...(c.tokenScopes ?? [])],
    acknowledgeClassicToken: false,
    classicAcknowledged: !!c.classicTokenAcknowledged,
  };
}

export type DraftErrors = Partial<Record<keyof ConnectionDraft, string>>;

/** Client-side checks; `creating` makes the token required. */
export function validateDraft(d: ConnectionDraft, creating: boolean): DraftErrors {
  const e: DraftErrors = {};
  if (!d.name.trim()) e.name = $localize`:@@connections.form.error.name:Enter a name.`;
  if (!isHttpsUrl(d.siteUrl))
    e.siteUrl = $localize`:@@connections.form.error.siteUrl:Enter the https address of the Jira site, for example https://acme.atlassian.net.`;
  if (!/^[^@\s]+@[^@\s]+$/.test(d.accountEmail.trim()))
    e.accountEmail = $localize`:@@connections.form.error.email:Enter the e-mail of the Atlassian account.`;
  if (creating && !d.secret.trim())
    e.secret = $localize`:@@connections.form.error.secret:Paste the API token of the account.`;
  if (!d.capabilities.length)
    e.capabilities = $localize`:@@connections.form.error.capabilities:Pick at least one capability.`;
  const bad = invalidProjectKeys(d.allowedProjectKeys);
  if (bad.length)
    e.allowedProjectKeys = $localize`:@@connections.form.error.keys:Not a Jira project key: ${bad.join(', ')}:keys:.`;
  const sample = d.verifyProjectKey.trim().toUpperCase();
  if (sample && !PROJECT_KEY_PATTERN.test(sample))
    e.verifyProjectKey = $localize`:@@connections.form.error.sampleKey:Not a Jira project key.`;
  if (d.tokenKind === 'SCOPED') {
    if (!d.tokenScopes.length)
      e.tokenScopes = $localize`:@@connections.form.error.scopes:List the scopes you chose when you created the token.`;
    else if (invalidScopes(d.tokenScopes).length)
      e.tokenScopes = $localize`:@@connections.form.error.scopeFormat:Not an Atlassian scope: ${invalidScopes(d.tokenScopes).join(', ')}:scopes:.`;
    if (d.cloudId.trim() && !CLOUD_ID_PATTERN.test(d.cloudId.trim()))
      e.cloudId = $localize`:@@connections.form.error.cloudId:A cloud id looks like 1a2b3c4d-1234-5678-9abc-def012345678. Leave it empty to find it automatically.`;
  } else if (!d.acknowledgeClassicToken && !d.classicAcknowledged) {
    e.acknowledgeClassicToken = $localize`:@@connections.form.error.acknowledge:Confirm that you understand what a classic token can do, or use a scoped token.`;
  }
  return e;
}

/** The request body; the token only when one was entered (omitted = keep the stored one). */
export function toInput(d: ConnectionDraft): ConnectionInput {
  const input: ConnectionInput = {
    type: 'JIRA',
    name: d.name.trim(),
    siteUrl: d.siteUrl.trim().replace(/\/+$/, ''),
    accountEmail: d.accountEmail.trim(),
    capabilities: sortCapabilities(d.capabilities) as ConnectionInput['capabilities'],
    allowedProjectKeys: normalizeProjectKeys(d.allowedProjectKeys),
    verifyProjectKey: d.verifyProjectKey.trim().toUpperCase() || null,
    secretExpiresAt: d.secretExpiresAt || null,
    tokenKind: d.tokenKind,
  };
  if (d.tokenKind === 'SCOPED') {
    input.tokenScopes = normalizeScopes(d.tokenScopes);
    input.cloudId = d.cloudId.trim() || null;
  } else {
    input.acknowledgeClassicToken = d.acknowledgeClassicToken || d.classicAcknowledged;
  }
  if (d.secret.trim()) input.secret = d.secret.trim();
  return input;
}
