import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { HelpLinkComponent } from '../../help/help-link.component';
import type { HelpKey } from '../../help/help-map';
import { BatonComponent } from './baton.component';
import type { BatonHolder } from './baton';

/**
 * Empty state: one sentence, one primary action, one link to the guide; no illustrations.
 *
 *   <bo-empty title="No runs yet." detail="Start one from a project workflow."
 *             actionLabel="Go to projects" (action)="go()" help="runs" />
 */
@Component({
  selector: 'bo-empty',
  imports: [RouterLink, NzButtonModule, BatonComponent, HelpLinkComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.data-tone]': 'tone()' },
  template: `
    <div class="empty">
      @if (holder()) {
        <bo-baton [holder]="holder()!" [label]="holderLabel() ?? holder()!" size="sm" />
      }
      <p class="title">{{ title() }}</p>
      @if (detail()) {
        <p class="detail">{{ detail() }}</p>
      }
      <ng-content />
      @if (actionLabel() || guideLink() || help()) {
        <div class="actions">
          @if (actionLabel()) {
            <button
              nz-button
              [nzType]="primary() ? 'primary' : 'default'"
              type="button"
              (click)="action.emit()"
            >
              {{ actionLabel() }}
            </button>
          }
          @if (guideLink(); as g) {
            @if (g.startsWith('http')) {
              <a [href]="g" target="_blank" rel="noopener noreferrer">{{ guideLabel() }}</a>
            } @else {
              <a [routerLink]="g.split('#')[0]" [fragment]="g.split('#')[1]">{{ guideLabel() }}</a>
            }
          } @else if (help(); as h) {
            <bo-help-link [topic]="h" variant="text">{{ guideLabel() }}</bo-help-link>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .empty {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 6px;
        padding: 28px 24px;
        border: 1px dashed var(--bo-line-strong);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
      }
      :host([data-tone='calm']) .empty {
        border-style: solid;
        border-color: var(--bo-line);
        background: var(--bo-surface);
      }
      :host([data-tone='quiet']) .empty {
        border: 0;
        background: transparent;
        padding: 16px 0;
      }
      .title {
        margin: 4px 0 0;
        font-size: 15px;
        font-weight: 600;
        color: var(--bo-ink);
      }
      .detail {
        margin: 0;
        font-size: 13px;
        color: var(--bo-ink-3);
        max-width: 60ch;
      }
      .actions {
        display: flex;
        align-items: center;
        gap: 16px;
        margin-top: 8px;
        font-size: 13px;
      }
    `,
  ],
})
export class EmptyComponent {
  readonly title = input.required<string>();
  readonly detail = input<string | null>(null);
  readonly actionLabel = input<string | null>(null);
  readonly primary = input(true);
  readonly guideLink = input<string | null>(null);
  readonly guideLabel = input($localize`:@@core.help.readGuide:Read the guide`);
  /** The guide as a manual section in the help panel (instead of guideLink). */
  readonly help = input<HelpKey | null>(null);
  readonly holder = input<BatonHolder | null>(null);
  readonly holderLabel = input<string | null>(null);
  /** `default` dashed box, `calm` solid (e.g. "Nothing needs you"), `quiet` no box. */
  readonly tone = input<'default' | 'calm' | 'quiet'>('default');
  readonly action = output<void>();
}
