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
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { ApprovalsApi } from '../../../core/api/approvals.service';
import type { ApprovalRequest, AssigneeCandidate } from '../../../core/api/types';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { roleName } from '../../../core/i18n/catalog-labels';
import { isReassigned } from '../data/approval-assignment';

/**
 * "Assign…" a waiting gate (ADR 0028, `run.assign`): pick who decides it from the project members who
 * could (`run.approve`, the gate's roles rule, not the run's starter on a four-eyes gate), with an
 * optional comment. "Back to the workflow's rule" clears a run-time assignment.
 */
@Component({
  selector: 'app-assign-approval-dialog',
  imports: [
    FormsModule,
    NzButtonModule,
    NzInputModule,
    NzModalModule,
    NzSelectModule,
    HelpLinkComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-modal
      [nzVisible]="!!approval()"
      nzTitle="Who decides this step"
      i18n-nzTitle="@@inbox.assign.title"
      [nzFooter]="footer"
      [nzWidth]="480"
      (nzOnCancel)="closed.emit()"
    >
      <ng-container *nzModalContent>
        <p class="lead" i18n="@@inbox.assign.lead">
          Only the people you pick can decide this gate in this run. The workflow stays as it is.
        </p>
        <p class="lead">
          <bo-help-link topic="gates.assign" variant="text"
            ><span i18n="@@inbox.assign.readHow">Read how</span></bo-help-link
          >
        </p>
        <label class="field-label" for="assign-users" i18n="@@inbox.assign.users">Assign to</label>
        <nz-select
          id="assign-users"
          class="users"
          nzMode="multiple"
          nzShowSearch
          [nzLoading]="loading()"
          nzPlaceHolder="Project members who can approve"
          i18n-nzPlaceHolder="@@inbox.assign.placeholder"
          nzNotFoundContent="No project member can decide this gate."
          i18n-nzNotFoundContent="@@inbox.assign.noCandidates"
          [ngModel]="selected()"
          (ngModelChange)="selected.set($event)"
        >
          @for (c of candidates(); track c.userId) {
            <nz-option [nzValue]="c.userId" [nzLabel]="optionLabel(c)" />
          }
        </nz-select>
        <p class="hint" i18n="@@inbox.assign.hint">
          Only members with run.approve who meet the gate's roles and four-eyes rule are listed.
        </p>
        <label class="field-label" for="assign-comment" i18n="@@inbox.assign.comment"
          >Comment (optional)</label
        >
        <textarea
          id="assign-comment"
          nz-input
          [nzAutosize]="{ minRows: 2, maxRows: 4 }"
          maxlength="2000"
          [ngModel]="comment()"
          (ngModelChange)="comment.set($event)"
        ></textarea>
        @if (error(); as e) {
          <p class="error" role="alert">{{ e }}</p>
        }
      </ng-container>
      <ng-template #footer>
        @if (reassigned()) {
          <button
            nz-button
            type="button"
            class="reset"
            [disabled]="busy()"
            (click)="save([])"
            i18n="@@inbox.assign.reset"
          >
            Back to the workflow's rule
          </button>
        }
        <button nz-button type="button" (click)="closed.emit()" i18n="@@inbox.assign.cancel">
          Cancel
        </button>
        <button
          nz-button
          nzType="primary"
          type="button"
          [nzLoading]="busy()"
          [disabled]="!selected().length"
          (click)="save(selected())"
          i18n="@@inbox.assign.save"
        >
          Assign
        </button>
      </ng-template>
    </nz-modal>
  `,
  styles: [
    `
      .lead {
        margin: 0 0 12px;
        color: var(--bo-ink-2);
      }
      .field-label {
        display: block;
        font-size: 12px;
        font-weight: 500;
        margin-bottom: 4px;
      }
      .users {
        width: 100%;
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
        margin: 4px 0 12px;
      }
      .error {
        color: var(--bo-danger, #c0392b);
        margin: 8px 0 0;
      }
      .reset {
        float: left;
      }
    `,
  ],
})
export class AssignApprovalDialogComponent {
  private readonly api = inject(ApprovalsApi);
  private readonly message = inject(NzMessageService);

  /** The approval to assign; the dialog is open while it is set. */
  readonly approval = input<ApprovalRequest | null>(null);
  readonly assigned = output<ApprovalRequest>();
  readonly closed = output<void>();

  readonly candidates = signal<AssigneeCandidate[]>([]);
  readonly loading = signal(false);
  readonly selected = signal<string[]>([]);
  readonly comment = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);
  readonly reassigned = computed(() => {
    const a = this.approval();
    return !!a && isReassigned(a);
  });

  constructor() {
    effect(() => {
      const a = this.approval();
      untracked(() => {
        this.selected.set([]);
        this.comment.set('');
        this.error.set(null);
        this.candidates.set([]);
        if (a?.id) this.load(a.id);
      });
    });
  }

  optionLabel(c: AssigneeCandidate): string {
    const roles = (c.projectRoles ?? []).map((r) => roleName(r)).join(', ');
    return roles ? `${c.displayName} (${roles})` : c.displayName;
  }

  save(userIds: string[]): void {
    const a = this.approval();
    if (!a?.id || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.assign(a.id, { userIds, comment: this.comment().trim() || null }).subscribe({
      next: (updated) => {
        this.busy.set(false);
        this.message.success(
          userIds.length
            ? $localize`:@@inbox.assign.saved:Gate assigned`
            : $localize`:@@inbox.assign.cleared:The workflow's rule applies again`,
        );
        this.assigned.emit(updated);
      },
      error: (err: unknown) => {
        this.busy.set(false);
        this.error.set(problemMessage(err));
      },
    });
  }

  private load(approvalId: string): void {
    this.loading.set(true);
    this.api.candidates(approvalId).subscribe({
      next: (list) => {
        this.candidates.set(list);
        // preselect the current assignees who can still decide (from the definition or a reassignment)
        this.selected.set(list.filter((c) => c.assigned).map((c) => c.userId));
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.loading.set(false);
        this.error.set(problemMessage(err));
      },
    });
  }
}
