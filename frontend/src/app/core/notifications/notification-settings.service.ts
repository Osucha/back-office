import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { IdentityService } from '../auth/identity.service';
import {
  DEFAULT_NOTIFICATION_PREFS,
  defaultPrefsFor,
  parsePrefs,
  prefsStorageKey,
  type NotificationPrefs,
  type NotifyPermission,
} from './notification-prefs';

/** The browser's notification permission for this origin, or `unsupported`. */
export function readPermission(): NotifyPermission {
  const N = (globalThis as { Notification?: typeof Notification }).Notification;
  if (!N || globalThis.isSecureContext === false) return 'unsupported';
  const p = N.permission;
  return p === 'granted' || p === 'denied' ? p : 'default';
}

/**
 * Desktop notification preferences of the signed-in user (ADR 0032), stored in this browser per user
 * (`bo.notifications.<userId>`; the contract has no field for them) and synchronised across tabs.
 * Also tracks the browser permission. `DesktopNotifierLoader` loads the notifier while `active()`
 * (`desktop-notifier.service.ts`, a lazy chunk with the sounds), so the initial bundle stays small.
 */
@Injectable({ providedIn: 'root' })
export class NotificationSettingsService {
  private readonly identity = inject(IdentityService);
  private readonly doc = inject(DOCUMENT);
  private readonly key = computed(() => prefsStorageKey(this.identity.user()?.id));
  /** Defaults depend on the user ("Connection expiring" is on for administrators). */
  private readonly defaults = computed(() =>
    defaultPrefsFor(this.identity.can('admin.connections.manage')),
  );

  readonly prefs = signal<NotificationPrefs>(DEFAULT_NOTIFICATION_PREFS);
  readonly permission = signal<NotifyPermission>(readPermission());
  readonly supported = computed(() => this.permission() !== 'unsupported');
  /** Notifications are on for a signed-in user (the notifier runs). */
  readonly active = computed(() => this.prefs().enabled && this.identity.isAuthenticated());

  constructor() {
    effect(() => {
      const key = this.key();
      const defaults = this.defaults();
      untracked(() => this.prefs.set(parsePrefs(read(key), defaults)));
    });
    // Another tab changed the preferences, or the user changed the permission in the browser.
    const onStorage = (e: StorageEvent) => {
      if (e.key === this.key()) this.prefs.set(parsePrefs(e.newValue, this.defaults()));
    };
    const onFocus = () => this.permission.set(readPermission());
    const win = this.doc.defaultView;
    win?.addEventListener('storage', onStorage);
    win?.addEventListener('focus', onFocus);
    this.doc.addEventListener('visibilitychange', onFocus);
    void this.watchPermission();
    inject(DestroyRef).onDestroy(() => {
      win?.removeEventListener('storage', onStorage);
      win?.removeEventListener('focus', onFocus);
      this.doc.removeEventListener('visibilitychange', onFocus);
    });
  }

  update(patch: Partial<NotificationPrefs>): void {
    const next = parsePrefs(JSON.stringify({ ...this.prefs(), ...patch }), this.defaults());
    this.prefs.set(next);
    try {
      localStorage.setItem(this.key(), JSON.stringify(next));
    } catch {
      // storage unavailable: the choice lasts for this page only
    }
  }

  /**
   * Turns notifications on. Must run inside the click handler (a user gesture): the browser shows
   * its permission prompt only then. Sounds work even when the permission is refused.
   */
  async enable(): Promise<NotifyPermission> {
    let permission = readPermission();
    if (permission === 'default') {
      try {
        const N = (globalThis as { Notification?: typeof Notification }).Notification!;
        const result = await N.requestPermission();
        permission = result === 'granted' || result === 'denied' ? result : 'default';
      } catch {
        permission = readPermission();
      }
    }
    this.permission.set(permission);
    this.update({ enabled: true });
    return permission;
  }

  disable(): void {
    this.update({ enabled: false });
  }

  /** Quick toggle (avatar menu). */
  toggle(): Promise<NotifyPermission> | void {
    if (this.prefs().enabled) this.disable();
    else return this.enable();
  }

  private async watchPermission(): Promise<void> {
    try {
      const status = await globalThis.navigator?.permissions?.query({
        name: 'notifications' as PermissionName,
      });
      if (status) status.onchange = () => this.permission.set(readPermission());
    } catch {
      // Permissions API missing (older Safari): focus / visibility changes re-read it
    }
  }
}

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}
