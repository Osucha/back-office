/**
 * Who decides an open gate (ADR 0028): the named assignees (a run-time assignment by a project owner or
 * maintainer, else the gate's own named approvers), else the gate's project roles, else anyone with
 * `run.approve` on the project. The backend enforces it (`canDecide`); the UI only says it.
 */
import type { ApprovalRequest } from '../../../core/api/types';
import { roleName } from '../../../core/i18n/catalog-labels';

/** "Ann Smith, Bob Jones", "Approver, Owner" or "anyone who can approve". */
export function assignedToText(a: ApprovalRequest): string {
  const names = (a.assignees ?? []).map((x) => x.displayName || x.userId);
  if (names.length) return names.join(', ');
  const roles = (a.approvers?.projectRoles ?? []).map((r) => roleName(r));
  if (roles.length) return roles.join(', ');
  return $localize`:@@inbox.assign.anyone:anyone who can approve`;
}

/** Whether `userId` is one of the named assignees. */
export function assignedTo(a: ApprovalRequest, userId: string | null | undefined): boolean {
  return !!userId && (a.assignees ?? []).some((x) => x.userId === userId);
}

/** Whether the gate was (re)assigned at run time instead of following the definition. */
export function isReassigned(a: ApprovalRequest): boolean {
  return !!a.assignment?.userIds?.length;
}
