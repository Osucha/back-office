/**
 * Approval before external writes (ADR 0033): the node config of Jira write steps (Notify Jira,
 * Transition issue). `approval` is REQUIRED (also when missing, e.g. workflows saved before the
 * setting existed) or NOT_REQUIRED; `approvers` is a gate-like rule, default project owners and
 * maintainers (accountable owner). Pure helpers; the section component edits through them.
 */
import type { GateApprovers } from '../../../core/api/types';
import { normalizeApprovers } from './gate-rules';

export const APPROVAL_KEY = 'approval';
export const APPROVERS_KEY = 'approvers';

export type WriteApprovalMode = 'REQUIRED' | 'NOT_REQUIRED';

/** What the server uses when the node has no `approvers` (owners and maintainers with run.approve). */
export const DEFAULT_WRITE_APPROVERS: GateApprovers = {
  projectRoles: ['owner', 'maintainer'],
  users: [],
  accountable: 'owner',
  forbidSelfApproval: false,
};

type Config = Record<string, unknown> | null | undefined;

/** Missing or blank -> REQUIRED; unknown values are shown as REQUIRED too (the validator reports them). */
export function writeApprovalMode(config: Config): WriteApprovalMode {
  const v = config?.[APPROVAL_KEY];
  return typeof v === 'string' && v.trim().toUpperCase() === 'NOT_REQUIRED'
    ? 'NOT_REQUIRED'
    : 'REQUIRED';
}

/** The config with the mode set; REQUIRED is written explicitly so a saved workflow says it. */
export function withWriteApprovalMode(
  config: Config,
  mode: WriteApprovalMode,
): Record<string, unknown> {
  return { ...(config ?? {}), [APPROVAL_KEY]: mode };
}

/** Whether the node overrides the default approver rule. */
export function hasCustomWriteApprovers(config: Config): boolean {
  const v = config?.[APPROVERS_KEY];
  return !!v && typeof v === 'object' && !Array.isArray(v);
}

/** The approver rule in effect: the node's own, else the default. */
export function writeApprovers(config: Config): GateApprovers {
  if (!hasCustomWriteApprovers(config)) return DEFAULT_WRITE_APPROVERS;
  return normalizeApprovers(config![APPROVERS_KEY] as Partial<GateApprovers>);
}

/**
 * The config with an approver rule: `null` = back to the default rule (the key is removed). An empty
 * rule from the editor ("anyone with run.approve") is stored explicitly, because a missing rule means
 * the default (owners and maintainers), not "anyone".
 */
export function withWriteApprovers(
  config: Config,
  approvers: GateApprovers | null | 'default',
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...(config ?? {}) };
  if (approvers === 'default') delete next[APPROVERS_KEY];
  else next[APPROVERS_KEY] = normalizeApprovers(approvers ?? {});
  return next;
}

/** Keys the dedicated sections edit; the generic config form leaves them alone. */
export const SECTION_CONFIG_KEYS = [APPROVAL_KEY, APPROVERS_KEY] as const;
