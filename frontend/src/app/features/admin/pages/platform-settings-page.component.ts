import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { AdminApi } from '../../../core/api/admin.service';
import type { PlatformSettings } from '../../../core/api/types';
import { AuthConfigService } from '../../../core/auth/auth-config.service';
import type { HasUnsavedChanges } from '../../../core/auth/guards';
import { reportFormError } from '../../../core/forms/server-errors';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { NotifyService } from '../../../core/ui/notify.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { PlatformBudgetPanelComponent } from '../../budgets/public-api';
import { AdminNavComponent } from '../components/admin-nav.component';
import { providerLabel } from '../data/local-accounts';
import {
  DEFAULT_SETTINGS,
  lockSeriesText,
  settingsWarnings,
  toSettings,
} from '../data/platform-settings';

/**
 * Platform settings in sections: sign-in (local login, MFA for local accounts), password policy,
 * lockout, registration and domains, tokens and group sync. Sticky action bar, `Ctrl/⌘ S` saves.
 */
@Component({
  selector: 'app-platform-settings-page',
  imports: [
    PlatformBudgetPanelComponent,
    ReactiveFormsModule,
    NzButtonModule,
    NzFormModule,
    NzRadioModule,
    NzSelectModule,
    NzSwitchModule,
    NzInputNumberModule,
    ActionBarComponent,
    AdminNavComponent,
    PageHeaderComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <app-admin-nav />
      <bo-page-header
        help="admin.settings"
        title="Platform settings"
        i18n-title="@@admin.platformSettings.title"
        subtitle="Who may sign in, how, and how long their credentials live."
        i18n-subtitle="@@admin.platformSettings.subtitle"
      />
      @if (loading()) {
        <bo-skeleton
          variant="detail"
          label="Loading settings"
          i18n-label="@@admin.platformSettings.loading"
        />
      } @else {
        <form nz-form nzLayout="vertical" [formGroup]="form" (ngSubmit)="save()" class="sections">
          <section class="section" aria-labelledby="s-signin">
            <div class="intro">
              <h2 id="s-signin" i18n="@@admin.platformSettings.signin.title">Sign-in</h2>
              @if (oidc()) {
                <p i18n="@@admin.platformSettings.signin.introConfigured">
                  {{ provider() }} is configured. Each user's method (local, identity provider or
                  both) is set on the user.
                </p>
              } @else {
                <p i18n="@@admin.platformSettings.signin.introNotConfigured">
                  {{ provider() }} is not configured. Each user's method (local, identity provider
                  or both) is set on the user.
                </p>
              }
            </div>
            <div class="fields">
              <div class="toggle">
                <nz-switch id="ps-local" formControlName="localLoginEnabled" />
                <label for="ps-local" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.signin.local"
                    >Allow local sign-in</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.signin.localHelp"
                    >E-mail and password on the Back Office sign-in page. When off, local accounts
                    cannot sign in until it is turned on again; their passwords are kept.</span
                  >
                </label>
              </div>
              <div class="toggle">
                <nz-switch id="ps-mfa" formControlName="mfaRequiredForLocal" />
                <label for="ps-mfa" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.signin.mfa"
                    >Require MFA for local accounts</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.signin.mfaHelp"
                    >Local users without an authenticator app must enrol one at their next sign-in
                    before they can do anything else. {{ provider() }} users are not affected.</span
                  >
                </label>
              </div>
              @for (w of warnings(); track w) {
                <p class="warn" role="status">{{ w }}</p>
              }
            </div>
          </section>

          <section class="section" aria-labelledby="s-policy" formGroupName="passwordPolicy">
            <div class="intro">
              <h2 id="s-policy" i18n="@@admin.platformSettings.policy.title">Password policy</h2>
              <p i18n="@@admin.platformSettings.policy.intro">
                Applies to new and changed local passwords; existing passwords stay valid.
              </p>
            </div>
            <div class="fields">
              <div class="row">
                <nz-form-item>
                  <nz-form-label nzFor="ps-min" i18n="@@admin.platformSettings.policy.min"
                    >Minimum length</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="tip">
                    <nz-input-number
                      nzId="ps-min"
                      formControlName="minLength"
                      [nzMin]="8"
                      [nzMax]="128"
                    />
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzFor="ps-max" i18n="@@admin.platformSettings.policy.max"
                    >Maximum length</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="tip">
                    <nz-input-number
                      nzId="ps-max"
                      formControlName="maxLength"
                      [nzMin]="16"
                      [nzMax]="1024"
                    />
                  </nz-form-control>
                </nz-form-item>
              </div>
              <p class="help" i18n="@@admin.platformSettings.policy.lengthHelp">
                Length beats composition rules: no digits or symbols are required.
              </p>
              <div class="toggle">
                <nz-switch id="ps-common" formControlName="rejectCommon" />
                <label for="ps-common" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.policy.common"
                    >Reject common passwords</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.policy.commonHelp"
                    >Refuses the 10,000 most common passwords, also with small variations.</span
                  >
                </label>
              </div>
              <div class="toggle">
                <nz-switch id="ps-email" formControlName="rejectContainingEmail" />
                <label for="ps-email" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.policy.email"
                    >Reject passwords containing the e-mail name</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.policy.emailHelp"
                    >"anna.nowak" may not appear in Anna's password.</span
                  >
                </label>
              </div>
            </div>
          </section>

          <section class="section" aria-labelledby="s-lock" formGroupName="lockout">
            <div class="intro">
              <h2 id="s-lock" i18n="@@admin.platformSettings.lockout.title">Lockout</h2>
              <p i18n="@@admin.platformSettings.lockout.intro">
                Consecutive failed sign-ins (password or MFA code) lock a local account.
              </p>
            </div>
            <div class="fields">
              <div class="row">
                <nz-form-item>
                  <nz-form-label nzFor="ps-fail" i18n="@@admin.platformSettings.lockout.failures"
                    >Failures before lockout</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="tip">
                    <nz-input-number
                      nzId="ps-fail"
                      formControlName="maxFailures"
                      [nzMin]="3"
                      [nzMax]="20"
                    />
                  </nz-form-control>
                </nz-form-item>
                <nz-form-item>
                  <nz-form-label nzFor="ps-lock" i18n="@@admin.platformSettings.lockout.firstLock"
                    >First lock (minutes)</nz-form-label
                  >
                  <nz-form-control [nzErrorTip]="tip">
                    <nz-input-number
                      nzId="ps-lock"
                      formControlName="lockMinutes"
                      [nzMin]="1"
                      [nzMax]="1440"
                    />
                  </nz-form-control>
                </nz-form-item>
              </div>
              <p class="help" i18n="@@admin.platformSettings.lockout.help">
                The lock doubles on every further lockout, up to 24 h
                <span class="num">({{ lockSeries() }})</span>. A successful sign-in or "Unlock
                account" on the user resets it.
              </p>
            </div>
          </section>

          <section class="section" aria-labelledby="s-reg">
            <div class="intro">
              <h2 id="s-reg" i18n="@@admin.platformSettings.registration.title">Registration</h2>
              <p i18n="@@admin.platformSettings.registration.intro">
                Who gets an account without being invited.
              </p>
            </div>
            <div class="fields">
              <nz-form-item>
                <nz-form-control [nzErrorTip]="tip">
                  <nz-radio-group
                    formControlName="registration"
                    aria-label="Registration"
                    i18n-aria-label="@@admin.platformSettings.registration.aria"
                  >
                    <label
                      nz-radio
                      nzValue="INVITE_ONLY"
                      i18n="@@admin.platformSettings.registration.inviteOnly"
                      >Invite only</label
                    >
                    <label nz-radio nzValue="OPEN" i18n="@@admin.platformSettings.registration.open"
                      >Open: anyone {{ provider() }} authenticates gets an account</label
                    >
                  </nz-radio-group>
                </nz-form-control>
              </nz-form-item>
              <nz-form-item>
                <nz-form-label
                  nzFor="ps-domains"
                  i18n="@@admin.platformSettings.registration.domains"
                  >Allowed e-mail domains</nz-form-label
                >
                <nz-form-control
                  nzExtra="With invite only, people from these domains may still sign in through the identity provider."
                  i18n-nzExtra="@@admin.platformSettings.registration.domainsHelp"
                  [nzErrorTip]="tip"
                >
                  <nz-select
                    nzId="ps-domains"
                    nzMode="tags"
                    formControlName="allowedEmailDomains"
                    [nzPlaceHolder]="domainPlaceholder"
                  />
                </nz-form-control>
              </nz-form-item>
              <div class="toggle">
                <nz-switch id="ps-sync" formControlName="syncGroupsFromIdentityProvider" />
                <label for="ps-sync" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.registration.sync"
                    >Synchronise groups from {{ provider() }} at sign-in</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.registration.syncHelp"
                    >Maps the OIDC groups claim onto groups with a matching identity provider
                    group.</span
                  >
                </label>
              </div>
            </div>
          </section>

          <section class="section" aria-labelledby="s-tokens">
            <div class="intro">
              <h2 id="s-tokens" i18n="@@admin.platformSettings.tokens.title">
                Personal access tokens
              </h2>
              <p i18n="@@admin.platformSettings.tokens.intro">
                Tokens people create for scripts and the API.
              </p>
            </div>
            <div class="fields">
              <nz-form-item>
                <nz-form-label nzFor="ps-pat" i18n="@@admin.platformSettings.tokens.maxDays"
                  >Maximum lifetime (days)</nz-form-label
                >
                <nz-form-control
                  [nzErrorTip]="tip"
                  nzExtra="Existing tokens keep their expiry."
                  i18n-nzExtra="@@admin.platformSettings.tokens.maxDaysHelp"
                >
                  <nz-input-number
                    nzId="ps-pat"
                    formControlName="personalTokenMaxDays"
                    [nzMin]="1"
                    [nzMax]="365"
                  />
                </nz-form-control>
              </nz-form-item>
            </div>
          </section>

          <section class="section" aria-labelledby="s-features">
            <div class="intro">
              <h2 id="s-features" i18n="@@admin.platformSettings.features.title">Features</h2>
              <p i18n="@@admin.platformSettings.features.intro">
                Optional tools projects may turn on for themselves.
              </p>
            </div>
            <div class="fields">
              <div class="toggle">
                <nz-switch id="ps-speckit" formControlName="specKitAllowed" />
                <label for="ps-speckit" class="t-body">
                  <span class="t-title" i18n="@@admin.platformSettings.features.specKit"
                    >Allow Spec Kit</span
                  >
                  <span class="t-help" i18n="@@admin.platformSettings.features.specKitHelp"
                    >Projects may turn on Spec Kit (specify, plan and tasks steps, the constitution).
                    When off, no project can run Spec Kit steps, whatever its own setting says; their
                    settings and constitutions are kept.</span
                  >
                </label>
              </div>
            </div>
          </section>
          <ng-template #tip let-control>{{
            control.errors?.server || text.rangeError
          }}</ng-template>

          <bo-action-bar
            [dirty]="dirty()"
            [saving]="saving()"
            [canSave]="valid()"
            saveLabel="Save settings"
            i18n-saveLabel="@@admin.platformSettings.save"
            (save)="save()"
          >
            <button
              nz-button
              type="button"
              [disabled]="!dirty()"
              (click)="reset()"
              i18n="@@admin.platformSettings.discard"
            >
              Discard changes
            </button>
          </bo-action-bar>
        </form>
        @defer (on viewport) {
          <app-platform-budget-panel [scrollIntoView]="section() === 'budget'" />
        } @placeholder {
          <div id="budget" class="budget-ph" aria-hidden="true"></div>
        }
      }
    </div>
  `,
  styles: [
    `
      .budget-ph {
        height: 240px;
      }
      .sections {
        max-width: 1040px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .section {
        display: grid;
        grid-template-columns: minmax(200px, 300px) minmax(0, 1fr);
        gap: 24px;
        padding: 20px;
        border-bottom: 1px solid var(--bo-line);
      }
      @media (max-width: 860px) {
        .section {
          grid-template-columns: 1fr;
          gap: 8px;
        }
      }
      h2 {
        margin: 0 0 4px;
        font-size: 14px;
        font-weight: 600;
      }
      .intro p {
        margin: 0;
        font-size: 12.5px;
        color: var(--bo-ink-3);
      }
      .fields {
        display: flex;
        flex-direction: column;
        gap: 14px;
        min-width: 0;
      }
      .fields nz-form-item {
        margin-bottom: 0;
      }
      .row {
        display: flex;
        gap: 16px;
        flex-wrap: wrap;
      }
      .toggle {
        display: flex;
        gap: 12px;
        align-items: flex-start;
      }
      .toggle nz-switch {
        margin-top: 1px;
      }
      .t-body {
        display: flex;
        flex-direction: column;
        gap: 2px;
        cursor: pointer;
      }
      .t-title {
        font-size: 13px;
        font-weight: 500;
      }
      .t-help,
      .help {
        font-size: 12px;
        color: var(--bo-ink-3);
        margin: 0;
      }
      .warn {
        margin: 0;
        padding: 8px 12px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-you-soft);
        box-shadow: inset 0 0 0 1px var(--bo-you);
        color: var(--bo-ink);
        font-size: 12.5px;
      }
      bo-action-bar {
        border-radius: 0 0 var(--bo-radius-card) var(--bo-radius-card);
        overflow: hidden;
      }
    `,
  ],
})
export class PlatformSettingsPageComponent implements HasUnsavedChanges {
  private readonly api = inject(AdminApi);
  private readonly fb = inject(FormBuilder);
  private readonly message = inject(NzMessageService);
  private readonly notify = inject(NotifyService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly authConfig = inject(AuthConfigService);

  /** `?section=budget` scrolls to the platform budget. */
  readonly section = input<string | undefined>();
  readonly loading = signal(true);
  readonly saving = signal(false);
  private saved: PlatformSettings = DEFAULT_SETTINGS;

  readonly form = this.fb.nonNullable.group({
    localLoginEnabled: [true],
    mfaRequiredForLocal: [false],
    passwordPolicy: this.fb.nonNullable.group({
      minLength: [12, [Validators.required, Validators.min(8), Validators.max(128)]],
      maxLength: [128, [Validators.required, Validators.min(16), Validators.max(1024)]],
      rejectCommon: [true],
      rejectContainingEmail: [true],
    }),
    lockout: this.fb.nonNullable.group({
      maxFailures: [5, [Validators.required, Validators.min(3), Validators.max(20)]],
      lockMinutes: [15, [Validators.required, Validators.min(1), Validators.max(1440)]],
    }),
    registration: this.fb.nonNullable.control<'OPEN' | 'INVITE_ONLY'>('INVITE_ONLY'),
    allowedEmailDomains: this.fb.nonNullable.control<string[]>([]),
    syncGroupsFromIdentityProvider: [false],
    personalTokenMaxDays: [90, [Validators.required, Validators.min(1), Validators.max(365)]],
    specKitAllowed: [true],
  });
  private readonly value = toSignal(this.form.valueChanges, { initialValue: null });
  readonly dirty = computed(() => {
    this.value();
    return this.form.dirty;
  });
  readonly valid = computed(() => {
    this.value();
    const p = this.form.controls.passwordPolicy.getRawValue();
    return this.form.valid && p.minLength <= p.maxLength;
  });
  readonly provider = computed(() => providerLabel(this.authConfig.config()?.providerName));
  readonly oidc = computed(() => this.authConfig.config()?.methods?.oidc !== false);
  readonly warnings = computed(() => {
    this.value();
    return settingsWarnings(this.form.getRawValue(), {
      oidc: this.oidc(),
      provider: this.provider(),
    });
  });
  readonly lockSeries = computed(() => {
    this.value();
    return lockSeriesText(this.form.controls.lockout.controls.lockMinutes.value);
  });

  readonly domainPlaceholder = 'example.com'; // i18n-ignore: a sample domain
  readonly text = {
    rangeError: $localize`:@@admin.platformSettings.rangeError:Enter a value in the allowed range.`,
  };

  constructor() {
    inject(BreadcrumbService).set([
      {
        label: $localize`:@@admin.platformSettings.breadcrumb.admin:Administration`,
        link: '/admin',
      },
      { label: $localize`:@@admin.platformSettings.breadcrumb.settings:Platform settings` },
    ]);
    this.authConfig.load().subscribe();
    this.api.settings().subscribe({
      next: (s) => {
        this.apply(s);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  hasUnsavedChanges(): boolean {
    return this.form.dirty;
  }

  reset(): void {
    this.apply(this.saved);
  }

  save(): void {
    if (!this.valid()) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.pendingSaves
      .track(this.api.updateSettings(toSettings(this.saved, this.form.getRawValue())))
      .subscribe({
        next: (s) => {
          this.saving.set(false);
          this.apply(s);
          this.message.success($localize`:@@admin.platformSettings.saved:Platform settings saved`);
        },
        error: (err: unknown) => {
          this.saving.set(false);
          reportFormError(
            this.notify,
            this.form,
            err,
            $localize`:@@admin.platformSettings.saveFailed:Could not save the settings`,
          );
        },
      });
  }

  private apply(s: PlatformSettings): void {
    this.saved = s;
    const d = DEFAULT_SETTINGS;
    this.form.reset({
      localLoginEnabled: s.localLoginEnabled ?? d.localLoginEnabled,
      mfaRequiredForLocal: s.mfaRequiredForLocal ?? d.mfaRequiredForLocal,
      passwordPolicy: { ...d.passwordPolicy, ...s.passwordPolicy },
      lockout: { ...d.lockout, ...s.lockout },
      registration: s.registration ?? 'INVITE_ONLY',
      allowedEmailDomains: s.allowedEmailDomains ?? [],
      syncGroupsFromIdentityProvider: s.syncGroupsFromIdentityProvider ?? false,
      personalTokenMaxDays: s.personalTokenMaxDays ?? 90,
      specKitAllowed: s.specKitAllowed ?? d.specKitAllowed,
    });
  }
}
