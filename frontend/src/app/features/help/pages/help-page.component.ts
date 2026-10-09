import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  type OnInit,
  input,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { DomSanitizer, type SafeHtml } from '@angular/platform-browser';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { HelpService } from '../../../core/help/help.service';
import { icu } from '../../../core/i18n/icu';
import {
  allChapters,
  findChapter,
  humanizeAnchor,
  neighbours,
  type ManualChapter,
  type ManualManifest,
} from '../../../core/help/manual';
import {
  buildSearchIndex,
  extractSection,
  highlightParts,
  rewriteManualLinks,
  searchManual,
  searchTerms,
  type SearchSection,
} from '../../../core/help/manual-markdown';
import { trustedMarkdown } from '../../../core/ui/markdown-viewer.component';
import { manualAnchorId } from '../../../core/ui/safe-html';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';

type ChapterState =
  | { kind: 'loading' }
  | { kind: 'missing' }
  | { kind: 'ready'; html: SafeHtml; headings: Record<string, string> };

/**
 * The manual (`/help`, `/help/:chapter#anchor`): table of contents from `manual.json` on the left,
 * the chapter rendered from Markdown on the right with previous / next, and a search across all
 * chapters (fetched on the first search). Missing chapters say "This chapter is being written.".
 */
@Component({
  selector: 'app-help-page',
  imports: [RouterLink, NzIconModule, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page help">
      <nav class="toc" aria-label="Manual contents" i18n-aria-label="@@help.toc.label">
        <div class="search">
          <label for="manual-search" class="sr-only" i18n="@@help.search.label"
            >Search the manual</label
          >
          <nz-icon nzType="search" aria-hidden="true" />
          <input
            id="manual-search"
            type="search"
            placeholder="Search the manual"
            i18n-placeholder="@@help.search.label"
            autocomplete="off"
            [value]="query()"
            (input)="onQuery($any($event.target).value)"
            (keydown.escape)="onQuery('')"
          />
        </div>
        @if (manifest(); as m) {
          @for (g of m.groups; track g.title) {
            <div class="group">
              <div class="group-title">{{ g.title }}</div>
              <ul>
                @for (c of g.chapters; track c.slug) {
                  <li>
                    <a
                      [routerLink]="['/help', c.slug]"
                      [class.active]="c.slug === chapter()"
                      [attr.aria-current]="c.slug === chapter() ? 'page' : null"
                      >{{ c.title }}</a
                    >
                    @if (c.slug === chapter() && c.anchors.length) {
                      <ul class="anchors">
                        @for (a of c.anchors; track a) {
                          <li>
                            <a
                              [routerLink]="['/help', c.slug]"
                              [fragment]="a"
                              [class.active]="a === fragment()"
                              >{{ headingOf(a) }}</a
                            >
                          </li>
                        }
                      </ul>
                    }
                  </li>
                }
              </ul>
            </div>
          }
        } @else if (manifestFailed()) {
          <p class="quiet" i18n="@@help.unavailable">The manual is not available on this server.</p>
        } @else {
          <bo-skeleton
            variant="list"
            [rows]="8"
            label="Loading the contents"
            i18n-label="@@help.toc.loading"
          />
        }
      </nav>

      <main class="content" aria-live="polite">
        @if (query().trim()) {
          <section aria-labelledby="results-title">
            <h1 id="results-title" class="title" i18n="@@help.search.results">Search results</h1>
            @if (searching()) {
              <bo-skeleton
                variant="list"
                [rows]="4"
                label="Searching the manual"
                i18n-label="@@help.search.searching"
              />
            } @else if (!hits().length) {
              <p class="quiet" i18n="@@help.search.none">
                Nothing in the manual matches “{{ query().trim() }}”. Try fewer or other words.
              </p>
            } @else {
              <p class="quiet">{{ hitCount() }}</p>
              <ol class="hits">
                @for (h of hits(); track h.section.chapter + h.section.title + $index) {
                  <li>
                    <a
                      class="hit-title"
                      [routerLink]="['/help', h.section.chapter]"
                      [fragment]="h.section.anchor ?? undefined"
                      (click)="onQuery('')"
                    >
                      <span class="hit-chapter">{{ h.section.chapterTitle }}</span>
                      @if (h.section.title !== h.section.chapterTitle) {
                        <span aria-hidden="true"> › </span>{{ h.section.title }}
                      }
                    </a>
                    <p class="snippet">
                      @for (p of parts(h.snippet); track $index) {
                        @if (p.hit) {
                          <mark>{{ p.text }}</mark>
                        } @else {
                          {{ p.text }}
                        }
                      }
                    </p>
                  </li>
                }
              </ol>
            }
          </section>
        } @else if (!chapter()) {
          <h1 class="title">{{ manifest()?.title ?? manualTitle }}</h1>
          <p class="lead" i18n="@@help.lead">
            How Back Office works, how to connect Jira, CI, LLM providers and your IDE, and how to
            run the platform. Press <kbd>F1</kbd> on any page for help about that page.
          </p>
          @if (manifest(); as m) {
            <div class="overview">
              @for (g of m.groups; track g.title) {
                <section class="card">
                  <h2>{{ g.title }}</h2>
                  <ul>
                    @for (c of g.chapters; track c.slug) {
                      <li>
                        <a [routerLink]="['/help', c.slug]">{{ c.title }}</a>
                      </li>
                    }
                  </ul>
                </section>
              }
            </div>
          }
        } @else {
          @switch (state().kind) {
            @case ('loading') {
              <bo-skeleton
                variant="list"
                [rows]="6"
                label="Loading the chapter"
                i18n-label="@@help.chapter.loading"
              />
            }
            @case ('missing') {
              <h1 class="title">{{ current()?.title ?? chapter() }}</h1>
              <p class="quiet" i18n="@@help.chapter.missing">This chapter is being written.</p>
            }
            @case ('ready') {
              <article
                class="markdown-body manual"
                [innerHTML]="html()"
                (click)="onContentClick($event)"
              ></article>
            }
          }
          @if (current()) {
            <nav class="pager" aria-label="Chapters" i18n-aria-label="@@help.pager.label">
              @if (pager().prev; as p) {
                <a class="prev" [routerLink]="['/help', p.slug]">
                  <span class="dir" i18n="@@help.pager.previous">Previous</span>{{ p.title }}
                </a>
              } @else {
                <span></span>
              }
              @if (pager().next; as n) {
                <a class="next" [routerLink]="['/help', n.slug]">
                  <span class="dir" i18n="@@help.pager.next">Next</span>{{ n.title }}
                </a>
              }
            </nav>
          }
        }
      </main>
    </div>
  `,
  styles: [
    `
      .help {
        display: grid;
        grid-template-columns: 248px minmax(0, 1fr);
        gap: 32px;
        align-items: start;
      }
      @media (max-width: 900px) {
        .help {
          grid-template-columns: 1fr;
        }
        .toc {
          position: static !important;
          max-height: none !important;
        }
      }
      .toc {
        position: sticky;
        top: calc(var(--bo-topbar-h) + 16px);
        max-height: calc(100vh - var(--bo-topbar-h) - 32px);
        overflow: auto;
        font-size: 13px;
        padding-right: 4px;
      }
      .search {
        display: flex;
        align-items: center;
        gap: 8px;
        height: 32px;
        padding: 0 10px;
        margin-bottom: 16px;
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
        color: var(--bo-ink-3);
      }
      .search:focus-within {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
      .search input {
        all: unset;
        flex: 1;
        min-width: 0;
        color: var(--bo-ink);
        font-size: 13px;
      }
      .search input::placeholder {
        color: var(--bo-ink-3);
      }
      .group {
        margin-bottom: 16px;
      }
      .group-title {
        font-size: 11.5px;
        font-weight: 600;
        color: var(--bo-ink-3);
        letter-spacing: 0.02em;
        margin-bottom: 4px;
      }
      ul {
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .toc a {
        display: flex;
        align-items: center;
        min-height: 24px;
        padding: 3px 8px;
        border-radius: 6px;
        color: var(--bo-ink-2);
        text-decoration: none;
      }
      .toc a:hover {
        background: var(--bo-surface-3);
        color: var(--bo-ink);
      }
      .toc a.active {
        color: var(--bo-ink);
        font-weight: 600;
        background: var(--bo-surface-2);
      }
      .toc a:focus-visible,
      .content a:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
      .anchors {
        margin: 2px 0 6px 10px;
        border-left: 1px solid var(--bo-line);
      }
      .anchors a {
        font-size: 12.5px;
        padding: 2px 8px;
      }
      .anchors a.active {
        font-weight: 500;
        color: var(--bo-accent);
        background: none;
      }
      .content {
        min-width: 0;
        max-width: 80ch;
      }
      .title {
        margin: 0 0 8px;
        font-size: var(--bo-fs-title);
        font-weight: 600;
        letter-spacing: var(--bo-tracking-title);
      }
      .lead {
        color: var(--bo-ink-2);
        margin: 0 0 24px;
      }
      kbd {
        font-family: var(--bo-font-mono);
        font-size: 12px;
        padding: 0 4px;
        border: 1px solid var(--bo-line-strong);
        border-radius: 4px;
        background: var(--bo-surface);
      }
      .quiet {
        color: var(--bo-ink-3);
        font-size: 13px;
      }
      .overview {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
        gap: 16px;
      }
      .card {
        padding: 16px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      .card h2 {
        margin: 0 0 8px;
        font-size: 14px;
        font-weight: 600;
      }
      .card li {
        padding: 3px 0;
      }
      .manual :first-child {
        margin-top: 0;
      }
      .hits li {
        padding: 12px 0;
        border-top: 1px solid var(--bo-line);
      }
      .hit-title {
        font-weight: 500;
      }
      .hit-chapter {
        color: var(--bo-ink-2);
      }
      .snippet {
        margin: 4px 0 0;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      mark {
        background: var(--bo-you-soft);
        color: var(--bo-ink);
        padding: 0 1px;
        border-radius: 2px;
      }
      .pager {
        display: flex;
        justify-content: space-between;
        gap: 16px;
        margin-top: 40px;
        padding-top: 16px;
        border-top: 1px solid var(--bo-line);
      }
      .pager a {
        display: flex;
        flex-direction: column;
        gap: 2px;
        padding: 8px 12px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
        text-decoration: none;
        font-weight: 500;
        max-width: 48%;
      }
      .pager a:hover {
        border-color: var(--bo-line-strong);
      }
      .pager .next {
        text-align: right;
        margin-left: auto;
      }
      .dir {
        font-size: 11.5px;
        font-weight: 400;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class HelpPageComponent implements OnInit {
  readonly manualTitle = $localize`:@@help.title:Manual`;
  readonly hitCount = computed(() =>
    icu($localize`:@@help.search.count:{count, plural, one {# section} other {# sections}}`, {
      count: this.hits().length,
    }),
  );
  private readonly help = inject(HelpService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);
  private readonly doc = inject(DOCUMENT);
  private readonly crumbs = inject(BreadcrumbService);

  /** Route parameter `:chapter` (absent on `/help`). */
  readonly chapter = input<string | undefined>();
  readonly fragment = toSignal(inject(ActivatedRoute).fragment, { initialValue: null });

  readonly manifest = signal<ManualManifest | null>(null);
  readonly manifestFailed = signal(false);
  readonly state = signal<ChapterState>({ kind: 'loading' });
  readonly query = signal('');
  readonly searching = signal(false);
  private readonly index = signal<SearchSection[] | null>(null);
  private indexLoading: Promise<void> | null = null;

  readonly current = computed<ManualChapter | null>(() =>
    findChapter(this.manifest(), this.chapter()),
  );
  readonly pager = computed(() => neighbours(this.manifest(), this.chapter()));
  readonly html = computed(() => {
    const s = this.state();
    return s.kind === 'ready' ? s.html : '';
  });
  readonly hits = computed(() => searchManual(this.index() ?? [], this.query()));
  private readonly terms = computed(() => searchTerms(this.query()));

  constructor() {
    effect(() => {
      const slug = this.chapter();
      untracked(() => (slug ? void this.loadChapter(slug) : this.state.set({ kind: 'loading' })));
    });
    effect(() => {
      const title = this.current()?.title;
      untracked(() =>
        this.crumbs.set(
          title
            ? [{ label: this.manualTitle, link: '/help' }, { label: title }]
            : [{ label: this.manualTitle }],
        ),
      );
    });
    // Scroll to `#anchor` once the chapter is on screen (and whenever the fragment changes).
    effect(() => {
      const frag = this.fragment();
      const ready = this.state().kind === 'ready';
      if (!frag || !ready || this.query().trim()) return;
      // Sanitised ids carry the `user-content-` prefix (DOM clobbering protection, safe-html.ts).
      setTimeout(() =>
        this.doc.getElementById(manualAnchorId(frag))?.scrollIntoView({ block: 'start' }),
      );
    });
  }

  ngOnInit(): void {
    void this.help.manifest().then((m) => {
      this.manifest.set(m);
      this.manifestFailed.set(!m);
    });
  }

  headingOf(anchor: string): string {
    const s = this.state();
    return (s.kind === 'ready' && s.headings[anchor]) || humanizeAnchor(anchor);
  }

  parts(text: string) {
    return highlightParts(text, this.terms());
  }

  onQuery(value: string): void {
    this.query.set(value);
    if (value.trim().length >= 2) void this.ensureIndex();
  }

  /** In-app links in the chapter navigate in the SPA. */
  onContentClick(e: MouseEvent): void {
    const a = (e.target as HTMLElement | null)?.closest?.('a');
    const href = a?.getAttribute('href');
    if (!href || !href.startsWith('/') || href.startsWith('/manual/')) return;
    if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    void this.router.navigateByUrl(href);
  }

  private async loadChapter(slug: string): Promise<void> {
    this.state.set({ kind: 'loading' });
    const markdown = await this.help.chapter(slug);
    if (this.chapter() !== slug) return;
    if (!markdown) return this.state.set({ kind: 'missing' });
    const manifest = this.manifest() ?? (await this.help.manifest());
    const headings: Record<string, string> = {};
    for (const a of findChapter(manifest, slug)?.anchors ?? []) {
      const s = extractSection(markdown, a);
      if (s) headings[a] = s.title;
    }
    const html = trustedMarkdown(this.sanitizer, rewriteManualLinks(markdown, slug), 'manual');
    this.state.set({ kind: 'ready', html, headings });
  }

  /** Fetches every chapter once (first search) and builds the search index. */
  private ensureIndex(): Promise<void> {
    if (this.index()) return Promise.resolve();
    this.indexLoading ??= (async () => {
      this.searching.set(true);
      const manifest = this.manifest() ?? (await this.help.manifest());
      const chapters = allChapters(manifest);
      const texts = await Promise.all(chapters.map((c) => this.help.chapter(c.slug)));
      this.index.set(
        buildSearchIndex(
          chapters
            .map((c, i) => ({ slug: c.slug, title: c.title, markdown: texts[i] ?? '' }))
            .filter((c) => c.markdown),
        ),
      );
      this.searching.set(false);
      this.indexLoading = null;
    })();
    return this.indexLoading;
  }
}
