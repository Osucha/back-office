import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  model,
  output,
  signal,
  viewChild,
} from '@angular/core';
import type { PasswordPolicy } from '../../../core/api/types';
import { DEFAULT_POLICY, passwordAcceptable, passwordChecks } from '../data/password-rules';
import { PasswordFieldComponent } from './password-field.component';
import { PasswordHintsComponent } from './password-hints.component';

let nextId = 0;

/**
 * New password + repeat, with the live policy hints. `valid()` is true when every hint is met and
 * both fields match. `serverError` (from the backend's policy check) is shown under the field.
 */
@Component({
  selector: 'app-new-password',
  imports: [PasswordFieldComponent, PasswordHintsComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-password-field
      #first
      [label]="label()"
      name="new-password"
      autocomplete="new-password"
      [hintId]="hintId"
      [maxLength]="(policy() ?? defaults).maxLength"
      [error]="serverError()"
      [(value)]="value"
      (enter)="enter.emit()"
    />
    <app-password-hints
      [hintId]="hintId"
      [password]="value()"
      [email]="email()"
      [policy]="policy()"
    />
    <app-password-field
      label="Repeat the new password"
      i18n-label="@@auth.newPassword.repeat"
      name="repeat-password"
      autocomplete="new-password"
      [error]="mismatch()"
      [(value)]="repeat"
      (enter)="enter.emit()"
      (focusout)="repeatTouched.set(true)"
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
export class NewPasswordComponent {
  readonly label = input($localize`:@@auth.newPassword.label:New password`);
  readonly email = input<string | null | undefined>(null);
  readonly policy = input<PasswordPolicy | null | undefined>(null);
  readonly serverError = input<string | null>(null);
  readonly value = model('');
  readonly repeat = model('');
  readonly enter = output<void>();

  readonly hintId = `pw-hints-${++nextId}`;
  readonly defaults = DEFAULT_POLICY;
  readonly repeatTouched = signal(false);
  private readonly first = viewChild.required<PasswordFieldComponent>('first');

  readonly mismatch = computed(() => {
    const r = this.repeat();
    if (!r || (!this.repeatTouched() && r.length < this.value().length)) return null;
    return r === this.value()
      ? null
      : $localize`:@@auth.newPassword.mismatch:The two passwords do not match.`;
  });
  readonly valid = computed(
    () =>
      passwordAcceptable(
        passwordChecks(this.value(), this.email(), this.policy() ?? DEFAULT_POLICY),
      ) && this.value() === this.repeat(),
  );

  focus(): void {
    this.first().focus();
  }
}
