import { Injectable, TemplateRef, inject } from '@angular/core';
import { NavigationStart, Router } from '@angular/router';
import { NzNotificationComponent, NzNotificationService } from 'ng-zorro-antd/notification';
import { filter } from 'rxjs';

/** The one key used by every screen notification: showing a new one replaces the previous one. */
export const SCREEN_NOTIFICATION_KEY = 'bo-screen';

/** Success/info notifications close by themselves; errors stay until the user closes them. */
export const TRANSIENT_MS = 4500;

export interface NotifyOptions {
  /** Buttons rendered under the text (`nzButton`). */
  buttons?: TemplateRef<{ $implicit: NzNotificationComponent }>;
}

/**
 * Screen notifications (NG-ZORRO `nz-notification`) with the rules of the reference UI:
 *
 * - at most one screen notification is visible at a time (a fixed key, so a new one replaces it);
 * - error notifications stay until closed; success/info disappear after a few seconds;
 * - the screen notification is closed when the user navigates to another page (query-only changes
 *   such as switching a tab keep it);
 * - the session notice (`SessionExpiryService`) uses its own key and is not affected.
 *
 * Short confirmations of a successful action keep using `NzMessageService.success` (top toast).
 */
@Injectable({ providedIn: 'root' })
export class NotifyService {
  private readonly notification = inject(NzNotificationService);
  private readonly router = inject(Router);
  private lastPath = pathOf(this.router.url);

  constructor() {
    this.router.events
      .pipe(filter((e): e is NavigationStart => e instanceof NavigationStart))
      .subscribe((e) => {
        const path = pathOf(e.url);
        if (path !== this.lastPath) this.close();
        this.lastPath = path;
      });
  }

  error(title: string, detail = '', options: NotifyOptions = {}): void {
    this.show('error', title, detail, 0, options);
  }

  warning(title: string, detail = '', options: NotifyOptions = {}): void {
    this.show('warning', title, detail, 0, options);
  }

  info(title: string, detail = ''): void {
    this.show('info', title, detail, TRANSIENT_MS, {});
  }

  success(title: string, detail = ''): void {
    this.show('success', title, detail, TRANSIENT_MS, {});
  }

  close(): void {
    this.notification.remove(SCREEN_NOTIFICATION_KEY);
  }

  private show(
    type: 'error' | 'warning' | 'info' | 'success',
    title: string,
    detail: string,
    duration: number,
    options: NotifyOptions,
  ): void {
    // Remove first: replacing in place would keep the timer of the previous notification.
    this.notification.remove(SCREEN_NOTIFICATION_KEY);
    this.notification[type](title, detail, {
      nzKey: SCREEN_NOTIFICATION_KEY,
      nzDuration: duration,
      ...(options.buttons ? { nzButton: options.buttons } : {}),
    });
  }
}

function pathOf(url: string): string {
  const i = url.search(/[?#]/);
  return i >= 0 ? url.slice(0, i) : url;
}
