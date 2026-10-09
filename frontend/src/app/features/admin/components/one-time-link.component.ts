import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzMessageService } from 'ng-zorro-antd/message';
import { copyToClipboard } from '../../../core/ui/clipboard';
import { absoluteTime } from '../../../core/ui/relay/format';
import { timeUntil } from '../data/local-accounts';

/**
 * A link to hand over (set-password or sign-in): read-only mono field, "Copy link" and one line on
 * what it does and when it expires. Back Office sends no e-mail.
 */
@Component({
  selector: 'app-one-time-link',
  imports: [NzButtonModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="box" role="group" [attr.aria-label]="label()">
      <div class="head">
        <span class="title">{{ label() }}</span>
        @if (expiresAt()) {
          <span class="exp" [attr.title]="abs()" i18n="@@admin.oneTimeLink.expires"
            >expires {{ rel() }} · {{ abs() }}</span
          >
        }
      </div>
      <div class="row">
        <input
          class="url"
          [value]="url()"
          readonly
          [attr.aria-label]="label()"
          [attr.data-link]="kind()"
          (focus)="select($event)"
        />
        <button nz-button nzType="primary" type="button" (click)="copy()">
          <nz-icon nzType="copy" /> <span i18n="@@admin.oneTimeLink.copy">Copy link</span>
        </button>
      </div>
      <p class="hint">{{ hintText() }}</p>
    </div>
  `,
  styles: [
    `
      .box {
        padding: 12px 14px;
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        background: var(--bo-surface-2);
      }
      .head {
        display: flex;
        align-items: baseline;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
        margin-bottom: 8px;
      }
      .title {
        font-size: 13px;
        font-weight: 600;
      }
      .exp {
        font-size: 12px;
        color: var(--bo-ink-3);
        font-variant-numeric: tabular-nums;
      }
      .row {
        display: flex;
        gap: 8px;
      }
      .url {
        flex: 1;
        min-width: 0;
        height: var(--bo-control-h);
        padding: 0 10px;
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
        color: var(--bo-ink);
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .url:focus {
        outline: none;
        border-color: var(--bo-accent);
        box-shadow: 0 0 0 2px var(--bo-accent-soft);
      }
      .hint {
        margin: 8px 0 0;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
    `,
  ],
})
export class OneTimeLinkComponent {
  private readonly message = inject(NzMessageService);

  readonly url = input.required<string>();
  readonly label = input($localize`:@@admin.oneTimeLink.defaultLabel:Set-password link`);
  readonly expiresAt = input<string | null | undefined>(null);
  /** First name of the recipient ("Anna"). */
  readonly name = input('');
  /** Overrides the default hint. */
  readonly hint = input<string | null>(null);

  /** `set-password` or `sign-in` (from the URL), for tests and styling. */
  readonly kind = computed(() =>
    this.url().includes('/set-password/') ? 'set-password' : 'sign-in',
  );
  readonly rel = computed(() => timeUntil(this.expiresAt()));
  readonly abs = computed(() => absoluteTime(this.expiresAt()));
  readonly hintText = computed(() => {
    if (this.hint()) return this.hint()!;
    const who = this.name();
    if (this.expiresAt()) {
      const rel = this.rel();
      return who
        ? $localize`:@@admin.oneTimeLink.hintNamed:Hand this link to ${who}:name:; it works once and expires ${rel}:expires:. Back Office sends no e-mail.`
        : $localize`:@@admin.oneTimeLink.hint:Hand this link to the user; it works once and expires ${rel}:expires:. Back Office sends no e-mail.`;
    }
    return who
      ? $localize`:@@admin.oneTimeLink.hintNamed24h:Hand this link to ${who}:name:; it works once and expires in 24 h. Back Office sends no e-mail.`
      : $localize`:@@admin.oneTimeLink.hint24h:Hand this link to the user; it works once and expires in 24 h. Back Office sends no e-mail.`;
  });

  select(e: FocusEvent): void {
    (e.target as HTMLInputElement).select();
  }

  async copy(): Promise<void> {
    if (await copyToClipboard(this.url()))
      this.message.success($localize`:@@admin.oneTimeLink.copied:Link copied`);
    else
      this.message.warning(
        $localize`:@@admin.oneTimeLink.clipboardUnavailable:Clipboard unavailable; select the link and copy it`,
      );
  }
}
