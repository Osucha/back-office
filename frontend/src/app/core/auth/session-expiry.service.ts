import { DOCUMENT } from '@angular/common';
import {
  DestroyRef,
  Injectable,
  NgZone,
  TemplateRef,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { NzNotificationComponent, NzNotificationService } from 'ng-zorro-antd/notification';

/** Template of a notification body (NG-ZORRO passes the notification and `nzData`). */
export type NoticeTemplate = TemplateRef<{ $implicit: NzNotificationComponent; data: unknown }>;
import { AuthApi } from '../api/auth.service';
import type { Me } from '../api/types';
import { IdentityService } from './identity.service';

/** One key for every session notice (warning and "expired"): each replaces the previous one. */
export const SESSION_NOTICE_KEY = 'bo-session';

/** The warning appears this long before the session ends. */
export const WARNING_SECONDS = 60;

export const SESSION_EXPIRED_TITLE = $localize`:@@core.session.expired.title:Your session has expired`;
export const SESSION_EXPIRED_TEXT = $localize`:@@core.session.expired.text:Sign in again to continue. If you have unsaved changes, stay on this page and copy them first: they will be lost when you sign in.`;

/** User input that counts as presence at the screen. */
export const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'wheel', 'touchstart'] as const;

export interface SessionNoticeTemplates {
  warning: NoticeTemplate;
  expired: NoticeTemplate;
}

/**
 * Session expiry without surprises (port of the reference `sessionExpiry.ts`).
 *
 * The server session has an idle timeout (`me.idleTimeoutSeconds`, extended by every request) and an
 * absolute limit (`me.absoluteExpiresAt`). This service mirrors both on the client so the user is
 * warned *before* the session ends, even when no request is made:
 *
 * - The idle deadline is `last successful API response + idleTimeout` (`touched()` is called by the
 *   HTTP interceptor). User input (mousedown/keydown/wheel/touchstart) counts as activity: when the
 *   last server contact is older than a minute (or a quarter of the timeout), one cheap
 *   `GET /api/v1/me` keeps the server session in step with the user. The service never polls on a
 *   timer, so an idle browser lets the session expire exactly as the server does.
 * - 60 s before the deadline a warning with a countdown appears; "Stay signed in" extends the
 *   session (`GET /me`; `GET /auth/session` would not extend it). Near the absolute limit the warning
 *   has no such button: the limit cannot be extended.
 * - At the deadline, or on any 401 from the API, the persistent notice "Your session has expired"
 *   appears with "Sign in again" (OIDC login with `returnTo` = current page) and "Stay on this page"
 *   (keeps the screen so unsaved work can be copied). While expired, navigation inside the app is
 *   blocked by `sessionGuard`, which shows the notice again.
 */
@Injectable({ providedIn: 'root' })
export class SessionExpiryService {
  private readonly identity = inject(IdentityService);
  private readonly api = inject(AuthApi);
  private readonly notification = inject(NzNotificationService);
  private readonly zone = inject(NgZone);
  private readonly doc = inject(DOCUMENT);

  readonly expired = signal(false);
  /** `idle` = can be extended, `absolute` = the maximum session length is reached. */
  readonly warning = signal<null | 'idle' | 'absolute'>(null);
  readonly secondsLeft = signal(0);

  private templates: SessionNoticeTemplates | null = null;
  private running = false;
  private idleMs = 0;
  private absoluteAt: number | null = null;
  private lastContact = 0;
  private keepAliveInFlight = false;
  private warnTimer: ReturnType<typeof setTimeout> | null = null;
  private expireTimer: ReturnType<typeof setTimeout> | null = null;
  private countdown: ReturnType<typeof setInterval> | null = null;
  private readonly onActivity = () => this.noteActivity();

