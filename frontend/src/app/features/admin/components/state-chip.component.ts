import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import type { BadgeTone } from '../data/local-accounts';

/**
 * Small token-coloured chip with the baton glyph and a word (admin states: account badges, grant
 * effects, audit results). `neutral` is an outlined chip without glyph (e.g. the sign-in method).
 * Like `bo-baton` but with a caller-provided hint instead of the baton meaning.
 */
@Component({
  selector: 'app-state-chip',
  imports: [GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[attr.data-tone]': 'tone()',
    '[attr.data-size]': 'size()',
    '[attr.title]': 'hint() || null',
  },
  template: `
    @if (tone() !== 'neutral') {
      <bo-glyph [holder]="$any(tone())" [size]="size() === 'sm' ? 10 : 11" />
    }
    <span class="label">{{ label() }}</span>
    @if (hint() && srHint()) {
      <span class="sr-only">: {{ hint() }}</span>
    }
  `,
  styles: [
    `
      :host {
        --tint: var(--bo-idle-soft);
        --ink: var(--bo-idle-ink);
        display: inline-flex;
        align-items: center;
        gap: 5px;
        height: 22px;
        padding: 0 9px 0 7px;
        border-radius: var(--bo-radius-chip);
        background: var(--tint);
        color: var(--ink);
        font-size: 12px;
        font-weight: 500;
        line-height: 1;
        white-space: nowrap;
        vertical-align: middle;
      }
      :host([data-size='sm']) {
        height: 20px;
        padding: 0 8px 0 6px;
        font-size: 11.5px;
      }
      :host([data-tone='neutral']) {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        color: var(--bo-ink-2);
        padding: 0 8px;
      }
      :host([data-tone='done']) {
        --tint: var(--bo-done-soft);
        --ink: var(--bo-done-ink);
      }
      :host([data-tone='blocked']) {
        --tint: var(--bo-blocked-soft);
        --ink: var(--bo-blocked-ink);
      }
      :host([data-tone='you']) {
        --tint: var(--bo-you-soft);
        --ink: var(--bo-you-ink);
        box-shadow: inset 0 0 0 1px var(--bo-you);
      }
      :host([data-tone='agent']) {
        --tint: var(--bo-agent-soft);
        --ink: var(--bo-agent-ink);
      }
      :host([data-tone='ci']) {
        --tint: var(--bo-ci-soft);
        --ink: var(--bo-ci-ink);
      }
      :host([data-tone='idle']) {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
      }
    `,
  ],
})
export class StateChipComponent {
  readonly tone = input<BadgeTone>('idle');
  readonly label = input.required<string>();
  readonly hint = input<string | null>(null);
  /** Also read the hint to screen readers (default: tooltip only). */
  readonly srHint = input(false);
  readonly size = input<'sm' | 'md'>('md');
}
