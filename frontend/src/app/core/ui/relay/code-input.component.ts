import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  input,
  model,
  output,
  signal,
  untracked,
  viewChildren,
} from '@angular/core';
import {
  applyBackspace,
  applyDelete,
  applyInput,
  applyPaste,
  cellsOf,
  completeCode,
  firstEmpty,
  type CodeEdit,
} from './code-input';

/**
 * One-time code input: `length` single-digit cells (default 6) that behave like one field.
 *
 * - typing a digit fills the cell and moves on; non-digits are ignored;
 * - Backspace clears the cell, or the previous one when the cell is empty; ←/→, Home/End move;
 * - pasting (or the browser's one-time-code autofill) of a full code fills every cell;
 * - every cell has its own `aria-label` ("Digit 2 of 6"); the group carries `label`;
 * - `(completed)` fires with the code once the last cell is filled by the user.
 *
 *   <bo-code-input label="Authentication code" [(value)]="code" (completed)="submit($event)" />
 *
 * Import from this file (it is not in the barrel, so pages that do not use it do not load it).
 */
@Component({
  selector: 'bo-code-input',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'bo-code-input' },
  template: `
    <div
      class="cells"
      role="group"
      [attr.aria-label]="label()"
      [attr.aria-describedby]="describedBy()"
      [class.invalid]="invalid()"
    >
      @for (c of cells(); track $index; let i = $index) {
        @if (groupAt() && i === groupAt()) {
          <span class="sep" aria-hidden="true"></span>
        }
        <input
          #cell
          class="cell"
          type="text"
          inputmode="numeric"
          pattern="[0-9]*"
          [attr.autocomplete]="i === 0 ? 'one-time-code' : 'off'"
          [attr.name]="i === 0 ? name() : null"
          [attr.aria-label]="digitLabel(i)"
          [attr.aria-invalid]="invalid() || null"
          [class.filled]="!!c"
          [disabled]="disabled()"
          [value]="c"
          (keydown)="onKeydown($event, i)"
          (input)="onInput($event, i)"
          (paste)="onPaste($event, i)"
          (focus)="onFocus($event, i)"
        />
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: inline-block;
      }
      .cells {
        display: flex;
        align-items: center;
        gap: var(--bo-space-2);
      }
      .sep {
        width: 8px;
        height: 2px;
        border-radius: 1px;
        background: var(--bo-line-strong);
        flex: none;
      }
      .cell {
        width: 44px;
        height: 52px;
        padding: 0;
        text-align: center;
        font-family: var(--bo-font-mono);
        font-size: 22px;
        font-weight: 500;
        font-variant-numeric: tabular-nums;
        color: var(--bo-ink);
        caret-color: var(--bo-accent);
        background: var(--bo-surface);
        border: 1px solid var(--bo-line-strong);
        border-radius: var(--bo-radius-control);
        outline: none;
        transition:
          border-color var(--bo-dur-fast) var(--bo-ease),
          box-shadow var(--bo-dur-fast) var(--bo-ease);
      }
      .cell.filled {
        border-color: var(--bo-ink-3);
      }
      .cell:focus {
        border-color: var(--bo-accent);
        box-shadow: 0 0 0 3px var(--bo-accent-soft);
      }
      .cell:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .cell:disabled {
        background: var(--bo-surface-2);
        color: var(--bo-ink-3);
      }
      .invalid .cell {
        border-color: var(--bo-blocked);
      }
      @media (max-width: 420px) {
        .cell {
          width: 38px;
          height: 46px;
          font-size: 19px;
        }
        .cells {
          gap: 6px;
        }
      }
    `,
  ],
})
export class CodeInputComponent {
  readonly length = input(6);
  /** Accessible name of the group, e.g. "Authentication code". */
  /** "Digit 2 of 6" for each cell. */
  digitLabel(i: number): string {
    return $localize`:@@core.codeInput.digit:Digit ${i + 1}:n: of ${this.length()}:total:`;
  }

  readonly label = input($localize`:@@core.codeInput.label:Verification code`);
  readonly name = input<string | null>('code');
  readonly disabled = input(false);
  readonly invalid = input(false);
  /** Id of the hint or error text under the input. */
  readonly describedBy = input<string | null>(null);
  /** Focus the first empty cell when the component appears. */
  readonly autofocus = input(false);
  /** The digits entered so far (may be shorter than `length`). */
  readonly value = model('');
  readonly completed = output<string>();

