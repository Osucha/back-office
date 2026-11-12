import { ChangeDetectionStrategy, Component, TemplateRef, input, signal } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';

/**
 * ADR 0061: a "?" next to a heading or a label outside `nz-form-label` (which has its own
 * `nzTooltipTitle`); the description of the field or section is its tooltip, shown on hover and on
 * keyboard focus. Warnings, errors and consequences people must read before acting stay visible
 * instead.
 */
@Component({
  selector: 'bo-help-tip',
  imports: [NzIconModule, NzTooltipModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span
      class="tip"
      tabindex="0"
      role="img"
      [attr.aria-label]="ariaLabel()"
      nz-tooltip
      [nzTooltipTitle]="text()"
      [nzTooltipVisible]="visible()"
      (nzTooltipVisibleChange)="visible.set($event)"
      (focus)="visible.set(true)"
      (blur)="visible.set(false)"
      (keydown.escape)="visible.set(false)"
      ><nz-icon nzType="question-circle"
    /></span>
  `,
  styles: [
    `
      :host {
        display: inline-flex;
        vertical-align: middle;
      }
      .tip {
        display: inline-flex;
        align-items: center;
        color: var(--bo-ink-3);
        cursor: help;
        font-size: 12px;
        font-weight: 400;
        border-radius: 50%;
      }
      .tip:hover,
      .tip:focus-visible {
        color: var(--bo-ink);
      }
      .tip:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
    `,
  ],
})
export class HelpTipComponent {
  /** The description: a string or a template (keeps the `i18n` id of a moved paragraph). */
  readonly text = input.required<string | TemplateRef<void>>();
  readonly ariaLabel = input($localize`:@@core.helpTip.label:Help`);
  readonly visible = signal(false);
}
