/**
 * Audit operation codes grouped for the "Operation" filter, readable labels, filter presets and the
 * result badge (glyph + word, never colour alone).
 */
import type { AuditResult } from '../../../core/api/types';
import type { BatonHolder } from '../../../core/ui/relay/baton';
import { KNOWN_OPERATIONS } from './audit-query';

export interface OperationGroup {
  label: string;
  operations: string[];
}

/** Sign-in and account security operations (ADR 0021 + OIDC sign-in). */
export const SECURITY_OPERATIONS = [
  'LOGIN_SUCCESS',
  'LOGIN_FAILURE',
  'LOGOUT',
  'ACCOUNT_LOCKED',
  'ACCOUNT_UNLOCK',
  'PASSWORD_CHANGE',
  'PASSWORD_RESET_ISSUE',
  'PASSWORD_RESET_COMPLETE',
  'MFA_ENABLE',
  'MFA_DISABLE',
  'MFA_RESET',
  'RECOVERY_CODE_USED',
  'RECOVERY_CODES_REGENERATE',
  'AUTH_MODE_CHANGE',
] as const;

const GROUPS: ReadonlyArray<{ label: string; match: (op: string) => boolean }> = [
  {
    label: $localize`:@@admin.auditOps.group.security:Sign-in and security`,
    match: (op) => (SECURITY_OPERATIONS as readonly string[]).includes(op),
  },
  {
    label: $localize`:@@admin.auditOps.group.access:Access`,
    match: (op) => op === 'ACCESS_DENIED' || op === 'AUDIT_VIEW',
  },
  {
    label: $localize`:@@admin.auditOps.group.users:Users and groups`,
    match: (op) => /^(USER|GROUP)_/.test(op),
  },
  {
    label: $localize`:@@admin.auditOps.group.roles:Roles and grants`,
    match: (op) => /^(ROLE|GRANT)_/.test(op),
  },
  { label: $localize`:@@admin.auditOps.group.tokens:Tokens`, match: (op) => /TOKEN_/.test(op) },
  {
    label: $localize`:@@admin.auditOps.group.projects:Projects and members`,
    match: (op) => /^(PROJECT|MEMBER)_/.test(op),
  },
  {
    label: $localize`:@@admin.auditOps.group.workflows:Workflows and runs`,
    match: (op) => /^(WORKFLOW|RUN)_/.test(op),
  },
];

/** Every known operation, security first, in groups for `nz-option-group`. */
export function operationGroups(
  operations: readonly string[] = [...SECURITY_OPERATIONS, ...KNOWN_OPERATIONS],
): OperationGroup[] {
  const unique = [...new Set(operations)];
  const out: OperationGroup[] = GROUPS.map((g) => ({ label: g.label, operations: [] as string[] }));
  const other: string[] = [];
  for (const op of unique) {
    const i = GROUPS.findIndex((g) => g.match(op));
    if (i >= 0) out[i].operations.push(op);
    else other.push(op);
  }
  if (other.length)
    out.push({ label: $localize`:@@admin.auditOps.group.other:Other`, operations: other });
  return out.filter((g) => g.operations.length);
}

