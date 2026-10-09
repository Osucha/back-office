import { AGENT_JOB_STATUSES } from '../../api/types';
import { batonOf, batonText, holderOfKind } from './baton';

describe('baton mapping', () => {
  it('maps run states to holders', () => {
    expect(batonOf.run({ status: 'RUNNING', currentNodeId: 'implement' })).toEqual({
      holder: 'agent',
      label: 'agent working',
      detail: 'implement',
      live: true,
    });
    expect(
      batonOf.run({
        status: 'WAITING',
        waitingOn: { kind: 'WAIT_FOR_CI', nodeId: 'ci', signal: 'tests' },
      }).holder,
    ).toBe('ci');
    expect(
      batonOf.run({
        status: 'RUNNING',
        waitingOn: { kind: 'WAIT_FOR_CI', nodeId: 'ci', signal: 'tests' },
      }),
    ).toMatchObject({ holder: 'ci', detail: 'tests', live: true });
    expect(
      batonOf.run({ status: 'WAITING', waitingOn: { kind: 'HUMAN_GATE', nodeId: 'spec-gate' } }),
    ).toMatchObject({ holder: 'you', detail: 'spec-gate' });
    expect(batonOf.run({ status: 'SUCCEEDED' }).holder).toBe('done');
    expect(batonOf.run({ status: 'SUCCEEDED', outcome: 'FAILURE' }).holder).toBe('blocked');
    expect(batonOf.run({ status: 'FAILED' }).holder).toBe('blocked');
    expect(batonOf.run({ status: 'CANCELLED' }).holder).toBe('idle');
    expect(batonOf.run({ status: 'PENDING' }).holder).toBe('idle');
  });

  it('a gate the user cannot decide is not their turn', () => {
    const r = batonOf.run(
      { status: 'WAITING', waitingOn: { kind: 'HUMAN_GATE', nodeId: 'g' } },
      { canDecide: false },
    );
    expect(r).toMatchObject({ holder: 'idle', label: 'with approver' });
  });

  it('maps CI executions through the gate result', () => {
    expect(batonOf.ci({ status: 'QUEUED' }).holder).toBe('idle');
    expect(batonOf.ci({ status: 'RUNNING' })).toMatchObject({ holder: 'agent', live: true });
    expect(batonOf.ci({ status: 'SUCCEEDED', gateResult: 'PASS' })).toMatchObject({
      holder: 'done',
      label: 'gate passed',
    });
    expect(batonOf.ci({ status: 'SUCCEEDED', gateResult: 'FAIL', mode: 'ADVISORY' })).toEqual({
      holder: 'blocked',
      label: 'gate failed',
      detail: 'advisory',
    });
    expect(batonOf.ci({ status: 'FAILED' }).holder).toBe('blocked');
  });

  it('maps benchmark attempts', () => {
    expect(batonOf.attempt('JUDGING')).toMatchObject({ holder: 'agent', live: true });
    expect(batonOf.attempt('TIMED_OUT').holder).toBe('blocked');
    expect(batonOf.attempt('SUCCEEDED').holder).toBe('done');
    expect(batonOf.attempt(undefined).holder).toBe('idle');
  });

  it('maps node executions by kind while active', () => {
    expect(batonOf.node({ status: 'RUNNING', kind: 'TASK' }).holder).toBe('agent');
    expect(batonOf.node({ status: 'WAITING', kind: 'HUMAN_GATE' })).toMatchObject({
      holder: 'you',
      live: false,
    });
    expect(batonOf.node({ status: 'WAITING', kind: 'WAIT_FOR_CI' }).holder).toBe('ci');
    expect(batonOf.node({ status: 'SUCCEEDED', kind: 'TASK', port: 'success' })).toEqual({
      holder: 'done',
      label: 'done',
      detail: 'success',
    });
    expect(batonOf.node({ status: 'SKIPPED', kind: 'TASK' }).holder).toBe('idle');
    expect(holderOfKind('DECISION')).toBe('agent');
    expect(holderOfKind('NOTE')).toBe('idle');
  });

  it('shows a waiting gate the same way for the run and its node', () => {
    const run = { status: 'WAITING', waitingOn: { kind: 'HUMAN_GATE', nodeId: 'g' } } as const;
    const gate = { status: 'WAITING', kind: 'HUMAN_GATE' } as const;
    for (const canDecide of [true, false, undefined]) {
      const r = batonOf.run(run, { canDecide });
      const n = batonOf.node(gate, { canDecide });
      expect([n.holder, n.label]).toEqual([r.holder, r.label]);
    }
    expect(batonOf.node(gate, { canDecide: false })).toMatchObject({
      holder: 'idle',
      label: 'with approver',
    });
  });

  it('maps approvals, including decisions and other people’s gates', () => {
    expect(
      batonOf.approval({
        status: 'OPEN',
        canDecide: true,
        approvers: { accountable: 'owner', forbidSelfApproval: false },
      }),
    ).toEqual({ holder: 'you', label: 'your turn', detail: 'Owner' });
    expect(batonOf.approval({ status: 'OPEN', canDecide: false }).holder).toBe('idle');
    expect(batonOf.approval({ status: 'DECIDED', decision: { option: 'approve' } }).holder).toBe(
      'done',
    );
    expect(batonOf.approval({ status: 'DECIDED', decision: { option: 'revise' } }).holder).toBe(
      'agent',
    );
    expect(batonOf.approval({ status: 'DECIDED', decision: { option: 'reject' } }).holder).toBe(
      'blocked',
    );
    expect(batonOf.approval({ status: 'EXPIRED' }).holder).toBe('idle');
  });

  it('maps every agent job status (runner fleet)', () => {
    const holders = Object.fromEntries(
      AGENT_JOB_STATUSES.map((s) => [s, batonOf.agentJob(s).holder]),
    );
    expect(holders).toEqual({
      QUEUED: 'idle',
      LEASED: 'idle',
      STARTING: 'agent',
      RUNNING: 'agent',
      SUCCEEDED: 'done',
      FAILED: 'blocked',
      TIMED_OUT: 'blocked',
      LOST: 'blocked',
      CANCELLED: 'idle',
    });
    expect(batonOf.agentJob('QUEUED').label).toBe('queued');
    expect(batonOf.agentJob('LEASED').label).toBe('queued');
    expect(batonOf.agentJob('RUNNING').live).toBe(true);
    expect(batonOf.agentJob('QUEUED').live).toBeFalsy();
    expect(batonOf.agentJob('CANCELLED').label).toBe('cancelled');
    for (const s of AGENT_JOB_STATUSES) expect(batonOf.agentJob(s).label).toBeTruthy();
  });

  it('renders the chip text', () => {
    expect(batonText({ holder: 'you', label: 'your turn', detail: 'spec gate' })).toBe(
      'your turn · spec gate',
    );
    expect(batonText({ holder: 'done', label: 'done' })).toBe('done');
  });
});
