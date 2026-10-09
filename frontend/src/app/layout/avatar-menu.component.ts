import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import type { Theme } from '../core/api/types';
import { IdentityService } from '../core/auth/identity.service';
import { NotificationSettingsService } from '../core/notifications/notification-settings.service';
import { DensityService, type Density } from '../core/ui/density.service';
import { ShortcutsService } from '../core/ui/relay/shortcuts.service';
import { ThemeService } from '../core/ui/theme.service';
import {
  LOCALE_NAMES,
  SUPPORTED_LOCALES,
  activeLocale,
  switchLocale,
  type AppLocale,
} from '../core/i18n/locale';

/**
 * Avatar menu (a small ARIA menu, no overlay dependency so it stays in the initial bundle cheaply):
 * account, theme (System / Light / Dark), density (Comfortable / Compact), language (English /
 * Polski; reloads the page), desktop notifications on/off, shortcuts, manual, sign out.
 */
@Component({
  selector: 'app-avatar-menu',
  imports: [NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(document:pointerdown)': 'outside($event)' },
  template: `
    <button
      #trigger
      type="button"
      class="avatar-btn"
      aria-haspopup="menu"
      [attr.aria-expanded]="open()"
      aria-controls="bo-avatar-menu"
      [attr.aria-label]="triggerLabel()"
      (click)="toggle()"
      (keydown.arrowdown)="openAndFocus($event)"
    >
      <span class="avatar" aria-hidden="true">{{ initials() }}</span>
    </button>
    @if (open()) {
      <div
        #menu
        id="bo-avatar-menu"
        class="menu"
        role="menu"
        aria-label="Account"
        i18n-aria-label="@@shell.avatar.menu"
        (keydown)="onKey($event)"
      >
        <div class="who" role="presentation">
          <div class="who-name">{{ displayName() }}</div>
          <div class="who-mail">{{ identity.user()?.email }}</div>
        </div>
        <div class="sep" role="separator"></div>
        <button type="button" role="menuitem" class="item" (click)="go('/account')">
          <nz-icon nzType="user" /> <span i18n="@@shell.avatar.account">Account</span>
        </button>
        <div class="sep" role="separator"></div>
        <div class="group" role="group" aria-labelledby="bo-am-theme">
          <div class="group-title" id="bo-am-theme" i18n="@@shell.avatar.theme">Theme</div>
          @for (t of themes; track t.value) {
            <button
              type="button"
              role="menuitemradio"
              class="item"
              [attr.aria-checked]="theme.preference() === t.value"
              (click)="theme.set(t.value); close()"
            >
              <span class="check">
                @if (theme.preference() === t.value) {
                  <nz-icon nzType="check" />
                }
              </span>
              {{ t.label }}
            </button>
          }
        </div>
        <div class="group" role="group" aria-labelledby="bo-am-density">
          <div class="group-title" id="bo-am-density" i18n="@@shell.avatar.density">Density</div>
          @for (d of densities; track d.value) {
            <button
              type="button"
              role="menuitemradio"
              class="item"
              [attr.aria-checked]="density.density() === d.value"
              (click)="density.set(d.value); close()"
            >
              <span class="check">
                @if (density.density() === d.value) {
                  <nz-icon nzType="check" />
                }
              </span>
              {{ d.label }}
            </button>
          }
        </div>
        <div class="group" role="group" aria-labelledby="bo-am-language">
          <div class="group-title" id="bo-am-language" i18n="@@shell.avatar.language">
            Language / Język
          </div>
          @for (l of locales; track l) {
            <button
              type="button"
              role="menuitemradio"
              class="item"
              [attr.aria-checked]="locale === l"
              [attr.lang]="l"
              (click)="setLocale(l)"
            >
              <span class="check">
                @if (locale === l) {
                  <nz-icon nzType="check" />
                }
              </span>
              {{ localeNames[l] }}
            </button>
          }
        </div>
        <div class="sep" role="separator"></div>
        <button
          type="button"
          role="menuitemcheckbox"
          class="item"
          [attr.aria-checked]="notifications.prefs().enabled"
          (click)="toggleNotifications()"
        >
          <span class="check">
            @if (notifications.prefs().enabled) {
              <nz-icon nzType="check" />
            }
          </span>
          <span i18n="@@shell.avatar.notifications">Desktop notifications</span>
        </button>
        <button
          type="button"
          role="menuitem"
          class="item"
          (click)="shortcuts.overlayOpen.set(true); close()"
        >
          <nz-icon nzType="question-circle" />
          <span i18n="@@shell.avatar.shortcuts">Keyboard shortcuts</span>
        </button>
        <button type="button" role="menuitem" class="item" (click)="go('/help')">
          <nz-icon nzType="read" /> <span i18n="@@shell.avatar.manual">Manual</span>
        </button>
        <button type="button" role="menuitem" class="item" (click)="close(); signOut.emit()">
          <nz-icon nzType="logout" /> <span i18n="@@shell.avatar.signOut">Sign out</span>
        </button>
      </div>
    }
  `,
  styles: [
    `
      :host {
        position: relative;
        display: inline-flex;
      }
      .avatar-btn {
        all: unset;
        cursor: pointer;
        border-radius: 50%;
      }
      .avatar-btn:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .avatar {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 30px;
        height: 30px;
        border-radius: 50%;
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        font-size: 12px;
        font-weight: 600;
      }
      .menu {
        position: absolute;
        right: 0;
        top: calc(100% + 8px);
        min-width: 220px;
        padding: 6px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: 10px;
        box-shadow: var(--bo-shadow-overlay);
        z-index: 1050;
      }
      .who {
        padding: 6px 10px 8px;
      }
      .who-name {
        font-weight: 600;
        font-size: 13px;
      }
      .who-mail {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .sep {
        height: 1px;
        background: var(--bo-line);
        margin: 4px 0;
      }
      .group-title {
        font-size: 11px;
        font-weight: 600;
        color: var(--bo-ink-3);
        padding: 6px 10px 2px;
      }
      .item {
        all: unset;
        box-sizing: border-box;
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        padding: 6px 10px;
        border-radius: 6px;
        font-size: 13px;
        color: var(--bo-ink);
        cursor: pointer;
      }
      .item:hover,
      .item:focus-visible {
        background: var(--bo-surface-2);
        outline: none;
      }
      .item:focus-visible {
        box-shadow: inset 0 0 0 2px var(--bo-accent);
      }
      .check {
        display: inline-flex;
        width: 16px;
      }
    `,
  ],
})
export class AvatarMenuComponent {
  readonly identity = inject(IdentityService);
  readonly theme = inject(ThemeService);
  readonly density = inject(DensityService);
  readonly notifications = inject(NotificationSettingsService);
  readonly shortcuts = inject(ShortcutsService);
  private readonly router = inject(Router);
  private readonly host = inject(ElementRef<HTMLElement>);
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');
  private readonly menu = viewChild<ElementRef<HTMLElement>>('menu');

  readonly signOut = output<void>();
  readonly open = signal(false);

  readonly themes: { value: Theme; label: string }[] = [
    { value: 'SYSTEM', label: $localize`:@@shell.avatar.theme.system:System` },
    { value: 'LIGHT', label: $localize`:@@shell.avatar.theme.light:Light` },
    { value: 'DARK', label: $localize`:@@shell.avatar.theme.dark:Dark` },
  ];
  readonly densities: { value: Density; label: string }[] = [
    { value: 'comfortable', label: $localize`:@@shell.avatar.density.comfortable:Comfortable` },
    { value: 'compact', label: $localize`:@@shell.avatar.density.compact:Compact` },
  ];
  /** Language switch: each name in its own language; switching reloads the page. */
  readonly locales = SUPPORTED_LOCALES;
  readonly localeNames = LOCALE_NAMES;
  readonly locale = activeLocale();
  readonly displayName = computed(() => {
    const u = this.identity.user();
    return u?.displayName || u?.email || $localize`:@@shell.avatar.signedIn:Signed in`;
  });
  readonly triggerLabel = computed(
    () => $localize`:@@shell.avatar.trigger:Account menu for ${this.displayName()}:name:`,
  );
  readonly initials = computed(() => initialsOf(this.displayName()));

  toggle(): void {
    if (this.open()) this.close();
    else {
      this.open.set(true);
      this.focusItem(0);
    }
  }

  openAndFocus(e: Event): void {
    e.preventDefault();
    this.open.set(true);
    this.focusItem(0);
  }

  close(): void {
    if (!this.open()) return;
    this.open.set(false);
    this.trigger()?.nativeElement.focus();
  }

  /**
   * Quick toggle; the click is the user gesture the permission prompt needs. When the browser
   * refuses (or cannot show) notifications, the preferences explain how to allow them.
   */
  toggleNotifications(): void {
    const result = this.notifications.toggle();
    this.close();
    void result?.then((permission) => {
      if (permission === 'denied' || permission === 'unsupported')
        void this.router.navigate(['/account'], { queryParams: { section: 'notifications' } });
    });
  }

  setLocale(locale: AppLocale): void {
    this.open.set(false);
    switchLocale(locale);
  }

  go(url: string): void {
    this.open.set(false);
    void this.router.navigateByUrl(url);
  }

  outside(e: PointerEvent): void {
    if (this.open() && !this.host.nativeElement.contains(e.target as Node)) this.open.set(false);
  }

  onKey(e: KeyboardEvent): void {
    const items = this.items();
    const i = items.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      this.close();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      items[(i + 1) % items.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      items[(i - 1 + items.length) % items.length]?.focus();
    } else if (e.key === 'Tab') {
      this.open.set(false);
    }
  }

  private items(): HTMLElement[] {
    return Array.from(this.menu()?.nativeElement.querySelectorAll<HTMLElement>('.item') ?? []);
  }

  private focusItem(i: number): void {
    queueMicrotask(() => this.items()[i]?.focus());
  }
}

/** Two letters for the avatar: first letters of the first two words (or of the e-mail). */
export function initialsOf(name: string): string {
  const words = name
    .replace(/@.*/, '')
    .split(/[\s._-]+/)
    .filter(Boolean);
  const letters = words.length >= 2 ? words[0][0] + words[1][0] : (words[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}
