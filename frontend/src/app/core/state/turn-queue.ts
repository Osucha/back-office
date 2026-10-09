/**
 * The *Your turn* queue (pure): open gates the user can decide, failed runs the user started or may
 * retry, recently failed CI executions of the user's projects, and monthly budgets the user manages
 * that crossed the alert threshold or the limit. Ordered by urgency (gates, then run failures and
 * exceeded budgets, then CI failures, then budget alerts) and, within a group, by age (longest
 * waiting first).
 */
import type {
  ApprovalRequest,
  BudgetStatus,
  CiExecutionSummary,
  Connection,
  WorkflowRunSummary,
} from '../api/types';
import { batonOf } from '../ui/relay/baton';
import { icu } from '../i18n/icu';
import { formatCost } from '../ui/relay/format';
import type { TurnItem } from '../ui/relay/turn';
import { budgetBaton, formatShare, budgetShare, monthName, needsAttention } from './budget-state';

export const FAILED_RUN_WINDOW_MS = 7 * 24 * 3600_000;
export const FAILED_CI_WINDOW_MS = 3 * 24 * 3600_000;

export interface TurnQueueInput {
  approvals: readonly ApprovalRequest[];
  failedRuns: readonly WorkflowRunSummary[];
  ciExecutions: readonly CiExecutionSummary[];
  userId: string | null | undefined;
  /** `run.start` on the project (retry). */
  canRetry: (projectId: string | null | undefined) => boolean;
  /** The user's projects for CI failures; `null` = all visible projects. */
  projectIds: ReadonlySet<string> | null;
  /** `GET /budgets`: the platform budget (admins) and the caller's project budgets. */
  budgets?: readonly BudgetStatus[];
  /** May change the budget: `project.manage` on the project, `admin.settings.manage` for PLATFORM. */
  canManageBudget?: (b: BudgetStatus) => boolean;
  /** `GET /connections/expiring`: tokens the user manages that expire within 14 days or expired. */
  expiringConnections?: readonly Connection[];
  now?: number;
}

/** `userId`: the signed-in user; a gate assigned to them by name says so first (ADR 0028). */
export function gateTurn(a: ApprovalRequest, userId?: string | null): TurnItem {
  const required = (a.checklist ?? []).filter((i) => i.required !== false).length;
  const role = a.approvers?.accountable;
  const mine = !!userId && (a.assignees ?? []).some((x) => x.userId === userId);
  const by = a.assignment?.assignedBy?.displayName;
  const why = [
    mine
      ? by
        ? $localize`:@@core.turn.assignedToYouBy:Assigned to you by ${by}:name:`
        : $localize`:@@core.turn.assignedToYou:Assigned to you`
      : null,
    role
      ? $localize`:@@core.turn.accountable:Accountable: ${role}:role:`
      : a.approvers?.projectRoles?.length
        ? $localize`:@@core.turn.approvers:Approvers: ${a.approvers.projectRoles.join(', ')}:roles:`
        : $localize`:@@core.turn.youCanApprove:You can approve`,
    required
      ? icu(
          $localize`:@@core.turn.requiredChecks:{count, plural, one {# required check} other {# required checks}}`,
          { count: required },
        )
      : null,
    a.context?.confidence !== null && a.context?.confidence !== undefined
      ? $localize`:@@core.turn.confidence:agent confidence ${Math.round(a.context.confidence * 100)}:percent:%`
      : null,
  ]
    .filter(Boolean)
    .join(' · ');
  const write = a.kind === 'WRITE_CONFIRMATION';
  return {
    id: `gate:${a.id}`,
    kind: 'gate',
    urgency: 0,
    title: a.question || $localize`:@@core.turn.decisionNeeded:Decision needed`,
    where: [a.projectKey, a.issueKey || a.workflowName, a.nodeId].filter((x): x is string => !!x),
    why: write
      ? [$localize`:@@core.turn.writeWhy:Nothing is written to Jira until you approve`, why]
          .filter(Boolean)
          .join(' · ')
      : why,
    since: a.createdAt ?? null,
    baton: batonOf.approval(a),
    primary: write
      ? { label: $localize`:@@core.turn.reviewWrite:Review the write`, keys: 'a' }
      : { label: $localize`:@@core.turn.reviewGate:Review gate`, keys: 'a' },
    link: ['/inbox', a.id],
    approval: a,
  };
}

/** Days from `now` to the `yyyy-mm-dd` date (negative: past), in whole UTC days. */
export function daysUntil(date: string | null | undefined, now: number): number | null {
  if (!date) return null;
  const at = Date.parse(`${date}T00:00:00Z`);
  if (Number.isNaN(at)) return null;
  const today = Date.parse(new Date(now).toISOString().slice(0, 10) + 'T00:00:00Z');
  return Math.round((at - today) / 86_400_000);
}

