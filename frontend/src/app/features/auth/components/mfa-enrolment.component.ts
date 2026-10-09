import { HttpErrorResponse } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { LocalAuthApi } from '../../../core/api/local-auth.service';
import type { TotpEnrolment } from '../../../core/api/types';
import { copyToClipboard } from '../../../core/ui/clipboard';
import { CodeInputComponent } from '../../../core/ui/relay/code-input.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { AUTH_FORM_STYLES } from './auth-styles';

/** `JBSWY3DPEHPK3PXP` -> `JBSW Y3DP EHPK 3PXP` (easier to type). */
export function groupSecret(secret: string): string {
  return secret.replace(/\s+/g, '').replace(/(.{4})(?=.)/g, '$1 ');
}

/** The QR SVG from the API as an image URL (rendered by <img>, so markup never runs in the page). */
export function qrImageUrl(svg: string | null | undefined): string | null {
  if (!svg || !svg.trim().startsWith('<svg')) return null;
  return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
}

/**
 * TOTP enrolment: starts it (`POST /me/mfa/totp`), shows the QR code and the secret with copy, and
 * confirms it with a 6-digit code (`PUT`). Emits the recovery codes; the host shows them once.
 */
@Component({
  selector: 'app-mfa-enrolment',
  imports: [NzButtonModule, NzIconModule, CodeInputComponent, GlyphComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <ol class="steps">
      <li>
        <span class="num" aria-hidden="true">1</span>
        <div class="body">
          <h2 class="step-title" i18n="@@auth.enrol.scanTitle">Scan the QR code</h2>
          <p class="field-note" i18n="@@auth.enrol.scanHint">
            Use an authenticator app such as 1Password, Google Authenticator, Microsoft
            Authenticator or Aegis.
          </p>
          <div class="qr-row">
            <div class="qr" [class.loading]="!enrolment()">
              @if (qr(); as src) {
                <img
                  [src]="src"
                  width="148"
                  height="148"
                  alt="QR code to add Back Office to your authenticator app"
                  i18n-alt="@@auth.enrol.qrAlt"
                />
              } @else if (startError()) {
                <span class="qr-error" i18n="@@auth.enrol.noQr">No QR code</span>
              }
            </div>
            <div class="secret">
              <span class="label" i18n="@@auth.enrol.orKey">Or enter this key</span>
              @if (enrolment()?.secret; as secret) {
                <code class="key" aria-label="Setup key" i18n-aria-label="@@auth.enrol.keyLabel">{{
                  grouped()
                }}</code>
                <button nz-button nzSize="small" type="button" (click)="copySecret(secret)">
                  <nz-icon [nzType]="copied() ? 'check' : 'copy'" />
                  @if (copied()) {
                    <ng-container i18n="@@auth.enrol.copied">Copied</ng-container>
                  } @else {
                    <ng-container i18n="@@auth.enrol.copyKey">Copy key</ng-container>
                  }
                </button>
              } @else {
                <span class="key ph" aria-hidden="true"></span>
              }
              <span class="field-note" i18n="@@auth.enrol.totpNote"
                >Time-based, 6 digits, every 30 seconds.</span
              >
            </div>
          </div>
          @if (startError()) {
            <div class="banner error" role="alert">
              <bo-glyph holder="blocked" [size]="14" />
              <span class="grow">{{ startError() }}</span>
              <button
                nz-button
                nzSize="small"
                type="button"
                (click)="start()"
                i18n="@@auth.login.tryAgain"
              >
                Try again
              </button>
            </div>
          }
        </div>
      </li>
      <li>
        <span class="num" aria-hidden="true">2</span>
        <div class="body">
          <h2 class="step-title" i18n="@@auth.enrol.codeTitle">Enter the code from the app</h2>
          <form (submit)="$event.preventDefault(); confirm()" novalidate>
            <bo-code-input
              #codeInput
              label="Code from your authenticator app"
              i18n-label="@@auth.enrol.codeLabel"
              describedBy="enrol-code-help"
              [invalid]="!!codeError()"
              [disabled]="!enrolment() || confirming()"
              [(value)]="code"
              (completed)="confirm()"
            />
            <p
              class="field-note"
              id="enrol-code-help"
              [class.field-error]="!!codeError()"
              role="status"
            >
              @if (codeError(); as e) {
                {{ e }}
              } @else {
                <ng-container i18n="@@auth.enrol.codeHint"
                  >This checks that the app and Back Office agree before two-factor authentication
                  is turned on.</ng-container
                >
              }
            </p>
            <button
              nz-button
              nzType="primary"
              class="submit"
              type="submit"
              [nzLoading]="confirming()"
              [disabled]="code().length !== 6 || !enrolment()"
              i18n="@@auth.enrol.submit"
            >
              Turn on two-factor authentication
            </button>
          </form>
        </div>
      </li>
    </ol>
  `,
  styles: [
    AUTH_FORM_STYLES,
    `
      :host {
        display: block;
      }
      .steps {
        list-style: none;
        margin: var(--bo-space-4) 0 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: var(--bo-space-5);
      }
      .steps > li {
        display: grid;
        grid-template-columns: 24px 1fr;
        gap: var(--bo-space-3);
      }
      .num {
        width: 24px;
        height: 24px;
        border-radius: 50%;
        border: 1px solid var(--bo-line-strong);
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-family: var(--bo-font-mono);
        font-size: 12px;
        color: var(--bo-ink-2);
        background: var(--bo-surface);
      }
      .body {
        min-width: 0;
      }
      .step-title {
        margin: 2px 0 2px;
        font-size: var(--bo-fs-body);
        font-weight: 600;
      }
      .qr-row {
        display: flex;
        gap: var(--bo-space-4);
        align-items: flex-start;
        margin-top: var(--bo-space-3);
        flex-wrap: wrap;
      }
      .qr {
        width: 164px;
        height: 164px;
        box-sizing: border-box;
        padding: 8px;
        border-radius: var(--bo-radius-card);
        background: #ffffff;
        border: 1px solid var(--bo-line);
        display: flex;
        align-items: center;
        justify-content: center;
        flex: none;
      }
      .qr.loading {
        background: var(--bo-surface-3);
      }
      .qr img {
        display: block;
        width: 148px;
        height: 148px;
        image-rendering: pixelated;
      }
      .qr-error {
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-3);
      }
      .secret {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: var(--bo-space-2);
        min-width: 0;
        flex: 1;
      }
      .secret .label {
        font-size: var(--bo-fs-dense);
        font-weight: 500;
      }
      .key {
        font-family: var(--bo-font-mono);
        font-size: 13px;
        letter-spacing: 0.04em;
        padding: 6px 8px;
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        word-break: normal;
        overflow-wrap: anywhere;
        color: var(--bo-ink);
        user-select: all;
      }
      .key.ph {
        width: 180px;
        height: 18px;
        background: var(--bo-surface-3);
      }
      bo-code-input {
        margin-top: var(--bo-space-3);
      }
      .field-note {
        margin-top: var(--bo-space-2);
      }
      .grow {
        flex: 1;
      }
    `,
  ],
})
export class MfaEnrolmentComponent {
  private readonly api = inject(LocalAuthApi);

  readonly enrolled = output<string[]>();

  readonly enrolment = signal<TotpEnrolment | null>(null);
  readonly startError = signal<string | null>(null);
  readonly code = signal('');
  readonly codeError = signal<string | null>(null);
  readonly confirming = signal(false);
  readonly copied = signal(false);
  private readonly codeInput = viewChild<CodeInputComponent>('codeInput');

  readonly qr = computed(() => qrImageUrl(this.enrolment()?.qrSvg));
  readonly grouped = computed(() => groupSecret(this.enrolment()?.secret ?? ''));

  constructor() {
    this.start();
  }

  start(): void {
    this.startError.set(null);
    this.api.startTotp().subscribe({
      next: (e) => {
        this.enrolment.set(e);
        // After the cells are enabled by the next change detection.
        setTimeout(() => this.codeInput()?.focus(), 0);
      },
      error: () =>
        this.startError.set(
          $localize`:@@auth.enrol.startFailed:Two-factor set-up could not start. Try again in a moment.`,
        ),
    });
  }

  async copySecret(secret: string): Promise<void> {
    this.copied.set(await copyToClipboard(secret));
    setTimeout(() => this.copied.set(false), 2000);
  }

  confirm(): void {
    const code = this.code();
    if (code.length !== 6 || this.confirming()) return;
    this.confirming.set(true);
    this.codeError.set(null);
    this.api.confirmTotp(code).subscribe({
      next: (r) => {
        this.confirming.set(false);
        this.enrolled.emit(r.codes ?? []);
      },
      error: (e: unknown) => {
        this.confirming.set(false);
        this.codeError.set(
          e instanceof HttpErrorResponse && e.status === 400
            ? $localize`:@@auth.enrol.mismatch:That code did not match. Wait for the next code in the app and enter it; check that the time on your device is set automatically.`
            : $localize`:@@auth.enrol.checkFailed:The code could not be checked. Try again.`,
        );
        this.code.set('');
        queueMicrotask(() => this.codeInput()?.focus());
      },
    });
  }
}
