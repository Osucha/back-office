import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';
import { type HtmlProfile, sanitizeHtml, sanitizeToTrustedHtml } from './safe-html';

marked.setOptions({ gfm: true, breaks: false });

function markdownToHtml(source: string): string {
  return marked.parse(source, { async: false }) as string;
}

/**
 * Markdown rendered and sanitised with DOMPurify (`safe-html.ts`), as a string. Sources are
 * untrusted by default (LLM and agent output, MR notes).
 */
export function renderMarkdown(
  source: string | null | undefined,
  profile: HtmlProfile = 'untrusted',
): string {
  return source ? sanitizeHtml(markdownToHtml(source), profile) : '';
}

/** Markdown rendered, sanitised and marked trusted for `[innerHTML]` (see `safe-html.ts`). */
export function trustedMarkdown(
  sanitizer: DomSanitizer,
  source: string | null | undefined,
  profile: HtmlProfile = 'untrusted',
): SafeHtml {
  return sanitizeToTrustedHtml(sanitizer, source ? markdownToHtml(source) : '', profile);
}

@Component({
  selector: 'app-markdown',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="markdown-body" [innerHTML]="html()"></div>`,
})
export class MarkdownViewerComponent {
  private readonly sanitizer = inject(DomSanitizer);
  readonly source = input<string | null | undefined>('');
  readonly html = computed<SafeHtml>(() => trustedMarkdown(this.sanitizer, this.source()));
}
