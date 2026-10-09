import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { AvatarMenuComponent } from './avatar-menu.component';
import { EventStreamService } from '../core/events/event-stream.service';
import type { Crumb } from '../core/ui/relay/breadcrumb.service';
import { CommandPaletteService } from '../core/ui/relay/command-palette.service';
import { GlyphComponent } from '../core/ui/relay/glyph.component';
import { KbdComponent } from '../core/ui/relay/kbd.component';
import { icu } from '../core/i18n/icu';

/**
 * Top bar: breadcrumb, command palette trigger, the amber *your turn* counter (hidden at zero),
 * live-update state, activity dock toggle and the avatar menu (account, theme, density, sign out).
 */
@Component({
  selector: 'app-top-bar',
  imports: [RouterLink, NzIconModule, KbdComponent, GlyphComponent, AvatarMenuComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="bar">
      <nav class="crumbs" aria-label="Breadcrumb" i18n-aria-label="@@shell.topbar.breadcrumb">
        <ol>
          @for (c of crumbs(); track $index; let last = $last) {
            <li>
              @if (c.link && !last) {
                <a [routerLink]="c.link" [class.mono]="c.mono">{{ c.label }}</a>
              } @else {
                <span [class.mono]="c.mono" [attr.aria-current]="last ? 'page' : null">{{
                  c.label
                }}</span>
              }
            </li>
          }
        </ol>
      </nav>

      <button type="button" class="search" (click)="palette.open()" aria-haspopup="dialog">
        <nz-icon nzType="search" aria-hidden="true" />
        <span class="search-text" i18n="@@shell.topbar.search">Search or jump to…</span>
        <bo-kbd keys="mod+k" />
      </button>

      <div class="right">
        @if (turnCount() > 0) {
          <a
            class="turn"
            routerLink="/inbox"
            [attr.aria-label]="turnLabel()"
            title="Your turn: gates you can decide"
            i18n-title="@@shell.topbar.turnTitle"
          >
            <bo-glyph holder="you" [size]="14" />
            <span class="n">{{ turnCount() }}</span>
          </a>
        }
        <span
          class="live"
          [attr.data-state]="events.state()"
          role="status"
          [attr.aria-label]="streamTitle()"
          [attr.title]="streamTitle()"
        ></span>
        <button
          type="button"
          class="icon-btn"
          [class.on]="dockOpen()"
          (click)="dockToggle.emit()"
          [attr.aria-pressed]="dockOpen()"
          aria-label="Activity"
          i18n-aria-label="@@shell.topbar.activity"
          title="Activity (.)"
          i18n-title="@@shell.topbar.activityTitle"
        >
          <nz-icon nzType="unordered-list" />
        </button>
        <button
          type="button"
          class="icon-btn"
          (click)="helpOpen.emit()"
          aria-label="Help for this page"
          i18n-aria-label="@@shell.topbar.help"
          title="Help for this page (F1)"
          i18n-title="@@shell.topbar.helpTitle"
          aria-haspopup="dialog"
        >
          <nz-icon nzType="question-circle" />
        </button>
        <app-avatar-menu (signOut)="signOut.emit()" />
      </div>
    </header>
  `,
  styles: [
    `
      :host {
        display: block;
        position: sticky;
        top: 0;
        z-index: 800;
      }
      .bar {
        height: var(--bo-topbar-h);
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 0 16px 0 24px;
        background: color-mix(in srgb, var(--bo-bg) 88%, transparent);
        backdrop-filter: blur(8px);
        border-bottom: 1px solid var(--bo-line);
      }
      .crumbs {
        flex: 1 1 auto;
        min-width: 0;
      }
      ol {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        align-items: center;
        min-width: 0;
        font-size: 13px;
      }
      li {
        display: flex;
        align-items: center;
        min-width: 0;
        white-space: nowrap;
      }
      li + li::before {
        content: '/';
        margin: 0 8px;
        color: var(--bo-line-strong);
      }
      li a {
        color: var(--bo-ink-3);
        text-decoration: none;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      li a:hover {
        color: var(--bo-ink);
      }
      li span {
        color: var(--bo-ink);
        font-weight: 500;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .mono {
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .search {
        display: flex;
        align-items: center;
        gap: 8px;
        height: 32px;
        width: min(340px, 34vw);
        padding: 0 6px 0 10px;
        border: 1px solid var(--bo-line);
        border-radius: 8px;
        background: var(--bo-surface);
        color: var(--bo-ink-3);
        font: inherit;
        font-size: 13px;
        cursor: text;
        flex: none;
      }
      .search:hover {
        border-color: var(--bo-line-strong);
      }
      .search-text {
        flex: 1;
        text-align: left;
        white-space: nowrap;
        overflow: hidden;
      }
      .right {
        display: flex;
        align-items: center;
        gap: 10px;
        flex: none;
      }
      .turn {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        height: 28px;
        padding: 0 10px 0 8px;
        border-radius: 999px;
        background: var(--bo-you);
        color: #1c1e21;
        font-weight: 700;
        font-size: 13px;
        text-decoration: none;
        font-variant-numeric: tabular-nums;
      }
      .turn bo-glyph {
        color: #1c1e21;
      }
      .turn:hover {
        filter: brightness(1.05);
      }
      .live {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--bo-idle);
      }
      .live[data-state='connected'] {
        background: var(--bo-done);
        box-shadow: 0 0 0 3px var(--bo-done-soft);
      }
      .live[data-state='connecting'] {
        background: var(--bo-you);
      }
      .icon-btn {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 32px;
        height: 32px;
        border-radius: 8px;
        color: var(--bo-ink-2);
      }
      .icon-btn:hover,
      .icon-btn.on {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
      }
      .avatar-btn {
        all: unset;
        cursor: pointer;
        border-radius: 50%;
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
      .search:focus-visible,
      .icon-btn:focus-visible,
      .avatar-btn:focus-visible,
      .turn:focus-visible,
      li a:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .check {
        display: inline-block;
        width: 16px;
      }
      @media (max-width: 900px) {
        .search-text {
          display: none;
        }
        .search {
          width: auto;
        }
      }
    `,
  ],
})
export class TopBarComponent {
  readonly events = inject(EventStreamService);
  readonly palette = inject(CommandPaletteService);

  readonly crumbs = input<Crumb[]>([]);
  readonly turnCount = input(0);
  readonly dockOpen = input(false);
  readonly dockToggle = output<void>();
  readonly helpOpen = output<void>();
  readonly signOut = output<void>();

  readonly streamTitle = computed(() => {
    const s = this.events.state();
    return s === 'connected'
      ? $localize`:@@shell.topbar.live.on:Live updates on`
      : s === 'connecting'
        ? $localize`:@@shell.topbar.live.connecting:Connecting to live updates`
        : $localize`:@@shell.topbar.live.off:Live updates off; retrying`;
  });

  readonly turnLabel = computed(() =>
    icu(
      $localize`:@@shell.topbar.turnLabel:{count, plural, one {# decision waits for you} other {# decisions wait for you}}`,
      { count: this.turnCount() },
    ),
  );
}
