import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  output,
} from '@angular/core';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { KbdComponent } from './kbd.component';
import { ShortcutsService } from './shortcuts.service';

/**
 * Sticky action bar for editors: unsaved-changes dot, secondary actions left (projected), primary
 * action right; `Ctrl/⌘ S` saves (also while typing in a field).
 *
 *   <bo-action-bar [dirty]="dirty()" [saving]="saving()" [canSave]="valid()" saveLabel="Save workflow"
 *                  (save)="save()">
 *     <button nz-button (click)="discard()">Discard changes</button>
 *     <span boActionStatus>3 warnings</span>
 *   </bo-action-bar>
 */
@Component({
  selector: 'bo-action-bar',
  imports: [NzButtonModule, KbdComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.sticky]': 'sticky()' },
  template: `
    <div
      class="bar"
      role="region"
      aria-label="Editor actions"
      i18n-aria-label="@@core.actionBar.region"
    >
      <span class="state" role="status">
        @if (dirty()) {
          <span class="dot" aria-hidden="true"></span> {{ dirtyLabel() }}
        } @else {
          <span class="saved">{{ cleanLabel() }}</span>
        }
      </span>
      <span class="status"><ng-content select="[boActionStatus]" /></span>
      <div class="secondary"><ng-content /></div>
      @if (showSave()) {
        <button
          nz-button
          nzType="primary"
          type="button"
          [nzLoading]="saving()"
          [disabled]="!canSave() || (!dirty() && requireDirty())"
          (click)="save.emit()"
        >
          {{ saveLabel() }}
          <bo-kbd keys="mod+s" [quiet]="true" />
        </button>
      }
    </div>
  `,
  styles: [
    `
      :host {
        display: block;
      }
      :host(.sticky) {
        position: sticky;
        bottom: 0;
        z-index: 5;
      }
      .bar {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 10px 16px;
        background: var(--bo-surface);
        border-top: 1px solid var(--bo-line);
      }
      .state {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        font-size: 12px;
        color: var(--bo-ink-2);
        white-space: nowrap;
      }
      .dot {
        width: 8px;
        height: 8px;
        border-radius: 50%;
        background: var(--bo-you);
      }
      .saved {
        color: var(--bo-ink-3);
      }
      .status {
        font-size: 12px;
        color: var(--bo-ink-3);
        min-width: 0;
      }
      .status:empty {
        display: none;
      }
      .secondary {
        display: flex;
        gap: 8px;
        align-items: center;
        margin-left: auto;
        flex-wrap: wrap;
      }
      button[nzType='primary'] {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
    `,
  ],
})
export class ActionBarComponent {
  readonly dirty = input(false);
  readonly saving = input(false);
  readonly canSave = input(true);
  readonly showSave = input(true);
  /** Disable Save while nothing changed (default true). */
  readonly requireDirty = input(true);
  readonly saveLabel = input($localize`:@@core.actionBar.save:Save changes`);
  readonly dirtyLabel = input($localize`:@@core.actionBar.dirty:Unsaved changes`);
  readonly cleanLabel = input($localize`:@@core.actionBar.clean:All changes saved`);
  readonly sticky = input(true);
  readonly save = output<void>();

  constructor() {
    inject(ShortcutsService).register(
      {
        keys: 'mod+s',
        description: $localize`:@@core.actionBar.shortcut:Save`,
        group: $localize`:@@core.actionBar.group:Editor`,
        allowInInputs: true,
        when: () => this.showSave(),
        handler: () => {
          if (this.canSave() && !this.saving() && (this.dirty() || !this.requireDirty()))
            this.save.emit();
        },
      },
      inject(DestroyRef),
    );
  }
}
