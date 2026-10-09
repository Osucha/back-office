import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import type { OverrideState } from '../data/grants-model';

const OPTIONS: ReadonlyArray<{
  value: OverrideState;
  label: string;
  holder: 'idle' | 'done' | 'blocked';
}> = [
  { value: 'inherit', label: $localize`:@@admin.effect.inherit:inherit`, holder: 'idle' },
  { value: 'allow', label: $localize`:@@admin.effect.allow:allow`, holder: 'done' },
  { value: 'deny', label: $localize`:@@admin.effect.deny:deny`, holder: 'blocked' },
];

/**
 * inherit / allow / deny as a compact radio group of baton-like chips (glyph + word). Arrow keys
 * move between the options (roving tab index).
 */
@Component({
  selector: 'app-effect-toggle',
  imports: [GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="group" role="radiogroup" [attr.aria-label]="label()">
      @for (o of options; track o.value; let i = $index) {
        <button
          type="button"
          role="radio"
          class="opt"
          [attr.data-holder]="o.holder"
          [class.on]="value() === o.value"
          [attr.aria-checked]="value() === o.value"
          [attr.tabindex]="value() === o.value ? 0 : -1"
          [disabled]="disabled()"
          (click)="pick(o.value)"
          (keydown.arrowright)="move(i, 1, $event)"
          (keydown.arrowdown)="move(i, 1, $event)"
          (keydown.arrowleft)="move(i, -1, $event)"
          (keydown.arrowup)="move(i, -1, $event)"
        >
          <bo-glyph [holder]="o.holder" [size]="10" />
          {{ o.label }}
        </button>
      }
    </div>
  `,
  styles: [
    `
      .group {
        display: inline-flex;
        gap: 2px;
        padding: 2px;
        border-radius: 999px;
        background: var(--bo-surface-2);
        box-shadow: inset 0 0 0 1px var(--bo-line);
      }
      .opt {
        --tint: transparent;
        --ink: var(--bo-ink-2);
        display: inline-flex;
        align-items: center;
        gap: 5px;
        height: 22px;
        padding: 0 9px 0 7px;
        border: 0;
        border-radius: 999px;
        background: var(--tint);
        color: var(--ink);
        font: inherit;
        font-size: 11.5px;
        font-weight: 500;
        cursor: pointer;
      }
      .opt:not(.on) bo-glyph {
        opacity: 0.55;
      }
      .opt:hover:not(:disabled) {
        color: var(--bo-ink);
      }
      .opt.on[data-holder='idle'] {
        --tint: var(--bo-surface);
        --ink: var(--bo-idle-ink);
        box-shadow: 0 0 0 1px var(--bo-line-strong);
      }
      .opt.on[data-holder='done'] {
        --tint: var(--bo-done-soft);
        --ink: var(--bo-done-ink);
      }
      .opt.on[data-holder='blocked'] {
        --tint: var(--bo-blocked-soft);
        --ink: var(--bo-blocked-ink);
      }
      .opt:disabled {
        cursor: default;
      }
      .opt:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 1px;
      }
    `,
  ],
})
export class EffectToggleComponent {
  readonly value = input<OverrideState>('inherit');
  readonly label = input($localize`:@@admin.effect.label:Grant`);
  readonly disabled = input(false);
  readonly valueChange = output<OverrideState>();
  readonly options = OPTIONS;

  pick(v: OverrideState): void {
    if (v !== this.value()) this.valueChange.emit(v);
  }

  move(i: number, d: number, e: Event): void {
    e.preventDefault();
    const next = (i + d + OPTIONS.length) % OPTIONS.length;
    this.pick(OPTIONS[next].value);
    const group = (e.target as HTMLElement).parentElement;
    queueMicrotask(() => group?.querySelectorAll<HTMLElement>('button')[next]?.focus());
  }
}
