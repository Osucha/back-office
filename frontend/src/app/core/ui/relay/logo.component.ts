import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/**
 * The Relay mark (same drawing as `public/favicon.svg` and `docs/ui/logo.svg`): the baton runs along a workflow
 * track from an agent step (cobalt) to your turn (amber), on graphite. Fixed brand colours in both themes; the faint
 * outline keeps the tile visible on dark surfaces. Decorative: the surrounding link or heading carries the name.
 */
@Component({
  selector: 'bo-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[style.width.px]': 'size()', '[style.height.px]': 'size()' },
  template: `
    <svg viewBox="0 0 32 32" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="30" height="30" rx="8.5" fill="#1C1E21" />
      <rect
        x="1.5"
        y="1.5"
        width="29"
        height="29"
        rx="8"
        fill="none"
        stroke="#FFFFFF"
        stroke-opacity="0.14"
      />
      <path
        d="M9.5 22C9.5 15.5 22.5 16.5 22.5 10"
        fill="none"
        stroke="#ECEAE5"
        stroke-width="2.6"
        stroke-linecap="round"
      />
      <circle cx="9.5" cy="22" r="4" fill="#7B93FF" />
      <circle cx="22.5" cy="10" r="4.4" fill="#FFB23E" />
      <circle cx="22.5" cy="10" r="1.5" fill="#1C1E21" />
    </svg>
  `,
  styles: [
    `
      :host {
        display: inline-block;
        flex: none;
        line-height: 0;
      }
      svg {
        width: 100%;
        height: 100%;
      }
    `,
  ],
})
export class LogoComponent {
  readonly size = input(28);
}
