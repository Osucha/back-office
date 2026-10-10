import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { filter, map } from 'rxjs';
import { HoverPreloadingStrategy } from '../core/routing/hover-preloading';
import { ProjectsStore, monogram } from '../core/state/projects.store';
import { navTarget, type NavItem } from './navigation';
import { LogoComponent } from '../core/ui/relay/logo.component';

const MAX_TILES = 6;

/**
 * The rail: 56 px, expands to 232 px on hover/focus or when pinned. Project switcher on top
 * (monogram tiles, current one highlighted, "All projects"), main navigation, then
 * Administration and Settings at the bottom. Hover or focus preloads the target route.
 */
@Component({
  selector: 'app-rail',
  imports: [LogoComponent, NgTemplateOutlet, RouterLink, RouterLinkActive, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.expanded]': 'expanded()',
    '[class.pinned]': 'pinned()',
    '(mouseenter)': 'hover.set(true)',
    '(mouseleave)': 'hover.set(false)',
    '(focusin)': 'onFocusIn($event)',
    '(focusout)': 'onFocusOut($event)',
  },
  template: `
    <div class="inner">
      <a
        class="brand"
        routerLink="/home"
        aria-label="Back Office home"
        i18n-aria-label="@@shell.rail.brand"
        (mouseenter)="intent('/home')"
      >
        <bo-logo class="mark" [size]="28" />
        <span class="label brand-name">Back Office</span>
      </a>

      <div
        class="projects"
        role="group"
        aria-label="Projects"
        i18n-aria-label="@@shell.rail.projects"
      >
        @for (p of tiles(); track p.id) {
          <a
            class="tile-row"
            [routerLink]="['/projects', p.id]"
            [class.current]="p.id === store.currentId()"
            [attr.aria-current]="p.id === store.currentId() ? 'true' : null"
            [attr.aria-label]="tileLabel(p.key, p.name)"
            [attr.title]="expanded() ? null : p.key + ' · ' + p.name"
            (click)="store.setCurrent(p.id)"
            (mouseenter)="intent('/projects')"
          >
            <span class="tile" aria-hidden="true">{{ mono(p.key) }}</span>
            <span class="label">
              <span class="p-key">{{ p.key }}</span>
              <span class="p-name">{{ p.name }}</span>
            </span>
          </a>
        }
        <a
          class="tile-row all"
          routerLink="/projects"
          (click)="store.setCurrent(null)"
          [class.current]="!store.currentId()"
          aria-label="All projects"
          i18n-aria-label="@@shell.rail.allProjects"
          [attr.title]="expanded() ? null : text.allProjects"
          (mouseenter)="intent('/projects')"
        >
          <span class="tile" aria-hidden="true"><nz-icon nzType="appstore" /></span>
          <span class="label" i18n="@@shell.rail.allProjects">All projects</span>
        </a>
      </div>

      <nav class="nav" aria-label="Main" i18n-aria-label="@@shell.rail.main">
        <ul>
          @for (item of main(); track item.path) {
            <ng-container *ngTemplateOutlet="entry; context: { $implicit: item }" />
          }
        </ul>
        <ul class="bottom">
          @for (item of bottom(); track item.path) {
            <ng-container *ngTemplateOutlet="entry; context: { $implicit: item }" />
          }
          <li>
            <button
              type="button"
              class="item pin"
              (click)="pinnedChange.emit(!pinned())"
              [attr.aria-pressed]="pinned()"
              [attr.aria-label]="pinned() ? text.unpinNav : text.keepNavOpen"
            >
              <nz-icon
                [nzType]="pinned() ? 'pushpin' : 'pushpin'"
                [nzTheme]="pinned() ? 'fill' : 'outline'"
                class="icon"
              />
              <span class="label">{{ pinned() ? text.unpin : text.keepOpen }}</span>
            </button>
          </li>
        </ul>
      </nav>
    </div>

    <ng-template #entry let-item>
      <li>
        <a
          class="item"
          [routerLink]="target(item)"
          [class.active]="isSection(item)"
          [attr.aria-current]="isSection(item) ? 'page' : null"
          [attr.aria-label]="itemLabel(item)"
          [attr.title]="expanded() ? null : item.label"
          (mouseenter)="intent(target(item))"
          (focus)="intent(target(item))"
        >
          <nz-icon [nzType]="item.icon" class="icon" />
          <span class="label">{{ item.label }}</span>
          @if (item.path === '/inbox' && turnCount()) {
            <span class="count" aria-hidden="true">{{
              turnCount() > 99 ? '99+' : turnCount()
            }}</span>
          }
        </a>
        @if (item.children && expanded() && isSection(item)) {
          <ul class="children">
            @for (c of item.children; track c.path) {
              <li>
                <a
                  class="child"
                  [routerLink]="c.path"
                  routerLinkActive="active"
                  ariaCurrentWhenActive="page"
                  (mouseenter)="intent(c.path)"
                  >{{ c.label }}</a
                >
              </li>
            }
          </ul>
        }
      </li>
    </ng-template>
  `,
  styles: [
    `
      :host {
        position: fixed;
        inset: 0 auto 0 0;
        width: var(--bo-rail-w);
        z-index: 1000;
        display: block;
      }
      .inner {
        height: 100%;
        width: var(--bo-rail-w);
        display: flex;
        flex-direction: column;
        background: var(--bo-surface);
        border-right: 1px solid var(--bo-line);
        overflow: hidden;
        transition:
          width var(--bo-dur-panel) var(--bo-ease),
          box-shadow var(--bo-dur-panel) var(--bo-ease);
      }
      :host(.expanded) .inner {
        width: var(--bo-rail-w-open);
      }
      :host(.expanded:not(.pinned)) .inner {
        box-shadow: var(--bo-shadow-rail);
      }
      @media (prefers-reduced-motion: reduce) {
        .inner {
          transition: none;
        }
      }
      .label {
        opacity: 0;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        transition: opacity var(--bo-dur-fast) var(--bo-ease);
        min-width: 0;
      }
      :host(.expanded) .label {
        opacity: 1;
      }
      .brand {
        display: flex;
        align-items: center;
        gap: 12px;
        height: var(--bo-topbar-h);
        padding: 0 14px;
        text-decoration: none;
        color: var(--bo-ink);
        flex: none;
      }
      .mark {
        width: 28px;
        height: 28px;
        flex: none;
      }
      .brand-name {
        font-weight: 600;
        font-size: 14px;
        letter-spacing: -0.01em;
      }
      .projects {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding: 8px 0 10px;
        margin: 0 10px;
        border-bottom: 1px solid var(--bo-line);
        flex: none;
      }
      .tile-row {
        display: flex;
        align-items: center;
        gap: 10px;
        height: 36px;
        padding: 0 4px;
        border-radius: 8px;
        color: var(--bo-ink-2);
        text-decoration: none;
      }
      .tile-row:hover {
        background: var(--bo-surface-2);
        color: var(--bo-ink);
      }
      .tile {
        flex: none;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 28px;
        height: 28px;
        border-radius: 7px;
        font-family: var(--bo-font-mono);
        font-size: 11px;
        font-weight: 600;
        letter-spacing: 0.02em;
        color: var(--bo-ink-2);
        background: var(--bo-surface-3);
        box-shadow: inset 0 0 0 1px var(--bo-line);
      }
      .tile-row.current .tile {
        background: var(--bo-ink);
        color: var(--bo-on-ink);
        box-shadow: none;
      }
      .tile-row.all .tile {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .tile-row.all.current .tile {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
      }
      .tile-row .label {
        display: flex;
        flex-direction: column;
        line-height: 1.2;
      }
      .p-key {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .p-name {
        font-size: 13px;
        color: var(--bo-ink);
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .nav {
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
        overflow-y: auto;
        overflow-x: hidden;
        padding: 8px 0;
      }
      ul {
        list-style: none;
        margin: 0;
        padding: 0 10px;
      }
      .bottom {
        margin-top: auto;
        padding-top: 8px;
        border-top: 1px solid var(--bo-line);
        margin-left: 10px;
        margin-right: 10px;
        padding-left: 0;
        padding-right: 0;
      }
      .item {
        position: relative;
        display: flex;
        align-items: center;
        gap: 12px;
        height: 36px;
        padding: 0 10px;
        margin: 1px 0;
        border-radius: 8px;
        color: var(--bo-ink-2);
        text-decoration: none;
        font-size: 13.5px;
        width: 100%;
        box-sizing: border-box;
        background: none;
        border: 0;
        cursor: pointer;
        font-family: inherit;
        text-align: left;
      }
      .item .icon {
        font-size: 16px;
        flex: none;
      }
      .item:hover {
        background: var(--bo-surface-2);
        color: var(--bo-ink);
      }
      .item.active {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
        font-weight: 500;
      }
      .item.active::before {
        content: '';
        position: absolute;
        left: -10px;
        top: 8px;
        bottom: 8px;
        width: 3px;
        border-radius: 0 3px 3px 0;
        background: var(--bo-ink);
      }
      .count {
        position: absolute;
        left: 24px;
        top: 4px;
        min-width: 16px;
        height: 16px;
        padding: 0 4px;
        border-radius: 999px;
        background: var(--bo-you);
        color: #1c1e21;
        font-size: 10px;
        font-weight: 700;
        line-height: 16px;
        text-align: center;
        font-variant-numeric: tabular-nums;
      }
      :host(.expanded) .count {
        position: static;
        margin-left: auto;
      }
      .children {
        padding: 2px 0 6px 38px;
      }
      .child {
        display: block;
        padding: 5px 8px;
        border-radius: 6px;
        font-size: 13px;
        color: var(--bo-ink-3);
        text-decoration: none;
        white-space: nowrap;
      }
      .child:hover {
        color: var(--bo-ink);
        background: var(--bo-surface-2);
      }
      .child.active {
        color: var(--bo-ink);
        font-weight: 500;
      }
      a:focus-visible,
      button:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
    `,
  ],
})
export class RailComponent {
  protected readonly text = {
    allProjects: $localize`:@@shell.rail.allProjects:All projects`,
    unpinNav: $localize`:@@shell.rail.unpinNav:Unpin navigation`,
    keepNavOpen: $localize`:@@shell.rail.keepNavOpen:Keep navigation open`,
    unpin: $localize`:@@shell.rail.unpin:Unpin`,
    keepOpen: $localize`:@@shell.rail.keepOpen:Keep open`,
  };
  readonly store = inject(ProjectsStore);
  private readonly preloader = inject(HoverPreloadingStrategy);
  private readonly router = inject(Router);

