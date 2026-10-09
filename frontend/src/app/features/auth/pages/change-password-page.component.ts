import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import { safeReturnTo } from '../../../core/auth/browser-navigation';
import { IdentityService } from '../../../core/auth/identity.service';
import { NzMessageService } from 'ng-zorro-antd/message';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { AuthShellComponent } from '../components/auth-shell.component';
import { AUTH_FORM_STYLES } from '../components/auth-styles';
import { NewPasswordComponent } from '../components/new-password.component';
import { PasswordFieldComponent } from '../components/password-field.component';
import { passwordErrorOf } from '../data/password-errors';

/**
 * `/change-password`: the forced change after a local sign-in (`PASSWORD_CHANGE_REQUIRED`, or any
 * API call answering `403 password-change-required`), and a plain change from the account page.
 * The forced change needs no current password. Afterwards the MFA set-up follows when the platform
 * requires it, otherwise `returnTo`.
 */
@Component({
  selector: 'app-change-password-page',
  imports: [
    NzButtonModule,
    AuthShellComponent,
    GlyphComponent,
    NewPasswordComponent,
    PasswordFieldComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-shell label="Change your password" i18n-label="@@auth.changePassword.title">
      @if (forced()) {
        <h1 class="form-title" i18n="@@auth.setPassword.resetTitle">Choose a new password</h1>
        <p class="form-lead" i18n="@@auth.changePassword.forcedLead">
          Your account needs a new password before you continue. Pick one you do not use anywhere
          else.
        </p>
      } @else {
        <h1 class="form-title" i18n="@@auth.changePassword.title">Change your password</h1>
        <p class="form-lead" i18n="@@auth.changePassword.lead">
          Your other sessions are signed out when the password changes.
        </p>
      }
      <div class="who">
        <span class="avatar" aria-hidden="true">{{ initial() }}</span>
        <span class="addr">{{ email() }}</span>
      </div>
      @if (formError()) {
        <div class="banner error" role="alert">
          <bo-glyph holder="blocked" [size]="14" />
          <span>{{ formError() }}</span>
        </div>
      }
      <form (submit)="$event.preventDefault(); save()" novalidate>
        <input type="email" name="username" autocomplete="username" [value]="email()" hidden />
        @if (!forced()) {
          <app-password-field
            #current
            label="Current password"
            i18n-label="@@auth.changePassword.current"
            name="current-password"
            autocomplete="current-password"
            [error]="currentError()"
            [(value)]="currentPassword"
          />
        }
        <app-new-password
          #next
          [email]="email()"
          [policy]="config.config()?.passwordPolicy"
          [serverError]="newError()"
          [(value)]="newPassword"
          [(repeat)]="repeat"
        />
        <button
          nz-button
          nzType="primary"
          class="submit"
          type="submit"
          [nzLoading]="saving()"
          [disabled]="!canSave()"
          i18n="@@auth.changePassword.submit"
        >
          Change password
        </button>
      </form>
      <div class="foot row-between">
        @if (forced()) {
          <button
            type="button"
            class="link-button"
            (click)="identity.logout()"
            i18n="@@auth.signOutInstead"
          >
            Sign out instead
          </button>
        } @else {
          <button type="button" class="link-button" (click)="cancel()" i18n="@@auth.backToAccount">
            Back to my account
          </button>
        }
      </div>
    </app-auth-shell>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      .foot {
        margin-top: var(--bo-space-4);
      }
    `,
  ],
})
export class ChangePasswordPageComponent {
  readonly identity = inject(IdentityService);
  readonly config = inject(AuthConfigService);
  private readonly api = inject(LocalAuthApi);
  private readonly router = inject(Router);
  private readonly message = inject(NzMessageService);

  /** `?forced=1`: set by the sign-in flow and by the restricted-session routing. */
  readonly forced = input(false, { transform: booleanAttribute });
  readonly returnTo = input<string | undefined>();

  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly repeat = signal('');
  readonly saving = signal(false);
  readonly currentError = signal<string | null>(null);
  readonly newError = signal<string | null>(null);
  readonly formError = signal<string | null>(null);
  private readonly next = viewChild.required<NewPasswordComponent>('next');
  private readonly current = viewChild<PasswordFieldComponent>('current');

  readonly email = computed(() => this.identity.user()?.email ?? '');
  readonly initial = computed(() => (this.email()[0] ?? '?').toUpperCase());
  readonly canSave = computed(
    () => this.next().valid() && (this.forced() || this.currentPassword().length > 0),
  );

  constructor() {
    this.config.load().subscribe();
    queueMicrotask(() => (this.forced() ? this.next().focus() : this.current()?.focus()));
  }

  async save(): Promise<void> {
    if (!this.canSave() || this.saving()) return;
    this.saving.set(true);
    this.currentError.set(null);
    this.newError.set(null);
    this.formError.set(null);
    try {
      await firstValueFrom(
        this.api.changePassword({
          currentPassword: this.forced() ? null : this.currentPassword(),
          newPassword: this.newPassword(),
        }),
      );
    } catch (err) {
      this.saving.set(false);
      const e = passwordErrorOf(err);
      if (e.field === 'current') {
        this.currentError.set(e.message);
        this.current()?.focus();
      } else if (e.field === 'new') {
        this.newError.set(e.message);
        this.next().focus();
      } else this.formError.set(e.message);
      return;
    }
    this.message.success(
      $localize`:@@auth.changePassword.done:Password changed. Your other sessions were signed out.`,
    );
    const target = safeReturnTo(this.returnTo());
    // A forced change may be followed by the required MFA set-up.
    const security = await firstValueFrom(this.api.mySecurity()).catch(() => null);
    if (security?.mfaRequired && !security.mfaEnabled) {
      await this.router.navigate(['/setup-mfa'], {
        queryParams: { forced: 1, returnTo: target === '/' ? null : target },
      });
      return;
    }
    await this.identity.load();
    await this.router.navigateByUrl(
      this.forced() ? target : safeReturnTo(this.returnTo() ?? '/account'),
    );
  }

  cancel(): void {
    void this.router.navigateByUrl(safeReturnTo(this.returnTo() ?? '/account'));
  }
}