/** A Jira token the user manages expires within 14 days or has expired: replace it in time. */
export function connectionTurn(c: Connection, now: number): TurnItem {
  const days = daysUntil(c.secretExpiresAt, now) ?? 0;
  const expired = days < 0;
  const name = c.name;
  return {
    id: `connection:${c.id}`,
    kind: 'connection',
    urgency: expired ? 1 : 3,
    title: expired
      ? $localize`:@@core.turn.tokenExpired:The Jira token of ${name}:name: has expired`
      : icu(
          $localize`:@@core.turn.tokenExpires:{days, plural, =0 {The Jira token of {name} expires today} one {The Jira token of {name} expires tomorrow} other {The Jira token of {name} expires in # days}}`,
          { days, name },
        ),
    where: [
      c.scope === 'PLATFORM'
        ? $localize`:@@core.turn.platformConnection:Platform connection`
        : $localize`:@@core.turn.projectConnection:Project connection`,
      c.accountEmail,
      c.secretExpiresAt ?? null,
    ].filter((x): x is string => !!x),
    why: expired
      ? $localize`:@@core.turn.tokenExpiredWhy:Jira steps using it fail until you replace the token`
      : $localize`:@@core.turn.tokenExpiresWhy:You manage this connection; create a new API token in Atlassian and replace it here`,
    since: null,
    baton: {
      holder: expired ? 'blocked' : 'you',
      label: expired
        ? $localize`:@@core.turn.tokenExpiredLabel:token expired`
        : $localize`:@@core.turn.tokenExpiringLabel:token expiring`,
    },
    primary: { label: $localize`:@@core.turn.replaceToken:Replace token`, keys: 'Enter' },
    // ADR 0035: a personal connection is replaced under My account
    link:
      c.scope === 'USER'
        ? ['/account']
        : c.scope === 'PLATFORM' || !c.projectId
          ? ['/admin/connections']
          : ['/projects', c.projectId],
    queryParams:
      c.scope === 'USER'
        ? { section: 'connections' }
        : c.scope === 'PLATFORM' || !c.projectId
          ? undefined
          : { tab: 'connections' },
    connection: c,
  };
}

export function failedRunTurn(r: WorkflowRunSummary, startedByMe: boolean): TurnItem {
  return {
    id: `run:${r.id}`,
    kind: 'failed-run',
    urgency: 1,
    title: $localize`:@@core.turn.runFailed:${(r.issueKey ? r.issueKey + ' · ' : '') + (r.workflowName ?? $localize`:@@core.turn.run:Run`)}:run: failed`,
    where: [r.projectKey, r.workflowName, r.currentNodeId].filter((x): x is string => !!x),
    why: startedByMe
      ? $localize`:@@core.turn.youStarted:You started this run`
      : $localize`:@@core.turn.youCanRetry:You can retry runs in this project`,
    since: r.finishedAt ?? r.createdAt ?? null,
    baton: batonOf.run(r),
    primary: { label: $localize`:@@core.turn.openRun:Open run`, keys: 'Enter' },
    link: ['/runs', r.id],
    run: r,
  };
}

export function failedCiTurn(x: CiExecutionSummary): TurnItem {
  const mr = x.context?.mergeRequest;
  return {
    id: `ci:${x.id}`,
    kind: 'failed-ci',
    urgency: 2,
    title: mr?.iid
      ? `!${mr.iid} ${mr.title ?? ''}`.trim()
      : $localize`:@@core.turn.ciOn:${x.entrypoint ?? 'CI'}:entrypoint: on ${x.context?.ref ?? x.context?.commitSha?.slice(0, 8) ?? $localize`:@@core.turn.aCommit:a commit`}:ref:`,
    where: [
      x.projectKey,
      x.entrypoint,
      x.mode === 'ADVISORY'
        ? $localize`:@@core.turn.advisory:advisory`
        : $localize`:@@core.turn.blocking:blocking`,
    ].filter((v): v is string => !!v),
    why:
      x.gateResult === 'FAIL'
        ? $localize`:@@core.turn.ciGateFailed:The review gate failed in your project`
        : $localize`:@@core.turn.ciFailed:The CI execution failed in your project`,
    since: x.finishedAt ?? x.createdAt ?? null,
    baton: batonOf.ci(x),
    primary: { label: $localize`:@@core.turn.openReview:Open review`, keys: 'Enter' },
    link: ['/ci', x.id],
    ci: x,
  };
}

/**
 * A budget over the alert threshold or the limit, for someone who can raise it. Exceeded budgets
 * with a hard stop halt agents, so they rank with failed runs; alerts come last.
 */
