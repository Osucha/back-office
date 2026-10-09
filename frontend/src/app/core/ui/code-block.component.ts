import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { copyToClipboard } from './clipboard';

/**
 * A labelled, copyable snippet (commands, config files, one-time tokens): mono text in a quiet
 * panel with a "Copy" button that turns into "Copied" for two seconds.
 *
 *   <app-code-block label="values.yaml" [code]="values" />
 */
@Component({
  selector: 'app-code-block',
  imports: [NzButtonModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="block" [class.secret]="secret()">
      @if (label()) {
        <figcaption>
          <span class="label">{{ label() }}</span>
          <button
            nz-button
            nzSize="small"
            [nzType]="primary() ? 'primary' : 'default'"
            type="button"
            (click)="copy()"
            [attr.aria-label]="copyLabel()"
          >
            <nz-icon [nzType]="copied() ? 'check' : 'copy'" />
            @if (copied()) {
              <ng-container i18n="@@core.code.copied">Copied</ng-container>
            } @else {
              <ng-container i18n="@@core.code.copy">Copy</ng-container>
            }
          </button>
        </figcaption>
      }
      <pre tabindex="0" [attr.aria-label]="label() || snippet"><code>{{ code() }}</code></pre>
    </figure>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .block {
        margin: 0;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface-2);
        overflow: hidden;
      }
      .block.secret {
        border-color: var(--bo-you);
        background: var(--bo-you-soft);
      }
      figcaption {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        padding: 4px 6px 4px 12px;
        border-bottom: 1px solid var(--bo-line);
        background: var(--bo-surface);
      }
      .label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      pre {
        margin: 0;
        padding: 10px 12px;
        overflow: auto;
        max-height: 320px;
        font-family: var(--bo-font-mono);
        font-size: 12px;
        line-height: 1.55;
        color: var(--bo-ink);
        white-space: pre;
      }
      pre:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .secret pre {
        white-space: pre-wrap;
        word-break: break-all;
        font-size: 13px;
      }
    `,
  ],
})
export class CodeBlockComponent {
  private readonly message = inject(NzMessageService);
  readonly code = input('');
  readonly label = input('');
  /** One-time secrets: amber frame, wraps long tokens. */
  readonly secret = input(false);
  readonly primary = input(false);
  readonly copied = signal(false);
  readonly snippet = $localize`:@@core.code.snippet:Snippet`;
  readonly copyLabel = computed(() =>
    this.copied()
      ? $localize`:@@core.code.copiedLabel:Copied ${this.label()}:label:`
      : $localize`:@@core.code.copyLabel:Copy ${this.label()}:label:`,
  );
  private timer: ReturnType<typeof setTimeout> | null = null;

  async copy(): Promise<void> {
    if (await copyToClipboard(this.code())) {
      this.copied.set(true);
      if (this.timer) clearTimeout(this.timer);
      this.timer = setTimeout(() => this.copied.set(false), 2000);
    } else
      this.message.warning(
        $localize`:@@core.code.clipboardUnavailable:Clipboard unavailable; select the text and copy it manually`,
      );
  }
}
