import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

export interface SegmentedNavItem {
  id: string;
  label: string;
  link: string;
  count?: number | null;
}

/**
 * Section switcher as a segmented control made of links (every section stays deep-linkable and
 * opens in a new tab); the current one carries `aria-current="page"`. Library and Evaluation use it
 * under their page header:
 *
 *   <bo-segmented-nav boHeaderBelow label="Library sections" [items]="items" active="skills" />
 */
@Component({
  selector: 'bo-segmented-nav',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="seg" [attr.aria-label]="label()">
      @for (s of items(); track s.id) {
        <a
          [routerLink]="s.link"
          [class.on]="active() === s.id"
          [attr.aria-current]="active() === s.id ? 'page' : null"
        >
          {{ s.label }}
          @if (s.count !== null && s.count !== undefined) {
            <span class="count">{{ s.count }}</span>
          }
        </a>
      }
    </nav>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .seg {
        display: inline-flex;
        gap: 2px;
        padding: 3px;
        border-radius: 8px;
        background: var(--bo-surface-3);
        border: 1px solid var(--bo-line);
      }
      a {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        height: 26px;
        padding: 0 12px;
        border-radius: 6px;
        color: var(--bo-ink-2);
        font-size: 13px;
        font-weight: 500;
        text-decoration: none;
        transition:
          background var(--bo-dur-fast) var(--bo-ease),
          color var(--bo-dur-fast) var(--bo-ease);
      }
      a:hover {
        color: var(--bo-ink);
      }
      a.on {
        background: var(--bo-surface);
        color: var(--bo-ink);
        box-shadow: 0 0 0 1px var(--bo-line);
      }
      a:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
      .count {
        font-family: var(--bo-font-mono);
        font-size: 11px;
        color: var(--bo-ink-3);
        font-variant-numeric: tabular-nums;
      }
    `,
  ],
})
export class SegmentedNavComponent {
  readonly items = input.required<readonly SegmentedNavItem[]>();
  readonly active = input<string | null>(null);
  readonly label = input($localize`:@@core.segmented.label:Sections`);
}
