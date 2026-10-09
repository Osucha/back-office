import {
  ChangeDetectionStrategy,
  Component,
  booleanAttribute,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import { safeReturnTo } from '../../../core/auth/browser-navigation';
import { IdentityService } from '../../../core/auth/identity.service';
import { AuthShellComponent } from '../components/auth-shell.component';
import { AUTH_FORM_STYLES } from '../components/auth-styles';
import { MfaEnrolmentComponent } from '../components/mfa-enrolment.component';
import { RecoveryCodesComponent } from '../components/recovery-codes.component';

type Stage = 'checking' | 'enrol' | 'codes' | 'already';

/**
 * `/setup-mfa`: TOTP enrolment (QR, key, code) and then the recovery codes, shown once. Forced after
 * a local sign-in when the platform requires MFA (`MFA_ENROLMENT_REQUIRED` or `403
 * mfa-enrolment-required`); also reachable from the account page and the command palette.
 */
@Component({
  selector: 'app-setup-mfa-page',
  imports: [
    RouterLink,
    NzButtonModule,
    AuthShellComponent,
    MfaEnrolmentComponent,
    RecoveryCodesComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-auth-shell
      label="Set up two-factor authentication"
      i18n-label="@@auth.setupMfa.title"
      [wide]="true"
    >
      <div class="progress" aria-hidden="true">
        <span [class.on]="true"></span>
        <span [class.on]="stage() === 'codes'"></span>
      </div>
      @switch (stage()) {
        @case ('codes') {
          <h1 class="form-title" i18n="@@auth.setupMfa.codesTitle">Save your recovery codes</h1>
          <p class="form-lead" i18n="@@auth.setupMfa.codesLead">
            Two-factor authentication is on. If you lose your phone, a recovery code gets you in.
          </p>
          <app-recovery-codes
            class="codes"
            [codes]="codes()"
            [email]="email()"
            [doneLabel]="forced() ? text.continue : text.backToAccount"
            (done)="finish()"
          />
        }
        @case ('already') {
          <h1 class="form-title" i18n="@@auth.setupMfa.alreadyTitle">
            Two-factor authentication is on
          </h1>
          <p class="form-lead" i18n="@@auth.setupMfa.alreadyLead">
            Your account already asks for a code at sign-in. Manage it, or create new recovery
            codes, on your account page.
          </p>
          <a
            nz-button
            nzType="primary"
            class="submit"
            routerLink="/account"
            [queryParams]="{ section: 'security' }"
            i18n="@@auth.setupMfa.openSecurity"
          >
            Open account security
          </a>
        }
        @default {
          <h1 class="form-title" i18n="@@auth.setupMfa.title">Set up two-factor authentication</h1>
          <p class="form-lead">
            @if (forced()) {
              <ng-container i18n="@@auth.setupMfa.forcedLead"
                >Local accounts need a second step at sign-in. It takes about a
                minute.</ng-container
              >
            } @else {
              <ng-container i18n="@@auth.setupMfa.lead"
                >After your password, Back Office will ask for a code from your phone.</ng-container
              >
            }
          </p>
          @if (stage() === 'enrol') {
            <app-mfa-enrolment (enrolled)="onEnrolled($event)" />
          } @else {
            <div
              class="checking"
              aria-busy="true"
              aria-label="Loading"
              i18n-aria-label="@@auth.setupMfa.loading"
            ></div>
          }
        }
      }
      <div class="foot">
        @if (stage() !== 'codes') {
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
            <a class="link-button" routerLink="/account" i18n="@@auth.setupMfa.backLink"
              >Back to my account</a
            >
          }
        }
      </div>
    </app-auth-shell>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      .progress {
        display: flex;
        gap: 4px;
        margin-bottom: var(--bo-space-4);
      }
      .progress span {
        height: 4px;
        width: 40px;
        border-radius: 2px;
        background: var(--bo-line);
      }
      .progress span.on {
        background: var(--bo-accent);
      }
      .codes {
        margin-top: var(--bo-space-4);
      }
      .checking {
        margin-top: var(--bo-space-4);
        height: 320px;
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface-3);
      }
      .foot {
        margin-top: var(--bo-space-4);
      }
      a.submit {
        display: inline-flex;
        align-items: center;
        justify-content: center;
      }
    `,
  ],
})
export class SetupMfaPageComponent {
  protected readonly text = {
    continue: $localize`:@@auth.setupMfa.continue:Continue to Back Office`,
    backToAccount: $localize`:@@auth.setupMfa.backToAccount:Back to my account`,
  };
  readonly identity = inject(IdentityService);
  private readonly api = inject(LocalAuthApi);
  private readonly router = inject(Router);

  readonly forced = input(false, { transform: booleanAttribute });
  readonly returnTo = input<string | undefined>();

  readonly stage = signal<Stage>('checking');
  readonly codes = signal<string[]>([]);
  readonly email = computed(() => this.identity.user()?.email ?? null);

  constructor() {
    this.api.mySecurity().subscribe({
      next: (s) => this.stage.set(s.mfaEnabled && !this.forced() ? 'already' : 'enrol'),
      error: () => this.stage.set('enrol'),
    });
  }

  onEnrolled(codes: string[]): void {
    this.codes.set(codes);
    this.stage.set('codes');
  }

  async finish(): Promise<void> {
    // The restriction is lifted; /me may now carry the full permissions.
    await this.identity.load();
    const target = this.forced()
      ? safeReturnTo(this.returnTo())
      : safeReturnTo(this.returnTo() ?? '/account');
    await this.router.navigateByUrl(target);
  }
}
