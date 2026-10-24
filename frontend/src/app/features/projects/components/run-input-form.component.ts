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
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzInputModule } from 'ng-zorro-antd/input';
import type { RunInputField, StartRunRequest } from '../../../core/api/types';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import {
  MAX_RUN_INPUT_LENGTH,
  buildStartRequest,
  type AdvancedError,
  type RunInputError,
} from '../data/run-inputs';

/**
 * The start dialog's form (ADR 0053): one field per run input the workflow's task steps declare (a text
 * area for MULTILINE, an input for TEXT and ISSUE_KEY), so the Jira issue key appears only for workflows
 * with a Jira step. "Advanced (JSON)" stays collapsed and adds extra parameters only. The parent asks
 * [request] for the start request when the user starts the run.
 */
@Component({
  selector: 'app-run-input-form',
  imports: [FormsModule, NzButtonModule, NzInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (loading()) {
      <div class="muted small" i18n="@@projects.start.inputsLoading">Loading the run inputs...</div>
    } @else {
      @if (failed()) {
        <div class="muted small" i18n="@@projects.start.inputsUnavailable">
          The run inputs could not be loaded; the server checks them when the run starts.
        </div>
      } @else if (!fields().length) {
        <div class="muted small" i18n="@@projects.start.noInputs">
          This workflow asks for no input when it starts.
        </div>
      }
      @for (f of fields(); track f.name) {
        <div class="run-input" [attr.data-field]="f.name">
          <label [for]="'run-input-' + f.name" class="field-label"
            >{{ f.label }}
            @if (f.required) {
              <span class="required" aria-hidden="true">*</span>
            }
          </label>
          @if (f.type === 'MULTILINE') {
            <textarea
              nz-input
              [id]="'run-input-' + f.name"
              [ngModel]="values()[f.name] ?? ''"
              (ngModelChange)="setValue(f.name, $event)"
              [maxlength]="max"
              [attr.aria-required]="f.required"
              [nzAutosize]="{ minRows: 3, maxRows: 12 }"
            ></textarea>
          } @else {
            <input
              nz-input
              [id]="'run-input-' + f.name"
              [class.mono]="f.type === 'ISSUE_KEY'"
              [ngModel]="values()[f.name] ?? ''"
              (ngModelChange)="setValue(f.name, $event)"
              [maxlength]="max"
              [attr.aria-required]="f.required"
              [placeholder]="f.type === 'ISSUE_KEY' ? issueKeyPlaceholder() : ''"
              (keydown.enter)="submitted.emit()"
            />
          }
          @if (f.help) {
            <div class="muted small">{{ f.help }}</div>
          }
          @switch (errors()[f.name]) {
            @case ('required') {
              <div class="danger small field-error" i18n="@@projects.start.fieldRequired">
                This field is required.
              </div>
            }
            @case ('tooLong') {
              <div class="danger small field-error" i18n="@@projects.start.fieldTooLong">
                At most {{ max }} characters.
              </div>
            }
          }
        </div>
      }
      <button
        nz-button
        nzType="link"
        type="button"
        class="advanced-toggle"
        [attr.aria-expanded]="advancedOpen()"
        (click)="advancedOpen.set(!advancedOpen())"
        i18n="@@projects.start.advanced"
      >
        Advanced (JSON)
      </button>
      @if (advancedOpen()) {
        <label for="run-params" class="field-label" i18n="@@projects.start.advancedLabel"
          >Extra parameters (JSON object, optional)</label
        >
        <textarea
          id="run-params"
          nz-input
          class="mono-textarea"
          [ngModel]="advancedJson()"
          (ngModelChange)="advancedJson.set($event); advancedError.set(null)"
          [nzAutosize]="{ minRows: 3, maxRows: 10 }"
          placeholder="{ }"
        ></textarea>
        @if (advancedMessage(); as m) {
          <div class="danger small advanced-error">{{ m }}</div>
        }
      }
    }
  `,
  styles: `
    .run-input {
      margin: 4px 0 12px;
    }
    .required {
      color: var(--ant-color-error, #cf1322);
      margin-left: 2px;
    }
    .advanced-toggle {
      padding-left: 0;
    }
  `,
})
export class RunInputFormComponent {
  private readonly api = inject(WorkflowsApi);

  readonly workflowId = input.required<string>();
  /** Placeholder of ISSUE_KEY fields, e.g. the project's Jira key with -123. */
  readonly issueKeyPlaceholder = input('ABC-123');
  /** Enter in a single-line field. */
  readonly submitted = output<void>();

  readonly max = MAX_RUN_INPUT_LENGTH;
  readonly fields = signal<RunInputField[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);
  readonly values = signal<Partial<Record<string, string>>>({});
  readonly errors = signal<Record<string, RunInputError>>({});
  readonly advancedOpen = signal(false);
  readonly advancedJson = signal('');
  readonly advancedError = signal<AdvancedError | null>(null);

  readonly advancedMessage = computed(() => {
    const e = this.advancedError();
    if (!e) return null;
    switch (e.kind) {
      case 'json':
        return $localize`:@@projects.start.invalidJson:The parameters are not valid JSON: ${e.detail}:error:`;
      case 'notObject':
        return $localize`:@@projects.start.advancedNotObject:The extra parameters must be a JSON object.`;
      case 'issueKey':
        return $localize`:@@projects.start.advancedIssueKey:Set the issue key in its field above, not in the extra parameters.`;
      case 'declared':
        return $localize`:@@projects.start.advancedDeclared:${e.name}:name: is a field of this form; set it above.`;
    }
  });

  constructor() {
    effect(() => {
      const id = this.workflowId();
      untracked(() => this.load(id));
    });
  }

  private load(id: string): void {
    this.fields.set([]);
    this.values.set({});
    this.errors.set({});
    this.failed.set(false);
    this.loading.set(true);
    this.api.runInputs(id).subscribe({
      next: (fields) => {
        if (this.workflowId() !== id) return;
        this.fields.set(fields);
        this.loading.set(false);
      },
      error: () => {
        if (this.workflowId() !== id) return;
        this.failed.set(true);
        this.loading.set(false);
      },
    });
  }

  setValue(name: string, value: string): void {
    this.values.update((v) => ({ ...v, [name]: value }));
    if (this.errors()[name]) {
      this.errors.update((e) => {
        const rest = { ...e };
        delete rest[name];
        return rest;
      });
    }
  }

  /** The start request, or null after marking what is missing or invalid. */
  request(): StartRunRequest | null {
    const result = buildStartRequest(this.fields(), this.values(), this.advancedJson());
    this.errors.set(result.fieldErrors);
    this.advancedError.set(result.advancedError ?? null);
    if (result.advancedError) this.advancedOpen.set(true);
    return result.request ?? null;
  }
}