  readonly cells = signal<string[]>(cellsOf('', 6));
  /** A visual gap in the middle of even-length codes (123 456). */
  readonly groupAt = computed(() => (this.length() % 2 === 0 ? this.length() / 2 : 0));
  private readonly inputs = viewChildren<ElementRef<HTMLInputElement>>('cell');
  private focused = false;

  constructor() {
    // External value / length changes (e.g. reset to '') are reflected in the cells.
    effect(() => {
      const value = this.value();
      const length = this.length();
      untracked(() => {
        const cur = this.cells();
        if (cur.length !== length || cur.join('') !== value) this.cells.set(cellsOf(value, length));
      });
    });
    effect(() => {
      if (this.autofocus() && this.inputs().length && !this.focused) {
        this.focused = true;
        untracked(() => queueMicrotask(() => this.focus()));
      }
    });
  }

  /** Focuses the first empty cell. */
  focus(): void {
    this.inputs()[firstEmpty(this.cells())]?.nativeElement.focus();
  }

  /** Clears every cell and focuses the first. */
  clear(): void {
    this.commit({ cells: cellsOf('', this.length()), focus: 0 }, false);
  }

  onKeydown(event: KeyboardEvent, i: number): void {
    if (event.ctrlKey || event.metaKey || event.altKey) return; // paste, shortcuts
    const last = this.length() - 1;
    switch (event.key) {
      case 'Backspace':
        event.preventDefault();
        this.commit(applyBackspace(this.cells(), i));
        return;
      case 'Delete':
        event.preventDefault();
        this.commit(applyDelete(this.cells(), i));
        return;
      case 'ArrowLeft':
        event.preventDefault();
        this.moveTo(Math.max(0, i - 1));
        return;
      case 'ArrowRight':
        event.preventDefault();
        this.moveTo(Math.min(last, i + 1));
        return;
      case 'Home':
        event.preventDefault();
        this.moveTo(0);
        return;
      case 'End':
        event.preventDefault();
        this.moveTo(last);
        return;
    }
    if (event.key.length === 1) {
      // Digits are applied here (so typing over a filled cell replaces it); other characters are dropped.
      event.preventDefault();
      if (/^\d$/.test(event.key)) this.commit(applyInput(this.cells(), i, event.key));
    }
  }

  /** Fallback for autofill and mobile keyboards that do not send key events. */
  onInput(event: Event, i: number): void {
    const el = event.target as HTMLInputElement;
    const typed = el.value;
    const before = this.cells();
    const cleared = [...before];
    cleared[i] = '';
    // Keep what the user added, not the digit that was already in the cell.
    const text = before[i] && typed.startsWith(before[i]) ? typed.slice(before[i].length) : typed;
    this.commit(applyInput(cleared, i, text || typed));
  }

  onPaste(event: ClipboardEvent, i: number): void {
    event.preventDefault();
    const text = event.clipboardData?.getData('text') ?? '';
    this.commit(applyPaste(this.cells(), i, text));
  }

  onFocus(event: FocusEvent, i: number): void {
    const target = firstEmpty(this.cells());
    // Never leave a gap: focusing a cell after the first empty one jumps back to it.
    if (target < i && !this.cells()[i]) {
      this.moveTo(target);
      return;
    }
    (event.target as HTMLInputElement).select();
  }

  private moveTo(i: number): void {
    const el = this.inputs()[i]?.nativeElement;
    el?.focus();
    el?.select();
  }

  private commit(edit: CodeEdit, emit = true): void {
    const before = this.cells().join('');
    this.cells.set(edit.cells);
    // The DOM value can differ from the model (a rejected character); write it back.
    this.inputs().forEach((ref, idx) => {
      if (ref.nativeElement.value !== (edit.cells[idx] ?? ''))
        ref.nativeElement.value = edit.cells[idx] ?? '';
    });
    this.value.set(edit.cells.join(''));
    this.moveTo(edit.focus);
    const code = completeCode(edit.cells);
    // Emitted when a user edit completes (or changes) the code; arrow keys and no-ops do not re-emit.
    if (emit && code && code !== before) this.completed.emit(code);
  }
}
