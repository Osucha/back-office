/**
 * What a desktop notification says (pure; ADR 0032). Title + one line, a `tag` per gate / run /
 * execution / budget so a repeated event replaces the notification instead of stacking, and the
 * in-app link a click opens. Every server text goes through `cleanText` (untrusted, ADR 0029); no
 * agent output, failure messages or secrets are ever included, only names and keys.
 */
import type { ApprovalRequest, WorkflowRunSummary } from '../api/types';
import type { NotifyKind } from './notification-prefs';
import { cleanText, joinClean } from './sanitize';
import type { SoundKind } from './sounds';

export interface NotificationContent {
  kind: NotifyKind;
  title: string;
  body: string;
  tag: string;
  /** In-app URL opened on click. */
  link: string;
}

/** The sound of each notification kind. */
export const KIND_SOUND: Record<NotifyKind, SoundKind> = {
  turn: 'turn',
  runFailed: 'failed',
  runLanded: 'done',
  budget: 'budget',
  ciFailed: 'failed',
  connectionExpiring: 'budget',
};

const TITLE_MAX = 64;
const BODY_MAX = 160;

/** Safe path segment: ids are UUIDs; anything else is dropped so a link can never leave the app. */
function seg(id: unknown): string {
  return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id) ? id : '';
}

function title(text: string): string {
  return cleanText(text, TITLE_MAX);
}

/**
 * A gate the user can decide appeared (or was assigned to them). A write confirmation (ADR 0033) gets
 * its own title, "Confirm a Jira write", and the one-line question ("Agent wants to comment on
 * SHOP-42"); the comment text itself (it may contain issue or model text) is never in the notification.
 */
export function gateContent(a: ApprovalRequest, assignedToMe: boolean): NotificationContent {
  const key = cleanText(a.projectKey, 24);
  const subject = a.issueKey || a.workflowName;
  const question =
    cleanText(a.question, 100) || $localize`:@@core.notify.decisionNeeded:Decision needed`;
  if (a.kind === 'WRITE_CONFIRMATION') {
    return {
      kind: 'turn',
      title: title(
        key
          ? $localize`:@@core.notify.writeTitle:Confirm a Jira write · ${key}:project:`
          : $localize`:@@core.notify.writeTitleNoProject:Confirm a Jira write`,
      ),
      body: joinClean(
        [question, assignedToMe ? $localize`:@@core.notify.assignedToYou:assigned to you` : null],
        BODY_MAX,
      ),
      tag: `bo-gate-${seg(a.id)}`,
      link: `/inbox/${seg(a.id)}`,
    };
  }
  return {
    kind: 'turn',
    title: title(
      assignedToMe
        ? key
          ? $localize`:@@core.notify.assignedTitle:Assigned to you · ${key}:project:`
          : $localize`:@@core.notify.assignedTitleNoProject:Assigned to you`
        : key
          ? $localize`:@@core.notify.turnTitle:Your turn · ${key}:project:`
          : $localize`:@@core.notify.turnTitleNoProject:Your turn`,
    ),
    body: joinClean([subject, question], BODY_MAX),
    tag: `bo-gate-${seg(a.id)}`,
    link: `/inbox/${seg(a.id)}`,
  };
}

/** A run the user started (or in a project they own) failed or landed. */
export function runContent(
  r: Pick<WorkflowRunSummary, 'id' | 'projectKey' | 'workflowName' | 'issueKey' | 'currentNodeId'>,
  failed: boolean,
): NotificationContent {
  const key = cleanText(r.projectKey, 24);
  const step = cleanText(r.currentNodeId, 40);
  const subject = [r.issueKey, r.workflowName || $localize`:@@core.notify.run:Run`];
  return {
    kind: failed ? 'runFailed' : 'runLanded',
    title: title(
      failed
        ? key
          ? $localize`:@@core.notify.runFailedTitle:Run failed · ${key}:project:`
          : $localize`:@@core.notify.runFailedTitleNoProject:Run failed`
        : key
          ? $localize`:@@core.notify.runLandedTitle:Run landed · ${key}:project:`
          : $localize`:@@core.notify.runLandedTitleNoProject:Run landed`,
    ),
    body: joinClean(
      failed && step
        ? [...subject, $localize`:@@core.notify.stoppedAt:stopped at ${step}:step:`]
        : subject,
      BODY_MAX,
    ),
    tag: `bo-run-${seg(r.id)}`,
    link: `/runs/${seg(r.id)}`,
  };
}

/** A CI execution of one of the user's projects failed. */
export function ciContent(
  executionId: string,
  projectKey: string | null | undefined,
  entrypoint: string | null | undefined,
  gateFailed: boolean,
): NotificationContent {
  const key = cleanText(projectKey, 24);
  return {
    kind: 'ciFailed',
    title: title(
      key
        ? $localize`:@@core.notify.ciFailedTitle:CI failed · ${key}:project:`
        : $localize`:@@core.notify.ciFailedTitleNoProject:CI failed`,
    ),
    body: joinClean(
      [
        entrypoint,
        gateFailed
          ? $localize`:@@core.notify.ciGateFailed:the review gate failed`
          : $localize`:@@core.notify.ciExecutionFailed:the execution failed`,
      ],
      BODY_MAX,
    ),
    tag: `bo-ci-${seg(executionId)}`,
    link: `/ci/${seg(executionId)}`,
  };
}

