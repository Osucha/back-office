import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import type { Observable } from 'rxjs';
import type { BatonHolder } from './baton';

/** One palette entry. Navigation entries give `link`; actions give `run`. */
export interface PaletteItem {
  /** Unique and stable across sessions (used for "recent"): `nav:/runs`, `project:<id>`. */
  id: string;
  title: string;
  subtitle?: string | null;
  /** Extra words the fuzzy scorer matches (ids, keys, synonyms). */
  keywords?: (string | null | undefined)[];
  /** NG-ZORRO outline icon name. */
  icon?: string;
  /** Shown as a baton glyph instead of the icon. */
  holder?: BatonHolder;
  /** Key hint on the right (e.g. `mod+k`). */
  keys?: string;
  link?: string;
  run?: () => void;
  /** Opens a scope instead of closing the palette (e.g. "Start run…" -> pick a workflow). */
  scope?: { id: string; placeholder: string };
}

/**
 * A source of palette entries. Called with the current query (debounced); may answer
 * synchronously, with a promise or an observable. Return a handful of items for an empty query
 * (or none), and matches for a query; the palette scores and merges them.
 */
export interface PaletteProvider {
  id: string;
  /** Group heading ("Go to", "Actions", "Projects", "Runs", ...). */
  group: string;
  /** Lower first. */
  order?: number;
  /** Only query this provider from this many characters on (remote searches). */
  minQuery?: number;
  /** When set, the provider only answers inside that scope (see `PaletteItem.scope`). */
  scope?: string;
  items(query: string): PaletteItem[] | Promise<PaletteItem[]> | Observable<PaletteItem[]>;
}

export interface RecentEntry {
  id: string;
  title: string;
  subtitle?: string | null;
  link?: string;
  icon?: string;
}

const RECENT_KEY = 'backoffice.palette.recent';
const MAX_RECENT = 8;

/**
 * Command palette registry (`Ctrl/⌘ K`, `/`). The shell registers the default providers
 * (destinations, actions, projects, workflows, runs, templates, users); a feature can add its own
 * while it is on screen:
 *
 *   inject(CommandPaletteService).register({
 *     id: 'suites', group: 'Task suites', items: (q) => this.suites().map(...),
 *   }, inject(DestroyRef));
 */
@Injectable({ providedIn: 'root' })
export class CommandPaletteService {
  readonly isOpen = signal(false);
  readonly providers = signal<PaletteProvider[]>([]);
  readonly recent = signal<RecentEntry[]>(readRecent());
  /** Scope to open with (set by `openScope`). */
  readonly initialScope = signal<{ id: string; placeholder: string } | null>(null);

  register(provider: PaletteProvider | PaletteProvider[], destroyRef?: DestroyRef): () => void {
    const list = Array.isArray(provider) ? provider : [provider];
    const ids = new Set(list.map((p) => p.id));
    this.providers.update((ps) => [...ps.filter((p) => !ids.has(p.id)), ...list]);
    const off = () => this.providers.update((ps) => ps.filter((p) => !list.includes(p)));
    destroyRef?.onDestroy(off);
    return off;
  }

  open(): void {
    this.initialScope.set(null);
    this.isOpen.set(true);
  }

  openScope(scope: { id: string; placeholder: string }): void {
    this.initialScope.set(scope);
    this.isOpen.set(true);
  }

  close(): void {
    this.isOpen.set(false);
  }

  toggle(): void {
    if (this.isOpen()) this.close();
    else this.open();
  }

  /** Remembers an executed entry (most recent first). */
  remember(item: PaletteItem): void {
    const entry: RecentEntry = {
      id: item.id,
      title: item.title,
      subtitle: item.subtitle ?? null,
      link: item.link,
      icon: item.icon,
    };
    const next = [entry, ...this.recent().filter((r) => r.id !== item.id)].slice(0, MAX_RECENT);
    this.recent.set(next);
    try {
      localStorage.setItem(RECENT_KEY, JSON.stringify(next));
    } catch {
      // storage unavailable
    }
  }
}

function readRecent(): RecentEntry[] {
  try {
    const raw = localStorage.getItem(RECENT_KEY);
    const parsed = raw ? (JSON.parse(raw) as unknown) : [];
    return Array.isArray(parsed)
      ? parsed
          .filter(
            (e): e is RecentEntry =>
              !!e &&
              typeof e === 'object' &&
              typeof (e as RecentEntry).id === 'string' &&
              typeof (e as RecentEntry).title === 'string',
          )
          .slice(0, MAX_RECENT)
      : [];
  } catch {
    return [];
  }
}