const LABELS: Record<string, string> = {
  LOGIN_SUCCESS: $localize`:@@admin.auditOps.op.loginSuccess:Signed in`,
  LOGIN_FAILURE: $localize`:@@admin.auditOps.op.loginFailure:Sign-in failed`,
  LOGOUT: $localize`:@@admin.auditOps.op.logout:Signed out`,
  ACCOUNT_LOCKED: $localize`:@@admin.auditOps.op.accountLocked:Account locked`,
  ACCOUNT_UNLOCK: $localize`:@@admin.auditOps.op.accountUnlock:Account unlocked`,
  PASSWORD_CHANGE: $localize`:@@admin.auditOps.op.passwordChange:Password changed`,
  PASSWORD_RESET_ISSUE: $localize`:@@admin.auditOps.op.passwordResetIssue:Set-password link issued`,
  PASSWORD_RESET_COMPLETE: $localize`:@@admin.auditOps.op.passwordResetComplete:Password set with a link`,
  MFA_ENABLE: $localize`:@@admin.auditOps.op.mfaEnable:MFA enabled`,
  MFA_DISABLE: $localize`:@@admin.auditOps.op.mfaDisable:MFA disabled`,
  MFA_RESET: $localize`:@@admin.auditOps.op.mfaReset:MFA reset by an admin`,
  RECOVERY_CODE_USED: $localize`:@@admin.auditOps.op.recoveryCodeUsed:Recovery code used`,
  RECOVERY_CODES_REGENERATE: $localize`:@@admin.auditOps.op.recoveryCodesRegenerate:Recovery codes regenerated`,
  AUTH_MODE_CHANGE: $localize`:@@admin.auditOps.op.authModeChange:Sign-in method changed`,
  ACCESS_DENIED: $localize`:@@admin.auditOps.op.accessDenied:Access denied`,
  AUDIT_VIEW: $localize`:@@admin.auditOps.op.auditView:Audit log searched`,
  SETTINGS_UPDATE: $localize`:@@admin.auditOps.op.settingsUpdate:Platform settings changed`,
  GRANT_CHANGE: $localize`:@@admin.auditOps.op.grantChange:Grants changed`,
  RUN_GATE_DECIDED: $localize`:@@admin.auditOps.op.runGateDecided:Gate decided`,
};

/** "LOGIN_FAILURE" -> "Sign-in failed"; unknown codes: "Ci token create" (sentence case). */
export function operationLabel(op: string | null | undefined): string {
  if (!op) return '';
  if (LABELS[op]) return LABELS[op];
  const words = op.toLowerCase().split('_').filter(Boolean);
  if (!words.length) return op;
  const text = words.map((w) => (w === 'ci' ? 'CI' : w === 'mfa' ? 'MFA' : w)).join(' ');
  return text[0].toUpperCase() + text.slice(1);
}

export interface AuditPreset {
  id: string;
  label: string;
  /** URL filter values set by the preset. */
  value: Record<string, string | null>;
}

export const AUDIT_PRESETS: readonly AuditPreset[] = [
  {
    id: 'failed-sign-ins',
    label: $localize`:@@admin.auditOps.preset.failedSignIns:Failed sign-ins`,
    value: { operation: 'LOGIN_FAILURE', result: null },
  },
  {
    id: 'lockouts',
    label: $localize`:@@admin.auditOps.preset.lockouts:Lockouts`,
    value: { operation: 'ACCOUNT_LOCKED', result: null },
  },
  {
    id: 'auth-changes',
    label: $localize`:@@admin.auditOps.preset.authChanges:Sign-in method changes`,
    value: { operation: 'AUTH_MODE_CHANGE', result: null },
  },
  {
    id: 'denied',
    label: $localize`:@@admin.auditOps.preset.denied:Denied access`,
    value: { operation: null, result: 'DENIED' },
  },
];

/** A preset is active when every value it sets matches the current filters. */
export function presetActive(
  preset: AuditPreset,
  values: Readonly<Record<string, string | null | undefined | string[]>>,
): boolean {
  return Object.entries(preset.value).every(([k, v]) => (values[k] ?? null) === v);
}

export interface ResultBadge {
  holder: BatonHolder;
  label: string;
}

export function resultBadge(result: AuditResult | string | null | undefined): ResultBadge {
  if (result === 'SUCCESS')
    return { holder: 'done', label: $localize`:@@admin.auditOps.result.success:success` };
  if (result === 'DENIED')
    return { holder: 'blocked', label: $localize`:@@admin.auditOps.result.denied:denied` };
  if (result === 'FAILURE')
    return { holder: 'blocked', label: $localize`:@@admin.auditOps.result.failed:failed` };
  return {
    holder: 'idle',
    label: result ? result.toLowerCase() : $localize`:@@admin.auditOps.result.unknown:unknown`,
  };
}
