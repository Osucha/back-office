import type {
  ApprovalRequest,
  BudgetStatus,
  CiExecutionSummary,
  Connection,
  WorkflowRunSummary,
} from '../api/types';
import { buildTurnQueue, daysUntil } from './turn-queue';

const now = Date.parse('2026-09-27T12:00:00Z');
const hoursAgo = (h: number) => new Date(now - h * 3600_000).toISOString();

const gate = (
  id: string,
  createdAt: string,
  extra: Partial<ApprovalRequest> = {},
): ApprovalRequest => ({
  id,
  status: 'OPEN',
  canDecide: true,
  question: `Approve ${id}?`,
  projectKey: 'SHOP',
  createdAt,
  ...extra,
});
const failedRun = (
  id: string,
  finishedAt: string,
  extra: Partial<WorkflowRunSummary> = {},
): WorkflowRunSummary => ({
  id,
  status: 'FAILED',
  projectId: 'p1',
  workflowName: 'Issue to MR',
  finishedAt,
  ...extra,
});
const ci = (
  id: string,
  finishedAt: string,
  extra: Partial<CiExecutionSummary> = {},
): CiExecutionSummary => ({
  id,
  status: 'SUCCEEDED',
  gateResult: 'FAIL',
  projectId: 'p1',
  entrypoint: 'code-review',
  finishedAt,
  ...extra,
});

