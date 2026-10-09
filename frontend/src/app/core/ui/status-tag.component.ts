import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzTagModule } from 'ng-zorro-antd/tag';

/** Colour + icon per domain status. Unknown values fall back to a neutral tag. */
const STYLES: Record<string, { color: string; icon?: string; spin?: boolean }> = {
  // RunStatus / BenchmarkStatus / CodeReview status / AttemptStatus
  PENDING: { color: 'default', icon: 'clock-circle' },
  DRAFT: { color: 'default', icon: 'edit' },
  QUEUED: { color: 'default', icon: 'clock-circle' },
  CREATED: { color: 'default', icon: 'clock-circle' },
  RUNNING: { color: 'processing', icon: 'loading', spin: true },
  JUDGING: { color: 'purple', icon: 'loading', spin: true },
  WAITING: { color: 'gold', icon: 'pause-circle' },
  WAITING_FOR_HUMAN: { color: 'gold', icon: 'pause-circle' },
  SUCCEEDED: { color: 'success', icon: 'check-circle' },
  COMPLETED: { color: 'success', icon: 'check-circle' },
  FAILED: { color: 'error', icon: 'close-circle' },
  TIMED_OUT: { color: 'volcano', icon: 'field-time' },
  CANCELLED: { color: 'default', icon: 'stop' },
  SKIPPED: { color: 'default', icon: 'minus-circle' },
  // Approval status
  OPEN: { color: 'gold', icon: 'pause-circle' },
  DECIDED: { color: 'success', icon: 'check' },
  EXPIRED: { color: 'default', icon: 'clock-circle' },
  // Review verdict / gate result
  APPROVE: { color: 'success', icon: 'check-circle' },
  COMMENT: { color: 'blue', icon: 'info-circle' },
  REQUEST_CHANGES: { color: 'error', icon: 'exclamation-circle' },
  PASS: { color: 'success' },
  FAIL: { color: 'error' },
  // Findings
  BLOCKER: { color: 'red' },
  MAJOR: { color: 'orange' },
  MINOR: { color: 'gold' },
  NIT: { color: 'default' },
  // Difficulty
  EASY: { color: 'green' },
  MEDIUM: { color: 'blue' },
  HARD: { color: 'orange' },
  EXPERT: { color: 'red' },
  // Providers
  ANTHROPIC: { color: 'volcano' },
  OPENAI: { color: 'green' },
  GOOGLE: { color: 'blue' },
  MISTRAL: { color: 'orange' },
  OLLAMA: { color: 'default' },
  OPENAI_COMPATIBLE: { color: 'cyan' },
  // Misc
  ENABLED: { color: 'success' },
  DISABLED: { color: 'default' },
  CONFIGURED: { color: 'success', icon: 'check-circle' },
  NOT_CONFIGURED: { color: 'default', icon: 'minus-circle' },
  MANUAL: { color: 'default' },
  API: { color: 'geekblue' },
  JIRA_TRANSITION: { color: 'blue' },
  JIRA: { color: 'blue' },
  CI: { color: 'geekblue' },
  PIPELINE: { color: 'purple' },
  // CI entrypoint mode / run outcome
  BLOCKING: { color: 'volcano' },
  ADVISORY: { color: 'cyan' },
  SUCCESS: { color: 'success', icon: 'check-circle' },
  FAILURE: { color: 'error', icon: 'close-circle' },
  // Task library
  GLOBAL: { color: 'geekblue' },
  // identity
  INVITED: { color: 'gold', icon: 'mail' },
  ACTIVE: { color: 'success', icon: 'check-circle' },
  DEACTIVATED: { color: 'default', icon: 'stop' },
  DENIED: { color: 'volcano', icon: 'stop' },
  ALLOW: { color: 'success' },
  DENY: { color: 'error' },
  PLATFORM: { color: 'geekblue' },
  PROJECT: { color: 'purple' },
  BUILT_IN: { color: 'default', icon: 'lock' },
  ABSTRACT: { color: 'default', icon: 'block' },
  LLM: { color: 'purple' },
  SPEC_KIT: { color: 'blue' },
  IMPLEMENT: { color: 'cyan' },
  FETCH_ISSUE: { color: 'default' },
  NOTIFY: { color: 'default' },
  TRANSITION_ISSUE: { color: 'default' },
  // Node kinds
  START: { color: 'green' },
  END: { color: 'default' },
  TASK: { color: 'blue' },
  DECISION: { color: 'orange' },
  HUMAN_GATE: { color: 'gold' },
  WAIT_FOR_CI: { color: 'geekblue' },
  NOTE: { color: 'default' },
};

export function statusColor(status: string | null | undefined): string {
  return (status && STYLES[status]?.color) || 'default';
}

