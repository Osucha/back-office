/**
 * Pure logic of repository tokens (ADR 0051, SCM connections), covered by scm-connection-model.spec.ts:
 * capability labels and what GitLab needs for them, the token GitLab should issue for a selection,
 * grouping of the tokens by project repository, chips, localized test messages and the form draft.
 *
 * Capabilities are read as plain strings: the server may add values, an unknown one is humanized.
 */
import type { ProjectRepository } from '../../../core/api/types';
import { humanizeEnum, type Chip } from './connection-model';
import type {
  ScmCapability,
  ScmConnection,
  ScmConnectionInput,
  ScmMessage,
  ScmVerification,
} from './connection-types';

/** May reach agents as tools when the token is verified read-only. */
export const SCM_READ_CAPABILITIES: readonly ScmCapability[] = ['GIT_READ', 'CI_READ'];
/** Used only by Back Office itself (the runner's push, typed write steps), never by agents. */
export const SCM_WRITE_CAPABILITIES: readonly ScmCapability[] = [
  'PUSH_BRANCH',
  'MR_WRITE',
  'MR_COMMENT',
  'PIPELINE_RUN',
];
const ORDER: readonly string[] = [...SCM_READ_CAPABILITIES, ...SCM_WRITE_CAPABILITIES];

export type GitLabRole = 'Reporter' | 'Developer';

/** The GitLab scope and minimum role a capability needs (mirrors the backend's ScmCapability). */
export const SCM_CAPABILITY_NEEDS: Readonly<Record<string, { scope: string; role: GitLabRole }>> = {
  GIT_READ: { scope: 'read_repository', role: 'Reporter' },
  CI_READ: { scope: 'read_api', role: 'Reporter' },
  PUSH_BRANCH: { scope: 'write_repository', role: 'Developer' },
  MR_WRITE: { scope: 'api', role: 'Developer' },
  MR_COMMENT: { scope: 'api', role: 'Reporter' },
  PIPELINE_RUN: { scope: 'api', role: 'Developer' },
};

export function isScmWriteCapability(c: string): boolean {
  return !(SCM_READ_CAPABILITIES as readonly string[]).includes(c);
}

export function sortScmCapabilities(list: readonly string[]): string[] {
  const rank = (c: string) => {
    const i = ORDER.indexOf(c);
    return i < 0 ? ORDER.length : i;
  };
  return Array.from(new Set(list)).sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
}

/** Short label (chips, checkboxes). */
export function scmCapabilityLabel(c: string): string {
  switch (c) {
    case 'GIT_READ':
      return $localize`:@@connections.scm.capability.gitRead:Clone`;
    case 'CI_READ':
      return $localize`:@@connections.scm.capability.ciRead:Read CI`;
    case 'PUSH_BRANCH':
      return $localize`:@@connections.scm.capability.pushBranch:Push branch`;
    case 'MR_WRITE':
      return $localize`:@@connections.scm.capability.mrWrite:Write merge requests`;
    case 'MR_COMMENT':
      return $localize`:@@connections.scm.capability.mrComment:Comment on merge requests`;
    case 'PIPELINE_RUN':
      return $localize`:@@connections.scm.capability.pipelineRun:Run pipelines`;
    default:
      return humanizeEnum(c);
  }
}

/** What the capability is for (checkbox explanation). */
export function scmCapabilityPurpose(c: string): string {
  switch (c) {
    case 'GIT_READ':
      return $localize`:@@connections.scm.capability.gitRead.purpose:Clone and fetch the repository.`;
    case 'CI_READ':
      return $localize`:@@connections.scm.capability.ciRead.purpose:Read pipelines and job logs.`;
    case 'PUSH_BRANCH':
      return $localize`:@@connections.scm.capability.pushBranch.purpose:Push the agent's branch after the agent has finished.`;
    case 'MR_WRITE':
      return $localize`:@@connections.scm.capability.mrWrite.purpose:Create and update merge requests.`;
    case 'MR_COMMENT':
      return $localize`:@@connections.scm.capability.mrComment.purpose:Post review comments on merge requests.`;
    case 'PIPELINE_RUN':
      return $localize`:@@connections.scm.capability.pipelineRun.purpose:Start and retry pipelines.`;
    default:
      return '';
  }
}

/** "GitLab: scope read_api, role Reporter or higher." */
export function scmCapabilityHint(c: string): string {
  const need = SCM_CAPABILITY_NEEDS[c];
  if (!need) return '';
  const scope = need.scope;
  const role = need.role;
  return $localize`:@@connections.scm.capability.needs:GitLab: scope ${scope}:scope:, role ${role}:role: or higher.`;
}

