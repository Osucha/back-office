import {
  DEFAULT_NOTIFICATION_PREFS,
  decideDelivery,
  defaultPrefsFor,
  inQuietHours,
  parsePrefs,
  prefsStorageKey,
  type NotificationPrefs,
} from './notification-prefs';

const on = (extra: Partial<NotificationPrefs> = {}): NotificationPrefs => ({
  ...DEFAULT_NOTIFICATION_PREFS,
  enabled: true,
  ...extra,
});
const at = (h: number, m = 0) => new Date(2026, 9, 8, h, m);

describe('notification preferences', () => {
  it('has the agreed defaults', () => {
    const d = DEFAULT_NOTIFICATION_PREFS;
    expect(d.enabled).toBe(false);
    expect(d.kinds).toEqual({
      turn: true,
      runFailed: true,
      runLanded: false,
      budget: true,
      ciFailed: false,
      connectionExpiring: false,
    });
    expect(d.onlyWhenHidden).toBe(true);
    expect(d.sound).toBe(true);
  });

  it('turns "Connection expiring" on by default for administrators only, keeping stored choices', () => {
    expect(defaultPrefsFor(true).kinds.connectionExpiring).toBe(true);
    expect(defaultPrefsFor(false).kinds.connectionExpiring).toBe(false);
    expect(parsePrefs(null, defaultPrefsFor(true)).kinds.connectionExpiring).toBe(true);
    // a preference stored before the type existed gets the default; an explicit choice wins
    expect(parsePrefs('{"kinds":{"turn":false}}', defaultPrefsFor(true)).kinds).toMatchObject({
      turn: false,
      connectionExpiring: true,
    });
    expect(
      parsePrefs('{"kinds":{"connectionExpiring":false}}', defaultPrefsFor(true)).kinds
        .connectionExpiring,
    ).toBe(false);
  });

  it('parses stored JSON defensively', () => {
    expect(parsePrefs(null)).toEqual(DEFAULT_NOTIFICATION_PREFS);
    expect(parsePrefs('{broken')).toEqual(DEFAULT_NOTIFICATION_PREFS);
    expect(parsePrefs('[1,2]')).toEqual(DEFAULT_NOTIFICATION_PREFS);
    const p = parsePrefs(
      JSON.stringify({
        enabled: true,
        kinds: { runLanded: true, turn: 'yes' },
        volume: 7,
        quietHours: { enabled: true, from: '25:00', to: '06:30' },
      }),
    );
    expect(p.enabled).toBe(true);
    expect(p.kinds.runLanded).toBe(true);
    expect(p.kinds.turn).toBe(true); // invalid value -> default
    expect(p.volume).toBe(1); // clamped
    expect(p.quietHours).toEqual({ enabled: true, from: '22:00', to: '06:30' });
  });

  it('keys the storage per user', () => {
    expect(prefsStorageKey('u1')).toBe('bo.notifications.u1');
    expect(prefsStorageKey(null)).toBe('bo.notifications.anonymous');
  });

  it('knows quiet hours, also over midnight', () => {
    const night = { enabled: true, from: '22:00', to: '07:00' };
    expect(inQuietHours(night, at(23))).toBe(true);
    expect(inQuietHours(night, at(6, 59))).toBe(true);
    expect(inQuietHours(night, at(7))).toBe(false);
    expect(inQuietHours(night, at(12))).toBe(false);
    const lunch = { enabled: true, from: '12:00', to: '13:00' };
    expect(inQuietHours(lunch, at(12, 30))).toBe(true);
    expect(inQuietHours(lunch, at(13))).toBe(false);
    expect(inQuietHours({ ...lunch, enabled: false }, at(12, 30))).toBe(false);
    expect(inQuietHours({ enabled: true, from: '09:00', to: '09:00' }, at(9))).toBe(false);
  });

  describe('decideDelivery', () => {
    const ctx = { attended: false, permission: 'granted' as const, now: at(10) };

    it('shows and sounds an enabled type in a background tab', () => {
      expect(decideDelivery(on(), 'turn', ctx)).toEqual({ show: true, sound: true });
    });

    it('respects the master switch and the type', () => {
      expect(decideDelivery(on({ enabled: false }), 'turn', ctx)).toEqual({
        show: false,
        sound: false,
      });
      expect(decideDelivery(on(), 'runLanded', ctx)).toEqual({ show: false, sound: false });
    });

    it('stays quiet while the user looks at the tab, unless asked otherwise', () => {
      expect(decideDelivery(on(), 'turn', { ...ctx, attended: true }).show).toBe(false);
      expect(
        decideDelivery(on({ onlyWhenHidden: false }), 'turn', { ...ctx, attended: true }).show,
      ).toBe(true);
    });

    it('respects quiet hours, the permission, the sound switch and the volume', () => {
      const quiet = on({ quietHours: { enabled: true, from: '09:00', to: '11:00' } });
      expect(decideDelivery(quiet, 'turn', ctx)).toEqual({ show: false, sound: false });
      expect(decideDelivery(on(), 'turn', { ...ctx, permission: 'denied' })).toEqual({
        show: false,
        sound: true,
      });
      expect(decideDelivery(on({ sound: false }), 'turn', ctx).sound).toBe(false);
      expect(decideDelivery(on({ volume: 0 }), 'turn', ctx).sound).toBe(false);
    });
  });
});
