import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzCheckboxModule } from 'ng-zorro-antd/checkbox';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { forkJoin, of } from 'rxjs';
import { RunsApi } from '../../../core/api/runs.service';
import { ApprovalsApi } from '../../../core/api/approvals.service';
import { approveBlocker, initialIdentity, type Identity } from '../data/write-identity';
import { IdentityService } from '../../../core/auth/identity.service';
import type {
  ApprovalRequest,
  Artifact,
  ArtifactSummary,
  Decision,
  WriteIdentity,
} from '../../../core/api/types';
import { roleName } from '../../../core/i18n/catalog-labels';
import { RoleNamePipe } from '../../../core/i18n/catalog-labels.pipes';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { icu } from '../../../core/i18n/icu';
import { actorName } from '../../../core/ui/actor.component';
import { ArtifactViewerComponent } from '../../../core/ui/artifact-viewer.component';
import { timeAgo } from '../../../core/ui/pipes/time-ago.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { absoluteTime } from '../../../core/ui/relay/format';
import { KbdComponent } from '../../../core/ui/relay/kbd.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { ShortcutsService } from '../../../core/ui/relay/shortcuts.service';
import { SidePanelComponent } from '../../../core/ui/relay/side-panel.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import {
  ApprovalDecisionService,
  optionKey,
  optionLabel,
  optionStyle,
} from '../data/approval-decision.service';
import {
  DEFAULT_CANNOT_DECIDE,
  type OptionState,
  isApproveOption,
  isRequired,
  missingRequired,
  needsComment,
  optionState,
} from '../data/approval-gating';
import { assignedTo, assignedToText } from '../data/approval-assignment';
import { AssignApprovalDialogComponent } from './assign-approval-dialog.component';

interface EditableArtifact {
  summary: ArtifactSummary;
  original: string;
  content: string;
  editing: boolean;
  loadError?: string;
}

/**
 * Gate panel (Relay side panel): the question, why you, the checklist (required items first),
 * artefacts in tabs (editable inline, saved with the decision), a comment and the decision buttons
 * with shortcuts `a` approve, `r` send back for revision, `x` reject. Approve stays disabled until
 * every required item is ticked, and says why.
 */
