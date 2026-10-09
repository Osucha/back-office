import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { EmptyComponent } from './relay/empty.component';

/**
 * Empty list placeholder with an optional call-to-action. Kept for existing pages; renders the
 * Relay empty state (`<bo-empty>`), which new code should use directly.
 */
@Component({
  selector: 'app-empty-state',
  imports: [EmptyComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-empty
      [title]="description()"
      [actionLabel]="actionLabel() || null"
      (action)="action.emit()"
    />
  `,
  styles: [
    `
      :host {
        display: block;
      }
    `,
  ],
})
export class EmptyStateComponent {
  readonly description = input($localize`:@@core.empty.nothingYet:Nothing here yet`);
  readonly actionLabel = input<string | null>(null);
  /** Kept for compatibility; the Relay empty state has no icons. */
  readonly actionIcon = input('plus');
  readonly action = output<void>();
}
