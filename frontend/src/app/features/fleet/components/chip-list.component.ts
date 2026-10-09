import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';

let nextId = 0;

/**
 * Editable list of short values as chips (host patterns, tool patterns, secret names). Type and
 * press Enter (or comma) to add; Backspace in the empty field removes the last chip. `validate`
 * refuses a value with a reason shown under the field; `invalid` marks existing chips (by index)
 * with the reason as tooltip and screen-reader text. `locked` chips come from the level above and
 * cannot be removed.
 */
@Component({
  selector: 'app-chip-list',
  imports: [NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="box" [class.readonly]="readonly()" [class.has-error]="!!error()">
      <ul class="chips" [attr.aria-label]="label()">
        @for (v of locked(); track v) {
          <li class="chip locked" [attr.title]="lockedHint()">
            <nz-icon nzType="lock" aria-hidden="true" />
            <span class="text">{{ v }}</span>
            <span class="sr-only">({{ lockedHint() }})</span>
          </li>
        }
        @for (v of values(); track $index; let i = $index) {
          <li class="chip" [class.bad]="!!invalid()[i]" [attr.title]="invalid()[i] || null">
            @if (invalid()[i]) {
              <nz-icon nzType="warning" aria-hidden="true" />
            }
            <span class="text">{{ v }}</span>
            @if (invalid()[i]) {
              <span class="sr-only">: {{ invalid()[i] }}</span>
            }
            @if (!readonly()) {
              <button
                type="button"
                class="x"
                [attr.aria-label]="removeLabel(v)"
                (click)="remove(i)"
              >
                <nz-icon nzType="close" />
              </button>
            }
          </li>
        }
      </ul>
      @if (!readonly()) {
        <input
          #field
          class="entry"
          [id]="inputId"
          [attr.aria-label]="addLabel()"
          [attr.aria-describedby]="error() ? inputId + '-err' : null"
          [attr.aria-invalid]="error() ? 'true' : null"
          [placeholder]="values().length || locked().length ? '' : placeholder()"
          [value]="draft()"
          (input)="onInput($any($event.target).value)"
          (keydown)="onKey($event)"
          (blur)="commit()"
          autocomplete="off"
          spellcheck="false"
        />
      } @else if (!values().length && !locked().length) {
        <span class="none">{{ emptyText() }}</span>
      }
    </div>
    @if (error()) {
      <div class="err" [id]="inputId + '-err'" role="alert">{{ error() }}</div>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .box {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px;
        min-height: 32px;
        padding: 3px 6px;
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-control);
        background: var(--bo-surface);
        cursor: text;
      }
      .box:focus-within {
        border-color: var(--bo-accent);
        box-shadow: 0 0 0 2px var(--bo-accent-soft);
      }
      .box.readonly {
        background: var(--bo-surface-2);
        cursor: default;
      }
      .box.has-error {
        border-color: var(--bo-blocked);
      }
      .chips {
        display: contents;
        list-style: none;
        margin: 0;
        padding: 0;
      }
      .chip {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        height: 22px;
        padding: 0 4px 0 8px;
        border-radius: var(--bo-radius-chip);
        background: var(--bo-surface-3);
        color: var(--bo-ink);
        font-family: var(--bo-font-mono);
        font-size: 11.5px;
        max-width: 100%;
      }
      .chip .text {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .chip.locked {
        padding-right: 8px;
        background: transparent;
        box-shadow: inset 0 0 0 1px var(--bo-line-strong);
        color: var(--bo-ink-2);
      }
      .chip.locked nz-icon {
        font-size: 10px;
      }
      .chip.bad {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .x {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 16px;
        height: 16px;
        padding: 0;
        border: 0;
        border-radius: 50%;
        background: transparent;
        color: inherit;
        font-size: 9px;
        cursor: pointer;
      }
      .x:hover {
        background: var(--bo-line);
      }
      .x:focus-visible {
        outline: 2px solid var(--bo-accent);
      }
      .entry {
        flex: 1 1 120px;
        min-width: 120px;
        height: 24px;
        border: 0;
        outline: 0;
        background: transparent;
        color: var(--bo-ink);
        font-family: var(--bo-font-mono);
        font-size: 12px;
      }
      .entry::placeholder {
        color: var(--bo-ink-3);
        font-family: var(--bo-font-ui);
      }
      .none {
        font-size: 12px;
        color: var(--bo-ink-3);
        padding: 0 2px;
      }
      .err {
        margin-top: 4px;
        font-size: 12px;
        color: var(--bo-blocked-ink);
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0, 0, 0, 0);
        white-space: nowrap;
      }
    `,
  ],
})
export class ChipListComponent {
  readonly values = input<readonly string[]>([]);
  readonly label = input($localize`:@@fleet.chips.values:Values`);
  readonly placeholder = input($localize`:@@fleet.chips.placeholder:Type and press Enter`);
  readonly emptyText = input($localize`:@@fleet.chips.none:None`);
  readonly readonly = input(false);
  readonly locked = input<readonly string[]>([]);
  readonly lockedHint = input(
    $localize`:@@fleet.policyEditor.lockedHint:Set by the level above; cannot be removed here`,
  );
  /** Reason a new value is refused, or null. */
  readonly validate = input<((value: string, current: readonly string[]) => string | null) | null>(
    null,
  );
  /** Reasons per index for chips already in the list. */
  readonly invalid = input<Record<number, string>>({});
  /** Lower-case the value (host names). */
  readonly lowercase = input(false);
  readonly valuesChange = output<string[]>();

  readonly inputId = `bo-chips-${nextId++}`;
  readonly draft = signal('');
  readonly refused = signal<string | null>(null);
  readonly error = computed(() => this.refused());
  readonly addLabel = computed(() => {
    const list = this.label();
    return $localize`:@@fleet.chips.addTo:Add to ${list}:list:`;
  });

  removeLabel(value: string): string {
    return $localize`:@@fleet.chips.remove:Remove ${value}:value:`;
  }

  onInput(v: string): void {
    this.refused.set(null);
    if (/[,\n]/.test(v)) {
      const parts = v.split(/[,\n]/);
      this.draft.set(parts.pop() ?? '');
      for (const p of parts) this.add(p);
    } else this.draft.set(v);
  }

  onKey(e: KeyboardEvent): void {
    if (e.key === 'Enter') {
      e.preventDefault();
      this.commit();
    } else if (e.key === 'Backspace' && !this.draft() && this.values().length) {
      this.remove(this.values().length - 1);
    }
  }

  commit(): void {
    const v = this.draft();
    if (!v.trim()) return;
    if (this.add(v)) this.draft.set('');
  }

  private add(raw: string): boolean {
    const v = this.lowercase() ? raw.trim().toLowerCase() : raw.trim();
    if (!v) return true;
    if (this.values().includes(v) || this.locked().includes(v)) {
      this.refused.set($localize`:@@fleet.chips.duplicate:${v}:value: is already in the list.`);
      return false;
    }
    const reason = this.validate()?.(v, this.values()) ?? null;
    if (reason) {
      this.refused.set(reason);
      return false;
    }
    this.refused.set(null);
    this.valuesChange.emit([...this.values(), v]);
    return true;
  }

  remove(index: number): void {
    this.valuesChange.emit(this.values().filter((_, i) => i !== index));
  }
}
