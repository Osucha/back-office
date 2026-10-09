import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import type { AuthMode } from '../../../core/api/types';
import { authModeName, authModeSummary, neutralProvider } from '../data/local-accounts';

/**
 * Sign-in method as radio cards (Local / identity provider / both), each with the consequences
 * under it. Native radios: arrow keys move, the label is the whole card.
 */
@Component({
  selector: 'app-auth-mode-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="options" role="radiogroup" [attr.aria-label]="label()">
      @for (m of modes(); track m) {
        <label class="option" [class.on]="value() === m" [class.disabled]="disabled()">
          <input
            type="radio"
            [name]="name()"
            [value]="m"
            [checked]="value() === m"
            [attr.aria-label]="modeName(m)"
            [attr.aria-describedby]="name() + '-' + m + '-desc'"
            [disabled]="disabled()"
            (change)="valueChange.emit(m)"
          />
          <span class="body">
            <span class="title">
              {{ modeName(m) }}
              @if (current() === m) {
                <span class="current" i18n="@@admin.authMode.current">current</span>
              }
            </span>
            <span class="lines" [id]="name() + '-' + m + '-desc'">
              @for (line of lines(m); track $index) {
                <span class="line">{{ line }}</span>
              }
            </span>
          </span>
        </label>
      }
    </div>
  `,
  styles: [
    `
      .options {
        display: grid;
        gap: 8px;
      }
      .option {
        display: flex;
        gap: 10px;
        align-items: flex-start;
        padding: 10px 12px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface);
        cursor: pointer;
        transition: border-color var(--bo-dur-fast) var(--bo-ease);
      }
      .option:hover {
        border-color: var(--bo-line-strong);
      }
      .option.on {
        border-color: var(--bo-ink);
        box-shadow: inset 0 0 0 1px var(--bo-ink);
      }
      .option.disabled {
        cursor: default;
        opacity: 0.8;
      }
      .option:focus-within {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      input {
        margin: 3px 0 0;
        accent-color: var(--bo-ink);
        flex: none;
      }
      input:focus-visible {
        outline: none;
      }
      .body {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 0;
      }
      .title {
        font-size: 13px;
        font-weight: 600;
        color: var(--bo-ink);
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .current {
        font-size: 11px;
        font-weight: 500;
        color: var(--bo-ink-3);
        padding: 1px 6px;
        border-radius: 999px;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
      }
      .lines {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .line {
        font-size: 12px;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class AuthModePickerComponent {
  readonly name = input.required<string>();
  readonly label = input($localize`:@@admin.authMode.label:Sign-in method`);
  readonly modes = input<readonly AuthMode[]>([]);
  readonly value = input<AuthMode | null>(null);
  readonly current = input<AuthMode | null>(null);
  readonly provider = input(neutralProvider());
  readonly disabled = input(false);
  /** Lines under an option; defaults to the one-sentence summary. */
  readonly describe = input<((m: AuthMode, provider: string) => string[]) | null>(null);
  readonly valueChange = output<AuthMode>();

  modeName(m: AuthMode): string {
    return authModeName(m, this.provider());
  }

  lines(m: AuthMode): string[] {
    const fn = this.describe();
    return fn ? fn(m, this.provider()) : [authModeSummary(m, this.provider())];
  }
}
