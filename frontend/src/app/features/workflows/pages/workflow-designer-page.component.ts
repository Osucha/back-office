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
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { NzButtonModule } from 'ng-zorro-antd/button';
import { NzDropDownModule } from 'ng-zorro-antd/dropdown';
import { NzFormModule } from 'ng-zorro-antd/form';
import { NzIconModule } from 'ng-zorro-antd/icon';
import { NzInputModule } from 'ng-zorro-antd/input';
import { NzMessageService } from 'ng-zorro-antd/message';
import { NzSegmentedModule } from 'ng-zorro-antd/segmented';
import { NzSkeletonModule } from 'ng-zorro-antd/skeleton';
import { NzSwitchModule } from 'ng-zorro-antd/switch';
import { NzTooltipModule } from 'ng-zorro-antd/tooltip';
import {
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  map,
  merge,
  of,
  switchMap,
} from 'rxjs';
import { ModelProfilesApi } from '../../../core/api/model-profiles.service';
import { TaskLibraryApi } from '../../../core/api/task-library.service';
import { WorkflowsApi } from '../../../core/api/workflows.service';
import { LibrarySourcesApi } from '../../../core/api/library-sources.service';
import { ProjectsApi } from '../../../core/api/projects.service';
import { projectRepositories } from '../../../core/ui/repositories';
import type {
  ModelProfile,
  Point,
  LibrarySource,
  ProjectRepository,
  ProjectWorkspace,
  Skill,
  ValidationResult,
  WorkflowCanvas,
  WorkflowDefinition,
  WorkflowDefinitionInput,
  WorkflowEdge,
  WorkflowNode,
  WorkflowPalette,
} from '../../../core/api/types';
import { IdentityService } from '../../../core/auth/identity.service';
import { icu } from '../../../core/i18n/icu';
import { PendingSavesService } from '../../../core/state/pending-saves.service';
import { ConfirmService } from '../../../core/ui/confirm.service';
import { ActionBarComponent } from '../../../core/ui/relay/action-bar.component';
import { BatonComponent } from '../../../core/ui/relay/baton.component';
import { BreadcrumbService } from '../../../core/ui/relay/breadcrumb.service';
import { GlyphComponent } from '../../../core/ui/relay/glyph.component';
import { ShortcutsService, formatKeys } from '../../../core/ui/relay/shortcuts.service';
import { SkeletonComponent } from '../../../core/ui/relay/skeleton.component';
import { HelpLinkComponent } from '../../../core/help/help-link.component';
import { ProjectsStore } from '../../../core/state/projects.store';
import { NodePaletteComponent, type PalettePick } from '../components/node-palette.component';
import { NodePropertyPanelComponent } from '../components/node-property-panel.component';
import { TriggersEditorComponent } from '../components/triggers-editor.component';
import { ProjectWorkspacesApi } from '../../../core/api/project-workspaces.service';
import { workspaceColor } from '../../../core/ui/workspaces';
import { membersOf } from '../data/workspaces';
import { WorkflowCanvasComponent, type PaletteDrop } from '../components/workflow-canvas.component';
import { autoLayout, needsLayout } from '../data/auto-layout';
import {
  EMPTY_DEFINITION,
  ciCodeReviewTemplate,
  createNode,
  duplicateNode,
  indexPalette,
  issueToMergeRequestTemplate,
  moveNode,
  normalizeDefinition,
  removeEdge,
  removeNode,
  replaceNode,
  upsertEdge,
} from '../data/graph-model';
import {
  issueCounts,
  mapValidation,
  mergeValidation,
  validateLocally,
  type Issues,
} from '../data/workflow-validation';
import { groupIssues, type IssueGroup } from '../data/issue-groups';

const MAX_HISTORY = 50;

/**
 * Workflow designer on an n8n-like canvas: palette (left), canvas (centre), property panel
 * (right), toolbar with validation / save / duplicate / layout and a two-way JSON view.
 * Used for `/projects/:projectId/workflows/new` and `/workflows/:workflowId/edit`.
 */