@Component({
  selector: 'app-approval-detail-drawer',
  imports: [
    RoleNamePipe,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzCheckboxModule,
    NzIconModule,
    NzInputModule,
    NzRadioModule,
    NzTabsModule,
    ArtifactViewerComponent,
    AssignApprovalDialogComponent,
    BatonComponent,
    KbdComponent,
    ProvenanceComponent,
    SidePanelComponent,
    SkeletonComponent,
    HelpLinkComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <bo-side-panel
      [open]="!!approval()"
      [title]="approval()?.question || text.decision"
      [subtitle]="where()"
      [defaultWidth]="640"
      storageKey="backoffice.gatePanelWidth"
      [initialFocus]="focus() === 'checklist' ? checklistSelector : null"
      (closed)="close()"
    >
      @if (approval(); as a) {
        <ng-container boPanelHeader>
          <bo-baton [baton]="baton()" />
          @if (a.createdAt) {
            <span class="since" [attr.title]="abs(a.createdAt)" i18n="@@inbox.gate.requested"
              >requested {{ ago(a.createdAt) }}</span
            >
          }
        </ng-container>
      }
      @if (approval(); as a) {
        @if (a.kind === 'WRITE_CONFIRMATION' && a.write; as w) {
          <section class="block write" aria-labelledby="gate-write">
            <h3 id="gate-write" i18n="@@inbox.write.title">What the agent wants to write</h3>
            <p class="write-what">
              @if (w.action === 'JIRA_TRANSITION') {
                <span i18n="@@inbox.write.transition"
                  >Move <strong class="mono">{{ w.issueKey }}</strong> to
                  <strong>{{ w.toStatus }}</strong></span
                >
              } @else {
                <span i18n="@@inbox.write.comment"
                  >Comment on <strong class="mono">{{ w.issueKey }}</strong></span
                >
              }
              @if (w.connectionName) {
                <span class="muted" i18n="@@inbox.write.via">via {{ w.connectionName }}</span>
              }
            </p>
            @if (a.status === 'OPEN' && a.canDecide !== false && writeOptions(); as id) {
              <fieldset class="who" aria-describedby="who-hint">
                <legend i18n="@@inbox.write.identity.title">Who writes to Jira</legend>
                <nz-radio-group [ngModel]="writeAs()" (ngModelChange)="writeAs.set($event)">
                  @if (id.personalAllowed) {
                    <label nz-radio nzValue="PERSONAL" [nzDisabled]="!id.personalConnectionName">
                      @if (id.personalConnectionName) {
                        <span i18n="@@inbox.write.identity.personal"
                          >You, through your personal connection
                          {{ id.personalConnectionName }}</span
                        >
                      } @else {
                        <span i18n="@@inbox.write.identity.personalNone"
                          >You (you have no personal connection for this)</span
                        >
                      }
                    </label>
                  }
                  @if (id.systemAllowed || !id.personalAllowed) {
                    <label nz-radio nzValue="SYSTEM" [nzDisabled]="!id.systemAllowed">
                      <span i18n="@@inbox.write.identity.system"
                        >The system connection {{ id.systemConnectionName ?? '' }}</span
                      >
                    </label>
                  }
                </nz-radio-group>
                <p class="muted small" id="who-hint">
                  @if (writeAs() === 'PERSONAL') {
                    <span i18n="@@inbox.write.identity.personalHint"
                      >Jira shows the comment or the move as yours. The audit log records "via
                      personal connection".</span
                    >
                  } @else if (id.personalProblem) {
                    <span>{{ id.personalProblem }}</span>
                  }
                  @if (id.personalAllowed && !id.personalConnectionName) {
                    <a
                      routerLink="/account"
                      [queryParams]="{ section: 'connections' }"
                      i18n="@@inbox.write.identity.add"
                      >Add a personal connection</a
                    >
                  }
                </p>
              </fieldset>
            }
            @if (w.action === 'JIRA_COMMENT') {
              <pre
                class="write-text"
                aria-label="Comment text"
                i18n-aria-label="@@inbox.write.textLabel"
                >{{ w.comment }}</pre>
            }
            <p class="muted small" i18n="@@inbox.write.exact">
              Approve writes exactly this, without rendering it again. Reject writes nothing: the
              step leaves through its failure port.
            </p>
            <p class="muted small mono" [attr.title]="w.contentSha256">
              <span i18n="@@inbox.write.digest">SHA-256 {{ w.contentSha256.slice(0, 16) }}…</span>
              <bo-help-link topic="gates.writeConfirmations" variant="text"
                ><span i18n="@@inbox.write.help">About confirmations</span></bo-help-link
              >
            </p>
          </section>
        } @else if (a.context?.summary) {
          <section class="block">
            <p class="summary">{{ a.context?.summary }}</p>
            @if (confidence() !== null) {
              <div class="confidence" [attr.aria-label]="confidenceLabel()">
                <span class="c-label" i18n="@@inbox.gate.confidence">Agent confidence</span>
                <span class="meter" aria-hidden="true"
                  ><span [style.width.%]="confidence()" [class.low]="confidence()! < 80"></span
                ></span>
                <span class="c-value">{{ confidence() }}%</span>
              </div>
            }
          </section>
        }

        <section class="block" aria-labelledby="gate-why">
          <h3 id="gate-why" i18n="@@inbox.gate.whyYou">Why you</h3>
          <dl class="why">
            <div>
              <dt i18n="@@inbox.gate.accountable">Accountable</dt>
              <dd>
                @if (a.approvers?.accountable) {
                  {{ a.approvers?.accountable | roleName }}
                } @else {
                  <span i18n="@@inbox.gate.noAccountable">No accountable role set</span>
                }
              </dd>
            </div>
            <div>
              <dt i18n="@@inbox.gate.approvers">Approvers</dt>
              <dd>
                @if (a.approvers?.projectRoles?.length || a.approvers?.users?.length) {
                  {{ approversText(a) }}
                } @else {
                  <span i18n="@@inbox.gate.anyone"
                    >Anyone with run.approve on {{ a.projectKey }}</span
                  >
                }
              </dd>
            </div>
            <div>
              <dt i18n="@@inbox.gate.assignedTo">Assigned to</dt>
              <dd class="assigned">
                <span class="assignees">{{ assignedText(a) }}</span>
                @if (isMine(a)) {
                  <span class="you" i18n="@@inbox.gate.assignedToYou">(you)</span>
                }
                @if (a.assignment; as asg) {
                  <bo-provenance
                    class="assigned-by"
                    verb="assigned by"
                    i18n-verb="@@inbox.gate.assignedBy"
                    [actor]="asg.assignedBy"
                    [at]="asg.assignedAt"
                  />
                  @if (asg.comment) {
                    <blockquote class="assign-note">{{ asg.comment }}</blockquote>
                  }
                }
                @if (a.canAssign && !readonly()) {
                  <button
                    nz-button
                    nzType="link"
                    nzSize="small"
                    type="button"
                    class="assign-btn"
                    (click)="assigning.set(a)"
                    i18n="@@inbox.gate.assign"
                  >
                    Assign…
                  </button>
                }
              </dd>
            </div>
            @if (a.approvers?.forbidSelfApproval) {
              <div>
                <dt i18n="@@inbox.gate.rule">Rule</dt>
                <dd i18n="@@inbox.gate.noSelfApproval">
                  The person who started the run cannot approve it
                </dd>
              </div>
            }
            <div>
              <dt i18n="@@inbox.gate.run">Run</dt>
              <dd>
                <bo-provenance
                  verb="started by"
                  i18n-verb="@@inbox.gate.startedBy"
                  [actor]="a.startedBy"
                />
                &nbsp;·&nbsp;<a
                  [routerLink]="['/runs', a.runId]"
                  (click)="close()"
                  i18n="@@inbox.gate.openRun"
                  >Open run</a
                >
                @if (a.context?.mergeRequestUrl) {
                  &nbsp;·&nbsp;<a
                    [href]="a.context?.mergeRequestUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    i18n="@@inbox.gate.mergeRequest"
                    >Merge request</a
                  >
                }
              </dd>
            </div>
          </dl>
          @if (a.decision; as d) {
            <div class="decided">
              <bo-provenance [verb]="decidedVerb(a)" [actor]="d.decidedBy" [at]="d.decidedAt" />
              @if (d.comment) {
                <blockquote>{{ d.comment }}</blockquote>
              }
            </div>
          }
        </section>

        @if (checklist().length) {
          <section class="block" aria-labelledby="gate-checklist">
            <h3 id="gate-checklist">
              <span i18n="@@inbox.gate.checklist">Checklist</span>
              <span class="count">{{ requiredText() }}</span>
            </h3>
            <ul class="checklist">
              @for (item of checklist(); track item.id; let first = $first) {
                <li [class.req]="isRequired(item)">
                  <label
                    nz-checkbox
                    [attr.data-focus]="first ? 'checklist' : null"
                    [ngModel]="checked().has(item.id)"
                    (ngModelChange)="toggleCheck(item.id, $event)"
                    [nzDisabled]="!canEdit()"
                  >
                    {{ item.text }}
                  </label>
                  @if (isRequired(item)) {
                    <span class="tag" i18n="@@inbox.gate.required">required</span>
                  } @else {
                    <span class="tag" i18n="@@inbox.gate.optional">optional</span>
                  }
                </li>
              }
            </ul>
          </section>
        }

        @if (a.kind !== 'WRITE_CONFIRMATION') {
          <section
            class="block"
            aria-label="Artefacts"
            i18n-aria-label="@@inbox.gate.artefactsLabel"
          >
            <h3>
              <span i18n="@@inbox.gate.artefacts">Artefacts</span>
              <span class="count">{{ artifacts().length }}</span>
            </h3>
            @if (loadingArtifacts()) {
              <bo-skeleton variant="list" [rows]="4" />
            } @else if (artifacts().length === 0) {
              <p class="muted" i18n="@@inbox.gate.noArtefacts">
                No artefacts are attached to this gate.
              </p>
            } @else {
              <nz-tabs nzSize="small" [nzAnimated]="false">
                @for (art of artifacts(); track art.summary.id) {
                  <nz-tab [nzTitle]="tabTitle">
                    <ng-template #tabTitle>
                      {{ art.summary.name }}
                      @if (art.content !== art.original) {
                        <span class="edited" i18n="@@inbox.gate.edited">edited</span>
                      }
                    </ng-template>
                    <ng-template nz-tab>
                      <div class="artifact-toolbar">
                        <span class="muted mono"
                          >{{ art.summary.kind }} · {{ art.summary.mediaType }}</span
                        >
                        <span class="spacer"></span>
                        @if (canEdit()) {
                          @if (art.editing) {
                            <button
                              nz-button
                              nzSize="small"
                              type="button"
                              (click)="revert(art)"
                              [disabled]="art.content === art.original"
                              i18n="@@inbox.gate.revert"
                            >
                              Revert
                            </button>
                            <button
                              nz-button
                              nzSize="small"
                              type="button"
                              (click)="toggleEdit(art)"
                              i18n="@@inbox.gate.preview"
                            >
                              Preview
                            </button>
                          } @else {
                            <button
                              nz-button
                              nzSize="small"
                              type="button"
                              (click)="toggleEdit(art)"
                              [disabled]="!!art.loadError"
                            >
                              <nz-icon nzType="edit" />
                              <span i18n="@@inbox.gate.editBefore">Edit before deciding</span>
                            </button>
                          }
                        }
                      </div>
                      @if (art.loadError) {
                        <p class="danger">{{ art.loadError }}</p>
                      } @else if (art.editing) {
                        <textarea
                          nz-input
                          class="mono-textarea"
                          [attr.aria-label]="editLabel(art)"
                          [ngModel]="art.content"
                          (ngModelChange)="setContent(art, $event)"
                          [nzAutosize]="{ minRows: 14, maxRows: 36 }"
                        ></textarea>
                      } @else {
                        @defer (on immediate) {
                          <app-artifact-viewer
                            [content]="art.content"
                            [kind]="art.summary.kind"
                            [mediaType]="art.summary.mediaType"
                          />
                        } @placeholder {
                          <bo-skeleton variant="list" [rows]="6" />
                        }
                      }
                    </ng-template>
                  </nz-tab>
                }
              </nz-tabs>
              @if (editedCount() > 0) {
                <p class="muted small">{{ editedText() }}</p>
              }
            }
          </section>
        }
      }

      <div boPanelFooter>
        @if (approval(); as a) {
          @if (readonly()) {
            @if (a.status === 'EXPIRED') {
              <p class="foot-note" i18n="@@inbox.gate.expiredNote">
                This gate is expired; nothing to do.
              </p>
            } @else {
              <p class="foot-note" i18n="@@inbox.gate.decidedNote">
                This gate is decided; nothing to do.
              </p>
            }
          } @else if (a.canDecide === false) {
            <div class="cannot" role="note">
              <strong i18n="@@inbox.gate.cannotDecide">You cannot decide this gate.</strong>
              {{ a.cannotDecideReason || defaultCannotDecide }}
            </div>
          } @else {
            <textarea
              nz-input
              class="comment"
              [placeholder]="commentPlaceholder()"
              [ngModel]="comment()"
              (ngModelChange)="comment.set($event)"
              [nzAutosize]="{ minRows: 2, maxRows: 5 }"
              aria-label="Decision comment"
              i18n-aria-label="@@inbox.gate.commentLabel"
            ></textarea>
            <div class="decide">
              @for (option of options(); track option) {
                <button
                  nz-button
                  type="button"
                  [nzType]="style(option).type === 'primary' ? 'primary' : 'default'"
                  [nzDanger]="style(option).danger"
                  [nzLoading]="submitting() === option"
                  [disabled]="submitting() !== null || !state(option).enabled"
                  [attr.data-option]="option"
                  [attr.aria-describedby]="state(option).enabled ? null : 'gate-reason'"
                  (click)="submit(option)"
                >
                  {{ labelFor(a, option) }}
                  @if (key(option); as k) {
                    <bo-kbd [keys]="k" [quiet]="true" />
                  }
                </button>
              }
            </div>
            @if (reason(); as r) {
              <p class="reason" id="gate-reason" role="status">{{ r }}</p>
            }
          }
        }
      </div>
    </bo-side-panel>

    <app-assign-approval-dialog
      [approval]="assigning()"
      (closed)="assigning.set(null)"
      (assigned)="onAssigned($event)"
    />
  `,
  styles: [
    `
      .since {
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .who {
        border: 0;
        margin: 8px 0;
        padding: 8px 10px;
        border-radius: 8px;
        background: var(--bo-surface-2);
        min-width: 0;
      }
      .who legend {
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-2);
        padding: 0;
        border: 0;
        width: auto;
        margin: 0 0 4px;
      }
      .who nz-radio-group {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .who label {
        white-space: normal;
      }
      .write-what {
        display: flex;
        flex-wrap: wrap;
        gap: 4px 10px;
        margin: 0 0 8px;
      }
      .write-text {
        white-space: pre-wrap;
        overflow-wrap: anywhere;
        max-height: 320px;
        overflow: auto;
        margin: 0 0 8px;
        padding: 10px 12px;
        font-family: var(--bo-font-mono, monospace);
        font-size: 12.5px;
        background: var(--bo-surface-2);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
      }
      .block {
        margin-bottom: 20px;
      }
      h3 {
        font-size: 13px;
        font-weight: 600;
        margin: 0 0 8px;
        display: flex;
        align-items: baseline;
        gap: 8px;
      }
      .count {
        font-weight: 400;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .summary {
        white-space: pre-wrap;
        margin: 0 0 10px;
        color: var(--bo-ink);
        line-height: 1.6;
      }
      .confidence {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: 12px;
        color: var(--bo-ink-3);
      }
      .meter {
        flex: 0 0 140px;
        height: 6px;
        border-radius: 999px;
        background: var(--bo-surface-3);
        overflow: hidden;
      }
      .meter span {
        display: block;
        height: 100%;
        background: var(--bo-done);
      }
      .meter span.low {
        background: var(--bo-you);
      }
      .c-value {
        font-family: var(--bo-font-mono);
        color: var(--bo-ink-2);
      }
      .why {
        margin: 0;
        display: grid;
        gap: 6px;
      }
      .why > div {
        display: grid;
        grid-template-columns: 110px minmax(0, 1fr);
        gap: 12px;
        font-size: 13px;
      }
      .assigned {
        display: flex;
        flex-wrap: wrap;
        align-items: baseline;
        gap: 4px 8px;
      }
      .assignees {
        font-weight: 500;
      }
      .you,
      .assigned-by {
        color: var(--bo-ink-3);
        font-size: 12px;
      }
      .assign-note {
        flex-basis: 100%;
        margin: 2px 0 0;
        padding-left: 8px;
        border-left: 2px solid var(--bo-line-strong);
        color: var(--bo-ink-2);
        font-size: 12px;
      }
      .assign-btn {
        padding: 0;
        height: auto;
      }
      dt {
        color: var(--bo-ink-3);
      }
      dd {
        margin: 0;
        color: var(--bo-ink);
      }
      .decided {
        margin-top: 10px;
        padding: 10px 12px;
        border-radius: 8px;
        background: var(--bo-surface-2);
      }
      blockquote {
        margin: 6px 0 0;
        padding-left: 10px;
        border-left: 2px solid var(--bo-line-strong);
        color: var(--bo-ink-2);
        white-space: pre-wrap;
      }
      .checklist {
        list-style: none;
        margin: 0;
        padding: 0;
        border: 1px solid var(--bo-line);
        border-radius: 8px;
      }
      .checklist li {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
        padding: 8px 12px;
        border-bottom: 1px solid var(--bo-line);
      }
      .checklist li:last-child {
        border-bottom: 0;
      }
      .tag {
        flex: none;
        font-size: 11px;
        color: var(--bo-ink-3);
        padding-top: 2px;
      }
      li.req .tag {
        color: var(--bo-you-ink);
        font-weight: 500;
      }
      .artifact-toolbar {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
      }
      .spacer {
        flex: 1;
      }
      .edited {
        margin-left: 6px;
        font-size: 11px;
        color: var(--bo-you-ink);
      }
      .small {
        font-size: 12px;
      }
      .comment {
        margin-bottom: 10px;
      }
      .decide {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        justify-content: flex-end;
      }
      .decide button {
        display: inline-flex;
        align-items: center;
        gap: 8px;
      }
      .reason {
        margin: 8px 0 0;
        font-size: 12px;
        color: var(--bo-ink-2);
        text-align: right;
      }
      .cannot {
        font-size: 13px;
        color: var(--bo-ink-2);
        padding: 10px 12px;
        border-radius: 8px;
        background: var(--bo-idle-soft);
      }
      .foot-note {
        margin: 0;
        font-size: 13px;
        color: var(--bo-ink-3);
      }
    `,
  ],
})
export class ApprovalDetailDrawerComponent {
  private readonly runs = inject(RunsApi);
  private readonly approvalsApi = inject(ApprovalsApi);
  private readonly decisions = inject(ApprovalDecisionService);
  /** ADR 0035: who the caller could write as (write confirmations only), and the choice. */
  readonly writeOptions = signal<WriteIdentity | null>(null);
  readonly writeAs = signal<Identity | null>(null);

  readonly approval = input<ApprovalRequest | null>(null);
  /** `checklist`: open with the focus on the first checklist item (the `a` shortcut in lists). */
  readonly focus = input<'checklist' | null>(null);
  readonly closed = output<void>();
  readonly decided = output<ApprovalRequest>();
  /** The approval changed without being decided (assigned to other users); hosts replace their copy. */
  readonly changed = output<ApprovalRequest>();
  /** The approval being assigned (the Assign dialog is open while set). */
  readonly assigning = signal<ApprovalRequest | null>(null);
  private readonly identity = inject(IdentityService);

  readonly artifacts = signal<EditableArtifact[]>([]);
  readonly loadingArtifacts = signal(false);
  readonly submitting = signal<string | null>(null);
  readonly readonly = computed(() => this.approval()?.status !== 'OPEN');
  /** Artefacts and checklist are editable only on an open gate the caller may decide. */
  readonly canEdit = computed(() => !this.readonly() && this.approval()?.canDecide !== false);
  readonly editedCount = computed(
    () => this.artifacts().filter((a) => a.content !== a.original).length,
  );
  readonly comment = signal('');
  /** Ticked checklist item ids (the recorded ones for a decided gate). */
  readonly checked = signal<ReadonlySet<string>>(new Set());
  readonly defaultCannotDecide = DEFAULT_CANNOT_DECIDE;
  readonly isRequired = isRequired;
  readonly checklistSelector = '[data-focus=checklist] input';

  readonly baton = computed(() => batonOf.approval(this.approval() ?? {}));
  readonly where = computed(() => {
    const a = this.approval();
    return a
      ? [a.projectKey, a.issueKey || a.workflowName, a.nodeId].filter(Boolean).join(' · ')
      : '';
  });
  /** Required items first. */
  readonly checklist = computed(() =>
    [...(this.approval()?.checklist ?? [])].sort(
      (x, y) => Number(isRequired(y)) - Number(isRequired(x)),
    ),
  );
  readonly requiredTotal = computed(() => this.checklist().filter(isRequired).length);
  readonly requiredDone = computed(
    () => this.requiredTotal() - missingRequired(this.approval() ?? {}, this.checked()).length,
  );
  readonly options = computed(() => this.approval()?.options ?? []);
  readonly confidence = computed(() => {
    const c = this.approval()?.context?.confidence;
    return c === null || c === undefined ? null : Math.round(c * 100);
  });
  /** The first reason an option is unavailable, spelled out (never only a tooltip). */
  readonly reason = computed(() => {
    for (const o of this.options()) {
      const s = this.state(o);
      if (!s.enabled && s.reason) {
        const reason = s.reason.charAt(0).toLowerCase() + s.reason.slice(1);
        return $localize`:@@inbox.gate.unavailable:${this.label(o)}:option: is unavailable: ${reason}:reason:.`;
      }
    }
    return null;
  });
  readonly commentPlaceholder = computed(() =>
    this.options().some(needsComment)
      ? $localize`:@@inbox.gate.commentNeeded:Comment for the agent (needed to send back or reject)`
      : $localize`:@@inbox.gate.commentOptional:Comment (optional)`,
  );
  readonly text = {
    decision: $localize`:@@inbox.gate.decision:Decision`,
  };
  readonly confidenceLabel = computed(
    () =>
      $localize`:@@inbox.gate.confidenceLabel:Agent confidence ${this.confidence()}:percent: percent`,
  );
  readonly requiredText = computed(() =>
    icu(
      $localize`:@@inbox.gate.requiredDone:{total, plural, one {{done} of # required done} other {{done} of # required done}}`,
      { total: this.requiredTotal(), done: this.requiredDone() },
    ),
  );
  readonly editedText = computed(() =>
    icu(
      $localize`:@@inbox.gate.editedCount:{count, plural, one {# edited artefact is saved with your decision.} other {# edited artefacts are saved with your decision.}}`,
      { count: this.editedCount() },
    ),
  );

  editLabel(art: EditableArtifact): string {
    return $localize`:@@inbox.gate.editLabel:Edit ${art.summary.name ?? ''}:name:`;
  }

  constructor() {
    effect(() => {
      const a = this.approval();
      untracked(() => {
        this.comment.set('');
        this.checked.set(new Set(a?.decision?.checkedItems ?? []));
        this.submitting.set(null);
        if (a) this.loadArtifacts(a);
        else this.artifacts.set([]);
        this.loadIdentity(a);
      });
    });
    const open = () => !!this.approval() && this.canEdit();
    const shortcut = (keys: string, description: string, pick: (o: string) => boolean) => ({
      keys,
      description,
      group: $localize`:@@inbox.gate.shortcutGroup:Gate`,
      when: () => open() && this.options().some(pick),
      handler: () => {
        const option = this.options().find(pick);
        if (!option) return;
        if (this.state(option).enabled) this.submit(option);
        else if (isApproveOption(option)) this.focusChecklist();
        else document.querySelector<HTMLTextAreaElement>('textarea.comment')?.focus();
      },
    });
    inject(ShortcutsService).register(
      [
        shortcut(
          'a',
          $localize`:@@inbox.gate.shortcut.approve:Approve`,
          (o) => optionKey(o) === 'a',
        ),
        shortcut(
          'r',
          $localize`:@@inbox.gate.shortcut.revise:Send back for revision`,
          (o) => optionKey(o) === 'r',
        ),
        shortcut('x', $localize`:@@inbox.gate.shortcut.reject:Reject`, (o) => optionKey(o) === 'x'),
      ],
      inject(DestroyRef),
    );
  }

  style = optionStyle;
  label = optionLabel;

  /** Write confirmations (ADR 0033) say what the button does to Jira. */
  labelFor(a: { kind?: string | null }, option: string): string {
    if (a.kind !== 'WRITE_CONFIRMATION') return optionLabel(option);
    switch (option.toUpperCase()) {
      case 'APPROVE':
        return $localize`:@@inbox.write.approve:Approve and write`;
      case 'REJECT':
        return $localize`:@@inbox.write.reject:Reject (write nothing)`;
      default:
        return optionLabel(option);
    }
  }
  key = optionKey;
  ago = timeAgo;
  abs = absoluteTime;

  /** Enabled state and reason of a decision button (`canDecide`, comment, required checklist). */
  state(option: string): OptionState {
    const a = this.approval();
    if (!a) return { enabled: false, reason: null };
    const base = optionState(a, option, this.checked(), this.comment());
    if (base.enabled && a.kind === 'WRITE_CONFIRMATION' && isApproveOption(option)) {
      const blocked = approveBlocker(this.writeOptions(), this.writeAs());
      if (blocked) return { enabled: false, reason: blocked };
    }
    return base;
  }

  toggleCheck(id: string, on: boolean): void {
    this.checked.update((s) => {
      const next = new Set(s);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  assignedText(a: ApprovalRequest): string {
    return assignedToText(a);
  }

  isMine(a: ApprovalRequest): boolean {
    return assignedTo(a, this.identity.user()?.id);
  }

  onAssigned(updated: ApprovalRequest): void {
    this.assigning.set(null);
    this.changed.emit(updated);
  }

  approversText(a: ApprovalRequest): string {
    const roles = (a.approvers?.projectRoles ?? []).map((r) => roleName(r));
    const users = a.approvers?.users?.length ?? 0;
    const people = users
      ? icu(
          $localize`:@@inbox.gate.namedPeople:{count, plural, one {# named person} other {# named people}}`,
          {
            count: users,
          },
        )
      : null;
    if (roles.length && people)
      return $localize`:@@inbox.gate.rolesAndPeople:${roles.join(', ')}:roles: and ${people}:people:`;
    return roles.length ? roles.join(', ') : (people ?? '');
  }

  decidedVerb(a: ApprovalRequest): string {
    const o = (a.decision?.option ?? '').toUpperCase();
    return o === 'APPROVE'
      ? $localize`:@@inbox.gate.approvedBy:approved by`
      : o === 'REJECT'
        ? $localize`:@@inbox.gate.rejectedBy:rejected by`
        : o === 'REVISE'
          ? $localize`:@@inbox.gate.sentBackBy:sent back by`
          : $localize`:@@inbox.gate.decidedBy:decided by`;
  }

  decisionTitle(a: ApprovalRequest): string {
    const option = a.decision?.option ?? '';
    return a.decision?.decidedBy
      ? $localize`:@@inbox.gate.decisionTitleBy:Decided: ${option}:option: by ${actorName(a.decision.decidedBy)}:name:`
      : $localize`:@@inbox.gate.decisionTitle:Decided: ${option}:option:`;
  }

  toggleEdit(art: EditableArtifact): void {
    this.artifacts.update((list) =>
      list.map((x) => (x === art ? { ...x, editing: !x.editing } : x)),
    );
  }

  setContent(art: EditableArtifact, content: string): void {
    this.artifacts.update((list) => list.map((x) => (x === art ? { ...x, content } : x)));
  }

  revert(art: EditableArtifact): void {
    this.artifacts.update((list) =>
      list.map((x) => (x === art ? { ...x, content: x.original } : x)),
    );
  }

  submit(option: string): void {
    const a = this.approval();
    if (!a || this.submitting() || !this.state(option).enabled) return;
    const edited = this.artifacts()
      .filter((x) => x.content !== x.original)
      .map((x) => ({ artifactId: x.summary.id!, content: x.content }));
    const decision: Decision = {
      option,
      comment: this.comment().trim() || null,
      ...(a.checklist?.length ? { checkedItems: [...this.checked()] } : {}),
      ...(edited.length ? { editedArtifacts: edited } : {}),
      ...(a.kind === 'WRITE_CONFIRMATION' && isApproveOption(option) && this.writeAs()
        ? { writeAs: this.writeAs() }
        : {}),
    };
    this.submitting.set(option);
    this.decisions.decide(a, decision).subscribe({
      next: (updated) => {
        this.submitting.set(null);
        this.decided.emit(updated);
      },
      error: () => this.submitting.set(null),
    });
  }

  close(): void {
    this.closed.emit();
  }

  /** ADR 0035: loads who the caller could write a confirmed Jira write as. */
  private loadIdentity(a: ApprovalRequest | null): void {
    this.writeOptions.set(null);
    this.writeAs.set(null);
    if (!a?.id || a.kind !== 'WRITE_CONFIRMATION' || a.status !== 'OPEN' || a.canDecide === false)
      return;
    this.approvalsApi.writeIdentity(a.id).subscribe({
      next: (o) => {
        if (this.approval()?.id !== a.id) return;
        this.writeOptions.set(o);
        this.writeAs.set(initialIdentity(o));
      },
      error: () => this.writeOptions.set(null),
    });
  }

  private focusChecklist(): void {
    const unticked = document.querySelector<HTMLElement>(
      '[data-focus=checklist] input, .checklist input',
    );
    unticked?.focus();
  }

  private loadArtifacts(a: ApprovalRequest): void {
    const summaries = a.context?.artifacts ?? [];
    if (!summaries.length || !a.runId) {
      this.artifacts.set([]);
      return;
    }
    this.loadingArtifacts.set(true);
    forkJoin(
      summaries.map((s) =>
        s.id ? this.runs.artifact(a.runId!, s.id) : of<Artifact>({ ...s, content: '' }),
      ),
    ).subscribe({
      next: (full) => {
        this.artifacts.set(
          full.map((f, i) => ({
            summary: summaries[i],
            original: f.content ?? '',
            content: f.content ?? '',
            editing: false,
          })),
        );
        this.loadingArtifacts.set(false);
      },
      error: () => {
        this.artifacts.set(
          summaries.map((s) => ({
            summary: s,
            original: '',
            content: '',
            editing: false,
            loadError: $localize`:@@inbox.gate.loadError:Could not load this artefact. Open the run to see it.`,
          })),
        );
        this.loadingArtifacts.set(false);
      },
    });
  }
}
