/**
 * ADR 0065: which cost figures a leaderboard row is compared on (pure; tested in board-cost.spec.ts).
 * A candidate that ran on a subscription (Claude Code with your own login) spent nothing through Back Office;
 * its cost per accepted result, score per dollar and point in the chart use the same work at API list prices.
 */
import type { LeaderboardRow, Money } from '../../../../core/api/types';

type CostRow = Pick<
  LeaderboardRow,
  | 'subscription'
  | 'meanCost'
  | 'meanApiCost'
  | 'totalCost'
  | 'totalApiCost'
  | 'costPerAccepted'
  | 'apiCostPerAccepted'
  | 'scorePerDollar'
  | 'scorePerApiDollar'
>;

/** True when the row is compared on the API figures: a subscription, or nothing spent but an API amount. */
export function comparesOnApi(r: CostRow | null | undefined): boolean {
  if (!r) return false;
  if (r.subscription) return true;
  const spent = r.totalCost?.amount ?? 0;
  const api = r.totalApiCost?.amount ?? r.meanApiCost?.amount ?? 0;
  return spent === 0 && api > 0;
}

/** Mean cost per attempt the row is compared on (USD), for the chart. */
export function boardMeanCost(r: CostRow): number {
  return (comparesOnApi(r) ? r.meanApiCost?.amount : r.meanCost?.amount) ?? 0;
}

/** Cost per accepted result the row is compared on; null without a passed attempt. */
export function boardCostPerAccepted(r: CostRow): Money | null {
  return (comparesOnApi(r) ? r.apiCostPerAccepted : r.costPerAccepted) ?? null;
}

/** Score per dollar the row is compared on; null when it cannot be computed. */
export function boardScorePerDollar(r: CostRow): number | null {
  return (comparesOnApi(r) ? r.scorePerApiDollar : r.scorePerDollar) ?? null;
}