@Component({
  selector: 'app-workflow-designer-page',
  imports: [
    FormsModule,
    RouterLink,
    NzButtonModule,
    NzIconModule,
    NzInputModule,
    NzFormModule,
    NzSwitchModule,
    NzSkeletonModule,
    NzDropDownModule,
    NzTooltipModule,
    NzSegmentedModule,
    WorkflowCanvasComponent,
    NodePaletteComponent,
    NodePropertyPanelComponent,
    TriggersEditorComponent,
    ActionBarComponent,
    BatonComponent,
    GlyphComponent,
    HelpLinkComponent,
    SkeletonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="designer">
      @if (loading()) {
        <div class="loading">
          <bo-skeleton
            variant="detail"
            label="Loading the workflow"
            i18n-label="@@workflows.designer.loading"
          />
        </div>
      } @else {
        <header class="head">
          <div class="title">
            <span class="eyebrow mono"
              >{{ projectKey() }} ·
              @if (existing(); as w) {
                v{{ w.version }}
              } @else {
                <span i18n="@@workflows.designer.newWorkflowEyebrow">new workflow</span>
              }
            </span>
            <input
              class="name-input"
              [ngModel]="def().name"
              (ngModelChange)="patchDef({ name: $event })"
              placeholder="Name this workflow"
              i18n-placeholder="@@workflows.designer.namePlaceholder"
              [readonly]="readOnly()"
              aria-label="Workflow name"
              i18n-aria-label="@@workflows.designer.nameLabel"
            />
          </div>
          <div class="state" role="status">
            @if (validating()) {
              <bo-baton
                size="sm"
                holder="agent"
                label="checking"
                i18n-label="@@workflows.designer.checking"
                [live]="true"
              />
            } @else if (issues().errorCount) {
              <button type="button" class="chip-btn" (click)="sheetOpen.set(true)">
                <bo-baton size="sm" holder="blocked" [label]="errorsLabel(issues().errorCount)" />
              </button>
            } @else {
              <bo-baton
                size="sm"
                holder="done"
                label="valid"
                i18n-label="@@workflows.designer.valid"
              />
            }
            @if (issues().warningCount) {
              <button type="button" class="chip-btn" (click)="sheetOpen.set(true)">
                <bo-baton size="sm" holder="you" [label]="warningsLabel(issues().warningCount)" />
              </button>
            }
            @if (readOnly()) {
              <bo-baton
                size="sm"
                holder="idle"
                label="read-only"
                i18n-label="@@workflows.designer.readOnlyChip"
                nz-tooltip
                nzTooltipTitle="Editing needs the workflow.edit permission on this project"
                i18n-nzTooltipTitle="@@workflows.designer.readOnlyTooltip"
              />
            }
          </div>
          <span class="spacer"></span>
          <div
            class="tools"
            role="toolbar"
            aria-label="Designer tools"
            i18n-aria-label="@@workflows.designer.tools"
          >
            <bo-help-link topic="designer" />
            <nz-segmented
              [nzOptions]="modeOptions"
              [ngModel]="mode()"
              (nzValueChange)="setMode($event)"
              nzSize="small"
              aria-label="View"
              i18n-aria-label="@@workflows.designer.viewLabel"
            />
            @if (!existing() && !readOnly()) {
              <button
                nz-button
                nzSize="small"
                type="button"
                nz-dropdown
                [nzDropdownMenu]="tplMenu"
                nzTrigger="click"
              >
                <span i18n="@@workflows.designer.template">Template</span>
                <nz-icon nzType="down" />
              </button>
              <nz-dropdown-menu #tplMenu="nzDropdownMenu">
                <ul nz-menu>
                  <li
                    nz-menu-item
                    (click)="loadTemplate('issue')"
                    i18n="@@workflows.designer.template.issue"
                  >
                    Issue to reviewed MR
                  </li>
                  <li
                    nz-menu-item
                    (click)="loadTemplate('review')"
                    i18n="@@workflows.designer.template.review"
                  >
                    CI code review
                  </li>
                  <li
                    nz-menu-item
                    (click)="loadTemplate('empty')"
                    i18n="@@workflows.designer.template.empty"
                  >
                    Empty (START + END)
                  </li>
                </ul>
              </nz-dropdown-menu>
            }
            @if (!readOnly()) {
              <span
                class="history"
                role="group"
                aria-label="History"
                i18n-aria-label="@@workflows.designer.history"
              >
                <button
                  nz-button
                  nzSize="small"
                  type="button"
                  (click)="undo()"
                  [disabled]="!canUndo()"
                  aria-label="Undo"
                  i18n-aria-label="@@workflows.designer.undo"
                  nz-tooltip
                  [nzTooltipTitle]="text.undoTooltip"
                >
                  <nz-icon nzType="undo" />
                </button>
                <button
                  nz-button
                  nzSize="small"
                  type="button"
                  (click)="redo()"
                  [disabled]="!canRedo()"
                  aria-label="Redo"
                  i18n-aria-label="@@workflows.designer.redo"
                  nz-tooltip
                  [nzTooltipTitle]="text.redoTooltip"
                >
                  <nz-icon nzType="redo" />
                </button>
              </span>
              <button
                nz-button
                nzSize="small"
                type="button"
                (click)="layout()"
                nz-tooltip
                nzTooltipTitle="Arrange in layers from START: main path on top, branches below"
                i18n-nzTooltipTitle="@@workflows.designer.tidyTooltip"
              >
                <nz-icon nzType="apartment" />
                <span i18n="@@workflows.designer.tidy">Tidy layout</span>
              </button>
              <button
                nz-button
                nzSize="small"
                type="button"
                (click)="validateNow()"
                [nzLoading]="validating()"
                i18n="@@workflows.designer.validate"
              >
                Validate
              </button>
            }
            <span class="divider" aria-hidden="true"></span>
            <button
              nz-button
              nzSize="small"
              nzType="text"
              type="button"
              (click)="togglePanel('left')"
              [attr.aria-pressed]="leftOpen()"
              [attr.aria-label]="leftOpen() ? text.hidePalette : text.showPalette"
              nz-tooltip
              nzTooltipTitle="Palette ([)"
              i18n-nzTooltipTitle="@@workflows.designer.paletteTooltip"
            >
              <nz-icon [nzType]="leftOpen() ? 'menu-fold' : 'menu-unfold'" />
            </button>
            <button
              nz-button
              nzSize="small"
              nzType="text"
              type="button"
              (click)="togglePanel('right')"
              [attr.aria-pressed]="rightOpen()"
              [attr.aria-label]="rightOpen() ? text.hideProperties : text.showProperties"
              nz-tooltip
              nzTooltipTitle="Properties (])"
              i18n-nzTooltipTitle="@@workflows.designer.propertiesTooltip"
            >
              <nz-icon [nzType]="rightOpen() ? 'menu-unfold' : 'menu-fold'" />
            </button>
          </div>
        </header>

        @if (mode() === 'designer') {
          <div class="body" [class.no-left]="!leftOpen()" [class.no-right]="!rightOpen()">
            @if (leftOpen()) {
              <aside
                class="left"
                aria-label="Palette"
                i18n-aria-label="@@workflows.designer.palette"
              >
                @if (readOnly()) {
                  <div class="panel readonly-note">
                    <div class="head2">
                      <nz-icon nzType="lock" />
                      <span i18n="@@workflows.designer.readOnly">Read-only</span>
                    </div>
                    <p class="muted small" i18n="@@workflows.designer.readOnlyHint">
                      You can inspect this workflow. Changing it needs the
                      <span class="mono">workflow.edit</span> permission on the project.
                    </p>
                  </div>
                } @else {
                  <app-node-palette
                    [palette]="palette()"
                    [loading]="paletteLoading()"
                    (pick)="addFromPalette($event)"
                  />
                }
              </aside>
            }
            <main class="center">
              <div class="canvas-area">
                <app-workflow-canvas
                  #canvas
                  [definition]="def()"
                  [palette]="palette()"
                  [issues]="counts()"
                  [selectedNodeId]="selectedNodeId()"
                  [initialViewport]="initialViewport()"
                  [workspaces]="workspaces()"
                  [readonly]="readOnly()"
                  (nodeSelected)="onNodeSelected($event)"
                  (edgeSelected)="selectedEdgeId.set($event)"
                  (connected)="connect($event)"
                  (nodeMoved)="onNodeMoved($event)"
                  (moveEnded)="onMoveEnded()"
                  (deleteRequested)="deleteSelection($event)"
                  (viewportChanged)="onViewport($event)"
                  (dropped)="onDrop($event)"
                />
              </div>
              <section class="sheet" [class.open]="sheetOpen()" aria-labelledby="issues-title">
                <button
                  type="button"
                  class="sheet-head"
                  (click)="sheetOpen.set(!sheetOpen())"
                  [attr.aria-expanded]="sheetOpen()"
                >
                  <nz-icon [nzType]="sheetOpen() ? 'down' : 'up'" />
                  <span id="issues-title" i18n="@@workflows.designer.validation">Validation</span>
                  @if (!issueGroups().length) {
                    <span class="count ok"
                      ><bo-glyph holder="done" [size]="11" />
                      <span i18n="@@workflows.designer.noIssues">no issues</span></span
                    >
                  } @else {
                    <span class="count" [class.zero]="!issues().errorCount" data-sev="error">
                      <bo-glyph holder="blocked" [size]="11" />
                      {{ errorsLabel(issues().errorCount) }}
                    </span>
                    <span class="count" [class.zero]="!issues().warningCount" data-sev="warning">
                      <bo-glyph holder="you" [size]="11" />
                      {{ warningsLabel(issues().warningCount) }}
                    </span>
                    <span class="sheet-sum">{{ placesLabel(issueGroups().length) }}</span>
                  }
                </button>
                @if (sheetOpen()) {
                  <div class="issue-groups">
                    @for (g of issueGroups(); track g.key) {
                      <section class="issue-group" [attr.aria-label]="g.title">
                        <button
                          type="button"
                          class="group-head"
                          (click)="jumpTo(g)"
                          [disabled]="!g.nodeId"
                          [class.active]="g.nodeId && g.nodeId === selectedNodeId()"
                        >
                          <span class="group-title">{{ g.title }}</span>
                          @if (g.nodeId) {
                            <span class="mono where">{{ g.nodeId }}</span>
                          }
                          <span class="group-counts">
                            @if (g.errors) {
                              <span class="mini" data-sev="error">{{ g.errors }}</span>
                            }
                            @if (g.warnings) {
                              <span class="mini" data-sev="warning">{{ g.warnings }}</span>
                            }
                          </span>
                          @if (g.nodeId) {
                            <span class="jump" i18n="@@workflows.designer.showNode">Show node</span>
                          }
                        </button>
                        <ul class="issue-list">
                          @for (i of g.items; track $index) {
                            <li>
                              <button
                                type="button"
                                class="issue"
                                (click)="jumpTo(g)"
                                [disabled]="!g.nodeId"
                              >
                                <bo-glyph
                                  [holder]="i.severity === 'error' ? 'blocked' : 'you'"
                                  [size]="11"
                                />
                                <span class="sev">{{ severityLabel(i.severity) }}</span>
                                <span class="msg">
                                  @if (i.where) {
                                    <span class="mono where">{{ i.where }}</span>
                                  }
                                  {{ i.message }}
                                </span>
                              </button>
                            </li>
                          }
                        </ul>
                      </section>
                    } @empty {
                      <p class="muted small none" i18n="@@workflows.designer.noIssuesHint">
                        No issues. Select a node to edit it; drag from a port to connect.
                      </p>
                    }
                  </div>
                }
              </section>
            </main>
            @if (rightOpen()) {
              <aside
                class="right"
                aria-label="Properties"
                i18n-aria-label="@@workflows.designer.properties"
              >
                @if (selectedNode(); as n) {
                  <app-node-property-panel
                    [node]="n"
                    [allIds]="nodeIds()"
                    [palette]="palette()"
                    [issues]="issuesFor(n.id)"
                    [profiles]="profiles()"
                    [skills]="skills()"
                    [projectId]="resolvedProjectId()"
                    [projectRepositories]="projectRepositories()"
                    [librarySources]="librarySources()"
                    [workspaces]="workspaces()"
                    [idLocked]="persistedIds().has(n.id)"
                    [readonly]="readOnly()"
                    (nodeChange)="updateNode(n.id, $event)"
                    (remove)="deleteNode(n.id)"
                    (duplicate)="duplicateSelected(n.id)"
                  />
                } @else if (selectedEdge(); as e) {
                  <div class="panel">
                    <div class="head2">
                      <nz-icon nzType="swap-right" />
                      <span i18n="@@workflows.designer.edge">Edge</span>
                    </div>
                    <p class="muted small">
                      <span class="mono">{{ e.from }}</span> &rsaquo;
                      <span class="mono">{{ e.fromPort }}</span> &rarr;
                      <span class="mono">{{ e.to }}</span>
                    </p>
                    @for (i of issuesForEdge(e.id)?.errors ?? []; track $index) {
                      <p class="danger small">{{ i.message }}</p>
                    }
                    <nz-form-item>
                      <nz-form-label nzFor="edge-label" i18n="@@workflows.designer.edgeLabel"
                        >Label</nz-form-label
                      >
                      <nz-form-control
                        nzExtra="Shown on the edge instead of the port name."
                        i18n-nzExtra="@@workflows.designer.edgeLabel.extra"
                      >
                        <input
                          nz-input
                          id="edge-label"
                          [ngModel]="e.label ?? ''"
                          (ngModelChange)="patchEdge(e.id, { label: $event || null })"
                          [readonly]="readOnly()"
                        />
                      </nz-form-control>
                    </nz-form-item>
                    @if (!readOnly()) {
                      <button
                        nz-button
                        nzDanger
                        nzSize="small"
                        type="button"
                        (click)="deleteSelection({ nodeIds: [], edgeIds: [e.id] })"
                        i18n="@@workflows.designer.deleteEdge"
                      >
                        Delete edge
                      </button>
                    }
                  </div>
                } @else {
                  <div class="panel">
                    <div class="head2">
                      <nz-icon nzType="setting" />
                      <span i18n="@@workflows.designer.workflow">Workflow</span>
                    </div>
                    <form nz-form nzLayout="vertical" (submit)="$event.preventDefault()">
                      <nz-form-item>
                        <nz-form-label nzFor="wf-desc" i18n="@@workflows.designer.description"
                          >Description</nz-form-label
                        >
                        <nz-form-control>
                          <textarea
                            nz-input
                            id="wf-desc"
                            name="description"
                            [ngModel]="def().description ?? ''"
                            (ngModelChange)="patchDef({ description: $event })"
                            [readonly]="readOnly()"
                            [nzAutosize]="{ minRows: 2, maxRows: 5 }"
                          ></textarea>
                        </nz-form-control>
                      </nz-form-item>
                      <nz-form-item>
                        <nz-form-control>
                          <nz-switch
                            name="enabled"
                            [ngModel]="def().enabled !== false"
                            (ngModelChange)="patchDef({ enabled: $event })"
                            [nzDisabled]="readOnly()"
                            aria-label="Enabled"
                            i18n-aria-label="@@workflows.designer.enabled"
                          />
                          @if (def().enabled !== false) {
                            <span
                              class="muted small"
                              style="margin-left: 8px"
                              i18n="@@workflows.designer.enabledOn"
                              >Enabled: runs can start</span
                            >
                          } @else {
                            <span
                              class="muted small"
                              style="margin-left: 8px"
                              i18n="@@workflows.designer.enabledOff"
                              >Disabled: runs cannot start</span
                            >
                          }
                        </nz-form-control>
                      </nz-form-item>
                      <nz-form-item>
                        <nz-form-label i18n="@@workflows.designer.workspaces"
                          >Workspaces</nz-form-label
                        >
                        <nz-form-control>
                          <ul class="ws-list">
                            @for (w of workspaces(); track w.key; let i = $index) {
                              <li>
                                <span class="ws-dot" [style.background]="workspaceColor(i)"></span>
                                <span>{{ w.name || w.key }}</span>
                                <span class="muted small mono">{{ w.key }}</span>
                                <span class="muted small">{{ workspaceStepsText(w.key) }}</span>
                              </li>
                            } @empty {
                              <li class="muted small" i18n="@@workflows.designer.noWorkspaces">
                                The project has no workspaces.
                              </li>
                            }
                          </ul>
                          @if (resolvedProjectId(); as pid) {
                            <a
                              class="small"
                              [routerLink]="['/projects', pid]"
                              [queryParams]="{ tab: 'workspaces' }"
                              i18n="@@workflows.designer.manageWorkspaces"
                              >Configure in the project tab Workspaces</a
                            >
                          }
                        </nz-form-control>
                      </nz-form-item>
                      <nz-form-item>
                        <nz-form-label i18n="@@workflows.designer.triggers">Triggers</nz-form-label>
                        <nz-form-control>
                          <app-triggers-editor
                            [triggers]="def().triggers ?? []"
                            (triggersChange)="patchDef({ triggers: $event })"
                            [readonly]="readOnly()"
                          />
                        </nz-form-control>
                      </nz-form-item>
                    </form>
                    <p class="muted small" i18n="@@workflows.designer.selectHint">
                      Select a node to edit it. Issues are listed in the Validation sheet under the
                      canvas.
                    </p>
                  </div>
                }
              </aside>
            }
          </div>
        } @else {
          <div class="json-body">
            <div class="toolbar">
              <span class="muted small" i18n="@@workflows.designer.jsonHint"
                >The full <code>WorkflowDefinitionInput</code>. Edits apply as soon as the JSON
                parses.</span
              >
              <span class="spacer"></span>
              <button
                nz-button
                nzSize="small"
                type="button"
                (click)="copyJson()"
                i18n="@@workflows.designer.copyJson"
              >
                Copy JSON
              </button>
              <button
                nz-button
                nzSize="small"
                type="button"
                (click)="formatJson()"
                [disabled]="!!jsonError()"
                i18n="@@workflows.designer.formatJson"
              >
                Format JSON
              </button>
            </div>
            @if (jsonError()) {
              <p class="danger small" role="alert">{{ jsonError() }}</p>
            }
            <textarea
              nz-input
              class="mono-textarea json-editor"
              [ngModel]="jsonText()"
              (ngModelChange)="onJsonChange($event)"
              [readonly]="readOnly()"
              spellcheck="false"
              aria-label="Workflow definition JSON"
              i18n-aria-label="@@workflows.designer.jsonLabel"
            ></textarea>
          </div>
        }

        <bo-action-bar
          [sticky]="false"
          [dirty]="dirty() && !readOnly()"
          [saving]="saving()"
          [showSave]="!readOnly()"
          [canSave]="!jsonError()"
          [requireDirty]="!!existing()"
          [saveLabel]="existing() ? text.saveVersion : text.create"
          [cleanLabel]="cleanLabel()"
          (save)="save()"
        >
          @if (issues().errorCount && !readOnly()) {
            <span boActionStatus>{{ fixLabel(issues().errorCount) }}</span>
          } @else if (laidOutOnOpen() && !dirty() && !readOnly()) {
            <span boActionStatus i18n="@@workflows.designer.laidOut"
              >Laid out on open; the positions are kept with the next save.</span
            >
          }
          <a
            nz-button
            type="button"
            [routerLink]="['/projects', resolvedProjectId()]"
            i18n="@@workflows.designer.close"
            >Close</a
          >
          @if (!readOnly() && existing(); as w) {
            <button
              nz-button
              type="button"
              (click)="duplicate(w)"
              [nzLoading]="duplicating()"
              i18n="@@workflows.designer.duplicate"
            >
              Duplicate workflow
            </button>
          }
        </bo-action-bar>
      }
    </div>
  `,
  styles: [
    `
      .designer {
        display: flex;
        flex-direction: column;
        height: calc(100vh - var(--bo-topbar-h));
        min-height: 520px;
      }
      .loading {
        padding: 24px 32px;
      }
      .head {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 10px 16px;
        background: var(--bo-surface);
        border-bottom: 1px solid var(--bo-line);
        flex-wrap: wrap;
      }
      .title {
        display: flex;
        flex-direction: column;
        min-width: 220px;
      }
      .eyebrow {
        font-size: 11px;
        color: var(--bo-ink-3);
      }
      .name-input {
        border: 0;
        outline: none;
        background: transparent;
        color: var(--bo-ink);
        font: inherit;
        font-size: 17px;
        font-weight: 600;
        padding: 0;
        width: 320px;
        max-width: 40vw;
        border-bottom: 1px dashed transparent;
      }
      .name-input:hover:not([readonly]),
      .name-input:focus {
        border-bottom-color: var(--bo-line-strong);
      }
      .name-input:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .state {
        display: flex;
        gap: 6px;
        align-items: center;
      }
      .chip-btn {
        all: unset;
        cursor: pointer;
        border-radius: 999px;
      }
      .chip-btn:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: 2px;
      }
      .tools {
        display: flex;
        gap: 6px;
        align-items: center;
        flex-wrap: wrap;
      }
      .divider {
        width: 1px;
        height: 20px;
        background: var(--bo-line);
        margin: 0 4px;
      }
      .spacer {
        flex: 1;
      }
      .body {
        flex: 1;
        min-height: 0;
        display: grid;
        grid-template-columns: 272px minmax(0, 1fr) 360px;
      }
      .body.no-left {
        grid-template-columns: minmax(0, 1fr) 360px;
      }
      .body.no-right {
        grid-template-columns: 272px minmax(0, 1fr);
      }
      .body.no-left.no-right {
        grid-template-columns: minmax(0, 1fr);
      }
      .left,
      .right {
        background: var(--bo-surface);
        overflow: auto;
        min-height: 0;
      }
      .left {
        border-right: 1px solid var(--bo-line);
      }
      .right {
        border-left: 1px solid var(--bo-line);
      }
      .center {
        min-height: 0;
        display: flex;
        flex-direction: column;
      }
      .ws-list {
        list-style: none;
        margin: 0 0 4px;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .ws-list li {
        display: flex;
        align-items: center;
        gap: 6px;
      }
      .ws-list .mono {
        font-family: var(--bo-font-mono);
      }
      .ws-dot {
        width: 9px;
        height: 9px;
        border-radius: 3px;
        flex: none;
      }
      .canvas-area {
        flex: 1;
        min-height: 0;
        padding: 8px;
      }
      .sheet {
        flex: none;
        border-top: 1px solid var(--bo-line);
        background: var(--bo-surface);
        max-height: 34vh;
        display: flex;
        flex-direction: column;
      }
      .sheet-head {
        all: unset;
        cursor: pointer;
        display: flex;
        align-items: center;
        gap: 8px;
        padding: 6px 14px;
        font-size: 12.5px;
        font-weight: 500;
      }
      .sheet-head:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .sheet-sum {
        color: var(--bo-ink-3);
        font-weight: 400;
      }
      .count {
        display: inline-flex;
        align-items: center;
        gap: 4px;
        padding: 0 8px;
        border-radius: 999px;
        font-size: 12px;
        line-height: 20px;
        font-weight: 500;
        font-variant-numeric: tabular-nums;
      }
      .count[data-sev='error'] {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .count[data-sev='warning'] {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .count.ok {
        background: var(--bo-done-soft);
        color: var(--bo-done-ink);
      }
      .count.zero {
        background: transparent;
        color: var(--bo-ink-3);
      }
      .issue-groups {
        overflow: auto;
        padding: 0 10px 10px;
        display: flex;
        flex-direction: column;
        gap: 6px;
      }
      .issue-group {
        border: 1px solid var(--bo-line);
        border-radius: 8px;
      }
      .group-head {
        all: unset;
        box-sizing: border-box;
        display: flex;
        align-items: center;
        gap: 8px;
        width: 100%;
        padding: 6px 10px;
        border-bottom: 1px solid var(--bo-line);
        background: var(--bo-surface-2);
        border-radius: 8px 8px 0 0;
        cursor: pointer;
        font-size: 12.5px;
      }
      .group-head.active {
        box-shadow: inset 3px 0 0 var(--bo-accent);
      }
      .group-head:disabled {
        cursor: default;
      }
      .group-head:hover:not(:disabled) .group-title {
        text-decoration: underline;
        text-underline-offset: 2px;
      }
      .group-head:focus-visible,
      .issue:focus-visible {
        outline: 2px solid var(--bo-accent);
        outline-offset: -2px;
      }
      .group-title {
        font-weight: 600;
      }
      .group-counts {
        display: inline-flex;
        gap: 4px;
      }
      .mini {
        min-width: 18px;
        padding: 0 5px;
        border-radius: 999px;
        font-size: 11px;
        line-height: 16px;
        font-weight: 600;
        text-align: center;
        font-variant-numeric: tabular-nums;
      }
      .mini[data-sev='error'] {
        background: var(--bo-blocked-soft);
        color: var(--bo-blocked-ink);
      }
      .mini[data-sev='warning'] {
        background: var(--bo-you-soft);
        color: var(--bo-you-ink);
      }
      .issue-list {
        list-style: none;
        margin: 0;
        padding: 2px 0;
      }
      .issue {
        all: unset;
        box-sizing: border-box;
        display: grid;
        grid-template-columns: 14px 56px minmax(0, 1fr);
        gap: 8px;
        align-items: baseline;
        width: 100%;
        padding: 4px 10px;
        font-size: 12.5px;
        cursor: pointer;
      }
      .issue:disabled {
        cursor: default;
      }
      .issue:hover:not(:disabled) {
        background: var(--bo-surface-2);
      }
      .sev {
        font-size: 11.5px;
        color: var(--bo-ink-3);
      }
      .where {
        font-size: 11.5px;
        color: var(--bo-ink-2);
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .msg .where {
        margin-right: 6px;
      }
      .jump {
        margin-left: auto;
        font-size: 12px;
        color: var(--bo-accent);
      }
      .none {
        padding: 6px 8px;
        margin: 0;
      }
      .history {
        display: inline-flex;
        gap: 2px;
      }
      .panel {
        padding: 12px 14px 24px;
      }
      .head2 {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
        font-weight: 600;
        margin-bottom: 8px;
      }
      .small {
        font-size: 12px;
      }
      .json-body {
        padding: 12px 16px;
        flex: 1;
        min-height: 0;
        display: flex;
        flex-direction: column;
      }
      .json-editor {
        flex: 1;
        min-height: 300px;
        resize: none;
      }
      @media (max-width: 1200px) {
        .body {
          grid-template-columns: 240px minmax(0, 1fr) 320px;
        }
      }
    `,
  ],
})
export class WorkflowDesignerPageComponent {
  private readonly workflowsApi = inject(WorkflowsApi);
  private readonly profilesApi = inject(ModelProfilesApi);
  private readonly library = inject(TaskLibraryApi);
  private readonly message = inject(NzMessageService);
  private readonly confirm = inject(ConfirmService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly identity = inject(IdentityService);
  private readonly pendingSaves = inject(PendingSavesService);
  private readonly crumbs = inject(BreadcrumbService);

  /** One of the two is bound from the route. */
  readonly projectId = input<string | undefined>();
  readonly workflowId = input<string | undefined>();
  /** Optional `?node=<nodeId>`: select that node once the workflow is loaded (Insights bottleneck). */
  readonly node = input<string | undefined>();

  readonly canvas = viewChild<WorkflowCanvasComponent>('canvas');

  readonly def = signal<WorkflowDefinitionInput>(structuredClone(EMPTY_DEFINITION));
  readonly existing = signal<WorkflowDefinition | null>(null);
  readonly palette = signal<WorkflowPalette | null>(null);
  readonly paletteLoading = signal(false);
  readonly profiles = signal<ModelProfile[]>([]);
  readonly skills = signal<Skill[]>([]);
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly duplicating = signal(false);
  readonly validating = signal(false);
  readonly serverValidation = signal<ValidationResult | null>(null);
  readonly selectedNodeId = signal<string | null>(null);
  readonly selectedEdgeId = signal<string | null>(null);
  readonly mode = signal<'designer' | 'json'>('designer');
  readonly jsonText = signal('');
  readonly jsonError = signal<string | null>(null);
  readonly dirty = signal(false);
  readonly persistedIds = signal<Set<string>>(new Set());
  readonly initialViewport = signal<WorkflowCanvas | null>(null);
  readonly modeOptions = [
    { label: $localize`:@@workflows.designer.mode.designer:Designer`, value: 'designer' },
    { label: 'JSON', value: 'json' },
  ];
  /** Undo / redo stacks of definitions (positions and edits that record history). */
  private readonly past = signal<WorkflowDefinitionInput[]>([]);
  private readonly future = signal<WorkflowDefinitionInput[]>([]);
  readonly canUndo = computed(() => !this.readOnly() && this.past().length > 0);
  readonly canRedo = computed(() => !this.readOnly() && this.future().length > 0);
  readonly undoKeys = formatKeys('mod+z').join(' ');
  readonly redoKeys = formatKeys('mod+shift+z').join(' ');
  readonly text = {
    undoTooltip: $localize`:@@workflows.designer.undoTooltip:Undo (${this.undoKeys}:keys:)`,
    redoTooltip: $localize`:@@workflows.designer.redoTooltip:Redo (${this.redoKeys}:keys:)`,
    hidePalette: $localize`:@@workflows.designer.hidePalette:Hide the palette`,
    showPalette: $localize`:@@workflows.designer.showPalette:Show the palette`,
    hideProperties: $localize`:@@workflows.designer.hideProperties:Hide properties`,
    showProperties: $localize`:@@workflows.designer.showProperties:Show properties`,
    saveVersion: $localize`:@@workflows.designer.saveVersion:Save as new version`,
    create: $localize`:@@workflows.designer.create:Create workflow`,
  };
  readonly errorsLabel = errorsLabel;
  readonly warningsLabel = warningsLabel;
  /** Positions were laid out on open (not yet saved). */
  readonly laidOutOnOpen = signal(false);
  private readonly validateNow$ = new Subject<void>();

  readonly resolvedProjectId = computed(() => this.projectId() ?? this.existing()?.projectId ?? '');
  /** `workflow.edit` on the workflow's project; without it the designer is a read-only viewer. */
  readonly canEdit = computed(() => {
    const projectId = this.resolvedProjectId();
    return !!projectId && this.identity.canInProject('workflow.edit', projectId);
  });
  readonly readOnly = computed(() => !this.canEdit());

  /** `unsavedChangesGuard`. */
  hasUnsavedChanges(): boolean {
    return this.dirty() && !this.readOnly();
  }
  readonly nodeIds = computed(() => this.def().nodes.map((n) => n.id));
  readonly selectedNode = computed(
    () => this.def().nodes.find((n) => n.id === this.selectedNodeId()) ?? null,
  );
  readonly selectedEdge = computed(
    () => this.def().edges.find((e) => e.id === this.selectedEdgeId()) ?? null,
  );
  readonly localValidation = computed(() =>
    validateLocally(this.def(), indexPalette(this.palette())),
  );
  readonly issues = computed(() =>
    mapValidation(mergeValidation(this.localValidation(), this.serverValidation())),
  );
  readonly counts = computed(() => issueCounts(this.issues()));
  /**
   * The validation sheet, grouped by node: workflow-level issues first, then one group per node in
   * canvas order (edge issues go to the node the edge leaves); errors before warnings in a group.
   */
  readonly issueGroups = computed<IssueGroup[]>(() => groupIssues(this.issues(), this.def()));
  readonly leftOpen = signal(readPanel('left'));
  readonly rightOpen = signal(readPanel('right'));
  readonly sheetOpen = signal(false);
  private readonly projectsStore = inject(ProjectsStore);
  private readonly projectsApi = inject(ProjectsApi);
  private readonly librarySourcesApi = inject(LibrarySourcesApi);
  private readonly workspacesApi = inject(ProjectWorkspacesApi);
  /** The project's repositories (ADR 0034) for the repositories of runner steps. */
  readonly projectRepositories = signal<ProjectRepository[]>([]);
  /** Library sources (ADR 0043) runner steps may attach. */
  readonly librarySources = signal<LibrarySource[]>([]);
  /** ADR 0044: run workspaces of the project steps may pick (configured in the project tab Workspaces). */
  readonly workspaces = signal<ProjectWorkspace[]>([]);
  readonly projectKey = computed(
    () =>
      this.projectsStore.byId().get(this.resolvedProjectId())?.key ??
      $localize`:@@workflows.designer.projectFallback:Project`,
  );

  readonly cleanLabel = computed(() => {
    if (this.readOnly()) return $localize`:@@workflows.designer.readOnlyClean:Read-only`;
    const w = this.existing();
    return w
      ? $localize`:@@workflows.designer.savedAs:Saved as v${w.version}:version:`
      : $localize`:@@workflows.designer.notSaved:Not saved yet`;
  });

  placesLabel(count: number): string {
    return icu(
      $localize`:@@workflows.designer.places:on {count, plural, one {# place} other {# places}}`,
      { count },
    );
  }

  fixLabel(count: number): string {
    return icu(
      $localize`:@@workflows.designer.draftOnSave:{count, plural, one {# error} other {# errors}}: saving keeps a draft, runs cannot start until fixed.`,
      { count },
    );
  }

  severityLabel(severity: 'error' | 'warning'): string {
    return severity === 'error'
      ? $localize`:@@workflows.designer.severity.error:error`
      : $localize`:@@workflows.designer.severity.warning:warning`;
  }

  togglePanel(side: 'left' | 'right'): void {
    const s = side === 'left' ? this.leftOpen : this.rightOpen;
    s.set(!s());
    try {
      localStorage.setItem(`backoffice.designer.${side}`, String(s()));
    } catch {
      // storage unavailable
    }
  }

  /** Validation sheet: select the node and bring it into view. */
  jumpTo(issue: { nodeId?: string | null }): void {
    if (!issue.nodeId) return;
    this.select(issue.nodeId);
    if (!this.rightOpen()) this.togglePanel('right');
    this.canvas()?.focusNode(issue.nodeId);
  }
  readonly nodeIssueEntries = computed(() =>
    Object.entries(this.issues().nodes).map(([id, i]) => ({
      id,
      errors: i.errors.length,
      warnings: i.warnings.length,
      first: i.errors[0]?.message ?? i.warnings[0]?.message ?? '',
    })),
  );

  constructor() {
    this.projectsStore.ensure();
    effect(() => {
      const pid = this.resolvedProjectId();
      const name = this.def().name;
      untracked(() => {
        if (!pid) return;
        this.projectsStore.setCurrent(pid);
        this.crumbs.set([
          { label: $localize`:@@workflows.designer.crumb.projects:Projects`, link: '/projects' },
          { label: this.projectKey(), link: ['/projects', pid], mono: true },
          {
            label:
              name ||
              (this.existing()
                ? $localize`:@@workflows.designer.crumb.workflow:Workflow`
                : $localize`:@@workflows.designer.crumb.new:New workflow`),
          },
        ]);
      });
    });
    inject(ShortcutsService).register(
      [
        {
          keys: '[',
          description: $localize`:@@workflows.designer.key.palette:Show or hide the palette`,
          group: designerGroup,
          handler: () => this.togglePanel('left'),
        },
        {
          keys: ']',
          description: $localize`:@@workflows.designer.key.properties:Show or hide properties`,
          group: designerGroup,
          handler: () => this.togglePanel('right'),
        },
        {
          keys: 'mod+z',
          description: $localize`:@@workflows.designer.key.undo:Undo`,
          group: designerGroup,
          handler: () => this.undo(),
          when: () => this.mode() === 'designer',
        },
        {
          keys: 'mod+shift+z',
          description: $localize`:@@workflows.designer.key.redo:Redo`,
          group: designerGroup,
          handler: () => this.redo(),
          when: () => this.mode() === 'designer',
        },
        {
          keys: 'mod+y',
          description: $localize`:@@workflows.designer.key.redo:Redo`,
          group: designerGroup,
          handler: () => this.redo(),
          when: () => this.mode() === 'designer',
          hidden: true,
        },
        {
          keys: 'mod+d',
          description: $localize`:@@workflows.designer.key.duplicate:Duplicate the selected node`,
          group: designerGroup,
          handler: () => this.duplicateSelected(this.selectedNodeId()!),
          when: () => this.canEditSelection(),
        },
        {
          keys: 'Delete',
          description: $localize`:@@workflows.designer.key.delete:Delete the selected node or edge`,
          group: designerGroup,
          handler: () => this.deleteSelected(),
          when: () =>
            this.mode() === 'designer' &&
            !this.readOnly() &&
            (!!this.selectedNodeId() || !!this.selectedEdgeId()),
        },
        ...(
          [
            [
              'ArrowRight',
              'right',
              $localize`:@@workflows.designer.key.next:Select the next node (first connected port)`,
            ],
            [
              'ArrowLeft',
              'left',
              $localize`:@@workflows.designer.key.previous:Select the previous node`,
            ],
            ['ArrowUp', 'up', $localize`:@@workflows.designer.key.above:Select the node above`],
            ['ArrowDown', 'down', $localize`:@@workflows.designer.key.below:Select the node below`],
          ] as const
        ).map(([keys, dir, description]) => ({
          keys,
          description,
          group: designerGroup,
          handler: () => this.canvas()?.navigate(dir),
          when: () => this.mode() === 'designer',
        })),
        {
          keys: 'Escape',
          description: $localize`:@@workflows.designer.key.clear:Clear the selection`,
          group: designerGroup,
          handler: () => this.select(null),
          when: () =>
            this.mode() === 'designer' && !!(this.selectedNodeId() || this.selectedEdgeId()),
          hidden: true,
        },
      ],
      this.destroyRef,
    );
    this.profilesApi.listCallable().subscribe((p) => this.profiles.set(p));

    effect(() => {
      const workflowId = this.workflowId();
      const projectId = this.projectId();
      untracked(() => {
        if (workflowId) this.loadExisting(workflowId);
        else {
          this.def.set(structuredClone(EMPTY_DEFINITION));
          this.resetHistory();
          this.loading.set(false);
          if (projectId) this.loadProjectData(projectId);
        }
      });
    });

    // Debounced server-side validation whenever the definition changes (+ explicit "Validate").
    merge(
      toObservable(this.def).pipe(
        debounceTime(600),
        map((d) => JSON.stringify(d)),
        distinctUntilChanged(),
      ),
      this.validateNow$.pipe(map(() => JSON.stringify(this.def()))),
    )
      .pipe(
        switchMap((json) => {
          const projectId = this.resolvedProjectId();
          const d = JSON.parse(json) as WorkflowDefinitionInput;
          if (!projectId || !d.nodes.length || this.readOnly()) return of(null);
          this.validating.set(true);
          return this.workflowsApi.validate(projectId, d).pipe(catchError(() => of(null)));
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.serverValidation.set(result);
        this.validating.set(false);
      });

    effect(() => {
      const d = this.def();
      if (!this.jsonError()) this.jsonText.set(JSON.stringify(d, null, 2));
    });
  }

  // ---------------------------------------------------------------- loading

  private loadExisting(workflowId: string): void {
    this.loading.set(true);
    this.workflowsApi.get(workflowId).subscribe({
      next: (w) => {
        this.existing.set(w);
        let d = normalizeDefinition(w);
        // No stored positions, or cards that would overlap: lay it out (kept with the next save).
        const relayout = needsLayout(d);
        if (relayout) d = autoLayout(d);
        this.laidOutOnOpen.set(relayout);
        this.def.set(d);
        this.persistedIds.set(new Set(d.nodes.map((n) => n.id)));
        this.initialViewport.set(relayout ? null : (w.canvas ?? null));
        this.resetHistory();
        this.dirty.set(false);
        this.loading.set(false);
        this.loadProjectData(w.projectId);
        const focus = this.node();
        if (focus && d.nodes.some((n) => n.id === focus)) this.select(focus);
      },
      error: () => this.loading.set(false),
    });
  }

  private loadProjectData(projectId: string): void {
    this.paletteLoading.set(true);
    this.workflowsApi.palette(projectId).subscribe({
      next: (p) => {
        this.palette.set(p);
        this.paletteLoading.set(false);
      },
      error: () => this.paletteLoading.set(false),
    });
    this.library
      .listSkills(projectId)
      .subscribe({ next: (s) => this.skills.set(s), error: () => undefined });
    this.librarySourcesApi
      .list()
      .subscribe({ next: (s) => this.librarySources.set(s), error: () => undefined });
    this.workspacesApi
      .list(projectId)
      .subscribe({ next: (w) => this.workspaces.set(w), error: () => undefined });
    // Fresh repositories for the step repository picker (ADR 0034); the store may be older.
    this.projectsApi.get(projectId).subscribe({
      next: (p) => this.projectRepositories.set(projectRepositories(p)),
      error: () =>
        this.projectRepositories.set(projectRepositories(this.projectsStore.byId().get(projectId))),
    });
  }

  // ---------------------------------------------------------------- definition mutations

  private lastRecord = 0;

  /**
   * Applies a new definition. `recordHistory`: true pushes an undo step; 'coalesce' pushes one only
   * when the previous step is older than a second (typing in the property panel is one step).
   */
  private commit(next: WorkflowDefinitionInput, recordHistory: boolean | 'coalesce' = true): void {
    if (this.readOnly()) return;
    const now = Date.now();
    if (recordHistory === 'coalesce') recordHistory = now - this.lastRecord > 1000;
    this.lastRecord = now;
    if (recordHistory) {
      this.past.update((p) => [...p, this.def()].slice(-MAX_HISTORY));
      this.future.set([]);
    }
    this.def.set(next);
    this.dirty.set(true);
  }

  readonly workspaceColor = workspaceColor;

  /** ADR 0044: how many steps of this workflow use the project workspace [key]. */
  workspaceStepsText(key: string): string {
    const n = membersOf(this.def(), key).length;
    return $localize`:@@workflows.designer.workspaceSteps:steps here: ${n}:count:`;
  }

  patchDef(changes: Partial<WorkflowDefinitionInput>): void {
    this.commit({ ...this.def(), ...changes }, 'coalesce');
  }

  issuesFor(id: string): Issues | null {
    return this.issues().nodes[id] ?? null;
  }

  issuesForEdge(id: string): Issues | null {
    return this.issues().edges[id] ?? null;
  }

  select(id: string | null): void {
    this.selectedEdgeId.set(null);
    this.selectedNodeId.set(id);
  }

  onNodeSelected(id: string | null): void {
    this.selectedNodeId.set(id);
    if (id) this.selectedEdgeId.set(null);
  }

  /** Click in the palette: the new card goes to the first free spot below the visible centre. */
  addFromPalette(pick: PalettePick): void {
    const center = this.canvas()?.viewportCenter() ?? { x: 100, y: 100 };
    const rects = [...(this.canvas()?.rects().values() ?? [])];
    const w = 240;
    const h = 150;
    const at = { x: Math.round(center.x - w / 2), y: Math.round(center.y - h / 2) };
    const clear = (y: number) =>
      !rects.some(
        (r) =>
          at.x < r.x + r.width + 24 &&
          r.x < at.x + w + 24 &&
          y < r.y + r.height + 24 &&
          r.y < y + h + 24,
      );
    for (let i = 0; i < 60 && !clear(at.y); i++) at.y += 40;
    this.addNode(pick, at);
  }

  onDrop(drop: PaletteDrop): void {
    this.addNode({ kind: drop.kind, templateKey: drop.templateKey }, drop.position);
  }

  private addNode(pick: PalettePick, position: Point): void {
    const d = this.def();
    if (pick.kind === 'START' && d.nodes.some((n) => n.kind === 'START')) {
      this.message.warning(
        $localize`:@@workflows.designer.oneStart:A workflow has exactly one START node`,
      );
      return;
    }
    const task = pick.templateKey
      ? (this.palette()?.tasks?.find((t) => t.key === pick.templateKey) ?? {
          key: pick.templateKey,
        })
      : null;
    const node = createNode(pick.kind, position, this.nodeIds(), task);
    this.commit({ ...d, nodes: [...d.nodes, node] });
    this.select(node.id);
    this.canvas()?.focus();
  }

  updateNode(id: string, next: WorkflowNode): void {
    // Form controls echo their value when they are (re)bound (nz-select in multiple mode does);
    // an echo is not an edit and must not loop back into the form or the undo history.
    const current = this.def().nodes.find((n) => n.id === id);
    if (current && JSON.stringify(current) === JSON.stringify(next)) return;
    this.commit(replaceNode(this.def(), id, next), 'coalesce');
    if (next.id !== id) this.selectedNodeId.set(next.id);
  }

  /** A drag is one undo step: history is recorded on its first move, not on every frame. */
  private dragging = false;

  onNodeMoved(e: { id: string; position: Point }): void {
    const next = moveNode(this.def(), e.id, e.position);
    if (next === this.def()) return;
    this.commit(next, !this.dragging);
    this.dragging = true;
  }

  onMoveEnded(): void {
    this.dragging = false;
  }

  connect(edge: Omit<WorkflowEdge, 'id'>): void {
    const d = this.def();
    const target = d.nodes.find((n) => n.id === edge.to);
    if (!target || target.kind === 'START' || target.kind === 'NOTE') return;
    this.commit({ ...d, edges: upsertEdge(d.edges, edge) });
  }

  patchEdge(id: string, changes: Partial<WorkflowEdge>): void {
    const d = this.def();
    this.commit(
      { ...d, edges: d.edges.map((e) => (e.id === id ? { ...e, ...changes } : e)) },
      'coalesce',
    );
  }

  deleteSelection(sel: { nodeIds: string[]; edgeIds: string[] }): void {
    let d = this.def();
    for (const id of sel.nodeIds) d = removeNode(d, id);
    for (const id of sel.edgeIds) d = { ...d, edges: removeEdge(d.edges, id) };
    if (d === this.def()) return;
    this.commit(d);
    if (sel.nodeIds.includes(this.selectedNodeId() ?? '')) this.selectedNodeId.set(null);
    if (sel.edgeIds.includes(this.selectedEdgeId() ?? '')) this.selectedEdgeId.set(null);
  }

  async deleteNode(id: string): Promise<void> {
    const edges = this.def().edges.filter((e) => e.from === id || e.to === id).length;
    if (edges > 0) {
      const ok = await this.confirm.danger(
        $localize`:@@workflows.designer.deleteNode.title:Delete node "${id}:id:"?`,
        icu(
          $localize`:@@workflows.designer.deleteNode.edges:{count, plural, other {# connected edge(s) are removed too.}}`,
          { count: edges },
        ),
        $localize`:@@workflows.designer.deleteNode.ok:Delete`,
      );
      if (!ok) return;
    }
    this.deleteSelection({ nodeIds: [id], edgeIds: [] });
  }

  duplicateSelected(id: string): void {
    const next = duplicateNode(this.def(), id);
    if (next === this.def()) return;
    this.commit(next);
    this.select(next.nodes[next.nodes.length - 1].id);
  }

  private canEditSelection(): boolean {
    return this.mode() === 'designer' && !this.readOnly() && !!this.selectedNode();
  }

  /** Keyboard delete: the selected node (with its edges; undo brings them back) or edge. */
  deleteSelected(): void {
    const node = this.selectedNodeId();
    const edge = this.selectedEdgeId();
    this.deleteSelection({ nodeIds: node ? [node] : [], edgeIds: !node && edge ? [edge] : [] });
  }

  undo(): void {
    const past = this.past();
    if (this.readOnly() || !past.length) return;
    this.future.update((f) => [this.def(), ...f].slice(0, MAX_HISTORY));
    this.past.set(past.slice(0, -1));
    this.restore(past[past.length - 1]);
  }

  redo(): void {
    const [next, ...rest] = this.future();
    if (this.readOnly() || !next) return;
    this.past.update((p) => [...p, this.def()].slice(-MAX_HISTORY));
    this.future.set(rest);
    this.restore(next);
  }

  private restore(d: WorkflowDefinitionInput): void {
    this.lastRecord = 0;
    this.def.set(d);
    this.dirty.set(true);
    if (!d.nodes.some((n) => n.id === this.selectedNodeId())) this.selectedNodeId.set(null);
    if (!d.edges.some((e) => e.id === this.selectedEdgeId())) this.selectedEdgeId.set(null);
  }

  private resetHistory(): void {
    this.past.set([]);
    this.future.set([]);
    this.lastRecord = 0;
  }

  /** "Tidy layout": the layered layout with the rendered card sizes, then the readable view. */
  layout(): void {
    const canvas = this.canvas();
    this.commit(
      autoLayout(this.def(), {
        sizes: canvas?.measuredSizes() ?? null,
        palette: indexPalette(this.palette()),
      }),
    );
    setTimeout(() => this.canvas()?.resetView(), 60);
  }

  onViewport(v: WorkflowCanvas): void {
    // Viewport changes are persisted with the definition but do not mark it dirty.
    this.def.update((d) => ({ ...d, canvas: v }));
  }

  validateNow(): void {
    this.validateNow$.next();
  }

  loadTemplate(which: 'issue' | 'review' | 'empty'): void {
    const tpl =
      which === 'issue'
        ? issueToMergeRequestTemplate()
        : which === 'review'
          ? ciCodeReviewTemplate()
          : structuredClone(EMPTY_DEFINITION);
    const laid = autoLayout(tpl, { palette: indexPalette(this.palette()) });
    this.commit({ ...laid, name: this.def().name || laid.name, canvas: null });
    this.select(null);
    setTimeout(() => this.canvas()?.resetView(), 120);
  }

  // ---------------------------------------------------------------- JSON view

  setMode(value: string | number): void {
    this.mode.set(value === 'json' ? 'json' : 'designer');
    if (value === 'json' && !this.jsonError())
      this.jsonText.set(JSON.stringify(this.def(), null, 2));
  }

  onJsonChange(text: string): void {
    this.jsonText.set(text);
    try {
      const parsed = JSON.parse(text) as Partial<WorkflowDefinitionInput>;
      if (
        !parsed ||
        typeof parsed !== 'object' ||
        !Array.isArray(parsed.nodes) ||
        !Array.isArray(parsed.edges)
      )
        throw new Error(
          $localize`:@@workflows.designer.json.shape:expected an object with "nodes" and "edges" arrays`,
        );
      this.jsonError.set(null);
      let d = normalizeDefinition(parsed);
      if (needsLayout(d)) d = autoLayout(d);
      this.commit(d);
      if (!d.nodes.some((n) => n.id === this.selectedNodeId())) this.selectedNodeId.set(null);
    } catch (e) {
      this.jsonError.set(
        $localize`:@@workflows.designer.json.invalid:Invalid JSON: ${(e as Error).message}:error:`,
      );
    }
  }

  formatJson(): void {
    this.jsonText.set(JSON.stringify(this.def(), null, 2));
  }

  async copyJson(): Promise<void> {
    try {
      await navigator.clipboard.writeText(JSON.stringify(this.def(), null, 2));
      this.message.success($localize`:@@workflows.designer.copied:Definition copied to clipboard`);
    } catch {
      this.message.warning(
        $localize`:@@workflows.designer.clipboardUnavailable:Clipboard unavailable; select the text and copy manually`,
      );
    }
  }

  // ---------------------------------------------------------------- persistence

  save(): void {
    const d = this.def();
    this.saving.set(true);
    const existing = this.existing();
    const req = existing
      ? this.workflowsApi.update(existing.id, d)
      : this.workflowsApi.create(this.projectId()!, d);
    this.pendingSaves.track(req).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.dirty.set(false);
        this.persistedIds.set(new Set(saved.nodes.map((n) => n.id)));
        if (saved.draft) {
          this.message.warning(
            $localize`:@@workflows.designer.savedDraft:Saved as a draft (v${saved.version}:version:): runs cannot start until the errors are fixed`,
          );
        }
        if (existing) {
          this.existing.set(saved);
          if (!saved.draft)
            this.message.success(
              $localize`:@@workflows.designer.saved:Workflow saved as v${saved.version}:version:`,
            );
        } else {
          this.message.success($localize`:@@workflows.designer.created:Workflow created`);
          this.router.navigate(['/workflows', saved.id, 'edit'], { replaceUrl: true });
        }
      },
      error: () => this.saving.set(false),
    });
  }

  duplicate(w: WorkflowDefinition): void {
    this.duplicating.set(true);
    this.workflowsApi.duplicate(w.id).subscribe({
      next: (copy) => {
        this.duplicating.set(false);
        this.message.success(
          $localize`:@@workflows.designer.duplicated:Copy "${copy.name}:name:" created`,
        );
        this.router.navigate(['/workflows', copy.id, 'edit']);
      },
      error: () => this.duplicating.set(false),
    });
  }
}

const designerGroup = $localize`:@@workflows.designer.key.group:Designer`;

function errorsLabel(count: number): string {
  return icu(
    $localize`:@@workflows.designer.errors:{count, plural, one {# error} other {# errors}}`,
    { count },
  );
}

function warningsLabel(count: number): string {
  return icu(
    $localize`:@@workflows.designer.warnings:{count, plural, one {# warning} other {# warnings}}`,
    { count },
  );
}

function readPanel(side: 'left' | 'right'): boolean {
  try {
    return localStorage.getItem(`backoffice.designer.${side}`) !== 'false';
  } catch {
    return true;
  }
}
