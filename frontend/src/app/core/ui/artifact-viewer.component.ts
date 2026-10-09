import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { ArtifactKind } from '../api/types';
import { JsonViewComponent } from './json-view.component';
import { MarkdownViewerComponent } from './markdown-viewer.component';

/** Picks the right renderer for an artefact based on kind / media type. */
@Component({
  selector: 'app-artifact-viewer',
  imports: [MarkdownViewerComponent, JsonViewComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @switch (mode()) {
      @case ('markdown') {
        <app-markdown [source]="content()" />
      }
      @case ('json') {
        <app-json-view [value]="content()" maxHeight="70vh" />
      }
      @default {
        <pre class="code-block" style="max-height: 70vh" tabindex="0">{{ content() }}</pre>
      }
    }
  `,
})
export class ArtifactViewerComponent {
  readonly content = input<string | null | undefined>('');
  readonly kind = input<ArtifactKind | string | null | undefined>(null);
  readonly mediaType = input<string | null | undefined>(null);

  readonly mode = computed<'markdown' | 'json' | 'text'>(() => {
    const kind = this.kind();
    const mt = (this.mediaType() ?? '').toLowerCase();
    if (kind === 'JSON' || mt.includes('json')) return 'json';
    if (kind === 'LOG' || kind === 'DIFF' || mt.includes('diff') || mt === 'text/plain')
      return 'text';
    if (
      ['SPEC', 'PLAN', 'TASKS', 'ANALYSIS', 'REVIEW', 'MARKDOWN'].includes(kind ?? '') ||
      mt.includes('markdown')
    ) {
      return 'markdown';
    }
    const c = (this.content() ?? '').trimStart();
    if (c.startsWith('{') || c.startsWith('[')) return 'json';
    return 'markdown';
  });
}
