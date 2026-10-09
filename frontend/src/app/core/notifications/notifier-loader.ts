import { Injectable, Injector, effect, inject, untracked } from '@angular/core';
import { NotificationSettingsService } from './notification-settings.service';

/**
 * Loads the desktop notifier (a lazy chunk with the sounds, ADR 0032) the first time notifications
 * are on for a signed-in user. Injected by the shell; the notifier itself checks the preferences on
 * every event, so turning notifications off needs no unloading.
 */
@Injectable({ providedIn: 'root' })
export class DesktopNotifierLoader {
  private loading = false;

  constructor() {
    const settings = inject(NotificationSettingsService);
    const injector = inject(Injector);
    effect(() => {
      if (!settings.active()) return;
      untracked(() => {
        if (this.loading) return;
        this.loading = true;
        import('./desktop-notifier.service')
          .then((m) => injector.get(m.DesktopNotifierService).start())
          .catch(() => {
            this.loading = false;
          });
      });
    });
  }
}