export function budgetTurn(b: BudgetStatus): TurnItem {
  const platform = b.scope === 'PLATFORM';
  const exceeded = b.state === 'EXCEEDED';
  const name = platform
    ? $localize`:@@core.turn.platformBudget:Platform budget`
    : $localize`:@@core.turn.projectBudget:${b.projectKey ?? $localize`:@@core.turn.project:Project`}:project: budget`;
  const pct = formatShare(budgetShare(b.spentUsd, b.monthlyLimitUsd));
  const spent = $localize`:@@core.budget.spentOf:${formatCost(b.spentUsd ?? 0)}:spent: of ${formatCost(b.monthlyLimitUsd ?? 0)}:limit:`;
  return {
    id: `budget:${platform ? 'platform' : b.projectId}:${b.period ?? ''}`,
    kind: 'budget',
    urgency: exceeded ? 1 : 3,
    title: exceeded
      ? $localize`:@@core.turn.budgetExceeded:${name}:budget: exceeded: ${spent}:spent:`
      : $localize`:@@core.turn.budgetAt:${name}:budget: at ${pct}:share:: ${spent}:spent:`,
    where: [
      platform ? $localize`:@@core.turn.platform:Platform` : b.projectKey,
      monthName(b.period, true),
    ].filter((x): x is string => !!x),
    why: exceeded
      ? b.hardStop
        ? $localize`:@@core.turn.agentsStopped:Agents are stopped until the limit is raised or the month ends`
        : $localize`:@@core.turn.spendingContinues:Spending continues past the limit (no hard stop)`
      : $localize`:@@core.turn.alertAt:Alert at ${b.alertAtPercent ?? 80}:percent:%; forecast ${formatCost(b.forecastUsd ?? 0)}:forecast: by month end`,
    since: null,
    baton: budgetBaton(b, true),
    primary: {
      label: exceeded
        ? $localize`:@@core.turn.raiseLimit:Raise the limit`
        : $localize`:@@core.turn.reviewBudget:Review budget`,
      keys: 'Enter',
    },
    link: platform ? ['/admin/settings'] : ['/projects', b.projectId],
    queryParams: platform ? { section: 'budget' } : { tab: 'budget' },
    budget: b,
  };
}

const age = (iso: string | null): number => (iso ? Date.parse(iso) : Number.POSITIVE_INFINITY);

export function buildTurnQueue(input: TurnQueueInput): TurnItem[] {
  const now = input.now ?? Date.now();
  const items: TurnItem[] = [];

  for (const a of input.approvals) {
    if (isDecidableGate(a)) items.push(gateTurn(a, input.userId));
  }

  items.push(...failedRunTurns(input, now));

  for (const x of input.ciExecutions) {
    // A failed CI review that started a run already shows up as the failed run.
    const shownAsRun = !!x.runId && items.some((i) => i.run?.id === x.runId);
    if (isRecentCiFailure(x, input.projectIds, now) && !shownAsRun) items.push(failedCiTurn(x));
  }

  for (const b of input.budgets ?? []) {
    if (isBudgetTurn(b, input.canManageBudget)) items.push(budgetTurn(b));
  }

  for (const c of input.expiringConnections ?? []) {
    if (c.secretExpiresAt && !c.legacy) items.push(connectionTurn(c, now));
  }

  return items.sort((a, b) => a.urgency - b.urgency || age(a.since) - age(b.since));
}

/** Recent failed runs the user started or may retry. */
function failedRunTurns(input: TurnQueueInput, now: number): TurnItem[] {
  const turns: TurnItem[] = [];
  for (const r of input.failedRuns) {
    if (r.status !== 'FAILED' || olderThan(r, FAILED_RUN_WINDOW_MS, now)) continue;
    const mine = !!input.userId && r.startedBy?.userId === input.userId;
    if (mine || input.canRetry(r.projectId)) turns.push(failedRunTurn(r, mine));
  }
  return turns;
}

/** Open (or status unknown) and the user may decide it. */
function isDecidableGate(a: ApprovalRequest): boolean {
  return (!a.status || a.status === 'OPEN') && a.canDecide !== false;
}

/** Finished (or created) more than `windowMs` ago; unknown times count as recent. */
function olderThan(
  x: { finishedAt?: string | null; createdAt?: string | null },
  windowMs: number,
  now: number,
): boolean {
  const at = Date.parse(x.finishedAt ?? x.createdAt ?? '');
  return !Number.isNaN(at) && now - at > windowMs;
}

/** A failed CI review of one of the user's projects (`null` = all) within the window. */
function isRecentCiFailure(
  x: CiExecutionSummary,
  projectIds: ReadonlySet<string> | null,
  now: number,
): boolean {
  const failed = x.status === 'FAILED' || x.gateResult === 'FAIL';
  if (!failed) return false;
  if (projectIds && !projectIds.has(x.projectId ?? '')) return false;
  return !olderThan(x, FAILED_CI_WINDOW_MS, now);
}

/** A budget near or over its limit that the user may change. */
function isBudgetTurn(b: BudgetStatus, canManage: TurnQueueInput['canManageBudget']): boolean {
  if (!needsAttention(b)) return false;
  if (b.scope === 'PROJECT' && !b.projectId) return false;
  return !!canManage?.(b);
}
