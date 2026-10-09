/**
 * The baton: who holds a piece of work right now (docs/ui/design-language.md, section 2). Every
 * status in the UI maps to one of six holders; the holder decides colour, glyph and motion, the
 * label says it in words. New states must be mapped here (see README.md, "Mapping a new state").
 */
import type {
  AgentJobStatus,
  ApprovalRequest,
  AttemptStatus,
  CiExecutionSummary,
  NodeExecution,
  NodeKind,
  WorkflowRunSummary,
} from '../../api/types';
import { roleName } from '../../i18n/catalog-labels';

export type BatonHolder = 'agent' | 'you' | 'ci' | 'done' | 'blocked' | 'idle';

export const BATON_HOLDERS: readonly BatonHolder[] = [
  'agent',
  'you',
  'ci',
  'done',
  'blocked',
  'idle',
];

export interface Baton {
  holder: BatonHolder;
  /** The word on the chip, sentence case ("agent", "your turn", "waiting for CI"). */
  label: string;
  /** Optional qualifier after a middle dot ("spec gate", "tests"). */
  detail?: string | null;
  /** Work in progress: the agent dot breathes, the CI loop turns (never with reduced motion). */
  live?: boolean;
}

/** What the holder means, for tooltips, legends and screen readers. */
export const HOLDER_MEANING: Record<BatonHolder, string> = {
  agent: $localize`:@@core.baton.meaning.agent:An agent is working`,
  you: $localize`:@@core.baton.meaning.you:A person must act`,
  ci: $localize`:@@core.baton.meaning.ci:Waiting for the project CI`,
  done: $localize`:@@core.baton.meaning.done:Finished successfully`,
  blocked: $localize`:@@core.baton.meaning.blocked:Failed or rejected`,
  idle: $localize`:@@core.baton.meaning.idle:Not started, pending or cancelled`,
};

/** Short holder names (legends, filter chips). */
export const HOLDER_NAME: Record<BatonHolder, string> = {
  agent: $localize`:@@core.baton.name.agent:Agent`,
  you: $localize`:@@core.baton.name.you:You`,
  ci: $localize`:@@core.baton.name.ci:CI`,
  done: $localize`:@@core.baton.name.done:Done`,
  blocked: $localize`:@@core.baton.name.blocked:Blocked`,
  idle: $localize`:@@core.baton.name.idle:Idle`,
};

type RunLike = Pick<WorkflowRunSummary, 'status' | 'waitingOn' | 'currentNodeId' | 'outcome'>;

/**
 * Run: `RUNNING` -> agent (ci while it waits for CI), `WAITING` on a human gate -> you, on CI ->
 * ci, `SUCCEEDED` -> done, `FAILED` -> blocked, `PENDING` / `CANCELLED` -> idle.
 * `canDecide` tells whether the signed-in user is an approver of the open gate: when false the
 * gate is someone else's turn and is shown as idle ("with approver") instead of the loud amber.
 */