export interface BudgetEvent {
  exceeded: boolean;
  platform: boolean;
  projectId: string | null;
  projectKey: string | null;
  period: string | null;
  /** Already formatted amounts (formatCost). */
  spent: string;
  limit: string;
  hardStop: boolean;
}

/** A budget the user can raise crossed its alert threshold or its limit. */
export function budgetContent(b: BudgetEvent): NotificationContent {
  const name = b.platform
    ? $localize`:@@core.notify.platformBudget:Platform budget`
    : cleanText(b.projectKey, 24) || $localize`:@@core.notify.projectBudget:Project budget`;
  const spent = $localize`:@@core.notify.spentOf:${b.spent}:spent: of ${b.limit}:limit: spent`;
  return {
    kind: 'budget',
    title: title(
      b.exceeded
        ? $localize`:@@core.notify.budgetExceededTitle:Budget exceeded · ${name}:budget:`
        : $localize`:@@core.notify.budgetAlertTitle:Budget alert · ${name}:budget:`,
    ),
    body: joinClean(
      [
        spent,
        b.exceeded && b.hardStop
          ? $localize`:@@core.notify.hardStop:new agent calls are stopped`
          : null,
      ],
      BODY_MAX,
    ),
    tag: `bo-budget-${b.platform ? 'platform' : seg(b.projectId)}-${seg(b.period)}-${b.exceeded ? 'exceeded' : 'alert'}`,
    link: b.platform
      ? '/admin/settings?section=budget'
      : `/projects/${seg(b.projectId)}?tab=budget`,
  };
}

export interface ConnectionExpiringEvent {
  connectionId: string | null;
  /** null = a platform connection. */
  projectId: string | null;
  projectKey: string | null;
  name: string | null;
  /** PLATFORM, PROJECT or USER (personal, ADR 0035). */
  scope?: string | null;
  daysLeft: number;
}

/** A connection token the user manages expires within 14 days or has expired (daily reminder). */
export function connectionContent(c: ConnectionExpiringEvent): NotificationContent {
  const key = cleanText(c.projectKey, 24);
  const name = cleanText(c.name, 60) || $localize`:@@core.notify.connection:Jira connection`;
  const days = Math.trunc(c.daysLeft);
  const when =
    days < 0
      ? $localize`:@@core.notify.tokenExpired:the token has expired`
      : days === 0
        ? $localize`:@@core.notify.tokenExpiresToday:the token expires today`
        : $localize`:@@core.notify.tokenExpiresIn:the token expires in ${days}:days: d`;
  return {
    kind: 'connectionExpiring',
    title: title(
      days < 0
        ? key
          ? $localize`:@@core.notify.connectionExpiredTitle:Connection expired · ${key}:project:`
          : $localize`:@@core.notify.connectionExpiredTitleNoProject:Connection expired`
        : key
          ? $localize`:@@core.notify.connectionExpiringTitle:Connection expiring · ${key}:project:`
          : $localize`:@@core.notify.connectionExpiringTitleNoProject:Connection expiring`,
    ),
    body: joinClean([name, when], BODY_MAX),
    tag: `bo-connection-${seg(c.connectionId)}-${days < 0 ? 'expired' : 'expiring'}`,
    link: c.projectId
      ? `/projects/${seg(c.projectId)}?tab=connections`
      : c.scope === 'USER'
        ? '/account?section=connections'
        : '/admin/connections',
  };
}

/** The sample of the preferences screen ("Send a test notification"). */
export function testContent(kind: NotifyKind): NotificationContent {
  const samples: Record<NotifyKind, () => NotificationContent> = {
    turn: () =>
      gateContent(
        {
          id: 'preview',
          projectKey: 'DEMO',
          issueKey: 'DEMO-42',
          question: $localize`:@@core.notify.sampleQuestion:Approve the plan?`,
        } as ApprovalRequest,
        false,
      ),
    runFailed: () =>
      runContent(
        {
          id: 'preview',
          projectKey: 'DEMO',
          workflowName: 'Feature',
          issueKey: 'DEMO-42',
          currentNodeId: 'implement',
        },
        true,
      ),
    runLanded: () =>
      runContent(
        {
          id: 'preview',
          projectKey: 'DEMO',
          workflowName: 'Feature',
          issueKey: 'DEMO-42',
          currentNodeId: null,
        },
        false,
      ),
    budget: () =>
      budgetContent({
        exceeded: false,
        platform: false,
        projectId: 'preview',
        projectKey: 'DEMO',
        period: 'preview',
        spent: '$80.00',
        limit: '$100.00',
        hardStop: false,
      }),
    ciFailed: () => ciContent('preview', 'DEMO', 'review', true),
    connectionExpiring: () =>
      connectionContent({
        connectionId: 'preview',
        projectId: null,
        projectKey: null,
        name: 'Jira reader', // i18n-ignore (sample connection name)
        daysLeft: 7,
      }),
  };
  const c = samples[kind]();
  return {
    ...c,
    title: $localize`:@@core.notify.testPrefix:Test: ${c.title}:title:`,
    tag: `bo-test-${kind}`,
    link: '/account?section=notifications',
  };
}
