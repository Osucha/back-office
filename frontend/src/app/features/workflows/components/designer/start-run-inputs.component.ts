import {
  ChangeDetectionStrategy,
  Component,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import type { RunInputField } from '../../../../core/api/types';
import { WorkflowsApi } from '../../../../core/api/workflows.service';

/**
 * START node panel (ADR 0053): the run inputs a run of this workflow asks for, the union of what its task
 * steps declare in the task library. Read-only: START declares no fields of its own (decision L1).
 */
@Component({
  selector: 'app-start-run-inputs',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted small" i18n="@@workflows.props.runInputs.help">
      The start form asks for the run inputs the task steps declare in the task library (saved
      version). Edit them on the tasks, not here.
    </p>
    @if (!workflowId()) {
      <p class="muted small" i18n="@@workflows.props.runInputs.unsaved">
        Save the workflow to see its run inputs.
      </p>
    } @else if (loading()) {
      <p class="muted small" i18n="@@workflows.props.runInputs.loading">Loading...</p>
    } @else if (failed()) {
      <p class="muted small" i18n="@@workflows.props.runInputs.failed">
        The run inputs could not be loaded.
      </p>
    } @else if (!fields().length) {
      <p class="muted small" i18n="@@workflows.props.runInputs.none">
        This workflow asks for no input when it starts.
      </p>
    } @else {
      <ul class="inputs">
        @for (f of fields(); track f.name) {
          <li [attr.data-run-input]="f.name">
            <strong>{{ f.label }}</strong>
            <span class="mono small">{{ f.name }}</span>
            @if (f.required) {
              <span class="small req" i18n="@@workflows.props.runInputs.required">required</span>
            }
            @if (f.help) {
              <div class="muted small">{{ f.help }}</div>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .inputs {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .inputs li {
      padding: 4px 0;
    }
    .inputs li > span {
      margin-left: 6px;
    }
    .req {
      color: var(--ant-color-error, #cf1322);
    }
  `,
})
export class StartRunInputsComponent {
  private readonly api = inject(WorkflowsApi);

  /** The saved workflow; null for a new, unsaved one. */
  readonly workflowId = input<string | null>(null);

  readonly fields = signal<RunInputField[]>([]);
  readonly loading = signal(false);
  readonly failed = signal(false);

  constructor() {
    effect(() => {
      const id = this.workflowId();
      untracked(() => {
        this.fields.set([]);
        this.failed.set(false);
        if (!id) return;
        this.loading.set(true);
        this.api.runInputs(id).subscribe({
          next: (f) => {
            if (this.workflowId() !== id) return;
            this.fields.set(f);
            this.loading.set(false);
          },
          error: () => {
            this.failed.set(true);
            this.loading.set(false);
          },
        });
      });
    });
  }
}