export function scmCapabilityLabels(list: readonly string[]): string {
  return sortScmCapabilities(list).map(scmCapabilityLabel).join(', ');
}

/**
 * The token GitLab should issue for these capabilities: the least role and the fewest scopes
 * (`write_repository` includes `read_repository`, `api` includes `read_api`). Null role = nothing picked.
 */
export function recommendedToken(capabilities: readonly string[]): {
  role: GitLabRole | null;
  scopes: string[];
} {
  const needs = capabilities.map((c) => SCM_CAPABILITY_NEEDS[c]).filter((n) => !!n);
  if (!needs.length) return { role: null, scopes: [] };
  const role: GitLabRole = needs.some((n) => n.role === 'Developer') ? 'Developer' : 'Reporter';
  const set = new Set(needs.map((n) => n.scope));
  if (set.has('write_repository')) set.delete('read_repository');
  if (set.has('api')) set.delete('read_api');
  const scopeOrder = ['read_repository', 'write_repository', 'read_api', 'api'];
  return { role, scopes: scopeOrder.filter((s) => set.has(s)) };
}

/** Read and write capabilities in one token: it can never reach an agent. */
export function mixesReadAndWrite(capabilities: readonly string[]): boolean {
  return (
    capabilities.some((c) => !isScmWriteCapability(c)) &&
    capabilities.some((c) => isScmWriteCapability(c))
  );
}

// ---------------------------------------------------------------- grouping

export interface RepositoryTokens {
  key: string;
  /** The repository's URL; null for an orphaned key (repository removed from the project). */
  url: string | null;
  primary: boolean;
  /** False: the repository is no longer part of the project; its tokens are never used. */
  exists: boolean;
  connections: ScmConnection[];
}

/**
 * One group per project repository (project order, also without tokens), then the keys of tokens
 * whose repository was removed (orphaned, sorted). Tokens: read-only first, then by name.
 */
export function groupByRepository(
  connections: readonly ScmConnection[],
  repositories: readonly ProjectRepository[],
): RepositoryTokens[] {
  const known = new Set(repositories.map((r) => r.key));
  const sorted = [...connections].sort(
    (a, b) =>
      writeCount(a) - writeCount(b) || a.name.localeCompare(b.name, undefined, { numeric: true }),
  );
  const groups: RepositoryTokens[] = repositories.map((r) => ({
    key: r.key,
    url: r.url,
    primary: !!r.primary,
    exists: true,
    connections: sorted.filter((c) => c.repositoryKey === r.key && c.repositoryExists !== false),
  }));
  const orphanKeys = Array.from(
    new Set(
      sorted
        .filter((c) => !known.has(c.repositoryKey) || c.repositoryExists === false)
        .map((c) => c.repositoryKey),
    ),
  ).sort();
  for (const key of orphanKeys)
    groups.push({
      key,
      url: null,
      primary: false,
      exists: false,
      connections: sorted.filter(
        (c) =>
          c.repositoryKey === key && (!known.has(c.repositoryKey) || c.repositoryExists === false),
      ),
    });
  return groups;
}

function writeCount(c: ScmConnection): number {
  return (c.capabilities ?? []).filter(isScmWriteCapability).length;
}

// ---------------------------------------------------------------- chips and messages

export function scmVerificationChip(v: ScmVerification | null | undefined): Chip {
  if (!v)
    return { tone: 'neutral', label: $localize`:@@connections.scm.verification.none:not tested` };
  switch (v.status) {
    case 'OK':
      return { tone: 'ok', label: $localize`:@@connections.scm.verification.ok:verified` };
    case 'FAILED':
      return { tone: 'bad', label: $localize`:@@connections.scm.verification.failed:test failed` };
    default:
      return v.writePowers?.length
        ? {
            tone: 'warn',
            label: $localize`:@@connections.scm.verification.overPrivileged:more rights than declared`,
          }
        : {
            tone: 'warn',
            label: $localize`:@@connections.scm.verification.warning:test with warnings`,
          };
  }
}

/** Whether the token may reach agents (verified read-only) or is for Back Office only. */
export function agentChip(c: Pick<ScmConnection, 'capabilities' | 'readOnly'>): Chip {
  if (c.readOnly)
    return {
      tone: 'ok',
      label: $localize`:@@connections.scm.agent.readOnly:read-only, verified`,
      title: $localize`:@@connections.scm.agent.readOnlyTitle:Declared read-only and tested without write rights: may be given to agents as a read-only tool.`,
    };
  if ((c.capabilities ?? []).some(isScmWriteCapability))
    return {
      tone: 'neutral',
      label: $localize`:@@connections.scm.agent.backOffice:Back Office only`,
      title: $localize`:@@connections.scm.agent.backOfficeTitle:Has a write capability: used only by Back Office itself, never given to agents.`,
    };
  return {
    tone: 'warn',
    label: $localize`:@@connections.scm.agent.unverified:read-only not verified`,
    title: $localize`:@@connections.scm.agent.unverifiedTitle:Declared read-only, but the last test failed or found write rights: agents do not get it.`,
  };
}