function run(r: RunLike, opts: { canDecide?: boolean; nodeName?: string | null } = {}): Baton {
  const node = opts.nodeName ?? r.currentNodeId ?? null;
  switch (r.status) {
    case 'RUNNING':
      if (r.waitingOn?.kind === 'WAIT_FOR_CI')
        return {
          holder: 'ci',
          label: $localize`:@@core.baton.waitingForCI:waiting for CI`,
          detail: r.waitingOn.signal,
          live: true,
        };
      return {
        holder: 'agent',
        label: $localize`:@@core.baton.agentWorking:agent working`,
        detail: node,
        live: true,
      };
    case 'WAITING':
      if (r.waitingOn?.kind === 'WAIT_FOR_CI')
        return {
          holder: 'ci',
          label: $localize`:@@core.baton.waitingForCI:waiting for CI`,
          detail: r.waitingOn.signal,
          live: true,
        };
      if (opts.canDecide === false)
        return {
          holder: 'idle',
          label: $localize`:@@core.baton.withApprover:with approver`,
          detail: r.waitingOn?.nodeId ?? node,
        };
      return {
        holder: 'you',
        label: $localize`:@@core.baton.yourTurn:your turn`,
        detail: r.waitingOn?.nodeId ?? node,
      };
    case 'SUCCEEDED':
      return r.outcome === 'FAILURE'
        ? { holder: 'blocked', label: $localize`:@@core.baton.finishedFailure:finished: failure` }
        : { holder: 'done', label: $localize`:@@core.baton.done:done` };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@core.baton.failed:failed`, detail: node };
    case 'CANCELLED':
      return { holder: 'idle', label: $localize`:@@core.baton.cancelled:cancelled` };
    case 'PENDING':
    default:
      return { holder: 'idle', label: $localize`:@@core.baton.queued:queued` };
  }
}

/**
 * CI execution: queued -> idle, running -> agent (the workflow is working on it), then the gate
 * result: pass -> done, fail -> blocked; a failed execution without a gate result -> blocked.
 */
function ci(x: Pick<CiExecutionSummary, 'status' | 'gateResult' | 'mode'>): Baton {
  switch (x.status) {
    case 'QUEUED':
      return { holder: 'idle', label: $localize`:@@core.baton.queued:queued` };
    case 'RUNNING':
      return { holder: 'agent', label: $localize`:@@core.baton.reviewing:reviewing`, live: true };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@core.baton.failed:failed` };
    case 'SUCCEEDED':
    default:
      if (x.gateResult === 'FAIL')
        return {
          holder: 'blocked',
          label: $localize`:@@core.baton.gateFailed:gate failed`,
          detail: x.mode === 'ADVISORY' ? $localize`:@@core.baton.advisory:advisory` : null,
        };
      if (x.gateResult === 'PENDING')
        return { holder: 'agent', label: $localize`:@@core.baton.reviewing:reviewing`, live: true };
      return { holder: 'done', label: $localize`:@@core.baton.gatePassed:gate passed` };
  }
}

/** Benchmark attempt: the same vocabulary; judging is agent work too. */
function attempt(status: AttemptStatus | null | undefined): Baton {
  switch (status) {
    case 'RUNNING':
      return { holder: 'agent', label: $localize`:@@core.baton.running:running`, live: true };
    case 'JUDGING':
      return { holder: 'agent', label: $localize`:@@core.baton.judging:judging`, live: true };
    case 'SUCCEEDED':
      return { holder: 'done', label: $localize`:@@core.baton.done:done` };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@core.baton.failed:failed` };
    case 'TIMED_OUT':
      return { holder: 'blocked', label: $localize`:@@core.baton.timedOut:timed out` };
    case 'PENDING':
    default:
      return { holder: 'idle', label: $localize`:@@core.baton.pending:pending` };
  }
}

/** Who holds the baton at a node of this kind while it is active. */
export function holderOfKind(kind: NodeKind | null | undefined): BatonHolder {
  if (kind === 'HUMAN_GATE') return 'you';
  if (kind === 'WAIT_FOR_CI') return 'ci';
  if (kind === 'START' || kind === 'END' || kind === 'NOTE') return 'idle';
  return 'agent';
}

/**
 * Node execution: running/waiting nodes are held by the node kind's holder (task -> agent, gate ->
 * you, CI wait -> ci); succeeded -> done, failed -> blocked, skipped -> idle. An open gate the
 * caller cannot decide (`canDecide === false`) is idle "with approver", as in `run()`.
 */
function node(
  n: Pick<NodeExecution, 'status' | 'kind' | 'port'>,
  opts: { canDecide?: boolean } = {},
): Baton {
  switch (n.status) {
    case 'RUNNING':
    case 'WAITING': {
      // a WAITING task waits for a person to confirm its external write (ADR 0033)
      const holder: BatonHolder =
        n.status === 'WAITING' && n.kind === 'TASK' ? 'you' : holderOfKind(n.kind);
      if (holder === 'you' && opts.canDecide === false)
        return { holder: 'idle', label: $localize`:@@core.baton.withApprover:with approver` };
      const label =
        holder === 'you'
          ? $localize`:@@core.baton.yourTurn:your turn`
          : holder === 'ci'
            ? $localize`:@@core.baton.waitingForCI:waiting for CI`
            : $localize`:@@core.baton.agentWorking:agent working`;
      return { holder, label, live: holder !== 'you' };
    }
    case 'SUCCEEDED':
      return { holder: 'done', label: $localize`:@@core.baton.done:done`, detail: n.port ?? null };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@core.baton.failed:failed` };
    case 'SKIPPED':
    default:
      return { holder: 'idle', label: $localize`:@@core.baton.skipped:skipped` };
  }
}

