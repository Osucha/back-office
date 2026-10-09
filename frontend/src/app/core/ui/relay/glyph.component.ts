import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import type { BatonHolder } from './baton';

/**
 * The baton glyph: filled hexagon (agent), raised hand (you), loop arrow (ci), check (done), stop
 * octagon (blocked), hollow circle (idle). Decorative (`aria-hidden`); the word next to it carries
 * the meaning (state is never shown by colour or shape alone). `live` animates agent and ci.
 */
@Component({
  selector: 'bo-glyph',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    'aria-hidden': 'true',
    '[attr.data-holder]': 'holder()',
    '[class.live]': 'live()',
    '[style.--bo-glyph-size.px]': 'size()',
  },
  template: `
    <svg viewBox="0 0 16 16" focusable="false">
      @switch (holder()) {
        @case ('agent') {
          <polygon class="fill" points="8,1.2 14,4.6 14,11.4 8,14.8 2,11.4 2,4.6" />
        }
        @case ('you') {
          <path
            class="stroke"
            d="M5.2 8.6V3.7a1 1 0 0 1 2 0v3.6M7.2 7.3V2.6a1 1 0 0 1 2 0v4.7M9.2 7.3V3.4a1 1 0 0 1 2 0v4.4M11.2 7.8V5.4a1 1 0 0 1 2 0v4.2c0 2.9-2 5-4.9 5h-.5c-1.5 0-2.6-.6-3.4-1.8L2.7 10.4a1.05 1.05 0 0 1 1.7-1.2l.8 1"
          />
        }
        @case ('ci') {
          <g class="spin">
            <path class="stroke" d="M13.2 8.2A5.2 5.2 0 1 1 11.6 4.3" />
            <path class="stroke" d="M12.4 1.6v3.1H9.3" />
          </g>
        }
        @case ('done') {
          <circle class="fill" cx="8" cy="8" r="6.8" />
          <path class="knock" d="M4.9 8.3l2.1 2.1 4.1-4.6" />
        }
        @case ('blocked') {
          <polygon
            class="fill"
            points="5.3,1.3 10.7,1.3 14.7,5.3 14.7,10.7 10.7,14.7 5.3,14.7 1.3,10.7 1.3,5.3"
          />
          <path class="knock" d="M5 8h6" />
        }
        @default {
          <circle class="stroke" cx="8" cy="8" r="5.6" />
        }
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        width: var(--bo-glyph-size, 12px);
        height: var(--bo-glyph-size, 12px);
        flex: none;
        color: var(--bo-idle);
      }
      :host([data-holder='agent']) {
        color: var(--bo-agent);
      }
      :host([data-holder='you']) {
        color: var(--bo-you);
      }
      :host([data-holder='ci']) {
        color: var(--bo-ci);
      }
      :host([data-holder='done']) {
        color: var(--bo-done);
      }
      :host([data-holder='blocked']) {
        color: var(--bo-blocked);
      }
      svg {
        width: 100%;
        height: 100%;
        overflow: visible;
      }
      .fill {
        fill: currentColor;
      }
      .stroke {
        fill: none;
        stroke: currentColor;
        stroke-width: 1.6;
        stroke-linecap: round;
        stroke-linejoin: round;
      }
      .knock {
        fill: none;
        stroke: var(--bo-surface);
        stroke-width: 1.8;
        stroke-linecap: round;
        stroke-linejoin: round;
      }
      :host(.live[data-holder='agent']) polygon {
        transform-origin: 8px 8px;
        animation: bo-breathe 2.4s var(--bo-ease) infinite;
      }
      :host(.live[data-holder='ci']) .spin {
        transform-origin: 8px 8px;
        animation: bo-turn 3.2s linear infinite;
      }
      @keyframes bo-breathe {
        0%,
        100% {
          opacity: 1;
          transform: scale(1);
        }
        50% {
          opacity: 0.55;
          transform: scale(0.82);
        }
      }
      @keyframes bo-turn {
        to {
          transform: rotate(360deg);
        }
      }
      @media (prefers-reduced-motion: reduce) {
        :host(.live) polygon,
        :host(.live) .spin {
          animation: none !important;
        }
      }
    `,
  ],
})
export class GlyphComponent {
  readonly holder = input.required<BatonHolder>();
  readonly live = input(false);
  readonly size = input(12);
}
