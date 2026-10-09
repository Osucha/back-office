import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  model,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { AUTH_FORM_STYLES } from './auth-styles';

let nextId = 0;

/**
 * Password input with a show/hide toggle and a caps-lock hint. The label, the hint and the error are
 * wired with `for` / `aria-describedby`; the caps-lock hint is announced politely.
 */
@Component({
  selector: 'app-password-field',
  imports: [NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label [attr.for]="inputId">
        <span>{{ label() }}</span>
        <ng-content select="[labelAside]" />
      </label>
      <div class="wrap">
        <input
          #el
          class="text-input"
          [id]="inputId"
          [attr.name]="name()"
          [type]="visible() ? 'text' : 'password'"
          [attr.autocomplete]="autocomplete()"
          [attr.aria-invalid]="!!error() || null"
          [attr.aria-describedby]="describedBy()"
          [attr.maxlength]="maxLength()"
          [value]="value()"
          [disabled]="disabled()"
          [required]="required()"
          spellcheck="false"
          autocapitalize="off"
          (input)="value.set(el.value)"
          (keydown)="caps($event)"
          (keyup)="caps($event)"
          (blur)="capsOn.set(false)"
          (keydown.enter)="enter.emit()"
        />
        <button
          type="button"
          class="eye"
          [attr.aria-label]="visible() ? text.hide : text.show"
          [attr.aria-pressed]="visible()"
          [attr.aria-controls]="inputId"
          [disabled]="disabled()"
          (click)="toggle()"
        >
          <nz-icon [nzType]="visible() ? 'eye-invisible' : 'eye'" />
        </button>
        <p class="caps" [id]="inputId + '-caps'" aria-live="polite">
          @if (capsOn()) {
            <span class="caps-on" i18n="@@auth.passwordField.caps">Caps Lock is on</span>
          }
        </p>
      </div>
      @if (error()) {
        <p class="field-error" [id]="inputId + '-error'">{{ error() }}</p>
      }
    </div>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      .wrap {
        position: relative;
      }
      .wrap .text-input {
        padding-right: 44px;
      }
      .eye {
        position: absolute;
        top: 4px;
        right: 4px;
        width: 32px;
        height: 32px;
        border: 0;
        border-radius: var(--bo-radius-control);
        background: transparent;
        color: var(--bo-ink-3);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        font-size: 16px;
      }
      .eye:hover {
        color: var(--bo-ink);
        background: var(--bo-surface-2);
      }
      /* Inside the field, left of the eye: appearing never moves the layout. */
      .caps {
        position: absolute;
        top: 9px;
        right: 42px;
        margin: 0;
        pointer-events: none;
      }
      .caps-on {
        display: inline-block;
        padding: 1px 8px;
        border-radius: var(--bo-radius-chip);
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
        font-size: 11px;
        font-weight: 500;
        line-height: 18px;
      }
      .wrap:has(.caps-on) .text-input {
        padding-right: 150px;
      }
      .field {
        gap: 4px;
      }
      .field > label {
        margin-bottom: 2px;
      }
    `,
  ],
})
export class PasswordFieldComponent {
  readonly label = input($localize`:@@auth.passwordField.label:Password`);
  protected readonly text = {
    hide: $localize`:@@auth.passwordField.hide:Hide password`,
    show: $localize`:@@auth.passwordField.show:Show password`,
  };
  readonly name = input<string | null>('password');
  readonly autocomplete = input<'current-password' | 'new-password'>('current-password');
  readonly error = input<string | null>(null);
  /** Extra ids for aria-describedby (e.g. the policy hints). */
  readonly hintId = input<string | null>(null);
  readonly disabled = input(false);
  readonly required = input(true);
  readonly maxLength = input<number | null>(null);
  readonly value = model('');
  readonly enter = output<void>();

  readonly inputId = `pw-${++nextId}`;
  readonly visible = signal(false);
  readonly capsOn = signal(false);
  private readonly el = viewChild.required<ElementRef<HTMLInputElement>>('el');

  readonly describedBy = computed(() =>
    [this.hintId(), this.inputId + '-caps', this.error() ? this.inputId + '-error' : null]
      .filter(Boolean)
      .join(' '),
  );

  focus(): void {
    this.el().nativeElement.focus();
  }

  toggle(): void {
    this.visible.update((v) => !v);
    // Keep typing where the user was.
    queueMicrotask(() => this.focus());
  }

  caps(event: KeyboardEvent): void {
    if (typeof event.getModifierState === 'function')
      this.capsOn.set(event.getModifierState('CapsLock'));
  }
}