  readonly main = input<NavItem[]>([]);
  readonly bottom = input<NavItem[]>([]);
  readonly pinned = input(false);
  readonly turnCount = input(0);
  readonly pinnedChange = output<boolean>();

  readonly hover = signal(false);
  readonly focusWithin = signal(false);
  readonly expanded = computed(() => this.pinned() || this.hover() || this.focusWithin());

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  readonly tiles = computed(() => {
    const list = [...this.store.projects()].sort((a, b) => a.key.localeCompare(b.key));
    const cur = this.store.current();
    const ordered = cur ? [cur, ...list.filter((p) => p.id !== cur.id)] : list;
    return ordered.slice(0, MAX_TILES);
  });

  constructor() {
    this.store.ensure();
  }

  mono = monogram;
  target = navTarget;

  isSection(item: NavItem): boolean {
    const url = this.url().split(/[?#]/)[0];
    const base = item.path;
    if (url === base || url.startsWith(base + '/')) return true;
    // Workflows and runs belong to their own sections; project pages to Projects.
    return false;
  }

  /** "Your turn, 3 waiting" for the inbox entry while gates wait. */
  itemLabel(item: NavItem): string {
    const n = this.turnCount();
    if (item.path !== '/inbox' || !n) return item.label;
    return $localize`:@@shell.rail.waiting:${item.label}:label:, ${n}:count: waiting`;
  }

  tileLabel(key: string, name: string): string {
    return $localize`:@@shell.rail.projectTile:Project ${key}:key:, ${name}:name:`;
  }

  intent(url: string): void {
    this.preloader.intent(url);
  }

  /** Keyboard focus expands the rail; a mouse click on a link does not keep it open. */
  onFocusIn(e: FocusEvent): void {
    const t = e.target as HTMLElement | null;
    if (t?.matches?.(':focus-visible')) this.focusWithin.set(true);
  }

  onFocusOut(e: FocusEvent): void {
    const next = e.relatedTarget as Node | null;
    const host = e.currentTarget as HTMLElement;
    if (!next || !host.contains(next)) this.focusWithin.set(false);
  }
}
