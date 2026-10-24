import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import type { Trigger, TriggerType } from '../../../core/api/types';
import {
  addTrigger,
  changeTriggerType,
  MIN_SCHEDULE_MINUTES,
  patchTrigger,
  validScheduleInterval,
} from '../data/trigger-model';

/** Editable list of workflow triggers (MANUAL / JIRA_TRANSITION(status) / API / CI / SCHEDULE(interval)). */
@Component({
  selector: 'app-triggers-editor',
  imports: [
    FormsModule,
    NzSelectModule,
    NzInputModule,
    NzInputNumberModule,
    NzButtonModule,
    NzIconModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="triggers">
      @for (t of triggers(); track $index; let i = $index) {
        <div class="row">
          <nz-select
            [ngModel]="t.type"
            (ngModelChange)="changeType(i, $event)"
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
            <nz-option
              nzValue="SCHEDULE"
              nzLabel="Schedule (every N minutes)"
              i18n-nzLabel="@@workflows.triggers.schedule"
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
          @if (t.type === 'SCHEDULE') {
            <span class="small" i18n="@@workflows.triggers.every">Every (minutes)</span>
            <nz-input-number
              nzSize="small"
              [nzMin]="minMinutes"
              [nzStep]="5"
              [nzPrecision]="0"
              [ngModel]="t.intervalMinutes ?? null"
              (ngModelChange)="update(i, { intervalMinutes: $event })"
              [nzStatus]="intervalValid(t) ? '' : 'error'"
              [nzDisabled]="readonly()"
              [attr.aria-label]="intervalLabel(i)"
              [attr.aria-invalid]="!intervalValid(t)"
              style="width: 90px"
            />
            <span class="muted small" i18n="@@workflows.triggers.scheduleHint"
              >after the latest run began, never while a run is active; the run gets the parameter
              since</span
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
  readonly minMinutes = MIN_SCHEDULE_MINUTES;

  typeLabel(index: number): string {
    return $localize`:@@workflows.triggers.typeLabel:Trigger ${index + 1}:number: type`;
  }

  statusLabel(index: number): string {
    return $localize`:@@workflows.triggers.statusLabel:Trigger ${index + 1}:number: status`;
  }

  intervalLabel(index: number): string {
    return $localize`:@@workflows.triggers.intervalLabel:Trigger ${index + 1}:number: every (minutes)`;
  }

  intervalValid(t: Trigger): boolean {
    return validScheduleInterval(t.intervalMinutes);
  }

  add(): void {
    this.triggersChange.emit(addTrigger(this.triggers()));
  }

  changeType(index: number, type: TriggerType): void {
    this.triggersChange.emit(changeTriggerType(this.triggers(), index, type));
  }

  update(index: number, patch: Partial<Trigger>): void {
    this.triggersChange.emit(patchTrigger(this.triggers(), index, patch));
  }

  removeAt(index: number): void {
    this.triggersChange.emit(this.triggers().filter((_, i) => i !== index));
  }
}
