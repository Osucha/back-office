/**
 * Desktop notification preferences (ADR 0032): pure model, defaults, parsing of the stored JSON and
 * the delivery rules. The contract's `UserPreferences` has no notification fields, so the
 * preferences are stored per user in this browser (`localStorage`, key per user id).
 *
 * This file is in the initial bundle (the avatar menu toggle): keep it free of Angular and small.
 */

/** The event types a user can be notified about, in the order of the preferences screen. */
export const NOTIFY_KINDS = [
  'turn',
  'runFailed',
  'runLanded',
  'budget',
  'ciFailed',
  'connectionExpiring',
  'newTasks',
] as const;
export type NotifyKind = (typeof NOTIFY_KINDS)[number];

export interface QuietHours {
  enabled: boolean;
  /** `HH:MM`, local time. */
  from: string;
  /** `HH:MM`, local time; may be earlier than `from` (over midnight). */
  to: string;
}

export interface NotificationPrefs {
  /** Master switch. */
  enabled: boolean;
  kinds: Record<NotifyKind, boolean>;
  /** Notify only while the Back Office tab is hidden or its window is not focused. */
  onlyWhenHidden: boolean;
  sound: boolean;
  /** 0..1 */
  volume: number;
  quietHours: QuietHours;
}

export const DEFAULT_NOTIFICATION_PREFS: NotificationPrefs = {
  enabled: false,
  kinds: {
    turn: true,
    runFailed: true,
    runLanded: false,
    budget: true,
    ciFailed: false,
    connectionExpiring: false,
    newTasks: true,
  },
  onlyWhenHidden: true,
  sound: true,
  volume: 0.6,
  quietHours: { enabled: false, from: '22:00', to: '07:00' },
};

/** localStorage key of a user's preferences (one browser can be shared by several accounts). */
export function prefsStorageKey(userId: string | null | undefined): string {
  return `bo.notifications.${userId || 'anonymous'}`; // i18n-ignore
}

const TIME = /^([01]\d|2[0-3]):([0-5]\d)$/;

function bool(value: unknown, fallback: boolean): boolean {
  return typeof value === 'boolean' ? value : fallback;
}

function time(value: unknown, fallback: string): string {
  return typeof value === 'string' && TIME.test(value) ? value : fallback;
}

/**
 * The defaults for one user: "Connection expiring" is on for those who manage platform connections
 * (administrators), off for everybody else (they can still turn it on).
 */
export function defaultPrefsFor(managesConnections: boolean): NotificationPrefs {
  const d = DEFAULT_NOTIFICATION_PREFS;
  return { ...d, kinds: { ...d.kinds, connectionExpiring: managesConnections } };
}

/** Stored JSON (any shape, possibly tampered with or from an older version) -> valid preferences. */
export function parsePrefs(
  raw: string | null | undefined,
  d: NotificationPrefs = DEFAULT_NOTIFICATION_PREFS,
): NotificationPrefs {
  let v: Record<string, unknown> = {};
  try {
    const parsed: unknown = raw ? JSON.parse(raw) : null;
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed))
      v = parsed as Record<string, unknown>;
  } catch {
    // broken JSON: defaults
  }
  const kinds = (v['kinds'] && typeof v['kinds'] === 'object' ? v['kinds'] : {}) as Record<
    string,
    unknown
  >;
  const quiet = (
    v['quietHours'] && typeof v['quietHours'] === 'object' ? v['quietHours'] : {}
  ) as Record<string, unknown>;
  const volume =
    typeof v['volume'] === 'number' && Number.isFinite(v['volume']) ? v['volume'] : d.volume;
  return {
    enabled: bool(v['enabled'], d.enabled),
    kinds: Object.fromEntries(NOTIFY_KINDS.map((k) => [k, bool(kinds[k], d.kinds[k])])) as Record<
      NotifyKind,
      boolean
    >,
    onlyWhenHidden: bool(v['onlyWhenHidden'], d.onlyWhenHidden),
    sound: bool(v['sound'], d.sound),
    volume: Math.min(1, Math.max(0, volume)),
    quietHours: {
      enabled: bool(quiet['enabled'], d.quietHours.enabled),
      from: time(quiet['from'], d.quietHours.from),
      to: time(quiet['to'], d.quietHours.to),
    },
  };
}

function minutes(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}

/** True when `now` (local time) falls into the quiet hours; `from == to` means never. */
export function inQuietHours(q: QuietHours, now: Date): boolean {
  if (!q.enabled) return false;
  const from = minutes(q.from);
  const to = minutes(q.to);
  if (from === to) return false;
  const t = now.getHours() * 60 + now.getMinutes();
  return from < to ? t >= from && t < to : t >= from || t < to;
}

/** Browser support and the user's decision for this origin. */
export type NotifyPermission = 'unsupported' | 'default' | 'granted' | 'denied';

export interface DeliveryContext {
  /** The tab is visible and its window has the focus. */
  attended: boolean;
  permission: NotifyPermission;
  now: Date;
}

export interface Delivery {
  /** Show a desktop notification. */
  show: boolean;
  /** Play the sound of the kind. */
  sound: boolean;
}

/**
 * Whether an event of `kind` is shown and/or heard. The sound needs no browser permission (it is
 * additional to the in-app indicators, never the only signal), the notification does.
 */
export function decideDelivery(
  prefs: NotificationPrefs,
  kind: NotifyKind,
  ctx: DeliveryContext,
): Delivery {
  const none = { show: false, sound: false };
  if (!prefs.enabled || !prefs.kinds[kind]) return none;
  if (prefs.onlyWhenHidden && ctx.attended) return none;
  if (inQuietHours(prefs.quietHours, ctx.now)) return none;
  return { show: ctx.permission === 'granted', sound: prefs.sound && prefs.volume > 0 };
}
