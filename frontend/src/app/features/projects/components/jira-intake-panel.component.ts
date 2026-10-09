import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzAlertModule } from 'ng-zorro-antd/alert';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCardModule } from 'ng-zorro-antd/card';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTagModule } from 'ng-zorro-antd/tag';
import { JiraIntakeApi } from '../../../core/api/jira-intake.service';
import type { JiraIntake, WorkflowDefinition } from '../../../core/api/types';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { problemMessage } from '../../../core/http/http-error.interceptor';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { absoluteTime } from '../../../core/ui/relay/format';
import {
  draftOf,
  intakeDraftProblems,
  intakeWorkflowOptions,
  readerState,
  sameDraft,
  type IntakeDraft,
} from '../data/jira-intake';

/**
 * Project settings > Jira intake watcher (ADR 0033): turns a Jira transition (or a new issue) into a
 * run of the chosen workflow. Reads Jira with an ISSUE_READ connection only; shows what it sends,
 * the connection it uses, its last poll, errors and back-off, and offers "Poll now" to holders of
 * `project.manage`.
 */
@Component({
  selector: 'app-jira-intake-panel',
  imports: [
    FormsModule,
    RouterLink,
    NzAlertModule,
    NzButtonModule,
    NzCardModule,
    NzInputNumberModule,
    NzSelectModule,
    NzSwitchModule,
    NzTagModule,
    HelpLinkComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nz-card [nzTitle]="titleTpl" [nzExtra]="extraTpl">
      <ng-template #titleTpl>
        <span i18n="@@projects.intake.title">Jira intake watcher</span>
        <bo-help-link topic="jira.intake" style="margin-left: 6px" />
      </ng-template>
      <ng-template #extraTpl>
        @if (intake(); as i) {
          @if (i.enabled) {
            <nz-tag nzColor="green" i18n="@@projects.intake.on">on</nz-tag>
          } @else {
            <nz-tag i18n="@@projects.intake.off">off</nz-tag>
          }
        }
      </ng-template>

      @if (!jiraEnabled()) {
        <p class="muted" i18n="@@projects.intake.noJira">
          Turn Jira on for this project first (the Jira section of the settings). The watcher reads
          the issues of that Jira project.
        </p>
      } @else if (loadError()) {
        <nz-alert nzType="error" nzShowIcon [nzMessage]="loadError()!" />
      } @else if (intake(); as i) {
        <p class="muted small" i18n="@@projects.intake.intro">
          Starts a run when a Jira issue enters the status of the workflow's Jira trigger (or,
          without a status, for each new issue matching the JQL filter). It only reads Jira, with a
          reader connection.
        </p>

        <div class="form">
          <label class="row">
            <nz-switch
              [ngModel]="draft().enabled"
              (ngModelChange)="set({ enabled: $event })"
              [nzDisabled]="!i.canManage"
              aria-label="Watch Jira for this project"
              i18n-aria-label="@@projects.intake.enabledLabel"
            />
            <span i18n="@@projects.intake.enabled">Watch Jira and start runs</span>
          </label>
          <div class="field">
            <label for="intake-workflow" i18n="@@projects.intake.workflow">Workflow to start</label>
            <nz-select
              id="intake-workflow"
              [ngModel]="draft().workflowId"
              (ngModelChange)="set({ workflowId: $event })"
              [nzDisabled]="!i.canManage"
              nzAllowClear
              nzPlaceHolder="Choose a workflow with a Jira trigger"
              i18n-nzPlaceHolder="@@projects.intake.workflowPlaceholder"
            >
              @for (o of options(); track o.id) {
                <nz-option [nzValue]="o.id" [nzLabel]="optionLabel(o)" [nzDisabled]="!o.usable" />
              }
            </nz-select>
            <div class="hint" i18n="@@projects.intake.workflowHint">
              Only workflows with a JIRA_TRANSITION trigger (designer, workflow settings) can be
              started. The trigger's status is the one that starts a run.
            </div>
          </div>
          <div class="field">
            <label for="intake-interval" i18n="@@projects.intake.interval"
              >Poll every (minutes)</label
            >
            <nz-input-number
              id="intake-interval"
              [ngModel]="draft().pollIntervalMinutes"
              (ngModelChange)="set({ pollIntervalMinutes: $event ?? 5 })"
              [nzMin]="1"
              [nzMax]="1440"
              [nzStep]="1"
              [nzDisabled]="!i.canManage"
              style="width: 120px"
            />
          </div>
          @for (p of problems(); track p) {
            <div class="danger small">{{ p }}</div>
          }
          @if (saveError()) {
            <nz-alert nzType="error" nzShowIcon [nzMessage]="saveError()!" />
          }
          @if (i.canManage) {
            <div class="actions">
              <button
                nz-button
                nzType="primary"
                type="button"
                [nzLoading]="saving()"
                [disabled]="!dirty() || problems().length > 0"
                (click)="save()"
                i18n="@@projects.intake.save"
              >
                Save
              </button>
              <button
                nz-button
                type="button"
                [nzLoading]="polling()"
                [disabled]="!i.enabled || dirty()"
                (click)="pollNow()"
                i18n="@@projects.intake.pollNow"
              >
                Poll now
              </button>
            </div>
          }
        </div>

        <h4 i18n="@@projects.intake.reader">Read connection</h4>
        @switch (reader()) {
          @case ('ok') {
            <p class="small">
              <span i18n="@@projects.intake.readerOk"
                >Reads with {{ i.connection.connection?.name }} (ISSUE_READ only).</span
              >
            </p>
          }
          @case ('overPrivileged') {
            <nz-alert
              nzType="warning"
              nzShowIcon
              [nzMessage]="overPrivilegedText()"
              class="block"
            />
          }
          @default {
            <nz-alert nzType="error" nzShowIcon [nzMessage]="noReaderTpl" class="block" />
            <ng-template #noReaderTpl>
              <span i18n="@@projects.intake.readerNone"
                >No connection with ISSUE_READ: the watcher refuses to poll.</span
              >
              <a
                [routerLink]="['/projects', projectId()]"
                [queryParams]="{ tab: 'connections' }"
                i18n="@@projects.intake.addReader"
                >Add a reader connection</a
              >
            </ng-template>
          }
        }

        <h4 i18n="@@projects.intake.status">Status</h4>
        @if (i.enabled && !i.status.baselineTaken) {
          <p class="muted small" i18n="@@projects.intake.baseline">
            The first poll only records the starting point: issues already in the status do not
            start runs.
          </p>
        }
        <dl class="status">
          <dt i18n="@@projects.intake.lastPoll">Last poll</dt>
          <dd [attr.title]="abs(i.status.lastPollAt)">{{ ago(i.status.lastPollAt) }}</dd>
          <dt i18n="@@projects.intake.lastSuccess">Last success</dt>
          <dd [attr.title]="abs(i.status.lastSuccessAt)">{{ ago(i.status.lastSuccessAt) }}</dd>
          <dt i18n="@@projects.intake.nextPoll">Next poll</dt>
          <dd [attr.title]="abs(i.status.nextPollAt)">
            {{ i.enabled ? ago(i.status.nextPollAt) : '–' }}
          </dd>
          <dt i18n="@@projects.intake.started">Runs started</dt>
          <dd>{{ i.status.issuesStarted }}</dd>
          @if (i.status.consecutiveFailures) {
            <dt i18n="@@projects.intake.failures">Failed polls in a row</dt>
            <dd>
              {{ i.status.consecutiveFailures }}
              <span class="muted small" i18n="@@projects.intake.backoffHint"
                >the next poll waits longer (back-off)</span
              >
            </dd>
          }
        </dl>
        @if (i.status.lastError) {
          <nz-alert
            nzType="error"
            nzShowIcon
            [nzMessage]="lastErrorTitle"
            [nzDescription]="i.status.lastError"
            class="block"
          />
        }
        @if (i.status.lastWarning) {
          <nz-alert nzType="warning" nzShowIcon [nzMessage]="i.status.lastWarning" class="block" />
        }
        @for (p of i.problems; track p) {
          <nz-alert nzType="warning" nzShowIcon [nzMessage]="p" class="block" />
        }
        @if (i.jql) {
          <h4 i18n="@@projects.intake.query">Query sent to Jira</h4>
          <pre class="jql">{{ i.jql }}</pre>
          <p class="muted small" i18n="@@projects.intake.queryHint">
            The project's JQL filter, limited to its Jira project and to the time since the last
            poll (plus an overlap); a connection limited to projects adds them too.
          </p>
        }
      } @else {
        <p class="muted" i18n="@@projects.intake.loading">Loading…</p>
      }
    </nz-card>
  `,
  styles: [
    `
      .form {
        display: flex;
        flex-direction: column;
        gap: 10px;
        margin-bottom: 12px;
      }
      .row {
        display: flex;
        align-items: center;
        gap: 8px;
      }
      .field {
        display: flex;
        flex-direction: column;
        gap: 4px;
      }
      .field > label {
        font-size: 12px;
        font-weight: 500;
        color: var(--bo-ink-2);
      }
      .hint {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .actions {
        display: flex;
        gap: 8px;
      }
      h4 {
        font-size: 13px;
        font-weight: 600;
        margin: 14px 0 6px;
      }
      .status {
        display: grid;
        grid-template-columns: max-content minmax(0, 1fr);
        gap: 4px 16px;
        margin: 0 0 8px;
        font-size: 13px;
      }
      .status dt {
        color: var(--bo-ink-3);
      }
      .status dd {
        margin: 0;
        font-variant-numeric: tabular-nums;
      }
      .block {
        margin-bottom: 8px;
      }
      .jql {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        font-family: var(--bo-font-mono, monospace);
        font-size: 12px;
        margin: 0 0 6px;
        padding: 8px 10px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .danger {
        color: var(--bo-blocked-ink);
      }
    `,
  ],
})
export class JiraIntakePanelComponent {
  private readonly api = inject(JiraIntakeApi);
  private readonly destroyRef = inject(DestroyRef);

  readonly projectId = input.required<string>();
  /** The project's workflows (the select offers those with a Jira trigger). */
  readonly workflows = input<WorkflowDefinition[]>([]);
  /** Jira is turned on for the project (issueTracker set). */
  readonly jiraEnabled = input(true);

  readonly intake = signal<JiraIntake | null>(null);
  readonly draft = signal<IntakeDraft>(draftOf(null));
  readonly loadError = signal<string | null>(null);
  readonly saveError = signal<string | null>(null);
  readonly saving = signal(false);
  readonly polling = signal(false);
  private refreshTimer: ReturnType<typeof setTimeout> | null = null;

  readonly options = computed(() => intakeWorkflowOptions(this.workflows()));
  readonly problems = computed(() => intakeDraftProblems(this.draft(), this.options()));
  readonly dirty = computed(() => !sameDraft(this.draft(), draftOf(this.intake())));
  readonly reader = computed(() => readerState(this.intake()));
  readonly overPrivilegedText = computed(() => {
    const c = this.intake()?.connection;
    const name = c?.connection?.name ?? '';
    const extra = (c?.excess ?? []).join(', ');
    return $localize`:@@projects.intake.readerOver:Reads with ${name}:name:, which can do more than read (${extra}:excess:). Create a connection with ISSUE_READ only.`;
  });
  readonly lastErrorTitle = $localize`:@@projects.intake.lastError:Last poll failed`;

  ago = (iso: string | null | undefined) => (iso ? timeAgo(iso) : '–');
  abs = (iso: string | null | undefined) => (iso ? absoluteTime(iso) : null);

  constructor() {
    effect(() => {
      const id = this.projectId();
      const on = this.jiraEnabled();
      untracked(() => {
        if (on) this.load(id);
      });
    });
    this.destroyRef.onDestroy(() => {
      if (this.refreshTimer) clearTimeout(this.refreshTimer);
    });
  }

  optionLabel(o: { name: string; status: string | null | undefined; usable: boolean }): string {
    if (o.status === undefined)
      return $localize`:@@projects.intake.optionNoTrigger:${o.name}:name: (no Jira trigger)`;
    return o.status
      ? $localize`:@@projects.intake.optionStatus:${o.name}:name: - when an issue enters ${o.status}:status:`
      : $localize`:@@projects.intake.optionNew:${o.name}:name: - for new issues`;
  }

  set(patch: Partial<IntakeDraft>): void {
    this.draft.update((d) => ({ ...d, ...patch }));
    this.saveError.set(null);
  }

  save(): void {
    if (this.problems().length || this.saving()) return;
    this.saving.set(true);
    this.saveError.set(null);
    this.api.update(this.projectId(), this.draft()).subscribe({
      next: (i) => {
        this.saving.set(false);
        this.apply(i);
        this.refreshSoon();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.saveError.set(problemMessage(err));
      },
    });
  }

  pollNow(): void {
    if (this.polling()) return;
    this.polling.set(true);
    this.api.pollNow(this.projectId()).subscribe({
      next: (i) => {
        this.polling.set(false);
        this.apply(i);
        this.refreshSoon();
      },
      error: (err: unknown) => {
        this.polling.set(false);
        this.saveError.set(problemMessage(err));
      },
    });
  }

  private load(projectId: string): void {
    this.loadError.set(null);
    this.api.get(projectId).subscribe({
      next: (i) => this.apply(i),
      error: (err: unknown) => this.loadError.set(problemMessage(err)),
    });
  }

  private apply(i: JiraIntake): void {
    this.intake.set(i);
    this.draft.set(draftOf(i));
  }

  /** A worker polls within seconds; show the outcome without a manual reload. */
  private refreshSoon(): void {
    if (this.refreshTimer) clearTimeout(this.refreshTimer);
    this.refreshTimer = setTimeout(() => {
      this.refreshTimer = null;
      this.api
        .get(this.projectId())
        .subscribe({ next: (i) => this.intake.set(i), error: () => undefined });
    }, 4000);
  }
}
