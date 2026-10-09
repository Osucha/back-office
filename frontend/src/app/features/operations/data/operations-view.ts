/** Pure helpers for the operations page. */
import type { OperationsOverview, Replica, WorkItemStatus } from '../../../core/api/types';
import type { Baton } from '../../../core/ui/relay/baton';

export const REFRESH_MS = 10_000;

/** A replica is stale when it has not reported for a minute (heartbeats every 15 s). */
export const REPLICA_STALE_MS = 60_000;

export function replicaBaton(r: Replica, now = Date.now()): Baton {
  const at = Date.parse(r.lastSeenAt ?? '');
  if (Number.isNaN(at))
    return { holder: 'idle', label: $localize`:@@operations.replica.unknown:unknown` };
  return now - at > REPLICA_STALE_MS
    ? { holder: 'blocked', label: $localize`:@@operations.replica.notReporting:not reporting` }
    : { holder: 'done', label: $localize`:@@operations.replica.up:up` };
}

/** Rows for the work-item status strip in a fixed order, including zeros. */
export function workItemCounts(
  o: OperationsOverview | null,
): { status: WorkItemStatus; count: number }[] {
  const order: WorkItemStatus[] = ['READY', 'LEASED', 'DONE', 'DEAD'];
  return order.map((status) => ({ status, count: o?.workItems?.[status] ?? 0 }));
}

/** Work-item status words (lower case, like the baton words). */
export function workItemStatusLabel(status: WorkItemStatus | string): string {
  switch (status) {
    case 'READY':
      return $localize`:@@operations.workItem.ready:ready`;
    case 'LEASED':
      return $localize`:@@operations.workItem.leased:leased`;
    case 'DONE':
      return $localize`:@@operations.workItem.done:done`;
    case 'DEAD':
      return $localize`:@@operations.workItem.dead:dead`;
    default:
      return status.toLowerCase();
  }
}

/** "12s", "4m 10s", "2h 5m" for the oldest ready item. */
export function formatAge(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '-';
  const s = Math.max(0, Math.round(seconds));
  if (s < 60) return $localize`:@@operations.age.seconds:${s}:seconds:s`;
  const m = Math.floor(s / 60);
  if (m < 60) return $localize`:@@operations.age.minutesSeconds:${m}:minutes:m ${s % 60}:seconds:s`;
  return $localize`:@@operations.age.hoursMinutes:${Math.floor(m / 60)}:hours:h ${m % 60}:minutes:m`;
}

/** The queue is lagging when the oldest ready item waited more than a minute. */
export function queueBaton(o: OperationsOverview | null): Baton {
  const age = o?.oldestReadyAgeSeconds ?? 0;
  const dead = o?.deadItems ?? o?.workItems?.['DEAD'] ?? 0;
  if (dead > 0)
    return {
      holder: 'you',
      label: $localize`:@@operations.queue.deadItems:dead items`,
      detail: $localize`:@@operations.queue.toRetry:${dead}:count: to retry`,
    };
  if (age > 60)
    return {
      holder: 'blocked',
      label: $localize`:@@operations.queue.lagging:lagging`,
      detail: $localize`:@@operations.queue.oldest:oldest ${formatAge(age)}:age:`,
    };
  return { holder: 'done', label: $localize`:@@operations.queue.flowing:flowing` };
}

/** Version skew between replicas (a rolling upgrade in progress, or a stuck one). */
export function versionSkew(replicas: readonly Replica[] | null | undefined): string[] {
  return [...new Set((replicas ?? []).map((r) => r.version).filter((v): v is string => !!v))];
}