/**
 * Localized text of a test message. The server's English text is used for unknown codes and as the
 * detail of UNREACHABLE (the host's own answer).
 */
export function scmMessageText(m: ScmMessage, v?: ScmVerification | null): string {
  if (m.code.startsWith('MISSING_')) {
    const c = m.code.slice('MISSING_'.length);
    const need = SCM_CAPABILITY_NEEDS[c];
    const label = scmCapabilityLabel(c);
    if (!need) return m.text;
    const scope = need.scope;
    const role = need.role;
    return $localize`:@@connections.scm.message.missing:${label}:capability: is missing: it needs scope ${scope}:scope: and role ${role}:role: or higher.`;
  }
  switch (m.code) {
    case 'UNREACHABLE': {
      const detail = m.text;
      return $localize`:@@connections.scm.message.unreachable:The repository host could not be reached or refused the token: ${detail}:detail:`;
    }
    case 'EXCESS': {
      const powers = (v?.writePowers ?? []).join(', ');
      return powers
        ? $localize`:@@connections.scm.message.excess:The token can do more than declared: ${powers}:powers:. Use a token with fewer rights.`
        : m.text;
    }
    case 'UNPROTECTED_DEFAULT_BRANCH':
      return $localize`:@@connections.scm.message.unprotected:The default branch is not protected: anyone who can push can change it directly.`;
    case 'ROLE_TOO_HIGH': {
      const role = v?.role ?? '';
      return role
        ? $localize`:@@connections.scm.message.roleTooHigh:Role ${role}:role: is more than any capability needs (at most Developer).`
        : m.text;
    }
    case 'NO_API':
      return $localize`:@@connections.scm.message.noApi:The token cannot call the API, so its role and expiry were not checked.`;
    default:
      return m.text;
  }
}

// ---------------------------------------------------------------- form

export interface ScmDraft {
  repositoryKey: string;
  name: string;
  capabilities: string[];
  /** Empty = keep the stored token (edit). */
  token: string;
  /** yyyy-MM-dd or empty (= the expiry GitLab reports). */
  secretExpiresAt: string;
}

export function emptyScmDraft(repositoryKey = ''): ScmDraft {
  return {
    repositoryKey,
    name: '',
    capabilities: ['GIT_READ'],
    token: '',
    secretExpiresAt: '',
  };
}

export function scmDraftOf(c: ScmConnection): ScmDraft {
  return {
    repositoryKey: c.repositoryKey,
    name: c.name,
    capabilities: sortScmCapabilities(c.capabilities ?? []),
    token: '',
    secretExpiresAt: c.secretExpiresAt ?? '',
  };
}

export type ScmDraftErrors = Partial<Record<keyof ScmDraft, string>>;

/** Client-side checks; `tokenRequired` on create, replace and test. */
export function validateScmDraft(
  d: ScmDraft,
  tokenRequired: boolean,
  repositoryKeys: readonly string[],
): ScmDraftErrors {
  const e: ScmDraftErrors = {};
  if (!d.repositoryKey || !repositoryKeys.includes(d.repositoryKey))
    e.repositoryKey = $localize`:@@connections.scm.form.error.repository:Choose a repository of this project.`;
  const name = d.name.trim();
  if (!name) e.name = $localize`:@@connections.scm.form.error.name:Enter a name.`;
  else if (name.length > 80)
    e.name = $localize`:@@connections.scm.form.error.nameLength:At most 80 characters.`;
  if (!d.capabilities.length)
    e.capabilities = $localize`:@@connections.scm.form.error.capabilities:Pick at least one capability.`;
  if (tokenRequired && !d.token.trim())
    e.token = $localize`:@@connections.scm.form.error.token:Paste the access token.`;
  return e;
}

/** The request body; the token only when one was entered (omitted = keep the stored one). */
export function toScmInput(d: ScmDraft): ScmConnectionInput {
  const input: ScmConnectionInput = {
    repositoryKey: d.repositoryKey,
    name: d.name.trim(),
    provider: 'GITLAB',
    capabilities: sortScmCapabilities(d.capabilities) as ScmCapability[],
    secretExpiresAt: d.secretExpiresAt || null,
  };
  const token = d.token.trim();
  if (token) input.token = token;
  return input;
}