@Component({
  selector: 'app-status-tag',
  imports: [NzTagModule, NzIconModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-tag [nzColor]="style().color" [attr.aria-label]="label()">
      @if (style().icon && showIcon()) {
        <nz-icon [nzType]="style().icon!" [nzSpin]="!!style().spin" />
      }
      {{ label() }}
    </nz-tag>
  `,
  styles: [
    `
      :host {
        display: inline-block;
      }
      nz-tag {
        margin-inline-end: 0;
      }
    `,
  ],
})
export class StatusTagComponent {
  readonly status = input<string | null | undefined>(null);
  readonly showIcon = input(true);
  /** Optional override of the visible text. */
  readonly text = input<string | null>(null);

  readonly style = computed(() => STYLES[this.status() ?? ''] ?? { color: 'default' });
  readonly label = computed(() => this.text() ?? humanize(this.status()));
}

let labels: Record<string, string> | null = null;

/** Translated words of the common enum values (statuses, verdicts, severities, node kinds). */
function statusLabels(): Record<string, string> {
  labels ??= {
    PENDING: $localize`:@@core.status.pending:Pending`,
    DRAFT: $localize`:@@core.status.draft:Draft`,
    QUEUED: $localize`:@@core.status.queued:Queued`,
    CREATED: $localize`:@@core.status.created:Created`,
    RUNNING: $localize`:@@core.status.running:Running`,
    JUDGING: $localize`:@@core.status.judging:Judging`,
    WAITING: $localize`:@@core.status.waiting:Waiting`,
    WAITING_FOR_HUMAN: $localize`:@@core.status.waitingForHuman:Waiting for human`,
    SUCCEEDED: $localize`:@@core.status.succeeded:Succeeded`,
    COMPLETED: $localize`:@@core.status.completed:Completed`,
    FAILED: $localize`:@@core.status.failed:Failed`,
    TIMED_OUT: $localize`:@@core.status.timedOut:Timed out`,
    CANCELLED: $localize`:@@core.status.cancelled:Cancelled`,
    SKIPPED: $localize`:@@core.status.skipped:Skipped`,
    OPEN: $localize`:@@core.status.open:Open`,
    DECIDED: $localize`:@@core.status.decided:Decided`,
    EXPIRED: $localize`:@@core.status.expired:Expired`,
    APPROVE: $localize`:@@core.status.approve:Approve`,
    COMMENT: $localize`:@@core.status.comment:Comment`,
    REQUEST_CHANGES: $localize`:@@core.status.requestChanges:Request changes`,
    PASS: $localize`:@@core.status.pass:Pass`,
    FAIL: $localize`:@@core.status.fail:Fail`,
    BLOCKER: $localize`:@@core.status.blocker:Blocker`,
    MAJOR: $localize`:@@core.status.major:Major`,
    MINOR: $localize`:@@core.status.minor:Minor`,
    NIT: $localize`:@@core.status.nit:Nit`,
    EASY: $localize`:@@core.status.easy:Easy`,
    MEDIUM: $localize`:@@core.status.medium:Medium`,
    HARD: $localize`:@@core.status.hard:Hard`,
    EXPERT: $localize`:@@core.status.expert:Expert`,
    ENABLED: $localize`:@@core.status.enabled:Enabled`,
    DISABLED: $localize`:@@core.status.disabled:Disabled`,
    CONFIGURED: $localize`:@@core.status.configured:Configured`,
    NOT_CONFIGURED: $localize`:@@core.status.notConfigured:Not configured`,
    MANUAL: $localize`:@@core.status.manual:Manual`,
    BLOCKING: $localize`:@@core.status.blocking:Blocking`,
    ADVISORY: $localize`:@@core.status.advisory:Advisory`,
    SUCCESS: $localize`:@@core.status.success:Success`,
    FAILURE: $localize`:@@core.status.failure:Failure`,
    GLOBAL: $localize`:@@core.status.global:Global`,
    INVITED: $localize`:@@core.status.invited:Invited`,
    ACTIVE: $localize`:@@core.status.active:Active`,
    DEACTIVATED: $localize`:@@core.status.deactivated:Deactivated`,
    DENIED: $localize`:@@core.status.denied:Denied`,
    ALLOW: $localize`:@@core.status.allow:Allow`,
    DENY: $localize`:@@core.status.deny:Deny`,
    PLATFORM: $localize`:@@core.status.platform:Platform`,
    PROJECT: $localize`:@@core.status.project:Project`,
    BUILT_IN: $localize`:@@core.status.builtIn:Built in`,
    ABSTRACT: $localize`:@@core.status.abstract:Abstract`,
    START: $localize`:@@core.status.start:Start`,
    END: $localize`:@@core.status.end:End`,
    TASK: $localize`:@@core.status.task:Task`,
    DECISION: $localize`:@@core.status.decision:Decision`,
    HUMAN_GATE: $localize`:@@core.status.humanGate:Human gate`,
    WAIT_FOR_CI: $localize`:@@core.status.waitForCi:Wait for ci`,
    NOTE: $localize`:@@core.status.note:Note`,
  };
  return labels;
}

/** "WAITING_FOR_HUMAN" -> "Waiting for human" (translated for known values). */
export function humanize(value: string | null | undefined): string {
  if (!value) return '-';
  const known = statusLabels()[value];
  if (known) return known;
  return value
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/^\w/, (c) => c.toUpperCase());
}
