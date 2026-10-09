import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { formatDuration } from '../pipes/duration.pipe';
import { HOLDER_NAME } from './baton';
import { formatCost } from './format';
import { icu } from '../../i18n/icu';
import { pointerIndex, segmentLabel, segmentWidths, type TrackSegment } from './relay-track';

interface ViewSegment extends TrackSegment {
  /** Preferred width in px (proportional, clamped; never stretched). */
  width: number;
  label: string;
  meta: string;
  pointer: boolean;
}

/**
 * Relay track: a strip of segments, one per visited workflow node, coloured by the holder at that
 * step, with the current (or, for a finished run, the last) segment marked by a small pointer and
 * (in flight) the path ahead in outline. Segment widths follow the step durations between a
 * minimum and a maximum, with gaps between them; short runs stay short and left-aligned.
 *
 *   <bo-relay-track [segments]="buildTrack(run)" />                       mini, 6 px (lists)
 *   <bo-relay-track [segments]="track()" variant="full" [selectedNodeId]="sel()"
 *                   (segmentSelected)="select($event.nodeId)" />          header of the run page
 *
 * For a `WorkflowRunSummary` (no nodes) use `<bo-run-track [run]>`, which loads the run.
 */
@Component({
  selector: 'bo-relay-track',
  imports: [NzTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-variant]': 'variant()' },
  template: `
    @if (!view().length) {
      <div class="empty" role="img" [attr.aria-label]="emptyLabel()">
        <span class="bar"></span>
        @if (variant() === 'full') {
          <span class="empty-text">{{ emptyLabel() }}</span>
        }
      </div>
    } @else {
      <ol class="track" [attr.aria-label]="ariaLabel()">
        @for (s of view(); track s.key) {
          <li
            class="seg"
            [style.flex-basis.px]="s.width"
            [attr.data-holder]="s.holder"
            [attr.data-state]="s.state"
            [class.current]="s.current"
            [class.pointer]="s.pointer"
            [class.selected]="variant() === 'full' && s.nodeId === selectedNodeId()"
            [attr.aria-label]="s.label"
            [attr.aria-current]="s.current ? 'step' : null"
          >
            @if (variant() === 'full') {
              <button
                type="button"
                class="seg-btn"
                [disabled]="s.state === 'ahead'"
                (click)="segmentSelected.emit(s)"
                nz-tooltip
                [nzTooltipTitle]="tip"
                nzTooltipPlacement="bottom"
                [attr.aria-label]="s.label"
              >
                <span class="bar"><span class="fill"></span></span>
                @if (s.pointer) {
                  <span class="pointer-mark" aria-hidden="true"></span>
                }
                <span class="name">{{ s.name }}</span>
                <span class="meta">{{ s.meta }}</span>
              </button>
              <ng-template #tip>
                <div class="tip-name">{{ s.name }}</div>
                <div>{{ holderName(s) }} · {{ stateWord(s) }}</div>
                @if (s.meta) {
                  <div>{{ s.meta }}</div>
                }
              </ng-template>
            } @else {
              <span class="bar" [attr.title]="s.label"><span class="fill"></span></span>
              @if (s.pointer) {
                <span class="pointer-mark" aria-hidden="true"></span>
              }
            }
          </li>
        }
      </ol>
    }
  `,
  styles: [
    `
      :host {
        display: block;
        min-width: 0;
        --bar-h: 6px;
      }
      :host([data-variant='full']) {
        --bar-h: 8px;
      }
      .track {
        display: flex;
        gap: 3px;
        margin: 0;
        padding: 0;
        list-style: none;
        align-items: flex-start;
        justify-content: flex-start;
      }
      :host([data-variant='full']) .track {
        gap: 4px;
        padding-bottom: 2px;
      }
      .seg {
        position: relative;
        flex: 0 1 auto;
        min-width: 4px;
        --c: var(--bo-idle);
      }
      :host([data-variant='full']) .seg {
        min-width: 56px;
      }
      .seg[data-holder='agent'] {
        --c: var(--bo-agent);
      }
      .seg[data-holder='you'] {
        --c: var(--bo-you);
      }
      .seg[data-holder='ci'] {
        --c: var(--bo-ci);
      }
      .seg[data-holder='done'] {
        --c: var(--bo-done);
      }
      .seg[data-holder='blocked'] {
        --c: var(--bo-blocked);
      }
      .bar {
        display: block;
        height: var(--bar-h);
        border-radius: 999px;
        background: color-mix(in srgb, var(--c) 62%, var(--bo-surface));
        overflow: hidden;
        position: relative;
      }
      .seg[data-state='active'] .bar,
      .seg[data-state='failed'] .bar {
        background: var(--c);
      }
      .seg[data-state='skipped'] .bar {
        background: var(--bo-line);
      }
      .seg[data-state='ahead'] .bar {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      /* Live work: a light sweep across the active bar (no motion when reduced). */
      .seg[data-state='active'] .fill {
        position: absolute;
        inset: 0;
        background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.45), transparent);
        transform: translateX(-100%);
        animation: bo-sweep 2.4s var(--bo-ease) infinite;
      }
      .seg[data-state='active'][data-holder='you'] .fill {
        animation: none;
      }
      @keyframes bo-sweep {
        to {
          transform: translateX(100%);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        .fill {
          animation: none !important;
        }
      }
      /* Pointer: a small caret under the end of the current (or last) segment. */
      .pointer-mark {
        position: absolute;
        right: 2px;
        top: calc(var(--bar-h) + 2px);
        width: 0;
        height: 0;
        border-left: 4px solid transparent;
        border-right: 4px solid transparent;
        border-bottom: 5px solid var(--c);
      }
      :host([data-variant='mini']) .pointer-mark {
        right: 0;
        top: calc(var(--bar-h) + 1px);
        border-left-width: 3px;
        border-right-width: 3px;
        border-bottom-width: 4px;
      }
      :host([data-variant='mini']) .track {
        padding-bottom: 5px;
      }
      /* full variant */
      .seg-btn {
        all: unset;
        box-sizing: border-box;
        display: flex;
        flex-direction: column;
        gap: 4px;
        width: 100%;
        padding: 0 0 2px;
        cursor: pointer;
        border-radius: 4px;
        min-width: 0;
      }
      .seg-btn:disabled {
        cursor: default;
      }
      .seg-btn:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 3px;
      }
      .name {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        padding-right: 6px;
        margin-top: 6px;
      }
      .meta {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-3);
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
        min-height: 14px;
      }
      .seg[data-state='ahead'] .name {
        color: var(--bo-ink-3);
        font-weight: 400;
      }
      .seg.selected .name {
        color: var(--bo-accent);
        font-weight: 600;
      }
      .seg-btn:hover:not(:disabled) .name {
        text-decoration: underline;
        text-underline-offset: 2px;
      }
      .tip-name {
        font-weight: 600;
      }
      .empty .bar {
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line);
        border-radius: 999px;
      }
      .empty-text {
        display: block;
        margin-top: 6px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class RelayTrackComponent {
  readonly segments = input<TrackSegment[]>([]);
  readonly variant = input<'mini' | 'full'>('mini');
  readonly selectedNodeId = input<string | null>(null);
  readonly emptyLabel = input($localize`:@@core.track.empty:No step has started yet`);
  readonly segmentSelected = output<TrackSegment>();

  readonly view = computed<ViewSegment[]>(() => {
    const segs = this.segments();
    const widths = segmentWidths(segs, this.variant());
    const pointer = pointerIndex(segs);
    return segs.map((s, i) => ({
      ...s,
      width: widths[i],
      pointer: i === pointer,
      label: segmentLabel(s, formatDuration, formatCost),
      meta: [
        s.durationMs !== null ? formatDuration(s.durationMs) : '',
        s.cost?.amount ? formatCost(s.cost) : '',
        s.attempt > 1 ? `×${s.attempt}` : '',
      ]
        .filter(Boolean)
        .join(' · '),
    }));
  });
  readonly ariaLabel = computed(() => {
    const segs = this.segments();
    const cur = segs.find((s) => s.current);
    const steps = icu(
      $localize`:@@core.track.aria:{count, plural, one {Relay track, # step} other {Relay track, # steps}}`,
      { count: segs.filter((s) => s.state !== 'ahead').length },
    );
    return cur ? $localize`:@@core.track.ariaNow:${steps}:steps:, now at ${cur.name}:step:` : steps;
  });

  holderName(s: TrackSegment): string {
    return HOLDER_NAME[s.holder];
  }

  stateWord(s: TrackSegment): string {
    return s.state === 'ahead' ? 'not reached yet' : s.state === 'active' ? 'in progress' : s.state;
  }
}
