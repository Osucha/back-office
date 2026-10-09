import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { PasswordPolicy } from '../../../core/api/types';
import { DEFAULT_POLICY, lengthMeter, passwordChecks } from '../data/password-rules';

/**
 * Live policy hints under a new-password field: a length meter and one line per enabled rule
 * (length, not common, not the e-mail name), each with a glyph and words. Mirrors the backend
 * policy; the backend checks again on submit.
 */
@Component({
  selector: 'app-password-hints',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[attr.id]': 'hintId()' },
  template: `
    <div class="meter" [attr.data-level]="meter().level" aria-hidden="true">
      <span class="bar" [style.width.%]="meter().ratio * 100"></span>
      <span class="half"></span>
    </div>
    <ul class="checks" aria-label="Password requirements" i18n-aria-label="@@auth.hints.label">
      @for (c of checks(); track c.id) {
        <li [class.ok]="c.ok" [class.pending]="c.pending">
          <svg viewBox="0 0 16 16" aria-hidden="true">
            @if (c.ok) {
              <circle cx="8" cy="8" r="6.5" class="fill" />
              <path d="M5 8.3l2 2 4-4.4" class="knock" />
            } @else if (c.pending) {
              <circle cx="8" cy="8" r="5.5" class="ring" />
            } @else {
              <circle cx="8" cy="8" r="6.5" class="fill" />
              <path d="M5.5 5.5l5 5M10.5 5.5l-5 5" class="knock" />
            }
          </svg>
          <span>{{ c.label }}</span>
          <span class="sr-only">
            @if (c.pending) {
              <ng-container i18n="@@auth.hints.pending">(not checked yet)</ng-container>
            } @else if (c.ok) {
              <ng-container i18n="@@auth.hints.met">(met)</ng-container>
            } @else {
              <ng-container i18n="@@auth.hints.notMet">(not met)</ng-container>
            }
          </span>
        </li>
      }
    </ul>
    <p class="sr-only" aria-live="polite">{{ meter().label }}</p>
  `,
  styles: [
    `
      :host {
        display: block;
        margin-top: var(--bo-space-2);
      }
      .meter {
        position: relative;
        height: 4px;
        border-radius: 2px;
        background: var(--bo-line);
        overflow: hidden;
      }
      .bar {
        position: absolute;
        inset: 0 auto 0 0;
        border-radius: 2px;
        background: var(--bo-idle);
        transition: width var(--bo-dur-fast) var(--bo-ease);
      }
      .half {
        position: absolute;
        top: 0;
        bottom: 0;
        left: 50%;
        width: 2px;
        background: var(--bo-surface);
      }
      [data-level='short'] .bar {
        background: var(--bo-you);
      }
      [data-level='ok'] .bar,
      [data-level='strong'] .bar {
        background: var(--bo-done);
      }
      .checks {
        list-style: none;
        margin: var(--bo-space-2) 0 0;
        padding: 0;
        display: grid;
        gap: 4px;
      }
      li {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: var(--bo-fs-meta);
        color: var(--bo-blocked-ink);
      }
      li.ok {
        color: var(--bo-done-ink);
      }
      li.pending {
        color: var(--bo-ink-3);
      }
      svg {
        width: 14px;
        height: 14px;
        flex: none;
      }
      .fill {
        fill: currentColor;
      }
      .ring {
        fill: none;
        stroke: currentColor;
        stroke-width: 1.4;
      }
      .knock {
        fill: none;
        stroke: var(--bo-surface);
        stroke-width: 1.8;
        stroke-linecap: round;
        stroke-linejoin: round;
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
      }
    `,
  ],
})
export class PasswordHintsComponent {
  readonly password = input('');
  readonly email = input<string | null | undefined>(null);
  readonly policy = input<PasswordPolicy | null | undefined>(null);
  readonly hintId = input<string | null>(null);

  private readonly effectivePolicy = computed(() => this.policy() ?? DEFAULT_POLICY);
  readonly checks = computed(() =>
    passwordChecks(this.password(), this.email(), this.effectivePolicy()),
  );
  readonly meter = computed(() => lengthMeter(this.password(), this.effectivePolicy()));
}
