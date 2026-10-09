import { ChangeDetectionStrategy, Component } from '@angular/core';

interface Seg {
  holder: 'agent' | 'you' | 'ci' | 'done' | 'idle';
  from: number;
  to: number;
}
interface Track {
  key: string;
  title: string;
  state: string;
  holder: Seg['holder'];
  y: number;
  segs: Seg[];
  /** x of the current-step marker; null when finished. */
  marker: number | null;
}

/**
 * Static relay-track illustration for the sign-in screens: three pieces of work, each a strip of
 * segments coloured by who held the baton at that step. Pure SVG filled from the Relay tokens, so it
 * follows light and dark. Decorative (`aria-hidden`); the copy next to it carries the message.
 */
@Component({
  selector: 'app-relay-illustration',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { 'aria-hidden': 'true' },
  template: `
    <svg viewBox="0 0 400 236" focusable="false">
      @for (t of tracks; track t.key) {
        <g [attr.transform]="'translate(0,' + t.y + ')'">
          <text x="0" y="0" class="key">{{ t.key }}</text>
          <text x="64" y="0" class="title">{{ t.title }}</text>
          <text x="400" y="0" class="state" [attr.data-holder]="t.holder" text-anchor="end">
            {{ t.state }}
          </text>
          <rect x="0" y="14" width="400" height="8" rx="4" class="rail" />
          @for (s of t.segs; track $index) {
            <rect
              [attr.x]="s.from"
              y="14"
              [attr.width]="s.to - s.from"
              height="8"
              rx="4"
              [attr.data-holder]="s.holder"
            />
          }
          @if (t.marker !== null) {
            <circle [attr.cx]="t.marker" cy="18" r="7" class="ring" [attr.data-holder]="t.holder" />
            <circle [attr.cx]="t.marker" cy="18" r="3" [attr.data-holder]="t.holder" />
          } @else {
            <circle cx="393" cy="18" r="7" data-holder="done" />
            <path d="M389.6 18.2l2.3 2.3 4-4.4" class="check" />
          }
          <g class="ticks">
            @for (s of t.segs; track $index) {
              <text [attr.x]="s.from" y="38" class="tick">{{ labelOf(s) }}</text>
            }
          </g>
        </g>
      }
    </svg>
  `,
  styles: [
    `
      :host {
        display: block;
        width: 100%;
        max-width: 440px;
      }
      svg {
        display: block;
        width: 100%;
        height: auto;
        overflow: visible;
      }
      text {
        font-family: var(--bo-font-ui);
        font-size: 11.5px;
        fill: var(--bo-ink-2);
      }
      .key {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        fill: var(--bo-ink-3);
      }
      .title {
        fill: var(--bo-ink);
        font-weight: 500;
      }
      .tick {
        font-family: var(--bo-font-mono);
        font-size: 9.5px;
        fill: var(--bo-ink-3);
      }
      .rail {
        fill: var(--bo-line);
      }
      [data-holder='agent'] {
        fill: var(--bo-agent);
      }
      [data-holder='you'] {
        fill: var(--bo-you);
      }
      [data-holder='ci'] {
        fill: var(--bo-ci);
      }
      [data-holder='done'] {
        fill: var(--bo-done);
      }
      [data-holder='idle'] {
        fill: var(--bo-idle);
      }
      text.state[data-holder='agent'] {
        fill: var(--bo-agent-ink);
      }
      text.state[data-holder='you'] {
        fill: var(--bo-you-ink);
      }
      text.state[data-holder='ci'] {
        fill: var(--bo-ci-ink);
      }
      text.state[data-holder='done'] {
        fill: var(--bo-done-ink);
      }
      text.state {
        font-weight: 500;
      }
      .ring {
        fill: var(--bo-surface);
        stroke-width: 2.5;
      }
      .ring[data-holder='agent'] {
        stroke: var(--bo-agent);
      }
      .ring[data-holder='you'] {
        stroke: var(--bo-you);
      }
      .ring[data-holder='ci'] {
        stroke: var(--bo-ci);
      }
      .check {
        fill: none;
        stroke: var(--bo-surface);
        stroke-width: 1.8;
        stroke-linecap: round;
        stroke-linejoin: round;
      }
    `,
  ],
})
export class RelayIllustrationComponent {
  readonly tracks: Track[] = [
    {
      key: 'SHOP-42',
      title: $localize`:@@auth.art.checkout:Checkout retries`,
      state: $localize`:@@auth.art.checkoutState:your turn · spec gate`,
      holder: 'you',
      y: 12,
      segs: [
        { holder: 'agent', from: 0, to: 118 },
        { holder: 'you', from: 122, to: 176 },
      ],
      marker: 176,
    },
    {
      key: 'SHOP-39',
      title: $localize`:@@auth.art.refund:Refund webhook`,
      state: $localize`:@@auth.art.refundState:ci · pipeline`,
      holder: 'ci',
      y: 96,
      segs: [
        { holder: 'agent', from: 0, to: 84 },
        { holder: 'you', from: 88, to: 122 },
        { holder: 'agent', from: 126, to: 232 },
        { holder: 'ci', from: 236, to: 290 },
      ],
      marker: 290,
    },
    {
      key: 'SHOP-37',
      title: $localize`:@@auth.art.invoice:Invoice PDF fonts`,
      state: $localize`:@@auth.art.invoiceState:landed`,
      holder: 'done',
      y: 180,
      segs: [
        { holder: 'agent', from: 0, to: 96 },
        { holder: 'you', from: 100, to: 138 },
        { holder: 'agent', from: 142, to: 262 },
        { holder: 'ci', from: 266, to: 330 },
        { holder: 'done', from: 334, to: 400 },
      ],
      marker: null,
    },
  ];

  private readonly labels: Record<Seg['holder'], string> = {
    agent: $localize`:@@auth.art.tick.agent:agent`,
    you: $localize`:@@auth.art.tick.you:you`,
    ci: $localize`:@@auth.art.tick.ci:ci`,
    done: $localize`:@@auth.art.tick.done:merged`,
    idle: '',
  };

  labelOf(s: Seg): string {
    return this.labels[s.holder];
  }
}
