import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { HELP_TOPICS, type HelpKey } from './help-map';
import { HelpService } from './help.service';

/**
 * Contextual help: opens the help panel on one manual section (topics in `help-map.ts`). The page
 * stays where it is, so it is safe next to one-time secrets and unsaved forms.
 *
 *   <bo-help-link topic="project.ci" />                         a quiet `?` icon button
 *   <bo-help-link topic="project.ciVariables" variant="text">Full steps</bo-help-link>
 */
@Component({
  selector: 'bo-help-link',
  imports: [NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      type="button"
      [class]="variant()"
      [attr.aria-label]="variant() === 'icon' ? title() : null"
      [attr.title]="title()"
      aria-haspopup="dialog"
      (click)="open($event)"
    >
      @if (variant() === 'icon') {
        <nz-icon nzType="question-circle" aria-hidden="true" />
      } @else {
        <ng-content><span i18n="@@core.help.readGuide">Read the guide</span></ng-content>
      }
    </button>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        vertical-align: middle;
      }
      button {
        all: unset;
        cursor: pointer;
        box-sizing: border-box;
      }
      .icon {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 24px;
        height: 24px;
        border-radius: 999px;
        color: var(--bo-ink-3);
        font-size: 15px;
        transition:
          color var(--bo-dur-fast),
          background var(--bo-dur-fast);
      }
      .icon:hover {
        color: var(--bo-accent);
        background: var(--bo-surface-3);
      }
      .text {
        color: var(--bo-accent);
        font-size: inherit;
        text-decoration: underline;
        text-underline-offset: 2px;
        text-decoration-thickness: 1px;
      }
      .text:hover {
        text-decoration-thickness: 2px;
      }
      button:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
    `,
  ],
})
export class HelpLinkComponent {
  private readonly help = inject(HelpService);
  readonly topic = input.required<HelpKey>();
  readonly variant = input<'icon' | 'text'>('icon');
  /** Overrides the topic's label in "Help: <label>". */
  readonly topicLabel = input<string | null>(null);
  readonly label = computed(() => this.topicLabel() ?? HELP_TOPICS[this.topic()].label);
  readonly title = computed(() => $localize`:@@core.help.linkTitle:Help: ${this.label()}:topic:`);

  open(e: Event): void {
    e.stopPropagation();
    this.help.open(HELP_TOPICS[this.topic()]);
  }
}
