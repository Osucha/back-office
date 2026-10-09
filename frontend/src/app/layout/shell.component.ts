import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { IdentityService } from '../core/auth/identity.service';
import { HelpPanelComponent } from '../core/help/help-panel.component';
import { HelpService } from '../core/help/help.service';
import { ActivityFeedService } from '../core/state/activity-feed.service';
import { DesktopNotifierLoader } from '../core/notifications/notifier-loader';
import { InboxService } from '../core/state/inbox.service';
import { BreadcrumbService, SECTION_LABELS, type Crumb } from '../core/ui/relay/breadcrumb.service';
import { CommandPaletteComponent } from '../core/ui/relay/command-palette.component';
import { CommandPaletteService } from '../core/ui/relay/command-palette.service';
import { ShortcutsOverlayComponent } from '../core/ui/relay/shortcuts-overlay.component';
import { ShortcutsService } from '../core/ui/relay/shortcuts.service';
import { ThemeService } from '../core/ui/theme.service';
import { ActivityDockComponent } from './activity-dock.component';
import { NAVIGATION, type NavItem, visibleNavigation } from './navigation';
import { registerDefaultPaletteProviders } from './palette-providers';
import { RailComponent } from './rail.component';
import { TopBarComponent } from './top-bar.component';

export { initialsOf } from './avatar-menu.component';

const PIN_KEY = 'backoffice.railPinned';
const DOCK_KEY = 'backoffice.dockOpen';

