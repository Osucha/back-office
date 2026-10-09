import {
  ChangeDetectionStrategy,
  Component,
  effect,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { PasswordFieldComponent } from '../../auth/public-api';

/**
 * "Confirm with your password" dialog for sensitive changes (turn off MFA, new recovery codes):
 * the consequence in words, the current password, and a verb+object confirm button. The host calls
 * the API and passes the error back (`error`), or closes it.
 */
@Component({
  selector: 'app-password-confirm-dialog',
  imports: [NzButtonModule, NzModalModule, PasswordFieldComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="open()"
      [nzTitle]="title()"
      [nzFooter]="footer"
      [nzWidth]="440"
      (nzOnCancel)="dismissed.emit()"
      (nzAfterOpen)="field()?.focus()"
    >
      <ng-container *nzModalContent>
        <p class="consequence">{{ consequence() }}</p>
        <form (submit)="$event.preventDefault(); submit()" novalidate>
          <app-password-field
            #field
            label="Your current password"
            i18n-label="@@account.confirm.password"
            name="current-password"
            autocomplete="current-password"
            [error]="error()"
            [(value)]="password"
          />
        </form>
      </ng-container>
      <ng-template #footer>
        <button nz-button type="button" (click)="dismissed.emit()" i18n="@@account.confirm.cancel">
          Cancel
        </button>
        <button
          nz-button
          nzType="primary"
          [nzDanger]="danger()"
          type="button"
          [nzLoading]="busy()"
          [disabled]="!password()"
          (click)="submit()"
        >
          {{ confirmLabel() }}
        </button>
      </ng-template>
    </nz-modal>
  `,
  styles: [
    `
      .consequence {
        margin: 0 0 4px;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class PasswordConfirmDialogComponent {
  readonly open = input(false);
  readonly title = input($localize`:@@account.confirm.title:Confirm with your password`);
  readonly consequence = input('');
  readonly confirmLabel = input($localize`:@@account.confirm.confirm:Confirm`);
  readonly danger = input(false);
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmed = output<string>();
  /** Closed without confirming (Cancel, Esc, the close icon). */
  readonly dismissed = output<void>();

  readonly password = signal('');
  readonly field = viewChild<PasswordFieldComponent>('field');

  constructor() {
    // A fresh field every time the dialog opens.
    effect(() => {
      if (this.open()) untracked(() => this.password.set(''));
    });
  }

  submit(): void {
    if (this.password() && !this.busy()) this.confirmed.emit(this.password());
  }
}
