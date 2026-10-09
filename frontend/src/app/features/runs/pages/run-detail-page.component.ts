import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzRadioModule } from 'ng-zorro-antd/radio';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTabsModule } from 'ng-zorro-antd/tabs';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import { debounceTime, filter, interval, startWith } from 'rxjs';
import { ApprovalsApi } from '../../../core/api/approvals.service';
import { RunsApi } from '../../../core/api/runs.service';
import type {
  ApprovalRequest,
  Artifact,
  ArtifactSummary,
  LogLine,
  NodeExecution,
  WorkflowDefinitionInput,
  WorkflowRun,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { EventStreamService } from '../../../core/events/event-stream.service';
import { InboxService } from '../../../core/state/inbox.service';
import { ProjectsStore } from '../../../core/state/projects.store';
import { RunDetailsStore } from '../../../core/state/run-details.store';
import { ArtifactViewerComponent } from '../../../core/ui/artifact-viewer.component';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { JsonViewComponent } from '../../../core/ui/json-view.component';
import { durationBetween } from '../../../core/ui/pipes/duration.pipe';
import { batonOf } from '../../../core/ui/relay/baton';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { icu } from '../../../core/i18n/icu';
import { fixed, formatNumber } from '../../../core/i18n/numbers';
import { absolute, stepFacts, type Fact } from '../data/step-facts';
import { EmptyComponent } from '../../../core/ui/relay/empty.component';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { PageHeaderComponent, type PageAction } from '../../../core/ui/relay/page-header.component';
import { ProvenanceComponent } from '../../../core/ui/relay/provenance.component';
import { RunTrackComponent } from '../../../core/ui/relay/run-track.component';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import type { TrackSegment } from '../../../core/ui/relay/relay-track';
import {
  ApprovalDetailDrawerComponent,
  AssignApprovalDialogComponent,
  assignedToText,
} from '../../inbox/public-api';
import {
  NODE_KIND_META,
  WorkflowCanvasComponent,
  autoLayout,
  needsLayout,
  normalizeDefinition,
} from '../../workflows/public-api';

import { ProvenancePanelComponent } from '../components/provenance-panel/provenance-panel.component';
import { RepositoryResultsComponent } from '../components/repository-results.component';

const ACTIVE = new Set(['PENDING', 'RUNNING', 'WAITING']);

/**
 * Run detail: header with the full relay track, a waiting banner (gate: open the gate panel; CI:
 * the signal and a manual signal form), the node timeline with provenance and costs on the left,
 * the selected node (summary, artefacts, live logs) on the right, and the canvas replay on a tab.
 */
@Component({
  selector: 'app-run-detail-page',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzRadioModule,
    NzSwitchModule,
    NzTabsModule,
    NzTooltipModule,
    ArtifactViewerComponent,
    JsonViewComponent,
    BatonComponent,
    EmptyComponent,
    GlyphComponent,
    PageHeaderComponent,
    ProvenanceComponent,
    RunTrackComponent,
    SkeletonComponent,
    WorkflowCanvasComponent,
    ApprovalDetailDrawerComponent,
    AssignApprovalDialogComponent,
    ProvenancePanelComponent,
    RepositoryResultsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      @if (!run()) {
        <bo-skeleton variant="detail" label="Loading the run" i18n-label="@@runs.detail.loading" />
      } @else if (run(); as r) {
        <bo-page-header
          [title]="r.issueKey || runTitle(r)"
          [monoTitle]="!r.issueKey"
          [eyebrow]="r.projectKey + ' · ' + r.workflowName + ' v' + r.workflowVersion"
          [menu]="menu()"
          [help]="tab === 4 ? 'provenance' : 'run'"
        >
          <bo-baton boHeaderStatus [baton]="baton()" />
          <bo-provenance
            boHeaderMeta
            [verb]="r.startedBy ? text.startedBy : text.triggeredBy"
            [who]="r.startedBy ? null : (r.trigger?.type ?? 'system')"
            [actor]="r.startedBy"
            [at]="r.createdAt"
            [durationMs]="durationMs()"
            [cost]="r.cost"
            [usage]="totalUsage()"
          />
          @if (isActive() && can('run.cancel')) {
            <button
              boHeaderActions
              nz-button
              nzDanger
              type="button"
              (click)="cancel()"
              [nzLoading]="busy() === 'cancel'"
              i18n="@@runs.detail.cancel"
            >
              Cancel run
            </button>
          }
          @if (r.status === 'FAILED' && can('run.start')) {
            <button
              boHeaderActions
              nz-button
              nzType="primary"
              type="button"
              (click)="retry()"
              [nzLoading]="busy() === 'retry'"
              [disabled]="r.failure?.retryable === false"
              nz-tooltip
              [nzTooltipTitle]="r.failure?.retryable === false ? text.notRetryable : text.retryHint"
              i18n="@@runs.detail.retry"
            >
              Retry from failed step
            </button>
          }
          <div boHeaderBelow class="track">
            <bo-run-track
              [run]="r"
              variant="full"
              [selectedNodeId]="selectedNodeId()"
              (segmentSelected)="onSegment($event)"
            />
          </div>
        </bo-page-header>

        @if (r.waitingOn; as w) {
          @if (w.kind === 'WRITE_CONFIRMATION') {
            <section class="banner" data-holder="you" role="status">
              <bo-glyph holder="you" [size]="18" />
              <div class="banner-text">
                @if (gateMine()) {
                  <strong i18n="@@runs.detail.write.waitingForYou"
                    >Waiting for you to confirm a Jira write</strong
                  >
                } @else {
                  <strong i18n="@@runs.detail.write.waitingForApprover"
                    >Waiting for someone to confirm a Jira write</strong
                  >
                }
                <span i18n="@@runs.detail.write.at"
                  >at <span class="mono">{{ w.nodeId }}</span
                  >. Nothing is written to Jira until the write is approved.</span
                >
                @if (gateAssignedText(); as who) {
                  <span class="assigned">{{ who }}</span>
                }
              </div>
              @if (waitingApproval()?.canAssign) {
                <button
                  nz-button
                  type="button"
                  (click)="assigning.set(waitingApproval())"
                  i18n="@@runs.detail.write.assign"
                >
                  Assign…
                </button>
              }
              @if (w.approvalId) {
                <button
                  nz-button
                  [nzType]="gateMine() ? 'primary' : 'default'"
                  type="button"
                  (click)="openGate(w.approvalId)"
                >
                  @if (gateMine()) {
                    <span i18n="@@runs.detail.write.review">Review the write</span>
                  } @else {
                    <span i18n="@@runs.detail.write.view">View the write</span>
                  }
                </button>
              }
            </section>
          } @else if (w.kind === 'HUMAN_GATE') {
            <section class="banner" data-holder="you" role="status">
              <bo-glyph holder="you" [size]="18" />
              <div class="banner-text">
                @if (gateMine()) {
                  <strong i18n="@@runs.detail.gate.waitingForYou"
                    >Waiting for you (approver)</strong
                  >
                } @else {
                  <strong i18n="@@runs.detail.gate.waitingForApprover"
                    >Waiting for an approver</strong
                  >
                }
                <span i18n="@@runs.detail.gate.at"
                  >at <span class="mono">{{ w.nodeId }}</span
                  >. The run continues when the gate is decided.</span
                >
                @if (gateAssignedText(); as who) {
                  <span class="assigned">{{ who }}</span>
                }
              </div>
              @if (waitingApproval()?.canAssign) {
                <button
                  nz-button
                  type="button"
                  (click)="assigning.set(waitingApproval())"
                  i18n="@@runs.detail.gate.assign"
                >
                  Assign…
                </button>
              }
              @if (w.approvalId) {
                <button
                  nz-button
                  [nzType]="gateMine() ? 'primary' : 'default'"
                  type="button"
                  (click)="openGate(w.approvalId)"
                >
                  @if (gateMine()) {
                    <span i18n="@@runs.detail.gate.review">Review gate</span>
                  } @else {
                    <span i18n="@@runs.detail.gate.view">View gate</span>
                  }
                </button>
              }
            </section>
          } @else {
            <section class="banner" data-holder="ci" role="status">
              <bo-glyph holder="ci" [live]="true" [size]="18" />
              <div class="banner-text">
                <strong i18n="@@runs.detail.ci.waiting"
                  >Waiting for CI signal <span class="mono">{{ w.signal || '?' }}</span></strong
                >
                <span i18n="@@runs.detail.ci.at">
                  at <span class="mono">{{ w.nodeId }}</span
                  >. The project CI sends it with
                  <code>backoffice-ci signal {{ w.signal }} --status success|fail</code>.
                </span>
                @if (can('run.signal')) {
                  <form class="signal-form" (submit)="$event.preventDefault(); sendSignal()">
                    <input
                      nz-input
                      nzSize="small"
                      class="mono"
                      [(ngModel)]="signalName"
                      name="signal"
                      placeholder="signal"
                      i18n-placeholder="@@runs.detail.ci.signalPlaceholder"
                      style="width: 130px"
                      aria-label="Signal name"
                      i18n-aria-label="@@runs.detail.ci.signalName"
                    />
                    <nz-radio-group
                      [(ngModel)]="signalStatus"
                      name="status"
                      nzSize="small"
                      aria-label="Signal status"
                      i18n-aria-label="@@runs.detail.ci.signalStatus"
                    >
                      <label nz-radio-button nzValue="SUCCESS" i18n="@@runs.detail.ci.success"
                        >Success</label
                      >
                      <label nz-radio-button nzValue="FAIL" i18n="@@runs.detail.ci.fail"
                        >Fail</label
                      >
                    </nz-radio-group>
                    <input
                      nz-input
                      nzSize="small"
                      [(ngModel)]="signalMessage"
                      name="message"
                      placeholder="Message (optional)"
                      i18n-placeholder="@@runs.detail.ci.messagePlaceholder"
                      style="width: 220px"
                      aria-label="Signal message"
                      i18n-aria-label="@@runs.detail.ci.message"
                    />
                    <button
                      nz-button
                      nzSize="small"
                      type="submit"
                      [nzLoading]="busy() === 'signal'"
                      [disabled]="!signalName.trim()"
                      i18n="@@runs.detail.ci.send"
                    >
                      Send signal manually
                    </button>
                  </form>
                }
              </div>
            </section>
          }
        }
        @if (r.failure; as fl) {
          <section class="banner" data-holder="blocked" role="alert">
            <bo-glyph holder="blocked" [size]="18" />
            <div class="banner-text">
              <strong i18n="@@runs.detail.failedAt"
                >Failed at <span class="mono">{{ fl.nodeId }}</span></strong
              >
              <span class="pre">{{ fl.message }}</span>
            </div>
          </section>
        }

        <nz-tabs [nzAnimated]="false" [(nzSelectedIndex)]="tab" class="run-tabs">
          <nz-tab nzTitle="Timeline" i18n-nzTitle="@@runs.detail.tab.timeline">
            <div class="detail-grid">
              <section
                class="timeline surface"
                aria-label="Node timeline"
                i18n-aria-label="@@runs.detail.timeline"
              >
                @if (!timeline().length) {
                  <bo-empty
                    tone="quiet"
                    title="No step has started yet."
                    i18n-title="@@runs.detail.timeline.empty"
                  />
                } @else {
                  <ol>
                    @for (s of timeline(); track s.id ?? $index) {
                      <li>
                        <button
                          type="button"
                          class="step"
                          [class.selected]="s.nodeId === selectedNodeId()"
                          (click)="selectNode(s.nodeId!)"
                          [attr.aria-pressed]="s.nodeId === selectedNodeId()"
                        >
                          <span class="rail-dot" [attr.data-holder]="nodeBaton(s).holder">
                            <bo-glyph
                              [holder]="nodeBaton(s).holder"
                              [live]="!!nodeBaton(s).live"
                              [size]="12"
                            />
                          </span>
                          <span class="step-main">
                            <span class="step-head">
                              <span class="step-name">{{ s.nodeName || s.nodeId }}</span>
                              @if ((s.attempt ?? 1) > 1) {
                                <span class="attempt" i18n="@@runs.detail.timeline.attempt"
                                  >attempt {{ s.attempt }}</span
                                >
                              }
                              @if (s.port) {
                                <span class="port mono">→ {{ s.port }}</span>
                              }
                            </span>
                            <bo-provenance
                              [who]="kindTitle(s)"
                              [detail]="s.templateKey ?? null"
                              [durationMs]="stepDuration(s)"
                              [cost]="s.cost?.amount ? s.cost : null"
                              [usage]="s.usage"
                            />
                            @if (s.error) {
                              <span class="step-error">{{ s.error }}</span>
                            }
                          </span>
                        </button>
                      </li>
                    }
                  </ol>
                }
              </section>

              <section
                class="node-panel surface"
                aria-label="Selected step"
                i18n-aria-label="@@runs.detail.node.panel"
              >
                @if (selectedExec(); as s) {
                  <header class="np-head">
                    <div>
                      <div class="np-eyebrow mono">{{ s.nodeId }}</div>
                      <h2>{{ s.nodeName || s.nodeId }}</h2>
                    </div>
                    <bo-baton [baton]="nodeBaton(s)" />
                  </header>
                  <bo-provenance
                    [who]="kindTitle(s)"
                    [detail]="s.templateKey ?? null"
                    [at]="s.startedAt"
                    [durationMs]="stepDuration(s)"
                    [cost]="s.cost"
                    [usage]="s.usage"
                  >
                    @if (s.confidence !== null && s.confidence !== undefined) {
                      <span>&nbsp;· {{ confidenceText(s.confidence) }}</span>
                    }
                  </bo-provenance>
                  @if (s.summary) {
                    <p class="np-summary">{{ s.summary }}</p>
                  }
                  @if (s.error) {
                    <p class="np-error">{{ s.error }}</p>
                  }
                } @else {
                  <header class="np-head">
                    <div>
                      <div class="np-eyebrow mono">
                        {{ r.workflowName }} v{{ r.workflowVersion }}
                      </div>
                      <h2 i18n="@@runs.detail.node.wholeRun">Whole run</h2>
                    </div>
                  </header>
                  <p class="muted small" i18n="@@runs.detail.node.selectHint">
                    Select a step on the relay track or in the timeline.
                  </p>
                }
                <section class="facts-block" aria-labelledby="facts-title">
                  <h3 class="facts-title" id="facts-title">
                    @if (selectedExec()) {
                      <span i18n="@@runs.detail.facts.thisStep">This step</span>
                    } @else {
                      <span i18n="@@runs.detail.facts.runInput">Run input</span>
                    }
                  </h3>
                  <dl class="facts">
                    @for (f of facts(); track f.label) {
                      <dt>{{ f.label }}</dt>
                      <dd [class.mono]="f.mono" [title]="f.value">{{ f.value }}</dd>
                    }
                  </dl>
                </section>
                <app-repository-results [results]="repositoryResults()" />

                <nz-tabs nzSize="small" [nzAnimated]="false">
                  <nz-tab [nzTitle]="artefactsTitle()">
                    @if (!nodeArtifacts().length) {
                      @if (selectedExec()) {
                        <p class="muted small" i18n="@@runs.detail.artefacts.noneForStep">
                          This step produced no artefacts.
                        </p>
                      } @else {
                        <p class="muted small" i18n="@@runs.detail.artefacts.none">
                          No artefacts yet.
                        </p>
                      }
                    } @else {
                      <div
                        class="art-list"
                        role="listbox"
                        aria-label="Artefacts"
                        i18n-aria-label="@@runs.detail.artefacts.list"
                      >
                        @for (a of nodeArtifacts(); track a.id) {
                          <button
                            type="button"
                            role="option"
                            class="art"
                            [class.selected]="a.id === selectedArtifact()?.id"
                            [attr.aria-selected]="a.id === selectedArtifact()?.id"
                            (click)="openArtifact(a)"
                          >
                            <span class="art-kind mono">{{ a.kind }}</span>
                            <span class="art-name">{{ a.name }}</span>
                            <span class="art-size mono">{{ sizeLabel(a.sizeBytes) }}</span>
                          </button>
                        }
                      </div>
                      @if (artifactLoading()) {
                        <bo-skeleton variant="list" [rows]="5" />
                      } @else if (artifactContent(); as art) {
                        <div class="art-bar">
                          <span class="muted mono small">{{ art.mediaType }}</span>
                          <span class="spacer"></span>
                          <button
                            nz-button
                            nzSize="small"
                            type="button"
                            (click)="copy(art.content ?? '')"
                            i18n="@@runs.detail.artefacts.copy"
                          >
                            Copy
                          </button>
                        </div>
                        @defer (on immediate) {
                          <app-artifact-viewer
                            [content]="art.content"
                            [kind]="art.kind"
                            [mediaType]="art.mediaType"
                          />
                        } @placeholder {
                          <bo-skeleton variant="list" [rows]="6" />
                        }
                      }
                    }
                  </nz-tab>
                  <nz-tab nzTitle="Logs" i18n-nzTitle="@@runs.detail.tab.logs">
                    <div class="log-bar">
                      <nz-radio-group
                        [ngModel]="logScope()"
                        (ngModelChange)="logScope.set($event)"
                        nzSize="small"
                        aria-label="Log scope"
                        i18n-aria-label="@@runs.detail.logs.scope"
                      >
                        <label
                          nz-radio-button
                          nzValue="node"
                          [nzDisabled]="!selectedNodeId()"
                          i18n="@@runs.detail.logs.thisStep"
                          >This step</label
                        >
                        <label nz-radio-button nzValue="all" i18n="@@runs.detail.logs.allSteps"
                          >All steps</label
                        >
                      </nz-radio-group>
                      <span class="spacer"></span>
                      <label class="follow">
                        <nz-switch
                          nzSize="small"
                          [(ngModel)]="follow"
                          aria-label="Follow new log lines"
                          i18n-aria-label="@@runs.detail.logs.followLabel"
                        />
                        <span i18n="@@runs.detail.logs.follow">Follow</span>
                      </label>
                      @if (isActive()) {
                        <span class="live-tag"
                          ><bo-glyph holder="agent" [live]="true" [size]="10" />
                          <span i18n="@@runs.detail.logs.live">live</span></span
                        >
                      }
                    </div>
                    <pre
                      class="code-block logs"
                      #logBox
                      tabindex="0"
                      aria-live="polite"
                      aria-label="Run logs"
                      i18n-aria-label="@@runs.detail.logs.label"
                    >@if (!visibleLogs().length) {<span class="muted" i18n="@@runs.detail.logs.empty">No log lines yet.</span>}@for (l of visibleLogs(); track $index) {<span class="log-line" [class.warn]="l.level === 'WARN'" [class.error]="l.level === 'ERROR'"><span class="dim">{{ l.at | date: 'HH:mm:ss' }}</span> <span class="dim">{{ l.nodeId }}</span> {{ l.message }}
</span>}</pre>
                  </nz-tab>
                  @if (r.review; as rev) {
                    <nz-tab nzTitle="Review" i18n-nzTitle="@@runs.detail.tab.review">
                      <p>
                        <span i18n="@@runs.detail.review.verdict"
                          >Verdict <strong>{{ rev.verdict }}</strong> · score
                          {{ rev.score ?? '-' }}</span
                        >
                        · {{ findingsText(rev.findings?.length ?? 0) }}
                        @if (r.trigger?.ciExecutionId) {
                          &nbsp;·&nbsp;<a
                            [routerLink]="['/ci', r.trigger?.ciExecutionId]"
                            i18n="@@runs.detail.review.openCi"
                            >Open CI review</a
                          >
                        }
                      </p>
                      @if (rev.summary) {
                        <p class="np-summary">{{ rev.summary }}</p>
                      }
                    </nz-tab>
                  }
                </nz-tabs>
              </section>
            </div>
          </nz-tab>
          <nz-tab nzTitle="Canvas replay" i18n-nzTitle="@@runs.detail.tab.canvas">
            <ng-template nz-tab>
              @if (snapshot(); as def) {
                @defer (on immediate) {
                  <div class="canvas-wrap surface">
                    <app-workflow-canvas
                      [definition]="def"
                      [readonly]="true"
                      [executions]="r.nodes ?? []"
                      [currentNodeId]="r.currentNodeId ?? null"
                      [selectedNodeId]="selectedNodeId()"
                      [showMinimap]="false"
                      (nodeSelected)="onCanvasSelect($event)"
                    />
                  </div>
                } @placeholder {
                  <bo-skeleton variant="detail" />
                }
              } @else {
                <bo-empty
                  tone="quiet"
                  title="This run has no definition snapshot to replay."
                  i18n-title="@@runs.detail.canvas.empty"
                />
              }
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Input" i18n-nzTitle="@@runs.detail.tab.input">
            <ng-template nz-tab>
              <app-json-view [value]="r.input ?? {}" maxHeight="60vh" />
            </ng-template>
          </nz-tab>
          <nz-tab nzTitle="Provenance" i18n-nzTitle="@@runs.detail.tab.provenance">
            <ng-template nz-tab>
              @defer (on immediate) {
                <app-provenance-panel [runId]="r.id!" />
              } @placeholder {
                <bo-skeleton variant="list" [rows]="3" />
              }
            </ng-template>
          </nz-tab>
        </nz-tabs>
      }
    </div>

    <app-approval-detail-drawer
      [approval]="gate()"
      [focus]="'checklist'"
      (closed)="gate.set(null)"
      (decided)="gate.set(null); load()"
      (changed)="onGateChanged($event)"
    />
    <app-assign-approval-dialog
      [approval]="assigning()"
      (closed)="assigning.set(null)"
      (assigned)="assigning.set(null); onGateChanged($event)"
    />
  `,
  styles: [
    `
      .track {
        padding: 14px 16px 10px;
        background: var(--bo-surface);
        border: 1px solid var(--bo-line);
        border-radius: var(--bo-radius-card);
        overflow-x: auto;
      }
      .banner {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 12px 16px;
        margin-bottom: 12px;
        border-radius: var(--bo-radius-card);
        background: var(--bo-idle-soft);
      }
      .banner[data-holder='you'] {
        background: var(--bo-you-soft);
        box-shadow: inset 3px 0 0 var(--bo-you);
        align-items: center;
      }
      .banner[data-holder='ci'] {
        background: var(--bo-ci-soft);
        box-shadow: inset 3px 0 0 var(--bo-ci);
      }
      .banner[data-holder='blocked'] {
        background: var(--bo-blocked-soft);
        box-shadow: inset 3px 0 0 var(--bo-blocked);
      }
      .banner bo-glyph {
        margin-top: 2px;
      }
      .banner-text {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 2px;
        font-size: 13px;
        min-width: 0;
      }
      .banner .assigned {
        color: var(--bo-ink-2);
      }
      .banner code {
        font-size: 12px;
      }
      .pre {
        white-space: pre-wrap;
      }
      .signal-form {
        display: flex;
        gap: 8px;
        align-items: center;
        flex-wrap: wrap;
        margin-top: 8px;
      }
      .detail-grid {
        display: grid;
        grid-template-columns: minmax(300px, 2fr) minmax(0, 3fr);
        gap: 16px;
        align-items: start;
      }
      .facts-block {
        margin: 14px 0 10px;
        padding-top: 12px;
        border-top: 1px solid var(--bo-line);
      }
      .facts-title {
        margin: 0 0 8px;
        font-size: 12px;
        font-weight: 600;
        color: var(--bo-ink-2);
      }
      .facts {
        display: grid;
        grid-template-columns: max-content minmax(0, 1fr);
        gap: 6px 16px;
        margin: 0;
        font-size: 13px;
      }
      .facts dt {
        color: var(--bo-ink-3);
      }
      .facts dd {
        margin: 0;
        min-width: 0;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .facts dd.mono {
        font-size: 12px;
      }
      @media (max-width: 1100px) {
        .detail-grid {
          grid-template-columns: 1fr;
        }
      }
      .timeline {
        padding: 8px;
      }
      .timeline ol {
        list-style: none;
        margin: 0;
        padding: 0;
        position: relative;
      }
      .step {
        all: unset;
        box-sizing: border-box;
        display: flex;
        gap: 10px;
        width: 100%;
        padding: 8px 10px;
        border-radius: 8px;
        cursor: pointer;
        position: relative;
      }
      .timeline li:not(:last-child) .step::after {
        content: '';
        position: absolute;
        left: 21px;
        top: 30px;
        bottom: -8px;
        width: 1px;
        background: var(--bo-line);
      }
      .step:hover {
        background: var(--bo-surface-2);
      }
      .step.selected {
        background: var(--bo-accent-soft);
      }
      .step.selected bo-provenance {
        color: var(--bo-ink-2);
      }
      .step:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .rail-dot {
        flex: none;
        width: 24px;
        height: 24px;
        border-radius: 50%;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: var(--bo-surface);
        box-shadow: inset 0 0 0 1px var(--bo-line);
        z-index: 1;
      }
      .step-main {
        display: flex;
        flex-direction: column;
        gap: 2px;
        min-width: 0;
      }
      .step-head {
        display: flex;
        gap: 8px;
        align-items: baseline;
        flex-wrap: wrap;
      }
      .step-name {
        font-weight: 500;
        font-size: 13.5px;
      }
      .attempt,
      .port {
        font-size: 11.5px;
        color: var(--bo-ink-2);
      }
      .step-error {
        font-size: 12px;
        color: var(--bo-blocked-ink);
        white-space: pre-wrap;
      }
      .node-panel {
        padding: 16px 18px;
        min-width: 0;
        /* Stays in view while a long timeline scrolls. */
        position: sticky;
        top: 16px;
      }
      .np-head {
        display: flex;
        align-items: flex-start;
        justify-content: space-between;
        gap: 12px;
      }
      .np-head h2 {
        margin: 0;
        font-size: 16px;
      }
      .np-eyebrow {
        color: var(--bo-ink-3);
        font-size: 11px;
      }
      .np-summary {
        white-space: pre-wrap;
        margin: 12px 0 4px;
        line-height: 1.6;
      }
      .np-error {
        white-space: pre-wrap;
        color: var(--bo-blocked-ink);
        margin: 8px 0;
      }
      .small {
        font-size: 12px;
      }
      .spacer {
        flex: 1;
      }
      .art-list {
        display: flex;
        flex-wrap: wrap;
        gap: 6px;
        margin-bottom: 10px;
      }
      .art {
        all: unset;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        gap: 8px;
        padding: 5px 10px;
        border-radius: 8px;
        border: 1px solid var(--bo-line);
        font-size: 12.5px;
        max-width: 100%;
      }
      .art:hover {
        border-color: var(--bo-line-strong);
      }
      .art.selected {
        border-color: var(--bo-accent);
        background: var(--bo-accent-soft);
      }
      .art:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .art-kind,
      .art-size {
        font-size: 10.5px;
        color: var(--bo-ink-3);
      }
      .art-name {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .art-bar,
      .log-bar {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 8px;
      }
      .follow {
        display: inline-flex;
        gap: 6px;
        align-items: center;
        font-size: 12px;
        color: var(--bo-ink-2);
      }
      .live-tag {
        display: inline-flex;
        gap: 4px;
        align-items: center;
        font-size: 12px;
        color: var(--bo-agent-ink);
      }
      .logs {
        max-height: 420px;
        min-height: 160px;
      }
      .dim {
        opacity: 0.6;
      }
      .log-line.warn {
        color: #ffcf70;
      }
      .log-line.error {
        color: #ff9a88;
      }
      .canvas-wrap {
        height: 62vh;
        min-height: 420px;
        padding: 6px;
      }
      .canvas-wrap app-workflow-canvas {
        height: 100%;
      }
    `,
  ],
})
export class RunDetailPageComponent {
  private readonly api = inject(RunsApi);
  private readonly approvals = inject(ApprovalsApi);
  private readonly events = inject(EventStreamService);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly identity = inject(IdentityService);
  private readonly inbox = inject(InboxService);
  private readonly projects = inject(ProjectsStore);
  private readonly details = inject(RunDetailsStore);
  private readonly crumbs = inject(BreadcrumbService);

  readonly runId = input.required<string>();
  private readonly logBox = viewChild<ElementRef<HTMLElement>>('logBox');

  readonly run = signal<WorkflowRun | null>(null);
  readonly artifacts = signal<ArtifactSummary[]>([]);
  readonly selectedArtifact = signal<ArtifactSummary | null>(null);
  readonly artifactContent = signal<Artifact | null>(null);
  readonly artifactLoading = signal(false);
  readonly selectedNodeId = signal<string | null>(null);
  readonly logs = signal<LogLine[]>([]);
  readonly logScope = signal<'node' | 'all'>('node');
  readonly busy = signal<'cancel' | 'retry' | 'signal' | null>(null);
  readonly gate = signal<ApprovalRequest | null>(null);
  tab = 0;
  follow = true;
  signalName = '';
  signalStatus: 'SUCCESS' | 'FAIL' = 'SUCCESS';
  signalMessage = '';
  private lastLogAt: string | null = null;

  /** Project permission of the caller on the run's project (run.start / run.cancel / run.signal). */
  can(permission: string): boolean {
    const projectId = this.run()?.projectId;
    return !!projectId && this.identity.canInProject(permission, projectId);
  }

  readonly text = {
    startedBy: $localize`:@@runs.detail.startedBy:started by`,
    triggeredBy: $localize`:@@runs.detail.triggeredBy:triggered by`,
    notRetryable: $localize`:@@runs.detail.notRetryable:The engine marked this failure as not retryable`,
    retryHint: $localize`:@@runs.detail.retryHint:Continue from the failing step`,
  };

  runTitle(r: WorkflowRun): string {
    return $localize`:@@runs.detail.title:Run ${(r.id ?? '').slice(0, 8)}:id:`;
  }

  confidenceText(confidence: number): string {
    return $localize`:@@runs.detail.confidence:confidence ${formatNumber(confidence, { style: 'percent', maximumFractionDigits: 0 })}:percent:`;
  }

  findingsText(count: number): string {
    return icu(
      $localize`:@@runs.detail.review.findings:{count, plural, one {# finding} other {# findings}}`,
      { count },
    );
  }

  readonly artefactsTitle = computed(
    () => $localize`:@@runs.detail.tab.artefacts:Artefacts (${this.nodeArtifacts().length}:count:)`,
  );

  readonly isActive = computed(() => ACTIVE.has(this.run()?.status ?? ''));
  /** The open approval of the gate the run waits on (rendered question, canDecide). */
  readonly waitingApproval = signal<ApprovalRequest | null>(null);
  /**
   * Whether the caller can decide the open gate: from the approval itself, else from the inbox;
   * undefined while unknown. The header and the node panel both use it (one mapping).
   */
  readonly gateDecidable = computed<boolean | undefined>(() => {
    const id = this.run()?.waitingOn?.approvalId;
    if (!id) return undefined;
    const a = this.waitingApproval();
    if (a?.id === id && a.canDecide !== undefined && a.canDecide !== null) return a.canDecide;
    const known = this.inbox.open().find((x) => x.id === id);
    if (known) return known.canDecide !== false;
    return undefined;
  });
  readonly gateMine = computed(() => this.gateDecidable() === true);
  /** "Assigned to: Ann" (named assignees or the gate's roles) for the open gate; null while unknown. */
  readonly gateAssignedText = computed(() => {
    const a = this.waitingApproval();
    if (!a || a.id !== this.run()?.waitingOn?.approvalId) return null;
    return $localize`:@@runs.detail.gate.assignedTo:Assigned to: ${assignedToText(a)}:who:`;
  });
  /** The approval being assigned (the Assign dialog is open while set). */
  readonly assigning = signal<ApprovalRequest | null>(null);
  readonly baton = computed(() => {
    const r = this.run();
    if (!r) return { holder: 'idle' as const, label: '' };
    const gate = r.waitingOn?.kind === 'HUMAN_GATE' || r.waitingOn?.kind === 'WRITE_CONFIRMATION';
    const node = r.nodes?.find((n) => n.nodeId === r.currentNodeId)?.nodeName ?? null;
    return batonOf.run(r, { canDecide: gate ? this.gateDecidable() : undefined, nodeName: node });
  });
  readonly durationMs = computed(() => {
    const r = this.run();
    return r?.startedAt ? durationBetween(r.startedAt, r.finishedAt) : null;
  });
  readonly menu = computed<PageAction[]>(() => {
    const r = this.run();
    if (!r) return [];
    const out: PageAction[] = [];
    if (r.mergeRequest?.url)
      out.push({
        label: $localize`:@@runs.detail.menu.mergeRequest:Open merge request`,
        icon: 'branches',
        href: r.mergeRequest.url,
      });
    if (r.trigger?.ciExecutionId)
      out.push({
        label: $localize`:@@runs.detail.menu.ciExecution:Open CI execution`,
        icon: 'cloud-sync',
        link: ['/ci', r.trigger.ciExecutionId],
      });
    if (r.workflowId)
      out.push({
        label: $localize`:@@runs.detail.menu.workflow:Open workflow`,
        icon: 'apartment',
        link: ['/workflows', r.workflowId, 'edit'],
      });
    out.push({
      label: $localize`:@@runs.detail.menu.project:Open project`,
      icon: 'project',
      link: ['/projects', r.projectId],
    });
    out.push({
      label: $localize`:@@runs.detail.menu.refresh:Refresh`,
      icon: 'reload',
      run: () => this.load(),
    });
    return out;
  });
  /** Snapshot normalised for the canvas (laid out when positions are missing). */
  readonly snapshot = computed<WorkflowDefinitionInput | null>(() => {
    const snap = this.run()?.definitionSnapshot;
    if (!snap?.nodes?.length) return null;
    const def = normalizeDefinition(snap);
    return needsLayout(def) ? autoLayout(def) : def;
  });
  readonly timeline = computed(() =>
    (this.run()?.nodes ?? []).filter((n) => n.kind !== 'START' && n.kind !== 'NOTE'),
  );
  readonly selectedExec = computed(() => {
    const id = this.selectedNodeId();
    if (!id) return null;
    const list = (this.run()?.nodes ?? []).filter((n) => n.nodeId === id);
    return list[list.length - 1] ?? null;
  });
  /**
   * Facts for the node panel: for a selected step what it ran with (template, gate question, CI
   * signal, branches), where the baton came from and where it went, timing and attempt; for the
   * whole run its input (issue, trigger, CI context, parameters). Useful without artefacts too.
   */
  readonly facts = computed<Fact[]>(() => {
    const r = this.run();
    if (!r) return [];
    const s = this.selectedExec();
    if (!s) return runFacts(r, this.timeline().length);
    // The waiting gate shows the question as rendered for this run, not the node's template.
    const a = this.waitingApproval();
    const rendered =
      a && a.nodeId === s.nodeId && a.id === r.waitingOn?.approvalId ? (a.question ?? null) : null;
    return stepFacts(r, s, rendered);
  });
  /** Per-repository results (ADR 0034): of the selected step, or the latest per key of the run. */
  readonly repositoryResults = computed(() => {
    const s = this.selectedExec();
    return s ? (s.repositories ?? []) : (this.run()?.repositories ?? []);
  });

  readonly nodeArtifacts = computed(() => {
    const id = this.selectedNodeId();
    const all = this.artifacts();
    return id ? all.filter((a) => a.nodeId === id) : all;
  });
  readonly visibleLogs = computed(() => {
    const node = this.selectedNodeId();
    const list =
      this.logScope() === 'node' && node
        ? this.logs().filter((l) => l.nodeId === node)
        : this.logs();
    return list.slice(-2000);
  });
  readonly totalUsage = computed(() =>
    (this.run()?.nodes ?? []).reduce(
      (acc, s) => ({
        inputTokens: (acc.inputTokens ?? 0) + (s.usage?.inputTokens ?? 0),
        outputTokens: (acc.outputTokens ?? 0) + (s.usage?.outputTokens ?? 0),
        cacheReadTokens: (acc.cacheReadTokens ?? 0) + (s.usage?.cacheReadTokens ?? 0),
      }),
      { inputTokens: 0, outputTokens: 0, cacheReadTokens: 0 },
    ),
  );

  constructor() {
    // Load the open approval of the waiting gate (question as rendered, canDecide).
    effect(() => {
      const id = this.run()?.waitingOn?.approvalId ?? null;
      untracked(() => {
        if (!id) return this.waitingApproval.set(null);
        if (this.waitingApproval()?.id === id) return;
        const known = this.inbox.open().find((a) => a.id === id);
        if (known) this.waitingApproval.set(known);
        this.approvals.get(id).subscribe({
          next: (a) => this.waitingApproval.set(a),
          error: () => undefined,
        });
      });
    });
    effect(() => {
      this.runId();
      untracked(() => {
        this.run.set(null);
        this.artifacts.set([]);
        this.artifactContent.set(null);
        this.selectedArtifact.set(null);
        this.selectedNodeId.set(null);
        this.clearLogs();
        this.load();
      });
    });

    // Live updates: RunnerLog lines are appended directly; other events reload the run.
    this.events
      .forRun$(() => this.runId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((e) => {
        if (e.type !== 'RunnerLog') return;
        const p = e.payload as Record<string, unknown>;
        this.appendLogs([
          {
            at: (p['at'] as string) ?? e.occurredAt,
            nodeId: (p['nodeId'] as string) ?? (p['stepId'] as string) ?? '',
            level: (p['level'] as LogLine['level']) ?? 'INFO',
            message: (p['message'] as string) ?? '',
          },
        ]);
      });
    this.events
      .forRun$(() => this.runId())
      .pipe(
        filter((e) => e.type !== 'RunnerLog'),
        debounceTime(400),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((e) => {
        if (e.type === 'ApprovalAssigned') this.reloadWaitingApproval();
        else this.load();
      });

    // Poll logs while active (SSE may be down); poll the run at a slower cadence as a fallback.
    interval(5000)
      .pipe(startWith(0), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (!this.run()) return;
        if (this.isActive() || this.logs().length === 0) this.pollLogs();
      });
    interval(20000)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        if (this.isActive() && this.events.state() !== 'connected') this.load();
      });
  }

  private reloadWaitingApproval(): void {
    const id = this.run()?.waitingOn?.approvalId;
    if (id)
      this.approvals
        .get(id)
        .subscribe({ next: (a) => this.waitingApproval.set(a), error: () => undefined });
  }

  load(): void {
    const id = this.runId();
    this.api.get(id).subscribe((r) => {
      this.run.set(r);
      this.details.put(r);
      this.projects.setCurrent(r.projectId);
      this.crumbs.set([
        { label: $localize`:@@runs.detail.crumb.runs:Runs`, link: '/runs' },
        {
          label: r.projectKey ?? $localize`:@@runs.detail.crumb.project:Project`,
          link: ['/projects', r.projectId],
          mono: true,
        },
        { label: r.issueKey || (r.id ?? '').slice(0, 8), mono: true },
      ]);
      if (!this.selectedNodeId())
        this.selectedNodeId.set(r.currentNodeId ?? r.failure?.nodeId ?? lastNode(r));
      if (r.waitingOn?.kind === 'WAIT_FOR_CI' && !this.signalName)
        this.signalName = r.waitingOn.signal ?? '';
    });
    this.api.artifacts(id).subscribe((list) => {
      this.artifacts.set(list);
      if (!this.selectedArtifact()) {
        const forNode = list.filter((a) => a.nodeId === this.selectedNodeId());
        const pick = forNode[forNode.length - 1] ?? list[list.length - 1];
        if (pick) this.openArtifact(pick);
      }
    });
  }

  nodeBaton = (s: NodeExecution) =>
    batonOf.node(s, {
      canDecide:
        (s.kind === 'HUMAN_GATE' || s.status === 'WAITING') &&
        s.nodeId === this.run()?.waitingOn?.nodeId
          ? this.gateDecidable()
          : undefined,
    });

  kindTitle(s: NodeExecution): string {
    return s.kind ? NODE_KIND_META[s.kind].title : $localize`:@@runs.detail.step:Step`;
  }

  stepDuration(s: NodeExecution): number | null {
    return s.startedAt ? durationBetween(s.startedAt, s.finishedAt) : null;
  }

  onSegment(seg: TrackSegment): void {
    this.tab = 0;
    this.selectNode(seg.nodeId);
  }

  onCanvasSelect(id: string | null): void {
    if (id) this.selectNode(id);
  }

  selectNode(nodeId: string): void {
    this.selectedNodeId.set(nodeId);
    const art = [...this.artifacts()].reverse().find((a) => a.nodeId === nodeId);
    if (art) this.openArtifact(art);
    else {
      this.selectedArtifact.set(null);
      this.artifactContent.set(null);
    }
  }

  /** The gate was assigned to other users: show it everywhere on the page and in the inbox. */
  onGateChanged(updated: ApprovalRequest): void {
    if (this.gate()?.id === updated.id) this.gate.set(updated);
    if (this.waitingApproval()?.id === updated.id) this.waitingApproval.set(updated);
    this.inbox.refresh();
  }

  openGate(approvalId: string): void {
    const known = this.inbox.open().find((a) => a.id === approvalId);
    if (known) this.gate.set(known);
    else this.approvals.get(approvalId).subscribe((a) => this.gate.set(a));
  }

  openArtifact(a: ArtifactSummary): void {
    this.selectedArtifact.set(a);
    this.artifactLoading.set(true);
    this.api.artifact(this.runId(), a.id!).subscribe({
      next: (full) => {
        this.artifactContent.set(full);
        this.artifactLoading.set(false);
      },
      error: () => this.artifactLoading.set(false),
    });
  }

  sizeLabel(bytes: number | undefined): string {
    if (!bytes) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${fixed(bytes / 1024, 1)} kB`;
    return `${fixed(bytes / 1024 / 1024, 1)} MB`;
  }

  async cancel(): Promise<void> {
    const ok = await this.confirm.danger(
      $localize`:@@runs.detail.cancel.title:Cancel this run?`,
      $localize`:@@runs.detail.cancel.text:Running agent jobs are stopped and the run is marked cancelled. This cannot be undone.`,
      $localize`:@@runs.detail.cancel.ok:Cancel run`,
    );
    if (!ok) return;
    this.busy.set('cancel');
    this.api.cancel(this.runId()).subscribe({
      next: (r) => {
        this.run.set(r);
        this.busy.set(null);
        this.message.success($localize`:@@runs.detail.cancel.done:Run cancelled`);
      },
      error: () => this.busy.set(null),
    });
  }

  retry(): void {
    this.busy.set('retry');
    this.api.retry(this.runId()).subscribe({
      next: (r) => {
        this.run.set(r);
        this.busy.set(null);
        this.message.success(
          $localize`:@@runs.detail.retry.done:Run continues from the failed step`,
        );
      },
      error: () => this.busy.set(null),
    });
  }

  sendSignal(): void {
    const signal = this.signalName.trim();
    if (!signal) return;
    this.busy.set('signal');
    this.api
      .signal(this.runId(), {
        signal,
        status: this.signalStatus,
        message: this.signalMessage.trim() || null,
      })
      .subscribe({
        next: (r) => {
          this.run.set(r);
          this.busy.set(null);
          this.signalMessage = '';
          this.message.success(
            this.signalStatus === 'SUCCESS'
              ? $localize`:@@runs.detail.signal.sentSuccess:Signal "${signal}:signal:" sent (success)`
              : $localize`:@@runs.detail.signal.sentFail:Signal "${signal}:signal:" sent (fail)`,
          );
        },
        error: () => this.busy.set(null),
      });
  }

  async copy(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.message.success($localize`:@@runs.detail.copied:Copied`);
    } catch {
      this.message.warning(
        $localize`:@@runs.detail.clipboardUnavailable:Clipboard unavailable; select the text and copy it`,
      );
    }
  }

  clearLogs(): void {
    this.logs.set([]);
    this.lastLogAt = null;
  }

  private pollLogs(): void {
    this.api
      .logs(this.runId(), { after: this.lastLogAt })
      .subscribe((lines) => this.appendLogs(lines));
  }

  private appendLogs(lines: LogLine[]): void {
    if (!lines.length) return;
    const existing = this.logs();
    const seen = new Set(existing.slice(-500).map((l) => `${l.at}|${l.nodeId}|${l.message}`));
    const fresh = lines.filter((l) => !seen.has(`${l.at}|${l.nodeId}|${l.message}`));
    if (!fresh.length) return;
    this.logs.set([...existing, ...fresh].slice(-5000));
    const last = fresh[fresh.length - 1].at;
    if (last && (!this.lastLogAt || last > this.lastLogAt)) this.lastLogAt = last;
    if (this.follow) {
      queueMicrotask(() => {
        const box = this.logBox()?.nativeElement;
        if (box) box.scrollTop = box.scrollHeight;
      });
    }
  }
}

function runFacts(r: WorkflowRun, steps: number): Fact[] {
  const out: Fact[] = [];
  out.push({
    label: $localize`:@@runs.detail.fact.trigger:Trigger`,
    value: triggerLabel(r.trigger?.type ?? 'MANUAL'),
  });
  if (r.issueKey)
    out.push({ label: $localize`:@@runs.detail.fact.issue:Issue`, value: r.issueKey, mono: true });
  const ci = r.input?.ci;
  const mr = ci?.mergeRequest;
  if (mr?.iid)
    out.push({
      label: $localize`:@@runs.detail.fact.mergeRequest:Merge request`,
      value: '!' + mr.iid + (mr.title ? ' ' + mr.title : ''),
    });
  if (ci?.ref)
    out.push({ label: $localize`:@@runs.detail.fact.ref:Ref`, value: ci.ref, mono: true });
  if (ci?.commitSha)
    out.push({
      label: $localize`:@@runs.detail.fact.commit:Commit`,
      value: ci.commitSha.slice(0, 12),
      mono: true,
    });
  if (ci?.pipelineId)
    out.push({
      label: $localize`:@@runs.detail.fact.pipeline:Pipeline`,
      value: ci.pipelineId,
      mono: true,
    });
  const params = Object.entries(r.input?.parameters ?? {});
  for (const [k, v] of params.slice(0, 6))
    out.push({ label: k, value: typeof v === 'string' ? v : JSON.stringify(v), mono: true });
  if (params.length > 6)
    out.push({
      label: $localize`:@@runs.detail.fact.more:More`,
      value: icu(
        $localize`:@@runs.detail.fact.moreParameters:{count, plural, one {# more parameter (Input tab)} other {# more parameters (Input tab)}}`,
        { count: params.length - 6 },
      ),
    });
  out.push({ label: $localize`:@@runs.detail.fact.steps:Steps`, value: String(steps) });
  if (r.startedAt)
    out.push({
      label: $localize`:@@runs.detail.fact.started:Started`,
      value: absolute(r.startedAt),
    });
  if (r.finishedAt)
    out.push({
      label: $localize`:@@runs.detail.fact.finished:Finished`,
      value: absolute(r.finishedAt),
    });
  return out;
}

function triggerLabel(type: string): string {
  const labels: Record<string, string> = {
    MANUAL: $localize`:@@runs.detail.fact.triggerManual:manual`,
    CI: 'CI',
    API: 'API',
    JIRA_TRANSITION: $localize`:@@runs.detail.fact.triggerJira:Jira transition`,
  };
  return labels[type] ?? type.toLowerCase();
}

function lastNode(r: WorkflowRun): string | null {
  const nodes = (r.nodes ?? []).filter(
    (n) => n.kind !== 'START' && n.kind !== 'END' && n.kind !== 'NOTE',
  );
  return nodes[nodes.length - 1]?.nodeId ?? null;
}
