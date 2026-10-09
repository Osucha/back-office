import type {
  ApprovalRequest,
  BudgetStatus,
  CiExecutionSummary,
  Connection,
  WorkflowRunSummary,
} from '../../api/types';
import type { Baton } from './baton';

export type TurnKind = 'gate' | 'failed-run' | 'failed-ci' | 'budget' | 'connection';

/**
 * One item of the *Your turn* queue, rendered by `<bo-turn-card>`: what, where, why you, since
 * when, and the primary action. Built by `buildTurnQueue()` (core/state/turn-queue.ts).
 */
export interface TurnItem {
  id: string;
  kind: TurnKind;
  /** 0 = gates, 1 = failed runs and exceeded budgets, 2 = failed CI, 3 = budget alerts. */
  urgency: number;
  title: string;
  /** project key · workflow · node */
  where: string[];
  /** "Accountable: approver · 2 of 3 checks required" */
  why: string;
  since: string | null;
  baton: Baton;
  primary: { label: string; keys?: string };
  /** Secondary navigation (run page, CI page). */
  link: unknown[];
  /** Query parameters for `link` (e.g. `{ tab: 'budget' }`). */
  queryParams?: Record<string, string>;
  approval?: ApprovalRequest;
  run?: WorkflowRunSummary;
  ci?: CiExecutionSummary;
  budget?: BudgetStatus;
  /** A connection whose token expires within 14 days or has expired (ADR 0030 reminder). */
  connection?: Connection;
}
