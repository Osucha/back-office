/**
 * Pure editing rules of workflow triggers (MANUAL / JIRA_TRANSITION(status) / API / CI /
 * SCHEDULE(intervalMinutes), ADR 0033 and ADR 0052). Each trigger keeps only the fields of its type,
 * so the definition round-trips through the API unchanged. Tested in trigger-model.spec.ts.
 */
import type { Trigger, TriggerType } from '../../../core/api/types';

/** Shortest interval of a SCHEDULE trigger in minutes (the contract's minimum, ADR 0052). */
export const MIN_SCHEDULE_MINUTES = 5;
/** Interval a newly added SCHEDULE trigger starts with. */
export const DEFAULT_SCHEDULE_MINUTES = 60;

/** The order in which "Add trigger" proposes types not used yet. */
const ADD_ORDER: readonly TriggerType[] = ['MANUAL', 'JIRA_TRANSITION', 'API', 'CI', 'SCHEDULE'];

/** A trigger of `type` with only the fields of that type (values of `prev` kept where they fit). */
export function newTrigger(type: TriggerType, prev?: Partial<Trigger>): Trigger {
  if (type === 'JIRA_TRANSITION') return { type, status: prev?.status ?? '' };
  if (type === 'SCHEDULE')
    return { type, intervalMinutes: prev?.intervalMinutes ?? DEFAULT_SCHEDULE_MINUTES };
  return { type };
}

/** Appends the first trigger type not present yet (API when all are). */
export function addTrigger(list: readonly Trigger[]): Trigger[] {
  const has = new Set(list.map((t) => t.type));
  return [...list, newTrigger(ADD_ORDER.find((t) => !has.has(t)) ?? 'API')];
}

/** Switches the trigger at `index` to `type` (a Jira status or an interval entered before is kept). */
export function changeTriggerType(
  list: readonly Trigger[],
  index: number,
  type: TriggerType,
): Trigger[] {
  return list.map((t, i) => (i === index ? newTrigger(type, t) : t));
}

/** Applies `patch` to the trigger at `index` and drops fields that do not belong to its type. */
export function patchTrigger(
  list: readonly Trigger[],
  index: number,
  patch: Partial<Trigger>,
): Trigger[] {
  return list.map((t, i) => only(i === index ? { ...t, ...patch } : t));
}

/** The trigger without fields of other types; a cleared interval stays empty (invalid, shown). */
function only(t: Trigger): Trigger {
  if (t.type === 'JIRA_TRANSITION') return { type: t.type, status: t.status ?? '' };
  if (t.type === 'SCHEDULE') return { type: t.type, intervalMinutes: t.intervalMinutes ?? null };
  return { type: t.type };
}

/** A SCHEDULE interval is a whole number of minutes, at least {@link MIN_SCHEDULE_MINUTES}. */
export function validScheduleInterval(minutes: number | null | undefined): boolean {
  return (
    typeof minutes === 'number' && Number.isInteger(minutes) && minutes >= MIN_SCHEDULE_MINUTES
  );
}
