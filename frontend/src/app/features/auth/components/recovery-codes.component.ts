import { ChangeDetectionStrategy, Component, input, output, signal } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { FormsModule } from '@angular/forms';
import { copyToClipboard } from '../../../core/ui/clipboard';
import { downloadText, recoveryCodesFileName, recoveryCodesText } from '../data/recovery-codes';

/**
 * Recovery codes shown once: a grid of mono codes, "Copy codes", "Download .txt" and an "I saved
 * them" confirmation that enables the continue button.
 */
@Component({
  selector: 'app-recovery-codes',
  imports: [FormsModule, NzButtonModule, NzCheckboxModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="box">
      <ol class="codes" aria-label="Recovery codes" i18n-aria-label="@@auth.codes.label">
        @for (c of codes(); track c) {
          <li>
            <span class="mono">{{ c }}</span>
          </li>
        }
      </ol>
      <div class="tools">
        <button nz-button type="button" nzSize="small" (click)="copy()">
          <nz-icon [nzType]="copied() ? 'check' : 'copy'" />
          @if (copied()) {
            <ng-container i18n="@@auth.enrol.copied">Copied</ng-container>
          } @else {
            <ng-container i18n="@@auth.codes.copy">Copy codes</ng-container>
          }
        </button>
        <button nz-button type="button" nzSize="small" (click)="download()">
          <nz-icon nzType="download" /> <span i18n="@@auth.codes.download">Download .txt</span>
        </button>
      </div>
    </div>
    <p class="note" i18n="@@auth.codes.note">
      This is the only time the codes are shown. Each one signs you in once when your authenticator
      app is not at hand.
    </p>
    <label nz-checkbox class="saved" [(ngModel)]="saved" i18n="@@auth.codes.saved"
      >I saved these codes somewhere safe</label
    >
    <button
      nz-button
      nzType="primary"
      class="continue"
      type="button"
      [disabled]="!saved()"
      (click)="done.emit()"
    >
      {{ doneLabel() }}
    </button>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .box {
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface-2);
        padding: var(--bo-space-4);
      }
      .codes {
        list-style: none;
        margin: 0;
        padding: 0;
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 6px var(--bo-space-4);
        counter-reset: code;
      }
      .codes li {
        counter-increment: code;
        display: flex;
        align-items: baseline;
        gap: 8px;
      }
      .codes li::before {
        content: counter(code, decimal-leading-zero);
        font-family: var(--bo-font-mono);
        font-size: 10px;
        color: var(--bo-ink-3);
        width: 16px;
      }
      .codes .mono {
        font-size: 14px;
        letter-spacing: 0.04em;
        color: var(--bo-ink);
        user-select: all;
      }
      .tools {
        display: flex;
        gap: var(--bo-space-2);
        margin-top: var(--bo-space-4);
        padding-top: var(--bo-space-3);
        border-top: 1px solid var(--bo-line);
      }
      .note {
        margin: var(--bo-space-3) 0 0;
        font-size: var(--bo-fs-meta);
        color: var(--bo-ink-2);
      }
      .saved {
        display: flex;
        margin-top: var(--bo-space-4);
      }
      .continue {
        margin-top: var(--bo-space-4);
        width: 100%;
        height: 40px;
      }
    `,
  ],
})
export class RecoveryCodesComponent {
  readonly codes = input.required<string[]>();
  readonly email = input<string | null | undefined>(null);
  readonly doneLabel = input($localize`:@@auth.codes.continue:Continue`);
  readonly done = output<void>();

  readonly saved = signal(false);
  readonly copied = signal(false);

  async copy(): Promise<void> {
    this.copied.set(await copyToClipboard(this.codes().join('\n')));
    setTimeout(() => this.copied.set(false), 2000);
  }

  download(): void {
    downloadText(
      recoveryCodesFileName(this.email()),
      recoveryCodesText(this.codes(), this.email()),
    );
  }
}