/**
 * The Relay shell: rail (left), top bar, content, optional activity dock (right), command palette
 * and the shortcuts overlay. Global shortcuts: `Ctrl/⌘ K` and `/` (palette), `?` (shortcuts),
 * `.` (activity dock), `g h` style jumps are in the palette instead.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RailComponent,
    TopBarComponent,
    ActivityDockComponent,
    CommandPaletteComponent,
    ShortcutsOverlayComponent,
    HelpPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.pinned]': 'pinned()',
    '[class.dock-open]': 'dockOpen()',
  },
  template: `
    <a class="skip" href="#bo-main" i18n="@@shell.skipToContent">Skip to content</a>
    <app-rail
      [main]="mainNav()"
      [bottom]="bottomNav()"
      [pinned]="pinned()"
      [turnCount]="inbox.decidableCount()"
      (pinnedChange)="setPinned($event)"
    />
    <div class="column">
      <app-top-bar
        [crumbs]="crumbs()"
        [turnCount]="inbox.decidableCount()"
        [dockOpen]="dockOpen()"
        (dockToggle)="toggleDock()"
        (helpOpen)="openHelp()"
        (signOut)="signOut()"
      />
      <main id="bo-main" class="content" tabindex="-1">
        <router-outlet />
      </main>
    </div>
    <!-- Out of the initial bundle: loaded when the browser is idle (or on first use). -->
    @defer (when dockOpen()) {
      @if (dockOpen()) {
        <app-activity-dock (closed)="toggleDock()" />
      }
    }
    @defer (on idle; when palette.isOpen()) {
      <bo-command-palette />
    }
    @defer (on idle; when shortcuts.overlayOpen()) {
      <bo-shortcuts-overlay />
    }
    <!-- Contextual help (marked + DOMPurify): loaded on the first help request. -->
    @defer (when help.panel()) {
      <bo-help-panel />
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-height: 100vh;
        background: var(--bo-bg);
        --shell-left: var(--bo-rail-w);
        --shell-right: 0px;
      }
      :host(.pinned) {
        --shell-left: var(--bo-rail-w-open);
      }
      :host(.dock-open) {
        --shell-right: var(--bo-dock-w);
      }
      .column {
        margin-left: var(--shell-left);
        margin-right: var(--shell-right);
        min-width: 0;
        transition: margin var(--bo-dur-panel) var(--bo-ease);
      }
      @media (prefers-reduced-motion: reduce) {
        .column {
          transition: none;
        }
      }
      .content {
        min-height: calc(100vh - var(--bo-topbar-h));
        outline: none;
      }
      .skip {
        position: fixed;
        left: 8px;
        top: -48px;
        z-index: 2000;
        padding: 8px 12px;
        border-radius: 6px;
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        text-decoration: none;
      }
      .skip:focus {
        top: 8px;
      }
      @media (max-width: 1100px) {
        :host(.dock-open) {
          --shell-right: 0px;
        }
      }
    `,
  ],
})
export class ShellComponent {
  readonly identity = inject(IdentityService);
  readonly inbox = inject(InboxService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly breadcrumbs = inject(BreadcrumbService);
  readonly palette = inject(CommandPaletteService);
  readonly shortcuts = inject(ShortcutsService);
  private readonly theme = inject(ThemeService);
  readonly help = inject(HelpService);

  readonly pinned = signal(readFlag(PIN_KEY));
  readonly dockOpen = signal(readFlag(DOCK_KEY));

  /** Menu built from the permissions of the signed-in user; empty groups are dropped. */
  readonly nav = computed<NavItem[]>(() =>
    visibleNavigation(NAVIGATION, (rule) => this.identity.allows(rule)),
  );
  readonly mainNav = computed(() => this.nav().filter((i) => i.place !== 'bottom'));
  readonly bottomNav = computed(() => this.nav().filter((i) => i.place === 'bottom'));

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /** Page crumbs when the page set them, otherwise section + route title. */
  readonly crumbs = computed<Crumb[]>(() => {
    const own = this.breadcrumbs.crumbs();
    if (own?.length) return own;
    const path = this.url().split(/[?#]/)[0];
    const first = path.split('/').filter(Boolean)[0] ?? 'home';
    const section = SECTION_LABELS[first] ?? first;
    const title = this.deepestTitle();
    const out: Crumb[] = [{ label: section, link: '/' + first }];
    if (title && title !== section && path.split('/').filter(Boolean).length > 1)
      out.push({ label: title });
    return out;
  });

  constructor() {
    // Collect live events from the start so the dock has history when opened.
    inject(ActivityFeedService);
    // Desktop notifications (ADR 0032): loads the notifier lazily once the user turned them on.
    inject(DesktopNotifierLoader);
    registerDefaultPaletteProviders();
    const GLOBAL = $localize`:@@shell.shortcut.group.global:Global`;
    this.shortcuts.register(
      [
        {
          keys: 'mod+k',
          description: $localize`:@@shell.shortcut.palette:Search and commands`,
          group: GLOBAL,
          allowInInputs: true,
          handler: () => this.palette.toggle(),
        },
        {
          keys: '/',
          description: $localize`:@@shell.shortcut.palette:Search and commands`,
          group: GLOBAL,
          hidden: true,
          handler: () => this.palette.open(),
        },
        {
          keys: '?',
          description: $localize`:@@shell.shortcut.shortcuts:Keyboard shortcuts`,
          group: GLOBAL,
          handler: () => this.shortcuts.overlayOpen.update((v) => !v),
        },
        {
          keys: '.',
          description: $localize`:@@shell.shortcut.dock:Activity dock`,
          group: GLOBAL,
          handler: () => this.toggleDock(),
        },
        {
          keys: 'alt+t',
          description: $localize`:@@shell.shortcut.theme:Toggle theme`,
          group: GLOBAL,
          handler: () => this.theme.toggle(),
        },
        {
          // F1: the platform's help key; `?` already opens the shortcuts.
          keys: 'F1',
          description: $localize`:@@shell.shortcut.help:Help for this page`,
          group: GLOBAL,
          allowInInputs: true,
          handler: () => this.openHelp(),
        },
      ],
      inject(DestroyRef),
    );
  }

  setPinned(value: boolean): void {
    this.pinned.set(value);
    writeFlag(PIN_KEY, value);
  }

  toggleDock(): void {
    this.dockOpen.update((v) => !v);
    writeFlag(DOCK_KEY, this.dockOpen());
  }

  /** Help of the current page in the help panel (toggles), or the manual for pages without one. */
  openHelp(): void {
    if (this.help.panel()) this.help.close();
    else this.help.openForUrl(this.router.url);
  }

  signOut(): void {
    void this.identity.logout();
  }

  private deepestTitle(): string | null {
    this.url();
    let r = this.route.snapshot;
    while (r.firstChild) r = r.firstChild;
    const t = r.routeConfig?.title;
    return typeof t === 'string' ? t : null;
  }
}

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === 'true';
  } catch {
    return false;
  }
}

function writeFlag(key: string, value: boolean): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    // storage unavailable
  }
}
