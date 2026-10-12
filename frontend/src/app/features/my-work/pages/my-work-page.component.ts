import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzInputNumberModule } from 'ng-zorro-antd/input-number';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzModalModule } from 'ng-zorro-antd/modal';
import { NzSelectModule } from 'ng-zorro-antd/select';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTableModule } from 'ng-zorro-antd/table';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { MyWorkApi } from '../../../core/api/my-work.service';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import type {
  Connection,
  IntakeProposal,
  IntakeProposalState,
  IntakeRule,
  IntakeRuleInput,
  WorkflowDefinition,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { EventStreamService } from '../../../core/events/event-stream.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { TimeAgoPipe } from '../../../core/ui/pipes/time-ago.pipe';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { PageHeaderComponent } from '../../../core/ui/relay/page-header.component';
import { SidePanelComponent, panelParam } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { RELAY_TABLE_STYLES } from '../../../core/ui/relay/table-styles';
import {
  type WorkflowChoice,
  emptyRule,
  ruleError,
  ruleInput,
  ruleStatus,
  workflowChoices,
} from '../data/my-work';

const TABS: (IntakeProposalState | 'RULES')[] = ['NEW', 'STARTED', 'DISMISSED', 'RULES'];

/**
 * My work (ADR 0038): the Jira issues your rules found that you have not decided on (Start the proposed workflow,
 * another one, or Dismiss), the ones you started or dismissed, and the rules themselves (your personal connection,
 * JQL, how often, which workflow). Proposals refresh live when a rule finds issues.
 */
@Component({
  selector: 'app-my-work-page',
  imports: [
    NgTemplateOutlet,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzCheckboxModule,
    NzDropDownModule,
    NzIconModule,
    NzInputModule,
    NzInputNumberModule,
    NzModalModule,
    NzSelectModule,
    NzSwitchModule,
    NzTableModule,
    NzTabsModule,
    EmptyComponent,
    PageHeaderComponent,
    SidePanelComponent,
    SkeletonComponent,
    TimeAgoPipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <bo-page-header
        help="myWork"
        title="My work"
        i18n-title="@@my-work.header.title"
        subtitle="Your Jira issues, found by your rules. Nothing starts until you say so: Start runs the workflow as you."
        i18n-subtitle="@@my-work.header.subtitle"
        [menu]="[{ label: text.reload, icon: 'reload', run: reload }]"
      >
        <button boHeaderActions nz-button nzType="primary" type="button" (click)="openRule('new')">
          <nz-icon nzType="plus" />
          <span i18n="@@my-work.rule.new">New rule</span>
        </button>
      </bo-page-header>

      <nz-tabs
        [nzSelectedIndex]="tabIndex()"
        (nzSelectedIndexChange)="tabIndex.set($event)"
        [nzAnimated]="false"
      >
        <nz-tab [nzTitle]="newTitle()">
          <ng-container *ngTemplateOutlet="list; context: { $implicit: 'NEW' }" />
        </nz-tab>
        <nz-tab nzTitle="Started" i18n-nzTitle="@@my-work.tab.started">
          <ng-template nz-tab
            ><ng-container *ngTemplateOutlet="list; context: { $implicit: 'STARTED' }"
          /></ng-template>
        </nz-tab>
        <nz-tab nzTitle="Dismissed" i18n-nzTitle="@@my-work.tab.dismissed">
          <ng-template nz-tab
            ><ng-container *ngTemplateOutlet="list; context: { $implicit: 'DISMISSED' }"
          /></ng-template>
        </nz-tab>
        <nz-tab [nzTitle]="rulesTitle()">
          @if (!rules().length) {
            <bo-empty
              title="No rules yet."
              i18n-title="@@my-work.rules.empty"
              detail="A rule reads Jira as you, through one of your personal connections, for example every issue assigned to you that is not done, and proposes a workflow for each one it finds."
              i18n-detail="@@my-work.rules.emptyDetail"
              actionLabel="New rule"
              i18n-actionLabel="@@my-work.rule.new"
              (action)="openRule('new')"
              help="myWork"
            />
          } @else {
            <nz-table [nzData]="rules()" [nzShowPagination]="false" nzSize="middle">
              <thead>
                <tr>
                  <th i18n="@@my-work.rules.col.rule">Rule</th>
                  <th nzWidth="220px" i18n="@@my-work.rules.col.workflow">Proposes</th>
                  <th nzWidth="200px" i18n="@@my-work.rules.col.status">Status</th>
                  <th nzWidth="120px">
                    <span class="sr-only" i18n="@@my-work.rules.col.actions">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                @for (r of rules(); track r.id) {
                  <tr
                    class="clickable-row"
                    tabindex="0"
                    (click)="openRule(r.id)"
                    (keydown.enter)="openRule(r.id)"
                  >
                    <td>
                      <div class="cell-main">
                        <div class="cell-line">
                          <span class="cell-title">{{ r.name }}</span>
                          @if (r.newProposals) {
                            <span class="chip you">{{ r.newProposals }}</span>
                          }
                        </div>
                        <span class="cell-sub mono" [attr.title]="r.jql">{{ r.jql }}</span>
                        <span class="cell-sub">{{ r.connectionName ?? '-' }}</span>
                      </div>
                    </td>
                    <td class="small">
                      {{ r.projectKey ? r.projectKey + ' · ' : '' }}{{ r.workflowName ?? '-' }}
                    </td>
                    <td class="small">
                      <span class="status" [attr.data-tone]="status(r).tone">{{
                        status(r).text
                      }}</span>
                      @if (r.lastError) {
                        <div class="err" [attr.title]="r.lastError">{{ r.lastError }}</div>
                      } @else if (r.lastSuccessAt) {
                        <div class="quiet">{{ checkedText(r.lastSuccessAt) }}</div>
                      }
                    </td>
                    <td (click)="$event.stopPropagation()">
                      <div class="row-actions">
                        <button
                          nz-button
                          nzSize="small"
                          type="button"
                          [disabled]="!r.enabled"
                          (click)="poll(r)"
                          i18n="@@my-work.rules.pollNow"
                        >
                          Check now
                        </button>
                      </div>
                    </td>
                  </tr>
                }
              </tbody>
            </nz-table>
          }
        </nz-tab>
      </nz-tabs>
    </div>

    <ng-template #list let-state>
      @if (loading() && !loaded()) {
        <bo-skeleton variant="table" [rows]="4" [columns]="4" />
      } @else if (!items(state).length) {
        @if (state === 'NEW') {
          <bo-empty
            tone="calm"
            [title]="rules().length ? text.nothingNew : text.noRulesYet"
            [detail]="rules().length ? text.nothingNewDetail : text.noRulesDetail"
            [actionLabel]="rules().length ? null : text.newRule"
            (action)="openRule('new')"
            help="myWork"
          />
        } @else {
          <bo-empty
            tone="quiet"
            [title]="state === 'STARTED' ? text.noneStarted : text.noneDismissed"
          />
        }
      } @else {
        <nz-table
          [nzData]="items(state)"
          [nzShowPagination]="items(state).length > 50"
          [nzPageSize]="50"
          nzSize="middle"
        >
          <thead>
            <tr>
              <th i18n="@@my-work.col.issue">Issue</th>
              <th nzWidth="230px" i18n="@@my-work.col.workflow">Workflow</th>
              <th nzWidth="130px" i18n="@@my-work.col.found">Found</th>
              <th nzWidth="230px">
                <span class="sr-only" i18n="@@my-work.col.actions">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (p of items(state); track p.id) {
              <tr>
                <td>
                  <div class="cell-main">
                    <div class="cell-line">
                      @if (p.issueUrl) {
                        <a
                          class="key"
                          [href]="p.issueUrl"
                          target="_blank"
                          rel="noopener noreferrer"
                          >{{ p.issueKey }}</a
                        >
                      } @else {
                        <span class="key">{{ p.issueKey }}</span>
                      }
                      @if (p.issueType) {
                        <span class="chip">{{ p.issueType }}</span>
                      }
                      @if (p.status) {
                        <span class="chip outline">{{ p.status }}</span>
                      }
                      @if (p.priority) {
                        <span class="quiet small">{{ p.priority }}</span>
                      }
                    </div>
                    @if (p.summary) {
                      <span class="cell-sub" [attr.title]="p.summary">{{ p.summary }}</span>
                    }
                  </div>
                </td>
                <td class="small">
                  {{ p.workflowName ?? '-' }}
                  @if (p.ruleName) {
                    <div class="quiet">{{ p.ruleName }}</div>
                  }
                </td>
                <td class="small" [attr.title]="p.detectedAt">{{ p.detectedAt | timeAgo }}</td>
                <td>
                  <div class="row-actions">
                    @if (state === 'STARTED') {
                      @if (p.runId) {
                        <a
                          nz-button
                          nzSize="small"
                          [routerLink]="['/runs', p.runId]"
                          i18n="@@my-work.openRun"
                          >Open run</a
                        >
                      }
                    } @else {
                      <button
                        nz-button
                        nzSize="small"
                        nzType="primary"
                        type="button"
                        [nzLoading]="busy() === p.id"
                        [disabled]="!p.workflowId"
                        (click)="start(p)"
                        i18n="@@my-work.start"
                      >
                        Start
                      </button>
                      <button
                        nz-button
                        nzSize="small"
                        type="button"
                        (click)="chooseWorkflow(p)"
                        [attr.aria-label]="text.startOther"
                        [attr.title]="text.startOther"
                      >
                        <nz-icon nzType="swap" />
                      </button>
                      @if (state === 'NEW') {
                        <button
                          nz-button
                          nzSize="small"
                          nzType="text"
                          type="button"
                          (click)="dismiss(p)"
                          i18n="@@my-work.dismiss"
                        >
                          Dismiss
                        </button>
                      } @else {
                        <button
                          nz-button
                          nzSize="small"
                          nzType="text"
                          type="button"
                          (click)="restore(p)"
                          i18n="@@my-work.restore"
                        >
                          Restore
                        </button>
                      }
                    }
                  </div>
                </td>
              </tr>
            }
          </tbody>
        </nz-table>
      }
    </ng-template>

    <nz-modal
      [nzVisible]="!!choosing()"
      [nzTitle]="chooseTitle()"
      nzOkText="Start"
      i18n-nzOkText="@@my-work.chooseStart"
      nzCancelText="Cancel"
      i18n-nzCancelText="@@my-work.chooseCancel"
      [nzOkDisabled]="!chosenWorkflow()"
      (nzOnOk)="startChosen()"
      (nzOnCancel)="choosing.set(null)"
    >
      <ng-container *nzModalContent>
        <label class="field-label" for="mw-choose" i18n="@@my-work.chooseWorkflow">Workflow</label>
        <nz-select
          id="mw-choose"
          nzShowSearch
          style="width: 100%"
          [ngModel]="chosenWorkflow()"
          (ngModelChange)="chosenWorkflow.set($event)"
        >
          @for (w of choices(); track w.id) {
            <nz-option [nzValue]="w.id" [nzLabel]="w.label" />
          }
        </nz-select>
      </ng-container>
    </nz-modal>

    <bo-side-panel
      [open]="panelOpen()"
      [title]="editing() ? editing()!.name : text.newRuleTitle"
      [subtitle]="text.ruleSubtitle"
      [defaultWidth]="640"
      storageKey="backoffice.panelWidth.intakeRule"
      initialFocus="#ir-name"
      (closed)="panel.close()"
    >
      <form class="form" (submit)="$event.preventDefault()">
        <label class="field-label" for="ir-name" i18n="@@my-work.rule.name">Name</label>
        <input
          id="ir-name"
          name="name"
          nz-input
          [ngModel]="draft().name"
          (ngModelChange)="patch({ name: $event })"
        />

        <label class="field-label" for="ir-connection" i18n="@@my-work.rule.connection"
          >Read Jira as (your personal connection)</label
        >
        <nz-select
          id="ir-connection"
          name="connection"
          style="width: 100%"
          [ngModel]="draft().connectionId || null"
          (ngModelChange)="patch({ connectionId: $event })"
        >
          @for (c of readableConnections(); track c.id) {
            <nz-option [nzValue]="c.id" [nzLabel]="c.name + ' · ' + c.siteUrl" />
          }
        </nz-select>
        @if (!readableConnections().length) {
          <p class="hint" i18n="@@my-work.rule.noConnection">
            You have no personal Jira connection that can read issues yet.
            <a routerLink="/account" [queryParams]="{ section: 'connections' }"
              >Add one in My account</a
            >.
          </p>
        }

        <label class="field-label" for="ir-jql" i18n="@@my-work.rule.jql"
          >JQL: which issues are yours</label
        >
        <textarea
          id="ir-jql"
          name="jql"
          nz-input
          class="mono"
          [ngModel]="draft().jql"
          (ngModelChange)="patch({ jql: $event })"
          [nzAutosize]="{ minRows: 2, maxRows: 6 }"
          spellcheck="false"
        ></textarea>
        <p class="hint" i18n="@@my-work.rule.jqlHelp">
          Runs as your account, so currentUser() is you. An issue is proposed once, the first time
          it appears in the results: when it is assigned to you, moves into a status your JQL asks
          for, or is created.
        </p>

        <div class="grid">
          <div>
            <label class="field-label" for="ir-interval" i18n="@@my-work.rule.interval"
              >Check every (minutes)</label
            >
            <nz-input-number
              id="ir-interval"
              name="interval"
              [nzMin]="1"
              [nzMax]="1440"
              [ngModel]="draft().pollIntervalMinutes"
              (ngModelChange)="patch({ pollIntervalMinutes: $event })"
            />
          </div>
          <div>
            <label class="field-label" for="ir-workflow" i18n="@@my-work.rule.workflow"
              >Workflow to propose</label
            >
            <nz-select
              id="ir-workflow"
              name="workflow"
              nzShowSearch
              style="width: 100%"
              [ngModel]="draft().workflowId || null"
              (ngModelChange)="patch({ workflowId: $event })"
            >
              @for (w of choices(); track w.id) {
                <nz-option [nzValue]="w.id" [nzLabel]="w.label" />
              }
            </nz-select>
          </div>
        </div>
        @if (!choices().length) {
          <p class="hint" i18n="@@my-work.rule.noWorkflow">
            There is no workflow you may start yet. Create a private project on the
            <a routerLink="/projects">Projects</a> page and add a workflow to it, for example from
            the library.
          </p>
        }

        <label class="switch-row">
          <nz-switch
            name="enabled"
            [ngModel]="draft().enabled"
            (ngModelChange)="patch({ enabled: $event })"
          />
          <span i18n="@@my-work.rule.enabled">Check Jira with this rule</span>
        </label>
        @if (!editing()?.initialized) {
          <label
            nz-checkbox
            name="existing"
            [ngModel]="draft().includeExisting"
            (ngModelChange)="patch({ includeExisting: $event })"
          >
            <span i18n="@@my-work.rule.includeExisting"
              >Also propose the issues that already match now</span
            >
          </label>
        }
        @if (formError(); as e) {
          <p class="err" role="alert">{{ e }}</p>
        }
      </form>
      <div boPanelFooter class="footer">
        @if (editing(); as r) {
          <button
            nz-button
            nzDanger
            type="button"
            (click)="deleteRule(r)"
            i18n="@@my-work.rule.delete"
          >
            Delete rule
          </button>
        }
        <span class="spacer"></span>
        <button nz-button type="button" (click)="panel.close()" i18n="@@my-work.cancel">
          Cancel
        </button>
        <button
          nz-button
          nzType="primary"
          type="button"
          [disabled]="!!draftError()"
          [nzLoading]="saving()"
          (click)="saveRule()"
          i18n="@@my-work.rule.save"
        >
          Save rule
        </button>
      </div>
    </bo-side-panel>
  `,
  styles: [
    RELAY_TABLE_STYLES,
    `
      .small {
        font-size: 12px;
      }
      .mono {
        font-family: var(--bo-font-mono);
      }
      .chip.you {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .status[data-tone='error'] {
        color: var(--bo-blocked);
      }
      .status[data-tone='off'],
      .status[data-tone='pending'] {
        color: var(--bo-ink-3);
      }
      .err {
        color: var(--bo-blocked);
        font-size: 12px;
        max-width: 40ch;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      p.err {
        white-space: normal;
        max-width: none;
      }
      .form {
        display: flex;
        flex-direction: column;
      }
      .field-label {
        display: block;
        margin: 12px 0 4px;
        font-size: 12.5px;
        font-weight: 500;
      }
      .hint {
        margin: 6px 0 0;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .grid {
        display: grid;
        grid-template-columns: 160px minmax(0, 1fr);
        gap: 0 16px;
      }
      .switch-row {
        display: flex;
        gap: 10px;
        align-items: center;
        margin: 16px 0 8px;
        font-size: 13px;
      }
      .footer {
        display: flex;
        gap: 8px;
        align-items: center;
      }
      .spacer {
        flex: 1;
      }
    `,
  ],
})
export class MyWorkPageComponent {
  private readonly api = inject(MyWorkApi);
  private readonly workflowsApi = inject(WorkflowsApi);
  private readonly identity = inject(IdentityService);
  private readonly store = inject(ProjectsStore);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);

  readonly panel = panelParam('rule');
  readonly tabIndex = signal(0);
  readonly proposals = signal<Record<IntakeProposalState, IntakeProposal[]>>({
    NEW: [],
    STARTED: [],
    DISMISSED: [],
  });
  readonly rules = signal<IntakeRule[]>([]);
  readonly connections = signal<Connection[]>([]);
  readonly workflowsByProject = signal(new Map<string, WorkflowDefinition[]>());
  readonly loading = signal(false);
  readonly loaded = signal(false);
  readonly busy = signal<string | null>(null);
  readonly saving = signal(false);
  readonly draft = signal<IntakeRuleInput>(emptyRule());
  readonly formError = signal<string | null>(null);
  readonly choosing = signal<IntakeProposal | null>(null);
  readonly chosenWorkflow = signal<string | null>(null);

  readonly text = {
    reload: $localize`:@@my-work.reload:Reload`,
    nothingNew: $localize`:@@my-work.empty.nothingNew:Nothing new from Jira.`,
    nothingNewDetail: $localize`:@@my-work.empty.nothingNewDetail:Your rules keep checking; a new issue shows up here and as a notification.`,
    noRulesYet: $localize`:@@my-work.empty.noRules:Let Back Office watch your Jira issues.`,
    noRulesDetail: $localize`:@@my-work.empty.noRulesDetail:Create a rule: it reads Jira as you and proposes a workflow for every new issue. You decide what starts.`,
    newRule: $localize`:@@my-work.rule.new:New rule`,
    newRuleTitle: $localize`:@@my-work.rule.newTitle:New rule`,
    ruleSubtitle: $localize`:@@my-work.rule.subtitle:Which of your Jira issues to propose, and with which workflow`,
    noneStarted: $localize`:@@my-work.empty.noneStarted:Nothing started from your rules yet.`,
    noneDismissed: $localize`:@@my-work.empty.noneDismissed:Nothing dismissed.`,
    startOther: $localize`:@@my-work.startOther:Start another workflow`,
  };

  readonly newTitle = computed(() => {
    const n = this.proposals().NEW.length;
    return $localize`:@@my-work.tab.new:New` + (n ? ` ${n}` : '');
  });
  readonly rulesTitle = computed(
    () => $localize`:@@my-work.tab.rules:Rules` + ` ${this.rules().length}`,
  );
  readonly readableConnections = computed(() =>
    this.connections().filter((c) => (c.capabilities ?? []).includes('ISSUE_READ')),
  );
  readonly startableProjects = computed(() =>
    this.store.projects().filter((p) => this.identity.canInProject('run.start', p.id)),
  );
  readonly choices = computed<WorkflowChoice[]>(() =>
    workflowChoices(this.startableProjects(), this.workflowsByProject()),
  );
  readonly editing = computed(() => {
    const id = this.panel.value();
    return id && id !== 'new' ? (this.rules().find((r) => r.id === id) ?? null) : null;
  });
  readonly panelOpen = computed(() => this.panel.value() === 'new' || !!this.editing());
  readonly draftError = computed(() => ruleError(this.draft()));
  readonly chooseTitle = computed(
    () =>
      $localize`:@@my-work.chooseTitle:Start a workflow for ${this.choosing()?.issueKey ?? ''}:key:`,
  );
  readonly reload = () => this.load();

  constructor() {
    inject(BreadcrumbService).set([{ label: $localize`:@@my-work.crumb:My work` }]);
    this.store.ensure();
    this.load();
    inject(EventStreamService)
      .of('IntakeProposalsFound')
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((e) => {
        const owner = (e.payload as Record<string, unknown> | undefined)?.['ownerUserId'];
        if (owner === this.identity.me()?.user?.id) this.load();
      });
    effect(() => {
      const projects = this.startableProjects();
      untracked(() => this.loadWorkflows(projects.map((p) => p.id)));
    });
    effect(() => {
      const r = this.editing();
      this.panel.value();
      untracked(() => {
        this.formError.set(null);
        const d = r ? ruleInput(r) : emptyRule();
        if (!r) {
          d.connectionId = this.readableConnections()[0]?.id ?? '';
          d.workflowId = this.choices()[0]?.id ?? '';
        }
        this.draft.set(d);
      });
    });
  }

  items(state: IntakeProposalState): IntakeProposal[] {
    return this.proposals()[state];
  }

  status(r: IntakeRule) {
    return ruleStatus(r);
  }

  checkedText(at: string): string {
    return $localize`:@@my-work.rule.checked:last checked ${new Date(at).toLocaleString()}:at:`;
  }

  load(): void {
    this.loading.set(true);
    const none = of([] as IntakeProposal[]);
    forkJoin({
      NEW: this.api.proposals('NEW').pipe(catchError(() => none)),
      STARTED: this.api.proposals('STARTED').pipe(catchError(() => none)),
      DISMISSED: this.api.proposals('DISMISSED').pipe(catchError(() => none)),
      rules: this.api.rules().pipe(catchError(() => of([] as IntakeRule[]))),
      connections: this.api.myConnections().pipe(catchError(() => of([] as Connection[]))),
    }).subscribe(({ NEW, STARTED, DISMISSED, rules, connections }) => {
      this.proposals.set({ NEW, STARTED, DISMISSED });
      this.rules.set(rules);
      this.connections.set(connections);
      this.loading.set(false);
      this.loaded.set(true);
    });
  }

  private loadWorkflows(projectIds: string[]): void {
    if (!projectIds.length) return;
    forkJoin(
      projectIds.map((id) =>
        this.workflowsApi.listForProject(id).pipe(catchError(() => of([] as WorkflowDefinition[]))),
      ),
    ).subscribe((lists) =>
      this.workflowsByProject.set(new Map(projectIds.map((id, i) => [id, lists[i]]))),
    );
  }

  start(p: IntakeProposal, workflowId?: string | null): void {
    this.busy.set(p.id);
    this.api.start(p.id, workflowId).subscribe({
      next: (started) => {
        this.busy.set(null);
        this.message.success(
          $localize`:@@my-work.started:Started ${started.workflowName ?? ''}:workflow: for ${started.issueKey}:key:`,
        );
        this.load();
      },
      error: () => this.busy.set(null),
    });
  }

  chooseWorkflow(p: IntakeProposal): void {
    this.chosenWorkflow.set(p.workflowId ?? this.choices()[0]?.id ?? null);
    this.choosing.set(p);
  }

  startChosen(): void {
    const p = this.choosing();
    if (!p) return;
    this.choosing.set(null);
    this.start(p, this.chosenWorkflow());
  }

  dismiss(p: IntakeProposal): void {
    this.api.dismiss(p.id).subscribe(() => this.load());
  }

  restore(p: IntakeProposal): void {
    this.api.restore(p.id).subscribe(() => this.load());
  }

  openRule(id: string): void {
    this.panel.open(id);
  }

  patch(changes: Partial<IntakeRuleInput>): void {
    this.draft.update((d) => ({ ...d, ...changes }));
  }

  poll(r: IntakeRule): void {
    this.api.pollRule(r.id).subscribe(() => {
      this.message.info($localize`:@@my-work.rule.polling:Checking Jira now`);
      this.load();
    });
  }

  saveRule(): void {
    if (this.draftError()) return;
    this.saving.set(true);
    this.formError.set(null);
    const r = this.editing();
    const req = r ? this.api.updateRule(r.id, this.draft()) : this.api.createRule(this.draft());
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.panel.close();
        this.message.success($localize`:@@my-work.rule.saved:Rule saved`);
        this.load();
      },
      error: (err: { error?: { detail?: string; errors?: { message?: string }[] } }) => {
        this.saving.set(false);
        this.formError.set(
          err?.error?.errors?.[0]?.message ??
            err?.error?.detail ??
            $localize`:@@my-work.rule.saveFailed:The rule could not be saved`,
        );
      },
    });
  }

  async deleteRule(r: IntakeRule): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@my-work.rule.deleteConfirm.title:Delete the rule "${r.name}:name:"?`,
      $localize`:@@my-work.rule.deleteConfirm.content:Its proposals disappear; runs you already started stay.`,
      $localize`:@@my-work.rule.deleteConfirm.ok:Delete rule`,
    );
    if (!ok) return;
    this.api.deleteRule(r.id).subscribe(() => {
      this.panel.close();
      this.load();
    });
  }
}
