import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { DomSanitizer, type SafeHtml } from '@angular/platform-browser';
import { Router } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { trustedMarkdown } from '../ui/markdown-viewer.component';
import { SidePanelComponent } from '../ui/relay/side-panel.component';
import { SkeletonComponent } from '../ui/relay/skeleton.component';
import type { HelpTopic } from './help-map';
import { HelpService } from './help.service';
import { findChapter } from './manual';
import { chapterIntro, extractSection, rewriteManualLinks } from './manual-markdown';

type State =
  | { kind: 'loading' }
  | { kind: 'missing'; what: 'chapter' | 'section' }
  | { kind: 'ready'; title: string; html: SafeHtml };

/**
 * The help panel: ONE manual section (from its anchor heading to the next heading of the same or a
 * higher level) in a side panel, with "Open in manual". Rendered by the shell inside `@defer`, so
 * marked and DOMPurify load only when help is first opened. Links inside go to the manual page.
 */
@Component({
  selector: 'bo-help-panel',
  imports: [NzButtonModule, NzIconModule, SidePanelComponent, SkeletonComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="!!topic()"
      [title]="title()"
      [subtitle]="subtitle()"
      [defaultWidth]="520"
      [minWidth]="400"
      storageKey="backoffice.helpPanelWidth"
      [zIndex]="1001"
      bodyLabel="Help"
      i18n-bodyLabel="@@core.help.panel.bodyLabel"
      (closed)="help.close()"
    >
      @switch (state().kind) {
        @case ('loading') {
          <bo-skeleton
            variant="list"
            [rows]="4"
            label="Loading help"
            i18n-label="@@core.help.panel.loading"
          />
        }
        @case ('missing') {
          <p class="missing">
            @if (missingWhat() === 'chapter') {
              <ng-container i18n="@@core.help.chapterMissing"
                >This chapter is being written.</ng-container
              >
            } @else {
              <ng-container i18n="@@core.help.sectionMissing"
                >This section is being written.</ng-container
              >
            }
          </p>
        }
        @case ('ready') {
          <div class="markdown-body help-body" [innerHTML]="html()" (click)="onClick($event)"></div>
        }
      }
      <div boPanelFooter class="foot">
        @if (topic(); as t) {
          <button nz-button type="button" (click)="help.openInManual(t)">
            <nz-icon nzType="read" /> <span i18n="@@core.help.openInManual">Open in manual</span>
          </button>
        }
      </div>
    </bo-side-panel>
  `,
  styles: [
    `
      .missing {
        margin: 0;
        color: var(--bo-ink-3);
        font-size: 13px;
      }
      .help-body {
        font-size: 13.5px;
      }
      .help-body :first-child {
        margin-top: 0;
      }
      .foot {
        display: flex;
        justify-content: flex-end;
      }
    `,
  ],
})
export class HelpPanelComponent {
  readonly help = inject(HelpService);
  private readonly router = inject(Router);
  private readonly sanitizer = inject(DomSanitizer);

  readonly topic = this.help.panel;
  readonly state = signal<State>({ kind: 'loading' });
  private readonly chapterTitle = signal<string | null>(null);

  readonly title = computed(() => {
    const s = this.state();
    return s.kind === 'ready'
      ? s.title
      : (this.topic()?.label ?? $localize`:@@core.help.panel.title:Help`);
  });
  readonly subtitle = computed(() =>
    this.chapterTitle()
      ? $localize`:@@core.help.panel.subtitleChapter:Manual · ${this.chapterTitle()}:chapter:`
      : $localize`:@@core.help.panel.subtitle:Manual`,
  );
  readonly html = computed(() => {
    const s = this.state();
    return s.kind === 'ready' ? s.html : '';
  });
  readonly missingWhat = computed(() => {
    const s = this.state();
    return s.kind === 'missing' ? s.what : 'chapter';
  });

  constructor() {
    effect(() => {
      const t = this.topic();
      if (t) untracked(() => void this.load(t));
    });
  }

  private async load(t: HelpTopic): Promise<void> {
    this.state.set({ kind: 'loading' });
    const [manifest, markdown] = await Promise.all([
      this.help.manifest(),
      this.help.chapter(t.chapter),
    ]);
    if (this.topic() !== t) return; // another topic was opened meanwhile
    this.chapterTitle.set(findChapter(manifest, t.chapter)?.title ?? null);
    if (!markdown) return this.state.set({ kind: 'missing', what: 'chapter' });
    let section = t.anchor ? extractSection(markdown, t.anchor) : chapterIntro(markdown);
    if (!section && t.anchor) {
      // A translated chapter without this anchor yet: the English section instead.
      const english = await this.help.chapter(t.chapter, 'en');
      if (this.topic() !== t) return;
      section = english ? extractSection(english, t.anchor) : null;
    }
    if (!section) return this.state.set({ kind: 'missing', what: 'section' });
    this.state.set({
      kind: 'ready',
      title: section.title,
      html: trustedMarkdown(this.sanitizer, rewriteManualLinks(section.body, t.chapter), 'manual'),
    });
  }

  /** In-app links (`/help/...`, other routes) navigate in the SPA and close the panel. */
  onClick(e: MouseEvent): void {
    const a = (e.target as HTMLElement | null)?.closest?.('a');
    const href = a?.getAttribute('href');
    if (!href || !href.startsWith('/') || href.startsWith('/manual/')) return;
    if (e.ctrlKey || e.metaKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    this.help.close();
    void this.router.navigateByUrl(href);
  }
}
