/**
 * Approver rules and the Definition-of-Ready/Done checklist of a HUMAN_GATE node (RACI on gates):
 * which project roles or users may decide, the single accountable role, self-approval, and the
 * checklist items that must be ticked before "approve".
 */
import type { ChecklistItem, GateApprovers } from '../../../core/api/types';
import { SLUG_PATTERN } from '../../../core/forms/patterns';

export const EMPTY_APPROVERS: GateApprovers = {
  projectRoles: [],
  users: [],
  accountable: null,
  forbidSelfApproval: false,
};

export function normalizeApprovers(a: Partial<GateApprovers> | null | undefined): GateApprovers {
  return {
    projectRoles: a?.projectRoles ?? [],
    users: a?.users ?? [],
    accountable: a?.accountable ?? null,
    forbidSelfApproval: a?.forbidSelfApproval ?? false,
  };
}

/**
 * New role selection; the accountable role is kept only while it is one of the selected roles
 * (with no roles selected any project role may be accountable).
 */
export function withProjectRoles(a: GateApprovers, roles: string[]): GateApprovers {
  const accountable =
    a.accountable && (roles.length === 0 || roles.includes(a.accountable)) ? a.accountable : null;
  return { ...a, projectRoles: roles, accountable };
}

/** Roles offered as "accountable": the selected ones, or every project role when none is. */
export function accountableOptions(a: GateApprovers, allRoles: string[]): string[] {
  return a.projectRoles?.length ? a.projectRoles : allRoles;
}

/** Approvers stored on the node: `null` when nothing is restricted (the contract default). */
export function compactApprovers(a: Partial<GateApprovers>): GateApprovers | null {
  const n = normalizeApprovers(a);
  const empty =
    !n.projectRoles?.length && !n.users?.length && !n.accountable && !n.forbidSelfApproval;
  return empty ? null : n;
}

/** Next free checklist id: `item-1`, `item-2`, ... */
export function nextChecklistId(items: readonly ChecklistItem[]): string {
  const taken = new Set(items.map((i) => i.id));
  let n = items.length + 1;
  while (taken.has(`item-${n}`)) n++;
  return `item-${n}`;
}

export function addChecklistItem(items: readonly ChecklistItem[]): ChecklistItem[] {
  return [...items, { id: nextChecklistId(items), text: '', required: true }];
}

export function patchChecklistItem(
  items: readonly ChecklistItem[],
  index: number,
  changes: Partial<ChecklistItem>,
): ChecklistItem[] {
  return items.map((item, i) => (i === index ? { ...item, ...changes } : item));
}

export function removeChecklistItem(
  items: readonly ChecklistItem[],
  index: number,
): ChecklistItem[] {
  return items.filter((_, i) => i !== index);
}

/** Problems of the gate rules, shown under the editor and as node errors. */
export function gateRuleProblems(
  approvers: Partial<GateApprovers> | null | undefined,
  checklist: readonly ChecklistItem[] | null | undefined,
): string[] {
  const problems: string[] = [];
  const a = normalizeApprovers(approvers);
  if (a.accountable && a.projectRoles?.length && !a.projectRoles.includes(a.accountable)) {
    problems.push(
      $localize`:@@workflows.gate.problem.accountable:Accountable role "${a.accountable}:role:" must be one of the approver roles`,
    );
  }
  const seen = new Set<string>();
  (checklist ?? []).forEach((item, i) => {
    const n = i + 1;
    if (!item.id || !SLUG_PATTERN.test(item.id)) {
      problems.push(
        $localize`:@@workflows.gate.problem.itemSlug:Checklist item ${n}:number:: id must be a slug (lowercase letters, digits, dashes)`,
      );
    } else if (seen.has(item.id)) {
      problems.push(
        $localize`:@@workflows.gate.problem.itemDuplicate:Checklist item ${n}:number:: duplicate id "${item.id}:id:"`,
      );
    }
    seen.add(item.id);
    if (!item.text?.trim()) {
      problems.push(
        $localize`:@@workflows.gate.problem.itemText:Checklist item ${n}:number:: text is required`,
      );
    }
  });
  return problems;
}
