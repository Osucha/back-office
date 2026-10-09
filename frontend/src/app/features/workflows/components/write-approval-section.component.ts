import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import type { GateApprovers } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { roleName } from '../../../core/i18n/catalog-labels';
import { icu } from '../../../core/i18n/icu';
import {
  hasCustomWriteApprovers,
  withWriteApprovalMode,
  withWriteApprovers,
  writeApprovalMode,
  writeApprovers,
  type WriteApprovalMode,
} from '../data/write-approval';
import { GateRulesEditorComponent } from './gate-rules-editor.component';

/**
 * "Approval before writing to Jira" of a write step (ADR 0033): REQUIRED (default) pauses the run
 * before the write and shows the exact text for confirmation; who confirms is a gate-like rule
 * (default: project owners and maintainers). NOT_REQUIRED writes right away and is warned about.
 */
@Component({
  selector: 'app-write-approval-section',
  imports: [
    FormsModule,
    NzAlertModule,
    NzButtonModule,
    NzRadioModule,
    HelpLinkComponent,
    GateRulesEditorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted small" i18n="@@workflows.write.intro">
      This step writes to Jira. With approval required, the run pauses before the write and Your
      turn shows exactly what will be written; nothing reaches Jira until someone approves.
    </p>
    <nz-radio-group
      [ngModel]="mode()"
      (ngModelChange)="setMode($event)"
      [nzDisabled]="readonly()"
      aria-label="Approval before writing to Jira"
      i18n-aria-label="@@workflows.write.modeLabel"
      class="modes"
    >
      <label nz-radio nzValue="REQUIRED" i18n="@@workflows.write.required"
        >Approval required (recommended)</label
      >
      <label nz-radio nzValue="NOT_REQUIRED" i18n="@@workflows.write.notRequired"
        >Write without approval</label
      >
    </nz-radio-group>
    @if (mode() === 'NOT_REQUIRED') {
      <nz-alert
        nzType="warning"
        nzShowIcon
        class="warn"
        nzMessage="This step writes to Jira without anybody confirming it. Text from issues or model output can reach it (prompt injection); keep approval required unless the content is fixed."
        i18n-nzMessage="@@workflows.write.notRequiredWarning"
      />
    } @else {
      <p class="who small">
        <span i18n="@@workflows.write.whoApproves">Who approves:</span>
        <strong>{{ whoText() }}</strong>
        @if (custom() && !readonly()) {
          <button
            nz-button
            nzType="link"
            nzSize="small"
            type="button"
            (click)="resetApprovers()"
            i18n="@@workflows.write.resetApprovers"
          >
            Back to the default
          </button>
        }
      </p>
      <app-gate-rules-editor
        [approvers]="approvers()"
        [showChecklist]="false"
        [readonly]="readonly()"
        (approversChange)="setApprovers($event)"
      />
    }
    <bo-help-link topic="designer.writeConfirmations" variant="text"
      ><span i18n="@@workflows.write.help">How confirmations before writes work</span></bo-help-link
    >
  `,
  styles: [
    `
      .modes {
        display: flex;
        flex-direction: column;
        gap: 4px;
        margin-bottom: 8px;
      }
      .warn {
        margin-bottom: 8px;
      }
      .who {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 4px 6px;
        margin: 0 0 4px;
      }
    `,
  ],
})
export class WriteApprovalSectionComponent {
  /** The node's `overrides.config`. */
  readonly config = input<Record<string, unknown> | null>(null);
  readonly readonly = input(false);
  /** The new config (never undefined: the mode is written explicitly). */
  readonly configChange = output<Record<string, unknown>>();

  readonly mode = computed(() => writeApprovalMode(this.config()));
  readonly approvers = computed(() => writeApprovers(this.config()));
  readonly custom = computed(() => hasCustomWriteApprovers(this.config()));
  readonly whoText = computed(() => {
    const a = this.approvers();
    const parts: string[] = [];
    if (a.projectRoles?.length) parts.push(a.projectRoles.map((r) => roleName(r)).join(', '));
    if (a.users?.length)
      parts.push(
        icu(
          $localize`:@@workflows.write.namedUsers:{count, plural, one {# named user} other {# named users}}`,
          { count: a.users.length },
        ),
      );
    const base = parts.length
      ? parts.join(' + ')
      : $localize`:@@workflows.write.anyApprover:anyone with run.approve`;
    const rule = this.custom()
      ? base
      : $localize`:@@workflows.write.defaultRule:${base}:who: (default)`;
    return a.forbidSelfApproval
      ? $localize`:@@workflows.write.fourEyes:${rule}:who:, not the person who started the run`
      : rule;
  });

  setMode(mode: WriteApprovalMode): void {
    this.configChange.emit(withWriteApprovalMode(this.config(), mode));
  }

  setApprovers(approvers: GateApprovers | null): void {
    this.configChange.emit(
      withWriteApprovers(withWriteApprovalMode(this.config(), this.mode()), approvers),
    );
  }

  resetApprovers(): void {
    this.configChange.emit(withWriteApprovers(this.config(), 'default'));
  }
}
