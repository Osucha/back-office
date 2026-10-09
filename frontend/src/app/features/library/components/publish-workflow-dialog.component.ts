import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type {
  LibraryWorkflow,
  LibraryWorkflowSummary,
  WorkflowDefinition,
} from '../../../core/api/types';
import { WorkflowLibraryApi } from '../../../core/api/workflow-library.service';
import { libraryKeyError, suggestLibraryKey } from '../data/library-workflow-form';

type Mode = 'NEW' | 'VERSION';

/**
 * "Publish to library" (ADR 0037): the saved project workflow becomes a new library entry or the next version of an
 * existing one (default: the entry it is linked to). Project-local settings are not published.
 */
@Component({
  selector: 'app-publish-workflow-dialog',
  imports: [FormsModule, NzModalModule, NzRadioModule, NzSelectModule, NzInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="!!workflow()"
      [nzTitle]="title()"
      nzOkText="Publish"
      i18n-nzOkText="@@library.publish.ok"
      nzCancelText="Cancel"
      i18n-nzCancelText="@@library.publish.cancel"
      [nzOkDisabled]="!canSubmit()"
      [nzOkLoading]="busy()"
      (nzOnOk)="submit()"
      (nzOnCancel)="closed.emit()"
    >
      <ng-container *nzModalContent>
        <p class="lead" i18n="@@library.publish.lead">
          The saved version of this workflow is published. Step repositories, a pinned Jira
          connection and named approvers stay in the project; steps must use global task templates.
        </p>
        <nz-radio-group [ngModel]="mode()" (ngModelChange)="mode.set($event)" name="mode">
          <label nz-radio-button nzValue="NEW" i18n="@@library.publish.modeNew"
            >New library workflow</label
          >
          <label
            nz-radio-button
            nzValue="VERSION"
            [nzDisabled]="!entries().length"
            i18n="@@library.publish.modeVersion"
            >New version of an existing one</label
          >
        </nz-radio-group>

        @if (mode() === 'NEW') {
          <label class="field-label" for="pub-name" i18n="@@library.publish.name">Name</label>
          <input id="pub-name" nz-input [ngModel]="name()" (ngModelChange)="setName($event)" />
          <label class="field-label" for="pub-key" i18n="@@library.publish.key">Key</label>
          <input
            id="pub-key"
            nz-input
            class="mono"
            [ngModel]="key()"
            (ngModelChange)="setKey($event)"
          />
          @if (keyError(); as e) {
            <div class="error" role="alert">{{ e }}</div>
          }
          <label class="field-label" for="pub-desc" i18n="@@library.publish.description"
            >Description</label
          >
          <textarea
            id="pub-desc"
            nz-input
            [ngModel]="description()"
            (ngModelChange)="description.set($event)"
            [nzAutosize]="{ minRows: 2, maxRows: 5 }"
          ></textarea>
          <label class="field-label" for="pub-tags" i18n="@@library.publish.tags">Tags</label>
          <nz-select
            id="pub-tags"
            nzMode="tags"
            style="width: 100%"
            [ngModel]="tags()"
            (ngModelChange)="tags.set($event)"
          ></nz-select>
        } @else {
          <label class="field-label" for="pub-entry" i18n="@@library.publish.entry"
            >Library workflow</label
          >
          <nz-select
            id="pub-entry"
            nzShowSearch
            style="width: 100%"
            [ngModel]="entryId()"
            (ngModelChange)="entryId.set($event)"
          >
            @for (w of entries(); track w.id) {
              <nz-option
                [nzValue]="w.id"
                [nzLabel]="w.name + ' (' + w.key + ', v' + w.latestVersion + ')'"
              />
            }
          </nz-select>
          @if (selected(); as s) {
            <p class="hint" i18n="@@library.publish.nextVersion">
              This becomes version {{ s.latestVersion + 1 }} of {{ s.key }}.
            </p>
          }
        }
        <label class="field-label" for="pub-note" i18n="@@library.publish.changeNote"
          >What changed (shown in the version history)</label
        >
        <textarea
          id="pub-note"
          nz-input
          maxlength="2000"
          [ngModel]="changeNote()"
          (ngModelChange)="changeNote.set($event)"
          [nzAutosize]="{ minRows: 2, maxRows: 6 }"
        ></textarea>
      </ng-container>
    </nz-modal>
  `,
  styles: [
    `
      .lead,
      .hint {
        margin: 0 0 12px;
        font-size: 13px;
        color: var(--bo-ink-2);
      }
      .hint {
        margin: 8px 0 0;
      }
      .field-label {
        display: block;
        margin: 12px 0 4px;
        font-size: 12.5px;
        font-weight: 500;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .error {
        margin-top: 4px;
        font-size: 12px;
        color: var(--bo-blocked);
      }
    `,
  ],
})
export class PublishWorkflowDialogComponent {
  private readonly api = inject(WorkflowLibraryApi);
  private readonly message = inject(NzMessageService);

  /** The project workflow to publish; the dialog is open while it is set. */
  readonly workflow = input<WorkflowDefinition | null>(null);
  readonly published = output<LibraryWorkflow>();
  readonly closed = output<void>();

  readonly entries = signal<LibraryWorkflowSummary[]>([]);
  readonly mode = signal<Mode>('NEW');
  readonly entryId = signal<string | null>(null);
  readonly name = signal('');
  readonly key = signal('');
  private readonly keyTouched = signal(false);
  readonly description = signal('');
  readonly tags = signal<string[]>([]);
  readonly changeNote = signal('');
  readonly busy = signal(false);

  readonly title = computed(
    () =>
      $localize`:@@library.publish.title:Publish "${this.workflow()?.name ?? ''}:name:" to the library`,
  );
  readonly selected = computed(() => this.entries().find((e) => e.id === this.entryId()) ?? null);
  readonly keyError = computed(() =>
    this.mode() === 'NEW'
      ? libraryKeyError(
          this.key(),
          this.entries().map((e) => e.key),
        )
      : null,
  );
  readonly canSubmit = computed(() => {
    if (this.busy()) return false;
    return this.mode() === 'NEW' ? !this.keyError() && !!this.name().trim() : !!this.entryId();
  });

  constructor() {
    effect(() => {
      const w = this.workflow();
      if (!w) return;
      untracked(() => {
        const linked = w.library?.libraryWorkflowId ?? null;
        this.mode.set(linked ? 'VERSION' : 'NEW');
        this.entryId.set(linked);
        this.name.set(w.name);
        this.key.set(suggestLibraryKey(w.name));
        this.keyTouched.set(false);
        this.description.set(w.description ?? '');
        this.tags.set([]);
        this.changeNote.set('');
        this.api
          .list(true)
          .subscribe((list) =>
            this.entries.set(list.filter((e) => !e.deprecated || e.id === linked)),
          );
      });
    });
  }

  setName(value: string): void {
    this.name.set(value);
    if (!this.keyTouched()) this.key.set(suggestLibraryKey(value));
  }

  setKey(value: string): void {
    this.keyTouched.set(true);
    this.key.set(value.trim().toLowerCase());
  }

  submit(): void {
    const w = this.workflow();
    if (!w || !this.canSubmit()) return;
    this.busy.set(true);
    const note = this.changeNote().trim() || null;
    const body =
      this.mode() === 'NEW'
        ? {
            key: this.key(),
            name: this.name().trim(),
            description: this.description().trim(),
            tags: this.tags(),
            changeNote: note,
          }
        : { libraryWorkflowId: this.entryId(), changeNote: note };
    this.api.publish(w.id, body).subscribe({
      next: (entry) => {
        this.busy.set(false);
        this.message.success(
          $localize`:@@library.publish.done:Published as ${entry.key}:key: version ${entry.latestVersion}:version:`,
        );
        this.published.emit(entry);
      },
      error: () => this.busy.set(false),
    });
  }
}