const APPROVE = new Set(['APPROVE', 'MERGE', 'ACCEPT', 'YES']);
const REVISE = new Set(['REVISE', 'RETRY', 'REDO']);

/**
 * Approval: open and yours -> you; open but you are not an approver -> idle ("with approver");
 * decided approve -> done, revise -> agent (the agent takes the baton back), reject -> blocked;
 * expired -> idle.
 */
function approval(
  a: Pick<ApprovalRequest, 'status' | 'canDecide' | 'decision' | 'approvers'>,
): Baton {
  const roleKey = a.approvers?.accountable ?? a.approvers?.projectRoles?.[0];
  const role = roleKey ? roleName(roleKey) : $localize`:@@core.baton.approver:approver`;
  if (a.status === 'OPEN' || !a.status) {
    return a.canDecide === false
      ? { holder: 'idle', label: $localize`:@@core.baton.withApprover:with approver`, detail: role }
      : { holder: 'you', label: $localize`:@@core.baton.yourTurn:your turn`, detail: role };
  }
  if (a.status === 'EXPIRED')
    return { holder: 'idle', label: $localize`:@@core.baton.expired:expired` };
  const option = (a.decision?.option ?? '').toUpperCase();
  if (APPROVE.has(option))
    return { holder: 'done', label: $localize`:@@core.baton.approved:approved` };
  if (REVISE.has(option))
    return {
      holder: 'agent',
      label: $localize`:@@core.baton.sentBackForRevision:sent back for revision`,
    };
  return {
    holder: 'blocked',
    label: option
      ? $localize`:@@core.baton.rejected:rejected`
      : $localize`:@@core.baton.decided:decided`,
  };
}

/**
 * Agent job on the runner fleet: waiting for a runner (QUEUED, LEASED) -> idle "queued", in the
 * sandbox (STARTING, RUNNING) -> agent, SUCCEEDED -> done, FAILED / TIMED_OUT / LOST -> blocked,
 * CANCELLED -> idle.
 */
function agentJob(status: AgentJobStatus | null | undefined): Baton {
  switch (status) {
    case 'QUEUED':
      return { holder: 'idle', label: $localize`:@@core.baton.queued:queued` };
    case 'LEASED':
      return {
        holder: 'idle',
        label: $localize`:@@core.baton.queued:queued`,
        detail: $localize`:@@core.baton.leased:leased`,
      };
    case 'STARTING':
      return { holder: 'agent', label: $localize`:@@core.baton.starting:starting`, live: true };
    case 'RUNNING':
      return {
        holder: 'agent',
        label: $localize`:@@core.baton.agentWorking:agent working`,
        live: true,
      };
    case 'SUCCEEDED':
      return { holder: 'done', label: $localize`:@@core.baton.done:done` };
    case 'FAILED':
      return { holder: 'blocked', label: $localize`:@@core.baton.failed:failed` };
    case 'TIMED_OUT':
      return { holder: 'blocked', label: $localize`:@@core.baton.timedOut:timed out` };
    case 'LOST':
      return {
        holder: 'blocked',
        label: $localize`:@@core.baton.lost:lost`,
        detail: $localize`:@@core.baton.noHeartbeat:no heartbeat`,
      };
    case 'CANCELLED':
    default:
      return {
        holder: 'idle',
        label: status
          ? $localize`:@@core.baton.cancelled:cancelled`
          : $localize`:@@core.baton.unknown:unknown`,
      };
  }
}

/** The mapping functions: `batonOf.run(r)`, `batonOf.ci(x)`, `batonOf.attempt(status)`, ... */
export const batonOf = { run, ci, attempt, node, approval, agentJob } as const;

/** "your turn · spec gate" - the chip text, also used as accessible name. */
export function batonText(b: Baton): string {
  return b.detail ? `${b.label} · ${b.detail}` : b.label;
}
