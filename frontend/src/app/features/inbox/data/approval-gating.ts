/**
 * Which decision buttons of a human gate are enabled for the caller. The backend enforces the same
 * rules (`canDecide`, approver roles/users, self-approval, required checklist items); the UI only
 * explains them up front instead of failing on submit.
 */
import type { ApprovalRequest, ChecklistItem } from '../../../core/api/types';

const APPROVE_OPTIONS = new Set(['APPROVE', 'MERGE', 'ACCEPT', 'YES']);
const COMMENT_OPTIONS = new Set(['REVISE', 'REJECT']);

export const DEFAULT_CANNOT_DECIDE = $localize`:@@inbox.gating.notApprover:You are not an approver of this gate.`;
export const COMMENT_FIRST = $localize`:@@inbox.gating.commentFirst:Add a comment first`;
export const CHECKLIST_FIRST = $localize`:@@inbox.gating.checklistFirst:Tick all required checklist items first`;

export function isApproveOption(option: string): boolean {
  return APPROVE_OPTIONS.has(option.toUpperCase());
}

export function needsComment(option: string): boolean {
  return COMMENT_OPTIONS.has(option.toUpperCase());
}

/** `required` defaults to true in the contract. */
export function isRequired(item: ChecklistItem): boolean {
  return item.required !== false;
}

export function missingRequired(
  approval: ApprovalRequest,
  checked: ReadonlySet<string>,
): ChecklistItem[] {
  return (approval.checklist ?? []).filter((i) => isRequired(i) && !checked.has(i.id));
}

export interface OptionState {
  enabled: boolean;
  /** Tooltip explaining why the option is disabled. */
  reason: string | null;
}

export function optionState(
  approval: ApprovalRequest,
  option: string,
  checked: ReadonlySet<string>,
  comment: string,
): OptionState {
  if (approval.status && approval.status !== 'OPEN') {
    return {
      enabled: false,
      reason: $localize`:@@inbox.gating.notOpen:This approval is no longer open`,
    };
  }
  if (approval.canDecide === false) {
    return { enabled: false, reason: approval.cannotDecideReason || DEFAULT_CANNOT_DECIDE };
  }
  if (needsComment(option) && !comment.trim()) return { enabled: false, reason: COMMENT_FIRST };
  if (isApproveOption(option) && missingRequired(approval, checked).length) {
    return { enabled: false, reason: CHECKLIST_FIRST };
  }
  return { enabled: true, reason: null };
}

/**
 * Options offered as one-click buttons in lists (inbox, dashboard): only when the caller may
 * decide, the option needs no comment, and approving needs no checklist ticks.
 */
export function quickDecisionOptions(approval: ApprovalRequest): string[] {
  if (approval.canDecide === false || (approval.status && approval.status !== 'OPEN')) return [];
  const hasRequired = (approval.checklist ?? []).some(isRequired);
  return (approval.options ?? []).filter(
    (o) => !needsComment(o) && !(isApproveOption(o) && hasRequired),
  );
}
