import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { Trigger, TriggerType } from '../../../core/api/types';

/** Editable list of workflow triggers (MANUAL / JIRA_TRANSITION(status) / API / CI). */
@Component({
  selector: 'app-triggers-editor',
  imports: [FormsModule, NzSelectModule, NzInputModule, NzButtonModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="triggers">
      @for (t of triggers(); track $index; let i = $index) {
        <div class="row">
          <nz-select
            [ngModel]="t.type"
            (ngModelChange)="
              update(i, {
                type: $event,
                status: $event === 'JIRA_TRANSITION' ? (t.status ?? '') : null,
              })
            "
            nzSize="small"
            style="width: 190px"
            [attr.aria-label]="typeLabel(i)"
            [nzDisabled]="readonly()"
          >
            <nz-option
              nzValue="MANUAL"
              nzLabel="Manual (UI button)"
              i18n-nzLabel="@@workflows.triggers.manual"
            />
            <nz-option
              nzValue="JIRA_TRANSITION"
              nzLabel="Jira (intake watcher)"
              i18n-nzLabel="@@workflows.triggers.jira"
            />
            <nz-option nzValue="API" nzLabel="API call" i18n-nzLabel="@@workflows.triggers.api" />
            <nz-option
              nzValue="CI"
              nzLabel="CI entrypoint"
              i18n-nzLabel="@@workflows.triggers.ci"
            />
          </nz-select>
          @if (t.type === 'JIRA_TRANSITION') {
            <input
              nz-input
              nzSize="small"
              [ngModel]="t.status ?? ''"
              (ngModelChange)="update(i, { status: $event })"
              placeholder="Status that starts a run; empty = new issues"
              i18n-placeholder="@@workflows.triggers.jiraStatus"
              style="width: 200px"
              [attr.aria-label]="statusLabel(i)"
              [readonly]="readonly()"
            />
          }
          @if (t.type === 'API') {
            <span class="muted small">{{ apiPath }}</span>
          }
          @if (t.type === 'CI') {
            <span class="muted small" i18n="@@workflows.triggers.viaCi"
              >via a CI entrypoint (Project &rsaquo; CI)</span
            >
          }
          @if (!readonly()) {
            <button
              nz-button
              nzType="text"
              nzSize="small"
              nzDanger
              type="button"
              (click)="removeAt(i)"
              aria-label="Remove trigger"
              i18n-aria-label="@@workflows.triggers.remove"
            >
              <nz-icon nzType="delete" />
            </button>
          }
        </div>
      }
      @if (!triggers().length) {
        <p class="muted small" i18n="@@workflows.triggers.none">
          No triggers: the workflow can still be started from the project page.
        </p>
      }
      <p class="muted small" i18n="@@workflows.triggers.hint">
        Triggers record how this workflow is meant to start. Runs start from the UI, the API or a CI
        entrypoint whatever is listed here. A Jira trigger is used by the project's Jira intake
        watcher (Project › Settings): an issue entering the status starts a run (empty status: each
        new issue).
      </p>
      @if (!readonly()) {
        <button nz-button nzType="dashed" nzSize="small" type="button" (click)="add()">
          <nz-icon nzType="plus" />
          <span i18n="@@workflows.triggers.add">Add trigger</span>
        </button>
      }
    </div>
  `,
  styles: [
    `
      .triggers {
        display: flex;
        flex-direction: column;
        gap: 6px;
        align-items: flex-start;
      }
      .row {
        display: flex;
        gap: 6px;
        align-items: center;
        flex-wrap: wrap;
      }
      .small {
        font-size: 11px;
      }
    `,
  ],
})
export class TriggersEditorComponent {
  readonly triggers = input<Trigger[]>([]);
  readonly readonly = input(false);
  readonly triggersChange = output<Trigger[]>();
  readonly apiPath = 'POST /api/v1/workflows/:id/runs'; // i18n-ignore

  typeLabel(index: number): string {
    return $localize`:@@workflows.triggers.typeLabel:Trigger ${index + 1}:number: type`;
  }

  statusLabel(index: number): string {
    return $localize`:@@workflows.triggers.statusLabel:Trigger ${index + 1}:number: status`;
  }

  add(): void {
    const has = new Set(this.triggers().map((t) => t.type));
    const order: TriggerType[] = ['MANUAL', 'JIRA_TRANSITION', 'API', 'CI'];
    const type = order.find((t) => !has.has(t)) ?? 'API';
    this.triggersChange.emit([
      ...this.triggers(),
      type === 'JIRA_TRANSITION' ? { type, status: '' } : { type },
    ]);
  }

  update(index: number, patch: Partial<Trigger>): void {
    const next = this.triggers().map((t, i) => (i === index ? { ...t, ...patch } : t));
    for (const t of next) if (t.type !== 'JIRA_TRANSITION') delete t.status;
    this.triggersChange.emit(next);
  }

  removeAt(index: number): void {
    this.triggersChange.emit(this.triggers().filter((_, i) => i !== index));
  }
}
