import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { isObservable, firstValueFrom, type Observable } from 'rxjs';
import {
  CommandPaletteService,
  type PaletteItem,
  type PaletteProvider,
  type RecentEntry,
} from './command-palette.service';
import { fuzzyFilter, highlightParts } from './fuzzy';
import { GlyphComponent } from './glyph.component';
import { KbdComponent } from './kbd.component';

interface Option {
  domId: string;
  item: PaletteItem;
  parts: Array<{ text: string; hit: boolean }>;
  recent: boolean;
}

interface Group {
  id: string;
  label: string;
  options: Option[];
}

const PER_GROUP_EMPTY = 6;
const PER_GROUP_QUERY = 8;
const RECENT_BOOST = 25;

/**
 * The command palette UI (rendered once by the shell): fuzzy search over every registered
 * provider, recent entries first, keyboard-only operable, ARIA combobox + listbox.
 */
@Component({
  selector: 'bo-command-palette',
  imports: [NzIconModule, GlyphComponent, KbdComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (palette.isOpen()) {
      <div class="scrim" (click)="close()" aria-hidden="true"></div>
      <div
        class="dialog"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
        i18n-aria-label="@@core.palette.dialog"
      >
        <div class="input-row">
          @if (scope(); as s) {
            <button
              type="button"
              class="scope"
              (click)="leaveScope()"
              aria-label="Leave this step"
              i18n-aria-label="@@core.palette.leaveStep"
            >
              <nz-icon nzType="arrow-left" />
            </button>
          } @else {
            <nz-icon nzType="search" class="search-icon" aria-hidden="true" />
          }
          <input
            #input
            type="text"
            role="combobox"
            aria-expanded="true"
            aria-controls="bo-palette-list"
            aria-autocomplete="list"
            [attr.aria-activedescendant]="activeOption()?.domId ?? null"
            [placeholder]="scope()?.placeholder ?? searchPlaceholder"
            [value]="query()"
            (input)="setQuery($any($event.target).value)"
            (keydown)="onKeydown($event)"
            spellcheck="false"
            autocomplete="off"
          />
          @if (pending()) {
            <span class="pending" aria-hidden="true"></span>
          }
          <bo-kbd keys="Escape" />
        </div>
        <div
          class="results"
          id="bo-palette-list"
          role="listbox"
          aria-label="Results"
          i18n-aria-label="@@core.palette.results"
          #list
        >
          @for (g of groups(); track g.id) {
            <div role="group" [attr.aria-labelledby]="'bo-pg-' + g.id">
              <div class="group-head" [id]="'bo-pg-' + g.id" role="presentation">{{ g.label }}</div>
              @for (o of g.options; track o.domId) {
                <div
                  class="option"
                  role="option"
                  [id]="o.domId"
                  [attr.aria-selected]="activeOption()?.domId === o.domId"
                  [class.active]="activeOption()?.domId === o.domId"
                  (mousemove)="hover(o)"
                  (click)="execute(o.item)"
                >
                  <span class="icon" aria-hidden="true">
                    @if (o.item.holder) {
                      <bo-glyph [holder]="o.item.holder" [size]="13" />
                    } @else {
                      <nz-icon [nzType]="o.item.icon || (o.recent ? 'history' : 'arrow-right')" />
                    }
                  </span>
                  <span class="text">
                    <span class="title">
                      @for (p of o.parts; track $index) {
                        @if (p.hit) {
                          <mark>{{ p.text }}</mark>
                        } @else {
                          <span>{{ p.text }}</span>
                        }
                      }
                    </span>
                    @if (o.item.subtitle) {
                      <span class="subtitle">{{ o.item.subtitle }}</span>
                    }
                  </span>
                  @if (o.item.keys) {
                    <bo-kbd [keys]="o.item.keys" />
                  } @else if (o.item.scope) {
                    <nz-icon nzType="right" class="chev" aria-hidden="true" />
                  }
                </div>
              }
            </div>
          } @empty {
            <div class="none" role="presentation">
              @if (pending()) {
                <ng-container i18n="@@core.palette.searching">Searching…</ng-container>
              } @else {
                <ng-container i18n="@@core.palette.noMatches"
                  >No matches for “{{ query() }}”. Try a project key, an issue key or a run
                  id.</ng-container
                >
              }
            </div>
          }
        </div>
        <div class="foot" aria-hidden="true">
          <span
            ><bo-kbd keys="ArrowUp" /><bo-kbd keys="ArrowDown" />
            <ng-container i18n="@@core.palette.footMove">move</ng-container></span
          >
          <span
            ><bo-kbd keys="Enter" />
            <ng-container i18n="@@core.palette.footOpen">open</ng-container></span
          >
          <span
            ><bo-kbd keys="Escape" />
            <ng-container i18n="@@core.palette.footClose">close</ng-container></span
          >
        </div>
      </div>
    }
  `,
  styles: [
    `
      .scrim {
        position: fixed;
        inset: 0;
        background: color-mix(in srgb, var(--bo-scrim) 30%, transparent);
        z-index: 1200;
      }
      .dialog {
        position: fixed;
        top: 12vh;
        left: 50%;
        transform: translateX(-50%);
        width: min(640px, calc(100vw - 32px));
        display: flex;
        flex-direction: column;
        max-height: 70vh;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: 12px;
        box-shadow: var(--bo-shadow-overlay);
        z-index: 1201;
        overflow: hidden;
        animation: bo-pal-in 140ms var(--bo-ease);
      }
      @keyframes bo-pal-in {
        from {
          opacity: 0;
          transform: translate(-50%, -6px);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .dialog {
          animation: none;
        }
      }
      .input-row {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 12px 14px;
        border-bottom: 1px solid var(--bo-line);
      }
      .search-icon {
        color: var(--bo-ink-3);
      }
      .scope {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        padding: 4px;
        border-radius: 4px;
        color: var(--bo-ink-2);
      }
      .scope:focus-visible {
        outline: 2px solid var(--bo-accent);
      }
      input {
        flex: 1;
        border: 0;
        outline: none;
        background: transparent;
        color: var(--bo-ink);
        font: inherit;
        font-size: 15px;
        min-width: 0;
      }
      input::placeholder {
        color: var(--bo-ink-3);
      }
      .pending {
        width: 12px;
        height: 12px;
        border-radius: 50%;
        border: 2px solid var(--bo-line-strong);
        border-top-color: var(--bo-accent);
        animation: bo-spin 0.8s linear infinite;
      }
      @keyframes bo-spin {
        to {
          transform: rotate(360deg);
        }
      }
      .results {
        overflow: auto;
        padding: 6px;
        flex: 1;
      }
      .group-head {
        font-size: 11px;
        font-weight: 600;
        color: var(--bo-ink-3);
        padding: 10px 10px 4px;
        text-transform: uppercase;
        letter-spacing: 0.04em;
      }
      .option {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 7px 10px;
        border-radius: 8px;
        cursor: pointer;
        color: var(--bo-ink);
      }
      .option.active {
        background: var(--bo-accent-soft);
      }
      .icon {
        display: inline-flex;
        width: 18px;
        justify-content: center;
        color: var(--bo-ink-3);
        flex: none;
      }
      .option.active .icon {
        color: var(--bo-accent);
      }
      .text {
        display: flex;
        flex-direction: column;
        min-width: 0;
        flex: 1;
      }
      .title {
        font-size: 13.5px;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      mark {
        background: none;
        color: inherit;
        font-weight: 700;
        text-decoration: underline;
        text-decoration-color: var(--bo-accent);
        text-underline-offset: 2px;
        padding: 0;
      }
      .subtitle {
        font-size: 12px;
        color: var(--bo-ink-3);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .chev {
        color: var(--bo-ink-3);
        font-size: 11px;
      }
      .none {
        padding: 24px 12px;
        color: var(--bo-ink-3);
        font-size: 13px;
      }
      .foot {
        display: flex;
        gap: 16px;
        padding: 8px 14px;
        border-top: 1px solid var(--bo-line);
        font-size: 11px;
        color: var(--bo-ink-3);
        background: var(--bo-surface-2);
      }
      .foot span {
        display: inline-flex;
        align-items: center;
        gap: 4px;
      }
    `,
  ],
})
export class CommandPaletteComponent {
  readonly palette = inject(CommandPaletteService);
  readonly searchPlaceholder = $localize`:@@core.palette.placeholder:Search or jump to…`;
  private readonly router = inject(Router);

  readonly query = signal('');
  readonly scope = signal<{ id: string; placeholder: string } | null>(null);
  private readonly responses = signal<Map<string, PaletteItem[]>>(new Map());
  readonly pending = signal(false);
  private readonly activeId = signal<string | null>(null);
  private readonly input = viewChild<ElementRef<HTMLInputElement>>('input');
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');
  private timer: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;
  private returnFocus: HTMLElement | null = null;

  readonly groups = computed<Group[]>(() => {
    const q = this.query().trim();
    const scope = this.scope();
    const recentIds = new Set(this.palette.recent().map((r) => r.id));
    const responses = this.responses();
    const providers = this.activeProviders();
    const groups: Group[] = [];
    const used = new Set<string>();
    let n = 0;
    const option = (item: PaletteItem, indices: number[], recent: boolean): Option => ({
      domId: `bo-po-${n++}`,
      item,
      parts: highlightParts(item.title, indices),
      recent,
    });

    // Recent first (empty query, top level).
    if (!q && !scope) {
      const recent = recentItems(this.palette.recent(), responses);
      if (recent.length) {
        groups.push({
          id: 'recent',
          label: $localize`:@@core.palette.recent:Recent`,
          options: recent.map((it) => option(it, [], true)),
        });
        recent.forEach((r) => used.add(r.id));
      }
    }

    for (const p of providers) {
      const items = (responses.get(p.id) ?? []).filter((it) => !used.has(it.id));
      const limited = rankItems(items, q, recentIds);
      if (!limited.length) continue;
      limited.forEach((m) => used.add(m.item.id));
      groups.push({
        id: p.id,
        label: p.group,
        options: limited.map((m) => option(m.item, m.match.indices, recentIds.has(m.item.id))),
      });
    }
    // With a query, the group holding the best match comes first.
    if (q && groups.length > 1) {
      const best = (g: Group) => (g.options[0] ? scoreOf(g.options[0].item, q) : 0);
      groups.sort((a, b) => best(b) - best(a));
    }
    return groups;
  });

  readonly options = computed(() => this.groups().flatMap((g) => g.options));
  readonly activeOption = computed<Option | null>(() => {
    const opts = this.options();
    return opts.find((o) => o.item.id === this.activeId()) ?? opts[0] ?? null;
  });

  private readonly activeProviders = computed(() => {
    const scope = this.scope()?.id ?? null;
    const q = this.query().trim();
    return [...this.palette.providers()]
      .filter((p) => (p.scope ?? null) === scope && q.length >= (p.minQuery ?? 0))
      .sort((a, b) => (a.order ?? 50) - (b.order ?? 50));
  });

  constructor() {
    effect(() => {
      if (!this.palette.isOpen()) return;
      untracked(() => {
        this.returnFocus = document.activeElement as HTMLElement | null;
        this.query.set('');
        this.scope.set(this.palette.initialScope());
        this.activeId.set(null);
        this.responses.set(new Map());
        this.fetch();
      });
    });
    effect(() => {
      const el = this.input()?.nativeElement;
      if (el) queueMicrotask(() => el.focus());
    });
  }

  setQuery(q: string): void {
    this.query.set(q);
    this.activeId.set(null);
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => this.fetch(), 90);
  }

  hover(o: Option): void {
    if (this.activeId() !== o.item.id) this.activeId.set(o.item.id);
  }

  onKeydown(e: KeyboardEvent): void {
    const opts = this.options();
    const idx = opts.findIndex((o) => o.domId === this.activeOption()?.domId);
    switch (e.key) {
      case 'ArrowDown':
        e.preventDefault();
        this.activate(opts[(idx + 1) % Math.max(1, opts.length)]);
        break;
      case 'ArrowUp':
        e.preventDefault();
        this.activate(opts[(idx - 1 + opts.length) % Math.max(1, opts.length)]);
        break;
      case 'Home':
        if (e.ctrlKey || !this.query()) {
          e.preventDefault();
          this.activate(opts[0]);
        }
        break;
      case 'End':
        if (e.ctrlKey || !this.query()) {
          e.preventDefault();
          this.activate(opts[opts.length - 1]);
        }
        break;
      case 'Enter': {
        e.preventDefault();
        const o = this.activeOption();
        if (o) this.execute(o.item);
        break;
      }
      case 'Backspace':
        if (!this.query() && this.scope()) {
          e.preventDefault();
          this.leaveScope();
        }
        break;
      case 'Escape':
        e.preventDefault();
        e.stopPropagation();
        if (this.scope() && !this.palette.initialScope()) this.leaveScope();
        else this.close();
        break;
    }
  }

  execute(item: PaletteItem): void {
    this.palette.remember(item);
    if (item.scope) {
      this.scope.set(item.scope);
      this.setQuery('');
      this.fetch();
      this.input()?.nativeElement.focus();
      return;
    }
    this.close(false);
    if (item.link) void this.router.navigateByUrl(item.link);
    else item.run?.();
  }

  leaveScope(): void {
    this.scope.set(null);
    this.setQuery('');
    this.fetch();
    this.input()?.nativeElement.focus();
  }

  close(restoreFocus = true): void {
    this.palette.close();
    const back = this.returnFocus;
    this.returnFocus = null;
    if (restoreFocus && back) queueMicrotask(() => back.focus?.());
  }

  private activate(o: Option | undefined): void {
    if (!o) return;
    this.activeId.set(o.item.id);
    queueMicrotask(() =>
      this.list()?.nativeElement.querySelector(`#${o.domId}`)?.scrollIntoView({ block: 'nearest' }),
    );
  }

  private fetch(): void {
    const gen = ++this.generation;
    const q = this.query().trim();
    const providers = this.activeProviders();
    let open = providers.length;
    this.pending.set(open > 0);
    const done = () => {
      if (gen === this.generation && --open <= 0) this.pending.set(false);
    };
    for (const p of providers) {
      resolveItems(p, q)
        .then((items) => {
          if (gen !== this.generation) return;
          this.responses.update((m) => new Map(m).set(p.id, items));
        })
        .catch(() => undefined)
        .finally(done);
    }
  }
}

function resolveItems(p: PaletteProvider, q: string): Promise<PaletteItem[]> {
  try {
    const r = p.items(q);
    if (isObservable(r))
      return firstValueFrom(r as Observable<PaletteItem[]>, { defaultValue: [] });
    return Promise.resolve(r);
  } catch {
    return Promise.resolve([]);
  }
}

/**
 * The last five used entries: the provider's current item when loaded, otherwise the remembered
 * entry when it has a link (an action without a link cannot be replayed from memory).
 */
function recentItems(
  recent: readonly RecentEntry[],
  responses: ReadonlyMap<string, PaletteItem[]>,
): PaletteItem[] {
  const all = new Map<string, PaletteItem>();
  for (const items of responses.values()) for (const it of items) all.set(it.id, it);
  return recent
    .map((r) => all.get(r.id) ?? (r.link ? ({ ...r, link: r.link } as PaletteItem) : null))
    .filter((x): x is PaletteItem => !!x)
    .slice(0, 5);
}

/** One group's matches, best first with a boost for recent entries; capped per group. */
function rankItems(items: readonly PaletteItem[], q: string, recentIds: ReadonlySet<string>) {
  return fuzzyFilter(items, q, (it) => [it.title, it.subtitle, ...(it.keywords ?? [])], 50)
    .map((m) => ({
      ...m,
      score: m.match.score + (recentIds.has(m.item.id) ? RECENT_BOOST : 0),
    }))
    .sort((a, b) => (q ? b.score - a.score : 0))
    .slice(0, q ? PER_GROUP_QUERY : PER_GROUP_EMPTY);
}

function scoreOf(item: PaletteItem, q: string): number {
  return (
    fuzzyFilter([item], q, (it) => [it.title, it.subtitle, ...(it.keywords ?? [])])[0]?.match
      .score ?? 0
  );
}