describe('turn queue', () => {
  const base = {
    userId: 'me',
    canRetry: (p: string | null | undefined) => p === 'p1',
    projectIds: new Set(['p1']),
    now,
  };

  it('orders gates first, then failed runs, then CI failures, oldest first within a group', () => {
    const q = buildTurnQueue({
      ...base,
      approvals: [gate('g-new', hoursAgo(1)), gate('g-old', hoursAgo(20))],
      failedRuns: [failedRun('r-new', hoursAgo(2)), failedRun('r-old', hoursAgo(30))],
      ciExecutions: [ci('c1', hoursAgo(3))],
    });
    expect(q.map((i) => i.id)).toEqual([
      'gate:g-old',
      'gate:g-new',
      'run:r-old',
      'run:r-new',
      'ci:c1',
    ]);
    expect(q[0].primary.keys).toBe('a');
    expect(q[0].baton.holder).toBe('you');
  });

  it('leaves out gates the user cannot decide and decided ones', () => {
    const q = buildTurnQueue({
      ...base,
      approvals: [
        gate('mine', hoursAgo(1)),
        gate('theirs', hoursAgo(1), { canDecide: false }),
        gate('done', hoursAgo(1), { status: 'DECIDED' }),
      ],
      failedRuns: [],
      ciExecutions: [],
    });
    expect(q.map((i) => i.id)).toEqual(['gate:mine']);
  });

  it('keeps failed runs the user started or may retry, within seven days', () => {
    const q = buildTurnQueue({
      ...base,
      approvals: [],
      failedRuns: [
        failedRun('started-by-me', hoursAgo(5), {
          projectId: 'other',
          startedBy: { kind: 'USER', userId: 'me' },
        }),
        failedRun('retryable', hoursAgo(5)),
        failedRun('not-mine', hoursAgo(5), { projectId: 'other' }),
        failedRun('too-old', hoursAgo(24 * 8)),
        failedRun('running', hoursAgo(1), { status: 'RUNNING' }),
      ],
      ciExecutions: [],
    });
    expect(q.map((i) => i.id)).toEqual(['run:started-by-me', 'run:retryable']);
    expect(q[0].why).toBe('You started this run');
  });

  it('keeps recent CI failures of the user’s projects and skips those shown as a failed run', () => {
    const q = buildTurnQueue({
      ...base,
      approvals: [],
      failedRuns: [failedRun('r1', hoursAgo(1))],
      ciExecutions: [
        ci('passed', hoursAgo(1), { gateResult: 'PASS' }),
        ci('other-project', hoursAgo(1), { projectId: 'p2' }),
        ci('old', hoursAgo(24 * 4)),
        ci('same-as-run', hoursAgo(1), { runId: 'r1' }),
        ci('crashed', hoursAgo(2), { status: 'FAILED', gateResult: undefined }),
      ],
    });
    expect(q.map((i) => i.id)).toEqual(['run:r1', 'ci:crashed']);
  });

  it('with no project restriction, CI failures of every visible project count', () => {
    const q = buildTurnQueue({
      ...base,
      projectIds: null,
      approvals: [],
      failedRuns: [],
      ciExecutions: [ci('p2', hoursAgo(1), { projectId: 'p2' })],
    });
    expect(q).toHaveLength(1);
  });

  describe('budget turns', () => {
    const budget = (extra: Partial<BudgetStatus>): BudgetStatus => ({
      scope: 'PROJECT',
      projectId: 'p1',
      projectKey: 'SHOP',
      period: '2026-09',
      monthlyLimitUsd: 50,
      alertAtPercent: 80,
      hardStop: true,
      spentUsd: 42,
      forecastUsd: 61,
      state: 'ALERT',
      ...extra,
    });
    const manages = (b: BudgetStatus) => (b.scope === 'PLATFORM' ? false : b.projectId === 'p1');

    it('adds alerts and exceeded budgets of projects the user manages', () => {
      const q = buildTurnQueue({
        ...base,
        approvals: [gate('g', hoursAgo(1))],
        failedRuns: [failedRun('r', hoursAgo(1))],
        ciExecutions: [ci('c', hoursAgo(1))],
        budgets: [
          budget({ state: 'ALERT' }),
          budget({ projectId: 'p2', projectKey: 'OPS', state: 'EXCEEDED' }),
          budget({ projectId: 'p1', projectKey: 'SHOP', period: '2026-08', state: 'EXCEEDED' }),
          budget({ state: 'OK', period: '2026-07' }),
          budget({ state: 'NO_LIMIT', period: '2026-06' }),
        ],
        canManageBudget: manages,
      });
      expect(q.map((i) => i.id)).toEqual([
        'gate:g',
        'run:r',
        'budget:p1:2026-08',
        'ci:c',
        'budget:p1:2026-09',
      ]);
      const exceeded = q[2];
      expect(exceeded.kind).toBe('budget');
      expect(exceeded.baton).toEqual({
        holder: 'you',
        label: 'over budget',
        detail: 'agents stopped',
      });
      expect(exceeded.primary.label).toBe('Raise the limit');
      expect(exceeded.link).toEqual(['/projects', 'p1']);
      expect(exceeded.queryParams).toEqual({ tab: 'budget' });
      expect(exceeded.why).toContain('Agents are stopped');
      const alert = q[4];
      expect(alert.title).toBe('SHOP budget at 84%: $42.00 of $50.00');
      expect(alert.baton.holder).toBe('you');
      expect(alert.where).toEqual(['SHOP', 'September 2026']);
      expect(alert.primary.label).toBe('Review budget');
    });

    it('the platform budget is a turn only for settings admins', () => {
      const platform = budget({ scope: 'PLATFORM', projectId: null, state: 'EXCEEDED' });
      const none = buildTurnQueue({
        ...base,
        approvals: [],
        failedRuns: [],
        ciExecutions: [],
        budgets: [platform],
        canManageBudget: manages,
      });
      expect(none).toEqual([]);
      const admin = buildTurnQueue({
        ...base,
        approvals: [],
        failedRuns: [],
        ciExecutions: [],
        budgets: [platform],
        canManageBudget: () => true,
      });
      expect(admin[0].title).toBe('Platform budget exceeded: $42.00 of $50.00');
      expect(admin[0].link).toEqual(['/admin/settings']);
      expect(admin[0].queryParams).toEqual({ section: 'budget' });
    });

    it('a soft limit says spending continues', () => {
      const q = buildTurnQueue({
        ...base,
        approvals: [],
        failedRuns: [],
        ciExecutions: [],
        budgets: [budget({ state: 'EXCEEDED', hardStop: false, spentUsd: 60 })],
        canManageBudget: manages,
      });
      expect(q[0].why).toBe('Spending continues past the limit (no hard stop)');
      expect(q[0].baton.detail).toBe('120% used');
    });

    it('without budgets or the permission callback nothing is added', () => {
      const q = buildTurnQueue({
        ...base,
        approvals: [],
        failedRuns: [],
        ciExecutions: [],
        budgets: [budget({ state: 'EXCEEDED' })],
      });
      expect(q).toEqual([]);
    });
  });

  it('says when a gate is assigned to the user by name, and by whom (ADR 0028)', () => {
    const q = buildTurnQueue({
      ...base,
      approvals: [
        gate('mine', hoursAgo(1), {
          assignees: [{ userId: 'me', displayName: 'Me' }],
          assignment: {
            userIds: ['me'],
            assignedAt: hoursAgo(1),
            assignedBy: { kind: 'USER', userId: 'boss', displayName: 'Bea Boss' },
          },
        }),
        gate('named', hoursAgo(2), { assignees: [{ userId: 'me', displayName: 'Me' }] }),
        gate('role', hoursAgo(3)),
        gate('other', hoursAgo(4), {
          canDecide: false,
          assignees: [{ userId: 'x', displayName: 'X' }],
        }),
      ],
      failedRuns: [],
      ciExecutions: [],
    });
    expect(q.map((t) => t.id)).toEqual(['gate:role', 'gate:named', 'gate:mine']);
    const why = Object.fromEntries(q.map((t) => [t.id, t.why]));
    expect(why['gate:mine']).toContain('Assigned to you by Bea Boss');
    expect(why['gate:named']).toContain('Assigned to you');
    expect(why['gate:role']).not.toContain('Assigned to you');
  });

  it('shows write confirmations as gates that say nothing is written yet', () => {
    const items = buildTurnQueue({
      approvals: [
        gate('w1', hoursAgo(1), {
          kind: 'WRITE_CONFIRMATION',
          question: 'Agent wants to move SHOP-1 to Done',
        }),
      ],
      failedRuns: [],
      ciExecutions: [],
      userId: 'u1',
      canRetry: () => false,
      projectIds: null,
      now,
    });
    expect(items[0].kind).toBe('gate');
    expect(items[0].title).toBe('Agent wants to move SHOP-1 to Done');
    expect(items[0].primary.label).toBe('Review the write');
    expect(items[0].why).toContain('Nothing is written to Jira until you approve');
  });

  it('reminds of expiring and expired connection tokens', () => {
    const conn = (
      id: string,
      secretExpiresAt: string,
      extra: Partial<Connection> = {},
    ): Connection =>
      ({
        id,
        name: id,
        scope: 'PLATFORM',
        accountEmail: 'bot@acme.com',
        secretExpiresAt,
        legacy: false,
        ...extra,
      }) as Connection;
    expect(daysUntil('2026-09-30', now)).toBe(3);
    const items = buildTurnQueue({
      approvals: [],
      failedRuns: [],
      ciExecutions: [],
      userId: 'u1',
      canRetry: () => false,
      projectIds: null,
      expiringConnections: [
        conn('soon', '2026-09-30'),
        conn('gone', '2026-09-20', { scope: 'PROJECT', projectId: 'p1' }),
        conn('today', '2026-09-27'),
      ],
      now,
    });
    expect(items.map((i) => [i.id, i.urgency])).toEqual([
      ['connection:gone', 1],
      ['connection:soon', 3],
      ['connection:today', 3],
    ]);
    expect(items[0].title).toBe('The Jira token of gone has expired');
    expect(items[0].link).toEqual(['/projects', 'p1']);
    expect(items[0].queryParams).toEqual({ tab: 'connections' });
    expect(items[1].title).toBe('The Jira token of soon expires in 3 days');
    expect(items[2].title).toBe('The Jira token of today expires today');
    expect(items[1].link).toEqual(['/admin/connections']);

    // ADR 0035: a personal connection is replaced under My account
    const mine = buildTurnQueue({
      approvals: [],
      failedRuns: [],
      ciExecutions: [],
      userId: 'u1',
      canRetry: () => false,
      projectIds: null,
      expiringConnections: [conn('mine', '2026-09-30', { scope: 'USER', ownerUserId: 'u1' })],
      now,
    });
    expect(mine[0].link).toEqual(['/account']);
    expect(mine[0].queryParams).toEqual({ section: 'connections' });
  });
});