  constructor() {
    effect(() => {
      const me = this.identity.me();
      untracked(() => (me ? this.start(me) : this.stop()));
    });
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Registered by `SessionNoticesComponent` (the templates carry the buttons). */
  registerTemplates(templates: SessionNoticeTemplates | null): void {
    this.templates = templates;
  }

  /** A successful API response: the server session was extended just now. */
  touched(): void {
    if (!this.running || this.expired()) return;
    this.lastContact = Date.now();
    if (this.warning() === 'idle') this.hideWarning();
    this.schedule();
  }

  /** User input: extends the server session when the last contact is not recent. */
  noteActivity(): void {
    if (!this.running || this.expired() || this.idleMs <= 0) return;
    if (Date.now() - this.lastContact >= this.keepAliveAfterMs()) this.keepAlive();
  }

  /** "Stay signed in". */
  staySignedIn(): void {
    this.keepAlive();
  }

  /** The session ended (deadline reached or 401 from the API). No-op when nobody was signed in. */
  markExpired(): void {
    if (!this.identity.isAuthenticated()) return;
    if (!this.expired()) {
      this.expired.set(true);
      this.clearTimers();
      this.warning.set(null);
    }
    this.showExpired();
  }

  /** Shows the "expired" notice again (used by `sessionGuard` on blocked navigation). */
  showExpired(): void {
    this.notification.remove(SESSION_NOTICE_KEY);
    if (this.templates) {
      this.notification.template(this.templates.expired, {
        nzKey: SESSION_NOTICE_KEY,
        nzDuration: 0,
      });
    } else {
      this.notification.warning(SESSION_EXPIRED_TITLE, SESSION_EXPIRED_TEXT, {
        nzKey: SESSION_NOTICE_KEY,
        nzDuration: 0,
      });
    }
  }

  /** "Sign in again": OIDC login returning to the current page. */
  signInAgain(): void {
    this.notification.remove(SESSION_NOTICE_KEY);
    void this.identity.login();
  }

  /** "Stay on this page": the notice closes, the screen stays, the session stays expired. */
  stayOnPage(): void {
    this.notification.remove(SESSION_NOTICE_KEY);
  }

  // ---- internals

  private start(me: Me): void {
    this.stop();
    this.running = true;
    this.expired.set(false);
    this.idleMs = Math.max(0, (me.idleTimeoutSeconds ?? 0) * 1000);
    const abs = me.absoluteExpiresAt ? Date.parse(me.absoluteExpiresAt) : NaN;
    this.absoluteAt = Number.isFinite(abs) ? abs : null;
    this.lastContact = Date.now();
    const win = this.doc.defaultView;
    if (win) {
      for (const type of ACTIVITY_EVENTS) {
        win.addEventListener(type, this.onActivity, { passive: true, capture: true });
      }
    }
    this.schedule();
  }

  private stop(): void {
    this.running = false;
    this.clearTimers();
    this.expired.set(false);
    this.warning.set(null);
    this.notification.remove(SESSION_NOTICE_KEY);
    const win = this.doc.defaultView;
    if (win) {
      for (const type of ACTIVITY_EVENTS) {
        win.removeEventListener(type, this.onActivity, { capture: true });
      }
    }
  }

  private keepAliveAfterMs(): number {
    return Math.min(60_000, this.idleMs / 4);
  }

  private keepAlive(): void {
    if (this.keepAliveInFlight || this.expired()) return;
    this.keepAliveInFlight = true;
    // A normal request: success reaches `touched()` through the interceptor, 401 `markExpired()`.
    this.api.touch().subscribe({
      next: () => (this.keepAliveInFlight = false),
      error: () => (this.keepAliveInFlight = false),
    });
  }

  /** The earlier of the idle deadline and the absolute limit, or null when neither is known. */
  private deadline(): { at: number; kind: 'idle' | 'absolute' } | null {
    const idleAt = this.idleMs > 0 ? this.lastContact + this.idleMs : null;
    if (idleAt === null && this.absoluteAt === null) return null;
    if (idleAt === null) return { at: this.absoluteAt!, kind: 'absolute' };
    if (this.absoluteAt !== null && this.absoluteAt <= idleAt) {
      return { at: this.absoluteAt, kind: 'absolute' };
    }
    return { at: idleAt, kind: 'idle' };
  }

  private schedule(): void {
    this.clearTimers();
    const d = this.deadline();
    if (!d) return;
    const now = Date.now();
    const untilEnd = d.at - now;
    if (untilEnd <= 0) {
      this.markExpired();
      return;
    }
    const untilWarn = untilEnd - WARNING_SECONDS * 1000;
    // Timers run outside Angular's zone bookkeeping; signals drive the (zoneless) UI.
    this.zone.runOutsideAngular(() => {
      this.expireTimer = setTimeout(() => this.zone.run(() => this.markExpired()), untilEnd);
      if (untilWarn <= 0) this.zone.run(() => this.showWarning(d.kind, d.at));
      else {
        this.warnTimer = setTimeout(
          () => this.zone.run(() => this.showWarning(d.kind, d.at)),
          untilWarn,
        );
      }
    });
  }

  private showWarning(kind: 'idle' | 'absolute', at: number): void {
    if (this.expired()) return;
    this.warning.set(kind);
    const tick = () => this.secondsLeft.set(Math.max(0, Math.ceil((at - Date.now()) / 1000)));
    tick();
    if (this.countdown) clearInterval(this.countdown);
    this.countdown = setInterval(tick, 1000);
    this.notification.remove(SESSION_NOTICE_KEY);
    if (this.templates) {
      this.notification.template(this.templates.warning, {
        nzKey: SESSION_NOTICE_KEY,
        nzDuration: 0,
      });
    } else {
      this.notification.warning(
        $localize`:@@core.session.warning.title:Your session is about to expire`,
        kind === 'idle'
          ? $localize`:@@core.session.warning.idle:Any activity keeps you signed in.`
          : $localize`:@@core.session.warning.absolute:The maximum session length is reached; save your work.`,
        { nzKey: SESSION_NOTICE_KEY, nzDuration: 0 },
      );
    }
  }

  private hideWarning(): void {
    this.warning.set(null);
    if (this.countdown) clearInterval(this.countdown);
    this.countdown = null;
    this.notification.remove(SESSION_NOTICE_KEY);
  }

  private clearTimers(): void {
    if (this.warnTimer) clearTimeout(this.warnTimer);
    if (this.expireTimer) clearTimeout(this.expireTimer);
    if (this.countdown) clearInterval(this.countdown);
    this.warnTimer = this.expireTimer = this.countdown = null;
  }
}
