import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { DesktopNotifierService } from '../../../core/notifications/desktop-notifier.service';
import { KIND_SOUND, testContent } from '../../../core/notifications/notification-content';
import {
  NOTIFY_KINDS,
  type NotifyKind,
  type QuietHours,
} from '../../../core/notifications/notification-prefs';
import { NotificationSettingsService } from '../../../core/notifications/notification-settings.service';
import { soundPlayer } from '../../../core/notifications/sounds';

interface KindText {
  title: string;
  help: string;
  preview: string;
}

/**
 * My account → Preferences → Notifications (ADR 0032): master switch (asks for the browser
 * permission from the click), event types with a sound preview each, "only when the tab is not in
 * view", sound and volume, quiet hours, a test notification. Saved per user in this browser at
 * once. Denied / unsupported permission states explain how to allow notifications again.
 */
@Component({
  selector: 'app-notifications-section',
  imports: [FormsModule, NzButtonModule, NzIconModule, NzSwitchModule, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="status" [attr.data-state]="settings.permission()" role="status">
      @switch (settings.permission()) {
        @case ('granted') {
          <nz-icon nzType="check-circle" />
          <span i18n="@@account.notify.granted"
            >Your browser allows notifications from Back Office.</span
          >
        }
        @case ('denied') {
          <nz-icon nzType="stop" />
          <span>
            <span i18n="@@account.notify.denied"
              >Your browser blocks notifications from Back Office. To allow them, click the icon
              left of the address (padlock or settings), set Notifications to Allow, then reload the
              page.</span
            >
            <bo-help-link
              topic="account.notifications"
              variant="text"
              i18n="@@account.notify.deniedSteps"
              >Steps for your browser</bo-help-link
            >
          </span>
        }
        @case ('unsupported') {
          <nz-icon nzType="info-circle" />
          <span i18n="@@account.notify.unsupported"
            >This browser cannot show desktop notifications here (they need a current browser and a
            secure https address). Sounds still work.</span
          >
        }
        @default {
          <nz-icon nzType="bell" />
          <span i18n="@@account.notify.askFirst"
            >When you turn notifications on, your browser asks whether Back Office may show
            them.</span
          >
        }
      }
    </div>

    <div class="toggle main">
      <nz-switch
        id="nt-enabled"
        [ngModel]="prefs().enabled"
        (ngModelChange)="setEnabled($event)"
        [nzLoading]="asking()"
      />
      <div class="t-body">
        <label for="nt-enabled" class="t-title" i18n="@@account.notify.enabled"
          >Desktop notifications</label
        >
        <span class="t-help" i18n="@@account.notify.enabledHelp"
          >Shown while a Back Office tab is open, also in the background. They add to the counters
          in the app; nothing is announced only by a notification or a sound.</span
        >
      </div>
    </div>

    <fieldset class="group" [class.off]="!prefs().enabled">
      <legend class="sub" i18n="@@account.notify.about">Notify me about</legend>
      @for (k of kinds; track k) {
        <div class="toggle">
          <nz-switch
            [id]="'nt-' + k"
            [ngModel]="prefs().kinds[k]"
            (ngModelChange)="setKind(k, $event)"
            [nzDisabled]="!prefs().enabled"
          />
          <div class="t-body">
            <label [for]="'nt-' + k" class="t-title">{{ text[k].title }}</label>
            <span class="t-help">{{ text[k].help }}</span>
          </div>
          <button
            nz-button
            nzType="text"
            nzSize="small"
            type="button"
            class="preview"
            [attr.aria-label]="text[k].preview"
            [title]="text[k].preview"
            (click)="preview(k)"
          >
            <nz-icon nzType="sound" /> <span i18n="@@account.notify.preview">Preview</span>
          </button>
        </div>
      }
    </fieldset>

    <fieldset class="group" [class.off]="!prefs().enabled">
      <legend class="sub" i18n="@@account.notify.when">When and how</legend>
      <div class="toggle">
        <nz-switch
          id="nt-hidden"
          [ngModel]="prefs().onlyWhenHidden"
          (ngModelChange)="settings.update({ onlyWhenHidden: $event })"
          [nzDisabled]="!prefs().enabled"
        />
        <div class="t-body">
          <label for="nt-hidden" class="t-title" i18n="@@account.notify.onlyHidden"
            >Only when the tab is not in view</label
          >
          <span class="t-help" i18n="@@account.notify.onlyHiddenHelp"
            >Nothing pops up while you are looking at Back Office: another tab, another window or a
            minimised browser.</span
          >
        </div>
      </div>
      <div class="toggle">
        <nz-switch
          id="nt-sound"
          [ngModel]="prefs().sound"
          (ngModelChange)="setSound($event)"
          [nzDisabled]="!prefs().enabled"
        />
        <div class="t-body">
          <label for="nt-sound" class="t-title" i18n="@@account.notify.sound">Play a sound</label>
          <span class="t-help" i18n="@@account.notify.soundHelp"
            >A short, quiet tone of its own for each type. Previews play even when this is
            off.</span
          >
        </div>
      </div>
      <div class="row">
        <label for="nt-volume" class="t-title" i18n="@@account.notify.volume">Volume</label>
        <input
          id="nt-volume"
          type="range"
          min="0"
          max="100"
          step="5"
          [ngModel]="volumePct()"
          (ngModelChange)="setVolume($event)"
          [disabled]="!prefs().enabled"
          [attr.aria-valuetext]="volumePct() + '%'"
        />
        <output for="nt-volume" class="mono">{{ volumePct() }}%</output>
      </div>
      <div class="toggle">
        <nz-switch
          id="nt-quiet"
          [ngModel]="prefs().quietHours.enabled"
          (ngModelChange)="setQuiet({ enabled: $event })"
          [nzDisabled]="!prefs().enabled"
        />
        <div class="t-body">
          <label for="nt-quiet" class="t-title" i18n="@@account.notify.quiet">Quiet hours</label>
          <span class="t-help" i18n="@@account.notify.quietHelp"
            >No notifications and no sounds between these times (this computer's clock).</span
          >
        </div>
      </div>
      @if (prefs().quietHours.enabled) {
        <div class="row times">
          <label for="nt-from" i18n="@@account.notify.from">From</label>
          <input
            id="nt-from"
            type="time"
            class="ant-input"
            [disabled]="!prefs().enabled"
            [ngModel]="prefs().quietHours.from"
            (ngModelChange)="setQuiet({ from: $event })"
          />
          <label for="nt-to" i18n="@@account.notify.to">to</label>
          <input
            id="nt-to"
            type="time"
            class="ant-input"
            [disabled]="!prefs().enabled"
            [ngModel]="prefs().quietHours.to"
            (ngModelChange)="setQuiet({ to: $event })"
          />
        </div>
      }
    </fieldset>

    <div class="actions">
      <span class="t-help" i18n="@@account.notify.savedHere"
        >Saved at once, in this browser, for your account.</span
      >
      <button
        nz-button
        type="button"
        [disabled]="settings.permission() !== 'granted' || !prefs().enabled"
        (click)="sendTest()"
        i18n="@@account.notify.test"
      >
        Send a test notification
      </button>
    </div>
  `,
  styles: [
    `
      :host {
        display: grid;
        gap: var(--bo-space-4);
      }
      .status {
        display: flex;
        gap: var(--bo-space-2);
        align-items: flex-start;
        padding: var(--bo-space-3);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        font-size: var(--bo-fs-dense);
        color: var(--bo-ink-2);
      }
      .status nz-icon {
        margin-top: 3px;
      }
      .status[data-state='granted'] {
        background: var(--bo-done-soft);
        color: var(--bo-done-ink);
      }
      .status[data-state='denied'] {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .group {
        border: 0;
        margin: 0;
        padding: 0;
        display: grid;
        gap: var(--bo-space-3);
      }
      .group.off .t-title,
      .group.off .row {
        color: var(--bo-ink-3);
      }
      .sub {
        margin: 0 0 var(--bo-space-1);
        padding: 0;
        font-size: var(--bo-fs-dense);
        font-weight: 600;
      }
      .toggle {
        display: grid;
        grid-template-columns: auto minmax(0, 1fr) auto;
        gap: var(--bo-space-3);
        align-items: start;
      }
      .toggle nz-switch {
        margin-top: 2px;
      }
      .t-body {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .t-title {
        font-size: var(--bo-fs-dense);
        font-weight: 500;
      }
      .t-help {
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .row {
        display: flex;
        gap: var(--bo-space-3);
        align-items: center;
        padding-left: 56px;
      }
      .row input[type='range'] {
        width: 200px;
        accent-color: var(--bo-ink);
      }
      .times input {
        width: 120px;
      }
      .actions {
        display: flex;
        gap: var(--bo-space-3);
        justify-content: space-between;
        align-items: center;
        padding-top: var(--bo-space-3);
        border-top: 1px solid var(--bo-line);
      }
      @media (max-width: 600px) {
        .row {
          padding-left: 0;
          flex-wrap: wrap;
        }
        .actions {
          flex-direction: column;
          align-items: stretch;
        }
      }
    `,
  ],
})
export class NotificationsSectionComponent {
  readonly settings = inject(NotificationSettingsService);
  private readonly notifier = inject(DesktopNotifierService);

  readonly prefs = this.settings.prefs;
  readonly kinds = NOTIFY_KINDS;
  readonly asking = signal(false);
  readonly volumePct = computed(() => Math.round(this.prefs().volume * 100));

  readonly text: Record<NotifyKind, KindText> = {
    turn: {
      title: $localize`:@@account.notify.kind.turn:Your turn`,
      help: $localize`:@@account.notify.kind.turnHelp:A gate you can decide is waiting, one was assigned to you, or an agent waits for you to confirm a Jira write.`,
      preview: $localize`:@@account.notify.kind.turnPreview:Preview the sound: Your turn`,
    },
    runFailed: {
      title: $localize`:@@account.notify.kind.runFailed:Run failed`,
      help: $localize`:@@account.notify.kind.runFailedHelp:Runs you started and runs in projects you own.`,
      preview: $localize`:@@account.notify.kind.runFailedPreview:Preview the sound: Run failed`,
    },
    runLanded: {
      title: $localize`:@@account.notify.kind.runLanded:Run landed`,
      help: $localize`:@@account.notify.kind.runLandedHelp:A run you started, or one in a project you own, finished successfully.`,
      preview: $localize`:@@account.notify.kind.runLandedPreview:Preview the sound: Run landed`,
    },
    budget: {
      title: $localize`:@@account.notify.kind.budget:Budget alert`,
      help: $localize`:@@account.notify.kind.budgetHelp:A budget you can raise reached its alert threshold or its limit.`,
      preview: $localize`:@@account.notify.kind.budgetPreview:Preview the sound: Budget alert`,
    },
    ciFailed: {
      title: $localize`:@@account.notify.kind.ciFailed:CI failed`,
      help: $localize`:@@account.notify.kind.ciFailedHelp:A CI execution or review gate in one of your projects failed.`,
      preview: $localize`:@@account.notify.kind.ciFailedPreview:Preview the sound: CI failed`,
    },
    connectionExpiring: {
      title: $localize`:@@account.notify.kind.connectionExpiring:Connection expiring`,
      help: $localize`:@@account.notify.kind.connectionExpiringHelp:A Jira token of a connection you manage expires within 14 days or has expired. On by default for administrators.`,
      preview: $localize`:@@account.notify.kind.connectionExpiringPreview:Preview the sound: Connection expiring`,
    },
  };

  /** Runs in the click handler: the permission prompt and the audio unlock need the gesture. */
  setEnabled(on: boolean): void {
    if (!on) {
      this.settings.disable();
      return;
    }
    if (this.prefs().sound) soundPlayer().unlock();
    this.asking.set(true);
    void this.settings.enable().finally(() => this.asking.set(false));
  }

  setKind(kind: NotifyKind, on: boolean): void {
    this.settings.update({ kinds: { ...this.prefs().kinds, [kind]: on } });
  }

  setSound(on: boolean): void {
    if (on) soundPlayer().unlock();
    this.settings.update({ sound: on });
  }

  setVolume(pct: number | string): void {
    this.settings.update({ volume: Number(pct) / 100 });
  }

  setQuiet(patch: Partial<QuietHours>): void {
    this.settings.update({ quietHours: { ...this.prefs().quietHours, ...patch } });
  }

  preview(kind: NotifyKind): void {
    const player = soundPlayer();
    player.unlock();
    player.play(KIND_SOUND[kind], this.prefs().volume, { force: true });
  }

  sendTest(): void {
    const kind = NOTIFY_KINDS.find((k) => this.prefs().kinds[k]) ?? 'turn';
    this.notifier.show(testContent(kind));
    if (this.prefs().sound) this.preview(kind);
  }
}
